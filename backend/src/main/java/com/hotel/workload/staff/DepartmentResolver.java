package com.hotel.workload.staff;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;
import java.util.UUID;

/**
 * Turns the department code sent by the API into a Department, and enforces that an inactive department
 * cannot be chosen for new staff or tasks. Records that already belong to an inactive department keep
 * working: callers only ask for an active department when the department actually changes.
 */
@Component
public class DepartmentResolver {

    private final DepartmentRepository departmentRepository;

    public DepartmentResolver(DepartmentRepository departmentRepository) {
        this.departmentRepository = departmentRepository;
    }

    /** The department for this code, which must exist and be active. */
    public Department requireActive(String code) {
        if (code == null || code.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Department is required");
        }
        Department department = departmentRepository.findByCode(code.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown department: " + code.trim()));
        if (!department.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Department '" + department.getName() + "' is inactive and cannot be used for new staff or tasks");
        }
        return department;
    }

    /** Null for a blank code (a task may have no department); otherwise the same rules as {@link #requireActive}. */
    public Department optionalActive(String code) {
        return code == null || code.isBlank() ? null : requireActive(code);
    }

    /**
     * The department to store when a record is edited: the record's current department when the code is
     * unchanged (even if that department is now inactive), otherwise the requested active one.
     */
    public Department forUpdate(Department current, String requestedCode, boolean required) {
        String requested = requestedCode == null ? "" : requestedCode.trim();
        if (requested.isEmpty()) {
            if (required) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Department is required");
            }
            return null;
        }
        if (current != null && Objects.equals(current.getCode(), requested)) {
            return current;
        }
        return requireActive(requested);
    }

    /** Clears any department-head reference to this staff member so the staff record can be removed. */
    public void clearHeadStaff(UUID staffId) {
        // Through the managed entities, so the persistence context never keeps a reference to the staff member being removed
        for (Department department : departmentRepository.findByHeadStaffId(staffId)) {
            department.setHeadStaff(null);
        }
        departmentRepository.flush();
    }
}
