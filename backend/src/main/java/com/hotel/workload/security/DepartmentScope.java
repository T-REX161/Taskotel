package com.hotel.workload.security;

import com.hotel.workload.staff.Department;

import java.util.Objects;

/**
 * How much of the data the current user may reach, as decided by {@link CurrentUserScopeResolver}.
 *
 * <ul>
 *   <li>{@link Kind#ALL}: every department (Administrators).</li>
 *   <li>{@link Kind#DEPARTMENT}: exactly one department (a valid Manager).</li>
 *   <li>{@link Kind#OWN}: no department-level access at all; the caller only reaches their own records (Staff). It is deliberately
 *       a separate kind so a Staff member can never be mistaken for a department-scoped or global user.</li>
 * </ul>
 *
 * A scope never carries information supplied by the client: it is built only from database records.
 */
public final class DepartmentScope {

    public enum Kind { ALL, DEPARTMENT, OWN }

    private static final DepartmentScope ALL = new DepartmentScope(Kind.ALL, null);
    private static final DepartmentScope OWN = new DepartmentScope(Kind.OWN, null);

    private final Kind kind;
    private final Department department;

    private DepartmentScope(Kind kind, Department department) {
        this.kind = kind;
        this.department = department;
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
        return new DepartmentScope(Kind.DEPARTMENT, department);
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

    /** The one department of a {@link Kind#DEPARTMENT} scope; any other kind has none, and asking for it is a programming error. */
    public Department department() {
        if (kind != Kind.DEPARTMENT) {
            throw new IllegalStateException("A " + kind + " scope has no single department");
        }
        return department;
    }

    /** The department code of a {@link Kind#DEPARTMENT} scope. */
    public String departmentCode() {
        return department().getCode();
    }

    /**
     * Whether data belonging to {@code other} is inside this scope. ALL includes everything (also records with no department);
     * DEPARTMENT includes only that department; OWN includes no department; a missing department is never inside a DEPARTMENT scope.
     */
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

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof DepartmentScope that)) return false;
        return kind == that.kind && Objects.equals(kind == Kind.DEPARTMENT ? department.getCode() : null,
                that.kind == Kind.DEPARTMENT ? that.department.getCode() : null);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, kind == Kind.DEPARTMENT ? department.getCode() : null);
    }

    @Override
    public String toString() {
        return kind == Kind.DEPARTMENT ? "DepartmentScope[" + department.getCode() + "]" : "DepartmentScope[" + kind + "]";
    }
}
