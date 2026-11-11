package com.hotel.workload.assignment;

import com.hotel.workload.security.Role;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.Availability;
import com.hotel.workload.staff.Department;
import com.hotel.workload.staff.DepartmentRepository;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import com.hotel.workload.staff.StaffService;
import com.hotel.workload.task.Priority;
import com.hotel.workload.task.Task;
import com.hotel.workload.task.TaskRepository;
import com.hotel.workload.task.TaskService;
import com.hotel.workload.task.UpdateTaskRequest;
import com.hotel.workload.task.TaskStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A staff member may hold one active assignment at a time (a task that is PENDING, ASSIGNED or IN_PROGRESS), and a task has one
 * assignee. Not transactional on purpose: these tests really commit, race several requests, and remove what they created.
 */
@SpringBootTest
public class AssignmentConcurrencyTest {

    private static final String TAG = "asgnrace_";
    private static final List<TaskStatus> ACTIVE = List.of(TaskStatus.PENDING, TaskStatus.ASSIGNED, TaskStatus.IN_PROGRESS);

    @Autowired private AssignmentService assignmentService;

    /**
     * The assignment service now decides by the caller's scope, so a direct call needs a signed-in user. These tests exercise locking and
     * concurrency, not authorization: every call signs in as an administrator for that one call, on whichever thread it runs on
     * (executor threads have no security context of their own), and clears it again.
     */
    private final AssignmentService assignments = new AssignmentService() {
        @Override public AssignmentDto createAssignment(CreateAssignmentRequest request) { return asAdminCall(() -> assignmentService.createAssignment(request)); }
        @Override public java.util.List<AssignmentDto> getAllAssignments() { return asAdminCall(() -> assignmentService.getAllAssignments()); }
        @Override public AssignmentDto reassignTask(UUID id, UpdateAssignmentRequest request) { return asAdminCall(() -> assignmentService.reassignTask(id, request)); }
    };

