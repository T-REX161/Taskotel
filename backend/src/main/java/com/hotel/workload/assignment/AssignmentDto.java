package com.hotel.workload.assignment;

import com.hotel.workload.task.TaskStatus;
import java.time.LocalDateTime;
import java.util.UUID;

public class AssignmentDto {

    private UUID id;
    private UUID taskId;
    private String taskTitle;
    private TaskStatus taskStatus;
    private UUID staffId;
    private String staffName;
    /** The assigned staff member's existing profile picture (the same URL the Staff and Users lists show), or null when there is none. */
    private String staffProfileImageUrl;
    private LocalDateTime assignedAt;

    public AssignmentDto() {}

    public static AssignmentDto fromEntity(Assignment assignment) {
        AssignmentDto dto = new AssignmentDto();
        dto.setId(assignment.getId());
        if (assignment.getTask() != null) {
            dto.setTaskId(assignment.getTask().getId());
            dto.setTaskTitle(assignment.getTask().getTitle());
            dto.setTaskStatus(assignment.getTask().getStatus());
        }
        if (assignment.getStaff() != null) {
            dto.setStaffId(assignment.getStaff().getId());
            dto.setStaffName(assignment.getStaff().getName());
            if (assignment.getStaff().getUser() != null) {
                dto.setStaffProfileImageUrl(assignment.getStaff().getUser().getProfileImageUrl());
            }
        }
        dto.setAssignedAt(assignment.getAssignedAt());
        return dto;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public void setTaskId(UUID taskId) {
        this.taskId = taskId;
    }

    public String getTaskTitle() {
        return taskTitle;
    }

    public void setTaskTitle(String taskTitle) {
        this.taskTitle = taskTitle;
    }

    public TaskStatus getTaskStatus() {
        return taskStatus;
    }

    public void setTaskStatus(TaskStatus taskStatus) {
        this.taskStatus = taskStatus;
    }

    public UUID getStaffId() {
        return staffId;
    }

    public void setStaffId(UUID staffId) {
        this.staffId = staffId;
    }

    public String getStaffName() {
        return staffName;
    }

    public void setStaffName(String staffName) {
        this.staffName = staffName;
    }

    public String getStaffProfileImageUrl() {
        return staffProfileImageUrl;
    }

    public void setStaffProfileImageUrl(String staffProfileImageUrl) {
        this.staffProfileImageUrl = staffProfileImageUrl;
    }

    public LocalDateTime getAssignedAt() {
        return assignedAt;
    }

    public void setAssignedAt(LocalDateTime assignedAt) {
        this.assignedAt = assignedAt;
    }
}
