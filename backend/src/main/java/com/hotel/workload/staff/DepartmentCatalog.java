package com.hotel.workload.staff;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Fixed vocabulary for department presentation, plus the six built-in departments.
 *
 * <p>Icon and colour are stored as stable keys, never as markup. The frontend maps each key to an icon
 * and a colour theme; an unknown key falls back to the {@code building} icon and the {@code gray} colour.
 */
public final class DepartmentCatalog {

    public static final String DEFAULT_ICON_KEY = "building";
    public static final String DEFAULT_COLOR_KEY = "gray";

    public static final Set<String> ICON_KEYS =
            Set.of("building", "sprout", "bell", "wrench", "coffee", "utensils", "shield", "users",
                    "clipboard", "calendar", "clock", "briefcase", "package", "truck", "sparkles", "monitor", "bed");

    public static final Set<String> COLOR_KEYS =
            Set.of("gray", "green", "blue", "purple", "amber", "orange", "cyan", "rose", "indigo", "teal");

    /** The departments that existed as an enum before departments became database records, in their original order. */
    public record BuiltIn(String code, String name, String iconKey, String colorKey) {}

    public static final List<BuiltIn> BUILT_IN = List.of(
            new BuiltIn("HOUSEKEEPING", "Housekeeping", "sprout", "green"),
            new BuiltIn("FRONT_DESK", "Front Desk", "bell", "blue"),
            new BuiltIn("MAINTENANCE", "Maintenance", "wrench", "purple"),
            new BuiltIn("ROOM_SERVICE", "Room Service", "coffee", "amber"),
            new BuiltIn("FOOD_AND_BEVERAGE", "Food & Beverage", "utensils", "orange"),
            new BuiltIn("SECURITY", "Security", "shield", "cyan"));

    private static final int MAX_CODE_LENGTH = 64;

    private DepartmentCatalog() {}

    /**
     * Derives a code from a department name: upper case, "&" as AND, every other run of non-alphanumeric
     * characters as one underscore ("Food & Beverage" gives FOOD_AND_BEVERAGE). A name with no Latin letters
     * or digits (for example one written in another script) gets a random DEPT_ code instead.
     */
    public static String codeFromName(String name) {
        String code = name == null ? "" : name.trim().toUpperCase(Locale.ROOT)
                .replace("&", " AND ")
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (code.isEmpty()) {
            return "DEPT_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        }
        return code.length() > MAX_CODE_LENGTH ? code.substring(0, MAX_CODE_LENGTH).replaceAll("_+$", "") : code;
    }

    /** Appends _2, _3 ... when the code is already taken, keeping it within the maximum length. */
    public static String withSuffix(String code, int attempt) {
        if (attempt <= 1) return code;
        String suffix = "_" + attempt;
        String base = code.length() + suffix.length() > MAX_CODE_LENGTH
                ? code.substring(0, MAX_CODE_LENGTH - suffix.length())
                : code;
        return base + suffix;
    }
}
