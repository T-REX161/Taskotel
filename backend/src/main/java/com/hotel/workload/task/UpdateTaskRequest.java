package com.hotel.workload.task;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalTime;

public class UpdateTaskRequest {

    @NotBlank(message = "Title is required")
    private String title;

    private String description;

    private String category;

    @NotNull(message = "Priority is required")
    private Priority priority;

    @Min(value = 1, message = "Estimated duration must be positive")
    private Integer estimatedDuration;

    private LocalDate dueDate;

    private LocalTime deadlineTime;

    private String department;

    @NotNull(message = "Status is required")
    private TaskStatus status;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public Priority getPriority() { return priority; }
    public void setPriority(Priority priority) { this.priority = priority; }

    public Integer getEstimatedDuration() { return estimatedDuration; }
    public void setEstimatedDuration(Integer estimatedDuration) { this.estimatedDuration = estimatedDuration; }

    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }

    public LocalTime getDeadlineTime() { return deadlineTime; }
    public void setDeadlineTime(LocalTime deadlineTime) { this.deadlineTime = deadlineTime; }

    public String getDepartment() { return department; }
    public void setDepartment(String department) { this.department = department; }

    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) { this.status = status; }
}
