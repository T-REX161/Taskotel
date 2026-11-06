package com.hotel.workload.assignment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.ScopeFixtures;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.Availability;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.support.SupportAssignment;
import com.hotel.workload.support.SupportAssignmentRepository;
import com.hotel.workload.support.SupportStatus;
import com.hotel.workload.task.Priority;
import com.hotel.workload.task.Task;
import com.hotel.workload.task.TaskRepository;
import com.hotel.workload.task.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Manager department isolation for assignments, through the real security chain and the real (H2) database. Every test rolls back.
 *
 * <p>Fixture: departments ALPHA and BETA. ALPHA has two Managers (m1, m2) and staff sa1 (holds ta1), sa2 and sa3 (free). BETA has one
 * Manager (m3) and staff sb1 (holds tb1) and sb2 (free). Tasks: ta1 (ASSIGNED to sa1), ta2 and ta3 (PENDING) in ALPHA; tb1 (ASSIGNED to
 * sb1), tb2 (PENDING) in BETA; tnull with no department; and tl, a BETA task held by sal, an ALPHA staff member lent to BETA through an
 * ACTIVE support record. Plus one Administrator and four invalid Managers (no Staff record, inactive account, inactive Staff record,
 * inactive department).
 */
@SpringBootTest
@Transactional
public class AssignmentDepartmentIsolationTest {

    private static final String ALPHA = "AISO_ALPHA";
    private static final String BETA = "AISO_BETA";
    private static final String CLOSED = "AISO_CLOSED";

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private SupportAssignmentRepository supportRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mockMvc;

    private User admin;
    private Staff sa1, sa2, sa3, sb1, sb2, sal;
    private Task ta1, ta2, ta3, tb1, tb2, tnull, tl;
    private Assignment asgA1, asgB1, asgL;
    private SupportAssignment support;

