package com.hotel.workload.staff;

import java.util.List;

public interface StaffService {
    List<StaffDto> getAllStaff();
    StaffDto createStaff(CreateStaffRequest request);
    StaffDto updateStaff(java.util.UUID id, UpdateStaffRequest request);
    StaffDto deactivateStaff(java.util.UUID id);
    StaffDto activateStaff(java.util.UUID id);
    void removeStaff(java.util.UUID id);
}
