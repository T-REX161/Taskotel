package com.hotel.workload.notification;

/** What a reminder's log row says (stored by name in {@code task_reminder_log.status}). */
public enum ReminderStatus {
    /** Reserved by a scheduler run and handed to the notification worker; the outcome is not known yet. Counts toward the overdue cap. */
    CLAIMED,
    /** The email provider accepted the request. Not proof that the inbox received it. Counts toward the overdue cap. */
    SENT,
    /** The email provider CONFIRMED a refusal (or email was not configured): it is known that nothing was sent. Not retried; does not count toward the cap. */
    FAILED,
    /** Deliberately not sent (task finished or changed, recipient no longer eligible, preference off). Not retried; does not count toward the cap. */
    SKIPPED,
    /** Not sent because the reminder's window had closed by the time the worker reached it (a backed-up queue). Not retried; does not count toward the cap. */
    EXPIRED,
    /**
     * Whether an email went out is NOT known: the connection broke or timed out after the request may have been received, or the worker never
     * reported back (a crash or a stop). It is treated as possibly sent: it counts toward the overdue cap and is never retried (at most once).
     */
    UNKNOWN
}
