package com.hotel.workload.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Order(1) // Run before DemoDataSeeder
@Profile("!prod & !production")
public class DatabaseMigrationRunner implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseMigrationRunner.class);
    private final JdbcTemplate jdbcTemplate;

    public DatabaseMigrationRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) {
        logger.info("Starting database migration/backfill for credentials_setup_required...");
        
        try {
            // 1. Backfill ADMIN and MANAGER to false
            int adminManagerUpdated = jdbcTemplate.update(
                "UPDATE users SET credentials_setup_required = false WHERE role IN ('ADMIN', 'MANAGER') AND credentials_setup_required IS NULL"
            );
            
            // 2. Backfill STAFF who have completed email verification to false
            int staffVerifiedUpdated = jdbcTemplate.update(
                "UPDATE users SET credentials_setup_required = false WHERE role = 'STAFF' AND email_verified = true AND credentials_setup_required IS NULL"
            );
            
            // 3. Backfill STAFF who are unverified to true
            int staffUnverifiedUpdated = jdbcTemplate.update(
                "UPDATE users SET credentials_setup_required = true WHERE role = 'STAFF' AND email_verified = false AND credentials_setup_required IS NULL"
            );
            
            logger.info("Backfill complete. Updated rows - Admin/Manager: {}, Staff(Verified): {}, Staff(Unverified): {}", 
                adminManagerUpdated, staffVerifiedUpdated, staffUnverifiedUpdated);

            // 4. Ensure the column has DEFAULT FALSE and NOT NULL
            jdbcTemplate.execute("ALTER TABLE users ALTER COLUMN credentials_setup_required SET DEFAULT false");
            jdbcTemplate.execute("ALTER TABLE users ALTER COLUMN credentials_setup_required SET NOT NULL");
            logger.info("Successfully applied NOT NULL and DEFAULT FALSE constraints to credentials_setup_required.");
        } catch (Exception e) {
            logger.warn("Could not apply NOT NULL constraint (expected if already applied or running in H2): {}", e.getMessage());
        }
    }
}
