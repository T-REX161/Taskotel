package com.hotel.workload.support;

/** Lifecycle of a temporary cross-department support assignment. */
public enum SupportStatus {
    /** The supported task is still open and the staff member is helping with it. */
    ACTIVE,
    /** The supported task was completed. */
    COMPLETED,
    /** Support ended without the task being completed (see {@link SupportEndReason}). */
    CANCELLED
}
