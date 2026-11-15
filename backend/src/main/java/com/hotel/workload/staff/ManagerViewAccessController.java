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
 * Admin-only. The URL rules (SecurityConfig) refuse everyone but an Admin before this class is reached, and
 * {@link ManagerViewAccessService} refuses everyone but an Admin again, so neither layer relies on the other.
 */
@RestController
@RequestMapping("/api/users/{userId}/view-departments")
public class ManagerViewAccessController {

    private final ManagerViewAccessService service;

    public ManagerViewAccessController(ManagerViewAccessService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<ManagerViewAccessDtos.ViewAccess> get(@PathVariable UUID userId) {
        return ResponseEntity.ok(service.get(userId));
    }

    @PutMapping
    public ResponseEntity<ManagerViewAccessDtos.ViewAccess> replace(@PathVariable UUID userId,
                                                                     @RequestBody ManagerViewAccessDtos.UpdateRequest request) {
        return ResponseEntity.ok(service.replace(userId, request == null ? null : request.departments()));
    }

    @GetMapping("/history")
    public ResponseEntity<List<ManagerViewAccessDtos.Change>> history(@PathVariable UUID userId) {
        return ResponseEntity.ok(service.history(userId));
    }
}
