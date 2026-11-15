package com.hotel.workload.staff;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Request and response shapes of the Admin-only Manager view-access operations. */
public final class ManagerViewAccessDtos {

    private ManagerViewAccessDtos() {
    }

    /** Body of the replacement: the complete set of department codes the Manager may view in addition to their own. */
    public record UpdateRequest(List<String> departments) {
    }

    /** One department the Manager may view. {@code active} false: kept, but not visible to the Manager while inactive. */
    public record Grant(String departmentCode, String departmentName, boolean active, LocalDateTime grantedAt, String grantedBy) {

        static Grant of(ManagerViewGrant grant) {
            Department department = grant.getDepartment();
            return new Grant(department.getCode(), department.getName(), department.isActive(), grant.getGrantedAt(),
                    grant.getGrantedByUsername());
        }
    }

    /**
     * A Manager's view access: their primary department (always included, from the linked Staff record; null when none) and the
     * additional departments they may view only.
     */
    public record ViewAccess(UUID userId, String username, String primaryDepartmentCode, String primaryDepartmentName,
                             List<Grant> departments) {
    }

    /** One audit record. */
    public record Change(UUID id, String departmentCode, String departmentName, String action, String changeSource,
                         String changedByUsername, LocalDateTime changedAt) {

        static Change of(ManagerViewGrantChange change) {
            return new Change(change.getId(), change.getDepartmentCode(), change.getDepartmentName(), change.getAction(),
                    change.getChangeSource(), change.getChangedByUsername(), change.getChangedAt());
        }
    }
}
