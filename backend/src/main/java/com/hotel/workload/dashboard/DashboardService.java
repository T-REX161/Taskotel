package com.hotel.workload.dashboard;

import org.springframework.security.core.Authentication;

public interface DashboardService {
    Object getDashboardSummary(Authentication authentication);
}
