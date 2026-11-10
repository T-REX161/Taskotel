package com.hotel.workload.staff;

import java.time.LocalDateTime;
import java.util.UUID;

/** Request and response shapes of the Admin-only Manager department operations. */
public final class ManagerDepartmentDtos {

    private ManagerDepartmentDtos() {
    }

    /** Body of the assignment: the department code (the stable key used everywhere else in the API). */
    public record AssignRequest(String department) {
    }

    /**
     * A Manager current department, read from the linked Staff record (the only place it is stored).
     * {@code valid} says whether the Manager would pass the scope check right now (active account, active Staff record, active
     * department); an Admin can see why a Manager is being refused without the refusal itself revealing it.
     */
    public record ManagerDepartment(UUID userId, String username, UUID staffId, String staffName,
                                    String departmentCode, String departmentName, boolean departmentActive,
                                    boolean accountActive, boolean staffActive, boolean valid) {

        static ManagerDepartment of(Staff staff) {
            Department department = staff.getDepartment();
            boolean accountActive = staff.getUser().isActive();
            boolean valid = accountActive && staff.isActive() && department != null && department.isActive();
            return new ManagerDepartment(staff.getUser().getId(), staff.getUser().getUsername(), staff.getId(), staff.getName(),
                    department == null ? null : department.getCode(), department == null ? null : department.getName(),
                    department != null && department.isActive(), accountActive, staff.isActive(), valid);
        }
    }

    /** One audit record, exactly the fields stored. */
    public record Change(UUID id, UUID managerUserId, String managerUsername, UUID staffId, String staffName,
                         UUID previousDepartmentId, String previousDepartmentCode, String previousDepartmentName,
                         UUID newDepartmentId, String newDepartmentCode, String newDepartmentName,
                         UUID changedByUserId, String changedByUsername, String changeSource, LocalDateTime changedAt) {

        static Change of(ManagerDepartmentChange c) {
            return new Change(c.getId(), c.getManagerUserId(), c.getManagerUsername(), c.getStaffId(), c.getStaffName(),
                    c.getPreviousDepartmentId(), c.getPreviousDepartmentCode(), c.getPreviousDepartmentName(),
                    c.getNewDepartmentId(), c.getNewDepartmentCode(), c.getNewDepartmentName(),
                    c.getChangedByUserId(), c.getChangedByUsername(), c.getChangeSource(), c.getChangedAt());
        }
    }
}
