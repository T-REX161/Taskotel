package com.hotel.workload.security;

import com.hotel.workload.staff.Department;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Administrator-granted additional departments on a scope (P3 of the multi-department Manager change; formerly view-only departments).
 * A granted department is managed like the primary one; the primary department stays identifiable, and nothing outside the grants is
 * ever reached.
 */
class DepartmentScopeAdditionalDepartmentsTest {

    private static Department dept(String code) {
        return new Department(code, code + " name");
    }

    @Test
    @DisplayName("A granted department is managed like the primary one; the primary department stays identifiable; others are never managed")
    void grantedDepartmentsAreManaged() {
        Department home = dept("HOME");
        Department granted = dept("GRANTED");
        Department other = dept("OTHER");
        DepartmentScope scope = DepartmentScope.department(home).withAdditionalDepartments(List.of(granted));

        assertSame(home, scope.primaryDepartment());
        assertEquals(List.of(granted), scope.additionalDepartments());
        assertEquals(List.of(home, granted), scope.managedDepartments(), "primary first");
        assertEquals(List.of("HOME", "GRANTED"), scope.managedDepartmentCodes());
        assertTrue(scope.canManage(home));
        assertTrue(scope.canManage(granted));
        assertTrue(scope.canManageCode("GRANTED"));
        assertFalse(scope.canManage(other));
        assertFalse(scope.canManageCode("OTHER"));
        assertFalse(scope.canManage(null));
        assertFalse(scope.canManageCode(null));
        assertFalse(scope.canManageCode(""));
        // The older primary-only methods still describe the primary department alone (no production authorization uses them)
        assertSame(home, scope.department());
        assertTrue(scope.includes(home));
        assertFalse(scope.includes(granted));
        // Equality includes the grants
        assertFalse(DepartmentScope.department(home).equals(scope), "a scope with a granted department differs from one without");
        assertEquals(DepartmentScope.department(home).withAdditionalDepartments(List.of(granted)), scope);
    }

    @Test
    @DisplayName("The primary department, duplicates, nulls and code-less departments are dropped from the additional departments")
    void additionalListIsCleaned() {
        Department home = dept("HOME");
        Department granted = dept("GRANTED");
        DepartmentScope scope = DepartmentScope.department(home)
                .withAdditionalDepartments(Arrays.asList(granted, home, dept("HOME"), dept("GRANTED"), null, new Department()));
        assertEquals(List.of(granted), scope.additionalDepartments());
        assertEquals(List.of(home, granted), scope.managedDepartments(), "the primary department is never listed twice");
        assertEquals(List.of(), DepartmentScope.department(home).withAdditionalDepartments(null).additionalDepartments());
    }

    @Test
    @DisplayName("Admin and Staff scopes carry no additional departments, and cannot be given any")
    void onlyDepartmentScopesCarryAdditionalDepartments() {
        assertTrue(DepartmentScope.all().canManage(dept("ANY")));
        assertFalse(DepartmentScope.own().canManage(dept("ANY")));
        assertTrue(DepartmentScope.all().additionalDepartments().isEmpty());
        assertTrue(DepartmentScope.own().additionalDepartments().isEmpty());
        assertThrows(IllegalStateException.class, () -> DepartmentScope.all().withAdditionalDepartments(List.of(dept("X"))));
        assertThrows(IllegalStateException.class, () -> DepartmentScope.own().withAdditionalDepartments(List.of(dept("X"))));
    }
}
