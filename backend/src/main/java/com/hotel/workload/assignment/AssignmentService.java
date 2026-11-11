package com.hotel.workload.assignment;

import java.util.List;
import java.util.UUID;

public interface AssignmentService {
    /** The single-staff form ({@code staffId}). */
    AssignmentDto createAssignment(CreateAssignmentRequest request);

    /** The multi-staff form ({@code staffIds}): one assignment per staff member, all created together or none. */
    List<AssignmentDto> createAssignments(CreateAssignmentRequest request);
    List<AssignmentDto> getAllAssignments();
    AssignmentDto reassignTask(UUID assignmentId, UpdateAssignmentRequest request);
}
