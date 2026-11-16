package com.hotel.workload.notification;

import com.hotel.workload.notification.NotificationService.Notice;
import com.hotel.workload.security.Role;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.verification.EmailService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * F1b: the bounded asynchronous hand-off after commit. The real worker is on (taskotel.notifications.async=true) with a queue of 2 so
 * that "full" is easy to reach; SendGrid is a mock controlled by a latch. The rest of the suite runs the delivery inline.
 */
@SpringBootTest
@TestPropertySource(properties = {"taskotel.notifications.async=true", "taskotel.notifications.queue-capacity=2"})
public class NotificationAsyncDeliveryTest {

    private static final String P = "as_";

    @Autowired private NotificationService notifications;
    @Autowired private UserRepository userRepository;
    @Autowired private NotificationPreferenceRepository preferenceRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired @Qualifier("notificationExecutor") private ThreadPoolTaskExecutor executor;
    @MockitoBean private EmailService emailService;

    private final List<String> deliveredSubjects = new CopyOnWriteArrayList<>();
    private final List<String> deliveryThreads = new CopyOnWriteArrayList<>();
    private CountDownLatch gate;
    private CountDownLatch firstStarted;

    @BeforeEach
    public void setUp() throws Exception {
        waitUntilWorkerIdle();
        gate = new CountDownLatch(1);
        firstStarted = new CountDownLatch(1);
        deliveredSubjects.clear();
        deliveryThreads.clear();
        when(emailService.isConfigured()).thenReturn(true);
        doAnswer(invocation -> {
            deliveryThreads.add(Thread.currentThread().getName());
            firstStarted.countDown();
            assertTrue(gate.await(20, TimeUnit.SECONDS), "the gate was never opened");
            deliveredSubjects.add(invocation.getArgument(1));
            return null;
        }).when(emailService).sendNotificationEmail(anyString(), anyString(), anyString(), anyString());
    }

    @AfterEach
    public void cleanUp() throws Exception {
        gate.countDown(); // never leave the worker blocked
        waitUntilWorkerIdle();
        userRepository.findAll().stream().filter(u -> u.getUsername().startsWith(P)).forEach(u -> {
            preferenceRepository.deleteAll(preferenceRepository.findByUserId(u.getId()));
            userRepository.delete(u);
        });
        userRepository.flush();
    }

    private void waitUntilWorkerIdle() throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while ((executor.getActiveCount() > 0 || !executor.getThreadPoolExecutor().getQueue().isEmpty()) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }

    private User account(String name) {
        User user = new User();
        user.setUsername(P + name);
        user.setEmail(P + name + "@hotel.test");
        user.setPassword(passwordEncoder.encode("Old@Pass123"));
        user.setRole(Role.STAFF);
        user.setActive(true);
        user.setEmailVerified(true);
        user.setCredentialsSetupRequired(false);
        return userRepository.saveAndFlush(user);
    }

    private void event(User user, String subject) {
        notifications.sendAfterCommit(NotificationCategory.TASK_ASSIGNED, List.of(new Notice(user.getId(), new EmailContent(subject, "t", "<p>h</p>"))));
    }

    private void waitFor(int deliveries) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (deliveredSubjects.size() < deliveries && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertEquals(deliveries, deliveredSubjects.size(), "delivered: " + deliveredSubjects);
    }

    @Test
    @DisplayName("The worker is configured as designed: one thread named notify-, a bounded queue, graceful shutdown")
    public void workerConfiguration() {
        assertEquals(1, executor.getCorePoolSize());
        assertEquals(1, executor.getMaxPoolSize());
        assertEquals("notify-", executor.getThreadNamePrefix());
        assertEquals(2, executor.getThreadPoolExecutor().getQueue().remainingCapacity() + executor.getThreadPoolExecutor().getQueue().size(),
                "the queue capacity comes from taskotel.notifications.queue-capacity");
    }

    @Test
    @DisplayName("The commit returns at once while SendGrid is still blocked; the email is sent afterwards by the notify- thread, not the caller's")
    public void deliveryDoesNotBlockTheCaller() throws Exception {
        User owner = account("block");
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        long start = System.nanoTime();
        template.executeWithoutResult(status -> event(owner, "blocked"));
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(millis < 3000, "the commit must not wait for the email (took " + millis + " ms)");

        assertTrue(firstStarted.await(10, TimeUnit.SECONDS), "the worker picked the event up");
        assertEquals(0, deliveredSubjects.size(), "still blocked inside SendGrid");
        gate.countDown();
        waitFor(1);
        String thread = deliveryThreads.get(0);
        assertTrue(thread.startsWith("notify-"), thread);
        assertNotEquals(Thread.currentThread().getName(), thread);
    }

