package com.hotel.workload.staff;

import java.util.List;
import java.util.UUID;

/**
 * Test fixtures for the six built-in departments, which used to be enum constants.
 *
 * <p>Each constant carries a fixed id, so Hibernate treats it as an existing (detached) department
 * and links to it by code. The real rows come from {@link DepartmentBootstrap} in the H2 test database,
 * so these can be assigned to staff and tasks that are saved in integration tests, and also used as
 * plain objects in unit tests.
 */
public final class TestDepartments {

    public static final Department HOUSEKEEPING = builtIn("HOUSEKEEPING");
    public static final Department FRONT_DESK = builtIn("FRONT_DESK");
    public static final Department MAINTENANCE = builtIn("MAINTENANCE");
    public static final Department ROOM_SERVICE = builtIn("ROOM_SERVICE");
    public static final Department FOOD_AND_BEVERAGE = builtIn("FOOD_AND_BEVERAGE");
    public static final Department SECURITY = builtIn("SECURITY");

    public static final List<Department> ALL =
            List.of(HOUSEKEEPING, FRONT_DESK, MAINTENANCE, ROOM_SERVICE, FOOD_AND_BEVERAGE, SECURITY);

    private TestDepartments() {}

    /** The built-in department with this code. */
    public static Department of(String code) {
        return ALL.stream()
                .filter(department -> department.getCode().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Not a built-in department: " + code));
    }

    private static Department builtIn(String code) {
        DepartmentCatalog.BuiltIn definition = DepartmentCatalog.BUILT_IN.stream()
                .filter(builtIn -> builtIn.code().equals(code))
                .findFirst()
                .orElseThrow();
        Department department = new Department(definition.code(), definition.name());
        department.setId(UUID.nameUUIDFromBytes(("test-department-" + code).getBytes()));
        department.setIconKey(definition.iconKey());
        department.setColorKey(definition.colorKey());
        department.setActive(true);
        return department;
    }
}
