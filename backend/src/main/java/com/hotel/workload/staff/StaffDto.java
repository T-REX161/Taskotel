package com.hotel.workload.staff;

import java.util.UUID;

public class StaffDto {
    private UUID id;
    private String name;
    private String email;
    private String phone;
    private String department;
    private String departmentName;
    private Availability availability;
    private boolean active;
    
    private UUID userId;
    private String username;
    private String role;
    private String profileImageUrl;
    /** A login email change waiting for the Staff member to confirm it: the address (masked) and when the code expires. Null when none. */
    private String pendingEmailMasked;
    private java.time.LocalDateTime pendingEmailExpiresAt;

    public StaffDto() {}

    public static StaffDto fromEntity(Staff staff) {
        StaffDto dto = new StaffDto();
        dto.setId(staff.getId());
        dto.setName(staff.getName());
        dto.setEmail(staff.getEmail());
        dto.setPhone(staff.getPhone());
        if (staff.getDepartment() != null) {
            dto.setDepartment(staff.getDepartment().getCode());
            dto.setDepartmentName(staff.getDepartment().getName());
        }
        dto.setAvailability(staff.getAvailability());
        dto.setActive(staff.isActive());
        if (staff.getUser() != null) {
            dto.setUserId(staff.getUser().getId());
            dto.setUsername(staff.getUser().getUsername());
            if (staff.getUser().getRole() != null) {
                dto.setRole(staff.getUser().getRole().name());
            }
            dto.setProfileImageUrl(staff.getUser().getProfileImageUrl());
            com.hotel.workload.security.User account = staff.getUser();
            if (account.getPendingEmail() != null && account.getPendingEmailExpiresAt() != null
                    && account.getPendingEmailExpiresAt().isAfter(java.time.LocalDateTime.now())) {
                dto.setPendingEmailMasked(com.hotel.workload.profile.EmailChangeService.mask(account.getPendingEmail()));
                dto.setPendingEmailExpiresAt(account.getPendingEmailExpiresAt());
            }
        }
        return dto;
    }

    public String getPendingEmailMasked() { return pendingEmailMasked; }
    public void setPendingEmailMasked(String pendingEmailMasked) { this.pendingEmailMasked = pendingEmailMasked; }
    public java.time.LocalDateTime getPendingEmailExpiresAt() { return pendingEmailExpiresAt; }
    public void setPendingEmailExpiresAt(java.time.LocalDateTime pendingEmailExpiresAt) { this.pendingEmailExpiresAt = pendingEmailExpiresAt; }
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getDepartment() { return department; }
    public void setDepartment(String department) { this.department = department; }

    public String getDepartmentName() { return departmentName; }
    public void setDepartmentName(String departmentName) { this.departmentName = departmentName; }
    public Availability getAvailability() { return availability; }
    public void setAvailability(Availability availability) { this.availability = availability; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getProfileImageUrl() { return profileImageUrl; }
    public void setProfileImageUrl(String profileImageUrl) { this.profileImageUrl = profileImageUrl; }
}
