package com.hotel.workload.profile;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Changing the signed-in user's own email address, with a code sent to the new address. Under /api/**, so a valid token is required.
 * The account is the authenticated one: neither request carries a user, and nothing in them could name one.
 */
@RestController
@RequestMapping("/api/profile/email-change")
public class EmailChangeController {

    /** The new address and the signed-in user's CURRENT password (re-authentication). Neither names an account. */
    public record NewEmailBody(String newEmail, String currentPassword) {}
    public record ConfirmBody(String code) {}

    private final EmailChangeService service;
    private final ProfilePictureService profiles;

    public EmailChangeController(EmailChangeService service, ProfilePictureService profiles) {
        this.service = service;
        this.profiles = profiles;
    }

    /** Sends a code to the new address (and again for a resend). Answers with the masked address and the timings, never the code. */
    @PostMapping("/request")
    public EmailChangeService.CodeSent request(@RequestBody NewEmailBody body, Authentication authentication, HttpServletRequest http) {
        return service.request(nameOf(authentication), http.getRemoteAddr(), body == null ? null : body.newEmail(),
                body == null ? null : body.currentPassword());
    }

    /** Verifies the code and switches the email. Answers with the updated profile, so the screen can show the new address at once. */
    @PostMapping("/confirm")
    public ProfileDto confirm(@RequestBody ConfirmBody body, Authentication authentication, HttpServletRequest http) {
        String username = nameOf(authentication);
        try {
            service.confirm(username, http.getRemoteAddr(), body == null ? null : body.code());
        } catch (DataIntegrityViolationException e) {
            // The database refused the switch (the address was taken at the last moment): it was rolled back, the old email stays
            throw new ResponseStatusException(HttpStatus.CONFLICT, EmailChangeService.IN_USE);
        }
        return profiles.getProfile(username);
    }

    private static String nameOf(Authentication authentication) {
        return authentication == null ? null : authentication.getName();
    }
}
