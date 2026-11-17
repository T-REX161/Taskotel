package com.hotel.workload.workload;

import java.util.List;

public interface WorkloadService {
    /**
     * Calculate and return the current workload the signed-in user may see (their scope comes from CurrentUserScopeResolver, never from
     * a caller-supplied value): every department for an Admin, the own department's staff for a valid Manager, the own row for Staff.
     * Workload is derived from active assignments in PostgreSQL and is never stored permanently.
     */
    List<WorkloadDto> calculateWorkload();


    /**
     * The workload of each department the signed-in user may see (every department for an Admin, the managed departments for a valid Manager;
     * Staff are refused with 403), calculated from the departments' own active tasks, each task once. Not the sum of the staff rows.
     */
    List<DepartmentWorkloadDto> calculateDepartmentWorkload();
}
