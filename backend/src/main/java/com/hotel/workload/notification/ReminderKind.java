package com.hotel.workload.notification;

/** The kinds of task reminder (stored by name in {@code task_reminder_log.kind}). */
public enum ReminderKind {
    /** 24 hours before a task's deadline time (tasks with a deadline time only). */
    DUE_24H,
    /** 2 hours before a task's deadline time (tasks with a deadline time only). */
    DUE_2H,
    /** 09:00 on the due date (date-only tasks). */
    DUE_TODAY,
    /** The task is past its due moment: first at the due moment, then daily at 09:00. */
    OVERDUE
}
