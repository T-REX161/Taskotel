package com.hotel.workload.security;

import com.hotel.workload.staff.Department;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1 of the multi-department Manager change: the authorized-department API on {@link DepartmentScope} and its equality. In this phase the
 * new methods must answer exactly like the primary-department methods they will replace (no behaviour change); Administrator grants are
 * still view-only and are NOT manageable.
 */
class DepartmentScopeAuthorizedSetTest {

    private static Department dept(String code) {
        return new Department(code, code + " name");
    }

    private static final List<String> CODES = Arrays.asList("HOME", "GRANTED", "OTHER", "", null);

    @Test
    @DisplayName("A Manager without grants: the primary department is the only managed one, and canManage answers like includes")
    void managerWithoutGrants() {
        Department home = dept("HOME");
        DepartmentScope scope = DepartmentScope.department(home);
        assertSame(home, scope.primaryDepartment());
        assertEquals(List.of(home), scope.managedDepartments());
        assertEquals(List.of("HOME"), scope.managedDepartmentCodes());
        assertTrue(scope.canManage(home));
        assertFalse(scope.canManage(dept("OTHER")));
        assertFalse(scope.canManage(null));
        assertFalse(scope.canManage(new Department()), "a department without a code is never managed");
        for (String code : CODES) {
            assertEquals(scope.includesCode(code), scope.canManageCode(code), String.valueOf(code));
        }
    }

    @Test
    @DisplayName("A Manager with view-only grants: in this phase a granted department is still NOT manageable")
    void grantsAreNotManageableYet() {
        Department home = dept("HOME");
        Department granted = dept("GRANTED");
        DepartmentScope scope = DepartmentScope.department(home).withViewOnly(List.of(granted));
        assertEquals(List.of(home), scope.managedDepartments());
        assertFalse(scope.canManage(granted));
        assertFalse(scope.canManageCode("GRANTED"));
        assertTrue(scope.canView(granted), "still readable, exactly as before");
        for (String code : CODES) {
            assertEquals(scope.includesCode(code), scope.canManageCode(code), String.valueOf(code));
        }
    }

    @Test
    @DisplayName("Admin manages every department (and records with none); Staff manage none; neither has a managed-department list")
    void adminAndStaff() {
        for (String code : CODES) {
            assertTrue(DepartmentScope.all().canManageCode(code), String.valueOf(code));
            assertFalse(DepartmentScope.own().canManageCode(code), String.valueOf(code));
            assertEquals(DepartmentScope.all().includesCode(code), DepartmentScope.all().canManageCode(code));
            assertEquals(DepartmentScope.own().includesCode(code), DepartmentScope.own().canManageCode(code));
        }
        assertTrue(DepartmentScope.all().canManage(null));
        assertFalse(DepartmentScope.own().canManage(dept("ANY")));
        assertThrows(IllegalStateException.class, () -> DepartmentScope.all().managedDepartments());
        assertThrows(IllegalStateException.class, () -> DepartmentScope.own().managedDepartments());
        assertThrows(IllegalStateException.class, () -> DepartmentScope.all().primaryDepartment());
        assertThrows(IllegalStateException.class, () -> DepartmentScope.own().primaryDepartment());
    }

    @Test
    @DisplayName("Equality compares the whole grant set: the same grants in any order are equal, different grants are not")
    void equalityComparesTheGrantSet() {
        Department home = dept("HOME");
        Department a = dept("A");
        Department b = dept("B");
        DepartmentScope none = DepartmentScope.department(home);
        DepartmentScope ab = DepartmentScope.department(home).withViewOnly(List.of(a, b));
        DepartmentScope ba = DepartmentScope.department(dept("HOME")).withViewOnly(List.of(dept("B"), dept("A")));
        DepartmentScope onlyA = DepartmentScope.department(home).withViewOnly(List.of(a));

        assertEquals(ab, ba, "same primary department and same grants, listed in another order");
        assertEquals(ab.hashCode(), ba.hashCode());
        assertNotEquals(none, ab, "grants are part of a scope's identity");
        assertNotEquals(ab, none);
        assertNotEquals(onlyA, ab);
        assertNotEquals(DepartmentScope.department(dept("OTHER")).withViewOnly(List.of(a, b)), ab, "a different primary department");
        assertEquals(none, DepartmentScope.department(dept("HOME")), "without grants: equal by primary department, as before");
        assertEquals(none.hashCode(), DepartmentScope.department(dept("HOME")).hashCode());
        assertEquals(DepartmentScope.all(), DepartmentScope.all());
        assertNotEquals(DepartmentScope.all(), DepartmentScope.own());
        assertNotEquals(DepartmentScope.all(), none);
    }
}
