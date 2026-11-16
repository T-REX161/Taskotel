package com.hotel.workload.notification;

import com.hotel.workload.assignment.Assignment;
import com.hotel.workload.assignment.AssignmentRepository;
import com.hotel.workload.notification.WorkloadAlertService.Levels;
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
import com.hotel.workload.verification.EmailService;
import com.hotel.workload.workload.WorkloadStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * N4 with the real asynchronous worker ON (a queue of 1, so that "full" is easy to reach): the state is committed before any email is sent
 * and no transaction or lock is held during the send; a dropped evaluation is healed by the next event; two workers that initialise the
 * same state at once produce exactly one alert. SendGrid is a mock; nothing is ever sent.
 */
@SpringBootTest
@TestPropertySource(properties = {"taskotel.notifications.async=true", "taskotel.notifications.queue-capacity=1"})
public class WorkloadAlertAsyncTest {

    private static final String HOME = "WAA_HOME";
    private static final String[] USERS = {"waa_admin", "waa_mgr", "waa_s1", "waa_s2"};

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private NotificationPreferenceRepository preferenceRepository;
    @Autowired private WorkloadAlertStateRepository stateRepository;
    @Autowired private WorkloadAlertService alerts;
    @Autowired private NotificationService notifications;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired @Qualifier("notificationExecutor") private ThreadPoolTaskExecutor executor;
    @MockitoBean private EmailService emailService;

    private MockMvc mockMvc;
    private ScopeFixtures fixtures;
    private Department home;
    private User admin;
    private Staff s1;
    private Staff s2;
    private final List<java.util.UUID> staffIds = new CopyOnWriteArrayList<>();

    @BeforeEach
    public void setUp() throws Exception {
        waitUntilIdle();
        org.springframework.security.test.context.TestSecurityContextHolder.clearContext();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(emailService.isConfigured()).thenReturn(true);
        fixtures = new ScopeFixtures(userRepository, staffRepository, departmentRepository, passwordEncoder);
        home = fixtures.department(HOME);
        admin = fixtures.account("waa_admin", Role.ADMIN, true);
        fixtures.manager("waa_mgr", home);
        s1 = fixtures.staff("waa_s1", home);
        s2 = fixtures.staff("waa_s2", home);
        for (String name : USERS) {
            User account = userRepository.findByUsername(name).orElseThrow();
            account.setEmailVerified(true);
            userRepository.saveAndFlush(account);
        }
        staffIds.addAll(List.of(s1.getId(), s2.getId()));
        Mockito.clearInvocations(emailService);
    }

    @AfterEach
    public void cleanUp() throws Exception {
        waitUntilIdle();
        taskRepository.findAll().stream().filter(t -> t.getDepartment() != null && HOME.equals(t.getDepartment().getCode())).forEach(t -> {
            assignmentRepository.findAll().stream().filter(a -> a.getTask() != null && a.getTask().getId().equals(t.getId())).forEach(assignmentRepository::delete);
            assignmentRepository.flush();
            taskRepository.delete(t);
        });
        taskRepository.flush();
        stateRepository.deleteAll(stateRepository.findAll().stream().filter(r -> staffIds.contains(r.getStaffId())).toList());
        for (String name : USERS) {
            userRepository.findByUsername(name).ifPresent(u -> preferenceRepository.deleteAll(preferenceRepository.findByUserId(u.getId())));
        }
        fixtures.remove(USERS);
        departmentRepository.findByCode(HOME).ifPresent(departmentRepository::delete);
        departmentRepository.flush();
    }

