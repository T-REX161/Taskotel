package com.hotel.workload.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.support.CronExpression;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The scheduler's startup rules and its overlap guard, without a database: the grace period must cover the schedule (otherwise the
 * application refuses to start), the feature is absent unless switched on, and a slow run is never overlapped by the next tick.
 */
public class TaskReminderSchedulerTest {

    private static final String QUARTER_HOURS = "0 0/15 * * * *";

    // ── grace-period validation ──────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("The defaults (15-minute schedule, 30-minute grace) are valid, and so is a grace equal to the interval")
    void validConfigurations() {
        assertDoesNotThrow(() -> TaskReminderScheduler.validate(QUARTER_HOURS, 30));
        assertDoesNotThrow(() -> TaskReminderScheduler.validate(QUARTER_HOURS, 15));
        assertDoesNotThrow(() -> TaskReminderScheduler.validate(QUARTER_HOURS, 120));
        assertDoesNotThrow(() -> TaskReminderScheduler.validate("0 0 * * * *", 60), "an hourly schedule needs at least an hour");
        assertDoesNotThrow(() -> TaskReminderScheduler.validate("0 */5 * * * *", 5));
    }

    @Test
    @DisplayName("A grace period shorter than the longest gap between runs is rejected with a message that says what to set")
    void graceShorterThanTheScheduleIsRejected() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> TaskReminderScheduler.validate(QUARTER_HOURS, 10));
        assertTrue(e.getMessage().contains("grace-minutes=10") && e.getMessage().contains("15 minutes") && e.getMessage().contains("at least 15"), e.getMessage());
        IllegalStateException hourly = assertThrows(IllegalStateException.class, () -> TaskReminderScheduler.validate("0 0 * * * *", 30));
        assertTrue(hourly.getMessage().contains("60 minutes"), hourly.getMessage());
        assertThrows(IllegalStateException.class, () -> TaskReminderScheduler.validate("0 0 9 * * MON", 30), "a weekly schedule cannot be covered by 30 minutes");
        assertThrows(IllegalStateException.class, () -> TaskReminderScheduler.validate(QUARTER_HOURS, 14));
    }

    @Test
    @DisplayName("An uneven schedule is judged by its LONGEST gap, not its average")
    void longestGapDecides() {
        // runs at minutes 0, 5 and 30: gaps of 5, 25 and 30 minutes
        assertEquals(Duration.ofMinutes(30), TaskReminderScheduler.longestGap(CronExpression.parse("0 0,5,30 * * * *")));
        assertThrows(IllegalStateException.class, () -> TaskReminderScheduler.validate("0 0,5,30 * * * *", 29));
        assertDoesNotThrow(() -> TaskReminderScheduler.validate("0 0,5,30 * * * *", 30));
        assertEquals(Duration.ofMinutes(15), TaskReminderScheduler.longestGap(CronExpression.parse(QUARTER_HOURS)));
    }

    @Test
    @DisplayName("A zero or negative grace period and an unusable cron expression are rejected with clear messages")
    void otherInvalidValues() {
        IllegalStateException zero = assertThrows(IllegalStateException.class, () -> TaskReminderScheduler.validate(QUARTER_HOURS, 0));
        assertTrue(zero.getMessage().contains("grace-minutes must be at least 1"), zero.getMessage());
        assertThrows(IllegalStateException.class, () -> TaskReminderScheduler.validate(QUARTER_HOURS, -5));
        IllegalStateException cron = assertThrows(IllegalStateException.class, () -> TaskReminderScheduler.validate("every quarter hour", 30));
        assertTrue(cron.getMessage().contains("not a valid Spring cron expression") && cron.getMessage().contains("every quarter hour"), cron.getMessage());
    }

    // ── startup behaviour ────────────────────────────────────────────────────────────────────────────────────────

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(TaskReminderScheduler.class)
                .withBean(TaskReminderService.class, () -> mock(TaskReminderService.class));
    }

    @Test
    @DisplayName("Enabled with an invalid grace period, the application REFUSES TO START with the explanation")
    void invalidConfigurationStopsStartup() {
        runner().withPropertyValues("taskotel.reminders.enabled=true", "taskotel.reminders.grace-minutes=10").run(context -> {
            assertTrue(context.getStartupFailure() != null, "the context must not start");
            Throwable root = context.getStartupFailure();
            while (root.getCause() != null) root = root.getCause();
            assertTrue(root.getMessage().contains("grace-minutes=10"), root.getMessage());
        });
        runner().withPropertyValues("taskotel.reminders.enabled=true", "taskotel.reminders.cron=0 0 * * * *", "taskotel.reminders.grace-minutes=30").run(context ->
                assertTrue(context.getStartupFailure() != null, "an hourly schedule with a 30-minute grace period is refused"));
    }

    @Test
    @DisplayName("Enabled with the defaults (or a valid override) the scheduler starts")
    void validConfigurationStarts() {
        runner().withPropertyValues("taskotel.reminders.enabled=true").run(context -> {
            assertEquals(null, context.getStartupFailure());
            assertEquals(1, context.getBeansOfType(TaskReminderScheduler.class).size());
        });
        runner().withPropertyValues("taskotel.reminders.enabled=true", "taskotel.reminders.grace-minutes=15").run(context ->
                assertEquals(null, context.getStartupFailure()));
    }

    @Test
    @DisplayName("Disabled (the default), an invalid grace period is irrelevant: no scheduler, no scheduling infrastructure, and the application starts")
    void disabledByDefaultNeedsNothing() {
        runner().run(context -> {
            assertEquals(null, context.getStartupFailure());
            assertTrue(context.getBeansOfType(TaskReminderScheduler.class).isEmpty());
            assertTrue(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).isEmpty());
        });
        runner().withPropertyValues("taskotel.reminders.enabled=false", "taskotel.reminders.grace-minutes=1").run(context -> {
            assertEquals(null, context.getStartupFailure());
            assertTrue(context.getBeansOfType(TaskReminderScheduler.class).isEmpty());
        });
    }

    // ── the overlap guard ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A tick that arrives while the previous run is still going is skipped (the service runs once), and the next tick after it ends runs normally")
    void overlappingTickIsSkipped() throws Exception {
        TaskReminderService service = mock(TaskReminderService.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        doAnswer(invocation -> {
            if (runs.incrementAndGet() == 1) {
                started.countDown();
                release.await(10, TimeUnit.SECONDS);
            }
            return null;
        }).when(service).run(any());
        TaskReminderScheduler scheduler = new TaskReminderScheduler(service, QUARTER_HOURS, 30);

        Thread slow = new Thread(scheduler::tick);
        slow.start();
        assertTrue(started.await(10, TimeUnit.SECONDS));
        scheduler.tick();                                     // the next tick, while the first is still running
        verify(service, times(1)).run(any());
        release.countDown();
        slow.join(10_000);
        scheduler.tick();                                     // the guard was released
        verify(service, times(2)).run(any());
    }

    @Test
    @DisplayName("A run that throws never escapes the scheduler and never leaves the guard stuck")
    void failingRunReleasesTheGuard() {
        TaskReminderService service = mock(TaskReminderService.class);
        doAnswer(invocation -> { throw new IllegalStateException("boom"); }).when(service).run(any());
        TaskReminderScheduler scheduler = new TaskReminderScheduler(service, QUARTER_HOURS, 30);
        assertDoesNotThrow(scheduler::tick);
        assertDoesNotThrow(scheduler::tick);
        verify(service, times(2)).run(any());
    }
}
