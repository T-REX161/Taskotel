package com.hotel.workload.staff;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Admin-only. The URL rule for /api/users/** (SecurityConfig) refuses everyone but an Admin before this class is reached, and
 * {@link ManagerDepartmentServiceImpl} refuses everyone but an Admin again, so neither layer relies on the other.
 */
@RestController
@RequestMapping("/api/users/{userId}/manager-department")
public class ManagerDepartmentController {

    private final ManagerDepartmentService service;

    public ManagerDepartmentController(ManagerDepartmentService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<ManagerDepartmentDtos.ManagerDepartment> get(@PathVariable UUID userId) {
        return ResponseEntity.ok(service.get(userId));
    }

    @PutMapping
    public ResponseEntity<ManagerDepartmentDtos.ManagerDepartment> assign(@PathVariable UUID userId,
                                                                          @RequestBody ManagerDepartmentDtos.AssignRequest request) {
        return ResponseEntity.ok(service.assign(userId, request == null ? null : request.department()));
    }

    @GetMapping("/history")
    public ResponseEntity<List<ManagerDepartmentDtos.Change>> history(@PathVariable UUID userId) {
        return ResponseEntity.ok(service.history(userId));
    }
}
