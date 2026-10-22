package com.hotel.workload.task;

public enum TaskStatus {
    PENDING("Pending"),
    ASSIGNED("Assigned"),
    IN_PROGRESS("In Progress"),
    COMPLETED("Completed"),
    CANCELLED("Cancelled");

    private final String displayName;

    TaskStatus(String displayName) {
        this.displayName = displayName;
    }

    /** Human-readable label for user-facing messages. Persistence and JSON still use name(). */
    public String getDisplayName() {
        return displayName;
    }

    /** COMPLETED and CANCELLED tasks are finished and cannot be moved to another status. */
    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED;
    }
}
