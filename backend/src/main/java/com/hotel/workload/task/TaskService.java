package com.hotel.workload.task;

import java.util.List;
import java.util.UUID;

public interface TaskService {
    List<TaskDto> getAllTasks();
    TaskDto createTask(CreateTaskRequest request);
    TaskDto updateTask(UUID id, UpdateTaskRequest request);
    void deleteTask(UUID id);
}