    private static <T> T asAdminCall(Callable<T> call) {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("concurrency_admin", null,
                        java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
        try {
            return call.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }
    @Autowired private StaffService staffService;
    @Autowired private TaskService taskService;

    /**
     * The task service now decides who may change a task, so a direct call needs a signed-in user. These tests exercise locking and
     * concurrency, not authorization: every call below signs in as an administrator for that one call, on whichever thread it runs on
     * (executor threads have no security context of their own), and clears it again.
     */
    private final TaskService asAdminTasks = new TaskService() {
        private <T> T asAdmin(java.util.concurrent.Callable<T> call) {
            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                    new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("concurrency_admin", null,
                            java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
            try {
                return call.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            } finally {
                org.springframework.security.core.context.SecurityContextHolder.clearContext();
            }
        }

        @Override public java.util.List<com.hotel.workload.task.TaskDto> getAllTasks() { return asAdmin(() -> taskService.getAllTasks()); }
        @Override public com.hotel.workload.task.TaskDto createTask(com.hotel.workload.task.CreateTaskRequest request) { return asAdmin(() -> taskService.createTask(request)); }
        @Override public com.hotel.workload.task.TaskDto updateTask(java.util.UUID id, UpdateTaskRequest request) { return asAdmin(() -> taskService.updateTask(id, request)); }
        @Override public void deleteTask(java.util.UUID id) { asAdmin(() -> { taskService.deleteTask(id); return null; }); }
        @Override public com.hotel.workload.task.TaskDto updateOwnTaskStatus(java.util.UUID id, com.hotel.workload.task.TaskStatus status) { return asAdmin(() -> taskService.updateOwnTaskStatus(id, status)); }
    };
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;

    private Department department;
    private int counter;

    @BeforeEach
    void setUp() {
        department = departmentRepository.findByCode("FRONT_DESK").orElseThrow();
        cleanUp();
    }

    @AfterEach
    void cleanUp() {
        for (Task task : taskRepository.findAll()) {
            if (task.getTitle().startsWith(TAG)) {
                assignmentRepository.deleteAll(assignmentRepository.findAllByTaskId(task.getId()));
                taskRepository.delete(task);
            }
        }
        for (Staff staff : staffRepository.findAllWithUser()) {
            if (staff.getUser().getUsername().startsWith(TAG)) staffRepository.delete(staff);
        }
        for (User user : userRepository.findAll()) {
            if (user.getUsername().startsWith(TAG)) userRepository.delete(user);
        }
    }

    private Staff staff() {
        String username = TAG + "s" + (counter++);
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@hotel.test");
        user.setPassword("not-used");
        user.setRole(Role.STAFF);
        user.setActive(true);
        user = userRepository.saveAndFlush(user);
        Staff staff = new Staff();
        staff.setUser(user);
        staff.setName("Race " + username);
        staff.setEmail(user.getEmail());
        staff.setDepartment(department);
        staff.setAvailability(Availability.AVAILABLE);
        staff.setActive(true);
        return staffRepository.saveAndFlush(staff);
    }

    private Task task() {
        User creator = userRepository.findByUsername(TAG + "creator").orElseGet(() -> {
            User user = new User();
            user.setUsername(TAG + "creator");
            user.setEmail(TAG + "creator@hotel.test");
            user.setPassword("not-used");
            user.setRole(Role.ADMIN);
            user.setActive(true);
            return userRepository.saveAndFlush(user);
        });
        Task task = new Task();
        task.setTitle(TAG + "task" + (counter++));
        task.setPriority(Priority.MEDIUM);
        task.setStatus(TaskStatus.PENDING);
        task.setEstimatedDuration(30);
        task.setDueDate(LocalDate.now().plusDays(1));
        task.setDepartment(department);
        task.setCreatedBy(creator);
        return taskRepository.saveAndFlush(task);
    }

    private void assign(Task task, Staff staff) {
        assignments.createAssignment(new CreateAssignmentRequest(task.getId(), staff.getId()));
    }

    /** Starts every call at the same moment; returns "OK" or the HTTP status each one ended with. */
    private List<String> race(List<Callable<Object>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (Callable<Object> call : calls) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    call.call();
                    return "OK";
                } catch (ResponseStatusException e) {
                    return String.valueOf(e.getStatusCode().value());
                }
            }));
        }
        ready.await();
        go.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> future : futures) outcomes.add(future.get());
        pool.shutdown();
        return outcomes;
    }

    private long activeAssignmentsOf(Staff staff) {
        return assignmentRepository.findByStaffId(staff.getId()).stream()
                .filter(a -> ACTIVE.contains(taskRepository.findById(a.getTask().getId()).orElseThrow().getStatus())).count();
    }

    private static long count(List<String> outcomes, String value) {
        return outcomes.stream().filter(value::equals).count();
    }

    @Test
    @DisplayName("Six tasks assigned to the same staff member at once: exactly one succeeds, the rest get a clean 409")
    void oneStaffMemberCannotGetTwoActiveTasks() throws Exception {
        Staff busy = staff();
        List<Callable<Object>> calls = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Task task = task();
            calls.add(() -> { assign(task, busy); return null; });
        }

        List<String> outcomes = race(calls);

        assertEquals(1, count(outcomes, "OK"), outcomes.toString());
        assertEquals(5, count(outcomes, "409"), outcomes.toString());
        assertEquals(1, activeAssignmentsOf(busy));
    }

    @Test
    @DisplayName("The same task offered to six different staff members at once: exactly one gets it")
    void oneTaskHasOneAssignee() throws Exception {
        Task task = task();
        List<Callable<Object>> calls = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Staff member = staff();
            calls.add(() -> { assign(task, member); return null; });
        }

        List<String> outcomes = race(calls);

        assertEquals(1, count(outcomes, "OK"), outcomes.toString());
        assertEquals(1, assignmentRepository.findAllByTaskId(task.getId()).size());
    }

    @Test
    @DisplayName("Different tasks for different staff members at once: all of them succeed")
    void independentAssignmentsAreNotBlocked() throws Exception {
        List<Callable<Object>> calls = new ArrayList<>();
        List<Staff> people = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Staff member = staff();
            Task task = task();
            people.add(member);
            calls.add(() -> { assign(task, member); return null; });
        }

        List<String> outcomes = race(calls);

        assertEquals(6, count(outcomes, "OK"), outcomes.toString());
        for (Staff member : people) assertEquals(1, activeAssignmentsOf(member));
    }

    @Test
    @DisplayName("Two tasks reassigned to the same free staff member at once: only one move is accepted")
    void concurrentReassignmentsToOnePerson() throws Exception {
        Staff first = staff(), second = staff(), target = staff();
        Task t1 = task(), t2 = task();
        assign(t1, first);
        assign(t2, second);
        UUID a1 = assignmentRepository.findAllByTaskId(t1.getId()).get(0).getId();
        UUID a2 = assignmentRepository.findAllByTaskId(t2.getId()).get(0).getId();

        List<String> outcomes = race(List.of(
                () -> assignments.reassignTask(a1, new UpdateAssignmentRequest(target.getId())),
                () -> assignments.reassignTask(a2, new UpdateAssignmentRequest(target.getId()))));

        assertEquals(1, count(outcomes, "OK"), outcomes.toString());
        assertEquals(1, count(outcomes, "409"), outcomes.toString());
        assertEquals(1, activeAssignmentsOf(target));
    }

    @Test
    @DisplayName("A new assignment and a reassignment to the same staff member at once: only one is accepted")
    void createAndReassignRaceForOnePerson() throws Exception {
        Staff holder = staff(), target = staff();
        Task held = task(), fresh = task();
        assign(held, holder);
        UUID assignmentId = assignmentRepository.findAllByTaskId(held.getId()).get(0).getId();

        List<String> outcomes = race(List.of(
                () -> { assign(fresh, target); return null; },
                () -> assignments.reassignTask(assignmentId, new UpdateAssignmentRequest(target.getId()))));

        assertEquals(1, count(outcomes, "OK"), outcomes.toString());
        assertEquals(1, activeAssignmentsOf(target));
    }

    @Test
    @DisplayName("Deactivating a staff member while a task is assigned to them: never ends with an inactive person holding an active task")
    void deactivationRacesWithAssignment() throws Exception {
        for (int round = 0; round < 5; round++) {
            Staff member = staff();
            Task task = task();
            List<String> outcomes = race(List.of(
                    () -> { assign(task, member); return null; },
                    () -> {
                        // A pool thread has no security context of its own: sign in as an administrator for this call only
                        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("race_admin", null,
                                        java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
                        try {
                            return staffService.deactivateStaff(member.getId());
                        } finally {
                            org.springframework.security.core.context.SecurityContextHolder.clearContext();
                        }
                    }));

            Staff stored = staffRepository.findById(member.getId()).orElseThrow();
            assertFalse(!stored.isActive() && activeAssignmentsOf(member) > 0, "inactive person with an active task: " + outcomes);
            assertTrue(count(outcomes, "OK") >= 1, outcomes.toString());
        }
    }

    private UpdateTaskRequest statusUpdate(Task task, TaskStatus status) {
        UpdateTaskRequest request = new UpdateTaskRequest();
        request.setTitle(task.getTitle());
        request.setPriority(Priority.MEDIUM);
        request.setEstimatedDuration(30);
        request.setDueDate(task.getDueDate());
        request.setDepartment(department.getCode());
        request.setStatus(status);
        return request;
    }

    /**
     * A competing request has locked the task and assigned it to {@code member}, but not committed yet. {@code action} starts meanwhile,
     * so it can only act correctly if it waits for that lock and then reads the committed assignment, not the state from before it.
     */
    private Object runWhileAssignmentIsInFlight(Task task, Staff member, Callable<Object> action) throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = pool.submit(() -> tx.execute(status -> {
            Task mine = taskRepository.lockByIds(Set.of(task.getId())).get(0);
            Assignment assignment = new Assignment();
            assignment.setTask(mine);
            assignment.setStaff(staffRepository.findById(member.getId()).orElseThrow());
            assignmentRepository.saveAndFlush(assignment);
            mine.setStatus(TaskStatus.ASSIGNED);
            taskRepository.saveAndFlush(mine);
            locked.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return null;
        }));
        locked.await();
        Future<Object> waiting = pool.submit(action);
        try {
            Thread.sleep(500);
            assertFalse(waiting.isDone(), "the operation must wait for the task lock instead of acting on the old state");
        } finally {
            release.countDown(); // never leave the lock held, even when the assertion fails
        }
        holder.get(10, TimeUnit.SECONDS);
        Object result = waiting.get(10, TimeUnit.SECONDS);
        pool.shutdown();
        return result;
    }

    @Test
    @DisplayName("Cancelling a task and assigning it at the same moment: a cancel that reports success is never lost")
    void cancellationRacesWithAssignment() throws Exception {
        for (int round = 0; round < 12; round++) {
            Staff member = staff();
            Task task = task();
            List<String> outcomes = race(List.of(
                    () -> { assign(task, member); return null; },
                    () -> asAdminTasks.updateTask(task.getId(), statusUpdate(task, TaskStatus.CANCELLED))));

            Task stored = taskRepository.findById(task.getId()).orElseThrow();
            boolean cancelled = outcomes.get(1).equals("OK");
            assertTrue(cancelled, "cancelling a pending or just-assigned task always succeeds: " + outcomes);
            assertEquals(TaskStatus.CANCELLED, stored.getStatus(), "the cancellation was overwritten by the assignment: " + outcomes);
            if (outcomes.get(0).equals("OK")) assertEquals(1, assignmentRepository.findAllByTaskId(task.getId()).size());
            else assertEquals(0, assignmentRepository.findAllByTaskId(task.getId()).size(), "a refused assignment leaves nothing behind");
        }
    }

    @Test
    @DisplayName("Completing a task while it is reassigned: one consistent end state, and a finished task is never reassigned afterwards")
    void completionRacesWithReassignment() throws Exception {
        for (int round = 0; round < 8; round++) {
            Staff holder = staff(), target = staff();
            Task task = task();
            assign(task, holder);
            UUID assignmentId = assignmentRepository.findAllByTaskId(task.getId()).get(0).getId();

            List<String> outcomes = race(List.of(
                    () -> asAdminTasks.updateTask(task.getId(), statusUpdate(task, TaskStatus.COMPLETED)),
                    () -> assignments.reassignTask(assignmentId, new UpdateAssignmentRequest(target.getId()))));

            assertEquals("OK", outcomes.get(0), outcomes.toString());
            assertEquals(TaskStatus.COMPLETED, taskRepository.findById(task.getId()).orElseThrow().getStatus());
            assertEquals(1, assignmentRepository.findAllByTaskId(task.getId()).size());
            assertEquals(0, activeAssignmentsOf(holder) + activeAssignmentsOf(target), "a completed task holds nobody");
            // once finished, the task can no longer be handed to anyone
            try {
                assignments.reassignTask(assignmentId, new UpdateAssignmentRequest(staff().getId()));
                throw new AssertionError("a completed task must not be reassignable");
            } catch (ResponseStatusException e) {
                assertEquals(400, e.getStatusCode().value());
            }
        }
    }

    @Test
    @DisplayName("Completing a task that is being assigned at that moment waits and then succeeds (it is not refused on the old 'no assignee' state)")
    void completionSeesAnAssignmentThatIsBeingCommitted() throws Exception {
        Task task = task();
        runWhileAssignmentIsInFlight(task, staff(), () -> asAdminTasks.updateTask(task.getId(), statusUpdate(task, TaskStatus.COMPLETED)));
        assertEquals(TaskStatus.COMPLETED, taskRepository.findById(task.getId()).orElseThrow().getStatus());
        assertEquals(1, assignmentRepository.findAllByTaskId(task.getId()).size());
    }

    @Test
    @DisplayName("Deleting a task that is being assigned at that moment waits, then removes the task together with its new assignment")
    void deletionSeesAnAssignmentThatIsBeingCommitted() throws Exception {
        Task task = task();
        runWhileAssignmentIsInFlight(task, staff(), () -> { asAdminTasks.deleteTask(task.getId()); return null; });
        assertTrue(taskRepository.findById(task.getId()).isEmpty());
        assertEquals(0, assignmentRepository.findAllByTaskId(task.getId()).size());
    }


    @Test
    @DisplayName("Valid lifecycle transitions still work: assign, start, complete; cancel; finished tasks cannot be reopened")
    void validLifecycleStillWorks() {
        Staff member = staff();
        Task task = task();
        assign(task, member);
        asAdminTasks.updateTask(task.getId(), statusUpdate(task, TaskStatus.IN_PROGRESS));
        assertEquals(TaskStatus.IN_PROGRESS, taskRepository.findById(task.getId()).orElseThrow().getStatus());
        asAdminTasks.updateTask(task.getId(), statusUpdate(task, TaskStatus.COMPLETED));
        assertEquals(TaskStatus.COMPLETED, taskRepository.findById(task.getId()).orElseThrow().getStatus());
        try {
            asAdminTasks.updateTask(task.getId(), statusUpdate(task, TaskStatus.PENDING));
            throw new AssertionError("a completed task cannot be reopened");
        } catch (ResponseStatusException e) {
            assertEquals(409, e.getStatusCode().value());
        }
        Task other = task();
        asAdminTasks.updateTask(other.getId(), statusUpdate(other, TaskStatus.CANCELLED));
        try {
            assign(other, member);
            throw new AssertionError("a cancelled task cannot be assigned");
        } catch (ResponseStatusException e) {
            assertEquals(400, e.getStatusCode().value());
        }
    }

    @Test
    @DisplayName("Existing behaviour: finishing a task frees the staff member, and reassignment moves the task")
    void completionFreesTheStaffMemberAndReassignmentMovesTheTask() {
        Staff first = staff(), second = staff();
        Task t1 = task(), t2 = task(), t3 = task();
        assign(t1, first);

        try {
            assign(t2, first);
            throw new AssertionError("a second active task for the same person must be refused");
        } catch (ResponseStatusException e) {
            assertEquals(409, e.getStatusCode().value());
        }

        Task done = taskRepository.findById(t1.getId()).orElseThrow();
        done.setStatus(TaskStatus.COMPLETED);
        taskRepository.saveAndFlush(done);
        assign(t2, first); // free again

        UUID assignmentId = assignmentRepository.findAllByTaskId(t2.getId()).get(0).getId();
        assignments.reassignTask(assignmentId, new UpdateAssignmentRequest(second.getId()));
        assertEquals(second.getId(), assignmentRepository.findAllByTaskId(t2.getId()).get(0).getStaff().getId());
        assign(t3, first);
        assertEquals(1, activeAssignmentsOf(first));
        assertEquals(1, activeAssignmentsOf(second));
    }

    // ── a department-scoped Manager (Step 2D) ────────────────────────────────────────────────────────────────

    /** A valid Manager of the test department: active account, active Staff record in it. Removed by cleanUp (user name starts with TAG). */
    private String manager() {
        String username = TAG + "m" + (counter++);
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@hotel.test");
        user.setPassword("not-used");
        user.setRole(Role.MANAGER);
        user.setActive(true);
        user = userRepository.saveAndFlush(user);
        Staff staff = new Staff();
        staff.setUser(user);
        staff.setName("Race " + username);
        staff.setEmail(user.getEmail());
        staff.setDepartment(department);
        staff.setAvailability(Availability.AVAILABLE);
        staff.setActive(true);
        staffRepository.saveAndFlush(staff);
        return username;
    }

    private <T> T asManagerCall(String username, Callable<T> call) {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(username, null,
                        java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_MANAGER"))));
        try {
            return call.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    @DisplayName("Manager path: six tasks assigned to the same staff member at once: exactly one succeeds, the rest get a clean 409")
    void managerPathKeepsTheOneActiveTaskRule() throws Exception {
        String manager = manager();
        Staff busy = staff();
        List<Callable<Object>> calls = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Task task = task();
            calls.add(() -> asManagerCall(manager, () -> assignmentService.createAssignment(new CreateAssignmentRequest(task.getId(), busy.getId()))));
        }

        List<String> outcomes = race(calls);

        assertEquals(1, count(outcomes, "OK"), outcomes.toString());
        assertEquals(5, count(outcomes, "409"), outcomes.toString());
        assertEquals(1, activeAssignmentsOf(busy));
    }

    @Test
    @DisplayName("A Manager assigning a task while an Admin moves it to another department: never both. Either the assignment wins and the move is refused, or the move wins and the Manager gets 404")
    void managerAssignmentRacesWithTheTaskLeavingTheDepartment() throws Exception {
        String manager = manager();
        for (int round = 0; round < 6; round++) {
            Task task = task();
            Staff member = staff();
            UpdateTaskRequest move = statusUpdate(task, TaskStatus.PENDING);
            move.setDepartment("HOUSEKEEPING");

            List<String> outcomes = race(List.of(
                    () -> asManagerCall(manager, () -> assignmentService.createAssignment(new CreateAssignmentRequest(task.getId(), member.getId()))),
                    () -> asAdminTasks.updateTask(task.getId(), move)));

            boolean assigned = assignmentRepository.existsByTaskId(task.getId());
            Task stored = taskRepository.findById(task.getId()).orElseThrow();
            if (assigned) {
                assertEquals("OK", outcomes.get(0), outcomes.toString());
                assertEquals(department.getCode(), stored.getDepartment().getCode(),
                        "an assigned task never ends in a different department from its assignee: " + outcomes);
            } else {
                assertEquals("404", outcomes.get(0), "a Manager who lost the race sees the task as gone: " + outcomes);
                assertEquals("OK", outcomes.get(1), outcomes.toString());
                assertEquals("HOUSEKEEPING", stored.getDepartment().getCode());
            }
        }
    }

    @Test
    @DisplayName("A Manager reassigning while an Admin moves the task to another department: the move or the reassignment, never an inconsistent mix")
    void managerReassignmentRacesWithTheTaskLeavingTheDepartment() throws Exception {
        String manager = manager();
        for (int round = 0; round < 6; round++) {
            Task task = task();
            Staff first = staff();
            Staff second = staff();
            assign(task, first);
            UUID assignmentId = assignmentRepository.findAllByTaskId(task.getId()).get(0).getId();
            UpdateTaskRequest move = statusUpdate(task, TaskStatus.ASSIGNED);
            move.setDepartment("HOUSEKEEPING");

            List<String> outcomes = race(List.of(
                    () -> asManagerCall(manager, () -> assignmentService.reassignTask(assignmentId, new UpdateAssignmentRequest(second.getId()))),
                    () -> asAdminTasks.updateTask(task.getId(), move)));

            Task stored = taskRepository.findById(task.getId()).orElseThrow();
            Staff holder = assignmentRepository.findAllByTaskId(task.getId()).get(0).getStaff();
            if ("OK".equals(outcomes.get(0))) {
                assertEquals(second.getId(), holder.getId(), outcomes.toString());
                assertEquals(department.getCode(), stored.getDepartment().getCode(), "the move must have been refused: " + outcomes);
            } else {
                assertEquals("404", outcomes.get(0), outcomes.toString());
                assertEquals(first.getId(), holder.getId(), "a refused reassignment changes nothing: " + outcomes);
            }
        }
    }
}
