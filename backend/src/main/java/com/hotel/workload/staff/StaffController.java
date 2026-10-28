package com.hotel.workload.staff;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
@RequestMapping("/api/staff")
public class StaffController {

    private final StaffService staffService;

    public StaffController(StaffService staffService) {
        this.staffService = staffService;
    }

    @GetMapping
    public ResponseEntity<List<StaffDto>> getAllStaff() {
        return ResponseEntity.ok(staffService.getAllStaff());
    }

    // Answers only whether the username is free; no account details are returned
    @GetMapping("/username-availability")
    public ResponseEntity<java.util.Map<String, Boolean>> checkUsernameAvailability(@org.springframework.web.bind.annotation.RequestParam String username) {
        return ResponseEntity.ok()
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .body(java.util.Map.of("available", staffService.isUsernameAvailable(username)));
    }

    @org.springframework.web.bind.annotation.PostMapping
    public ResponseEntity<StaffDto> createStaff(@jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody CreateStaffRequest request) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED).body(staffService.createStaff(request));
    }

    @org.springframework.web.bind.annotation.PutMapping("/{id}")
    public ResponseEntity<StaffDto> updateStaff(@org.springframework.web.bind.annotation.PathVariable java.util.UUID id, @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody UpdateStaffRequest request) {
        return ResponseEntity.ok(staffService.updateStaff(id, request));
    }

    @org.springframework.web.bind.annotation.PatchMapping("/{id}/deactivate")
    public ResponseEntity<StaffDto> deactivateStaff(@org.springframework.web.bind.annotation.PathVariable java.util.UUID id) {
        return ResponseEntity.ok(staffService.deactivateStaff(id));
    }

    @org.springframework.web.bind.annotation.PatchMapping("/{id}/activate")
    public ResponseEntity<StaffDto> activateStaff(@org.springframework.web.bind.annotation.PathVariable java.util.UUID id) {
        return ResponseEntity.ok(staffService.activateStaff(id));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/{id}")
    public ResponseEntity<Void> removeStaff(@org.springframework.web.bind.annotation.PathVariable java.util.UUID id) {
        staffService.removeStaff(id);
        return ResponseEntity.noContent().build();
    }
}
