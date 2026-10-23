package com.hotel.workload.staff;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Makes sure the six built-in departments exist outside production: in tests (H2, created empty) and in a
 * development database that Hibernate has just given a departments table.
 *
 * <p>It only ever inserts a built-in whose code is missing. It never updates, renames or deletes anything,
 * so a department edited by a user is left exactly as it is. Production schemas are managed by Flyway V4,
 * which creates and seeds the table, so this does not run there.
 */
@Component
@Order(0) // before DatabaseMigrationRunner and DemoDataSeeder
@Profile("!prod & !production")
public class DepartmentBootstrap implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(DepartmentBootstrap.class);

    private final DepartmentRepository departmentRepository;

    public DepartmentBootstrap(DepartmentRepository departmentRepository) {
        this.departmentRepository = departmentRepository;
    }

    @Override
    public void run(String... args) {
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
            logger.info("Created {} built-in department(s) that were missing.", created);
        }
    }
}
