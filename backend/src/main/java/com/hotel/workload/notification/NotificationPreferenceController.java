package com.hotel.workload.notification;

import com.hotel.workload.notification.NotificationPreferenceDtos.PreferencesResponse;
import com.hotel.workload.notification.NotificationPreferenceDtos.UpdateRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user's own notification preferences, for every role. The account is taken from the verified token only; these
 * endpoints accept no user id, so nobody can read or change another account's preferences through them.
 */
@RestController
@RequestMapping("/api/notification-preferences")
public class NotificationPreferenceController {

    private final NotificationPreferenceService service;

    public NotificationPreferenceController(NotificationPreferenceService service) {
        this.service = service;
    }

    @GetMapping
    public PreferencesResponse get(Authentication authentication) {
        return service.get(nameOf(authentication));
    }

    @PutMapping
    public PreferencesResponse update(Authentication authentication, @RequestBody UpdateRequest request) {
        return service.update(nameOf(authentication), request);
    }

    private static String nameOf(Authentication authentication) {
        return authentication == null ? null : authentication.getName();
    }
}
