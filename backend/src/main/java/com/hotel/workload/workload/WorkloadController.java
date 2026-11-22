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
    private final DepartmentCapacityService departmentCapacityService;

    public WorkloadController(WorkloadService workloadService, com.hotel.workload.staff.DepartmentService departmentService,
                              DepartmentCapacityService departmentCapacityService) {
        this.workloadService = workloadService;
        this.departmentService = departmentService;
        this.departmentCapacityService = departmentCapacityService;
    }

    /**
     * GET /api/workload/department-capacity
     * Each department's remaining workload, staff capacity, utilization and risk level (see DepartmentCapacity), plus the saved Capacity
     * Threshold they are judged by. For the accounts that see the "Workload by Department" cards: an Admin sees every department and a valid
     * Manager the departments they manage. Staff are refused (they have their own page without department cards): at the URL level in
     * SecurityConfig and again in the service, from the signed-in account in the database. Read-only; changing the threshold stays Admin
     * only (PUT /api/system-settings).
     */
    @GetMapping("/department-capacity")
    public ResponseEntity<DepartmentCapacityDto> getDepartmentCapacity() {
        return ResponseEntity.ok(departmentCapacityService.forCaller());
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
