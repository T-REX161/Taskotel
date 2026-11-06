package com.hotel.workload.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.workload.assignment.Assignment;
import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.ScopeFixtures;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.support.SupportAssignment;
import com.hotel.workload.support.SupportAssignmentRepository;
import com.hotel.workload.support.SupportStatus;
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

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Manager department isolation for tasks, through the real security chain and the real (H2) database. Every test rolls back.
 *
 * <p>Fixture: departments ALPHA and BETA. ALPHA has two Managers (m1, m2), BETA has one (m3). Tasks: ta1 (ASSIGNED to sa1) and ta2 in ALPHA,
 * tb1 in BETA (ASSIGNED to sb1, with an ACTIVE cross-department support record on it), and tnull with no department. One Administrator, and four
 * invalid Managers (no Staff record, inactive account, inactive Staff record, inactive department).
 */
@SpringBootTest
@Transactional
public class TaskDepartmentIsolationTest {

    private static final String ALPHA = "TISO_ALPHA";
    private static final String BETA = "TISO_BETA";
    private static final String CLOSED = "TISO_CLOSED";

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private SupportAssignmentRepository supportRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private TaskService taskService;

    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mockMvc;

    private Department alpha;
    private Department beta;
    private User admin;
    private Staff sa1;
    private Staff sb1;
    private Task ta1;
    private Task ta2;
    private Task tb1;
    private Task tnull;
    private SupportAssignment support;

    @BeforeEach
    public void setUp() {
        SecurityContextHolder.clearContext();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        ScopeFixtures fixtures = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder);
        alpha = fixtures.department(ALPHA);
        beta = fixtures.department(BETA);
        Department closed = fixtures.department(CLOSED);

        admin = fixtures.account("tiso_admin", Role.ADMIN, true);
        fixtures.manager("tiso_m1", alpha);
        fixtures.manager("tiso_m2", alpha);
        fixtures.manager("tiso_m3", beta);
        sa1 = fixtures.staff("tiso_sa1", alpha);
        sb1 = fixtures.staff("tiso_sb1", beta);
        fixtures.staff("tiso_sa2", alpha);

        // Invalid Managers, one reason each
        fixtures.account("tiso_m_nostaff", Role.MANAGER, true);
        fixtures.account("tiso_m_inactive_account", Role.MANAGER, false);
        fixtures.staffRecord("tiso_m_inactive_account", Role.MANAGER, alpha);
        Staff inactiveStaff = fixtures.manager("tiso_m_inactive_staff", alpha);
        inactiveStaff.setActive(false);
        staffRepository.saveAndFlush(inactiveStaff);
        fixtures.manager("tiso_m_closed_department", closed);
        closed.setActive(false);
        departmentRepository.saveAndFlush(closed);

        ta1 = task("Task A1", alpha, TaskStatus.ASSIGNED);
        ta2 = task("Task A2", alpha, TaskStatus.PENDING);
        tb1 = task("Task B1", beta, TaskStatus.ASSIGNED);
        tnull = task("Task without department", null, TaskStatus.PENDING);
        assign(ta1, sa1);
        assign(tb1, sb1);

