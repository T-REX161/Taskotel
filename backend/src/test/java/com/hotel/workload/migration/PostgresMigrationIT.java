package com.hotel.workload.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PostgreSQL migration rehearsal tests (M1-M8 of the P4a plan).
 *
 * <p><b>Never part of a plain {@code mvn test}</b>: surefire's default includes do not match *IT
 * classes, and the {@code pg-migration} tag is excluded. Run only after approval with
 * {@code mvn -Pmigration-it test}.
 *
 * <p>Targets only rehearsal databases named {@code wb_rehearsal_<WB_MIGRATION_TEST_STAMP>_<role>}
 * on the server given by DB_HOST / DB_PORT / DB_USER / DB_PASSWORD. Each test first connects and
 * checks {@code current_database()} against that pattern before running any other statement, and
 * refuses {@code workload_balancer}.
 *
 * <p>Rehearsal databases (created beforehand, with approval):
 * <ul>
 *   <li>{@code _fresh}: empty database (M1, M2)</li>
 *   <li>{@code _copy}: pg_restore of the verified backup (M3, M7, M8); left in its pre-Flyway state afterwards</li>
 *   <li>{@code _dupe}: pg_restore of the verified backup; this test inserts one duplicate assignment row (M4)</li>
 *   <li>{@code _collide}: empty database for name-collision cases (M5, M6)</li>
 * </ul>
 * {@code _fresh} and {@code _collide} must be recreated empty before a second run.
 */
@Tag("pg-migration")
public class PostgresMigrationIT {

    private static final Pattern REHEARSAL_DATABASE =
            Pattern.compile("^wb_rehearsal_\\d{8}_\\d{6}_(fresh|copy|dupe|collide)$");
    private static final String LIVE_DATABASE = "workload_balancer";
    /** Never a target, even if a name somehow matched REHEARSAL_DATABASE (same list as db-tools/RehearsalCommon.ps1). */
    private static final List<String> PROTECTED_DATABASES = List.of(
            "workload_balancer", "wb_restore_check_20261020_140921", "wb_restore_check_20261020_142245",
            "postgres", "template0", "template1");
    private static final List<String> PROTECTED_PREFIXES = List.of("wb_restore_check_", "wb_restored_");
    private static final String V3_MARKER = "Created by Flyway V3: one assignment row per task";
    /** These tests prove V3 on its own; V4 (departments) is proved by DepartmentMigrationIT, so Flyway stops at 3 here. */
    private static final String V3_TARGET = "3";
    private static final Path FINGERPRINT_SQL = Path.of("db-tools/fingerprint.sql");
    private static final Path ROLLBACK_EXISTING = Path.of("db-tools/rollback/V3_existing.sql");
    private static final Path ROLLBACK_FRESH = Path.of("db-tools/rollback/V3_fresh.sql");

    private record Target(String database, String url, String user, String password) { }

    // ── Connection and safety guard ──────────────────────────────────────────

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

    /** Throws unless the name is a rehearsal database name that is not protected. Checked before any connection. */
    static void assertRehearsalDatabaseName(String database) {
        for (String protectedName : PROTECTED_DATABASES) {
            assertFalse(protectedName.equalsIgnoreCase(database), "Refusing: '" + database + "' is a protected database");
        }
        for (String prefix : PROTECTED_PREFIXES) {
            assertFalse(database.toLowerCase(java.util.Locale.ROOT).startsWith(prefix), "Refusing: '" + database + "' is a protected database");
        }
        assertTrue(REHEARSAL_DATABASE.matcher(database).matches(),
                "Refusing: '" + database + "' is not a rehearsal database name (WB_MIGRATION_TEST_STAMP must be yyyyMMdd_HHmmss)");
    }

    private static Target target(String role) throws SQLException {
        String database = "wb_rehearsal_" + requiredEnv("WB_MIGRATION_TEST_STAMP") + "_" + role;
        assertRehearsalDatabaseName(database);
        String url = "jdbc:postgresql://" + env("DB_HOST", "localhost") + ":" + env("DB_PORT", "5432") + "/" + database;
        Target target = new Target(database, url, env("DB_USER", "postgres"), requiredEnv("DB_PASSWORD"));

        // First statement on the connection: prove where we are before anything else runs
        String connectedTo = queryString(target, "SELECT current_database()");
        assertNotEquals(LIVE_DATABASE, connectedTo, "Refusing to run against the live database");
        assertEquals(database, connectedTo, "Connected to an unexpected database");
        assertRehearsalDatabaseName(connectedTo);
        return target;
    }

