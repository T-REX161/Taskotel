package com.hotel.workload.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs {@link TaskReminderService} every 15 minutes (on the quarter hours, Asia/Kolkata). It exists ONLY when
 * {@code taskotel.reminders.enabled=true}: by default (false) no scheduler is created, nothing is scheduled and no reminder is ever sent.
 * Scheduling is enabled by this class alone, so the feature being off leaves the application exactly as it was.
 *
 * <p><b>The grace period must cover the schedule.</b> A reminder is due only while its moment is at most {@code grace-minutes} in the past
 * ({@link TaskReminderRules}). If the grace period were shorter than the longest gap between two runs, a reminder could fall in that gap and
 * be missed without any error. So when the feature is enabled the application REFUSES TO START unless
 * {@code grace-minutes >= the longest gap between runs of the configured cron}; see {@link #validate}. The defaults (a 15-minute schedule and
 * a 30-minute grace period) satisfy it with room for one failed or retried run.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "taskotel.reminders.enabled", havingValue = "true")
public class TaskReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(TaskReminderScheduler.class);
    static final String DEFAULT_CRON = "0 0/15 * * * *";
    private static final int MAX_FIRES_EXAMINED = 2000;
    private static final Duration SEARCH_HORIZON = Duration.ofDays(400);

    private final TaskReminderService service;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public TaskReminderScheduler(TaskReminderService service,
                                 @Value("${taskotel.reminders.cron:" + DEFAULT_CRON + "}") String cron,
                                 @Value("${taskotel.reminders.grace-minutes:30}") long graceMinutes) {
        validate(cron, graceMinutes);
        this.service = service;
        log.info("Task reminders are ENABLED (cron '{}', {}, grace {} minutes).", cron, TaskReminderRules.ZONE, graceMinutes);
    }

    /**
     * Rejects a configuration that could silently miss reminders: an unusable cron expression, or a grace period shorter than the longest gap
     * between two runs (a reminder moment inside that gap would never be inside a run's window).
     *
     * @throws IllegalStateException with a message that says what to change
     */
    public static void validate(String cron, long graceMinutes) {
        CronExpression expression;
        try {
            expression = CronExpression.parse(cron);
        } catch (RuntimeException e) {
            throw new IllegalStateException("taskotel.reminders.cron is not a valid Spring cron expression (six fields: second minute hour day month weekday): '"
                    + cron + "'. The default is '" + DEFAULT_CRON + "' (every 15 minutes).", e);
        }
        Duration longest = longestGap(expression);
        if (graceMinutes < 1) {
            throw new IllegalStateException("taskotel.reminders.grace-minutes must be at least 1 (it is " + graceMinutes + "); the default is 30.");
        }
        if (Duration.ofMinutes(graceMinutes).compareTo(longest) < 0) {
            long needed = (longest.toMinutes() + (longest.toSecondsPart() > 0 ? 1 : 0));
            throw new IllegalStateException("taskotel.reminders.grace-minutes=" + graceMinutes + " is shorter than the longest gap between scheduler runs ("
                    + needed + " minutes for cron '" + cron + "'). A reminder could fall between two runs and never be sent. Set grace-minutes to at least "
                    + needed + " (the default 30 goes with the default 15-minute schedule), or run the scheduler more often.");
        }
    }

    /** The longest time between two consecutive runs of the expression (examined over many runs, up to about a year). */
    static Duration longestGap(CronExpression expression) {
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime previous = expression.next(start);
        if (previous == null) return SEARCH_HORIZON;
        Duration longest = Duration.ZERO;
        int examined = 0;
        LocalDateTime horizon = start.plus(SEARCH_HORIZON);
        while (examined++ < MAX_FIRES_EXAMINED) {
            LocalDateTime next = expression.next(previous);
            if (next == null || next.isAfter(horizon)) {
                // Fewer runs than the horizon: the gap to "never again" is unbounded in practice
                if (next == null) return longest.compareTo(SEARCH_HORIZON) > 0 ? longest : SEARCH_HORIZON;
                break;
            }
            Duration gap = Duration.of(ChronoUnit.SECONDS.between(previous, next), ChronoUnit.SECONDS);
            if (gap.compareTo(longest) > 0) longest = gap;
            previous = next;
        }
        return longest;
    }

    @Scheduled(cron = "${taskotel.reminders.cron:" + DEFAULT_CRON + "}", zone = "Asia/Kolkata")
    public void tick() {
        // A tick that is still running (a slow database) is never overlapped by the next one
        if (!running.compareAndSet(false, true)) {
            log.warn("The previous task reminder run is still going; this tick was skipped");
            return;
        }
        try {
            service.run(ZonedDateTime.now(TaskReminderRules.ZONE));
        } catch (RuntimeException e) {
            log.error("Task reminder tick failed: {}", e.getClass().getSimpleName());
        } finally {
            running.set(false);
        }
    }
}
