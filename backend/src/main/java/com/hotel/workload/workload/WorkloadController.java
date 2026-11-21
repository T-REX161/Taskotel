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
    private final com.hotel.workload.settings.SystemSettingsService systemSettings;

    public WorkloadController(WorkloadService workloadService, com.hotel.workload.staff.DepartmentService departmentService,
                              com.hotel.workload.settings.SystemSettingsService systemSettings) {
        this.workloadService = workloadService;
        this.departmentService = departmentService;
        this.systemSettings = systemSettings;
    }

    /**
     * GET /api/workload/department-risk-threshold
     * The saved Capacity Threshold, read-only, for the accounts that see the "Workload by Department" cards: an Admin and a valid Manager.
     * A department whose displayed share of the total load is at or above this percentage is AT RISK. Staff are refused (they have their own
     * page without department cards and need no department-level information): at the URL level in SecurityConfig and again in the service,
     * from the signed-in account in the database. Same source as the Admin's System Settings page (the one GLOBAL row, or the defaults).
     * Changing it stays Admin only (PUT /api/system-settings).
     */
    @GetMapping("/department-risk-threshold")
    public ResponseEntity<com.hotel.workload.settings.DepartmentRiskThresholdDto> getDepartmentRiskThreshold() {
        return ResponseEntity.ok(systemSettings.departmentRiskThreshold());
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
