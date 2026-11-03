package com.hotel.workload.profile;

import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The signed-in user's own profile and profile picture, for every role. The account is taken from the verified token only;
 * these endpoints accept no user id, so nobody can read or change another account's picture through them.
 */
@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final ProfilePictureService profilePictureService;

    public ProfileController(ProfilePictureService profilePictureService) {
        this.profilePictureService = profilePictureService;
    }

    @GetMapping
    public ProfileDto getProfile(Authentication authentication) {
        return profilePictureService.getProfile(nameOf(authentication));
    }

    /** Changes the signed-in user's display name. Only that name: username, role and picture are untouched. */
    @PutMapping("/display-name")
    public ProfileDto updateDisplayName(Authentication authentication, @RequestBody UpdateDisplayNameRequest request) {
        return profilePictureService.updateDisplayName(nameOf(authentication), request.displayName());
    }

    public record UpdatePhoneRequest(String phone) {}

    /** Changes the signed-in user's own phone number (not verified; no SMS). Only that number: nothing else on the account changes. */
    @PutMapping("/phone")
    public ProfileDto updatePhone(Authentication authentication, @RequestBody UpdatePhoneRequest request) {
        return profilePictureService.updatePhone(nameOf(authentication), request == null ? null : request.phone());
    }

    /** Saves the signed-in user's own appearance, date format and time format. Nothing else on the account changes. */
    @PutMapping("/preferences")
    public ProfileDto updatePreferences(Authentication authentication, @RequestBody UserPreferences.Values request) {
        return profilePictureService.updatePreferences(nameOf(authentication), request);
    }

    /** Uploads a new picture (multipart field "file"), replacing the current one only once the new one is stored and saved. */
    @PostMapping(path = "/picture", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ProfileDto uploadPicture(Authentication authentication,
                                    @RequestParam(value = "file", required = false) MultipartFile file) {
        return profilePictureService.uploadPicture(nameOf(authentication), file);
    }

    @DeleteMapping("/picture")
    public ProfileDto removePicture(Authentication authentication) {
        return profilePictureService.removePicture(nameOf(authentication));
    }

    private static String nameOf(Authentication authentication) {
        return authentication == null ? null : authentication.getName();
    }
}
