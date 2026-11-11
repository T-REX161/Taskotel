package com.hotel.workload.assignment;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * Creates the first assignment of a task, for ONE staff member ({@code staffId}, the original format) or for SEVERAL ({@code staffIds}).
 * Exactly one of the two must be given; {@link AssignmentServiceImpl} checks that (an empty list, a null or duplicate id, both fields and
 * neither are 400), so the rule also holds for a direct service call.
 */
public class CreateAssignmentRequest {

    @NotNull(message = "Task ID is required")
    private UUID taskId;

    private UUID staffId;

    private List<UUID> staffIds;

    public CreateAssignmentRequest() {}

    public CreateAssignmentRequest(UUID taskId, UUID staffId) {
        this.taskId = taskId;
        this.staffId = staffId;
    }

    /** The multi-staff form. */
    public static CreateAssignmentRequest forStaff(UUID taskId, List<UUID> staffIds) {
        CreateAssignmentRequest request = new CreateAssignmentRequest();
        request.taskId = taskId;
        request.staffIds = staffIds;
        return request;
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

    public List<UUID> getStaffIds() {
        return staffIds;
    }

    public void setStaffIds(List<UUID> staffIds) {
        this.staffIds = staffIds;
    }
}
