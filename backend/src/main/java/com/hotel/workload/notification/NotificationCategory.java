package com.hotel.workload.notification;

/**
 * The kinds of notification a user can choose how to receive, with the defaults every user starts with.
 * {@code mandatory} categories can never be turned off (security notifications must always reach the account owner).
 */
public enum NotificationCategory {
    TASK_ASSIGNED(true, true, false),
    TASK_STATUS(true, false, false),
    WORKLOAD_ALERT(true, true, false),
    TASK_REMINDER(true, false, false),
    SECURITY(true, true, true);

    private final boolean defaultInApp;
    private final boolean defaultEmail;
    private final boolean mandatory;

    NotificationCategory(boolean defaultInApp, boolean defaultEmail, boolean mandatory) {
        this.defaultInApp = defaultInApp;
        this.defaultEmail = defaultEmail;
        this.mandatory = mandatory;
    }

    public boolean defaultInApp() { return defaultInApp; }
    public boolean defaultEmail() { return defaultEmail; }
    public boolean isMandatory() { return mandatory; }
}
