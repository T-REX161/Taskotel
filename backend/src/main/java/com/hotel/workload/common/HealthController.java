package com.hotel.workload.common;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Liveness probe for the hosting platform (for example Render's health check path). It is outside {@code /api}, carries no data and needs no
 * sign-in. It deliberately does not touch the database: it answers "the process is up and serving", so a sleeping managed database never makes
 * the platform restart a healthy instance. Readiness of the data layer is proven by the application failing to start when it cannot connect.
 */
@RestController
public class HealthController {

    @GetMapping("/healthz")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }
}
