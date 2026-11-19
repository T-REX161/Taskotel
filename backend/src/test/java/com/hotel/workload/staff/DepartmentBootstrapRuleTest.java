package com.hotel.workload.staff;

import com.hotel.workload.security.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * When {@link DepartmentBootstrap} may create the starting departments. The rule that keeps a removed department removed after every
 * restart: seed only a brand-new database, never re-create what an Admin deleted. (The end-to-end restart on a real PostgreSQL database
 * is checked separately; this pins the decision itself for every combination of what the database already holds.)
 */
public class DepartmentBootstrapRuleTest {

    private final DepartmentRepository departments = mock(DepartmentRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final StaffRepository staff = mock(StaffRepository.class);
    private final DepartmentBootstrap bootstrap = new DepartmentBootstrap(departments, users, staff);

    private void database(long departmentCount, long userCount, long staffCount) {
        when(departments.count()).thenReturn(departmentCount);
        when(users.count()).thenReturn(userCount);
        when(staff.count()).thenReturn(staffCount);
        when(departments.existsByCode(anyString())).thenReturn(false);
    }

    @Test
    @DisplayName("A brand-new database (no departments, no accounts) gets all six starting departments")
    public void freshDatabaseIsSeeded() {
        database(0, 0, 0);
        assertTrue(bootstrap.isBrandNewDatabase());
        bootstrap.run();
        ArgumentCaptor<Department> saved = ArgumentCaptor.forClass(Department.class);
        verify(departments, times(DepartmentCatalog.BUILT_IN.size())).save(saved.capture());
        for (int i = 0; i < DepartmentCatalog.BUILT_IN.size(); i++) {
            assertEquals(DepartmentCatalog.BUILT_IN.get(i).code(), saved.getAllValues().get(i).getCode());
        }
    }

    @Test
    @DisplayName("An older development database that has staff but no departments table content is seeded, so the staff rows have their departments")
    public void legacyDatabaseWithStaffIsSeeded() {
        database(0, 5, 3);
        assertTrue(bootstrap.isBrandNewDatabase());
        bootstrap.run();
        verify(departments, times(DepartmentCatalog.BUILT_IN.size())).save(any(Department.class));
    }

    @Test
    @DisplayName("Departments exist (even only some of the six): nothing is created, so a removed built-in is never brought back")
    public void removedBuiltInIsNotRecreated() {
        database(5, 3, 2); // an Admin removed SECURITY
        assertFalse(bootstrap.isBrandNewDatabase());
        bootstrap.run();
        verify(departments, never()).save(any(Department.class));
        verify(departments, never()).existsByCode(anyString());
    }

    @Test
    @DisplayName("Every department was removed on purpose (accounts exist, no staff): nothing is re-created after a restart")
    public void removingEveryDepartmentIsRespected() {
        database(0, 1, 0);
        assertFalse(bootstrap.isBrandNewDatabase());
        bootstrap.run();
        verify(departments, never()).save(any(Department.class));
    }
}
