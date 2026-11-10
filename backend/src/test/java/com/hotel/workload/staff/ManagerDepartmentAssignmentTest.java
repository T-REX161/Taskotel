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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Step 2H: the Admin-only operation that assigns or changes a Manager department, and its audit history. Real security chain, real
 * (H2) database; every test rolls back.
 *
 * <p>Fixture: departments MDA_ALPHA, MDA_BETA and MDA_CLOSED (inactive). Managers m1 and m2 in ALPHA, m3 in BETA; ordinary Staff
 * s1 in ALPHA; an Admin account; a Manager with no Staff record.
 */
@SpringBootTest
@Transactional
public class ManagerDepartmentAssignmentTest {

    private static final String ALPHA = "MDA_ALPHA";
    private static final String BETA = "MDA_BETA";
    private static final String CLOSED = "MDA_CLOSED";

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private ManagerDepartmentChangeRepository changeRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ManagerDepartmentService service;
    @Autowired private StaffService staffService;

    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mockMvc;

    private Department alpha, beta, closed;
    private Staff m1, m2, m3, s1;
    private User admin, managerWithoutStaff;

    @BeforeEach
    public void setUp() {
        // Start from an anonymous thread: another test class may have left a signed-in user in the security context
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        org.springframework.security.test.context.TestSecurityContextHolder.clearContext();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        ScopeFixtures fixtures = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder);
        alpha = fixtures.department(ALPHA);
        beta = fixtures.department(BETA);
        closed = fixtures.department(CLOSED);
        closed.setActive(false);
        departmentRepository.saveAndFlush(closed);

