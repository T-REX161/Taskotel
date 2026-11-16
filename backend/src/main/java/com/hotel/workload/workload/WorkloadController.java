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
    private final com.hotel.workload.staff.DepartmentService departmentService;

    public WorkloadController(WorkloadService workloadService, com.hotel.workload.staff.DepartmentService departmentService) {
        this.workloadService = workloadService;
        this.departmentService = departmentService;
    }

    /**
     * GET /api/workload/departments
     * The departments the Workload page shows as cards, decided by the service from the signed-in account: every department for an ADMIN and
     * a STAFF member (as GET /api/departments), and for a MANAGER only the departments they manage. Accessible to ADMIN, MANAGER and STAFF.
     */
    @GetMapping("/departments")
    public ResponseEntity<List<com.hotel.workload.staff.DepartmentDto>> getWorkloadDepartments() {
        return ResponseEntity.ok(departmentService.getDepartmentsForWorkload());
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
