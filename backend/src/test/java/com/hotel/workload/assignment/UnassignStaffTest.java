package com.hotel.workload.assignment;

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
import com.hotel.workload.support.SupportEndReason;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Removing a staff member from a task: DELETE /api/assignments/{id}, through the real security chain and the real (H2) database. Every
 * test rolls back.
 *
 * <p>Fixture: departments ALPHA and BETA. ALPHA has Manager m1 and staff sa1, sa2, sa3. BETA has Manager m3 and staff sb1 and sal, an
 * ALPHA staff member lent to BETA by an ACTIVE support record. Tasks: ta (ALPHA, ASSIGNED to sa1 and sa2: two assignees), tsolo (ALPHA,
 * ASSIGNED to sa3 only), tb (BETA, ASSIGNED to sb1), tl (BETA, ASSIGNED to sal, supported), tdone (COMPLETED) and tcancel (CANCELLED).
 */
@SpringBootTest
@Transactional
public class UnassignStaffTest {

    private static final String ALPHA = "UNAS_ALPHA";
    private static final String BETA = "UNAS_BETA";

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private SupportAssignmentRepository supportRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;
    private User admin;
    private Staff sa1, sa2, sa3, sb1, sal;
    private Task ta, tsolo, tb, tl, tdone, tcancel;
    private Assignment asgA1, asgA2, asgSolo, asgB, asgL, asgDone, asgCancel;
    private SupportAssignment support;

