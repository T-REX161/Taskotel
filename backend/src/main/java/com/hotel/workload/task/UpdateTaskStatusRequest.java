package com.hotel.workload.task;

import jakarta.validation.constraints.NotNull;

/** The only thing a Staff member may send about a task: its new status. Nothing else in the body is read. */
public class UpdateTaskStatusRequest {

    @NotNull(message = "Status is required")
    private TaskStatus status;

    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) { this.status = status; }
}
