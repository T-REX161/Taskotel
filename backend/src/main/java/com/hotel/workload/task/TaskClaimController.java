package com.hotel.workload.task;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Staff self-service claiming (STAFF only, enforced by the URL rules and again by {@link TaskClaimService}). */
@RestController
@RequestMapping("/api/tasks")
public class TaskClaimController {

    private final TaskClaimService claimService;

    public TaskClaimController(TaskClaimService claimService) {
        this.claimService = claimService;
    }

    /** The unassigned PENDING tasks of the caller's own department that the caller could accept. */
    @GetMapping("/available")
    public AvailableTasksDto available() {
        return claimService.availableTasks();
    }

    /** Accepts one available task exclusively: 200 with the task (now ASSIGNED) for the first caller, 409 for everyone who lost the race. */
    @PostMapping("/{id}/claim")
    public TaskDto claim(@PathVariable UUID id) {
        return claimService.claim(id);
    }
}
