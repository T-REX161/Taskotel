package com.hotel.workload.staff;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

public class DepartmentDto {
    /** Kept for API compatibility and always false: Administrator grants give full management, so nothing is view-only any more. */
    private boolean viewOnly;
    /**
     * For a signed-in Manager only: always true, because a Manager is sent only the departments they manage (their primary department or
     * one an Administrator granted).
     * Null, and so left out of the response, for Admin and Staff.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean managed;
    private UUID id;
    private String code;
    private String name;
    private String description;
    private String iconKey;
    private String colorKey;
    private boolean active;
    private UUID headStaffId;
    private String headStaffName;
    // Null when the viewer may not see this department in full (see DepartmentServiceImpl.getAllDepartments)
    private Long staffCount;
    private Long activeTasks;
    private String status;
    /** Same as headStaffName, or "Not Assigned"; kept so existing consumers of this field keep working. */
    private String supervisor;

    public DepartmentDto() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getIconKey() { return iconKey; }
    public void setIconKey(String iconKey) { this.iconKey = iconKey; }

    public String getColorKey() { return colorKey; }
    public void setColorKey(String colorKey) { this.colorKey = colorKey; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public UUID getHeadStaffId() { return headStaffId; }
    public void setHeadStaffId(UUID headStaffId) { this.headStaffId = headStaffId; }

    public String getHeadStaffName() { return headStaffName; }
    public void setHeadStaffName(String headStaffName) { this.headStaffName = headStaffName; }

    public Long getStaffCount() { return staffCount; }
    public void setStaffCount(Long staffCount) { this.staffCount = staffCount; }

    public Long getActiveTasks() { return activeTasks; }
    public void setActiveTasks(Long activeTasks) { this.activeTasks = activeTasks; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getSupervisor() { return supervisor; }
    public void setSupervisor(String supervisor) { this.supervisor = supervisor; }

    public boolean isViewOnly() { return viewOnly; }
    public void setViewOnly(boolean viewOnly) { this.viewOnly = viewOnly; }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Boolean getManaged() { return managed; }
    public void setManaged(Boolean managed) { this.managed = managed; }
}
