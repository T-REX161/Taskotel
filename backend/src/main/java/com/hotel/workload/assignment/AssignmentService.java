package com.hotel.workload.assignment;

import java.util.List;
import java.util.UUID;

public interface AssignmentService {
    AssignmentDto createAssignment(CreateAssignmentRequest request);
    List<AssignmentDto> getAllAssignments();
    AssignmentDto reassignTask(UUID assignmentId, UpdateAssignmentRequest request);
}
