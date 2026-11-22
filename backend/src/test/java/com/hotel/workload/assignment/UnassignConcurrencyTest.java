package com.hotel.workload.assignment;

import com.hotel.workload.security.Role;
import com.hotel.workload.security.ScopeFixtures;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Concurrent removal of staff from a task, against the real database. These tests COMMIT (each request runs in its own transaction on its
 * own thread), so every fixture is removed afterwards. They prove the task is locked first: removals of one task are applied one after the
 * other, so the last one always sees that nobody is left and sets the task to PENDING, and removing the same person twice is one success
 * and one 404, never an error or a half-changed task.
 */
@SpringBootTest
public class UnassignConcurrencyTest {

    private static final String DEPT = "UNAC_D";
    private static final String TAG = "UNAC_";

    @Autowired private AssignmentService assignmentService;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private ScopeFixtures fixtures;
    private Department department;
    private final List<Staff> staff = new ArrayList<>();
    private final List<String> usernames = new ArrayList<>();
    private com.hotel.workload.security.User admin;

    @BeforeEach
    public void setUp() {
        SecurityContextHolder.clearContext();
        fixtures = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder);
        department = fixtures.department(DEPT);
        admin = fixtures.account("unac_admin", Role.ADMIN, true);
        usernames.add("unac_admin");
        for (int i = 1; i <= 4; i++) {
            String username = "unac_s" + i;
            usernames.add(username);
            staff.add(fixtures.staff(username, department));
        }
    }

    @AfterEach
    public void cleanUp() {
        SecurityContextHolder.clearContext();
        for (Task task : taskRepository.findAll()) {
            if (task.getTitle().startsWith(TAG)) {
                assignmentRepository.deleteAll(assignmentRepository.findAllByTaskId(task.getId()));
                taskRepository.delete(task);
            }
        }
        taskRepository.flush();
        fixtures.remove(usernames.toArray(new String[0]));
        departmentRepository.findByCode(DEPT).ifPresent(departmentRepository::delete);
        departmentRepository.flush();
    }

    private Task assignedTask(String title, int... staffIndexes) {
        Task task = new Task();
        task.setTitle(TAG + title);
        task.setPriority(Priority.MEDIUM);
        task.setStatus(TaskStatus.ASSIGNED);
        task.setEstimatedDuration(30);
        task.setDueDate(LocalDate.of(2027, 1, 1));
        task.setDepartment(department);
        task.setCreatedBy(admin);
        task = taskRepository.saveAndFlush(task);
        for (int index : staffIndexes) {
            Assignment assignment = new Assignment();
            assignment.setTask(task);
            assignment.setStaff(staff.get(index));
            assignmentRepository.saveAndFlush(assignment);
        }
        return task;
    }

    private UUID assignmentOf(Task task, int staffIndex) {
        return assignmentRepository.findAllByTaskId(task.getId()).stream()
                .filter((a) -> a.getStaff().getId().equals(staff.get(staffIndex).getId()))
                .findFirst().orElseThrow().getId();
    }

    /** Runs every removal at the same moment, each on its own thread signed in as an Administrator. Returns 204 or the HTTP status of the refusal. */
    private List<Integer> removeTogether(List<UUID> assignmentIds) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(assignmentIds.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (UUID id : assignmentIds) {
            Callable<Integer> call = () -> {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken("unac_admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
                try {
                    start.await();
                    assignmentService.unassign(id);
                    return 204;
                } catch (ResponseStatusException e) {
                    return e.getStatusCode().value();
                } finally {
                    SecurityContextHolder.clearContext();
                }
            };
            futures.add(pool.submit(call));
        }
        start.countDown();
        List<Integer> results = new ArrayList<>();
        for (Future<Integer> future : futures) results.add(future.get(60, TimeUnit.SECONDS)); // a deadlock would time out here
        pool.shutdown();
        return results;
    }

    private TaskStatus statusOf(Task task) {
        return taskRepository.findById(task.getId()).orElseThrow().getStatus();
    }

    @Test
    @DisplayName("Two people removed from one task at the same moment: both removals succeed and the task ends PENDING with nobody on it")
    void removingEveryAssigneeAtOnce() throws Exception {
        Task task = assignedTask("both", 0, 1);
        List<Integer> results = removeTogether(List.of(assignmentOf(task, 0), assignmentOf(task, 1)));
        assertEquals(List.of(204, 204), results);
        assertFalse(assignmentRepository.existsByTaskId(task.getId()));
        assertEquals(TaskStatus.PENDING, statusOf(task), "the removal that came last must see that nobody is left");
    }

    @Test
    @DisplayName("Three of four removed at once: the fourth keeps the task ASSIGNED")
    void removingSomeAtOnce() throws Exception {
        Task task = assignedTask("some", 0, 1, 2, 3);
        List<Integer> results = removeTogether(List.of(assignmentOf(task, 0), assignmentOf(task, 1), assignmentOf(task, 2)));
        assertEquals(List.of(204, 204, 204), results);
        assertEquals(1, assignmentRepository.findAllByTaskId(task.getId()).size());
        assertEquals(TaskStatus.ASSIGNED, statusOf(task));
    }

    @Test
    @DisplayName("The same person removed several times at once: exactly one 204, the rest 404, and the task ends PENDING")
    void removingTheSamePersonRepeatedly() throws Exception {
        Task task = assignedTask("same", 0);
        UUID id = assignmentOf(task, 0);
        List<Integer> results = removeTogether(List.of(id, id, id, id));
        assertEquals(1, results.stream().filter((r) -> r == 204).count(), results.toString());
        assertEquals(3, results.stream().filter((r) -> r == 404).count(), results.toString());
        assertFalse(assignmentRepository.existsByTaskId(task.getId()));
        assertEquals(TaskStatus.PENDING, statusOf(task));
    }

    @Test
    @DisplayName("Removals of different tasks do not block each other: all succeed together")
    void independentRemovalsAllSucceed() throws Exception {
        Task one = assignedTask("indep-1", 0);
        Task two = assignedTask("indep-2", 1);
        List<Integer> results = removeTogether(List.of(assignmentOf(one, 0), assignmentOf(two, 1)));
        assertEquals(List.of(204, 204), results);
        assertEquals(TaskStatus.PENDING, statusOf(one));
        assertEquals(TaskStatus.PENDING, statusOf(two));
    }

    @Test
    @DisplayName("A removal racing a reassignment of the same assignment: one wins cleanly, the task is never left half-changed")
    void removalRacingAReassignment() throws Exception {
        Task task = assignedTask("race", 0);
        UUID id = assignmentOf(task, 0);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> remove = () -> asAdmin(start, () -> assignmentService.unassign(id));
        UpdateAssignmentRequest request = new UpdateAssignmentRequest();
        request.setStaffId(staff.get(1).getId());
        Callable<Integer> reassign = () -> asAdmin(start, () -> assignmentService.reassignTask(id, request));
        Future<Integer> removed = pool.submit(remove);
        Future<Integer> reassigned = pool.submit(reassign);
        start.countDown();
        int removeResult = removed.get(60, TimeUnit.SECONDS);
        int reassignResult = reassigned.get(60, TimeUnit.SECONDS);
        pool.shutdown();

        boolean stillAssigned = assignmentRepository.existsByTaskId(task.getId());
        if (stillAssigned) {
            // the reassignment ran after the removal's loser check, or the removal lost: the task is held by exactly one person and is ASSIGNED
            assertEquals(1, assignmentRepository.findAllByTaskId(task.getId()).size(), "removal " + removeResult + ", reassign " + reassignResult);
            assertEquals(TaskStatus.ASSIGNED, statusOf(task));
        } else {
            assertEquals(200, removeResult, "the helper reports 200 for any success"); // 200 = the call returned without a refusal
            assertEquals(TaskStatus.PENDING, statusOf(task), "removal " + removeResult + ", reassign " + reassignResult);
        }
    }

    private static Integer asAdmin(CountDownLatch start, Runnable action) throws InterruptedException {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("unac_admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        try {
            start.await();
            action.run();
            return 200;
        } catch (ResponseStatusException e) {
            return e.getStatusCode().value();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
