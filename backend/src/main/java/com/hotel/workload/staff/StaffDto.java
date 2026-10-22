package com.hotel.workload.staff;

import java.util.UUID;

public class StaffDto {
    private UUID id;
    private String name;
    private String email;
    private String phone;
    private Department department;
    private Availability availability;
    private boolean active;
    
    private UUID userId;
    private String username;
    private String role;

    public StaffDto() {}

    public static StaffDto fromEntity(Staff staff) {
        StaffDto dto = new StaffDto();
        dto.setId(staff.getId());
        dto.setName(staff.getName());
        dto.setEmail(staff.getEmail());
        dto.setPhone(staff.getPhone());
        dto.setDepartment(staff.getDepartment());
        dto.setAvailability(staff.getAvailability());
        dto.setActive(staff.isActive());
        if (staff.getUser() != null) {
            dto.setUserId(staff.getUser().getId());
            dto.setUsername(staff.getUser().getUsername());
            if (staff.getUser().getRole() != null) {
                dto.setRole(staff.getUser().getRole().name());
            }
        }
        return dto;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public Department getDepartment() { return department; }
    public void setDepartment(Department department) { this.department = department; }
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
}