    @BeforeEach
    public void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        ScopeFixtures fixtures = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder);
        Department alpha = fixtures.department(ALPHA);
        Department beta = fixtures.department(BETA);
        admin = fixtures.account("unas_admin", Role.ADMIN, true);
        fixtures.manager("unas_m1", alpha);
        fixtures.manager("unas_m3", beta);
        sa1 = fixtures.staff("unas_sa1", alpha);
        sa2 = fixtures.staff("unas_sa2", alpha);
        sa3 = fixtures.staff("unas_sa3", alpha);
        sb1 = fixtures.staff("unas_sb1", beta);
        sal = fixtures.staff("unas_sal", alpha);
        fixtures.account("unas_m_nostaff", Role.MANAGER, true);

        ta = task("UNAS A two assignees", alpha, TaskStatus.ASSIGNED);
        tsolo = task("UNAS A one assignee", alpha, TaskStatus.ASSIGNED);
        tb = task("UNAS B", beta, TaskStatus.ASSIGNED);
        tl = task("UNAS B lent", beta, TaskStatus.ASSIGNED);
        tdone = task("UNAS done", alpha, TaskStatus.COMPLETED);
        tcancel = task("UNAS cancelled", alpha, TaskStatus.CANCELLED);
        asgA1 = assign(ta, sa1);
        asgA2 = assign(ta, sa2);
        asgSolo = assign(tsolo, sa3);
        asgB = assign(tb, sb1);
        asgL = assign(tl, sal);
        asgDone = assign(tdone, sa1);
        asgCancel = assign(tcancel, sa2);

        support = new SupportAssignment();
        support.setPlanId(UUID.randomUUID());
        support.setStaffId(sal.getId());
        support.setStaffName(sal.getName());
        support.setHomeDepartmentCode(ALPHA);
        support.setReceivingDepartmentCode(BETA);
        support.setTaskId(tl.getId());
        support.setTaskTitle(tl.getTitle());
        support.setAssignmentId(asgL.getId());
        support.setApprovedByUsername("unas_admin");
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

    private MvcResult remove(String username, String role, UUID assignmentId) throws Exception {
        return mockMvc.perform(delete("/api/assignments/" + assignmentId).with(user(username).roles(role))).andReturn();
    }

    private int removeStatus(String username, String role, Assignment assignment) throws Exception {
        return remove(username, role, assignment.getId()).getResponse().getStatus();
    }

    private TaskStatus statusOf(Task task) {
        taskRepository.flush();
        return taskRepository.findById(task.getId()).orElseThrow().getStatus();
    }

    private boolean exists(Assignment assignment) {
        assignmentRepository.flush();
        return assignmentRepository.existsById(assignment.getId());
    }

    private SupportStatus supportStatus() {
        supportRepository.flush();
        return supportRepository.findById(support.getId()).orElseThrow().getStatus();
    }

    private long activeTasksOf(Staff staff) {
        assignmentRepository.flush();
        Object[] row = assignmentRepository.activeWorkloadOfStaff(staff.getId(), List.of(TaskStatus.PENDING, TaskStatus.ASSIGNED, TaskStatus.IN_PROGRESS)).get(0);
        return ((Number) row[1]).longValue();
    }

    // ── authorization ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Admin removes a staff member: 204, and the assignment is gone")
    public void adminRemoves() throws Exception {
        assertEquals(204, removeStatus("unas_admin", "ADMIN", asgA1));
        assertFalse(exists(asgA1));
    }

    @Test
    @DisplayName("A Manager removes a staff member of their own department: 204")
    public void managerRemovesInOwnDepartment() throws Exception {
        assertEquals(204, removeStatus("unas_m1", "MANAGER", asgA1));
        assertFalse(exists(asgA1));
    }

    @Test
    @DisplayName("Staff cannot remove anyone (403), including their own assignment, and nothing changes")
    public void staffCannotRemove() throws Exception {
        assertEquals(403, removeStatus("unas_sa1", "STAFF", asgA1));
        assertEquals(403, removeStatus("unas_sa3", "STAFF", asgSolo));
        assertTrue(exists(asgA1));
        assertTrue(exists(asgSolo));
        assertEquals(TaskStatus.ASSIGNED, statusOf(tsolo));
    }

    @Test
    @DisplayName("Without signing in the request is 401, and nothing changes")
    public void unauthenticatedIsRefused() throws Exception {
        assertEquals(401, mockMvc.perform(delete("/api/assignments/" + asgA1.getId())).andReturn().getResponse().getStatus());
        assertTrue(exists(asgA1));
    }

    @Test
    @DisplayName("A Manager account with no valid Staff record is refused with 403 (fails closed), and nothing changes")
    public void invalidManagerIsRefused() throws Exception {
        assertEquals(403, removeStatus("unas_m_nostaff", "MANAGER", asgA1));
        assertTrue(exists(asgA1));
    }

    // ── Manager department isolation ─────────────────────────────────────────

    @Test
    @DisplayName("A Manager cannot remove from another department's task (404), and nothing changes")
    public void managerCannotRemoveFromAnotherDepartment() throws Exception {
        assertEquals(404, removeStatus("unas_m1", "MANAGER", asgB));
        assertTrue(exists(asgB));
        assertEquals(TaskStatus.ASSIGNED, statusOf(tb));
    }

    @Test
    @DisplayName("Out of scope and nonexistent look the same to a Manager: same status and same message")
    public void outOfScopeLooksLikeNonexistent() throws Exception {
        MvcResult outOfScope = remove("unas_m1", "MANAGER", asgB.getId());
        MvcResult nonexistent = remove("unas_m1", "MANAGER", UUID.randomUUID());
        assertEquals(404, outOfScope.getResponse().getStatus());
        assertEquals(404, nonexistent.getResponse().getStatus());
        assertEquals(nonexistent.getResponse().getErrorMessage(), outOfScope.getResponse().getErrorMessage());
        assertEquals(nonexistent.getResponse().getContentAsString(), outOfScope.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("Support-loan: the task's (receiving) department Manager may remove the lent holder; the donor department's Manager gets 404")
    public void lentHolderIsTheTaskDepartmentsToRemove() throws Exception {
        assertEquals(404, removeStatus("unas_m1", "MANAGER", asgL), "the donor department's Manager cannot reach a BETA task");
        assertTrue(exists(asgL));
        assertEquals(SupportStatus.ACTIVE, supportStatus());

        assertEquals(204, removeStatus("unas_m3", "MANAGER", asgL));
        assertFalse(exists(asgL));
    }

    @Test
    @DisplayName("Admin removes in any department")
    public void adminRemovesAnywhere() throws Exception {
        assertEquals(204, removeStatus("unas_admin", "ADMIN", asgB));
        assertEquals(204, removeStatus("unas_admin", "ADMIN", asgSolo));
    }

    @Test
    @DisplayName("An unknown assignment is 404 for Admin too")
    public void unknownAssignment() throws Exception {
        assertEquals(404, remove("unas_admin", "ADMIN", UUID.randomUUID()).getResponse().getStatus());
    }

    // ── several assignees / last assignee ────────────────────────────────────

    @Test
    @DisplayName("Removing one of several assignees keeps the others and keeps the task ASSIGNED")
    public void removingOneOfSeveral() throws Exception {
        assertEquals(204, removeStatus("unas_m1", "MANAGER", asgA1));
        assertFalse(exists(asgA1));
        assertTrue(exists(asgA2), "the other assignee still holds the task");
        assertEquals(TaskStatus.ASSIGNED, statusOf(ta), "someone still holds it, so it is not Pending");
        assertEquals(1, assignmentRepository.findAllByTaskId(ta.getId()).size());
    }

    @Test
    @DisplayName("Removing the last assignee returns the task to PENDING")
    public void removingTheLastReturnsToPending() throws Exception {
        assertEquals(204, removeStatus("unas_m1", "MANAGER", asgSolo));
        assertFalse(assignmentRepository.existsByTaskId(tsolo.getId()));
        assertEquals(TaskStatus.PENDING, statusOf(tsolo));
    }

    @Test
    @DisplayName("Removing the assignees one after the other: ASSIGNED until the last one, then PENDING")
    public void removingAllOneByOne() throws Exception {
        assertEquals(204, removeStatus("unas_admin", "ADMIN", asgA1));
        assertEquals(TaskStatus.ASSIGNED, statusOf(ta));
        assertEquals(204, removeStatus("unas_admin", "ADMIN", asgA2));
        assertEquals(TaskStatus.PENDING, statusOf(ta));
        assertFalse(assignmentRepository.existsByTaskId(ta.getId()));
    }

    @Test
    @DisplayName("An IN_PROGRESS task whose last assignee is removed returns to PENDING")
    public void inProgressReturnsToPending() throws Exception {
        tsolo.setStatus(TaskStatus.IN_PROGRESS);
        taskRepository.saveAndFlush(tsolo);
        assertEquals(204, removeStatus("unas_m1", "MANAGER", asgSolo));
        assertEquals(TaskStatus.PENDING, statusOf(tsolo));
    }

    @Test
    @DisplayName("Removing the same assignment twice: the second is 404")
    public void removingTwice() throws Exception {
        assertEquals(204, removeStatus("unas_admin", "ADMIN", asgSolo));
        assertEquals(404, removeStatus("unas_admin", "ADMIN", asgSolo));
        assertEquals(TaskStatus.PENDING, statusOf(tsolo));
    }

    // ── finished tasks ───────────────────────────────────────────────────────

    @Test
    @DisplayName("A completed task keeps its assignee and its status (400)")
    public void completedTaskIsProtected() throws Exception {
        assertEquals(400, removeStatus("unas_admin", "ADMIN", asgDone));
        assertEquals(400, removeStatus("unas_m1", "MANAGER", asgDone));
        assertTrue(exists(asgDone));
        assertEquals(TaskStatus.COMPLETED, statusOf(tdone));
    }

    @Test
    @DisplayName("A cancelled task keeps its assignee and its status (400)")
    public void cancelledTaskIsProtected() throws Exception {
        assertEquals(400, removeStatus("unas_admin", "ADMIN", asgCancel));
        assertEquals(400, removeStatus("unas_m1", "MANAGER", asgCancel));
        assertTrue(exists(asgCancel));
        assertEquals(TaskStatus.CANCELLED, statusOf(tcancel));
    }

    // ── workload ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Workload: the removed person's active load drops, the others' does not change, and the person can be assigned again")
    public void workloadIsConsistent() throws Exception {
        assertEquals(1, activeTasksOf(sa1));
        assertEquals(1, activeTasksOf(sa2));

        assertEquals(204, removeStatus("unas_m1", "MANAGER", asgA1));
        assertEquals(0, activeTasksOf(sa1), "sa1 no longer carries the task");
        assertEquals(1, activeTasksOf(sa2), "sa2 still does");

        // sa1 is free again: assigning them to another PENDING task is accepted
        assertEquals(204, removeStatus("unas_m1", "MANAGER", asgSolo));
        assertEquals(201, mockMvc.perform(post("/api/assignments").with(user("unas_m1").roles("MANAGER")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"taskId\": \"" + tsolo.getId() + "\", \"staffId\": \"" + sa1.getId() + "\"}")).andReturn().getResponse().getStatus());
        assertEquals(TaskStatus.ASSIGNED, statusOf(tsolo));
    }

    @Test
    @DisplayName("Workload: a removal that leaves the task Pending frees the person completely (no active task counted)")
    public void lastRemovalFreesThePerson() throws Exception {
        assertEquals(204, removeStatus("unas_admin", "ADMIN", asgSolo));
        assertEquals(0, activeTasksOf(sa3));
    }

    // ── cross-department support lifecycle ───────────────────────────────────

    @Test
    @DisplayName("Support: removing the lent holder of a supported task ends that support (reason: task reassigned)")
    public void removingTheSupportedHolderEndsTheSupport() throws Exception {
        assertEquals(SupportStatus.ACTIVE, supportStatus());
        assertEquals(204, removeStatus("unas_m3", "MANAGER", asgL));
        assertNotEquals(SupportStatus.ACTIVE, supportStatus(), "nobody holds the task any more, so the support ends");
        assertEquals(SupportEndReason.TASK_REASSIGNED, supportRepository.findById(support.getId()).orElseThrow().getEndReason());
        assertEquals(TaskStatus.PENDING, statusOf(tl));
        assertEquals(0, activeTasksOf(sal), "the lent person is free again");
    }

    @Test
    @DisplayName("Support: removing someone else from a supported task keeps the support while the supported person still holds it")
    public void removingAnotherHolderKeepsTheSupport() throws Exception {
        Assignment helper = assign(tl, sb1);
        assertEquals(204, removeStatus("unas_m3", "MANAGER", helper));
        assertTrue(exists(asgL), "the lent person still holds the task");
        assertEquals(SupportStatus.ACTIVE, supportStatus(), "the supported person still holds it, so the support stays");
        assertEquals(TaskStatus.ASSIGNED, statusOf(tl));
    }

    @Test
    @DisplayName("Support: removing the lent person while another holder remains ends the support but keeps the task ASSIGNED")
    public void removingTheSupportedHolderWhileOthersRemain() throws Exception {
        assign(tl, sb1);
        assertEquals(204, removeStatus("unas_m3", "MANAGER", asgL));
        assertNotEquals(SupportStatus.ACTIVE, supportStatus());
        assertEquals(TaskStatus.ASSIGNED, statusOf(tl), "sb1 still holds the task");
    }
}
