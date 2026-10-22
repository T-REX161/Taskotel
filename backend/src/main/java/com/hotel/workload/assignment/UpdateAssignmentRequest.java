package com.hotel.workload.assignment;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public class UpdateAssignmentRequest {

    @NotNull(message = "Staff ID is required")
    private UUID staffId;

    public UpdateAssignmentRequest() {}

    public UpdateAssignmentRequest(UUID staffId) {
        this.staffId = staffId;
    }

    public UUID getStaffId() {
        return staffId;
    }

    public void setStaffId(UUID staffId) {
        this.staffId = staffId;
    }
}
