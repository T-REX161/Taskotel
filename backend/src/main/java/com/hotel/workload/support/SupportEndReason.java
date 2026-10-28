package com.hotel.workload.support;

/** Why a support assignment is no longer active. */
public enum SupportEndReason {
    TASK_COMPLETED,
    TASK_CANCELLED,
    TASK_DELETED,
    /** The task was reassigned to someone else through the normal assignment flow. */
    TASK_REASSIGNED,
    /** The task was moved out of the receiving department. */
    TASK_MOVED,
    /** A manager ended the support by hand; the task went back to its department's waiting list. */
    MANUALLY_ENDED
}
