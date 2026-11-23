package com.hotel.workload.notification;

import com.hotel.workload.notification.TaskReminderRules.Fire;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WHEN reminders fall: pure schedule rules, every moment in Asia/Kolkata. The JVM default zone is forced to UTC (what production runs in)
 * and some moments are given as UTC instants, to show the answers never depend on the server's zone.
 */
public class TaskReminderRulesTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Duration GRACE = Duration.ofMinutes(30);
    private static final LocalDate DUE = LocalDate.of(2026, 12, 10);
    private static final LocalTime TIME = LocalTime.of(14, 0);

    private TimeZone original;

    @BeforeEach
    void serverRunsInUtc() {
        original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @AfterEach
    void restore() {
        TimeZone.setDefault(original);
    }

    private static ZonedDateTime ist(int month, int day, int hour, int minute) {
        return ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, IST);
    }

    private static List<Fire> timed(ZonedDateTime now) {
        return TaskReminderRules.dueAt(DUE, TIME, now, GRACE);
    }

    private static List<Fire> dateOnly(ZonedDateTime now) {
        return TaskReminderRules.dueAt(DUE, null, now, GRACE);
    }

    private static Fire fire(ReminderKind kind, int slot) {
        return new Fire(kind, slot);
    }

    @Test
    @DisplayName("A task with a deadline time: 24 hours and 2 hours before it, only inside the grace window")
    void dueSoonWindows() {
        assertEquals(List.of(), timed(ist(12, 9, 13, 59)), "before the 24 h moment");
        assertEquals(List.of(fire(ReminderKind.DUE_24H, 0)), timed(ist(12, 9, 14, 0)), "exactly 24 h before");
        assertEquals(List.of(fire(ReminderKind.DUE_24H, 0)), timed(ist(12, 9, 14, 29)), "inside the grace window");
        assertEquals(List.of(), timed(ist(12, 9, 14, 30)), "the window has closed: never sent late");
        assertEquals(List.of(), timed(ist(12, 10, 11, 59)));
        assertEquals(List.of(fire(ReminderKind.DUE_2H, 0)), timed(ist(12, 10, 12, 0)), "exactly 2 h before");
        assertEquals(List.of(fire(ReminderKind.DUE_2H, 0)), timed(ist(12, 10, 12, 15)));
        assertEquals(List.of(), timed(ist(12, 10, 12, 30)));
        assertEquals(List.of(), timed(ist(12, 10, 13, 59)), "no third due-soon reminder");
    }

    @Test
    @DisplayName("A task with a deadline time: overdue first at the due moment, then every day at 09:00")
    void overdueTimed() {
        assertEquals(List.of(fire(ReminderKind.OVERDUE, 0)), timed(ist(12, 10, 14, 0)), "at the due moment");
        assertEquals(List.of(fire(ReminderKind.OVERDUE, 0)), timed(ist(12, 10, 14, 15)));
        assertEquals(List.of(), timed(ist(12, 10, 14, 30)), "too late for the first one");
        assertEquals(List.of(), timed(ist(12, 11, 8, 59)), "the next one is at 09:00");
        assertEquals(List.of(fire(ReminderKind.OVERDUE, 1)), timed(ist(12, 11, 9, 0)));
        assertEquals(List.of(fire(ReminderKind.OVERDUE, 1)), timed(ist(12, 11, 9, 15)));
        assertEquals(List.of(), timed(ist(12, 11, 9, 30)));
        assertEquals(List.of(fire(ReminderKind.OVERDUE, 2)), timed(ist(12, 12, 9, 0)));
        assertEquals(List.of(fire(ReminderKind.OVERDUE, 3)), timed(ist(12, 13, 9, 0)));
        assertEquals(List.of(fire(ReminderKind.OVERDUE, 9)), timed(ist(12, 19, 9, 0)), "the slot is the current day, so a late assignee gets the current slot only");
    }

    @Test
    @DisplayName("A deadline early in the day: the first daily reminder is the next morning, not an hour after the deadline")
    void earlyDeadline() {
        List<Fire> none = TaskReminderRules.dueAt(DUE, LocalTime.of(8, 0), ist(12, 10, 9, 0), GRACE);
        assertEquals(List.of(), none, "09:00 on the due date is an hour after an 08:00 deadline");
        assertEquals(List.of(fire(ReminderKind.OVERDUE, 0)), TaskReminderRules.dueAt(DUE, LocalTime.of(8, 0), ist(12, 10, 8, 0), GRACE));
        assertEquals(List.of(fire(ReminderKind.OVERDUE, 1)), TaskReminderRules.dueAt(DUE, LocalTime.of(8, 0), ist(12, 11, 9, 0), GRACE));
    }

    @Test
    @DisplayName("A date-only task: due-today at 09:00 on the due date")
    void dueTodayDateOnly() {
        assertEquals(List.of(), dateOnly(ist(12, 9, 9, 0)), "the day before: nothing");
        assertEquals(List.of(), dateOnly(ist(12, 10, 8, 59)));
        assertEquals(List.of(fire(ReminderKind.DUE_TODAY, 0)), dateOnly(ist(12, 10, 9, 0)));
        assertEquals(List.of(fire(ReminderKind.DUE_TODAY, 0)), dateOnly(ist(12, 10, 9, 29)));
        assertEquals(List.of(), dateOnly(ist(12, 10, 9, 30)));
        assertEquals(List.of(), dateOnly(ist(12, 10, 23, 45)), "no overdue reminder on the due date itself");
    }

    @Test
    @DisplayName("A date-only task: first overdue at 09:00 the day after, then daily at 09:00")
    void overdueDateOnly() {
        assertEquals(List.of(), dateOnly(ist(12, 11, 8, 0)));
        assertEquals(List.of(fire(ReminderKind.OVERDUE, 0)), dateOnly(ist(12, 11, 9, 0)));
        assertEquals(List.of(fire(ReminderKind.OVERDUE, 0)), dateOnly(ist(12, 11, 9, 20)));
        assertEquals(List.of(), dateOnly(ist(12, 11, 9, 30)));
        assertEquals(List.of(fire(ReminderKind.OVERDUE, 1)), dateOnly(ist(12, 12, 9, 0)));
        assertEquals(List.of(fire(ReminderKind.OVERDUE, 2)), dateOnly(ist(12, 13, 9, 0)));
    }

    @Test
    @DisplayName("Moments given in UTC (the server's zone) give the same answers as the same moments in IST")
    void utcInstantsAreConverted() {
        // 06:30 UTC is 12:00 IST: the 2-hour reminder for a 14:00 IST deadline
        ZonedDateTime utc = ZonedDateTime.of(2026, 12, 10, 6, 30, 0, 0, ZoneOffset.UTC);
        assertEquals(List.of(fire(ReminderKind.DUE_2H, 0)), timed(utc));
        // 03:30 UTC is 09:00 IST: the due-today reminder of a date-only task
        assertEquals(List.of(fire(ReminderKind.DUE_TODAY, 0)), dateOnly(ZonedDateTime.of(2026, 12, 10, 3, 30, 0, 0, ZoneOffset.UTC)));
        // 18:30 UTC on the 10th is already 00:00 IST on the 11th: still the due date + 1, but before 09:00, so nothing
        assertEquals(List.of(), dateOnly(ZonedDateTime.of(2026, 12, 10, 18, 30, 0, 0, ZoneOffset.UTC)));
        // 03:30 UTC on the 11th is 09:00 IST on the 11th: the first overdue reminder of the date-only task
        assertEquals(List.of(fire(ReminderKind.OVERDUE, 0)), dateOnly(ZonedDateTime.of(2026, 12, 11, 3, 30, 0, 0, ZoneOffset.UTC)));
        assertEquals(ZoneId.of("Asia/Kolkata"), TaskReminderRules.ZONE);
    }

    @Test
    @DisplayName("The moment a reminder falls on (used to tell who missed it): every kind, timed and date-only")
    void momentsOfReminders() {
        assertEquals(ist(12, 9, 14, 0), TaskReminderRules.momentOf(DUE, TIME, fire(ReminderKind.DUE_24H, 0)));
        assertEquals(ist(12, 10, 12, 0), TaskReminderRules.momentOf(DUE, TIME, fire(ReminderKind.DUE_2H, 0)));
        assertEquals(ist(12, 10, 14, 0), TaskReminderRules.momentOf(DUE, TIME, fire(ReminderKind.OVERDUE, 0)));
        assertEquals(ist(12, 11, 9, 0), TaskReminderRules.momentOf(DUE, TIME, fire(ReminderKind.OVERDUE, 1)));
        assertEquals(ist(12, 13, 9, 0), TaskReminderRules.momentOf(DUE, TIME, fire(ReminderKind.OVERDUE, 3)));
        assertEquals(ist(12, 10, 9, 0), TaskReminderRules.momentOf(DUE, null, fire(ReminderKind.DUE_TODAY, 0)));
        assertEquals(ist(12, 11, 9, 0), TaskReminderRules.momentOf(DUE, null, fire(ReminderKind.OVERDUE, 0)));
        assertEquals(ist(12, 12, 9, 0), TaskReminderRules.momentOf(DUE, null, fire(ReminderKind.OVERDUE, 1)));
        // and every moment is inside its own window
        for (Fire f : timed(ist(12, 11, 9, 5))) assertEquals(ist(12, 11, 9, 0), TaskReminderRules.momentOf(DUE, TIME, f));
    }

    @Test
    @DisplayName("No due date, no reminders; the due key tells one deadline from another")
    void noDueDateAndDueKey() {
        assertTrue(TaskReminderRules.dueAt(null, TIME, ist(12, 10, 14, 0), GRACE).isEmpty());
        assertEquals("2026-12-10", TaskReminderRules.dueKey(DUE, null));
        assertEquals("2026-12-10T14:00", TaskReminderRules.dueKey(DUE, TIME));
        assertEquals("2026-12-10T14:00", TaskReminderRules.dueKey(DUE, LocalTime.of(14, 0, 30)), "seconds are ignored");
        assertEquals("", TaskReminderRules.dueKey(null, null));
    }
}
