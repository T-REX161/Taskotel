package com.hotel.workload.profile;

import com.hotel.workload.security.User;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Rules for the display name: what is accepted, and what is shown when none has been chosen. */
public final class DisplayNames {

    public static final int MAX_LENGTH = 50;
    static final String EMPTY_MESSAGE = "Display name cannot be empty.";
    static final String TOO_LONG_MESSAGE = "Display name must be " + MAX_LENGTH + " characters or fewer.";
    static final String INVALID_MESSAGE = "Display name cannot contain control characters or line breaks.";

    private DisplayNames() {}

    /** The trimmed name, or a 400 explaining what is wrong. Letters of any language, digits, spaces and punctuation are fine. */
    public static String validate(String requested) {
        String name = requested == null ? "" : requested.strip();
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, EMPTY_MESSAGE);
        }
        if (name.codePointCount(0, name.length()) > MAX_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, TOO_LONG_MESSAGE);
        }
        if (name.codePoints().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, INVALID_MESSAGE);
        }
        return name;
    }

    /** The chosen name, or the username with a capital first letter (what the navigation showed before names could be chosen). */
    public static String effective(User user) {
        String chosen = user.getDisplayName();
        if (chosen != null && !chosen.isBlank()) {
            return chosen;
        }
        String username = user.getUsername() == null ? "" : user.getUsername().strip();
        return username.isEmpty() ? "Account" : username.substring(0, 1).toUpperCase() + username.substring(1);
    }
}
