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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * A Staff member moves their own task forward: PATCH /api/tasks/{id}/status. Real security chain, real service, real (H2) database; every
 * test rolls back. Fixture: department TSTAT with Staff s1 and s2, a Manager and an Admin; tasks owned by s1, by s2, and one unassigned.
 */
@SpringBootTest
@Transactional
public class StaffTaskStatusUpdateTest {

    private static final String DEPT = "TSTAT_D";

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
    private Department department;
    private User admin;
    private Staff s1;
    private Staff s2;
    private Task mine;       // ASSIGNED to s1
    private Task theirs;     // ASSIGNED to s2
    private Task unassigned; // PENDING, no assignment

    @BeforeEach
    public void setUp() {
        SecurityContextHolder.clearContext();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        ScopeFixtures fixtures = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder);
        department = fixtures.department(DEPT);
        admin = fixtures.account("tstat_admin", Role.ADMIN, true);
        fixtures.manager("tstat_mgr", department);
        s1 = fixtures.staff("tstat_s1", department);
        s2 = fixtures.staff("tstat_s2", department);
        mine = task("Mine", TaskStatus.ASSIGNED);
        theirs = task("Theirs", TaskStatus.ASSIGNED);
        unassigned = task("Unassigned", TaskStatus.PENDING);
        assign(mine, s1);
        assign(theirs, s2);
    }

    @AfterEach
    public void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Task task(String title, TaskStatus status) {
        Task task = new Task();
        task.setTitle(title);
        task.setDescription("Description of " + title);
        task.setPriority(Priority.MEDIUM);
        task.setStatus(status);
        task.setEstimatedDuration(30);
        task.setDueDate(LocalDate.of(2027, 1, 1));
        task.setDepartment(department);
        task.setCreatedBy(admin);
        return taskRepository.saveAndFlush(task);
    }

    private Assignment assign(Task task, Staff staff) {
        Assignment assignment = new Assignment();
        assignment.setTask(task);
        assignment.setStaff(staff);
        return assignmentRepository.saveAndFlush(assignment);
    }

    private MvcResult call(String username, String role, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(user(username).roles(role)).contentType(MediaType.APPLICATION_JSON)).andReturn();
    }

    private MvcResult setStatus(String username, UUID taskId, String statusJson) throws Exception {
        return call(username, "STAFF", patch("/api/tasks/" + taskId + "/status").content("{\"status\":" + statusJson + "}"));
    }

    private MvcResult setStatus(String username, Task task, TaskStatus status) throws Exception {
        return setStatus(username, task.getId(), "\"" + status.name() + "\"");
    }

    private TaskStatus stored(Task task) {
        taskRepository.flush();
        return taskRepository.findById(task.getId()).orElseThrow().getStatus();
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    // ── the allowed path ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Staff start their own task (Assigned to In Progress), then complete it (In Progress to Completed); the answer is the updated task")
    void staffMoveTheirOwnTaskForward() throws Exception {
        MvcResult started = setStatus("tstat_s1", mine, TaskStatus.IN_PROGRESS);
        assertEquals(200, started.getResponse().getStatus(), body(started));
        JsonNode json = mapper.readTree(body(started));
        assertEquals("IN_PROGRESS", json.get("status").asText());
        assertEquals(mine.getId().toString(), json.get("id").asText());
        assertEquals(TaskStatus.IN_PROGRESS, stored(mine));

        MvcResult done = setStatus("tstat_s1", mine, TaskStatus.COMPLETED);
        assertEquals(200, done.getResponse().getStatus(), body(done));
        assertEquals(TaskStatus.COMPLETED, stored(mine));
    }

    @Test
    @DisplayName("The change shows in the Staff member's own task list, and the other Staff member's task is untouched")
    void listReflectsTheChange() throws Exception {
        setStatus("tstat_s1", mine, TaskStatus.IN_PROGRESS);
        MvcResult list = call("tstat_s1", "STAFF", get("/api/tasks"));
        JsonNode tasks = mapper.readTree(body(list));
        assertEquals(1, tasks.size());
        assertEquals("IN_PROGRESS", tasks.get(0).get("status").asText());
        assertEquals(TaskStatus.ASSIGNED, stored(theirs));
    }

    // ── ownership ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Another Staff member's task, an unassigned task and a task that does not exist all give the same 404, and nothing changes")
    void notMyTaskIsNotFound() throws Exception {
        MvcResult other = setStatus("tstat_s1", theirs, TaskStatus.IN_PROGRESS);
        MvcResult none = setStatus("tstat_s1", unassigned, TaskStatus.IN_PROGRESS);
        MvcResult missing = setStatus("tstat_s1", UUID.randomUUID(), "\"IN_PROGRESS\"");
        assertEquals(404, other.getResponse().getStatus());
        assertEquals(404, none.getResponse().getStatus());
        assertEquals(404, missing.getResponse().getStatus());
        assertEquals(body(missing).replaceAll("\"timestamp\":\"[^\"]*\"", ""), body(other).replaceAll("\"timestamp\":\"[^\"]*\"", ""), "no difference that reveals whose task it is");
        assertEquals(TaskStatus.ASSIGNED, stored(theirs));
        assertEquals(TaskStatus.PENDING, stored(unassigned));
    }

    @Test
    @DisplayName("After a reassignment the task is no longer mine: the former holder gets 404 and the new holder can move it")
    void reassignedTaskFollowsTheAssignment() throws Exception {
        Assignment assignment = assignmentRepository.findAllByTaskId(mine.getId()).get(0);
        assignment.setStaff(s2);
        assignmentRepository.saveAndFlush(assignment);
        assertEquals(404, setStatus("tstat_s1", mine, TaskStatus.IN_PROGRESS).getResponse().getStatus());
        assertEquals(200, setStatus("tstat_s2", mine, TaskStatus.IN_PROGRESS).getResponse().getStatus());
    }

    @Test
    @DisplayName("A Staff account with no Staff record, or an inactive one, is refused (403)")
    void noUsableStaffRecord() throws Exception {
        new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder).account("tstat_norecord", Role.STAFF, true);
        assertEquals(403, setStatus("tstat_norecord", mine, TaskStatus.IN_PROGRESS).getResponse().getStatus());
        s1.setActive(false);
        staffRepository.saveAndFlush(s1);
        assertEquals(403, setStatus("tstat_s1", mine, TaskStatus.IN_PROGRESS).getResponse().getStatus());
        assertEquals(TaskStatus.ASSIGNED, stored(mine));
    }

    // ── transitions ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Only the next step is allowed: skipping, going back, cancelling, pending, repeating and reopening are all 409 and change nothing")
    void invalidTransitionsAreRejected() throws Exception {
        for (TaskStatus target : List.of(TaskStatus.PENDING, TaskStatus.ASSIGNED, TaskStatus.COMPLETED, TaskStatus.CANCELLED)) {
            MvcResult result = setStatus("tstat_s1", mine, target);
            assertEquals(409, result.getResponse().getStatus(), "ASSIGNED to " + target + ": " + body(result));
        }
        assertEquals(TaskStatus.ASSIGNED, stored(mine));

        setStatus("tstat_s1", mine, TaskStatus.IN_PROGRESS);
        for (TaskStatus target : List.of(TaskStatus.PENDING, TaskStatus.ASSIGNED, TaskStatus.IN_PROGRESS, TaskStatus.CANCELLED)) {
            assertEquals(409, setStatus("tstat_s1", mine, target).getResponse().getStatus(), "IN_PROGRESS to " + target);
        }
        assertEquals(TaskStatus.IN_PROGRESS, stored(mine));

        setStatus("tstat_s1", mine, TaskStatus.COMPLETED);
        for (TaskStatus target : TaskStatus.values()) {
            assertEquals(409, setStatus("tstat_s1", mine, target).getResponse().getStatus(), "COMPLETED to " + target);
        }
        assertEquals(TaskStatus.COMPLETED, stored(mine));

        Task cancelled = task("Cancelled", TaskStatus.CANCELLED);
        assign(cancelled, s1);
        for (TaskStatus target : TaskStatus.values()) {
            assertEquals(409, setStatus("tstat_s1", cancelled, target).getResponse().getStatus(), "CANCELLED to " + target);
        }
        assertEquals(TaskStatus.CANCELLED, stored(cancelled));
    }

    @Test
    @DisplayName("A missing, null or unknown status is a 400")
    void badStatusValues() throws Exception {
        assertEquals(400, call("tstat_s1", "STAFF", patch("/api/tasks/" + mine.getId() + "/status").content("{}")).getResponse().getStatus());
        assertEquals(400, setStatus("tstat_s1", mine.getId(), "null").getResponse().getStatus());
        assertEquals(400, setStatus("tstat_s1", mine.getId(), "\"FLYING\"").getResponse().getStatus());
        assertEquals(TaskStatus.ASSIGNED, stored(mine));
    }

    // ── nothing but the status can change ───────────────────────────────────────────────────

    @Test
    @DisplayName("Extra fields in the body (title, priority, department, duration, assignee) are ignored: only the status changes")
    void onlyStatusChanges() throws Exception {
        Department other = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder).department("TSTAT_OTHER");
        String bodyJson = "{\"status\":\"IN_PROGRESS\",\"title\":\"Hijacked\",\"description\":\"changed\",\"priority\":\"URGENT\","
                + "\"department\":\"" + other.getCode() + "\",\"estimatedDuration\":999,\"staffId\":\"" + s2.getId() + "\","
                + "\"assignmentId\":\"" + UUID.randomUUID() + "\",\"dueDate\":\"2030-05-05\"}";
        MvcResult result = call("tstat_s1", "STAFF", patch("/api/tasks/" + mine.getId() + "/status").content(bodyJson));
        assertEquals(200, result.getResponse().getStatus(), body(result));

        taskRepository.flush();
        Task after = taskRepository.findById(mine.getId()).orElseThrow();
        assertEquals(TaskStatus.IN_PROGRESS, after.getStatus());
        assertEquals("Mine", after.getTitle());
        assertEquals("Description of Mine", after.getDescription());
        assertEquals(Priority.MEDIUM, after.getPriority());
        assertEquals(DEPT, after.getDepartment().getCode());
        assertEquals(30, after.getEstimatedDuration());
        assertEquals(LocalDate.of(2027, 1, 1), after.getDueDate());
        List<Assignment> assignments = assignmentRepository.findAllByTaskId(mine.getId());
        assertEquals(1, assignments.size());
        assertEquals(s1.getId(), assignments.get(0).getStaff().getId(), "the assignment is unchanged");
    }

    @Test
    @DisplayName("Staff still cannot create, edit (PUT) or delete tasks, or assign: the status route gives them nothing else")
    void staffGainNoOtherTaskPermission() throws Exception {
        String full = "{\"title\":\"X\",\"priority\":\"LOW\",\"status\":\"IN_PROGRESS\",\"department\":\"" + DEPT + "\",\"estimatedDuration\":10}";
        assertEquals(403, call("tstat_s1", "STAFF", put("/api/tasks/" + mine.getId()).content(full)).getResponse().getStatus());
        assertEquals(403, call("tstat_s1", "STAFF", post("/api/tasks").content(full)).getResponse().getStatus());
        assertEquals(403, call("tstat_s1", "STAFF", delete("/api/tasks/" + mine.getId())).getResponse().getStatus());
        assertEquals(403, call("tstat_s1", "STAFF", post("/api/assignments").content("{\"taskId\":\"" + unassigned.getId() + "\",\"staffId\":\"" + s1.getId() + "\"}")).getResponse().getStatus());
        assertEquals(TaskStatus.ASSIGNED, stored(mine));
        assertEquals("Mine", taskRepository.findById(mine.getId()).orElseThrow().getTitle());
    }

    // ── Admin and Manager ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Admin and Manager keep the full task update (PUT): they can still set a status, and the new route is not for them (403)")
    void adminAndManagerUnaffected() throws Exception {
        String full = "{\"title\":\"Mine renamed\",\"priority\":\"HIGH\",\"status\":\"IN_PROGRESS\",\"department\":\"" + DEPT + "\",\"estimatedDuration\":45}";
        MvcResult byManager = call("tstat_mgr", "MANAGER", put("/api/tasks/" + mine.getId()).content(full));
        assertEquals(200, byManager.getResponse().getStatus(), body(byManager));
        assertEquals(TaskStatus.IN_PROGRESS, stored(mine));
        assertEquals("Mine renamed", taskRepository.findById(mine.getId()).orElseThrow().getTitle());

        MvcResult byAdmin = call("tstat_admin", "ADMIN", put("/api/tasks/" + theirs.getId()).content(full.replace("Mine renamed", "Theirs renamed")));
        assertEquals(200, byAdmin.getResponse().getStatus(), body(byAdmin));

        assertEquals(403, call("tstat_mgr", "MANAGER", patch("/api/tasks/" + mine.getId() + "/status").content("{\"status\":\"COMPLETED\"}")).getResponse().getStatus());
        assertEquals(403, call("tstat_admin", "ADMIN", patch("/api/tasks/" + mine.getId() + "/status").content("{\"status\":\"COMPLETED\"}")).getResponse().getStatus());
        assertEquals(TaskStatus.IN_PROGRESS, stored(mine));
        assertEquals(200, call("tstat_mgr", "MANAGER", get("/api/tasks")).getResponse().getStatus());
    }

    @Test
    @DisplayName("Without a sign-in the route is 401")
    void unauthenticated() throws Exception {
        MvcResult result = mockMvc.perform(patch("/api/tasks/" + mine.getId() + "/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"IN_PROGRESS\"}")).andReturn();
        assertEquals(401, result.getResponse().getStatus());
        assertEquals(TaskStatus.ASSIGNED, stored(mine));
    }

    // ── the service enforces it by itself (no URL rule in front) ──────────────────────────────

    private void signInAs(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @Test
    @DisplayName("Called directly, the service refuses Admin and Manager (403) and other people's tasks (404): the rules are not only URL rules")
    void serviceEnforcesTheRules() {
        signInAs("tstat_admin", "ADMIN");
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> taskService.updateOwnTaskStatus(mine.getId(), TaskStatus.IN_PROGRESS)).getStatusCode().value());
        signInAs("tstat_mgr", "MANAGER");
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> taskService.updateOwnTaskStatus(mine.getId(), TaskStatus.IN_PROGRESS)).getStatusCode().value());

        signInAs("tstat_s1", "STAFF");
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> taskService.updateOwnTaskStatus(theirs.getId(), TaskStatus.IN_PROGRESS)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> taskService.updateOwnTaskStatus(unassigned.getId(), TaskStatus.IN_PROGRESS)).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> taskService.updateOwnTaskStatus(mine.getId(), null)).getStatusCode().value());
        assertEquals(TaskStatus.IN_PROGRESS, taskService.updateOwnTaskStatus(mine.getId(), TaskStatus.IN_PROGRESS).getStatus());
        assertNotEquals(TaskStatus.ASSIGNED, stored(mine));

        SecurityContextHolder.clearContext();
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> taskService.updateOwnTaskStatus(mine.getId(), TaskStatus.COMPLETED)).getStatusCode().value());
    }

    // ── existing follow-up behaviour ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Completing a task ends the temporary cross-department support on it, as the full update does")
    void completionEndsSupport() throws Exception {
        setStatus("tstat_s1", mine, TaskStatus.IN_PROGRESS);
        SupportAssignment support = new SupportAssignment();
        support.setPlanId(UUID.randomUUID());
        support.setStaffId(s1.getId());
        support.setStaffName(s1.getName());
        support.setHomeDepartmentCode(DEPT);
        support.setReceivingDepartmentCode(DEPT);
        support.setTaskId(mine.getId());
        support.setTaskTitle(mine.getTitle());
        support.setApprovedByUsername("tstat_admin");
        support = supportRepository.saveAndFlush(support);
        assertEquals(SupportStatus.ACTIVE, support.getStatus());

        assertEquals(200, setStatus("tstat_s1", mine, TaskStatus.COMPLETED).getResponse().getStatus());
        supportRepository.flush();
        assertTrue(supportRepository.findById(support.getId()).orElseThrow().getStatus() != SupportStatus.ACTIVE, "the support ended with the task");
    }
}
