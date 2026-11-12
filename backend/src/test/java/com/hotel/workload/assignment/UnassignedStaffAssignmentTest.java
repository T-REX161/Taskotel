package com.hotel.workload.assignment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.ScopeFixtures;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * A Staff member with no department is never an assignment target (single, several at once, or reassignment), and is left out of the
 * workload lists and the operational dashboard counts. Real security chain and database (H2); every test rolls back.
 */
@SpringBootTest
@Transactional
public class UnassignedStaffAssignmentTest {

    private static final String A = "UNA_A";

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mockMvc;
    private ScopeFixtures fixtures;
    private Department deptA;
    private User admin;
    private Staff s1;
    private Staff s2;
    private Staff un;
    private Task task;
    private Task taskWithoutDepartment;

    @BeforeEach
    public void setUp() {
        SecurityContextHolder.clearContext();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        fixtures = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder);
        deptA = fixtures.department(A);
        admin = fixtures.account("una_admin", Role.ADMIN, true);
        fixtures.manager("una_mgr", deptA);
        s1 = fixtures.staff("una_s1", deptA);
        s2 = fixtures.staff("una_s2", deptA);
        un = unassigned("una_un");
        task = task("Needs people", deptA, 40);
        taskWithoutDepartment = task("No department task", null, 25);
    }

    @AfterEach
    public void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Staff unassigned(String username) {
        Staff staff = fixtures.staff(username, deptA);
        staff.setDepartment(null);
        return staffRepository.saveAndFlush(staff);
    }

    private Task task(String title, Department department, int minutes) {
        Task t = new Task();
        t.setTitle(title);
        t.setPriority(Priority.MEDIUM);
        t.setStatus(TaskStatus.PENDING);
        t.setEstimatedDuration(minutes);
        t.setDueDate(LocalDate.of(2027, 1, 1));
        t.setDepartment(department);
        t.setCreatedBy(admin);
        return taskRepository.saveAndFlush(t);
    }

    private MvcResult call(String username, String role, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(user(username).roles(role)).contentType(MediaType.APPLICATION_JSON)).andReturn();
    }

    private MvcResult assignOne(String caller, String role, Task t, Staff s) throws Exception {
        return call(caller, role, post("/api/assignments").content("{\"taskId\":\"" + t.getId() + "\",\"staffId\":\"" + s.getId() + "\"}"));
    }

    private MvcResult assignMany(Task t, Staff... staff) throws Exception {
        StringBuilder ids = new StringBuilder();
        for (Staff s : staff) ids.append(ids.length() == 0 ? "" : ",").append("\"").append(s.getId()).append("\"");
        return call("una_admin", "ADMIN", post("/api/assignments").content("{\"taskId\":\"" + t.getId() + "\",\"staffIds\":[" + ids + "]}"));
    }

    private long rows(Task t) {
        assignmentRepository.flush();
        return assignmentRepository.findAllByTaskId(t.getId()).size();
    }

    private TaskStatus status(Task t) {
        taskRepository.flush();
        return taskRepository.findById(t.getId()).orElseThrow().getStatus();
    }

    private JsonNode json(MvcResult r) throws Exception {
        return mapper.readTree(r.getResponse().getContentAsString());
    }

    private String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }

    // ── assignment ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A single assignment to unassigned Staff is 400 with a clear message, for a task in a department and for a task with none; nothing is created")
    void singleAssignmentIsRefused() throws Exception {
        for (Task t : List.of(task, taskWithoutDepartment)) {
            MvcResult result = assignOne("una_admin", "ADMIN", t, un);
            assertEquals(400, result.getResponse().getStatus(), body(result));
            assertTrue(body(result).contains("has no department and cannot be assigned tasks"), body(result));
            assertEquals(0, rows(t));
            assertEquals(TaskStatus.PENDING, status(t));
        }
    }

    @Test
    @DisplayName("A multi-staff assignment that includes unassigned Staff is refused as a whole: no partial rows, the task stays Pending, and the valid members can still be assigned afterwards")
    void multiAssignmentIsAtomic() throws Exception {
        MvcResult result = assignMany(task, s1, un, s2);
        assertEquals(400, result.getResponse().getStatus(), body(result));
        assertTrue(body(result).contains("has no department") && body(result).contains(un.getName()), "the person is named: " + body(result));
        assertEquals(0, rows(task), "not even the valid members were assigned");
        assertEquals(TaskStatus.PENDING, status(task));
        MvcResult retry = assignMany(task, s1, s2);
        assertEquals(201, retry.getResponse().getStatus(), body(retry));
        assertEquals(2, rows(task));
    }

    @Test
    @DisplayName("A reassignment to unassigned Staff is 400 and the assignment stays with its holder")
    void reassignmentIsRefused() throws Exception {
        MvcResult created = assignOne("una_admin", "ADMIN", task, s1);
        assertEquals(201, created.getResponse().getStatus(), body(created));
        String assignmentId = json(created).get("id").asText();
        MvcResult moved = call("una_admin", "ADMIN", put("/api/assignments/" + assignmentId).content("{\"staffId\":\"" + un.getId() + "\"}"));
        assertEquals(400, moved.getResponse().getStatus(), body(moved));
        assertTrue(body(moved).contains("has no department and cannot be assigned tasks"));
        assertEquals(s1.getId(), assignmentRepository.findAllByTaskId(task.getId()).get(0).getStaff().getId());
    }

    @Test
    @DisplayName("A Manager cannot reach unassigned Staff either: 404, as for a person who does not exist")
    void managerGetsNotFound() throws Exception {
        assertEquals(404, assignOne("una_mgr", "MANAGER", task, un).getResponse().getStatus());
        assertEquals(0, rows(task));
    }

    @Test
    @DisplayName("Assigned Staff are unaffected: single, several and reassignment still work for people with a department")
    void existingBehaviourUnchanged() throws Exception {
        assertEquals(201, assignOne("una_admin", "ADMIN", task, s1).getResponse().getStatus());
        Task second = task("Second", deptA, 15);
        assertEquals(201, assignMany(second, s2).getResponse().getStatus());
        Staff s3 = fixtures.staff("una_s3", deptA);
        String assignmentId = assignmentRepository.findAllByTaskId(task.getId()).get(0).getId().toString();
        assertEquals(200, call("una_admin", "ADMIN", put("/api/assignments/" + assignmentId).content("{\"staffId\":\"" + s3.getId() + "\"}")).getResponse().getStatus());
    }

    // ── workload and dashboard ──────────────────────────────────────────────────────────────

    private JsonNode workload() throws Exception {
        return json(call("una_admin", "ADMIN", get("/api/workload")));
    }

    private boolean listed(JsonNode workload, Staff staff) {
        for (JsonNode row : workload) if (row.get("staffId").asText().equals(staff.getId().toString())) return true;
        return false;
    }

    private JsonNode summary() throws Exception {
        return json(call("una_admin", "ADMIN", get("/api/dashboard/summary")));
    }

    @Test
    @DisplayName("The Admin workload lists people with a department and leaves unassigned Staff out; the Staff member's own workload still works")
    void workloadExcludesUnassignedStaff() throws Exception {
        JsonNode all = workload();
        assertTrue(listed(all, s1) && listed(all, s2));
        assertFalse(listed(all, un), "unassigned Staff are not in the Admin workload");

        MvcResult own = call("una_un", "STAFF", get("/api/workload"));
        assertEquals(200, own.getResponse().getStatus(), body(own));
        assertEquals(1, json(own).size(), "their own row is still available to them");
        assertEquals(un.getId().toString(), json(own).get(0).get("staffId").asText());

        JsonNode manager = json(call("una_mgr", "MANAGER", get("/api/workload")));
        assertFalse(listed(manager, un), "and never in a Manager's department workload");
    }

    @Test
    @DisplayName("Operational dashboard counts leave unassigned Staff out (total, available, inactive), and the workload buckets add up to the staff total")
    void dashboardCountsExcludeUnassignedStaff() throws Exception {
        JsonNode before = summary();
        unassigned("una_extra");                 // another unassigned Staff member: nothing changes
        Staff inactive = unassigned("una_inactive");
        inactive.setActive(false);
        staffRepository.saveAndFlush(inactive);   // inactive and unassigned: not counted as inactive either
        Staff unavailable = unassigned("una_unavailable");
        unavailable.setAvailability(com.hotel.workload.staff.Availability.UNAVAILABLE);
        staffRepository.saveAndFlush(unavailable);
        JsonNode after = summary();
        for (String field : List.of("totalStaff", "availableStaff", "unavailableStaff", "inactiveStaff")) {
            assertEquals(before.get(field).asInt(), after.get(field).asInt(), field + " is unchanged by unassigned Staff");
        }
        assertEquals(after.get("totalStaff").asInt(),
                after.get("lowWorkloadStaff").asInt() + after.get("normalWorkloadStaff").asInt() + after.get("highWorkloadStaff").asInt() + after.get("atRiskStaff").asInt(),
                "the totals and the workload buckets agree");

        // The counts do move for a person who has a department (so the checks above are meaningful), and move back when it is removed
        Staff extra = fixtures.staff("una_counted", deptA);
        assertEquals(after.get("totalStaff").asInt() + 1, summary().get("totalStaff").asInt());
        assertEquals(after.get("availableStaff").asInt() + 1, summary().get("availableStaff").asInt());
        extra.setDepartment(null);
        staffRepository.saveAndFlush(extra);
        assertEquals(after.get("totalStaff").asInt(), summary().get("totalStaff").asInt());
        JsonNode finalSummary = summary();
        assertEquals(finalSummary.get("totalStaff").asInt(),
                finalSummary.get("lowWorkloadStaff").asInt() + finalSummary.get("normalWorkloadStaff").asInt()
                        + finalSummary.get("highWorkloadStaff").asInt() + finalSummary.get("atRiskStaff").asInt());
    }

    @Test
    @DisplayName("A Manager's dashboard counts are unaffected: unassigned Staff are in no department")
    void managerDashboardUnaffected() throws Exception {
        JsonNode before = json(call("una_mgr", "MANAGER", get("/api/dashboard/summary")));
        unassigned("una_more");
        JsonNode after = json(call("una_mgr", "MANAGER", get("/api/dashboard/summary")));
        assertEquals(before.get("totalStaff").asInt(), after.get("totalStaff").asInt());
    }
}
