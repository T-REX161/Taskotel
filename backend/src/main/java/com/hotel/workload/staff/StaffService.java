package com.hotel.workload.staff;

import java.util.List;

public interface StaffService {
    /** The Staff Management listing: STAFF-role accounts only (never Admin or Manager accounts), within the caller's scope. */
    List<StaffDto> getAllStaff();

    /**
     * As {@link #getAllStaff()}; with {@code includePrivileged} an Administrator also gets the Staff records linked to Manager and Admin
     * accounts (the Users page needs them to link a Manager's department). Ignored for everyone else.
     */
    List<StaffDto> getAllStaff(boolean includePrivileged);
    StaffDto createStaff(CreateStaffRequest request);
    boolean isUsernameAvailable(String username);
    StaffDto updateStaff(java.util.UUID id, UpdateStaffRequest request);
    StaffDto deactivateStaff(java.util.UUID id);
    StaffDto activateStaff(java.util.UUID id);
    void removeStaff(java.util.UUID id);

    /**
     * Starts a verified change of a Staff member's login email (Admin, or a Manager for their own department). The active email is not
     * touched; the Staff member confirms with the code sent to the new address, signed in, themselves.
     */
    com.hotel.workload.profile.EmailChangeService.CodeSent requestLoginEmailChange(java.util.UUID id, String newEmail, String client);
}
