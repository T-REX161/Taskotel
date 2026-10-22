package com.hotel.workload.workload;

import java.util.List;

public interface WorkloadService {
    /**
     * Calculate and return the current workload for every staff member.
     * Workload is derived from active assignments in PostgreSQL and is
     * never stored permanently.
     */
    List<WorkloadDto> calculateWorkload(String username, boolean isAdminOrManager);
}
