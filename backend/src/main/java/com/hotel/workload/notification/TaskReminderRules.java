package com.hotel.workload.notification;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * WHEN a task's reminders fall, as pure functions of the task's due date, deadline time and the current moment. No database, no clock of its
 * own, no server time zone: every calculation is made in {@link #ZONE} (Asia/Kolkata), whatever zone the server runs in. A task's date and
 * time are wall-clock values in that zone (the zone the application shows them in).
 *
 * <p>Schedule:
 * <ul>
 *   <li>A task WITH a deadline time: DUE_24H at 24 hours and DUE_2H at 2 hours before it (only while it has not passed). OVERDUE: first at the
 *       due moment itself (slot 0), then every day at 09:00 starting the day after the due date (slots 1, 2, ...).</li>
 *   <li>A task with a due DATE only: DUE_TODAY at 09:00 on the due date. OVERDUE: first at 09:00 the following day (slot 0), then every day at
 *       09:00 (slots 1, 2, ...).</li>
 * </ul>
 *
 * <p><b>No old reminders.</b> A reminder is due only while the moment it falls on is no more than {@code grace} in the past. A reminder whose
 * moment has gone by (a task created late, assigned late, a scheduler that was off) is never sent afterwards. For overdue reminders only the
 * CURRENT daily slot can be due, so someone assigned to a long-overdue task receives the next daily reminder, not the earlier ones. The
 * per-recipient cap of three overdue emails is applied by the caller, from the log.
 */
public final class TaskReminderRules {

    /** The application's time zone, for every reminder calculation. */
    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    /** The time of day of the daily reminders. */
    public static final LocalTime DAILY_AT = LocalTime.of(9, 0);
    /** At most this many overdue emails per task, recipient and due moment, counting the first. */
    public static final int MAX_OVERDUE_EMAILS = 3;

    private TaskReminderRules() {}

    /** One reminder that is due now: its kind and, for OVERDUE, which daily slot (0 is the first). */
    public record Fire(ReminderKind kind, int slot) {}

    /** The text that identifies a due moment in the log: a different date or time is a different task deadline. */
    public static String dueKey(LocalDate dueDate, LocalTime deadlineTime) {
        if (dueDate == null) return "";
        return deadlineTime == null ? dueDate.toString() : dueDate + "T" + deadlineTime.truncatedTo(ChronoUnit.MINUTES);
    }

    /** The reminders that are due at {@code now} (normally none, sometimes one). Empty for a task without a due date. */
    public static List<Fire> dueAt(LocalDate dueDate, LocalTime deadlineTime, ZonedDateTime now, Duration grace) {
        List<Fire> fires = new ArrayList<>();
        if (dueDate == null || now == null) return fires;
        ZonedDateTime at = now.withZoneSameInstant(ZONE);
        if (deadlineTime != null) {
            ZonedDateTime due = dueDate.atTime(deadlineTime.truncatedTo(ChronoUnit.MINUTES)).atZone(ZONE);
            if (at.isBefore(due)) {
                if (inWindow(at, due.minusHours(24), grace)) fires.add(new Fire(ReminderKind.DUE_24H, 0));
                if (inWindow(at, due.minusHours(2), grace)) fires.add(new Fire(ReminderKind.DUE_2H, 0));
                return fires;
            }
            // Overdue. Slot 0 is the due moment itself; slot k >= 1 is 09:00 on the k-th day after the due date.
            ZonedDateTime latestDaily = latestDailyOnOrBefore(at);
            if (latestDaily.toLocalDate().isBefore(dueDate.plusDays(1))) {
                if (inWindow(at, due, grace)) fires.add(new Fire(ReminderKind.OVERDUE, 0));
            } else {
                int slot = (int) ChronoUnit.DAYS.between(dueDate, latestDaily.toLocalDate());
                if (inWindow(at, latestDaily, grace)) fires.add(new Fire(ReminderKind.OVERDUE, slot));
            }
            return fires;
        }
        // Date only
        if (at.toLocalDate().isAfter(dueDate)) {
            // Overdue: slot 0 is 09:00 the day after the due date, then every day at 09:00
            ZonedDateTime latestDaily = latestDailyOnOrBefore(at);
            LocalDate firstDay = dueDate.plusDays(1);
            if (!latestDaily.toLocalDate().isBefore(firstDay)) {
                int slot = (int) ChronoUnit.DAYS.between(firstDay, latestDaily.toLocalDate());
                if (inWindow(at, latestDaily, grace)) fires.add(new Fire(ReminderKind.OVERDUE, slot));
            }
            return fires;
        }
        if (at.toLocalDate().equals(dueDate) && inWindow(at, dueDate.atTime(DAILY_AT).atZone(ZONE), grace)) {
            fires.add(new Fire(ReminderKind.DUE_TODAY, 0));
        }
        return fires;
    }

    /**
     * The moment a reminder falls on. Someone assigned AFTER this moment has missed it and is not sent it (even inside the grace window): they
     * receive the next one instead.
     */
    public static ZonedDateTime momentOf(LocalDate dueDate, LocalTime deadlineTime, Fire fire) {
        if (deadlineTime != null) {
            ZonedDateTime due = dueDate.atTime(deadlineTime.truncatedTo(ChronoUnit.MINUTES)).atZone(ZONE);
            return switch (fire.kind()) {
                case DUE_24H -> due.minusHours(24);
                case DUE_2H -> due.minusHours(2);
                case DUE_TODAY -> dueDate.atTime(DAILY_AT).atZone(ZONE);
                case OVERDUE -> fire.slot() == 0 ? due : dueDate.plusDays(fire.slot()).atTime(DAILY_AT).atZone(ZONE);
            };
        }
        return switch (fire.kind()) {
            case DUE_TODAY, DUE_24H, DUE_2H -> dueDate.atTime(DAILY_AT).atZone(ZONE);
            case OVERDUE -> dueDate.plusDays(1L + fire.slot()).atTime(DAILY_AT).atZone(ZONE);
        };
    }

    /** The most recent 09:00 (in the application's zone) at or before {@code at}. */
    private static ZonedDateTime latestDailyOnOrBefore(ZonedDateTime at) {
        ZonedDateTime today = at.toLocalDate().atTime(DAILY_AT).atZone(ZONE);
        return at.isBefore(today) ? today.minusDays(1) : today;
    }

    /** {@code moment <= at < moment + grace}. */
    private static boolean inWindow(ZonedDateTime at, ZonedDateTime moment, Duration grace) {
        return !at.isBefore(moment) && at.isBefore(moment.plus(grace));
    }
}
