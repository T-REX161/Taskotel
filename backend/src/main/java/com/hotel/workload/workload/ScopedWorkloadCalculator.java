package com.hotel.workload.workload;

import com.hotel.workload.security.DepartmentScope;

import java.util.List;

/**
 * Internal seam that lets the dashboard reuse the workload calculation under the scope it has ALREADY resolved from
 * {@link com.hotel.workload.security.CurrentUserScopeResolver}, so one dashboard request uses one scope snapshot.
 *
 * <p>Not a permission API: it is not on {@link WorkloadService}, no controller exposes it, and no request value can reach it. Because a
 * {@link DepartmentScope} is trusted as given (it can only come from the resolver), only {@code DashboardServiceImpl} may call it;
 * {@code ScopedWorkloadCalculatorUsageTest} fails if another production class references it or implements it.
 * {@link WorkloadService#calculateWorkload()} resolves its own scope and is the only path HTTP requests use.
 */
public interface ScopedWorkloadCalculator {
    /** The workload rows for an already-resolved scope. For {@code OWN} the Staff member is the signed-in user. */
    List<WorkloadDto> calculateWorkload(DepartmentScope scope);
}
