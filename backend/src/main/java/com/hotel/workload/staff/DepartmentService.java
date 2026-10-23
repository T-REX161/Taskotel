package com.hotel.workload.staff;

import java.util.List;

public interface DepartmentService {

    /** Every department, active and inactive, with live staff and active-task counts. */
    List<DepartmentDto> getAllDepartments();

    DepartmentDto createDepartment(CreateDepartmentRequest request);

    DepartmentDto updateDepartment(String code, UpdateDepartmentRequest request);
}
