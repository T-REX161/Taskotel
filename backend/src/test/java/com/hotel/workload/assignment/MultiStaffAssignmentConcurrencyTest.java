package com.hotel.workload.assignment;

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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrent multi-staff assignments against the real database. These tests COMMIT (the requests run in separate transactions on separate
 * threads), so every fixture is removed afterwards. They prove the locking: the task is locked first, then every selected staff member in id
 * order, so requests are applied one after the other and none can deadlock or leave a half-made assignment.
 */
@SpringBootTest
public class MultiStaffAssignmentConcurrencyTest {

    private static final String DEPT = "MSAC_D";
    private static final String TAG = "MSAC_";

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
        admin = fixtures.account("msac_admin", com.hotel.workload.security.Role.ADMIN, true);
        usernames.add("msac_admin");
        for (int i = 1; i <= 6; i++) {
            String username = "msac_s" + i;
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

    private Task task(String title) {
        Task task = new Task();
        task.setTitle(TAG + title);
        task.setPriority(Priority.MEDIUM);
        task.setStatus(TaskStatus.PENDING);
        task.setEstimatedDuration(30);
        task.setDueDate(LocalDate.of(2027, 1, 1));
        task.setDepartment(department);
        task.setCreatedBy(admin);
        return taskRepository.saveAndFlush(task);
    }

    private static List<UUID> ids(List<Staff> members) {
        List<UUID> ids = new ArrayList<>();
        for (Staff member : members) ids.add(member.getId());
        return ids;
    }

    /** Runs every request at the same moment, each on its own thread signed in as an Administrator. Returns 201 or the HTTP status of the refusal. */
    private List<Integer> runTogether(List<CreateAssignmentRequest> requests) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(requests.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (CreateAssignmentRequest request : requests) {
            futures.add(pool.submit(() -> {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken("msac_admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
                try {
                    start.await();
                    assignmentService.createAssignments(request);
                    return 201;
                } catch (ResponseStatusException e) {
                    return e.getStatusCode().value();
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }));
        }
        start.countDown();
        List<Integer> results = new ArrayList<>();
        for (Future<Integer> future : futures) results.add(future.get(60, TimeUnit.SECONDS)); // a deadlock would time out here
        pool.shutdown();
        return results;
    }

    @Test
    @DisplayName("Two requests for the same task with overlapping staff: exactly one succeeds, the other is 409, and the task holds exactly the winner's staff")
    void sameTaskTwoRequests() throws Exception {
        Task task = task("same-task");
        List<Integer> results = runTogether(List.of(
                CreateAssignmentRequest.forStaff(task.getId(), ids(staff.subList(0, 3))),
                CreateAssignmentRequest.forStaff(task.getId(), ids(staff.subList(2, 5)))));
        assertEquals(1, results.stream().filter((r) -> r == 201).count(), results.toString());
        assertEquals(1, results.stream().filter((r) -> r == 409).count(), results.toString());
        List<Assignment> rows = assignmentRepository.findAllByTaskId(task.getId());
        assertEquals(3, rows.size(), "all of the winner's three, none of the loser's");
        Set<UUID> holders = new HashSet<>();
        rows.forEach((a) -> holders.add(a.getStaff().getId()));
        assertTrue(holders.equals(new HashSet<>(ids(staff.subList(0, 3)))) || holders.equals(new HashSet<>(ids(staff.subList(2, 5)))), "exactly one request's staff");
        assertEquals(TaskStatus.ASSIGNED, taskRepository.findById(task.getId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("Many identical requests at once: one winner, the rest 409, and one row per person (never duplicates)")
    void manyIdenticalRequests() throws Exception {
        Task task = task("identical");
        List<CreateAssignmentRequest> requests = new ArrayList<>();
        for (int i = 0; i < 6; i++) requests.add(CreateAssignmentRequest.forStaff(task.getId(), ids(staff.subList(0, 3))));
        List<Integer> results = runTogether(requests);
        assertEquals(1, results.stream().filter((r) -> r == 201).count(), results.toString());
        assertEquals(5, results.stream().filter((r) -> r == 409).count(), results.toString());
        assertEquals(3, assignmentRepository.findAllByTaskId(task.getId()).size());
    }

    @Test
    @DisplayName("Different tasks that share staff, listed in opposite orders: no deadlock (staff are locked in id order), and nobody ends up with two active assignments")
    void sharedStaffOppositeOrders() throws Exception {
        Task first = task("opposite-1");
        Task second = task("opposite-2");
        List<Staff> forward = staff.subList(0, 3);
        List<Staff> backward = new ArrayList<>(forward);
        java.util.Collections.reverse(backward);
        List<Integer> results = runTogether(List.of(
                CreateAssignmentRequest.forStaff(first.getId(), ids(forward)),
                CreateAssignmentRequest.forStaff(second.getId(), ids(backward))));
        assertEquals(1, results.stream().filter((r) -> r == 201).count(), results.toString());
        assertEquals(1, results.stream().filter((r) -> r == 409).count(), "the loser finds its staff busy: " + results);
        long total = assignmentRepository.findAllByTaskId(first.getId()).size() + assignmentRepository.findAllByTaskId(second.getId()).size();
        assertEquals(3, total, "three rows in all: one winner, no half-made assignment");
        for (Staff member : forward) {
            long active = assignmentRepository.findAllByTaskId(first.getId()).stream().filter((a) -> a.getStaff().getId().equals(member.getId())).count()
                    + assignmentRepository.findAllByTaskId(second.getId()).stream().filter((a) -> a.getStaff().getId().equals(member.getId())).count();
            assertTrue(active <= 1, member.getName() + " holds at most one task");
        }
    }

    @Test
    @DisplayName("Requests for different tasks with different staff all succeed together")
    void independentRequestsAllSucceed() throws Exception {
        Task one = task("independent-1");
        Task two = task("independent-2");
        List<Integer> results = runTogether(List.of(
                CreateAssignmentRequest.forStaff(one.getId(), ids(staff.subList(0, 2))),
                CreateAssignmentRequest.forStaff(two.getId(), ids(staff.subList(2, 5)))));
        assertEquals(List.of(201, 201), results);
        assertEquals(2, assignmentRepository.findAllByTaskId(one.getId()).size());
        assertEquals(3, assignmentRepository.findAllByTaskId(two.getId()).size());
    }
}
