package com.hotel.workload.task;

import com.hotel.workload.assignment.Assignment;
import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.notification.NotificationCategory;
import com.hotel.workload.notification.NotificationPreference;
import com.hotel.workload.notification.NotificationPreferenceRepository;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.ScopeFixtures;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.Availability;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.verification.EmailService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Staff self-service task claiming, over HTTP with the real security chain and real, committed transactions
 * (the status e-mails of later task changes are sent after the commit). SendGrid is a mock; nothing is ever sent.
 *
 * <p>World: department CL_A with Manager {@code cl_mgr}, a second Manager {@code cl_mgr_grant} (primary department CL_B, with an
 * Administrator grant for CL_A), Staff {@code cl_s1}, {@code cl_s2}, {@code cl_s3}; department CL_B with Staff {@code cl_other}.
 */
@SpringBootTest
public class TaskClaimTest {

    private static final String A = "CL_A";
    private static final String B = "CL_B";
    private static final String[] USERS = {"cl_admin", "cl_mgr", "cl_mgr_grant", "cl_mgr_b", "cl_s1", "cl_s2", "cl_s3", "cl_s4", "cl_other"};

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private com.hotel.workload.staff.ManagerViewGrantRepository grantRepository;
    @Autowired private NotificationPreferenceRepository preferenceRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private EmailService emailService;

    private MockMvc mockMvc;
    private ScopeFixtures fixtures;
    private Department deptA;
    private Department deptB;
    private User admin;
    private Staff s1;
    private Staff s2;
    private Staff s3;
    private Staff s4;
    private Staff other;

