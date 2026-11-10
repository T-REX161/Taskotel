package com.hotel.workload.staff;

import java.util.List;
import java.util.UUID;

/** Admin-only: read, assign and change a Manager department, and read the audit history of those changes. */
public interface ManagerDepartmentService {

    ManagerDepartmentDtos.ManagerDepartment get(UUID userId);

    ManagerDepartmentDtos.ManagerDepartment assign(UUID userId, String departmentCode);

    List<ManagerDepartmentDtos.Change> history(UUID userId);
}