    @Test
    @DisplayName("After-commit semantics are kept: a rolled-back transaction queues nothing, and nothing is queued before the commit")
    public void rollbackQueuesNothing() throws Exception {
        User owner = account("rollback");
        long before = executor.getThreadPoolExecutor().getTaskCount();
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.executeWithoutResult(status -> {
            event(owner, "never");
            assertEquals(before, executor.getThreadPoolExecutor().getTaskCount(), "not queued while the transaction is open");
            status.setRollbackOnly();
        });
        assertThrows(IllegalStateException.class, () -> template.executeWithoutResult(status -> {
            event(owner, "never either");
            throw new IllegalStateException("the operation failed");
        }));
        assertEquals(before, executor.getThreadPoolExecutor().getTaskCount(), "nothing was queued");
        verify(emailService, never()).sendNotificationEmail(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("A full queue drops the new events and logs, never throws and never runs them on the caller's thread; the rest are delivered in order")
    public void fullQueueDropsAndKeepsOrder() throws Exception {
        User owner = account("full");
        event(owner, "e1");
        assertTrue(firstStarted.await(10, TimeUnit.SECONDS), "e1 is running (blocked) in the worker");
        // the worker is busy with e1; the queue holds 2
        long start = System.nanoTime();
        assertDoesNotThrow(() -> {
            event(owner, "e2");
            event(owner, "e3");
            event(owner, "e4"); // queue full: dropped
            event(owner, "e5"); // dropped
        });
        assertTrue((System.nanoTime() - start) / 1_000_000 < 3000, "dropping must not block the caller");
        assertEquals(0, deliveredSubjects.size());

        gate.countDown();
        waitFor(3);
        Thread.sleep(300); // a dropped event must never show up later
        assertEquals(List.of("e1", "e2", "e3"), deliveredSubjects, "delivered in the order committed; e4 and e5 were dropped");
        for (String thread : deliveryThreads) assertTrue(thread.startsWith("notify-"), "never on the caller's thread: " + thread);
    }

    @Test
    @DisplayName("A failing lookup or send inside the worker is contained: the next event is still delivered")
    public void workerSurvivesFailures() throws Exception {
        gate.countDown();
        User owner = account("fail");
        // All stubbing is done BEFORE any event is submitted: Mockito stubbing is not safe against the worker thread calling the mock meanwhile
        doThrow(new IllegalStateException("send failed")).doAnswer(invocation -> {
            deliveredSubjects.add(invocation.getArgument(1));
            return null;
        }).when(emailService).sendNotificationEmail(anyString(), anyString(), anyString(), anyString());
        notifications.sendAfterCommit(NotificationCategory.TASK_STATUS, () -> {
            throw new IllegalStateException("lookup failed");
        });
        event(owner, "first (fails)");
        event(owner, "second");
        waitFor(1);
        assertEquals(List.of("second"), deliveredSubjects);
    }

    @Test
    @DisplayName("Shutdown drains the queued events (up to 20 seconds) and then refuses new ones, which are dropped")
    public void shutdownDrainsTheQueue() {
        ThreadPoolTaskExecutor own = new NotificationDeliveryConfig().notificationExecutor(1000, Mockito.mock(EmailService.class));
        own.afterPropertiesSet();
        assertEquals(1000, own.getThreadPoolExecutor().getQueue().remainingCapacity(), "the default capacity is 1000");
        List<Integer> done = new CopyOnWriteArrayList<>();
        for (int i = 1; i <= 3; i++) {
            int n = i;
            own.execute(() -> {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                done.add(n);
            });
        }
        own.shutdown(); // waits for the queued tasks, up to the configured 20 seconds
        assertEquals(List.of(1, 2, 3), done, "all queued events were delivered, in order, before the executor closed");
        assertThrows(RejectedExecutionException.class, () -> own.execute(() -> done.add(4)), "after shutdown new events are rejected");
        assertEquals(3, done.size());
    }
}
