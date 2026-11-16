package com.hotel.workload.security;

import com.hotel.workload.staff.Department;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * How much of the data the current user may reach, as decided by {@link CurrentUserScopeResolver}.
 *
 * <ul>
 *   <li>{@link Kind#ALL}: every department (Administrators).</li>
 *   <li>{@link Kind#DEPARTMENT}: a valid Manager: their primary department plus any additional departments an Administrator granted.</li>
 *   <li>{@link Kind#OWN}: no department-level access at all; the caller only reaches their own records (Staff). It is deliberately
 *       a separate kind so a Staff member can never be mistaken for a department-scoped or global user.</li>
 * </ul>
 *
 * <p><b>The departments a Manager manages.</b> {@link #managedDepartments()} is the primary department (the department of the Manager's own
 * Staff record) followed by the active departments an Administrator granted ({@link #additionalDepartments()}). Every one of them gives the
 * same, full Manager-level operational access: {@link #canManage(Department)} is the authority for every read and write a Manager makes.
 * {@link #primaryDepartment()} identifies the primary department, for defaults and labels only. The grants never widen anything else:
 * Administrator-only operations are refused by role, and moving a record between departments stays Administrator-only in the services.
 *
 * <p>{@link #department()}, {@link #departmentCode()}, {@link #includes(Department)} and {@link #includesCode(String)} are the older
 * primary-department-only methods. No production code uses them for authorization any more; they still answer for the primary
 * department only, so a call that slipped back in could only under-grant.
 *
 * A scope never carries information supplied by the client: it is built only from database records.
 */
public final class DepartmentScope {

    public enum Kind { ALL, DEPARTMENT, OWN }

    private static final DepartmentScope ALL = new DepartmentScope(Kind.ALL, null, List.of());
    private static final DepartmentScope OWN = new DepartmentScope(Kind.OWN, null, List.of());

    private final Kind kind;
    private final Department department;
    /** The Administrator-granted departments a Manager also manages. Empty for every other kind. Never contains the primary department. */
    private final List<Department> additional;

    private DepartmentScope(Kind kind, Department department, List<Department> additional) {
        this.kind = kind;
        this.department = department;
        this.additional = additional;
    }

    /** Every department. */
    public static DepartmentScope all() {
        return ALL;
    }

    /** No department-level access: the caller's own records only. */
    public static DepartmentScope own() {
        return OWN;
    }

    /** One department. The department must exist (have a code); callers verify that it is active before building the scope. */
    public static DepartmentScope department(Department department) {
        if (department == null || department.getCode() == null || department.getCode().isBlank()) {
            throw new IllegalArgumentException("A department scope needs a department");
        }
        return new DepartmentScope(Kind.DEPARTMENT, department, List.of());
    }

    /**
     * This DEPARTMENT scope with {@code departments} as additional managed departments (the active departments an Administrator granted).
     * The primary department, departments without a code and duplicates are dropped. Only a DEPARTMENT scope can carry additional
     * departments; asking for it on another kind is a programming error. The caller ({@link CurrentUserScopeResolver}) passes only active
     * departments granted to this Manager's own account.
     */
    public DepartmentScope withAdditionalDepartments(List<Department> departments) {
        if (kind != Kind.DEPARTMENT) {
            throw new IllegalStateException("Only a department scope can carry additional departments");
        }
        Map<String, Department> byCode = new LinkedHashMap<>();
        if (departments != null) {
            for (Department other : departments) {
                if (other == null || other.getCode() == null || other.getCode().isBlank() || other.getCode().equals(department.getCode())) {
                    continue;
                }
                byCode.putIfAbsent(other.getCode(), other);
            }
        }
        return new DepartmentScope(Kind.DEPARTMENT, department, List.copyOf(byCode.values()));
    }

    public Kind kind() {
        return kind;
    }

    public boolean isAll() {
        return kind == Kind.ALL;
    }

    public boolean isDepartment() {
        return kind == Kind.DEPARTMENT;
    }

    public boolean isOwn() {
        return kind == Kind.OWN;
    }

    // ── the older primary-department-only methods (no production authorization uses them) ────────────────────────────────

    /** The primary department of a {@link Kind#DEPARTMENT} scope; any other kind has none, and asking for it is a programming error. */
    public Department department() {
        if (kind != Kind.DEPARTMENT) {
            throw new IllegalStateException("A " + kind + " scope has no single department");
        }
        return department;
    }

    /** The primary department code of a {@link Kind#DEPARTMENT} scope. */
    public String departmentCode() {
        return department().getCode();
    }

    /** Whether {@code other} is this scope's PRIMARY department (ALL: any; OWN: none). Use {@link #canManage(Department)} for authorization. */
    public boolean includes(Department other) {
        return includesCode(other == null ? null : other.getCode());
    }

    /** Same as {@link #includes(Department)}, for a department code. */
    public boolean includesCode(String departmentCode) {
        return switch (kind) {
            case ALL -> true;
            case DEPARTMENT -> departmentCode != null && departmentCode.equals(department.getCode());
            case OWN -> false;
        };
    }

    // ── the authorized-department API ──────────────────────────────────────────────────────────────────────────────────

    /**
     * The Manager's primary department: the department of their own Staff record. Only for defaults and labels (for example the department
     * a task gets when none is chosen), never for an authorization decision, which uses {@link #canManage} or {@link #managedDepartments}.
     * Asking on another kind is a programming error.
     */
    public Department primaryDepartment() {
        return department();
    }

    /** The Administrator-granted departments this Manager also manages (never the primary one). Empty for ALL and OWN. */
    public List<Department> additionalDepartments() {
        return additional;
    }

    /**
     * The departments this DEPARTMENT scope may MANAGE: the primary department first, then the additional ones. Asking on another kind is
     * a programming error: ALL manages every department and OWN none.
     */
    public List<Department> managedDepartments() {
        Department primary = department();
        List<Department> result = new ArrayList<>(additional.size() + 1);
        result.add(primary);
        result.addAll(additional);
        return Collections.unmodifiableList(result);
    }

    /** The codes of {@link #managedDepartments()}, in the same order. */
    public List<String> managedDepartmentCodes() {
        return managedDepartments().stream().map(Department::getCode).toList();
    }

    /** Whether data of {@code other} may be MANAGED: ALL any department (and records without one); DEPARTMENT a managed one; OWN none. */
    public boolean canManage(Department other) {
        return canManageCode(other == null ? null : other.getCode());
    }

    /** Same as {@link #canManage(Department)}, for a department code. */
    public boolean canManageCode(String departmentCode) {
        return switch (kind) {
            case ALL -> true;
            case DEPARTMENT -> departmentCode != null && managedDepartmentCodes().contains(departmentCode);
            case OWN -> false;
        };
    }

    /** The codes of the additional departments, as a set (order does not matter for equality). */
    private Set<String> additionalCodes() {
        return additional.stream().map(Department::getCode).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Two scopes are equal only when they grant the same access: the same kind, the same primary department and the same set of additional
     * departments.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof DepartmentScope that)) return false;
        return kind == that.kind
                && Objects.equals(kind == Kind.DEPARTMENT ? department.getCode() : null, that.kind == Kind.DEPARTMENT ? that.department.getCode() : null)
                && additionalCodes().equals(that.additionalCodes());
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, kind == Kind.DEPARTMENT ? department.getCode() : null, additionalCodes());
    }

    @Override
    public String toString() {
        if (kind != Kind.DEPARTMENT) {
            return "DepartmentScope[" + kind + "]";
        }
        if (additional.isEmpty()) {
            return "DepartmentScope[" + department.getCode() + "]";
        }
        return "DepartmentScope[" + department.getCode() + ", additional " + additional.stream().map(Department::getCode).toList() + "]";
    }
}
