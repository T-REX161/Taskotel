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

/** View-only departments never widen the primary (write) authority of a scope. */
class DepartmentScopeViewOnlyTest {

    private static Department dept(String code) {
        return new Department(code, code + " name");
    }

    @Test
    @DisplayName("includes() and department() stay the primary department only; canView() adds the view-only ones")
    void viewOnlyNeverWidensWriteAuthority() {
        Department home = dept("HOME");
        Department granted = dept("GRANTED");
        Department other = dept("OTHER");
        DepartmentScope scope = DepartmentScope.department(home).withViewOnly(List.of(granted));

        assertSame(home, scope.department());
        assertTrue(scope.includes(home));
        assertFalse(scope.includes(granted), "a view-only department is never inside the management scope");
        assertFalse(scope.includesCode("GRANTED"));
        assertTrue(scope.canView(home));
        assertTrue(scope.canView(granted));
        assertFalse(scope.canView(other));
        assertFalse(scope.canView(null));
        assertTrue(scope.isViewOnly(granted));
        assertFalse(scope.isViewOnly(home));
        assertEquals(List.of(home, granted), scope.viewableDepartments());
        // P1 (multi-department change): equality now compares the granted departments too, so a scope with a grant is no longer
        // mistaken for the same Manager's scope without it. Every assertion above (the write authority) is unchanged.
        assertFalse(DepartmentScope.department(home).equals(scope), "a scope with a granted department differs from one without");
        assertEquals(DepartmentScope.department(home).withViewOnly(List.of(granted)), scope);
    }

    @Test
    @DisplayName("The primary department, duplicates, nulls and code-less departments are dropped from the view-only list")
    void viewOnlyListIsCleaned() {
        Department home = dept("HOME");
        Department granted = dept("GRANTED");
        DepartmentScope scope = DepartmentScope.department(home)
                .withViewOnly(Arrays.asList(granted, home, dept("HOME"), dept("GRANTED"), null, new Department()));
        assertEquals(List.of(granted), scope.viewOnlyDepartments());
        assertEquals(List.of(), DepartmentScope.department(home).withViewOnly(null).viewOnlyDepartments());
    }

    @Test
    @DisplayName("Admin and Staff scopes carry no view-only departments, and cannot be given any")
    void onlyDepartmentScopesCarryViewOnly() {
        assertTrue(DepartmentScope.all().canView(dept("ANY")));
        assertFalse(DepartmentScope.all().isViewOnly(dept("ANY")), "Admin data is never flagged view-only");
        assertFalse(DepartmentScope.own().canView(dept("ANY")));
        assertTrue(DepartmentScope.all().viewOnlyDepartments().isEmpty());
        assertThrows(IllegalStateException.class, () -> DepartmentScope.all().withViewOnly(List.of(dept("X"))));
        assertThrows(IllegalStateException.class, () -> DepartmentScope.own().withViewOnly(List.of(dept("X"))));
    }
}