        // An ACTIVE temporary support on the BETA task: a refused operation must leave it exactly as it is
        support = new SupportAssignment();
        support.setPlanId(UUID.randomUUID());
        support.setStaffId(sb1.getId());
        support.setStaffName(sb1.getName());
        support.setHomeDepartmentCode(ALPHA);
        support.setReceivingDepartmentCode(BETA);
        support.setTaskId(tb1.getId());
        support.setTaskTitle(tb1.getTitle());
        support.setApprovedByUsername("tiso_admin");
        support = supportRepository.saveAndFlush(support);
    }

    @AfterEach
    public void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Task task(String title, Department department, TaskStatus status) {
        Task task = new Task();
        task.setTitle(title);
        task.setPriority(Priority.MEDIUM);
        task.setStatus(status);
        task.setEstimatedDuration(30);
        task.setDueDate(LocalDate.of(2027, 1, 1));
        task.setDepartment(department);
        task.setCreatedBy(admin);
        return taskRepository.saveAndFlush(task);
    }

    private void assign(Task task, Staff staff) {
        Assignment assignment = new Assignment();
        assignment.setTask(task);
        assignment.setStaff(staff);
        assignmentRepository.saveAndFlush(assignment);
    }

    private MvcResult call(String username, String role, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(user(username).roles(role)).contentType(MediaType.APPLICATION_JSON)).andReturn();
    }

    private int status(String username, String role, MockHttpServletRequestBuilder request) throws Exception {
        return call(username, role, request).getResponse().getStatus();
    }

    private Set<String> listedTitles(String username, String role) throws Exception {
        MvcResult result = call(username, role, get("/api/tasks"));
        assertEquals(200, result.getResponse().getStatus());
        Set<String> titles = new HashSet<>();
        for (JsonNode node : mapper.readTree(result.getResponse().getContentAsString())) {
            titles.add(node.get("title").asText());
        }
        return titles;
    }

    /** department: null means the field is missing; otherwise it is sent as given (a blank string stays blank). */
    private String updateBody(Task task, String title, String department, TaskStatus status) {
        return """
                {"title": "%s", "priority": "MEDIUM", "estimatedDuration": 30, "dueDate": "2027-01-01", %s"status": "%s"}
                """.formatted(title, department == null ? "" : "\"department\": \"" + department + "\", ", status);
    }

    private String createBody(String title, String department) {
        return """
                {"title": "%s", "priority": "MEDIUM", "estimatedDuration": 30, "dueDate": "2027-01-01"%s}
                """.formatted(title, department == null ? "" : ", \"department\": \"" + department + "\"");
    }

    private Task reload(Task task) {
        taskRepository.flush();
        return taskRepository.findById(task.getId()).orElseThrow();
    }

    private void assertUntouched(Task before, String title, TaskStatus status, String departmentCode) {
        Task stored = reload(before);
        assertEquals(title, stored.getTitle());
        assertEquals(status, stored.getStatus());
        assertEquals(departmentCode, stored.getDepartment() == null ? null : stored.getDepartment().getCode());
    }

    private void assertSupportAndAssignmentUntouched() {
        SupportAssignment stored = supportRepository.findById(support.getId()).orElseThrow();
        assertEquals(SupportStatus.ACTIVE, stored.getStatus(), "Active support on the BETA task must not be ended");
        assertNull(stored.getEndReason());
        assertTrue(assignmentRepository.existsByTaskId(tb1.getId()), "The BETA task's assignment must remain");
        assertEquals(TaskStatus.ASSIGNED, reload(tb1).getStatus());
    }

    private void signInAs(String username, String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(username, null,
                java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }

    private CreateTaskRequest createRequest(String title, String department) {
        CreateTaskRequest request = new CreateTaskRequest();
        request.setTitle(title);
        request.setPriority(Priority.MEDIUM);
        request.setDepartment(department);
        return request;
    }

    private UpdateTaskRequest updateRequest(String title, String department, TaskStatus status) {
        UpdateTaskRequest request = new UpdateTaskRequest();
        request.setTitle(title);
        request.setPriority(Priority.MEDIUM);
        request.setDepartment(department);
        request.setStatus(status);
        return request;
    }

    // ── Admin: global ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Admin lists tasks of every department, including a task with no department")
    public void adminListsEveryDepartment() throws Exception {
        Set<String> titles = listedTitles("tiso_admin", "ADMIN");
        assertTrue(titles.containsAll(Set.of("Task A1", "Task A2", "Task B1", "Task without department")), titles.toString());
    }

    @Test
    @DisplayName("Admin creates, updates, moves, clears and deletes tasks in any department, including department-less ones")
    public void adminManagesEveryTask() throws Exception {
        assertEquals(201, status("tiso_admin", "ADMIN", post("/api/tasks").content(createBody("Admin made in beta", BETA))));
        assertEquals(201, status("tiso_admin", "ADMIN", post("/api/tasks").content(createBody("Admin made nowhere", null))),
                "Admin may still create a task with no department");

        assertEquals(200, status("tiso_admin", "ADMIN", put("/api/tasks/" + tb1.getId()).content(updateBody(tb1, "B1 renamed", BETA, TaskStatus.ASSIGNED))));
        assertEquals(200, status("tiso_admin", "ADMIN", put("/api/tasks/" + ta2.getId()).content(updateBody(ta2, "A2 moved", BETA, TaskStatus.PENDING))));
        assertEquals(BETA, reload(ta2).getDepartment().getCode());
        assertEquals(200, status("tiso_admin", "ADMIN", put("/api/tasks/" + ta2.getId()).content(updateBody(ta2, "A2 cleared", null, TaskStatus.PENDING))));
        assertNull(reload(ta2).getDepartment(), "Admin may still clear a task's department");
        assertEquals(200, status("tiso_admin", "ADMIN", put("/api/tasks/" + tnull.getId()).content(updateBody(tnull, "Orphan renamed", null, TaskStatus.PENDING))));
        assertEquals(204, status("tiso_admin", "ADMIN", delete("/api/tasks/" + tnull.getId())));
        assertEquals(204, status("tiso_admin", "ADMIN", delete("/api/tasks/" + ta2.getId())));
        assertTrue(taskRepository.findById(tnull.getId()).isEmpty());
    }

    // ── Manager: listing ─────────────────────────────────────────────────────

    @Test
    @DisplayName("A Manager lists only the tasks of their own department; department-less tasks are not listed")
    public void managerListsOnlyOwnDepartment() throws Exception {
        assertEquals(Set.of("Task A1", "Task A2"), listedTitles("tiso_m1", "MANAGER"));
        assertEquals(Set.of("Task B1"), listedTitles("tiso_m3", "MANAGER"));

        String body = call("tiso_m1", "MANAGER", get("/api/tasks")).getResponse().getContentAsString();
        for (String hidden : List.of("Task B1", "Task without department", tb1.getId().toString(), tnull.getId().toString())) {
            assertFalse(body.contains(hidden), hidden + " must not appear in a Manager's list");
        }
    }

    @Test
    @DisplayName("Two Managers of the same department see the same tasks")
    public void managersOfOneDepartmentSeeTheSameTasks() throws Exception {
        assertEquals(listedTitles("tiso_m1", "MANAGER"), listedTitles("tiso_m2", "MANAGER"));
    }

    // ── Manager: creating ────────────────────────────────────────────────────

    @Test
    @DisplayName("A Manager creates a task in their own department, and the record is linked to it")
    public void managerCreatesInOwnDepartment() throws Exception {
        MvcResult result = call("tiso_m1", "MANAGER", post("/api/tasks").content(createBody("Made by m1", ALPHA)));
        assertEquals(201, result.getResponse().getStatus());
        assertEquals(ALPHA, mapper.readTree(result.getResponse().getContentAsString()).get("department").asText());
        assertTrue(listedTitles("tiso_m2", "MANAGER").contains("Made by m1"));
        assertFalse(listedTitles("tiso_m3", "MANAGER").contains("Made by m1"));
    }

    @Test
    @DisplayName("A Manager's task with a blank or missing department automatically uses their own department")
    public void managerBlankDepartmentDefaultsToOwn() throws Exception {
        for (String[] variant : new String[][] {{"Made missing", null}, {"Made blank", ""}, {"Made spaces", "   "}}) {
            MvcResult result = call("tiso_m1", "MANAGER", post("/api/tasks").content(createBody(variant[0], variant[1])));
            assertEquals(201, result.getResponse().getStatus(), variant[0]);
            assertEquals(ALPHA, mapper.readTree(result.getResponse().getContentAsString()).get("department").asText(), variant[0]);
        }
        assertEquals(Set.of("Task A1", "Task A2", "Made missing", "Made blank", "Made spaces"), listedTitles("tiso_m1", "MANAGER"));
    }

    @Test
    @DisplayName("A Manager cannot create a task in another department: 403 and nothing is saved")
    public void managerCannotCreateInOtherDepartment() throws Exception {
        long before = taskRepository.count();
        assertEquals(403, status("tiso_m1", "MANAGER", post("/api/tasks").content(createBody("Crafted beta", BETA))));
        assertEquals(403, status("tiso_m1", "MANAGER", post("/api/tasks").content(createBody("Crafted unknown", "NO_SUCH_DEPARTMENT"))),
                "An unknown department is refused the same way, so the response does not reveal which departments exist");
        assertEquals(403, status("tiso_m1", "MANAGER", post("/api/tasks").content(createBody("Crafted case", "tiso_alpha"))));
        assertEquals(before, taskRepository.count());
    }

    // ── Manager: tasks of other departments (IDOR) ───────────────────────────

    @Test
    @DisplayName("A Manager cannot update another department's task, including its status: 404, and nothing changes")
    public void managerCannotUpdateOtherDepartmentTask() throws Exception {
        assertEquals(404, status("tiso_m1", "MANAGER", put("/api/tasks/" + tb1.getId()).content(updateBody(tb1, "Hijacked", BETA, TaskStatus.ASSIGNED))));
        assertEquals(404, status("tiso_m1", "MANAGER", put("/api/tasks/" + tb1.getId()).content(updateBody(tb1, "Pulled in", ALPHA, TaskStatus.ASSIGNED))),
                "A crafted request naming the Manager's own department changes nothing either");
        assertEquals(404, status("tiso_m1", "MANAGER", put("/api/tasks/" + tb1.getId()).content(updateBody(tb1, "Finished", BETA, TaskStatus.COMPLETED))));
        assertEquals(404, status("tiso_m1", "MANAGER", put("/api/tasks/" + tb1.getId()).content(updateBody(tb1, "Cancelled", BETA, TaskStatus.CANCELLED))));
        assertUntouched(tb1, "Task B1", TaskStatus.ASSIGNED, BETA);
        assertSupportAndAssignmentUntouched();

        // The other way round
        assertEquals(404, status("tiso_m3", "MANAGER", put("/api/tasks/" + ta2.getId()).content(updateBody(ta2, "Hijacked", ALPHA, TaskStatus.PENDING))));
        assertUntouched(ta2, "Task A2", TaskStatus.PENDING, ALPHA);
    }

    @Test
    @DisplayName("A Manager cannot delete another department's task: 404, and the task, its assignment and its support stay")
    public void managerCannotDeleteOtherDepartmentTask() throws Exception {
        assertEquals(404, status("tiso_m1", "MANAGER", delete("/api/tasks/" + tb1.getId())));
        assertTrue(taskRepository.findById(tb1.getId()).isPresent());
        assertSupportAndAssignmentUntouched();
    }

    @Test
    @DisplayName("A task in another department is indistinguishable from a task that does not exist")
    public void outOfScopeLooksLikeNotFound() throws Exception {
        MvcResult otherUpdate = call("tiso_m1", "MANAGER", put("/api/tasks/" + tb1.getId()).content(updateBody(tb1, "x", BETA, TaskStatus.ASSIGNED)));
        MvcResult missingUpdate = call("tiso_m1", "MANAGER", put("/api/tasks/" + UUID.randomUUID()).content(updateBody(tb1, "x", BETA, TaskStatus.ASSIGNED)));
        assertEquals(404, otherUpdate.getResponse().getStatus());
        assertEquals(missingUpdate.getResponse().getStatus(), otherUpdate.getResponse().getStatus());
        assertEquals(missingUpdate.getResponse().getContentAsString(), otherUpdate.getResponse().getContentAsString());

        MvcResult otherDelete = call("tiso_m1", "MANAGER", delete("/api/tasks/" + tb1.getId()));
        MvcResult missingDelete = call("tiso_m1", "MANAGER", delete("/api/tasks/" + UUID.randomUUID()));
        assertEquals(404, otherDelete.getResponse().getStatus());
        assertEquals(missingDelete.getResponse().getContentAsString(), otherDelete.getResponse().getContentAsString());
    }

    // ── Manager: departments cannot be moved or cleared ──────────────────────

    @Test
    @DisplayName("A Manager cannot move their task to another department or clear its department: 403, task unchanged")
    public void managerCannotChangeOrClearDepartment() throws Exception {
        assertEquals(403, status("tiso_m1", "MANAGER", put("/api/tasks/" + ta2.getId()).content(updateBody(ta2, "Moved", BETA, TaskStatus.PENDING))));
        assertEquals(403, status("tiso_m1", "MANAGER", put("/api/tasks/" + ta2.getId()).content(updateBody(ta2, "Cleared blank", "", TaskStatus.PENDING))));
        assertEquals(403, status("tiso_m1", "MANAGER", put("/api/tasks/" + ta2.getId()).content(updateBody(ta2, "Cleared missing", null, TaskStatus.PENDING))));
        assertEquals(403, status("tiso_m1", "MANAGER", put("/api/tasks/" + ta2.getId()).content(updateBody(ta2, "Moved unknown", "NO_SUCH_DEPARTMENT", TaskStatus.PENDING))));
        assertUntouched(ta2, "Task A2", TaskStatus.PENDING, ALPHA);
    }

    @Test
    @DisplayName("A Manager still edits and changes the status of their own department's tasks, with every existing rule intact")
    public void managerStillManagesOwnTasks() throws Exception {
        assertEquals(200, status("tiso_m1", "MANAGER", put("/api/tasks/" + ta2.getId()).content(updateBody(ta2, "A2 renamed", ALPHA, TaskStatus.PENDING))));
        assertEquals("A2 renamed", reload(ta2).getTitle());
        assertEquals(200, status("tiso_m2", "MANAGER", put("/api/tasks/" + ta1.getId()).content(updateBody(ta1, "A1", ALPHA, TaskStatus.IN_PROGRESS))));
        assertEquals(TaskStatus.IN_PROGRESS, reload(ta1).getStatus());
        // Existing status rules: an unassigned task cannot be put in progress (409), a finished one cannot be reopened (409)
        assertEquals(409, status("tiso_m1", "MANAGER", put("/api/tasks/" + ta2.getId()).content(updateBody(ta2, "A2 renamed", ALPHA, TaskStatus.IN_PROGRESS))));
        assertEquals(200, status("tiso_m1", "MANAGER", put("/api/tasks/" + ta1.getId()).content(updateBody(ta1, "A1", ALPHA, TaskStatus.COMPLETED))));
        assertEquals(409, status("tiso_m1", "MANAGER", put("/api/tasks/" + ta1.getId()).content(updateBody(ta1, "A1", ALPHA, TaskStatus.PENDING))));
        assertEquals(204, status("tiso_m2", "MANAGER", delete("/api/tasks/" + ta2.getId())));
        assertTrue(taskRepository.findById(ta2.getId()).isEmpty());
    }

    // ── tasks without a department ───────────────────────────────────────────

    @Test
    @DisplayName("Tasks with no department are invisible and inaccessible to Managers: not listed, 404 on update and delete, nothing changes")
    public void managersCannotReachDepartmentlessTasks() throws Exception {
        assertFalse(listedTitles("tiso_m1", "MANAGER").contains("Task without department"));
        assertFalse(listedTitles("tiso_m3", "MANAGER").contains("Task without department"));
        assertEquals(404, status("tiso_m1", "MANAGER", put("/api/tasks/" + tnull.getId()).content(updateBody(tnull, "Claimed", ALPHA, TaskStatus.PENDING))));
        assertEquals(404, status("tiso_m1", "MANAGER", put("/api/tasks/" + tnull.getId()).content(updateBody(tnull, "Renamed", null, TaskStatus.PENDING))));
        assertEquals(404, status("tiso_m1", "MANAGER", delete("/api/tasks/" + tnull.getId())));
        assertUntouched(tnull, "Task without department", TaskStatus.PENDING, null);
    }

    // ── invalid Managers ─────────────────────────────────────────────────────

    @Test
    @DisplayName("An invalid Manager gets 403 on every task operation: no Staff record, inactive account, inactive Staff record, inactive department")
    public void invalidManagersFailClosed() throws Exception {
        long tasksBefore = taskRepository.count();
        for (String name : List.of("tiso_m_nostaff", "tiso_m_inactive_account", "tiso_m_inactive_staff", "tiso_m_closed_department")) {
            assertEquals(403, status(name, "MANAGER", get("/api/tasks")), name + " list");
            assertEquals(403, status(name, "MANAGER", post("/api/tasks").content(createBody("By " + name, ALPHA))), name + " create");
            assertEquals(403, status(name, "MANAGER", post("/api/tasks").content(createBody("By " + name, null))), name + " create blank");
            assertEquals(403, status(name, "MANAGER", put("/api/tasks/" + ta2.getId()).content(updateBody(ta2, "x", ALPHA, TaskStatus.PENDING))), name + " update");
            assertEquals(403, status(name, "MANAGER", delete("/api/tasks/" + ta2.getId())), name + " delete");
        }
        assertUntouched(ta2, "Task A2", TaskStatus.PENDING, ALPHA);
        assertEquals(tasksBefore, taskRepository.count(), "No task was created by an invalid Manager");
    }

    @Test
    @DisplayName("A Manager's access follows the database immediately: moved to another department, then deactivated")
    public void managerAccessFollowsTheDatabase() throws Exception {
        assertEquals(Set.of("Task A1", "Task A2"), listedTitles("tiso_m1", "MANAGER"));
        Staff record = staffRepository.findByUserUsername("tiso_m1").orElseThrow();
        record.setDepartment(beta);
        staffRepository.saveAndFlush(record);
        assertEquals(Set.of("Task B1"), listedTitles("tiso_m1", "MANAGER"));
        assertEquals(404, status("tiso_m1", "MANAGER", delete("/api/tasks/" + ta2.getId())));
        record.setActive(false);
        staffRepository.saveAndFlush(record);
        assertEquals(403, status("tiso_m1", "MANAGER", get("/api/tasks")));
    }

    // ── Staff: unchanged ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Staff keep their behaviour: they see only the tasks assigned to them and cannot create, change or delete any task")
    public void staffBehaviourIsUnchanged() throws Exception {
        assertEquals(Set.of("Task A1"), listedTitles("tiso_sa1", "STAFF"));
        assertEquals(Set.of("Task B1"), listedTitles("tiso_sb1", "STAFF"));
        assertEquals(Set.of(), listedTitles("tiso_sa2", "STAFF"), "A staff member with no assignment sees no task");

        assertEquals(403, status("tiso_sa1", "STAFF", post("/api/tasks").content(createBody("By staff", ALPHA))));
        assertEquals(403, status("tiso_sa1", "STAFF", put("/api/tasks/" + ta1.getId()).content(updateBody(ta1, "x", ALPHA, TaskStatus.IN_PROGRESS))));
        assertEquals(403, status("tiso_sa1", "STAFF", delete("/api/tasks/" + ta1.getId())));
        assertUntouched(ta1, "Task A1", TaskStatus.ASSIGNED, ALPHA);
    }

    // ── direct service calls ─────────────────────────────────────────────────

    @Test
    @DisplayName("Direct service calls enforce the same rules as HTTP: Manager scope, Staff refused, no sign-in refused")
    public void directServiceCallsEnforceTheSameRules() {
        // Manager: own department works, other department and department-less are not found, other-department create is refused
        signInAs("tiso_m1", "ROLE_MANAGER");
        assertEquals(2, taskService.getAllTasks().size());
        assertEquals("A2 via service", taskService.updateTask(ta2.getId(), updateRequest("A2 via service", ALPHA, TaskStatus.PENDING)).getTitle());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> taskService.updateTask(tb1.getId(), updateRequest("x", BETA, TaskStatus.ASSIGNED))).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> taskService.deleteTask(tb1.getId())).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> taskService.deleteTask(tnull.getId())).getStatusCode().value());
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> taskService.createTask(createRequest("x", BETA))).getStatusCode().value());
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> taskService.updateTask(ta2.getId(), updateRequest("x", BETA, TaskStatus.PENDING))).getStatusCode().value());
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> taskService.updateTask(ta2.getId(), updateRequest("x", null, TaskStatus.PENDING))).getStatusCode().value());
        assertEquals(ALPHA, taskService.createTask(createRequest("Service default", "")).getDepartment());

        // Staff: reads their own tasks, every write is refused
        signInAs("tiso_sa1", "ROLE_STAFF");
        assertEquals(1, taskService.getAllTasks().size());
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> taskService.createTask(createRequest("x", ALPHA))).getStatusCode().value());
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> taskService.updateTask(ta1.getId(), updateRequest("x", ALPHA, TaskStatus.IN_PROGRESS))).getStatusCode().value());
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> taskService.deleteTask(ta1.getId())).getStatusCode().value());

        // Invalid Manager
        signInAs("tiso_m_nostaff", "ROLE_MANAGER");
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> taskService.getAllTasks()).getStatusCode().value());
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> taskService.deleteTask(ta2.getId())).getStatusCode().value());

        // Nobody signed in
        SecurityContextHolder.clearContext();
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> taskService.getAllTasks()).getStatusCode().value());
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> taskService.createTask(createRequest("x", ALPHA))).getStatusCode().value());
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> taskService.updateTask(ta2.getId(), updateRequest("x", ALPHA, TaskStatus.PENDING))).getStatusCode().value());
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> taskService.deleteTask(ta2.getId())).getStatusCode().value());
        assertTrue(taskRepository.findById(ta2.getId()).isPresent());
        assertSupportAndAssignmentUntouched();
    }

    @Test
    @DisplayName("A refused operation never changes task data and never touches assignments or support")
    public void refusedOperationsChangeNothing() throws Exception {
        long assignments = assignmentRepository.count();
        long tasks = taskRepository.count();
        long supports = supportRepository.count();

        status("tiso_m1", "MANAGER", delete("/api/tasks/" + tb1.getId()));
        status("tiso_m1", "MANAGER", put("/api/tasks/" + tb1.getId()).content(updateBody(tb1, "x", BETA, TaskStatus.COMPLETED)));
        status("tiso_m1", "MANAGER", put("/api/tasks/" + ta1.getId()).content(updateBody(ta1, "x", BETA, TaskStatus.COMPLETED)));
        status("tiso_m1", "MANAGER", post("/api/tasks").content(createBody("x", BETA)));
        status("tiso_sa1", "STAFF", delete("/api/tasks/" + ta1.getId()));
        status("tiso_m_nostaff", "MANAGER", delete("/api/tasks/" + ta1.getId()));

        assertEquals(assignments, assignmentRepository.count());
        assertEquals(tasks, taskRepository.count());
        assertEquals(supports, supportRepository.count());
        assertUntouched(ta1, "Task A1", TaskStatus.ASSIGNED, ALPHA);
        assertUntouched(tb1, "Task B1", TaskStatus.ASSIGNED, BETA);
        assertSupportAndAssignmentUntouched();
    }
}
