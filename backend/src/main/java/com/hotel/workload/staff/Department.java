package com.hotel.workload.staff;

public enum Department {
    HOUSEKEEPING("Housekeeping"),
    FRONT_DESK("Front Desk"),
    MAINTENANCE("Maintenance"),
    ROOM_SERVICE("Room Service"),
    FOOD_AND_BEVERAGE("Food & Beverage"),
    SECURITY("Security");

    private final String displayName;

    Department(String displayName) {
        this.displayName = displayName;
    }

    /** Human-readable label for user-facing messages. Persistence and JSON still use name(). */
    public String getDisplayName() {
        return displayName;
    }
}