    private static Connection connect(Target target) throws SQLException {
        return DriverManager.getConnection(target.url(), target.user(), target.password());
    }

    private static Flyway flyway(Target target, boolean baselineOnMigrate, String targetVersion) {
        FluentConfiguration configuration = Flyway.configure()
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

    // ── SQL helpers ──────────────────────────────────────────────────────────

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

    private static boolean historyTableExists(Target target) throws SQLException {
        return queryString(target, "SELECT to_regclass('public.flyway_schema_history')::text") != null;
    }

    private static List<String> history(Target target) throws SQLException {
        return queryStrings(target, "SELECT coalesce(version, '-') || ':' || type || ':' || success "
                + "FROM public.flyway_schema_history ORDER BY installed_rank");
    }

    private static int publicTableCount(Target target) throws SQLException {
        return Integer.parseInt(queryString(target,
                "SELECT count(*) FROM pg_class WHERE relnamespace = 'public'::regnamespace AND relkind IN ('r','p')"));
    }

    private static String constraintDefinition(Target target) throws SQLException {
        return queryString(target, "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                + "WHERE conname = 'uk_assignments_task_id' AND conrelid = 'public.assignments'::regclass");
    }

    private static String constraintComment(Target target) throws SQLException {
        return queryString(target, "SELECT obj_description(oid, 'pg_constraint') FROM pg_constraint "
                + "WHERE conname = 'uk_assignments_task_id' AND conrelid = 'public.assignments'::regclass");
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

    private static String messages(Throwable error) {
        StringBuilder all = new StringBuilder();
        for (Throwable t = error; t != null; t = t.getCause()) {
            all.append(t.getMessage()).append('\n');
        }
        return all.toString();
    }

    // ── M1, M2 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("M1/M2 + recovery: a fresh database runs V1-V3 once; V3_fresh.sql removes exactly V3, and V3 re-applies identically")
    public void freshDatabaseRunsAllMigrationsOnce() throws Exception {
        Target fresh = target("fresh");
        assertEquals(0, publicTableCount(fresh), "The _fresh rehearsal database must be empty");

        MigrateResult first = flyway(fresh, true, V3_TARGET).migrate();
        assertEquals(3, first.migrationsExecuted);
        assertEquals(List.of("1:SQL:true", "2:SQL:true", "3:SQL:true"), history(fresh));
        assertEquals("UNIQUE (task_id)", constraintDefinition(fresh));
        assertEquals(V3_MARKER, constraintComment(fresh));

        MigrateResult second = flyway(fresh, true, V3_TARGET).migrate();
        assertEquals(0, second.migrationsExecuted, "A second start must find nothing to migrate");

        // Recovery on a fresh install: the guarded rollback removes exactly V3 (constraint, its index, history row)
        Set<String> schemaWithV3 = schemaSnapshot(fresh);
        execute(fresh, Files.readString(ROLLBACK_FRESH));
        assertEquals(List.of("1:SQL:true", "2:SQL:true"), history(fresh), "Rollback must remove only the V3 history row");
        assertNull(constraintDefinition(fresh), "Rollback must remove the V3 constraint");
        Set<String> schemaAfterRollback = schemaSnapshot(fresh);
        Set<String> removedByRollback = new HashSet<>(schemaWithV3);
        removedByRollback.removeAll(schemaAfterRollback);
        Set<String> addedByRollback = new HashSet<>(schemaAfterRollback);
        addedByRollback.removeAll(schemaWithV3);
        assertTrue(addedByRollback.isEmpty(), "Rollback must not add anything: " + addedByRollback);
        assertEquals(2, removedByRollback.size(), "Rollback must remove exactly the constraint and its index: " + removedByRollback);
        assertTrue(removedByRollback.stream().allMatch(entry -> entry.contains("uk_assignments_task_id")), removedByRollback.toString());

        // ... and V3 can be applied again with an identical result
        MigrateResult reapplied = flyway(fresh, true, V3_TARGET).migrate();
        assertEquals(1, reapplied.migrationsExecuted, "Only V3 must run again");
        assertEquals(List.of("1:SQL:true", "2:SQL:true", "3:SQL:true"), history(fresh));
        assertEquals("UNIQUE (task_id)", constraintDefinition(fresh));
        assertEquals(V3_MARKER, constraintComment(fresh));
        assertEquals(schemaWithV3, schemaSnapshot(fresh), "Re-applied V3 must produce the same schema as the first run");
        assertEquals(0, flyway(fresh, true, V3_TARGET).migrate().migrationsExecuted);
    }

    // ── M7, M3, M8 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("M7/M3/M8 + recovery: a copy of live is refused without baseline, baselined at 2 with only V3, rolled back exactly, re-applied identically, and rolled back again")
    public void existingCopyIsBaselinedAndRolledBackExactly() throws Exception {
        Target copy = target("copy");
        assertFalse(historyTableExists(copy), "The _copy rehearsal database must be a pre-Flyway restore of the backup");
        Set<String> schemaBefore = schemaSnapshot(copy);
        String dataBefore = fingerprint(copy);

        // M7: production settings never baseline automatically
        FlywayException refused = assertThrows(FlywayException.class, () -> flyway(copy, false, V3_TARGET).migrate());
        assertTrue(messages(refused).toLowerCase().contains("baseline"), messages(refused));
        assertFalse(historyTableExists(copy), "A refused start must not create the history table");
        assertEquals(schemaBefore, schemaSnapshot(copy));

        // M3: baseline at 2, only V3 runs, data unchanged, only the constraint and its index added
        MigrateResult result = flyway(copy, true, V3_TARGET).migrate();
        assertEquals(1, result.migrationsExecuted);
        assertEquals(List.of("2:BASELINE:true", "3:SQL:true"), history(copy));
        assertEquals(dataBefore, fingerprint(copy), "V3 must not change any row");
        assertEquals(V3_MARKER, constraintComment(copy));

        Set<String> schemaAfter = schemaSnapshot(copy);
        Set<String> added = new HashSet<>(schemaAfter);
        added.removeAll(schemaBefore);
        Set<String> removed = new HashSet<>(schemaBefore);
        removed.removeAll(schemaAfter);
        assertTrue(removed.isEmpty(), "V3 must not remove or alter anything: " + removed);
        assertEquals(2, added.size(), "V3 must add exactly the constraint and its index: " + added);
        assertTrue(added.stream().allMatch(entry -> entry.contains("uk_assignments_task_id")), added.toString());

        // M8: the rollback script restores the exact pre-Flyway schema
        execute(copy, Files.readString(ROLLBACK_EXISTING));
        assertFalse(historyTableExists(copy));
        assertNull(constraintDefinition(copy));
        assertEquals(schemaBefore, schemaSnapshot(copy), "Rollback must restore the exact pre-V3 schema");
        assertEquals(dataBefore, fingerprint(copy));

        // Recovery on an existing install: after the rollback, V3 can be applied again with an identical result ...
        MigrateResult reapplied = flyway(copy, true, V3_TARGET).migrate();
        assertEquals(1, reapplied.migrationsExecuted, "Baseline and V3 must run again");
        assertEquals(List.of("2:BASELINE:true", "3:SQL:true"), history(copy));
        assertEquals(schemaAfter, schemaSnapshot(copy), "Re-applied V3 must produce the same schema as the first run");
        assertEquals(dataBefore, fingerprint(copy), "Re-applying V3 must not change any row");
        assertEquals(V3_MARKER, constraintComment(copy));

        // ... and rolled back again, leaving the copy in its exact pre-Flyway state
        execute(copy, Files.readString(ROLLBACK_EXISTING));
        assertFalse(historyTableExists(copy));
        assertNull(constraintDefinition(copy));
        assertEquals(schemaBefore, schemaSnapshot(copy), "The second rollback must again restore the exact pre-Flyway schema");
        assertEquals(dataBefore, fingerprint(copy));
    }

    // ── M4 ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("M4: duplicate assignments make V3 fail without changing the schema or data")
    public void duplicateAssignmentsAbortV3() throws Exception {
        Target dupe = target("dupe");
        if (historyTableExists(dupe)) {
            assertEquals(List.of("2:BASELINE:true"), history(dupe), "_dupe may only hold the baseline row from an earlier run");
        }
        assertTrue(Integer.parseInt(queryString(dupe, "SELECT count(*) FROM public.assignments")) > 0,
                "The _dupe rehearsal database must be a restore of the backup with assignments");

        // Rehearsal database only: duplicate one existing assignment row (new id, same task)
        String duplicates = "SELECT count(*) FROM (SELECT task_id FROM public.assignments GROUP BY task_id HAVING count(*) > 1) d";
        if ("0".equals(queryString(dupe, duplicates))) {
            execute(dupe, "INSERT INTO public.assignments (id, task_id, staff_id, assigned_at) "
                    + "SELECT gen_random_uuid(), task_id, staff_id, assigned_at FROM public.assignments ORDER BY id LIMIT 1");
        }
        Set<String> schemaBefore = schemaSnapshot(dupe);
        String dataBefore = fingerprint(dupe);

        FlywayException failure = assertThrows(FlywayException.class, () -> flyway(dupe, true, V3_TARGET).migrate());
        assertTrue(messages(failure).contains("more than one assignment row"), messages(failure));

        assertNull(constraintDefinition(dupe), "V3 must not leave the constraint behind");
        assertEquals(schemaBefore, schemaSnapshot(dupe));
        assertEquals(dataBefore, fingerprint(dupe));
        assertEquals(List.of("2:BASELINE:true"), history(dupe), "Only the baseline row may remain after V3 rolls back");

        // V3 never applied here, so the existing-install rollback must refuse and keep the baseline history
        assertThrows(SQLException.class, () -> execute(dupe, Files.readString(ROLLBACK_EXISTING)));
        assertTrue(historyTableExists(dupe), "A refused rollback must not drop flyway_schema_history");
        assertEquals(List.of("2:BASELINE:true"), history(dupe));
        assertEquals(schemaBefore, schemaSnapshot(dupe));
    }

    // ── M5, M6 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("M5/M6: name collisions stop V3; an identical existing constraint is accepted but not claimed")
    public void nameCollisionsAreRejected() throws Exception {
        Target collide = target("collide");
        assertEquals(0, publicTableCount(collide), "The _collide rehearsal database must be empty");
        assertEquals(2, flyway(collide, true, "2").migrate().migrationsExecuted);

        // M5a: an index already uses the name
        execute(collide, "CREATE INDEX uk_assignments_task_id ON public.assignments (staff_id)");
        FlywayException relation = assertThrows(FlywayException.class, () -> flyway(collide, true, V3_TARGET).migrate());
        assertTrue(messages(relation).contains("a relation named uk_assignments_task_id already exists"), messages(relation));
        assertEquals(List.of("1:SQL:true", "2:SQL:true"), history(collide));
        execute(collide, "DROP INDEX public.uk_assignments_task_id");

        // M5b: a constraint with that name but a different definition
        execute(collide, "ALTER TABLE public.assignments ADD CONSTRAINT uk_assignments_task_id UNIQUE (task_id, staff_id)");
        FlywayException definition = assertThrows(FlywayException.class, () -> flyway(collide, true, V3_TARGET).migrate());
        assertTrue(messages(definition).contains("unexpected definition"), messages(definition));
        execute(collide, "ALTER TABLE public.assignments DROP CONSTRAINT uk_assignments_task_id");

        // M5c: a unique index on task_id under another name
        execute(collide, "CREATE UNIQUE INDEX wb_rehearsal_other_unique_task_id ON public.assignments (task_id)");
        FlywayException otherName = assertThrows(FlywayException.class, () -> flyway(collide, true, V3_TARGET).migrate());
        assertTrue(messages(otherName).contains("unique index under another name"), messages(otherName));
        execute(collide, "DROP INDEX public.wb_rehearsal_other_unique_task_id");
        assertEquals(List.of("1:SQL:true", "2:SQL:true"), history(collide));

        // M6: an identical constraint already exists: V3 succeeds without claiming it
        execute(collide, "ALTER TABLE public.assignments ADD CONSTRAINT uk_assignments_task_id UNIQUE (task_id)");
        assertEquals(1, flyway(collide, true, V3_TARGET).migrate().migrationsExecuted);
        assertEquals(List.of("1:SQL:true", "2:SQL:true", "3:SQL:true"), history(collide));
        assertNull(constraintComment(collide), "V3 must not label a constraint it did not create");

        // The fresh-install rollback must refuse to drop a constraint V3 did not create
        assertThrows(SQLException.class, () -> execute(collide, Files.readString(ROLLBACK_FRESH)));
        assertEquals("UNIQUE (task_id)", constraintDefinition(collide));
        assertEquals(List.of("1:SQL:true", "2:SQL:true", "3:SQL:true"), history(collide));
    }
}