    private void waitUntilIdle() throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while ((executor.getActiveCount() > 0 || !executor.getThreadPoolExecutor().getQueue().isEmpty()) && System.currentTimeMillis() < deadline) Thread.sleep(20);
    }

    private Task task(String title, int minutes) {
        Task task = new Task();
        task.setTitle(title);
        task.setPriority(Priority.HIGH);
        task.setStatus(TaskStatus.PENDING);
        task.setEstimatedDuration(minutes);
        task.setDepartment(home);
        task.setCreatedBy(admin);
        return taskRepository.saveAndFlush(task);
    }

    private MvcResult assign(Task task, Staff staff) throws Exception {
        return mockMvc.perform(post("/api/assignments").with(user("waa_admin").roles("ADMIN")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"taskId\":\"" + task.getId() + "\",\"staffId\":\"" + staff.getId() + "\"}")).andReturn();
    }

    private MvcResult edit(Task task, int minutes, TaskStatus status) throws Exception {
        return mockMvc.perform(put("/api/tasks/" + task.getId()).with(user("waa_admin").roles("ADMIN")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"" + task.getTitle() + "\",\"priority\":\"HIGH\",\"estimatedDuration\":" + minutes + ",\"department\":\"" + HOME + "\",\"status\":\"" + status.name() + "\"}")).andReturn();
    }

    private static boolean isWorkloadAlert(String subject) {
        return subject.startsWith("Taskotel - Your workload is now") || subject.startsWith("Taskotel - Workload alert:");
    }

    private List<String> alertRecipients() {
        return Mockito.mockingDetails(emailService).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("sendNotificationEmail") && isWorkloadAlert(i.getArgument(1)))
                .map(i -> (String) i.getArgument(0)).sorted().collect(Collectors.toList());
    }

    private String level(java.util.UUID staffId, String audience) {
        return stateRepository.findByStaffIdAndAudience(staffId, audience).map(WorkloadAlertState::getLevel).orElse("(none)");
    }

    // ── the state transaction is finished before any email is sent ──────────────────────────────────────────────

    @Test
    @DisplayName("When the email service is called, the state is already committed and visible, no transaction is open on the worker thread, and the state row is not locked")
    public void stateIsCommittedAndUnlockedWhenTheEmailIsSent() throws Exception {
        AtomicBoolean checked = new AtomicBoolean();
        AtomicBoolean transactionOpen = new AtomicBoolean(true);
        AtomicReference<String> visibleLevel = new AtomicReference<>();
        AtomicReference<Long> lockMillis = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        doAnswer(invocation -> {
            String subject = invocation.getArgument(1);
            if (isWorkloadAlert(subject) && checked.compareAndSet(false, true)) {
                try {
                    transactionOpen.set(TransactionSynchronizationManager.isActualTransactionActive());
                    TransactionTemplate fresh = new TransactionTemplate(transactionManager);
                    fresh.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                    fresh.executeWithoutResult(status -> {
                        visibleLevel.set(level(s1.getId(), "STAFF")); // committed: visible from a brand-new transaction
                        long start = System.nanoTime();
                        stateRepository.lockByStaffIdAndAudience(s1.getId(), "STAFF"); // would wait (up to its 5 s timeout) if a lock were still held
                        lockMillis.set((System.nanoTime() - start) / 1_000_000);
                    });
                } catch (RuntimeException e) {
                    failure.set(e.getClass().getSimpleName() + ": " + e.getMessage());
                } finally {
                    done.countDown();
                }
            }
            return null;
        }).when(emailService).sendNotificationEmail(anyString(), anyString(), anyString(), anyString());

        MvcResult result = assign(task("Heavy", 300), s1);
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertTrue(done.await(20, TimeUnit.SECONDS), "no workload alert reached the email service");

        assertNull(failure.get(), failure.get());
        assertFalse(transactionOpen.get(), "the worker must not be inside a transaction while it calls SendGrid");
        assertEquals("HIGH", visibleLevel.get(), "the state transaction had committed before the email");
        assertTrue(lockMillis.get() < 2000, "the state row was not locked during the send (took " + lockMillis.get() + " ms)");
    }

    // ── a dropped evaluation, and healing by the next event ────────────────────────────────────────────────────

    @Test
    @DisplayName("A full queue drops the evaluation without affecting the operation; the next workload-changing event evaluates the latest state and alerts")
    public void droppedEvaluationIsHealedByTheNextEvent() throws Exception {
        // a row exists for s1 (LOW), created by an earlier processed event
        Task first = task("Small", 50);
        assertEquals(201, assign(first, s1).getResponse().getStatus());
        waitUntilIdle();
        assertEquals("LOW", level(s1.getId(), "STAFF"));
        assertEquals(200, edit(first, 50, TaskStatus.COMPLETED).getResponse().getStatus());
        waitUntilIdle();
        Mockito.clearInvocations(emailService);

        // occupy the single worker, and fill the one queue slot
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch busy = new CountDownLatch(1);
        notifications.runAfterCommit("blocker", () -> {
            busy.countDown();
            try {
                gate.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(busy.await(10, TimeUnit.SECONDS));
        notifications.runAfterCommit("filler", () -> { });

        Task heavy = task("Heavy while full", 300);
        MvcResult result = assign(heavy, s1); // its evaluation (and its assignment email) are dropped and logged
        assertEquals(201, result.getResponse().getStatus(), "a dropped notification never affects the operation");
        assertEquals("LOW", level(s1.getId(), "STAFF"), "the dropped evaluation recorded nothing");
        gate.countDown();
        waitUntilIdle();
        assertTrue(alertRecipients().isEmpty(), "the dropped evaluation sent nothing");

        // the next workload-changing event evaluates the latest committed workload against the stored level
        assertEquals(200, edit(heavy, 310, TaskStatus.ASSIGNED).getResponse().getStatus());
        waitUntilIdle();
        assertEquals(List.of("waa_mgr@scope.test", "waa_s1@scope.test"), alertRecipients(), "the missed rise is detected now");
        assertEquals("HIGH", level(s1.getId(), "STAFF"));
    }

    // ── first-time state creation races ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Eight workers initialising the same Staff member's state at once: exactly one alert per recipient and exactly two state rows")
    public void concurrentFirstTimeCreation() throws Exception {
        Task heavy = task("Heavy, held directly", 300);
        Assignment assignment = new Assignment();
        assignment.setTask(heavy);
        assignment.setStaff(s1);
        assignmentRepository.saveAndFlush(assignment);
        heavy.setStatus(TaskStatus.ASSIGNED);
        taskRepository.saveAndFlush(heavy);
        assertTrue(stateRepository.findByStaffIdAndAudience(s1.getId(), "STAFF").isEmpty());

        int workers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            CountDownLatch ready = new CountDownLatch(workers);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Throwable>> results = new CopyOnWriteArrayList<>();
            Map<java.util.UUID, Levels> before = Map.of(s1.getId(), new Levels(WorkloadStatus.LOW, WorkloadStatus.LOW));
            for (int i = 0; i < workers; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        alerts.evaluate(before, null);
                        return null;
                    } catch (Throwable t) {
                        return t;
                    }
                }));
            }
            assertTrue(ready.await(20, TimeUnit.SECONDS));
            go.countDown();
            for (Future<Throwable> result : results) assertNull(result.get(60, TimeUnit.SECONDS), "no worker may fail");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(List.of("waa_mgr@scope.test", "waa_s1@scope.test"), alertRecipients(), "one alert for the Staff member and one for the Manager, however many workers raced");
        assertEquals("HIGH", level(s1.getId(), "STAFF"));
        assertEquals("HIGH", level(s1.getId(), "MANAGER"));
        assertEquals(2, stateRepository.findAll().stream().filter(r -> r.getStaffId().equals(s1.getId())).count(), "one row per audience");
    }
}
