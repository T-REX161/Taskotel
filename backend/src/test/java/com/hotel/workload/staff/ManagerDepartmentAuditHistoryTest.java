package com.hotel.workload.staff;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.workload.assignment.Assignment;
import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.ScopeFixtures;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.task.Priority;
import com.hotel.workload.task.Task;
import com.hotel.workload.task.TaskRepository;
import com.hotel.workload.task.TaskStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The Manager department audit history: every Admin-initiated change is recorded (first assignment, transfer, removal), a failed or no-op
 * request records nothing, and only an Admin can read the history. Real security chain and database (H2); every test rolls back.
 * Departments AUD_A and AUD_B; an Admin; a Manager (aud_m1) in A; a Manager (aud_un) with no department; an ordinary Staff member in A.
 */
@SpringBootTest
@Transactional
public class ManagerDepartmentAuditHistoryTest {

    private static final String A = "AUD_A";
    private static final String B = "AUD_B";

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private ManagerDepartmentChangeRepository changeRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mockMvc;
    private Department deptA;
    private Department deptB;
    private User admin;
    private Staff m1;
    private Staff unassigned;
    private Staff s1;

    @BeforeEach
    public void setUp() {
        SecurityContextHolder.clearContext();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        ScopeFixtures fixtures = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder);
        deptA = fixtures.department(A);
        deptB = fixtures.department(B);
        admin = fixtures.account("aud_admin", Role.ADMIN, true);
        m1 = fixtures.manager("aud_m1", deptA);
        unassigned = fixtures.manager("aud_un", deptA);
        unassigned.setDepartment(null);
        unassigned = staffRepository.saveAndFlush(unassigned);
        s1 = fixtures.staff("aud_s1", deptA);
    }

    @AfterEach
    public void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private MvcResult call(String username, String role, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(user(username).roles(role)).contentType(MediaType.APPLICATION_JSON)).andReturn();
    }

    private static String assignUrl(Staff manager) {
        return "/api/users/" + manager.getUser().getId() + "/manager-department";
    }

    private int assign(Staff manager, String code) throws Exception {
        String body = code == null ? "{}" : "{\"department\":\"" + code + "\"}";
        return call("aud_admin", "ADMIN", put(assignUrl(manager)).content(body)).getResponse().getStatus();
    }

    /** The ordinary Staff edit; department: a quoted code, or null / "" to clear. */
    private int edit(String caller, String role, Staff staff, String departmentJson) throws Exception {
        String body = "{\"fullName\": \"" + staff.getName() + "\", \"email\": \"" + staff.getEmail() + "\", \"phone\": \"555-0100\", \"department\": "
                + departmentJson + ", \"availability\": \"AVAILABLE\", \"active\": true}";
        return call(caller, role, put("/api/staff/" + staff.getId()).content(body)).getResponse().getStatus();
    }

    private static String q(String code) {
        return "\"" + code + "\"";
    }

    private long total() {
        changeRepository.flush();
        return changeRepository.count();
    }

    private List<ManagerDepartmentChange> changesOf(Staff manager) {
        changeRepository.flush();
        return changeRepository.findHistory(manager.getUser().getId());
    }

    private Staff reload(Staff staff) {
        staffRepository.flush();
        return staffRepository.findById(staff.getId()).orElseThrow();
    }

    private JsonNode history(Staff manager) throws Exception {
        MvcResult result = call("aud_admin", "ADMIN", get(assignUrl(manager) + "/history"));
        assertEquals(200, result.getResponse().getStatus());
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private void activeWorkFor(Staff staff) {
        Task task = new Task();
        task.setTitle("Active work");
        task.setPriority(Priority.MEDIUM);
        task.setStatus(TaskStatus.ASSIGNED);
        task.setEstimatedDuration(30);
        task.setDueDate(LocalDate.of(2027, 1, 1));
        task.setDepartment(deptA);
        task.setCreatedBy(admin);
        task = taskRepository.saveAndFlush(task);
        Assignment assignment = new Assignment();
        assignment.setTask(task);
        assignment.setStaff(staff);
        assignmentRepository.saveAndFlush(assignment);
    }

    // ── recording ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("First assignment (Unassigned to a department) creates an audit record with no previous department, through both the dedicated operation and the Staff edit")
    void firstAssignmentIsRecorded() throws Exception {
        long before = total();
        assertEquals(200, assign(unassigned, A));
        assertEquals(before + 1, total());
        ManagerDepartmentChange change = changesOf(unassigned).get(0);
        assertNull(change.getPreviousDepartmentId());
        assertNull(change.getPreviousDepartmentCode());
        assertNull(change.getPreviousDepartmentName());
        assertEquals(deptA.getId(), change.getNewDepartmentId());
        assertEquals(A, change.getNewDepartmentCode());
        assertEquals(deptA.getName(), change.getNewDepartmentName());
        assertEquals(admin.getId(), change.getChangedByUserId());
        assertEquals("aud_admin", change.getChangedByUsername());
        assertEquals(ManagerDepartmentChange.SOURCE_ASSIGNMENT, change.getChangeSource());
        assertEquals(unassigned.getUser().getId(), change.getManagerUserId());
        assertEquals(unassigned.getId(), change.getStaffId());
        assertNotNull(change.getChangedAt());

        // The same through the ordinary Staff edit
        Staff other = staffRepository.saveAndFlush(managerWithoutDepartment("aud_un2"));
        assertEquals(200, edit("aud_admin", "ADMIN", other, q(B)));
        ManagerDepartmentChange viaEdit = changesOf(other).get(0);
        assertNull(viaEdit.getPreviousDepartmentCode());
        assertEquals(B, viaEdit.getNewDepartmentCode());
        assertEquals(ManagerDepartmentChange.SOURCE_STAFF_EDIT, viaEdit.getChangeSource());
    }

    private Staff managerWithoutDepartment(String username) {
        ScopeFixtures fixtures = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder);
        Staff staff = fixtures.manager(username, deptA);
        staff.setDepartment(null);
        return staffRepository.saveAndFlush(staff);
    }

    @Test
    @DisplayName("A transfer (A to B) creates an audit record with both departments, through both the dedicated operation and the Staff edit")
    void transferIsRecorded() throws Exception {
        assertEquals(200, assign(m1, B));
        ManagerDepartmentChange change = changesOf(m1).get(0);
        assertEquals(A, change.getPreviousDepartmentCode());
        assertEquals(B, change.getNewDepartmentCode());
        assertEquals(ManagerDepartmentChange.SOURCE_ASSIGNMENT, change.getChangeSource());

        assertEquals(200, edit("aud_admin", "ADMIN", m1, q(A)));
        ManagerDepartmentChange viaEdit = changesOf(m1).get(0);
        assertEquals(B, viaEdit.getPreviousDepartmentCode());
        assertEquals(A, viaEdit.getNewDepartmentCode());
        assertEquals(ManagerDepartmentChange.SOURCE_STAFF_EDIT, viaEdit.getChangeSource());
        assertEquals(2, changesOf(m1).size());
    }

    @Test
    @DisplayName("A removal (department to Unassigned) creates an audit record with no new department, and the Manager loses access")
    void removalIsRecorded() throws Exception {
        for (String blank : List.of("null", "\"\"")) {
            Staff manager = staffRepository.saveAndFlush(managerInDepartment("aud_rm" + blank.length()));
            long before = total();
            assertEquals(200, call(manager.getUser().getUsername(), "MANAGER", get("/api/staff")).getResponse().getStatus(), "access before removal");
            assertEquals(200, edit("aud_admin", "ADMIN", manager, blank));
            assertNull(reload(manager).getDepartment());
            assertEquals(before + 1, total());
            ManagerDepartmentChange change = changesOf(manager).get(0);
            assertEquals(A, change.getPreviousDepartmentCode());
            assertEquals(deptA.getId(), change.getPreviousDepartmentId());
            assertNull(change.getNewDepartmentId());
            assertNull(change.getNewDepartmentCode());
            assertNull(change.getNewDepartmentName());
            assertEquals("aud_admin", change.getChangedByUsername());
            assertEquals(ManagerDepartmentChange.SOURCE_STAFF_EDIT, change.getChangeSource());
            assertEquals(403, call(manager.getUser().getUsername(), "MANAGER", get("/api/staff")).getResponse().getStatus(), "no department access after removal");
        }
    }

    private Staff managerInDepartment(String username) {
        return new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder).manager(username, deptA);
    }

    // ── nothing recorded ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Failed changes create no audit record: unknown or inactive department, a removal blocked by active work, a Manager or Staff caller, an unknown Manager")
    void failedChangesCreateNoRecord() throws Exception {
        long before = total();
        assertEquals(400, assign(m1, "NO_SUCH_DEPARTMENT"));
        deptB.setActive(false);
        departmentRepository.saveAndFlush(deptB);
        assertEquals(400, assign(m1, B));
        assertEquals(400, assign(unassigned, B));
        assertEquals(400, assign(unassigned, null), "no department in the request");

        activeWorkFor(m1);
        assertEquals(409, edit("aud_admin", "ADMIN", m1, "null"), "removal with active assignments");
        assertEquals(A, reload(m1).getDepartment().getCode());

        assertEquals(403, call("aud_m1", "MANAGER", put(assignUrl(m1)).content("{\"department\":\"" + A + "\"}")).getResponse().getStatus());
        assertEquals(403, edit("aud_m1", "MANAGER", m1, "null"));
        assertEquals(403, call("aud_s1", "STAFF", put(assignUrl(m1)).content("{\"department\":\"" + A + "\"}")).getResponse().getStatus());
        assertEquals(404, call("aud_admin", "ADMIN", put("/api/users/" + java.util.UUID.randomUUID() + "/manager-department").content("{\"department\":\"" + A + "\"}"))
                .getResponse().getStatus());
        assertEquals(before, total());
        assertNull(reload(unassigned).getDepartment(), "unchanged");
    }

    @Test
    @DisplayName("No-op requests create no audit record: the same department, clearing an already-empty department, an edit that leaves the department alone")
    void noOpChangesCreateNoRecord() throws Exception {
        long before = total();
        assertEquals(409, assign(m1, A), "the same department is refused");
        assertEquals(200, edit("aud_admin", "ADMIN", m1, q(A)), "an edit that keeps the department");
        assertEquals(200, edit("aud_admin", "ADMIN", unassigned, "null"), "clearing what is already empty");
        assertEquals(200, edit("aud_admin", "ADMIN", unassigned, "\"\""));
        assertEquals(before, total());
    }

    @Test
    @DisplayName("Only a Manager department change is audited: Staff and Admin department changes write no record")
    void onlyManagerChangesAreAudited() throws Exception {
        long before = total();
        assertEquals(200, edit("aud_admin", "ADMIN", s1, q(B)));
        assertEquals(200, edit("aud_admin", "ADMIN", s1, "null"));
        assertEquals(before, total());
    }

    // ── viewing ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Only an Admin can read the history: Manager (also for their own record), Staff and anonymous are refused, and nothing leaks")
    void onlyAdminsCanReadHistory() throws Exception {
        assertEquals(200, assign(m1, B));
        String url = assignUrl(m1) + "/history";
        assertEquals(403, call("aud_m1", "MANAGER", get(url)).getResponse().getStatus());
        assertEquals(403, call("aud_un", "MANAGER", get(url)).getResponse().getStatus());
        assertEquals(403, call("aud_s1", "STAFF", get(url)).getResponse().getStatus());
        assertEquals(401, mockMvc.perform(get(url)).andReturn().getResponse().getStatus());
        assertEquals(200, call("aud_admin", "ADMIN", get(url)).getResponse().getStatus());
    }

    @Test
    @DisplayName("The history API returns null for a missing side (shown as Unassigned by the page); existing records stay readable, newest first")
    void historyShowsNullSidesAndKeepsOlderRecords() throws Exception {
        assertEquals(200, assign(unassigned, A));
        assertEquals(200, assign(unassigned, B));
        assertEquals(200, edit("aud_admin", "ADMIN", unassigned, "null"));

        JsonNode history = history(unassigned);
        assertEquals(3, history.size());
        // newest first: removal, transfer, first assignment
        assertTrue(history.get(0).get("newDepartmentCode").isNull() && history.get(0).get("newDepartmentName").isNull());
        assertEquals(B, history.get(0).get("previousDepartmentCode").asText());
        assertEquals(A, history.get(1).get("previousDepartmentCode").asText());
        assertEquals(B, history.get(1).get("newDepartmentCode").asText());
        assertTrue(history.get(2).get("previousDepartmentCode").isNull() && history.get(2).get("previousDepartmentName").isNull());
        assertEquals(A, history.get(2).get("newDepartmentCode").asText());
        for (JsonNode record : history) {
            assertEquals("aud_admin", record.get("changedByUsername").asText());
            assertTrue(record.hasNonNull("changedAt") && record.hasNonNull("changeSource"));
        }

        // A record written before V20 (both sides present) is unchanged and readable beside the new kinds
        assertEquals(200, assign(m1, B));
        JsonNode older = history(m1);
        assertEquals(1, older.size());
        assertEquals(A, older.get(0).get("previousDepartmentCode").asText());
        assertEquals(B, older.get(0).get("newDepartmentCode").asText());
    }

    @Test
    @DisplayName("The department change and its audit record are one unit: a Manager's department and the record always agree")
    void departmentAndRecordAgree() throws Exception {
        assertEquals(200, assign(unassigned, B));
        assertEquals(B, reload(unassigned).getDepartment().getCode());
        assertEquals(B, changesOf(unassigned).get(0).getNewDepartmentCode());
        assertEquals(200, edit("aud_admin", "ADMIN", unassigned, "null"));
        assertNull(reload(unassigned).getDepartment());
        assertNull(changesOf(unassigned).get(0).getNewDepartmentCode());
    }
}
