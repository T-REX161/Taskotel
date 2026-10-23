package com.hotel.workload.migration;

import com.hotel.workload.staff.DepartmentCatalog;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rehearsal of Flyway V4 (departments table) on PostgreSQL. Never part of a plain {@code mvn test};
 * run only after approval with {@code mvn -Pmigration-it test -Dtest=DepartmentMigrationIT}.
 *
 * <p>Targets only rehearsal databases named {@code wb_rehearsal_<WB_MIGRATION_TEST_STAMP>_<role>} and applies the
 * same name guard as {@link PostgresMigrationIT}: the live {@code workload_balancer} database and every protected
 * database are refused before any statement runs.
 *
 * <ul>
 *   <li>{@code _copy}: a restore of the verified backup of the real database (pre-Flyway). Migrated to V4, checked,
 *       rolled back to its exact original schema, re-applied, and <b>left at V4</b> for {@code DepartmentApplicationIT}.</li>
 *   <li>{@code _fresh}: empty database; V1 to V4, rollback, re-apply.</li>
 *   <li>{@code _collide}: empty database for the cases where V4 must refuse to run.</li>
 * </ul>
 */
@Tag("pg-migration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class DepartmentMigrationIT {

    private static final Path FINGERPRINT_SQL = Path.of("db-tools/fingerprint.sql");
    private static final Path ROLLBACK_EXISTING = Path.of("db-tools/rollback/V4_existing.sql");
    private static final Path ROLLBACK_FRESH = Path.of("db-tools/rollback/V4_fresh.sql");
    private static final String V4_MARKER = "Created by Flyway V4: departments table (replaces the department enum)";
    private static final List<String> SIX_CODES = DepartmentCatalog.BUILT_IN.stream().map(DepartmentCatalog.BuiltIn::code).toList();

    private record Target(String database, String url, String user, String password) { }

    /** Schema of the _copy database before anything was applied, captured by the first test (tests run in order). */
    private static Set<String> originalCopySchema;

    /** V3 is applied together with V4 on the baselined copy; its one constraint and index are not part of this comparison. */
    private static boolean isV3Object(String entry) {
        return entry.contains("uk_assignments_task_id");
    }

    // ── Connection and safety guard (same rules as PostgresMigrationIT) ─────

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            fail(name + " must be set to run the PostgreSQL migration rehearsal tests");
        }
        return value;
    }

    private static Target target(String role) throws SQLException {
        String database = "wb_rehearsal_" + requiredEnv("WB_MIGRATION_TEST_STAMP") + "_" + role;
        PostgresMigrationIT.assertRehearsalDatabaseName(database);
        String url = "jdbc:postgresql://" + env("DB_HOST", "localhost") + ":" + env("DB_PORT", "5432") + "/" + database;
        Target target = new Target(database, url, env("DB_USER", "postgres"), requiredEnv("DB_PASSWORD"));
        // First statement on the connection: prove where we are before anything else runs
        String connectedTo = queryString(target, "SELECT current_database()");
        assertNotEquals("workload_balancer", connectedTo, "Refusing to run against the live database");
        assertEquals(database, connectedTo);
        PostgresMigrationIT.assertRehearsalDatabaseName(connectedTo);
        return target;
    }

    private static Connection connect(Target target) throws SQLException {
        return DriverManager.getConnection(target.url(), target.user(), target.password());
    }

    private static Flyway flyway(Target target, boolean baselineOnMigrate, String targetVersion) {
        var configuration = Flyway.configure()
                .dataSource(target.url(), target.user(), target.password())
                .locations("classpath:db/migration")
                .baselineOnMigrate(baselineOnMigrate)
                .baselineVersion("2")
                .cleanDisabled(true);
        if (targetVersion != null) {
            configuration = configuration.target(targetVersion);
        }
        return configuration.load();
    }

    // ── SQL helpers ─────────────────────────────────────────────────────────

    private static String queryString(Target target, String sql) throws SQLException {
        try (Connection c = connect(target); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static List<String> queryStrings(Target target, String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection c = connect(target); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                rows.add(rs.getString(1));
            }
        }
        return rows;
    }

    private static void execute(Target target, String sql) throws SQLException {
        try (Connection c = connect(target); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static String fingerprint(Target target) throws Exception {
        return queryString(target, Files.readString(FINGERPRINT_SQL));
    }

    private static boolean tableExists(Target target, String table) throws SQLException {
        return queryString(target, "SELECT to_regclass('public." + table + "')::text") != null;
    }

    private static List<String> history(Target target) throws SQLException {
        return queryStrings(target, "SELECT coalesce(version, '-') || ':' || type || ':' || success "
                + "FROM public.flyway_schema_history ORDER BY installed_rank");
    }

    /** Tables, columns, constraints and indexes in public, excluding Flyway's own history table. */
    private static Set<String> schemaSnapshot(Target target) throws SQLException {
        Set<String> snapshot = new HashSet<>();
        snapshot.addAll(queryStrings(target,
                "SELECT 'column ' || table_name || '.' || column_name || ' ' || data_type || ' nullable=' || is_nullable "
                        + "|| ' default=' || coalesce(column_default, '') FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history'"));
        snapshot.addAll(queryStrings(target,
                "SELECT 'constraint ' || conrelid::regclass::text || ' ' || conname || ' ' || contype::text || ' ' || pg_get_constraintdef(oid) "
                        + "|| ' on_delete=' || confdeltype::text FROM pg_constraint WHERE connamespace = 'public'::regnamespace "
                        + "AND conrelid <> coalesce(to_regclass('public.flyway_schema_history')::oid, 0::oid)"));
        snapshot.addAll(queryStrings(target,
                "SELECT 'index ' || indexdef FROM pg_indexes WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'"));
        return snapshot;
    }

    /** "CODE:count" for every distinct department code on staff and on tasks. */
    private static List<String> usageByDepartment(Target target) throws SQLException {
        List<String> rows = new ArrayList<>();
        rows.addAll(queryStrings(target, "SELECT 'staff ' || coalesce(department, '<null>') || ':' || count(*) FROM staff GROUP BY department ORDER BY 1"));
        rows.addAll(queryStrings(target, "SELECT 'tasks ' || coalesce(department, '<null>') || ':' || count(*) FROM tasks GROUP BY department ORDER BY 1"));
        return rows;
    }

    /** The same counts, reached through the new foreign key and the departments table. */
    private static List<String> usageThroughDepartments(Target target) throws SQLException {
        List<String> rows = new ArrayList<>();
        rows.addAll(queryStrings(target, "SELECT 'staff ' || d.code || ':' || count(*) FROM staff s JOIN departments d ON d.code = s.department GROUP BY d.code ORDER BY 1"));
        rows.addAll(queryStrings(target, "SELECT 'tasks ' || d.code || ':' || count(*) FROM tasks t JOIN departments d ON d.code = t.department GROUP BY d.code ORDER BY 1"));
        return rows;
    }

    private static String messages(Throwable error) {
        StringBuilder all = new StringBuilder();
        for (Throwable t = error; t != null; t = t.getCause()) {
            all.append(t.getMessage()).append('\n');
        }
        return all.toString();
    }

    private static Set<String> minus(Set<String> a, Set<String> b) {
        Set<String> result = new HashSet<>(a);
        result.removeAll(b);
        return result;
    }

    /** Asserts the structure V4 is meant to leave behind. */
    private static void assertV4Structure(Target t) throws SQLException {
        assertEquals(V4_MARKER, queryString(t, "SELECT obj_description('public.departments'::regclass, 'pg_class')"));
        assertEquals("uuid", queryString(t, "SELECT data_type FROM information_schema.columns WHERE table_name = 'departments' AND column_name = 'id'"));
        assertEquals("UNIQUE (code)", queryString(t, "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'uk_departments_code'"));
        assertTrue(queryString(t, "SELECT indexdef FROM pg_indexes WHERE indexname = 'uk_departments_name_lower'").contains("lower((name)::text)"));
        for (String fk : List.of("fk_staff_department_code", "fk_tasks_department_code", "fk_departments_head_staff")) {
            assertEquals("true", queryString(t, "SELECT convalidated::text FROM pg_constraint WHERE conname = '" + fk + "' AND contype = 'f'"), fk + " must exist and be validated");
        }
        assertTrue(queryString(t, "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'fk_departments_head_staff'").contains("ON DELETE SET NULL"));
        assertEquals("0", queryString(t, "SELECT count(*) FROM pg_constraint WHERE contype = 'c' AND conrelid IN ('staff'::regclass, 'tasks'::regclass) "
                + "AND pg_get_constraintdef(oid) ILIKE '%department%'"), "The enum CHECK constraints must be gone");
        assertEquals("2", queryString(t, "SELECT count(*) FROM pg_indexes WHERE indexname IN ('idx_staff_department', 'idx_tasks_department')"));
    }

    private static void assertSixSeeded(Target t) throws SQLException {
        List<String> rows = queryStrings(t, "SELECT code || '|' || name || '|' || coalesce(description, '<null>') || '|' || icon_key || '|' || color_key || '|' || active "
                + "|| '|' || (head_staff_id IS NULL) FROM departments ORDER BY created_at, name");
        List<String> expected = DepartmentCatalog.BUILT_IN.stream()
                .map(b -> b.code() + "|" + b.name() + "|<null>|" + b.iconKey() + "|" + b.colorKey() + "|true|true").toList();
        assertEquals(expected, rows, "The six built-in departments, in their original order");
        assertEquals("6", queryString(t, "SELECT count(DISTINCT id) FROM departments"), "Six distinct UUIDs");
        assertEquals("true", queryString(t, "SELECT (min(created_at) < max(created_at))::text FROM departments"), "Creation times advance so the order is kept");
    }

    // ── 1. The real database copy ───────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("V4 on a copy of the real database: all rows unchanged, department mappings preserved, enum CHECKs replaced by foreign keys")
    public void v4OnTheCopyPreservesEverything() throws Exception {
        Target copy = target("copy");
        assertFalse(tableExists(copy, "flyway_schema_history"), "The _copy database must be a pre-Flyway restore of the backup");
        assertFalse(tableExists(copy, "departments"));

        Set<String> schemaBefore = schemaSnapshot(copy);
        originalCopySchema = schemaBefore;
        String dataBefore = fingerprint(copy);
        List<String> usageBefore = usageByDepartment(copy);
        System.out.println("BEFORE fingerprint:\n" + dataBefore);
        System.out.println("BEFORE department usage: " + usageBefore);
        assertTrue(schemaBefore.stream().anyMatch(e -> e.contains("staff_department_check")), "The copy has the Hibernate enum CHECK on staff");
        assertTrue(schemaBefore.stream().anyMatch(e -> e.contains("tasks_department_check")), "The copy has the Hibernate enum CHECK on tasks");

        // Production settings never baseline automatically
        FlywayException refused = assertThrows(FlywayException.class, () -> flyway(copy, false, null).migrate());
        assertTrue(messages(refused).toLowerCase().contains("baseline"), messages(refused));
        assertEquals(schemaBefore, schemaSnapshot(copy), "A refused start must change nothing");

        // The real go-live path: baseline at 2, then V3 and V4 run
        MigrateResult result = flyway(copy, true, null).migrate();
        System.out.println("MIGRATED: " + result.migrationsExecuted + " migration(s); history " + history(copy));
        assertEquals(2, result.migrationsExecuted, "V3 and V4 run on a baselined existing database");
        assertEquals(List.of("2:BASELINE:true", "3:SQL:true", "4:SQL:true"), history(copy));

        // No application row changed
        assertEquals(dataBefore, fingerprint(copy), "V4 must not change any assignment, staff, task or user row");
        assertEquals(usageBefore, usageByDepartment(copy), "Every staff and task still has the same department code");

        // The departments are there, with the same mapping
        assertSixSeeded(copy);
        assertEquals(usageBefore, usageThroughDepartments(copy), "Every staff and task resolves to a department row, with the same counts");
        assertEquals("0", queryString(copy, "SELECT count(*) FROM staff s LEFT JOIN departments d ON d.code = s.department WHERE d.id IS NULL"));
        assertEquals("0", queryString(copy, "SELECT count(*) FROM tasks t LEFT JOIN departments d ON d.code = t.department WHERE t.department IS NOT NULL AND d.id IS NULL"));
        assertV4Structure(copy);

        // Exactly what changed in the schema
        Set<String> schemaAfter = schemaSnapshot(copy);
        Set<String> removed = minus(schemaBefore, schemaAfter);
        Set<String> added = minus(schemaAfter, schemaBefore);
        added.removeIf(DepartmentMigrationIT::isV3Object);
        System.out.println("SCHEMA REMOVED: " + removed);
        assertEquals(2, removed.size(), "Only the two enum CHECK constraints are removed: " + removed);
        assertTrue(removed.stream().anyMatch(e -> e.contains("staff_department_check")) && removed.stream().anyMatch(e -> e.contains("tasks_department_check")));
        assertFalse(added.stream().anyMatch(e -> e.startsWith("column staff.") || e.startsWith("column tasks.")),
                "No column of staff or tasks is added or altered: " + added);
        assertTrue(added.stream().allMatch(e -> e.contains("departments") || e.contains("fk_staff_department_code")
                || e.contains("fk_tasks_department_code") || e.contains("idx_staff_department") || e.contains("idx_tasks_department")), added.toString());
        System.out.println("SCHEMA ADDED (" + added.size() + " entries): " + added.stream().sorted().collect(Collectors.joining("\n  ", "\n  ", "")));

        // Rolling Flyway again does nothing
        assertEquals(0, flyway(copy, true, null).migrate().migrationsExecuted);
    }

    @Test
    @Order(2)
    @DisplayName("A new department works at the database level, and the constraints reject bad data (nothing is kept: the transaction rolls back)")
    public void newDepartmentsAndConstraintsOnTheCopy() throws Exception {
        Target copy = target("copy");
        String dataBefore = fingerprint(copy);
        long departmentsBefore = Long.parseLong(queryString(copy, "SELECT count(*) FROM departments"));

        try (Connection c = connect(copy)) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                // Create a department, and staff and a task in it
                s.execute("INSERT INTO departments (id, code, name, description, icon_key, color_key, active, created_at) "
                        + "VALUES (gen_random_uuid(), 'SPA_AND_WELLNESS', 'Spa & Wellness', 'Treatments', 'users', 'rose', TRUE, now())");
                s.execute("INSERT INTO users (id, username, email, password, role, active, email_verified, first_login, created_at, credentials_setup_required) "
                        + "VALUES ('00000000-0000-0000-0000-0000000000a1', 'rehearsal_spa', 'rehearsal_spa@example.invalid', 'x', 'STAFF', TRUE, FALSE, TRUE, now(), FALSE)");
                s.execute("INSERT INTO staff (id, user_id, name, email, department, availability, active, created_at) "
                        + "VALUES ('00000000-0000-0000-0000-0000000000b1', '00000000-0000-0000-0000-0000000000a1', 'Rehearsal Spa', 'rehearsal_spa@example.invalid', 'SPA_AND_WELLNESS', 'AVAILABLE', TRUE, now())");
                s.execute("INSERT INTO tasks (id, title, priority, status, department, created_by_id, created_at) "
                        + "SELECT '00000000-0000-0000-0000-0000000000c1', 'Rehearsal spa task', 'LOW', 'PENDING', 'SPA_AND_WELLNESS', id, now() FROM users LIMIT 1");
                assertEquals(1, countWhere(s, "staff", "department = 'SPA_AND_WELLNESS'"));
                assertEquals(1, countWhere(s, "tasks", "department = 'SPA_AND_WELLNESS'"));

                // A department head, and what happens when that staff member is deleted (ON DELETE SET NULL)
                s.execute("UPDATE departments SET head_staff_id = '00000000-0000-0000-0000-0000000000b1' WHERE code = 'SPA_AND_WELLNESS'");
                s.execute("DELETE FROM staff WHERE id = '00000000-0000-0000-0000-0000000000b1'");
                try (ResultSet rs = s.executeQuery("SELECT head_staff_id IS NULL FROM departments WHERE code = 'SPA_AND_WELLNESS'")) {
                    assertTrue(rs.next() && rs.getBoolean(1), "Deleting the head clears the reference");
                }

                // Bad data is rejected, each in its own savepoint so the transaction stays usable
                expectViolation(c, "23503", "INSERT INTO staff (id, user_id, name, email, department, availability, active, created_at) "
                        + "VALUES (gen_random_uuid(), '00000000-0000-0000-0000-0000000000a1', 'X', 'x@example.invalid', 'NO_SUCH_DEPARTMENT', 'AVAILABLE', TRUE, now())");
                expectViolation(c, "23503", "UPDATE tasks SET department = 'NO_SUCH_DEPARTMENT' WHERE id = '00000000-0000-0000-0000-0000000000c1'");
                expectViolation(c, "23505", "INSERT INTO departments (id, code, name, active, created_at) VALUES (gen_random_uuid(), 'SPA_AND_WELLNESS', 'Another name', TRUE, now())");
                expectViolation(c, "23505", "INSERT INTO departments (id, code, name, active, created_at) VALUES (gen_random_uuid(), 'SPA_2', 'spa & WELLNESS', TRUE, now())");
                expectViolation(c, "23505", "INSERT INTO departments (id, code, name, active, created_at) VALUES (gen_random_uuid(), 'HK_2', 'housekeeping', TRUE, now())");
                expectViolation(c, "23503", "DELETE FROM departments WHERE code = 'SPA_AND_WELLNESS'"); // still has a task
            }
            c.rollback();
        }

        assertEquals(departmentsBefore, Long.parseLong(queryString(copy, "SELECT count(*) FROM departments")), "The rehearsal transaction was rolled back");
        assertEquals(dataBefore, fingerprint(copy), "No row was kept");
    }

    private static int countWhere(Statement s, String table, String where) throws SQLException {
        try (ResultSet rs = s.executeQuery("SELECT count(*) FROM " + table + " WHERE " + where)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static void expectViolation(Connection c, String sqlState, String sql) throws SQLException {
        Savepoint savepoint = c.setSavepoint();
        try (Statement s = c.createStatement()) {
            s.execute(sql);
            fail("Expected SQLSTATE " + sqlState + " but the statement succeeded: " + sql);
        } catch (SQLException e) {
            assertEquals(sqlState, e.getSQLState(), e.getMessage());
        } finally {
            c.rollback(savepoint);
        }
    }

    @Test
    @Order(3)
    @DisplayName("Rollback refuses to run if a department was added or edited after V4, and loses nothing")
    public void rollbackRefusesWhenItWouldLoseData() throws Exception {
        Target copy = target("copy");
        Set<String> schemaV4 = schemaSnapshot(copy);
        String data = fingerprint(copy);

        // A department added after V4
        execute(copy, "INSERT INTO departments (id, code, name, active, created_at) VALUES (gen_random_uuid(), 'TEMP_REHEARSAL', 'Temp rehearsal', TRUE, now())");
        SQLException added = assertThrows(SQLException.class, () -> execute(copy, Files.readString(ROLLBACK_EXISTING)));
        assertTrue(added.getMessage().contains("not exactly the six original departments"), added.getMessage());
        execute(copy, "DELETE FROM departments WHERE code = 'TEMP_REHEARSAL'");
        assertEquals(List.of("2:BASELINE:true", "3:SQL:true", "4:SQL:true"), history(copy), "A refused rollback keeps the history");

        // An original department edited after V4
        execute(copy, "UPDATE departments SET description = 'edited' WHERE code = 'SECURITY'");
        SQLException edited = assertThrows(SQLException.class, () -> execute(copy, Files.readString(ROLLBACK_EXISTING)));
        assertTrue(edited.getMessage().contains("were edited after V4"), edited.getMessage());
        execute(copy, "UPDATE departments SET description = NULL WHERE code = 'SECURITY'");

        // The fresh-install rollback refuses a baselined database
        SQLException wrongScript = assertThrows(SQLException.class, () -> execute(copy, Files.readString(ROLLBACK_FRESH)));
        assertTrue(wrongScript.getMessage().contains("V4_existing.sql"), wrongScript.getMessage());

        assertEquals(schemaV4, schemaSnapshot(copy), "Refused rollbacks change nothing");
        assertEquals(data, fingerprint(copy));
        assertSixSeeded(copy);
    }

    @Test
    @Order(4)
    @DisplayName("V4_existing.sql restores the exact pre-V4 schema, V4 re-applies identically, and the copy is left at V4")
    public void rollbackRestoresTheExactSchemaAndV4Reapplies() throws Exception {
        Target copy = target("copy");
        Set<String> schemaV4 = schemaSnapshot(copy);
        String data = fingerprint(copy);
        List<String> usage = usageByDepartment(copy);

        // Roll back V4 only (V3 and the baseline stay)
        execute(copy, Files.readString(ROLLBACK_EXISTING));
        assertEquals(List.of("2:BASELINE:true", "3:SQL:true"), history(copy));
        assertFalse(tableExists(copy, "departments"));
        assertEquals(data, fingerprint(copy), "Rollback must not change any row");
        assertEquals(usage, usageByDepartment(copy));
        Set<String> schemaAfterRollback = schemaSnapshot(copy);
        Set<String> gone = minus(schemaV4, schemaAfterRollback);
        Set<String> back = minus(schemaAfterRollback, schemaV4);
        System.out.println("ROLLBACK removed " + gone.size() + " V4 entries and restored: " + back);
        assertEquals(2, back.size(), "Only the two enum CHECK constraints come back: " + back);
        assertTrue(back.stream().anyMatch(e -> e.contains("staff_department_check")) && back.stream().anyMatch(e -> e.contains("tasks_department_check")));
        assertTrue(gone.stream().allMatch(e -> e.contains("departments") || e.contains("fk_staff_department_code")
                || e.contains("fk_tasks_department_code") || e.contains("idx_staff_department") || e.contains("idx_tasks_department")), gone.toString());

        // The strongest check: apart from V3's own constraint, the schema is exactly the original database's schema again,
        // so the two enum CHECK constraints came back with exactly the definitions Hibernate had generated.
        assertNotNull(originalCopySchema, "The first test captures the original schema");
        Set<String> withoutV3 = new HashSet<>(schemaAfterRollback);
        withoutV3.removeIf(DepartmentMigrationIT::isV3Object);
        assertEquals(originalCopySchema, withoutV3, "After rolling V4 back the schema must equal the original database schema");

        // Re-apply: identical result
        MigrateResult reapplied = flyway(copy, true, null).migrate();
        assertEquals(1, reapplied.migrationsExecuted, "Only V4 runs again");
        assertEquals(List.of("2:BASELINE:true", "3:SQL:true", "4:SQL:true"), history(copy));
        assertEquals(data, fingerprint(copy));
        assertEquals(usage, usageByDepartment(copy));
        assertSixSeeded(copy);
        assertV4Structure(copy);
        assertEquals(schemaV4, schemaSnapshot(copy), "Re-applied V4 must produce the same schema as the first run");
    }

    // ── 2. Fresh database ───────────────────────────────────────────────────

    @Test
    @Order(5)
    @DisplayName("Fresh database: V1 to V4 build the same departments structure, V4_fresh.sql rolls back to the V3 schema, V4 re-applies")
    public void freshDatabase() throws Exception {
        Target fresh = target("fresh");
        assertEquals("0", queryString(fresh, "SELECT count(*) FROM pg_class WHERE relnamespace = 'public'::regnamespace AND relkind IN ('r','p')"),
                "The _fresh database must start empty (recreate it before a second run)");

        MigrateResult upToV3 = flyway(fresh, true, "3").migrate();
        assertEquals(3, upToV3.migrationsExecuted);
        Set<String> schemaV3 = schemaSnapshot(fresh);

        MigrateResult v4 = flyway(fresh, true, null).migrate();
        assertEquals(1, v4.migrationsExecuted);
        assertEquals(List.of("1:SQL:true", "2:SQL:true", "3:SQL:true", "4:SQL:true"), history(fresh));
        assertSixSeeded(fresh);
        assertV4Structure(fresh);
        Set<String> schemaV4 = schemaSnapshot(fresh);

        // The wrong rollback script is refused; the right one restores the V3 schema exactly
        SQLException wrong = assertThrows(SQLException.class, () -> execute(fresh, Files.readString(ROLLBACK_EXISTING)));
        assertTrue(wrong.getMessage().contains("2:BASELINE"), wrong.getMessage());
        assertEquals(schemaV4, schemaSnapshot(fresh));

        execute(fresh, Files.readString(ROLLBACK_FRESH));
        assertEquals(List.of("1:SQL:true", "2:SQL:true", "3:SQL:true"), history(fresh));
        assertEquals(schemaV3, schemaSnapshot(fresh), "V4_fresh.sql must restore the exact post-V3 schema");

        assertEquals(1, flyway(fresh, true, null).migrate().migrationsExecuted);
        assertEquals(schemaV4, schemaSnapshot(fresh), "Re-applied V4 gives the same schema");
    }

    // ── 3. V4 refuses to run on unexpected states, atomically ───────────────

    @Test
    @Order(6)
    @DisplayName("V4 refuses to run, and changes nothing, when departments already exists, when a stored code is unknown, or when an unexpected CHECK mentions department")
    public void v4RefusesUnexpectedStates() throws Exception {
        Target collide = target("collide");
        assertEquals("0", queryString(collide, "SELECT count(*) FROM pg_class WHERE relnamespace = 'public'::regnamespace AND relkind IN ('r','p')"),
                "The _collide database must start empty (recreate it before a second run)");
        assertEquals(3, flyway(collide, true, "3").migrate().migrationsExecuted);
        Set<String> schemaV3 = schemaSnapshot(collide);

        // 1. A departments table already exists
        execute(collide, "CREATE TABLE departments (id int)");
        FlywayException exists = assertThrows(FlywayException.class, () -> flyway(collide, true, null).migrate());
        assertTrue(messages(exists).contains("departments already exists"), messages(exists));
        execute(collide, "DROP TABLE departments");
        assertEquals(schemaV3, schemaSnapshot(collide), "A refused V4 leaves the schema exactly as it was");
        assertEquals(List.of("1:SQL:true", "2:SQL:true", "3:SQL:true"), failedFreeHistory(collide));

        // 2. A staff row carries a code outside the six
        execute(collide, "INSERT INTO users (id, username, email, password, role, active, email_verified, first_login, created_at, credentials_setup_required) "
                + "VALUES ('00000000-0000-0000-0000-0000000000a2', 'collide_user', 'collide@example.invalid', 'x', 'STAFF', TRUE, FALSE, TRUE, now(), FALSE)");
        execute(collide, "INSERT INTO staff (id, user_id, name, email, department, availability, active, created_at) "
                + "VALUES ('00000000-0000-0000-0000-0000000000b2', '00000000-0000-0000-0000-0000000000a2', 'C', 'collide@example.invalid', 'SPA', 'AVAILABLE', TRUE, now())");
        FlywayException unknown = assertThrows(FlywayException.class, () -> flyway(collide, true, null).migrate());
        assertTrue(messages(unknown).contains("outside the six built-in departments"), messages(unknown));
        assertFalse(tableExists(collide, "departments"), "Nothing from V4 was kept");
        execute(collide, "DELETE FROM staff WHERE id = '00000000-0000-0000-0000-0000000000b2'");

        // 3. A CHECK constraint that mentions department but is not the six-value enum check
        execute(collide, "ALTER TABLE staff ADD CONSTRAINT staff_dept_not_enum CHECK (department <> 'X_FORBIDDEN')");
        FlywayException check = assertThrows(FlywayException.class, () -> flyway(collide, true, null).migrate());
        assertTrue(messages(check).contains("is not the six-value enum check"), messages(check));
        assertFalse(tableExists(collide, "departments"));
        execute(collide, "ALTER TABLE staff DROP CONSTRAINT staff_dept_not_enum");

        // With the obstacles removed V4 applies cleanly
        execute(collide, "DELETE FROM users WHERE id = '00000000-0000-0000-0000-0000000000a2'");
        assertEquals(schemaV3, schemaSnapshot(collide));
        assertEquals(1, flyway(collide, true, null).migrate().migrationsExecuted);
        assertSixSeeded(collide);
    }

    private static List<String> failedFreeHistory(Target t) throws SQLException {
        List<String> rows = history(t);
        assertTrue(rows.stream().noneMatch(r -> r.endsWith(":false")), "No failed migration entry: " + rows);
        return rows;
    }
}
