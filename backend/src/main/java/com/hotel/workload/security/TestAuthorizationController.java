package com.hotel.workload.security;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/test")
@Profile("!prod & !production")
public class TestAuthorizationController {

    @GetMapping("/admin")
    public ResponseEntity<String> getAdminEndpoint() {
        return ResponseEntity.ok("Admin access granted");
    }

    @GetMapping("/manager")
    public ResponseEntity<String> getManagerEndpoint() {
        return ResponseEntity.ok("Manager access granted");
    }

    @GetMapping("/staff")
    public ResponseEntity<String> getStaffEndpoint() {
        return ResponseEntity.ok("Staff access granted");
    }
}
