package com.hotel.workload.notification;

import com.hotel.workload.notification.NotificationService.Notice;
import com.hotel.workload.notification.NotificationService.Outcome;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.verification.EmailSendingException;
import com.hotel.workload.verification.EmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

/**
 * The tracked hand-off used by task reminders: the caller learns at once whether the worker accepted the job, and later, per account,
 * what became of its message. Everything is a mock; nothing is sent.
 */
public class NotificationTrackedDeliveryTest {

    private UserRepository users;
    private NotificationPreferenceService preferences;
    private EmailService email;
    private final EmailContent content = new EmailContent("subject", "text", "<p>html</p>");

    private UUID sendable;
    private UUID optedOut;
    private UUID unverified;
    private UUID failing;
    private UUID sharesAddress;
    private UUID missing;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        preferences = mock(NotificationPreferenceService.class);
        email = mock(EmailService.class);
        when(email.isConfigured()).thenReturn(true);
        sendable = account("a@x.test", true, true);
        optedOut = account("b@x.test", true, true);
        unverified = account("c@x.test", false, true);
        failing = account("d@x.test", true, true);
        sharesAddress = account("A@X.TEST", true, true);
        missing = UUID.randomUUID();
        when(users.findById(missing)).thenReturn(Optional.empty());
        when(preferences.isEmailEnabled(eq(optedOut), eq(NotificationCategory.TASK_REMINDER))).thenReturn(false);
        doThrow(new EmailSendingException("down")).when(email).sendNotificationEmail(eq("d@x.test"), anyString(), anyString(), anyString());
    }

    private UUID account(String address, boolean verified, boolean active) {
        UUID id = UUID.randomUUID();
        User user = mock(User.class);
        when(user.getId()).thenReturn(id);
        when(user.getEmail()).thenReturn(address);
        when(user.isEmailVerified()).thenReturn(verified);
        when(user.isActive()).thenReturn(active);
        when(users.findById(id)).thenReturn(Optional.of(user));
        when(preferences.isEmailEnabled(id, NotificationCategory.TASK_REMINDER)).thenReturn(true);
        return id;
    }

    private NotificationService service(TaskExecutor executor, boolean async) {
        return new NotificationService(users, preferences, email, mock(PlatformTransactionManager.class), executor, async);
    }

    private List<Notice> everyone() {
        return List.of(new Notice(sendable, content), new Notice(optedOut, content), new Notice(unverified, content), new Notice(failing, content),
                new Notice(sharesAddress, content), new Notice(missing, content));
    }

    @Test
    @DisplayName("The worker accepts the job: each account's outcome is reported truthfully (SENT, SKIPPED for ineligible and shared addresses, FAILED for a provider error)")
    void outcomesAreReportedPerAccount() {
        AtomicReference<Map<UUID, Outcome>> seen = new AtomicReference<>();
        assertTrue(service(Runnable::run, true).enqueueTracked(NotificationCategory.TASK_REMINDER, this::everyone, seen::set));
        Map<UUID, Outcome> outcomes = seen.get();
        assertEquals(Outcome.SENT, outcomes.get(sendable));
        assertEquals(Outcome.SKIPPED, outcomes.get(optedOut), "preference off");
        assertEquals(Outcome.SKIPPED, outcomes.get(unverified), "unverified address");
        assertEquals(Outcome.FAILED, outcomes.get(failing), "the provider failed");
        assertEquals(Outcome.SKIPPED, outcomes.get(sharesAddress), "the same address (any letter case) was already served");
        assertEquals(Outcome.SKIPPED, outcomes.get(missing), "no such account");
        verify(email).sendNotificationEmail(eq("a@x.test"), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("The worker REJECTS the job: false is returned, nothing is sent, the recipients are never even resolved and the callback never runs")
    void rejectionIsReported() {
        AtomicBoolean supplierRan = new AtomicBoolean();
        AtomicBoolean callbackRan = new AtomicBoolean();
        TaskExecutor rejecting = task -> { throw new RejectedExecutionException("full"); };
        boolean accepted = service(rejecting, true).enqueueTracked(NotificationCategory.TASK_REMINDER,
                () -> { supplierRan.set(true); return everyone(); }, outcomes -> callbackRan.set(true));
        assertFalse(accepted);
        assertFalse(supplierRan.get());
        assertFalse(callbackRan.get());
        verify(email, never()).sendNotificationEmail(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("A genuinely full bounded queue (the production policy) rejects the next job, and the accepted jobs still run")
    void fullQueueRejects() throws Exception {
        ThreadPoolTaskExecutor real = new ThreadPoolTaskExecutor();
        real.setCorePoolSize(1);
        real.setMaxPoolSize(1);
        real.setQueueCapacity(1);
        real.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        real.initialize();
        try {
            NotificationService service = service(real, true);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch workerBusy = new CountDownLatch(1);
            CountDownLatch finished = new CountDownLatch(2);
            // the first job occupies the single worker, the second fills the one queue slot
            assertTrue(service.enqueueTracked(NotificationCategory.TASK_REMINDER, () -> {
                workerBusy.countDown();
                try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return List.of(new Notice(sendable, content));
            }, o -> finished.countDown()));
            assertTrue(workerBusy.await(10, TimeUnit.SECONDS));
            assertTrue(service.enqueueTracked(NotificationCategory.TASK_REMINDER, () -> List.of(new Notice(unverified, content)), o -> finished.countDown()));
            AtomicBoolean thirdCallback = new AtomicBoolean();
            assertFalse(service.enqueueTracked(NotificationCategory.TASK_REMINDER, () -> List.of(new Notice(sendable, content)), o -> thirdCallback.set(true)),
                    "the queue is full: the third job is refused, not run on the caller's thread");
            release.countDown();
            assertTrue(finished.await(10, TimeUnit.SECONDS), "the two accepted jobs completed");
            assertFalse(thirdCallback.get());
        } finally {
            real.shutdown();
        }
    }

    @Test
    @DisplayName("Email not configured: every named account is FAILED (never SENT, never SKIPPED), and nothing is attempted")
    void notConfigured() {
        when(email.isConfigured()).thenReturn(false);
        AtomicReference<Map<UUID, Outcome>> seen = new AtomicReference<>();
        assertTrue(service(Runnable::run, true).enqueueTracked(NotificationCategory.TASK_REMINDER, this::everyone, seen::set));
        assertTrue(seen.get().values().stream().allMatch(o -> o == Outcome.FAILED), seen.get().toString());
        verify(email, never()).sendNotificationEmail(anyString(), anyString(), anyString(), anyString());
    }

    private static final java.util.function.Function<List<UUID>, EmailContent> MERGE =
            ids -> new EmailContent("merged for " + ids.size(), "merged text", "<p>merged</p>");

    @Test
    @DisplayName("Accounts sharing an address are served by ONE merged email built from exactly the eligible accounts, and every one of them reports the outcome of that email")
    void sharedAddressIsMergedAndEveryAccountGetsTheOutcome() {
        UUID first = account("shared@x.test", true, true);
        UUID second = account("SHARED@X.TEST", true, true);
        UUID optedOutTwin = account("shared@x.test", true, true);
        when(preferences.isEmailEnabled(eq(optedOutTwin), eq(NotificationCategory.TASK_REMINDER))).thenReturn(false);
        AtomicReference<List<UUID>> mergedFrom = new AtomicReference<>();
        AtomicReference<Map<UUID, Outcome>> seen = new AtomicReference<>();
        assertTrue(service(Runnable::run, true).enqueueTracked(NotificationCategory.TASK_REMINDER,
                () -> List.of(new Notice(first, content), new Notice(optedOutTwin, content), new Notice(second, content)),
                seen::set, ids -> { mergedFrom.set(ids); return MERGE.apply(ids); }));
        assertEquals(List.of(first, second), mergedFrom.get(), "the account that opted out is never merged in");
        verify(email, org.mockito.Mockito.times(1)).sendNotificationEmail(eq("shared@x.test"), eq("merged for 2"), anyString(), anyString());
        assertEquals(Outcome.SENT, seen.get().get(first));
        assertEquals(Outcome.SENT, seen.get().get(second), "not SKIPPED just because another account with the same address came first");
        assertEquals(Outcome.SKIPPED, seen.get().get(optedOutTwin), "skipped for its own reason: its preference");
    }

    @Test
    @DisplayName("A shared-address email that the provider REFUSES makes every account behind it FAILED; a broken connection makes every one of them UNKNOWN")
    void sharedAddressFailureOutcomes() {
        UUID first = account("shared2@x.test", true, true);
        UUID second = account("Shared2@x.test", true, true);
        AtomicReference<Map<UUID, Outcome>> refused = new AtomicReference<>();
        doThrow(new EmailSendingException("status code: 400")).when(email).sendNotificationEmail(eq("shared2@x.test"), anyString(), anyString(), anyString());
        service(Runnable::run, true).enqueueTracked(NotificationCategory.TASK_REMINDER,
                () -> List.of(new Notice(first, content), new Notice(second, content)), refused::set, MERGE);
        assertEquals(Outcome.FAILED, refused.get().get(first));
        assertEquals(Outcome.FAILED, refused.get().get(second));

        AtomicReference<Map<UUID, Outcome>> broken = new AtomicReference<>();
        doThrow(new EmailSendingException("Failed to dispatch", new java.io.IOException("timed out"))).when(email)
                .sendNotificationEmail(eq("shared2@x.test"), anyString(), anyString(), anyString());
        service(Runnable::run, true).enqueueTracked(NotificationCategory.TASK_REMINDER,
                () -> List.of(new Notice(first, content), new Notice(second, content)), broken::set, MERGE);
        assertEquals(Outcome.UNKNOWN, broken.get().get(first));
        assertEquals(Outcome.UNKNOWN, broken.get().get(second));
    }

    @Test
    @DisplayName("If combining fails (or yields nothing), nobody's reminder is lost: each account gets its own email")
    void mergeFailureFallsBackToIndividualEmails() {
        UUID first = account("shared3@x.test", true, true);
        UUID second = account("shared3@X.test", true, true);
        AtomicReference<Map<UUID, Outcome>> seen = new AtomicReference<>();
        service(Runnable::run, true).enqueueTracked(NotificationCategory.TASK_REMINDER,
                () -> List.of(new Notice(first, content), new Notice(second, content)), seen::set, ids -> { throw new IllegalStateException("bug"); });
        assertEquals(Outcome.SENT, seen.get().get(first));
        assertEquals(Outcome.SENT, seen.get().get(second));
        verify(email, org.mockito.Mockito.times(2)).sendNotificationEmail(org.mockito.ArgumentMatchers.argThat(a -> a.equalsIgnoreCase("shared3@x.test")), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("What is KNOWN about a failed send: a provider answer or an unconfigured client is FAILED; a broken connection (even nested) or anything unexpected is UNKNOWN")
    void failureClassification() {
        assertEquals(Outcome.FAILED, NotificationService.classify(new EmailSendingException("status code: 401")));
        assertEquals(Outcome.FAILED, NotificationService.classify(new EmailSendingException("SendGrid is not configured.")));
        assertEquals(Outcome.UNKNOWN, NotificationService.classify(new EmailSendingException("Failed to dispatch", new java.io.IOException("reset"))));
        assertEquals(Outcome.UNKNOWN, NotificationService.classify(new EmailSendingException("wrapped", new RuntimeException(new java.net.SocketTimeoutException("slow")))));
        assertEquals(Outcome.UNKNOWN, NotificationService.classify(new IllegalStateException("unexpected")));
    }

    @Test
    @DisplayName("A recipient lookup that throws, or a callback that throws, is contained: no exception reaches the caller")
    void failuresAreContained() {
        AtomicReference<Map<UUID, Outcome>> seen = new AtomicReference<>();
        assertTrue(service(Runnable::run, true).enqueueTracked(NotificationCategory.TASK_REMINDER,
                () -> { throw new IllegalStateException("database down"); }, seen::set));
        assertTrue(seen.get().isEmpty(), "nothing is claimed to have happened");
        assertTrue(service(Runnable::run, true).enqueueTracked(NotificationCategory.TASK_REMINDER, this::everyone,
                o -> { throw new IllegalStateException("bookkeeping failed"); }));
    }
}
