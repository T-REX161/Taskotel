package com.hotel.workload.support;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** A proposed set of "this staff member helps with this task" pairs for one receiving department. */
public class SupportPlanRequest {

    @NotBlank(message = "Receiving department is required")
    private String receivingDepartment;

    @NotEmpty(message = "Choose at least one staff member and task")
    @Size(max = 20, message = "A plan can contain at most 20 pairs")
    @Valid
    private List<Item> items;

    /** From the preview. When present, confirmation is refused if what the preview saw has changed since. */
    private String fingerprint;

    /**
     * Set only after a manager has been shown the staffing-risk warning and chose to continue. It lets a donor department
     * lend its last free person (it normally keeps one in reserve). It never relaxes any other rule, and it does not
     * confirm anything by itself: the plan still goes through preview and the explicit confirmation.
     */
    private boolean acknowledgeReserveOverride;

    public static class Item {
        @NotNull(message = "Staff member is required")
        private UUID staffId;
        @NotNull(message = "Task is required")
        private UUID taskId;

        public Item() {}
        public Item(UUID staffId, UUID taskId) { this.staffId = staffId; this.taskId = taskId; }
        public UUID getStaffId() { return staffId; }
        public void setStaffId(UUID staffId) { this.staffId = staffId; }
        public UUID getTaskId() { return taskId; }
        public void setTaskId(UUID taskId) { this.taskId = taskId; }
    }

    public String getReceivingDepartment() { return receivingDepartment; }
    public void setReceivingDepartment(String receivingDepartment) { this.receivingDepartment = receivingDepartment; }
    public List<Item> getItems() { return items; }
    public void setItems(List<Item> items) { this.items = items; }
    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }
    public boolean isAcknowledgeReserveOverride() { return acknowledgeReserveOverride; }
    public void setAcknowledgeReserveOverride(boolean acknowledgeReserveOverride) { this.acknowledgeReserveOverride = acknowledgeReserveOverride; }
}
