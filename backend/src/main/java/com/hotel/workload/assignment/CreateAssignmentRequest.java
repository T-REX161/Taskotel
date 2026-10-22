package com.hotel.workload.assignment;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public class CreateAssignmentRequest {

    @NotNull(message = "Task ID is required")
    private UUID taskId;

    @NotNull(message = "Staff ID is required")
    private UUID staffId;

    public CreateAssignmentRequest() {}

    public CreateAssignmentRequest(UUID taskId, UUID staffId) {
        this.taskId = taskId;
        this.staffId = staffId;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public void setTaskId(UUID taskId) {
        this.taskId = taskId;
    }

    public UUID getStaffId() {
        return staffId;
    }

    public void setStaffId(UUID staffId) {
        this.staffId = staffId;
    }
}
