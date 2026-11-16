package com.hotel.workload.staff;

import java.util.List;

public interface DepartmentService {

    /** Every department, active and inactive, with live staff and active-task counts. */
    List<DepartmentDto> getAllDepartments();

    /**
     * The departments the Workload page shows as cards. An Admin and a Staff member get exactly what {@link #getAllDepartments()} gives them.
     * A Manager gets ONLY the departments they manage (their primary department and the active departments an Administrator granted), each
     * in full and marked {@code managed}: no other department is returned, not even as a basic identity, whatever its workload is.
     */
    List<DepartmentDto> getDepartmentsForWorkload();

    DepartmentDto createDepartment(CreateDepartmentRequest request);

    DepartmentDto updateDepartment(String code, UpdateDepartmentRequest request);

    /**
     * Removes a department that nothing uses. Refuses (409, nothing changed) for the built-in departments and for
     * any department that still has staff or tasks; never deletes or reassigns those records.
     */
    void deleteDepartment(String code);
}
