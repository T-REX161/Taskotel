package com.hotel.workload.support;

import com.hotel.workload.task.Task;

import java.util.UUID;

/**
 * What the task and assignment flows tell the support feature, so a support record ends when its task does.
 * None of these methods ever touch a staff member's permanent department.
 */
public interface SupportLifecycle {
    /** A task was saved: ends its active support if it is now completed, cancelled or moved to another department. */
    void onTaskChanged(Task task);

    /** A task is about to be deleted. */
    void onTaskDeleted(UUID taskId);

    /** A task was reassigned: ends its active support unless the same person still holds it. */
    void onTaskReassigned(UUID taskId, UUID newStaffId);
}
