package com.hotel.workload.staff;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public class CreateDepartmentRequest {

    @NotBlank(message = "Department name is required")
    @Size(min = 2, max = 100, message = "Department name must be between 2 and 100 characters")
    private String name;

    @Size(max = 500, message = "Description must be at most 500 characters")
    private String description;

    private String iconKey;
    private String colorKey;

    /** Optional department head: an existing active staff member. */
    private UUID headStaffId;

    /** Defaults to active when omitted. */
    private Boolean active;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getIconKey() { return iconKey; }
    public void setIconKey(String iconKey) { this.iconKey = iconKey; }

    public String getColorKey() { return colorKey; }
    public void setColorKey(String colorKey) { this.colorKey = colorKey; }

    public UUID getHeadStaffId() { return headStaffId; }
    public void setHeadStaffId(UUID headStaffId) { this.headStaffId = headStaffId; }

    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }
}
