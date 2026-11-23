package com.hotel.workload.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.support.SimpleTriggerContext;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** With the switch ON the scheduler exists and Spring really registers a tick every 15 minutes on the quarter hours, in Asia/Kolkata. */
@SpringBootTest(properties = "taskotel.reminders.enabled=true")
public class TaskReminderSchedulerEnabledTest {

    @Autowired private TaskReminderScheduler scheduler;
    @Autowired private TaskReminderService service;
    @Autowired private ScheduledAnnotationBeanPostProcessor scheduling;

    @Test
    @DisplayName("Enabled: the scheduler bean exists with a 15-minute cron in Asia/Kolkata")
    void schedulerIsConfigured() throws Exception {
        assertNotNull(scheduler);
        Method tick = TaskReminderScheduler.class.getMethod("tick");
        Scheduled scheduled = tick.getAnnotation(Scheduled.class);
        assertNotNull(scheduled);
        assertEquals("Asia/Kolkata", scheduled.zone());
        assertEquals("${taskotel.reminders.cron:0 0/15 * * * *}", scheduled.cron());
    }

    @Test
    @DisplayName("The tick Spring actually registered: the resolved cron is '0 0/15 * * * *' and its next runs are the quarter hours, 15 minutes apart, in IST")
    void theRegisteredTickRunsOnTheQuarterHours() {
        List<CronTask> ticks = scheduling.getScheduledTasks().stream()
                .map(ScheduledTask::getTask)
                .filter(task -> task instanceof CronTask)
                .map(task -> (CronTask) task)
                .filter(task -> task.getRunnable().toString().equals(TaskReminderScheduler.class.getName() + ".tick"))
                .toList();
        assertEquals(1, ticks.size(), "exactly one reminder tick is scheduled");
        CronTask tick = ticks.get(0);
        assertEquals("0 0/15 * * * *", tick.getExpression(), "the placeholder was resolved to the default cron");

        // 14:01 IST (08:31 UTC) -> 14:15, 14:30, 14:45, 15:00 IST
        Clock clock = Clock.fixed(Instant.parse("2026-12-10T08:31:00Z"), ZoneOffset.UTC);
        Instant previous = tick.getTrigger().nextExecution(new SimpleTriggerContext(clock));
        ZonedDateTime first = previous.atZone(TaskReminderRules.ZONE);
        assertEquals(14, first.getHour());
        assertEquals(15, first.getMinute());
        assertEquals(0, first.getSecond());
        for (int i = 0; i < 6; i++) {
            Instant next = tick.getTrigger().nextExecution(new SimpleTriggerContext(Clock.fixed(previous, ZoneOffset.UTC)));
            assertEquals(Duration.ofMinutes(15), Duration.between(previous, next));
            assertTrue(next.atZone(TaskReminderRules.ZONE).getMinute() % 15 == 0);
            previous = next;
        }
    }

    @Test
    @DisplayName("The default grace window is two scheduler runs (30 minutes), so one missed run can be retried and nothing old is ever sent")
    void graceIsTwoRuns() {
        assertEquals(Duration.ofMinutes(30), org.springframework.test.util.ReflectionTestUtils.getField(service, "grace"));
    }
}
