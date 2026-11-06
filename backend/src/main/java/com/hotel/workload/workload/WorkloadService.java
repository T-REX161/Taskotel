package com.hotel.workload.workload;

import java.util.List;

public interface WorkloadService {
    /**
     * Calculate and return the current workload the signed-in user may see (their scope comes from CurrentUserScopeResolver, never from
     * a caller-supplied value): every department for an Admin, the own department's staff for a valid Manager, the own row for Staff.
     * Workload is derived from active assignments in PostgreSQL and is never stored permanently.
     */
    List<WorkloadDto> calculateWorkload();
}
