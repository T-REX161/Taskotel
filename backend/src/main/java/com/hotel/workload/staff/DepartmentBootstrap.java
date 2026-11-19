package com.hotel.workload.staff;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.hotel.workload.security.UserRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Gives a brand-new database outside production its six starting departments: in tests (H2, created empty) and in a
 * development database that Hibernate has just given a departments table.
 *
 * <p>It seeds only when there is nothing to preserve, so a department an Admin removed (built-in or not) stays removed after every
 * restart. It never updates, renames or deletes anything. The database counts as brand new when the departments table is empty AND
 * either no account exists yet (a fresh install) or staff already exist (an older development database that predates departments,
 * whose staff rows need their department rows). An empty departments table next to accounts but no staff means an Admin removed
 * every department on purpose, and nothing is re-created. Production schemas are managed by Flyway V4, which seeded the table once,
 * so this does not run there.
 *
 * <p>Startup order: this runs first (`@Order(0)`), then DatabaseMigrationRunner, then DemoDataSeeder, so the "no account exists yet" test sees the
 * database BEFORE demo accounts are created. Deliberate edge cases (all covered by tests):
 * <ul>
 *   <li>brand-new database (no departments, no accounts): the six departments are created, then demo data may follow;</li>
 *   <li>some departments removed: nothing is created, so a removed built-in stays removed;</li>
 *   <li>every department removed, accounts exist, no staff: nothing is created (the Admin did that on purpose);</li>
 *   <li>an older development database with staff but no departments: the six are created, so those staff rows have their departments;</li>
 *   <li>no departments and no accounts after a manual wipe: treated as brand new and seeded again.</li>
 * </ul>
 * Demo data (local only, requires app.demo-data.enabled and an empty users table) needs the six starting departments; on a database with
 * departments missing it fails loudly with "Built-in department missing" instead of re-creating anything.
 */
@Component
@Order(0) // before DatabaseMigrationRunner and DemoDataSeeder
@Profile("!prod & !production")
public class DepartmentBootstrap implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(DepartmentBootstrap.class);

    private final DepartmentRepository departmentRepository;
    private final UserRepository userRepository;
    private final StaffRepository staffRepository;

    public DepartmentBootstrap(DepartmentRepository departmentRepository, UserRepository userRepository, StaffRepository staffRepository) {
        this.departmentRepository = departmentRepository;
        this.userRepository = userRepository;
        this.staffRepository = staffRepository;
    }

    /** True only for a database that has never had its starting departments (see the class description). */
    boolean isBrandNewDatabase() {
        if (departmentRepository.count() > 0) {
            return false;
        }
        return userRepository.count() == 0 || staffRepository.count() > 0;
    }

    @Override
    public void run(String... args) {
        if (!isBrandNewDatabase()) {
            return;
        }
        LocalDateTime base = LocalDateTime.now();
        List<DepartmentCatalog.BuiltIn> builtIns = DepartmentCatalog.BUILT_IN;
        int created = 0;
        for (int i = 0; i < builtIns.size(); i++) {
            DepartmentCatalog.BuiltIn builtIn = builtIns.get(i);
            if (departmentRepository.existsByCode(builtIn.code())) {
                continue;
            }
            Department department = new Department(builtIn.code(), builtIn.name());
            department.setIconKey(builtIn.iconKey());
            department.setColorKey(builtIn.colorKey());
            department.setActive(true);
            // Staggered by a millisecond so the original order is preserved when listing by creation time
            department.setCreatedAt(base.plusNanos(i * 1_000_000L));
            departmentRepository.save(department);
            created++;
        }
        if (created > 0) {
            logger.info("Created the {} starting departments of a new database.", created);
        }
    }
}
