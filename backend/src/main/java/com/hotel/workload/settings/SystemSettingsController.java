package com.hotel.workload.settings;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** System settings, Admin only (SecurityConfig, and again in the service). No user id or role is accepted from the caller. */
@RestController
@RequestMapping("/api/system-settings")
public class SystemSettingsController {

    private final SystemSettingsService service;

    public SystemSettingsController(SystemSettingsService service) {
        this.service = service;
    }

    @GetMapping
    public SystemSettingsDto get() {
        return service.get();
    }

    @PutMapping
    public SystemSettingsDto update(@Valid @RequestBody UpdateSystemSettingsRequest request) {
        return service.update(request);
    }
}
