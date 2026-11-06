package com.hotel.workload.workload;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/workload")
public class WorkloadController {

    private final WorkloadService workloadService;

    public WorkloadController(WorkloadService workloadService) {
        this.workloadService = workloadService;
    }

    /**
     * GET /api/workload
     * Returns the current workload the caller may see, calculated on-the-fly from PostgreSQL: every department for an ADMIN, the own
     * department for a valid MANAGER, the own row for STAFF. Accessible to ADMIN, MANAGER, and STAFF.
     */
    @GetMapping
    public ResponseEntity<List<WorkloadDto>> getWorkload() {
        // The service decides what this caller may see (CurrentUserScopeResolver); nothing here grants access
        return ResponseEntity.ok(workloadService.calculateWorkload());
    }
}