    @BeforeEach
    public void setUp() {
        org.springframework.security.test.context.TestSecurityContextHolder.clearContext();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(emailService.isConfigured()).thenReturn(true);
        fixtures = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder);
        deptA = fixtures.department(A);
        deptB = fixtures.department(B);
        admin = fixtures.account("cl_admin", Role.ADMIN, true);
        fixtures.manager("cl_mgr", deptA);
        Staff mgrGrant = fixtures.manager("cl_mgr_grant", deptB);
        fixtures.manager("cl_mgr_b", deptB);
        s1 = fixtures.staff("cl_s1", deptA);
        s2 = fixtures.staff("cl_s2", deptA);
        s3 = fixtures.staff("cl_s3", deptA);
        s4 = fixtures.staff("cl_s4", deptA);
        other = fixtures.staff("cl_other", deptB);
        com.hotel.workload.staff.ManagerViewGrant grant = new com.hotel.workload.staff.ManagerViewGrant();
        grant.setManager(mgrGrant.getUser());
        grant.setDepartment(deptA);
        grant.setGrantedByUserId(admin.getId());
        grant.setGrantedByUsername("cl_admin");
        grantRepository.saveAndFlush(grant);
        for (String name : USERS) {
            User account = userRepository.findByUsername(name).orElseThrow();
            account.setEmailVerified(true);
            userRepository.saveAndFlush(account);
            if (!name.equals("cl_admin")) preferenceRepository.saveAndFlush(new NotificationPreference(account.getId(), NotificationCategory.TASK_STATUS, true, true));
        }
    }

    @AfterEach
    public void cleanUp() {
        for (String name : USERS) {
            userRepository.findByUsername(name).ifPresent(u -> {
                grantRepository.findAll().stream().filter(g -> g.getManager().getId().equals(u.getId())).forEach(grantRepository::delete);
                preferenceRepository.deleteAll(preferenceRepository.findByUserId(u.getId()));
            });
        }
        grantRepository.flush();
        {
            // every task the fixtures created (whatever its department, including none), identified by its creator
            taskRepository.findAll().stream().filter(t -> t.getCreatedBy() != null && t.getCreatedBy().getId().equals(admin.getId())).forEach(t -> {
                assignmentRepository.findAll().stream().filter(a -> a.getTask() != null && a.getTask().getId().equals(t.getId())).forEach(assignmentRepository::delete);
                assignmentRepository.flush();
                taskRepository.delete(t);
            });
            taskRepository.flush();
        }
        fixtures.remove(USERS);
        departmentRepository.findByCode(A).ifPresent(departmentRepository::delete);
        departmentRepository.findByCode(B).ifPresent(departmentRepository::delete);
        departmentRepository.flush();
        Mockito.clearInvocations(emailService);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────────────

    private Task task(String title, Department department, TaskStatus status) {
        Task task = new Task();
        task.setTitle(title);
        task.setDescription("Details of " + title);
        task.setPriority(Priority.HIGH);
        task.setStatus(status);
        task.setEstimatedDuration(45);
        task.setDepartment(department);
        task.setCreatedBy(admin);
        return taskRepository.saveAndFlush(task);
    }

    private Task pending(String title) {
        return task(title, deptA, TaskStatus.PENDING);
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String username, String role, String body) throws Exception {
        request = request.with(user(username).roles(role));
        if (body != null) request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mockMvc.perform(request).andReturn();
    }

    private MvcResult available(String username) throws Exception {
        return call(get("/api/tasks/available"), username, "STAFF", null);
    }

    private MvcResult claim(String username, Task task) throws Exception {
        return call(post("/api/tasks/" + task.getId() + "/claim"), username, "STAFF", null);
    }

    private MvcResult start(String username, Task task) throws Exception {
        return call(patch("/api/tasks/" + task.getId() + "/status"), username, "STAFF", "{\"status\":\"IN_PROGRESS\"}");
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    private List<Assignment> assignmentsOf(Task task) {
        return assignmentRepository.findAll().stream().filter(a -> a.getTask() != null && a.getTask().getId().equals(task.getId())).toList();
    }

    private TaskStatus statusOf(Task task) {
        return taskRepository.findById(task.getId()).orElseThrow().getStatus();
    }

    // ── Available Tasks ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Staff see the pending, unassigned tasks of their own department, with the details a card needs")
    public void staffSeeAvailableTasksOfTheirDepartment() throws Exception {
        Task t = pending("Deep clean 402");
        MvcResult result = available("cl_s1");
        assertEquals(200, status(result));
        String json = body(result);
        assertTrue(json.contains("\"canClaim\":true"), json);
        for (String fragment : new String[] {t.getId().toString(), "Deep clean 402", "Details of Deep clean 402", "\"priority\":\"HIGH\"", "\"estimatedDuration\":45",
                "\"department\":\"CL_A\"", "\"status\":\"PENDING\"", "createdAt"}) {
            assertTrue(json.contains(fragment), fragment + " in " + json);
        }
    }

    @Test
    @DisplayName("Staff never see another department's tasks, and a task of another department cannot be claimed (404, nothing changes)")
    public void otherDepartmentsAreInvisibleAndNotClaimable() throws Exception {
        Task foreign = task("Foreign job", deptB, TaskStatus.PENDING);
        pending("Own job");
        String json = body(available("cl_s1"));
        assertFalse(json.contains("Foreign job"), json);
        assertTrue(json.contains("Own job"), json);

        MvcResult result = claim("cl_s1", foreign);
        assertEquals(404, status(result));
        assertTrue(assignmentsOf(foreign).isEmpty());
        assertEquals(TaskStatus.PENDING, statusOf(foreign));
        // a department-less task is equally invisible
        Task noDepartment = task("No department job", null, TaskStatus.PENDING);
        assertFalse(body(available("cl_s1")).contains("No department job"));
        assertEquals(404, status(claim("cl_s1", noDepartment)));
    }

    @Test
    @DisplayName("Assigned, in-progress, completed and cancelled tasks do not appear, and an unassigned task is never listed once somebody holds it")
    public void onlyPendingUnassignedTasksAppear() throws Exception {
        task("Done job", deptA, TaskStatus.COMPLETED);
        task("Cancelled job", deptA, TaskStatus.CANCELLED);
        Task held = pending("Held job");
        Task assigned = pending("Assigned job");
        assertEquals(200, status(claim("cl_s2", assigned)));
        // somebody holds "Held job" through a Manager assignment
        assertEquals(201, status(call(post("/api/assignments"), "cl_mgr", "MANAGER",
                "{\"taskId\":\"" + held.getId() + "\",\"staffId\":\"" + s3.getId() + "\"}")));
        pending("Free job");
        String json = body(available("cl_s1"));
        assertTrue(json.contains("Free job"), json);
        for (String hidden : new String[] {"Done job", "Cancelled job", "Held job", "Assigned job"}) assertFalse(json.contains(hidden), hidden + " in " + json);
    }

    @Test
    @DisplayName("Staff who cannot accept a task get the reason and cannot claim: unavailable or inactive staff get no tasks, a busy member (already holding an active task) still sees the open ones")
    public void ineligibleStaffGetAReasonAndNoTasks() throws Exception {
        Task open = pending("Open job");
        Staff row = staffRepository.findById(s1.getId()).orElseThrow();
        row.setAvailability(Availability.UNAVAILABLE);
        staffRepository.saveAndFlush(row);
        String unavailable = body(available("cl_s1"));
        assertTrue(unavailable.contains("\"canClaim\":false") && unavailable.contains("unavailable") && !unavailable.contains("Open job"), unavailable);

        row.setAvailability(Availability.AVAILABLE);
        staffRepository.saveAndFlush(row);
        Task mine = pending("My own job");
        assertEquals(200, status(claim("cl_s1", mine)));
        String busy = body(available("cl_s1"));
        assertTrue(busy.contains("\"canClaim\":false") && busy.contains("already have an active task") && busy.contains("Open job"), busy);
        assertEquals(409, status(claim("cl_s1", open)), "seeing the task does not allow claiming it");
    }

    // ── claiming ─────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("The first claim wins: one assignment for the claimant, the task is ASSIGNED, the claim time is recorded and no assigner is invented")
    public void firstClaimSucceeds() throws Exception {
        Task t = pending("Prime job");
        MvcResult result = claim("cl_s1", t);
        assertEquals(200, status(result), body(result));
        assertTrue(body(result).contains("\"status\":\"ASSIGNED\""), body(result));
        assertEquals(TaskStatus.ASSIGNED, statusOf(t));
        List<Assignment> rows = assignmentsOf(t);
        assertEquals(1, rows.size());
        assertEquals(s1.getId(), rows.get(0).getStaff().getId());
        assertNull(rows.get(0).getAssignedByUserId(), "accepting a task yourself is not an assignment by anyone");
        assertTrue(rows.get(0).getAssignedAt() != null);
        // it moves from Available to My Tasks
        assertFalse(body(available("cl_s2")).contains("Prime job"));
        assertTrue(body(call(get("/api/tasks"), "cl_s1", "STAFF", null)).contains("Prime job"));
    }

    @Test
    @DisplayName("The loser of a sequential race gets 409 with a clear message, and the winner keeps the task")
    public void secondClaimIsAConflict() throws Exception {
        Task t = pending("Contested job");
        assertEquals(200, status(claim("cl_s1", t)));
        MvcResult loser = claim("cl_s2", t);
        assertEquals(409, status(loser));
        assertTrue(body(loser).contains("no longer available"), body(loser));
        assertEquals(1, assignmentsOf(t).size());
        assertEquals(s1.getId(), assignmentsOf(t).get(0).getStaff().getId());
        // claiming it again yourself is a conflict too, never a second row
        assertEquals(409, status(claim("cl_s1", t)));
        assertEquals(1, assignmentsOf(t).size());
    }

    @Test
    @DisplayName("Concurrent claims of one task: exactly one winner, everyone else 409, exactly one assignment")
    public void concurrentClaimsHaveExactlyOneWinner() throws Exception {
        Task t = pending("Race job");
        String[] claimants = {"cl_s1", "cl_s2", "cl_s3"};
        ExecutorService pool = Executors.newFixedThreadPool(claimants.length);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (String name : claimants) {
                Callable<Integer> attempt = () -> {
                    go.await();
                    return status(claim(name, t));
                };
                results.add(pool.submit(attempt));
            }
            go.countDown();
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> f : results) codes.add(f.get(60, TimeUnit.SECONDS));
            assertEquals(1, codes.stream().filter(c -> c == 200).count(), codes.toString());
            assertEquals(claimants.length - 1, codes.stream().filter(c -> c == 409).count(), codes.toString());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, assignmentsOf(t).size());
        assertEquals(TaskStatus.ASSIGNED, statusOf(t));
    }

    @Test
    @DisplayName("One person claiming two tasks at once ends with exactly one active task")
    public void sameStaffMemberCannotWinTwoTasks() throws Exception {
        Task first = pending("Twin one");
        Task second = pending("Twin two");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (Task t : List.of(first, second)) {
                Callable<Integer> attempt = () -> {
                    go.await();
                    return status(claim("cl_s1", t));
                };
                results.add(pool.submit(attempt));
            }
            go.countDown();
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> f : results) codes.add(f.get(60, TimeUnit.SECONDS));
            assertEquals(1, codes.stream().filter(c -> c == 200).count(), codes.toString());
            assertEquals(1, codes.stream().filter(c -> c == 409).count(), codes.toString());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, assignmentRepository.findByStaffId(s1.getId()).size());
    }

    @Test
    @DisplayName("Admin, Manager and anonymous callers cannot claim or list available tasks; a claim by them changes nothing")
    public void unauthorizedCallersCannotClaim() throws Exception {
        Task t = pending("Guarded job");
        assertEquals(403, status(call(post("/api/tasks/" + t.getId() + "/claim"), "cl_mgr", "MANAGER", null)));
        assertEquals(403, status(call(post("/api/tasks/" + t.getId() + "/claim"), "cl_admin", "ADMIN", null)));
        assertEquals(403, status(call(get("/api/tasks/available"), "cl_mgr", "MANAGER", null)));
        assertEquals(403, status(call(get("/api/tasks/available"), "cl_admin", "ADMIN", null)));
        assertEquals(401, status(mockMvc.perform(post("/api/tasks/" + t.getId() + "/claim")).andReturn()));
        assertEquals(401, status(mockMvc.perform(get("/api/tasks/available")).andReturn()));
        assertTrue(assignmentsOf(t).isEmpty());
        assertEquals(TaskStatus.PENDING, statusOf(t));
    }

    @Test
    @DisplayName("An inactive or unavailable Staff member cannot claim, and a missing task is a plain 404")
    public void ineligibleStaffCannotClaim() throws Exception {
        Task t = pending("Eligibility job");
        Staff row = staffRepository.findById(s1.getId()).orElseThrow();
        row.setAvailability(Availability.UNAVAILABLE);
        staffRepository.saveAndFlush(row);
        assertEquals(409, status(claim("cl_s1", t)));
        row.setAvailability(Availability.AVAILABLE);
        row.setActive(false);
        staffRepository.saveAndFlush(row);
        assertEquals(403, status(claim("cl_s1", t)));
        assertTrue(assignmentsOf(t).isEmpty());
        assertEquals(404, status(call(post("/api/tasks/" + UUID.randomUUID() + "/claim"), "cl_s2", "STAFF", null)));
    }

    @Test
    @DisplayName("A completed or cancelled task cannot be claimed")
    public void finishedTasksCannotBeClaimed() throws Exception {
        Task done = task("Finished", deptA, TaskStatus.COMPLETED);
        Task cancelled = task("Dropped", deptA, TaskStatus.CANCELLED);
        assertEquals(409, status(claim("cl_s1", done)));
        assertEquals(409, status(claim("cl_s1", cancelled)));
        assertTrue(assignmentsOf(done).isEmpty() && assignmentsOf(cancelled).isEmpty());
    }

    @Test
    @DisplayName("Manager assignments are unchanged: a Manager still assigns, several staff still share a task, and a claimed task can be reassigned")
    public void managerAssignmentsStillWork() throws Exception {
        Task shared = pending("Shared job");
        MvcResult many = call(post("/api/assignments"), "cl_mgr", "MANAGER",
                "{\"taskId\":\"" + shared.getId() + "\",\"staffIds\":[\"" + s1.getId() + "\",\"" + s2.getId() + "\"]}");
        assertEquals(201, status(many), body(many));
        assertEquals(2, assignmentsOf(shared).size());
        assertEquals(TaskStatus.ASSIGNED, statusOf(shared));
        assertEquals(409, status(claim("cl_s3", shared)), "an assigned task cannot be claimed");

        Task claimed = pending("Claimed then moved");
        assertEquals(200, status(claim("cl_s3", claimed)));
        UUID assignmentId = assignmentsOf(claimed).get(0).getId();
        assertEquals(s3.getId(), assignmentsOf(claimed).get(0).getStaff().getId());
        assertEquals(204, status(call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/assignments/" + assignmentId), "cl_mgr", "MANAGER", null)));
        assertEquals(TaskStatus.PENDING, statusOf(claimed), "unassigning a claimed task puts it back");
        assertTrue(body(available("cl_s3")).contains("Claimed then moved"));
    }

    // ── Option A: a self-claimed task belongs to exactly one Staff member ──────────────────────────────────────────

    @Test
    @DisplayName("Option A: a self-claimed task is marked, cannot be claimed by anyone else, and a Manager's single or multi-staff assignment cannot add anyone")
    public void selfClaimedTaskStaysExclusive() throws Exception {
        Task t = pending("Mine alone");
        assertEquals(200, status(claim("cl_s1", t)));
        assertTrue(assignmentsOf(t).get(0).isSelfClaimed());

        assertEquals(409, status(claim("cl_s2", t)));
        MvcResult multi = call(post("/api/assignments"), "cl_mgr", "MANAGER", "{\"taskId\":\"" + t.getId() + "\",\"staffIds\":[\"" + s2.getId() + "\",\"" + s3.getId() + "\"]}");
        assertEquals(409, status(multi), body(multi));
        assertTrue(body(multi).contains("belongs to them alone"), body(multi));
        MvcResult single = call(post("/api/assignments"), "cl_mgr", "MANAGER", "{\"taskId\":\"" + t.getId() + "\",\"staffId\":\"" + s2.getId() + "\"}");
        assertEquals(409, status(single), body(single));
        MvcResult byAdmin = call(post("/api/assignments"), "cl_admin", "ADMIN", "{\"taskId\":\"" + t.getId() + "\",\"staffIds\":[\"" + s3.getId() + "\"]}");
        assertEquals(409, status(byAdmin), body(byAdmin));

        assertEquals(1, assignmentsOf(t).size(), "still exactly one holder");
        assertEquals(s1.getId(), assignmentsOf(t).get(0).getStaff().getId());
    }

    @Test
    @DisplayName("Option A is only for self-claims: a Manager's multi-staff assignment is unmarked and unchanged, and a Manager's reassignment of a self-claimed task makes it an ordinary assignment")
    public void managerAssignmentsAreNotMarkedAndReassignmentClearsTheMark() throws Exception {
        Task shared = pending("Manager shared");
        assertEquals(201, status(call(post("/api/assignments"), "cl_mgr", "MANAGER",
                "{\"taskId\":\"" + shared.getId() + "\",\"staffIds\":[\"" + s1.getId() + "\",\"" + s2.getId() + "\"]}")));
        assertEquals(2, assignmentsOf(shared).size());
        assertTrue(assignmentsOf(shared).stream().noneMatch(Assignment::isSelfClaimed));
        UUID managerId = userRepository.findByUsername("cl_mgr").orElseThrow().getId();
        assertEquals(managerId, assignmentsOf(shared).get(0).getAssignedByUserId());

        Task mine = pending("Mine then moved");
        assertEquals(200, status(claim("cl_s3", mine)));
        UUID assignmentId = assignmentsOf(mine).get(0).getId();
        Staff free = s4; // cl_s1 and cl_s2 hold the shared task: reassign to a free person
        MvcResult moved = call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/assignments/" + assignmentId), "cl_mgr", "MANAGER",
                "{\"staffId\":\"" + free.getId() + "\"}");
        assertEquals(200, status(moved), body(moved));
        Assignment after = assignmentsOf(mine).get(0);
        assertEquals(free.getId(), after.getStaff().getId());
        assertFalse(after.isSelfClaimed(), "a Manager chose the holder: an ordinary assignment now");
        assertNull(after.getAssignedByUserId(), "the original assigner (nobody) is kept, as for every reassignment");
    }

    // ── concurrency regressions: what changes while a claim waits for its locks ───────────────────────────────────

    /**
     * Runs {@code change} in a transaction that holds the lock on {@code lockTask} (or on {@code lockStaff} when the task is null), starts a
     * claim of {@code t} by {@code claimant} while that transaction is open, proves the claim is waiting, then commits the change and
     * returns the claim's HTTP status.
     */
    private int claimWhileHeld(Task lockTask, Staff lockStaff, Runnable change, String claimant, Task t) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(txStatus -> {
                if (lockTask != null) taskRepository.lockByIds(Set.of(lockTask.getId()));
                if (lockStaff != null) staffRepository.lockByIds(Set.of(lockStaff.getId()));
                change.run();
                locked.countDown();
                try {
                    release.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertTrue(locked.await(60, TimeUnit.SECONDS), "the change must be holding its lock");
            Future<Integer> claim = pool.submit(() -> status(claim(claimant, t)));
            Thread.sleep(800);
            assertFalse(claim.isDone(), "the claim must wait for the lock, not read around it");
            release.countDown();
            holder.get(60, TimeUnit.SECONDS);
            return claim.get(60, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private void changeStaff(Staff staff, Consumer<Staff> change) {
        Staff row = staffRepository.findById(staff.getId()).orElseThrow();
        change.accept(row);
        staffRepository.saveAndFlush(row);
    }

    @Test
    @DisplayName("A claim that waits while the Staff member is made Unavailable is refused (409): the claim judges the row as committed, not as first read")
    public void availabilityChangedWhileClaimWaits() throws Exception {
        Task t = pending("Availability race");
        int code = claimWhileHeld(null, s1, () -> changeStaff(s1, row -> row.setAvailability(Availability.UNAVAILABLE)), "cl_s1", t);
        assertEquals(409, code);
        assertTrue(assignmentsOf(t).isEmpty());
        assertEquals(TaskStatus.PENDING, statusOf(t));
    }

    @Test
    @DisplayName("A claim that waits while the Staff member is moved to another department is refused (404): the task is no longer in their department")
    public void staffDepartmentChangedWhileClaimWaits() throws Exception {
        Task t = pending("Transfer race");
        int code = claimWhileHeld(null, s1, () -> changeStaff(s1, row -> row.setDepartment(deptB)), "cl_s1", t);
        assertEquals(404, code);
        assertTrue(assignmentsOf(t).isEmpty());
        assertEquals(TaskStatus.PENDING, statusOf(t));
    }

    @Test
    @DisplayName("A claim that waits while the Staff member's account is deactivated is refused (403)")
    public void accountDeactivatedWhileClaimWaits() throws Exception {
        Task t = pending("Deactivation race");
        int code = claimWhileHeld(null, s1, () -> {
            User account = userRepository.findById(s1.getUser().getId()).orElseThrow();
            account.setActive(false);
            userRepository.saveAndFlush(account);
        }, "cl_s1", t);
        assertEquals(403, code);
        assertTrue(assignmentsOf(t).isEmpty());
    }

    @Test
    @DisplayName("A claim that waits while the account's role changes away from STAFF is refused (403)")
    public void roleChangedWhileClaimWaits() throws Exception {
        Task t = pending("Role race");
        int code = claimWhileHeld(null, s1, () -> {
            User account = userRepository.findById(s1.getUser().getId()).orElseThrow();
            account.setRole(Role.MANAGER);
            userRepository.saveAndFlush(account);
        }, "cl_s1", t);
        assertEquals(403, code);
        assertTrue(assignmentsOf(t).isEmpty());
        assertEquals(TaskStatus.PENDING, statusOf(t));
    }

    @Test
    @DisplayName("A claim that waits while the task is moved to another department is refused (404): nothing is assigned across departments")
    public void taskDepartmentChangedWhileClaimWaits() throws Exception {
        Task t = pending("Task moved");
        int code = claimWhileHeld(t, null, () -> {
            Task row = taskRepository.findById(t.getId()).orElseThrow();
            row.setDepartment(deptB);
            taskRepository.saveAndFlush(row);
        }, "cl_s1", t);
        assertEquals(404, code);
        assertTrue(assignmentsOf(t).isEmpty());
        assertEquals(TaskStatus.PENDING, statusOf(t));
    }

    @Test
    @DisplayName("A claim that waits while the task is cancelled is refused (409)")
    public void taskCancelledWhileClaimWaits() throws Exception {
        Task t = pending("Task cancelled");
        int code = claimWhileHeld(t, null, () -> {
            Task row = taskRepository.findById(t.getId()).orElseThrow();
            row.setStatus(TaskStatus.CANCELLED);
            taskRepository.saveAndFlush(row);
        }, "cl_s1", t);
        assertEquals(409, code);
        assertTrue(assignmentsOf(t).isEmpty());
        assertEquals(TaskStatus.CANCELLED, statusOf(t));
    }

    @Test
    @DisplayName("A claim that waits while a Manager assigns the same task wins nothing: one holder, the Manager's choice")
    public void managerAssignmentWinsTheRaceAndClaimIsRefused() throws Exception {
        Task t = pending("Manager first");
        int code = claimWhileHeld(t, null, () -> {
            Assignment a = new Assignment();
            a.setTask(taskRepository.findById(t.getId()).orElseThrow());
            a.setStaff(staffRepository.findById(s2.getId()).orElseThrow());
            assignmentRepository.saveAndFlush(a);
            Task row = taskRepository.findById(t.getId()).orElseThrow();
            row.setStatus(TaskStatus.ASSIGNED);
            taskRepository.saveAndFlush(row);
        }, "cl_s1", t);
        assertEquals(409, code);
        assertEquals(1, assignmentsOf(t).size());
        assertEquals(s2.getId(), assignmentsOf(t).get(0).getStaff().getId());
        assertFalse(assignmentsOf(t).get(0).isSelfClaimed());
    }

    // ── Option A across the remaining assignment paths ─────────────────────────────────────────────────────────────

    private MvcResult edit(String username, String role, Task t, TaskStatus newStatus) throws Exception {
        String body = "{\"title\":\"" + t.getTitle() + "\",\"priority\":\"HIGH\",\"estimatedDuration\":45,\"department\":\"" + t.getDepartment().getCode()
                + "\",\"status\":\"" + newStatus.name() + "\"}";
        return call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/tasks/" + t.getId()), username, role, body);
    }

    private MvcResult move(String username, Task t, TaskStatus newStatus) throws Exception {
        return call(patch("/api/tasks/" + t.getId() + "/status"), username, "STAFF", "{\"status\":\"" + newStatus.name() + "\"}");
    }

    private MvcResult managerAssigns(Task t, Staff... staff) throws Exception {
        String ids = java.util.Arrays.stream(staff).map(s -> "\"" + s.getId() + "\"").collect(Collectors.joining(","));
        return call(post("/api/assignments"), "cl_mgr", "MANAGER", "{\"taskId\":\"" + t.getId() + "\",\"staffIds\":[" + ids + "]}");
    }

    @Test
    @DisplayName("Completing a self-claimed task leaves its single assignment as it was and opens no way to add anyone; the finished task cannot be claimed or assigned")
    public void completionDoesNotOpenASelfClaimedTask() throws Exception {
        Task t = pending("Finish me");
        assertEquals(200, status(claim("cl_s1", t)));
        assertEquals(200, status(move("cl_s1", t, TaskStatus.IN_PROGRESS)));
        assertEquals(409, status(managerAssigns(t, s2)), "a task in progress is still exclusively theirs");
        assertEquals(200, status(move("cl_s1", t, TaskStatus.COMPLETED)));

        assertEquals(1, assignmentsOf(t).size());
        assertTrue(assignmentsOf(t).get(0).isSelfClaimed());
        assertEquals(s1.getId(), assignmentsOf(t).get(0).getStaff().getId());
        assertEquals(400, status(managerAssigns(t, s2, s3)), "a completed task cannot be assigned");
        assertEquals(409, status(claim("cl_s2", t)));
        assertEquals(1, assignmentsOf(t).size());
    }

    @Test
    @DisplayName("Cancelling a self-claimed task keeps its assignment unchanged; the cancelled task cannot be claimed by anyone else or assigned")
    public void cancellationDoesNotOpenASelfClaimedTask() throws Exception {
        Task t = pending("Cancel me");
        assertEquals(200, status(claim("cl_s1", t)));
        MvcResult cancelled = edit("cl_mgr", "MANAGER", t, TaskStatus.CANCELLED);
        assertEquals(200, status(cancelled), body(cancelled));
        assertEquals(TaskStatus.CANCELLED, statusOf(t));
        assertEquals(1, assignmentsOf(t).size());
        assertTrue(assignmentsOf(t).get(0).isSelfClaimed());
        assertEquals(400, status(managerAssigns(t, s2)), "a cancelled task cannot be assigned");
        assertEquals(409, status(claim("cl_s2", t)));
        assertEquals(1, assignmentsOf(t).size());
    }

    @Test
    @DisplayName("A Staff member cannot reassign, unassign or assign through the assignment routes (403): a self-claimed task changes hands only by a Manager or Admin")
    public void staffCannotChangeAssignments() throws Exception {
        Task t = pending("Staff hands off");
        assertEquals(200, status(claim("cl_s1", t)));
        UUID assignmentId = assignmentsOf(t).get(0).getId();
        assertEquals(403, status(call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/assignments/" + assignmentId), "cl_s2", "STAFF", null)));
        assertEquals(403, status(call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/assignments/" + assignmentId), "cl_s1", "STAFF",
                "{\"staffId\":\"" + s2.getId() + "\"}")));
        assertEquals(403, status(call(post("/api/assignments"), "cl_s2", "STAFF", "{\"taskId\":\"" + t.getId() + "\",\"staffId\":\"" + s2.getId() + "\"}")));
        assertEquals(1, assignmentsOf(t).size());
        assertEquals(s1.getId(), assignmentsOf(t).get(0).getStaff().getId());
    }

    @Test
    @DisplayName("A Manager's multi-staff assignment racing a claim of the same task: exactly one outcome, never a self-claimed task with a second assignee")
    public void claimRacingAManagerMultiAssignmentNeverMixesOwners() throws Exception {
        for (int round = 0; round < 4; round++) {
            Task t = pending("Mix race " + round);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch go = new CountDownLatch(1);
            int claimCode;
            int managerCode;
            try {
                Future<Integer> claimed = pool.submit(() -> { go.await(); return status(claim("cl_s1", t)); });
                Future<Integer> assigned = pool.submit(() -> { go.await(); return status(managerAssigns(t, s2, s3)); });
                go.countDown();
                claimCode = claimed.get(60, TimeUnit.SECONDS);
                managerCode = assigned.get(60, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }
            List<Assignment> rows = assignmentsOf(t);
            if (claimCode == 200) {
                assertEquals(409, managerCode, "round " + round);
                assertEquals(1, rows.size(), "round " + round);
                assertTrue(rows.get(0).isSelfClaimed() && rows.get(0).getStaff().getId().equals(s1.getId()), "round " + round);
            } else {
                assertEquals(409, claimCode, "round " + round);
                assertEquals(201, managerCode, "round " + round);
                assertEquals(2, rows.size(), "round " + round);
                assertTrue(rows.stream().noneMatch(Assignment::isSelfClaimed), "round " + round);
            }
            // reset for the next round: nobody holds anything
            assignmentRepository.deleteAll(rows);
            assignmentRepository.flush();
            Task row = taskRepository.findById(t.getId()).orElseThrow();
            row.setStatus(TaskStatus.CANCELLED);
            taskRepository.saveAndFlush(row);
        }
    }
}