    @BeforeEach
    public void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        ScopeFixtures fixtures = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder);
        Department alpha = fixtures.department(ALPHA);
        Department beta = fixtures.department(BETA);
        Department closed = fixtures.department(CLOSED);

        admin = fixtures.account("aiso_admin", Role.ADMIN, true);
        fixtures.manager("aiso_m1", alpha);
        fixtures.manager("aiso_m2", alpha);
        fixtures.manager("aiso_m3", beta);
        sa1 = fixtures.staff("aiso_sa1", alpha);
        sa2 = fixtures.staff("aiso_sa2", alpha);
        sa3 = fixtures.staff("aiso_sa3", alpha);
        sb1 = fixtures.staff("aiso_sb1", beta);
        sb2 = fixtures.staff("aiso_sb2", beta);
        sal = fixtures.staff("aiso_sal", alpha);

        fixtures.account("aiso_m_nostaff", Role.MANAGER, true);
        fixtures.account("aiso_m_inactive_account", Role.MANAGER, false);
        fixtures.staffRecord("aiso_m_inactive_account", Role.MANAGER, alpha);
        Staff inactiveStaff = fixtures.manager("aiso_m_inactive_staff", alpha);
        inactiveStaff.setActive(false);
        staffRepository.saveAndFlush(inactiveStaff);
        fixtures.manager("aiso_m_closed_department", closed);
        closed.setActive(false);
        departmentRepository.saveAndFlush(closed);

        ta1 = task("AISO Task A1", alpha, TaskStatus.ASSIGNED);
        ta2 = task("AISO Task A2", alpha, TaskStatus.PENDING);
        ta3 = task("AISO Task A3", alpha, TaskStatus.PENDING);
        tb1 = task("AISO Task B1", beta, TaskStatus.ASSIGNED);
        tb2 = task("AISO Task B2", beta, TaskStatus.PENDING);
        tnull = task("AISO Task without department", null, TaskStatus.PENDING);
        tl = task("AISO Task B lent", beta, TaskStatus.ASSIGNED);
        asgA1 = assign(ta1, sa1);
        asgB1 = assign(tb1, sb1);
        asgL = assign(tl, sal);

        // An ACTIVE temporary support: an ALPHA staff member holds a BETA task
        support = new SupportAssignment();
        support.setPlanId(UUID.randomUUID());
        support.setStaffId(sal.getId());
        support.setStaffName(sal.getName());
        support.setHomeDepartmentCode(ALPHA);
        support.setReceivingDepartmentCode(BETA);
        support.setTaskId(tl.getId());
        support.setTaskTitle(tl.getTitle());
        support.setAssignmentId(asgL.getId());
        support.setApprovedByUsername("aiso_admin");
        support = supportRepository.saveAndFlush(support);
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

    private Assignment assign(Task task, Staff staff) {
        Assignment assignment = new Assignment();
        assignment.setTask(task);
        assignment.setStaff(staff);
        return assignmentRepository.saveAndFlush(assignment);
    }

    private MvcResult call(String username, String role, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(user(username).roles(role)).contentType(MediaType.APPLICATION_JSON)).andReturn();
    }

    private int status(String username, String role, MockHttpServletRequestBuilder request) throws Exception {
        return call(username, role, request).getResponse().getStatus();
    }

    private static String body(Task task, Staff staff) {
        return "{\"taskId\": \"" + task.getId() + "\", \"staffId\": \"" + staff.getId() + "\"}";
    }

    private static String reassignBody(Staff staff) {
        return "{\"staffId\": \"" + staff.getId() + "\"}";
    }

    private int create(String username, String role, Task task, Staff staff) throws Exception {
        return status(username, role, post("/api/assignments").content(body(task, staff)));
    }

    private int reassign(String username, String role, Assignment assignment, Staff target) throws Exception {
        return status(username, role, put("/api/assignments/" + assignment.getId()).content(reassignBody(target)));
    }

    private Set<String> listedTitles(String username, String role) throws Exception {
        MvcResult result = call(username, role, get("/api/assignments"));
        assertEquals(200, result.getResponse().getStatus());
        Set<String> titles = new HashSet<>();
        for (JsonNode node : mapper.readTree(result.getResponse().getContentAsString())) {
            titles.add(node.get("taskTitle").asText());
        }
        return titles;
    }

    private UUID holderOf(Assignment assignment) {
        assignmentRepository.flush();
        return assignmentRepository.findById(assignment.getId()).orElseThrow().getStaff().getId();
    }

    private TaskStatus statusOf(Task task) {
        taskRepository.flush();
        return taskRepository.findById(task.getId()).orElseThrow().getStatus();
    }

    // ── listing ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A Manager lists only assignments of tasks in their own department")
    public void managerListsOnlyOwnDepartment() throws Exception {
        Set<String> titles = listedTitles("aiso_m1", "MANAGER");
        assertTrue(titles.contains("AISO Task A1"), titles.toString());
        assertFalse(titles.contains("AISO Task B1"), titles.toString());
        assertFalse(titles.contains("AISO Task B lent"), "A task of BETA held by lent ALPHA staff is not the ALPHA Manager's: " + titles);
    }

    @Test
    @DisplayName("Two Managers of one department see the same assignments")
    public void managersShareTheirDepartmentScope() throws Exception {
        assertEquals(listedTitlesOfDepartment("aiso_m1"), listedTitlesOfDepartment("aiso_m2"));
        assertTrue(listedTitles("aiso_m2", "MANAGER").contains("AISO Task A1"));
    }

    private Set<String> listedTitlesOfDepartment(String manager) throws Exception {
        Set<String> titles = listedTitles(manager, "MANAGER");
        titles.removeIf(t -> !t.startsWith("AISO"));
        return titles;
    }

    @Test
    @DisplayName("Support-loan: the receiving department's Manager sees the assignment held by lent staff from another department; the donor's Manager does not")
    public void lentAssignmentIsVisibleToTheTaskDepartmentOnly() throws Exception {
        assertTrue(listedTitles("aiso_m3", "MANAGER").contains("AISO Task B lent"));
        assertFalse(listedTitles("aiso_m1", "MANAGER").contains("AISO Task B lent"));
    }

    @Test
    @DisplayName("Admin lists the assignments of every department")
    public void adminListsEverything() throws Exception {
        Set<String> titles = listedTitles("aiso_admin", "ADMIN");
        assertTrue(titles.containsAll(Set.of("AISO Task A1", "AISO Task B1", "AISO Task B lent")), titles.toString());
    }

    @Test
    @DisplayName("Staff list only their own assignments, as before")
    public void staffListOwnOnly() throws Exception {
        Set<String> titles = listedTitles("aiso_sa1", "STAFF");
        assertEquals(Set.of("AISO Task A1"), titles);
        assertEquals(Set.of(), listedTitles("aiso_sa2", "STAFF"));
    }

    // ── create ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A Manager assigns an own-department task to own-department staff")
    public void managerCreatesInsideTheDepartment() throws Exception {
        assertEquals(201, create("aiso_m1", "MANAGER", ta2, sa2));
        assertEquals(TaskStatus.ASSIGNED, statusOf(ta2));
        assertEquals(201, create("aiso_m2", "MANAGER", ta3, sa3), "the second Manager of the department may too");
    }

    @Test
    @DisplayName("A Manager cannot assign another department's task (404), and nothing changes")
    public void managerCannotAssignAnotherDepartmentsTask() throws Exception {
        assertEquals(404, create("aiso_m1", "MANAGER", tb2, sa2), "other department's task, own staff");
        assertEquals(404, create("aiso_m1", "MANAGER", tb2, sb2), "other department's task and staff");
        assertEquals(TaskStatus.PENDING, statusOf(tb2));
        assertFalse(assignmentRepository.existsByTaskId(tb2.getId()));
    }

    @Test
    @DisplayName("A Manager cannot assign an own-department task to another department's staff (404), and nothing changes")
    public void managerCannotAssignToAnotherDepartmentsStaff() throws Exception {
        assertEquals(404, create("aiso_m1", "MANAGER", ta2, sb2));
        assertEquals(TaskStatus.PENDING, statusOf(ta2));
        assertFalse(assignmentRepository.existsByTaskId(ta2.getId()));
    }

    @Test
    @DisplayName("A Manager cannot assign a task with no department (404); Admin still can, as before")
    public void departmentLessTasks() throws Exception {
        assertEquals(404, create("aiso_m1", "MANAGER", tnull, sa2));
        assertEquals(TaskStatus.PENDING, statusOf(tnull));
        assertFalse(assignmentRepository.existsByTaskId(tnull.getId()));

        assertEquals(201, create("aiso_admin", "ADMIN", tnull, sa2), "Admin keeps the existing behaviour for tasks with no department");
    }

    @Test
    @DisplayName("Admin assigns across departments: any department's task to that department's staff")
    public void adminCreatesInAnyDepartment() throws Exception {
        assertEquals(201, create("aiso_admin", "ADMIN", ta2, sa2));
        assertEquals(201, create("aiso_admin", "ADMIN", tb2, sb2));
        assertEquals(400, create("aiso_admin", "ADMIN", ta3, sb2), "the existing task/staff department rule still applies to Admin");
    }

    @Test
    @DisplayName("The existing business rules still apply to a Manager inside the department (unavailable, inactive, duplicate)")
    public void businessRulesStillApplyToManagers() throws Exception {
        sa2.setAvailability(Availability.UNAVAILABLE);
        staffRepository.saveAndFlush(sa2);
        assertEquals(400, create("aiso_m1", "MANAGER", ta2, sa2));
        assertEquals(409, create("aiso_m1", "MANAGER", ta1, sa3), "ta1 already has an active assignment");
        assertEquals(409, create("aiso_m1", "MANAGER", ta2, sa1), "sa1 already holds an active task");
    }

    // ── reassign ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A Manager reassigns an own-department assignment to own-department staff")
    public void managerReassignsInsideTheDepartment() throws Exception {
        assertEquals(200, reassign("aiso_m1", "MANAGER", asgA1, sa2));
        assertEquals(sa2.getId(), holderOf(asgA1));
        assertEquals(200, reassign("aiso_m2", "MANAGER", asgA1, sa3), "the second Manager of the department may too");
        assertEquals(sa3.getId(), holderOf(asgA1));
    }

    @Test
    @DisplayName("A Manager cannot reassign another department's assignment (404), whoever the target is, and nothing changes")
    public void managerCannotReassignAnotherDepartmentsAssignment() throws Exception {
        assertEquals(404, reassign("aiso_m1", "MANAGER", asgB1, sa2));
        assertEquals(404, reassign("aiso_m1", "MANAGER", asgB1, sb2));
        assertEquals(sb1.getId(), holderOf(asgB1));
        assertEquals(TaskStatus.ASSIGNED, statusOf(tb1));
    }

    @Test
    @DisplayName("A Manager cannot reassign to another department's staff (404), and nothing changes")
    public void managerCannotReassignToAnotherDepartmentsStaff() throws Exception {
        assertEquals(404, reassign("aiso_m1", "MANAGER", asgA1, sb2));
        assertEquals(sa1.getId(), holderOf(asgA1));
    }

    @Test
    @DisplayName("Admin reassigns in any department")
    public void adminReassignsAnywhere() throws Exception {
        assertEquals(200, reassign("aiso_admin", "ADMIN", asgA1, sa2));
        assertEquals(200, reassign("aiso_admin", "ADMIN", asgB1, sb2));
    }

    @Test
    @DisplayName("Support-loan: the receiving department's Manager may reassign the lent holder's task to their own staff, which ends the support")
    public void receivingManagerReassignsALentAssignment() throws Exception {
        assertEquals(200, reassign("aiso_m3", "MANAGER", asgL, sb2),
                "the current holder belongs to another department; only the task's and the new holder's department decide");
        assertEquals(sb2.getId(), holderOf(asgL));
        supportRepository.flush();
        assertNotEquals(SupportStatus.ACTIVE, supportRepository.findById(support.getId()).orElseThrow().getStatus(),
                "reassigning the task ends its support, as before");
    }

    @Test
    @DisplayName("Support-loan: the donor department's Manager can neither see nor reassign the lent assignment (404), and the support stays active")
    public void donorManagerCannotTouchALentAssignment() throws Exception {
        assertEquals(404, reassign("aiso_m1", "MANAGER", asgL, sa2));
        assertEquals(404, reassign("aiso_m1", "MANAGER", asgL, sb2));
        assertEquals(sal.getId(), holderOf(asgL));
        supportRepository.flush();
        assertEquals(SupportStatus.ACTIVE, supportRepository.findById(support.getId()).orElseThrow().getStatus());
    }

    // ── identical 404 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Out-of-scope and nonexistent records are indistinguishable to a Manager: same status and same message")
    public void outOfScopeLooksLikeNonexistent() throws Exception {
        MvcResult outTask = call("aiso_m1", "MANAGER", post("/api/assignments").content(body(tb2, sa2)));
        MvcResult noTask = call("aiso_m1", "MANAGER", post("/api/assignments")
                .content("{\"taskId\": \"" + UUID.randomUUID() + "\", \"staffId\": \"" + sa2.getId() + "\"}"));
        MvcResult nullTask = call("aiso_m1", "MANAGER", post("/api/assignments").content(body(tnull, sa2)));
        assertSame404(outTask, noTask);
        assertSame404(nullTask, noTask);

        MvcResult outStaff = call("aiso_m1", "MANAGER", post("/api/assignments").content(body(ta2, sb2)));
        MvcResult noStaff = call("aiso_m1", "MANAGER", post("/api/assignments")
                .content("{\"taskId\": \"" + ta2.getId() + "\", \"staffId\": \"" + UUID.randomUUID() + "\"}"));
        assertSame404(outStaff, noStaff);

        MvcResult outAssignment = call("aiso_m1", "MANAGER", put("/api/assignments/" + asgB1.getId()).content(reassignBody(sa2)));
        MvcResult noAssignment = call("aiso_m1", "MANAGER", put("/api/assignments/" + UUID.randomUUID()).content(reassignBody(sa2)));
        assertSame404(outAssignment, noAssignment);

        MvcResult outTarget = call("aiso_m1", "MANAGER", put("/api/assignments/" + asgA1.getId()).content(reassignBody(sb2)));
        MvcResult noTarget = call("aiso_m1", "MANAGER", put("/api/assignments/" + asgA1.getId())
                .content("{\"staffId\": \"" + UUID.randomUUID() + "\"}"));
        assertSame404(outTarget, noTarget);
    }

    private void assertSame404(MvcResult a, MvcResult b) throws Exception {
        assertEquals(404, a.getResponse().getStatus());
        assertEquals(404, b.getResponse().getStatus());
        assertEquals(b.getResponse().getErrorMessage(), a.getResponse().getErrorMessage());
        assertEquals(b.getResponse().getContentAsString(), a.getResponse().getContentAsString());
    }

    // ── invalid Managers ─────────────────────────────────────────────────────

    @Test
    @DisplayName("An invalid Manager (no Staff record, inactive account, inactive Staff record, inactive department) is refused with 403 on every operation")
    public void invalidManagersFailClosed() throws Exception {
        for (String manager : new String[] {"aiso_m_nostaff", "aiso_m_inactive_account", "aiso_m_inactive_staff", "aiso_m_closed_department"}) {
            assertEquals(403, status(manager, "MANAGER", get("/api/assignments")), manager + " list");
            assertEquals(403, create(manager, "MANAGER", ta2, sa2), manager + " create");
            assertEquals(403, reassign(manager, "MANAGER", asgA1, sa2), manager + " reassign");
        }
        assertEquals(sa1.getId(), holderOf(asgA1));
        assertEquals(TaskStatus.PENDING, statusOf(ta2));
        assertFalse(assignmentRepository.existsByTaskId(ta2.getId()));
    }

    // ── Staff ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Staff cannot create or reassign assignments (403), and nothing changes")
    public void staffCannotWrite() throws Exception {
        assertEquals(403, create("aiso_sa2", "STAFF", ta2, sa2));
        assertEquals(403, reassign("aiso_sa1", "STAFF", asgA1, sa2));
        assertEquals(sa1.getId(), holderOf(asgA1));
        assertFalse(assignmentRepository.existsByTaskId(ta2.getId()));
    }

    @Test
    @DisplayName("Without signing in, every operation is 401")
    public void unauthenticatedIsRefused() throws Exception {
        assertEquals(401, mockMvc.perform(get("/api/assignments")).andReturn().getResponse().getStatus());
        assertEquals(401, mockMvc.perform(post("/api/assignments").contentType(MediaType.APPLICATION_JSON).content(body(ta2, sa2)))
                .andReturn().getResponse().getStatus());
    }
}