        admin = fixtures.account("mda_admin", Role.ADMIN, true);
        m1 = fixtures.manager("mda_m1", alpha);
        m2 = fixtures.manager("mda_m2", alpha);
        m3 = fixtures.manager("mda_m3", beta);
        s1 = fixtures.staff("mda_s1", alpha);
        managerWithoutStaff = fixtures.account("mda_m_nostaff", Role.MANAGER, true);
    }

    @AfterEach
    public void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String url(User target) {
        return "/api/users/" + target.getId() + "/manager-department";
    }

    private MvcResult call(String username, String role, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(user(username).roles(role)).contentType(MediaType.APPLICATION_JSON)).andReturn();
    }

    private int status(String username, String role, MockHttpServletRequestBuilder request) throws Exception {
        return call(username, role, request).getResponse().getStatus();
    }

    private int assign(String username, String role, User target, String departmentCode) throws Exception {
        String body = departmentCode == null ? "{}" : "{\"department\":\"" + departmentCode + "\"}";
        return status(username, role, put(url(target)).content(body));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private Staff reload(Staff staff) {
        staffRepository.flush();
        return staffRepository.findById(staff.getId()).orElseThrow();
    }

    private String departmentOf(Staff staff) {
        return reload(staff).getDepartment().getCode();
    }

    private List<ManagerDepartmentChange> changesOf(Staff manager) {
        changeRepository.flush();
        return changeRepository.findHistory(manager.getUser().getId());
    }

    private long totalChanges() {
        changeRepository.flush();
        return changeRepository.count();
    }

    private Task task(Department department, TaskStatus status, String title) {
        Task task = new Task();
        task.setTitle(title);
        task.setPriority(Priority.MEDIUM);
        task.setStatus(status);
        task.setDepartment(department);
        task.setCreatedBy(admin);
        return taskRepository.saveAndFlush(task);
    }

    private void assignTo(Task task, Staff staff) {
        Assignment assignment = new Assignment();
        assignment.setTask(task);
        assignment.setStaff(staff);
        assignmentRepository.saveAndFlush(assignment);
    }

    private String updateBody(Staff staff, String departmentCode) {
        return """
                {"fullName": "%s", "email": "%s", "phone": "555-0100", "department": "%s", "availability": "AVAILABLE", "active": true}
                """.formatted(staff.getName(), staff.getEmail(), departmentCode);
    }

    // ── 1. Admin assigns and changes ─────────────────────────────────────────

    @Test
    @DisplayName("Admin changes a Manager department: the Staff record moves and a complete audit record is written")
    public void adminChangesDepartmentAndAuditIsWritten() throws Exception {
        long before = totalChanges();
        MvcResult result = call("mda_admin", "ADMIN", put(url(m1.getUser())).content("{\"department\":\"" + BETA + "\"}"));
        assertEquals(200, result.getResponse().getStatus());

        JsonNode body = json(result);
        assertEquals(BETA, body.get("departmentCode").asText());
        assertEquals(m1.getId().toString(), body.get("staffId").asText());
        assertTrue(body.get("valid").asBoolean());
        assertEquals(BETA, departmentOf(m1));

        assertEquals(before + 1, totalChanges());
        ManagerDepartmentChange change = changesOf(m1).get(0);
        assertEquals(m1.getUser().getId(), change.getManagerUserId());
        assertEquals("mda_m1", change.getManagerUsername());
        assertEquals(m1.getId(), change.getStaffId());
        assertEquals(alpha.getId(), change.getPreviousDepartmentId());
        assertEquals(ALPHA, change.getPreviousDepartmentCode());
        assertEquals(alpha.getName(), change.getPreviousDepartmentName());
        assertEquals(beta.getId(), change.getNewDepartmentId());
        assertEquals(BETA, change.getNewDepartmentCode());
        assertEquals(beta.getName(), change.getNewDepartmentName());
        assertEquals(admin.getId(), change.getChangedByUserId());
        assertEquals("mda_admin", change.getChangedByUsername());
        assertEquals(ManagerDepartmentChange.SOURCE_ASSIGNMENT, change.getChangeSource());
        assertNotNull(change.getChangedAt());
    }

    @Test
    @DisplayName("The new department applies on the Manager next request: they now manage the new department staff, not the old")
    public void changeAppliesImmediately() throws Exception {
        assertEquals(200, assign("mda_admin", "ADMIN", m1.getUser(), BETA));
        MvcResult list = call("mda_m1", "MANAGER", get("/api/staff"));
        assertEquals(200, list.getResponse().getStatus());
        Set<String> names = new java.util.HashSet<>();
        for (JsonNode node : json(list)) names.add(node.get("username").asText());
        assertFalse(names.contains("mda_s1"), "no longer sees the old department");
    }

    @Test
    @DisplayName("Each change adds a record and earlier records stay unchanged, newest first, even after a department is renamed")
    public void historyIsPreservedAcrossChanges() throws Exception {
        assertEquals(200, assign("mda_admin", "ADMIN", m1.getUser(), BETA));
        ManagerDepartmentChange first = changesOf(m1).get(0);
        String firstFrom = first.getPreviousDepartmentName();
        assertEquals(200, assign("mda_admin", "ADMIN", m1.getUser(), ALPHA));

        List<ManagerDepartmentChange> history = changesOf(m1);
        assertEquals(2, history.size());
        assertEquals(ALPHA, history.get(0).getNewDepartmentCode(), "newest first");
        assertEquals(BETA, history.get(1).getNewDepartmentCode());
        assertEquals(firstFrom, history.get(1).getPreviousDepartmentName(), "the first record is untouched");

        // A later rename does not rewrite the snapshot
        beta.setName("Renamed Beta");
        departmentRepository.saveAndFlush(beta);
        assertEquals(alpha.getName() + "|" + "MDA_BETA name", history.get(1).getPreviousDepartmentName() + "|" + history.get(1).getNewDepartmentName());

        JsonNode http = json(call("mda_admin", "ADMIN", get(url(m1.getUser()) + "/history")));
        assertEquals(2, http.size());
        assertEquals("MDA_BETA name", http.get(1).get("newDepartmentName").asText());
    }

    @Test
    @DisplayName("Existing tasks stay in their original department when a Manager moves")
    public void existingTasksAreNotMoved() throws Exception {
        Task task = task(alpha, TaskStatus.PENDING, "Created in alpha");
        assertEquals(200, assign("mda_admin", "ADMIN", m1.getUser(), BETA));
        assertEquals(ALPHA, taskRepository.findById(task.getId()).orElseThrow().getDepartment().getCode());
    }

    // ── 2. Authorization ─────────────────────────────────────────────────────

    @Test
    @DisplayName("A Manager (own record, another Manager, or any) and Staff are refused 403 for assign, read and history; nothing changes")
    public void managersAndStaffAreRefused() throws Exception {
        long before = totalChanges();
        for (User target : List.of(m1.getUser(), m2.getUser(), m3.getUser())) {
            assertEquals(403, assign("mda_m1", "MANAGER", target, BETA), "Manager assigning");
            assertEquals(403, status("mda_m1", "MANAGER", get(url(target))));
            assertEquals(403, status("mda_m1", "MANAGER", get(url(target) + "/history")));
            assertEquals(403, assign("mda_s1", "STAFF", target, BETA), "Staff assigning");
            assertEquals(403, status("mda_s1", "STAFF", get(url(target))));
            assertEquals(403, status("mda_s1", "STAFF", get(url(target) + "/history")));
        }
        assertEquals(ALPHA, departmentOf(m1));
        assertEquals(ALPHA, departmentOf(m2));
        assertEquals(BETA, departmentOf(m3));
        assertEquals(before, totalChanges());
    }

    @Test
    @DisplayName("Anonymous callers get 401 on all three endpoints")
    public void anonymousIsRefused() throws Exception {
        assertEquals(401, mockMvc.perform(put(url(m1.getUser())).content("{\"department\":\"" + BETA + "\"}").contentType(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getStatus());
        assertEquals(401, mockMvc.perform(get(url(m1.getUser()))).andReturn().getResponse().getStatus());
        assertEquals(401, mockMvc.perform(get(url(m1.getUser()) + "/history")).andReturn().getResponse().getStatus());
        assertEquals(ALPHA, departmentOf(m1));
    }

    @Test
    @DisplayName("The service refuses Manager, Staff and anonymous callers itself, not only the URL rules")
    public void serviceEnforcesAdminOnly() {
        UUID id = m1.getUser().getId();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("mda_m2", null,
                List.of(new SimpleGrantedAuthority("ROLE_MANAGER"))));
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> service.assign(id, BETA)).getStatusCode().value());
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> service.history(id)).getStatusCode().value());
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> service.get(id)).getStatusCode().value());

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("mda_s1", null,
                List.of(new SimpleGrantedAuthority("ROLE_STAFF"))));
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> service.assign(id, BETA)).getStatusCode().value());
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> service.history(id)).getStatusCode().value());

        SecurityContextHolder.clearContext();
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> service.assign(id, BETA)).getStatusCode().value());
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> service.history(id)).getStatusCode().value());
        assertEquals(ALPHA, departmentOf(m1));
    }

    // ── 3. Validation ────────────────────────────────────────────────────────

    @Test
    @DisplayName("A target who is not a Manager is refused (400); an unknown user is 404; nothing is written")
    public void targetMustBeAManager() throws Exception {
        long before = totalChanges();
        assertEquals(400, assign("mda_admin", "ADMIN", s1.getUser(), BETA));
        assertEquals(400, assign("mda_admin", "ADMIN", admin, BETA));
        assertEquals(404, status("mda_admin", "ADMIN", put("/api/users/" + UUID.randomUUID() + "/manager-department").content("{\"department\":\"" + BETA + "\"}")));
        assertEquals(ALPHA, departmentOf(s1));
        assertEquals(before, totalChanges());
    }

    @Test
    @DisplayName("A Manager with no linked Staff record is refused (409): no department is invented and nothing is written")
    public void managerWithoutStaffRecordIsRefused() throws Exception {
        long before = totalChanges();
        long staffBefore = staffRepository.count();
        assertEquals(409, assign("mda_admin", "ADMIN", managerWithoutStaff, BETA));
        assertEquals(409, status("mda_admin", "ADMIN", get(url(managerWithoutStaff))));
        assertEquals(staffBefore, staffRepository.count(), "no Staff record was created");
        assertEquals(before, totalChanges());
    }

    @Test
    @DisplayName("An inactive, unknown or missing department is refused (400); the Manager and the history are untouched")
    public void departmentMustExistAndBeActive() throws Exception {
        long before = totalChanges();
        assertEquals(400, assign("mda_admin", "ADMIN", m1.getUser(), CLOSED));
        assertEquals(400, assign("mda_admin", "ADMIN", m1.getUser(), "NO_SUCH_DEPARTMENT"));
        assertEquals(400, assign("mda_admin", "ADMIN", m1.getUser(), null));
        assertEquals(400, assign("mda_admin", "ADMIN", m1.getUser(), "   "));
        assertEquals(ALPHA, departmentOf(m1));
        assertEquals(before, totalChanges());
    }

    @Test
    @DisplayName("Assigning the department the Manager already has is refused (409) and writes no audit record")
    public void sameDepartmentIsRefused() throws Exception {
        long before = totalChanges();
        assertEquals(409, assign("mda_admin", "ADMIN", m1.getUser(), ALPHA));
        assertEquals(before, totalChanges());
    }

    @Test
    @DisplayName("The Staff transfer rule applies: active tasks in another department block the move; a finished task does not")
    public void transferRuleStillApplies() throws Exception {
        Task active = task(alpha, TaskStatus.IN_PROGRESS, "Active in alpha");
        assignTo(active, m1);
        long before = totalChanges();
        assertEquals(409, assign("mda_admin", "ADMIN", m1.getUser(), BETA));
        assertEquals(ALPHA, departmentOf(m1));
        assertEquals(before, totalChanges());

        active.setStatus(TaskStatus.COMPLETED);
        taskRepository.saveAndFlush(active);
        assertEquals(200, assign("mda_admin", "ADMIN", m1.getUser(), BETA));
    }

    // ── 4. History access and content ────────────────────────────────────────

    @Test
    @DisplayName("History holds only that Manager records and only the audit fields; an empty history is an empty list")
    public void historyIsScopedToTheManagerAndLimitedToAuditFields() throws Exception {
        assertEquals(200, assign("mda_admin", "ADMIN", m1.getUser(), BETA));
        assertEquals(200, assign("mda_admin", "ADMIN", m3.getUser(), ALPHA));

        JsonNode history = json(call("mda_admin", "ADMIN", get(url(m1.getUser()) + "/history")));
        assertEquals(1, history.size());
        assertEquals("mda_m1", history.get(0).get("managerUsername").asText());

        Set<String> keys = new java.util.TreeSet<>();
        history.get(0).fieldNames().forEachRemaining(keys::add);
        assertEquals(new java.util.TreeSet<>(Set.of("id", "managerUserId", "managerUsername", "staffId", "staffName",
                "previousDepartmentId", "previousDepartmentCode", "previousDepartmentName",
                "newDepartmentId", "newDepartmentCode", "newDepartmentName",
                "changedByUserId", "changedByUsername", "changeSource", "changedAt")), keys);

        MvcResult none = call("mda_admin", "ADMIN", get(url(m2.getUser()) + "/history"));
        assertEquals(200, none.getResponse().getStatus());
        assertEquals(0, json(none).size());
    }

    @Test
    @DisplayName("Reading a Manager department reports whether the Manager is valid, without changing anything")
    public void currentDepartmentReportsValidity() throws Exception {
        JsonNode ok = json(call("mda_admin", "ADMIN", get(url(m1.getUser()))));
        assertEquals(ALPHA, ok.get("departmentCode").asText());
        assertTrue(ok.get("valid").asBoolean());

        m2.setActive(false);
        staffRepository.saveAndFlush(m2);
        JsonNode inactive = json(call("mda_admin", "ADMIN", get(url(m2.getUser()))));
        assertFalse(inactive.get("valid").asBoolean());
    }

    // ── 5. Existing protections stay in place ────────────────────────────────

    @Test
    @DisplayName("Invalid Managers stay denied: a department assignment does not reactivate them or open any access")
    public void invalidManagersStayDenied() throws Exception {
        m2.setActive(false);
        staffRepository.saveAndFlush(m2);

        assertEquals(200, assign("mda_admin", "ADMIN", m2.getUser(), BETA));
        assertFalse(reload(m2).isActive(), "the Staff record is not reactivated");
        assertEquals(403, status("mda_m2", "MANAGER", get("/api/staff")));
        assertEquals(403, status("mda_m_nostaff", "MANAGER", get("/api/staff")));
    }

    @Test
    @DisplayName("Through the ordinary staff edit: a Manager still cannot transfer anyone; an Admin transfer of a Manager is audited")
    public void staffEditPathIsGuardedAndAudited() throws Exception {
        // A Manager is still refused, for Staff and for Managers
        assertEquals(403, status("mda_m1", "MANAGER", put("/api/staff/" + s1.getId()).content(updateBody(s1, BETA))));
        assertEquals(403, status("mda_m1", "MANAGER", put("/api/staff/" + m2.getId()).content(updateBody(m2, BETA))));
        assertEquals(ALPHA, departmentOf(s1));
        assertEquals(0, totalChanges());

        // An Admin moving ordinary Staff is not a Manager department change: no record
        assertEquals(200, status("mda_admin", "ADMIN", put("/api/staff/" + s1.getId()).content(updateBody(s1, BETA))));
        assertEquals(0, totalChanges());

        // An Admin moving a Manager record leaves a record, marked with its source
        assertEquals(200, status("mda_admin", "ADMIN", put("/api/staff/" + m2.getId()).content(updateBody(m2, BETA))));
        List<ManagerDepartmentChange> changes = changesOf(m2);
        assertEquals(1, changes.size());
        assertEquals(ManagerDepartmentChange.SOURCE_STAFF_EDIT, changes.get(0).getChangeSource());
        assertEquals(ALPHA, changes.get(0).getPreviousDepartmentCode());
        assertEquals(BETA, changes.get(0).getNewDepartmentCode());
        assertEquals("mda_admin", changes.get(0).getChangedByUsername());

        // An edit that keeps the department writes nothing
        assertEquals(200, status("mda_admin", "ADMIN", put("/api/staff/" + m2.getId()).content(updateBody(m2, BETA))));
        assertEquals(1, changesOf(m2).size());
    }

    @Test
    @DisplayName("The staff-edit path keeps the active-task transfer rule for Managers too")
    public void staffEditKeepsTransferRule() throws Exception {
        assignTo(task(alpha, TaskStatus.ASSIGNED, "Held by m1"), m1);
        assertEquals(409, status("mda_admin", "ADMIN", put("/api/staff/" + m1.getId()).content(updateBody(m1, BETA))));
        assertEquals(ALPHA, departmentOf(m1));
        assertEquals(0, changesOf(m1).size());
    }

    @Test
    @DisplayName("Staff service unit of the rule: a Manager caller still cannot transfer through the service")
    public void staffServiceStillRefusesManagerTransfer() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("mda_m1", null,
                List.of(new SimpleGrantedAuthority("ROLE_MANAGER"))));
        UpdateStaffRequest request = new UpdateStaffRequest();
        request.setFullName(s1.getName());
        request.setEmail(s1.getEmail());
        request.setPhone("555-0100");
        request.setDepartment(BETA);
        request.setAvailability(Availability.AVAILABLE);
        request.setActive(true);
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> staffService.updateStaff(s1.getId(), request)).getStatusCode().value());
        assertEquals(ALPHA, departmentOf(s1));
    }

    @Test
    @DisplayName("The list of changes never mixes in other audit information: only this table's records are returned")
    public void historyContainsOnlyDepartmentChanges() throws Exception {
        assertEquals(200, assign("mda_admin", "ADMIN", m1.getUser(), BETA));
        List<String> sources = new ArrayList<>();
        for (JsonNode node : json(call("mda_admin", "ADMIN", get(url(m1.getUser()) + "/history")))) {
            sources.add(node.get("changeSource").asText());
        }
        assertEquals(List.of(ManagerDepartmentChange.SOURCE_ASSIGNMENT), sources);
    }
}
