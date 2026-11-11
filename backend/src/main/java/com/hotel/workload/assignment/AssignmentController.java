package com.hotel.workload.assignment;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/assignments")
public class AssignmentController {

    private final AssignmentService assignmentService;

    public AssignmentController(AssignmentService assignmentService) {
        this.assignmentService = assignmentService;
    }

    /**
     * Assigns a task. {@code staffId} (one staff member) answers with the one assignment, as before. {@code staffIds} (several, all
     * created together or none) answers with the list of the created assignments. Exactly one of the two must be sent.
     */
    @PostMapping
    public ResponseEntity<?> createAssignment(@Valid @RequestBody CreateAssignmentRequest request) {
        if (request.getStaffIds() != null) {
            return ResponseEntity.status(HttpStatus.CREATED).body(assignmentService.createAssignments(request));
        }
        AssignmentDto created = assignmentService.createAssignment(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    public ResponseEntity<List<AssignmentDto>> getAllAssignments() {
        return ResponseEntity.ok(assignmentService.getAllAssignments());
    }

    @PutMapping("/{assignmentId}")
    public ResponseEntity<AssignmentDto> reassignTask(
            @PathVariable UUID assignmentId,
            @Valid @RequestBody UpdateAssignmentRequest request) {
        AssignmentDto updated = assignmentService.reassignTask(assignmentId, request);
        return ResponseEntity.ok(updated);
    }
}
