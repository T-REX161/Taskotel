package com.hotel.workload.security;

import com.hotel.workload.staff.Department;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * <p><b>Two separate kinds of department access.</b> {@link #department()}, {@link #includes(Department)} and
 * {@link #includesCode(String)} describe the Manager's PRIMARY department only: they are the authority for every write and every
 * management decision, and they are never widened. A Manager may in addition hold view-only access to other departments, granted by an
 * Administrator ({@link #viewOnlyDepartments()}); that access is answered only by {@link #canView(Department)} and
 * {@link #viewableDepartments()}, which read paths use to list data. A read path that does not ask for it simply shows the primary
 * department, so missing it under-exposes rather than over-exposes, and no write path can be reached through it.
 *
 * A scope never carries information supplied by the client: it is built only from database records.
 */
public final class DepartmentScope {

    public enum Kind { ALL, DEPARTMENT, OWN }

    private static final DepartmentScope ALL = new DepartmentScope(Kind.ALL, null, List.of());
    private static final DepartmentScope OWN = new DepartmentScope(Kind.OWN, null, List.of());

    private final Kind kind;
    private final Department department;
    /** Additional departments a Manager may VIEW (never manage). Empty for every other kind. Never contains the primary department. */
    private final List<Department> viewOnly;

    private DepartmentScope(Kind kind, Department department, List<Department> viewOnly) {
        this.kind = kind;
        this.department = department;
        this.viewOnly = viewOnly;
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
     * This DEPARTMENT scope with view-only access to {@code departments} as well. The primary department, departments without a code
     * and duplicates are dropped. Only a DEPARTMENT scope can carry view-only access; asking for it on another kind is a programming error.
     * The primary department and every write authority ({@link #department()}, {@link #includes(Department)}) are unchanged.
     */
    public DepartmentScope withViewOnly(List<Department> departments) {
        if (kind != Kind.DEPARTMENT) {
            throw new IllegalStateException("Only a department scope can carry view-only departments");
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

    /** The one (primary) department of a {@link Kind#DEPARTMENT} scope; any other kind has none, and asking for it is a programming error. */
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
     * Whether data belonging to {@code other} is inside this scope FOR MANAGEMENT. ALL includes everything (also records with no
     * department); DEPARTMENT includes only the primary department (never a view-only one); OWN includes no department; a missing
     * department is never inside a DEPARTMENT scope.
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

    /** The departments this Manager may only view (never the primary one). Empty for ALL and OWN. */
    public List<Department> viewOnlyDepartments() {
        return viewOnly;
    }

    /** For a DEPARTMENT scope: the primary department first, then the view-only ones. Asking on another kind is a programming error. */
    public List<Department> viewableDepartments() {
        Department primary = department();
        List<Department> result = new ArrayList<>(viewOnly.size() + 1);
        result.add(primary);
        result.addAll(viewOnly);
        return Collections.unmodifiableList(result);
    }

    /** Whether data of {@code other} may be READ: ALL any; DEPARTMENT the primary or a view-only department; OWN none. */
    public boolean canView(Department other) {
        return canViewCode(other == null ? null : other.getCode());
    }

    /** Same as {@link #canView(Department)}, for a department code. */
    public boolean canViewCode(String departmentCode) {
        return switch (kind) {
            case ALL -> true;
            case DEPARTMENT -> departmentCode != null && (departmentCode.equals(department.getCode()) || isViewOnlyCode(departmentCode));
            case OWN -> false;
        };
    }

    /** True only when {@code other} is visible to this scope through a view-only grant (so its data must be shown read-only). */
    public boolean isViewOnly(Department other) {
        return other != null && isViewOnlyCode(other.getCode());
    }

    /** Same as {@link #isViewOnly(Department)}, for a department code. */
    public boolean isViewOnlyCode(String departmentCode) {
        if (kind != Kind.DEPARTMENT || departmentCode == null) {
            return false;
        }
        for (Department other : viewOnly) {
            if (departmentCode.equals(other.getCode())) {
                return true;
            }
        }
        return false;
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
        if (kind != Kind.DEPARTMENT) {
            return "DepartmentScope[" + kind + "]";
        }
        if (viewOnly.isEmpty()) {
            return "DepartmentScope[" + department.getCode() + "]";
        }
        return "DepartmentScope[" + department.getCode() + ", view-only " + viewOnly.stream().map(Department::getCode).toList() + "]";
    }
}
