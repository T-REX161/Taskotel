package com.hotel.workload.notification;

import java.util.List;

/** Request and response shapes of the notification preference API. Nothing here names a user: the account comes from the token. */
public final class NotificationPreferenceDtos {

    private NotificationPreferenceDtos() {}

    /** One category as the user has it. {@code locked} means it cannot be turned off (security alerts). Email is the only channel. */
    public record PreferenceDto(String category, boolean email, boolean locked) {}

    public record PreferencesResponse(List<PreferenceDto> preferences) {}

    /**
     * A change to one category's email switch, which must be given so a save is always explicit. A leftover {@code inApp} field from an
     * older client is ignored (unknown properties are not rejected).
     */
    public record PreferenceChange(String category, Boolean email) {}

    public record UpdateRequest(List<PreferenceChange> preferences) {}
}
