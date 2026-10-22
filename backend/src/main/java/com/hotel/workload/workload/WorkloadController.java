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
     * Returns the current workload for every staff member, calculated
     * on-the-fly from PostgreSQL. Accessible to ADMIN, MANAGER, and STAFF.
     */
    @GetMapping
    public ResponseEntity<List<WorkloadDto>> getWorkload(org.springframework.security.core.Authentication authentication) {
        boolean isAdminOrManager = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN") || a.getAuthority().equals("ROLE_MANAGER"));
        
        return ResponseEntity.ok(workloadService.calculateWorkload(authentication.getName(), isAdminOrManager));
    }
}
