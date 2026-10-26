package com.hotel.workload.staff;

import java.util.List;

public interface DepartmentService {

    /** Every department, active and inactive, with live staff and active-task counts. */
    List<DepartmentDto> getAllDepartments();

    DepartmentDto createDepartment(CreateDepartmentRequest request);

    DepartmentDto updateDepartment(String code, UpdateDepartmentRequest request);

    /**
     * Removes a department that nothing uses. Refuses (409, nothing changed) for the built-in departments and for
     * any department that still has staff or tasks; never deletes or reassigns those records.
     */
    void deleteDepartment(String code);
}
