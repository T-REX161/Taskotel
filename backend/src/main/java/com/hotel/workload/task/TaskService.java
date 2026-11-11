package com.hotel.workload.task;

import java.util.List;
import java.util.UUID;

public interface TaskService {
    List<TaskDto> getAllTasks();
    TaskDto createTask(CreateTaskRequest request);
    TaskDto updateTask(UUID id, UpdateTaskRequest request);
    void deleteTask(UUID id);

    /** A Staff member moves a task assigned to them one step forward (Assigned to In Progress, In Progress to Completed). Nothing else changes. */
    TaskDto updateOwnTaskStatus(UUID id, TaskStatus status);
}
