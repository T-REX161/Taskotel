package com.hotel.workload.profile;

import com.hotel.workload.security.User;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * A user's own display preferences: appearance, date format and time format. They are stored as three plain text columns on the
 * user's own row (null until chosen) and read through {@link #of(User)}, which fills in the defaults, so an account that never
 * chose anything behaves exactly as before: light theme, day/month/year dates, 12-hour times.
 */
public final class UserPreferences {

    public static final List<String> THEMES = List.of("LIGHT", "DARK", "SYSTEM");
    public static final List<String> DATE_FORMATS = List.of("DMY", "MDY", "YMD"); // DD/MM/YYYY, MM/DD/YYYY, YYYY-MM-DD
    public static final List<String> TIME_FORMATS = List.of("H12", "H24");        // 12-hour, 24-hour

    public static final String DEFAULT_THEME = "LIGHT";
    public static final String DEFAULT_DATE_FORMAT = "DMY";
    public static final String DEFAULT_TIME_FORMAT = "H12";

    static final String INVALID_THEME = "Appearance must be Light, Dark or System.";
    static final String INVALID_DATE_FORMAT = "Date format must be DD/MM/YYYY, MM/DD/YYYY or YYYY-MM-DD.";
    static final String INVALID_TIME_FORMAT = "Time format must be 12-hour or 24-hour.";

    private UserPreferences() {}

    /** What the client sends and receives (the three codes above). */
    public record Values(String themePreference, String dateFormat, String timeFormat) {}

    /** The user's preferences, with the default in place of anything not chosen (or not recognised). */
    public static Values of(User user) {
        return new Values(
                pick(user.getThemePreference(), THEMES, DEFAULT_THEME),
                pick(user.getDateFormat(), DATE_FORMATS, DEFAULT_DATE_FORMAT),
                pick(user.getTimeFormat(), TIME_FORMATS, DEFAULT_TIME_FORMAT));
    }

    /** The requested values if every one is allowed, otherwise a 400 naming the first that is not. Nothing is guessed or defaulted. */
    public static Values validate(Values requested) {
        if (requested == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, INVALID_THEME);
        }
        return new Values(
                require(requested.themePreference(), THEMES, INVALID_THEME),
                require(requested.dateFormat(), DATE_FORMATS, INVALID_DATE_FORMAT),
                require(requested.timeFormat(), TIME_FORMATS, INVALID_TIME_FORMAT));
    }

    private static String pick(String stored, List<String> allowed, String fallback) {
        return stored != null && allowed.contains(stored) ? stored : fallback;
    }

    private static String require(String value, List<String> allowed, String message) {
        String code = value == null ? "" : value.strip().toUpperCase();
        if (!allowed.contains(code)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return code;
    }
}
