package com.hotel.workload.migration;

import com.hotel.workload.staff.DepartmentCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * File-level checks on the V4 migration (departments table) and its two rollback scripts.
 * Reads files only: no Spring context and no database connection. The behaviour of the SQL itself is
 * proved against a PostgreSQL rehearsal copy by DepartmentMigrationIT.
 */
public class DepartmentMigrationFilesTest {

    private static final Path V4 = Path.of("src/main/resources/db/migration/V4__departments_table.sql");
    private static final Path ROLLBACK_EXISTING = Path.of("db-tools/rollback/V4_existing.sql");
    private static final Path ROLLBACK_FRESH = Path.of("db-tools/rollback/V4_fresh.sql");
    private static final String V4_MARKER = "Created by Flyway V4: departments table (replaces the department enum)";

    private static String read(Path file) throws IOException {
        return Files.readString(file);
    }

    private static String withoutLineComments(String sql) {
        return sql.lines().map(line -> line.replaceFirst("--.*$", "")).collect(Collectors.joining("\n"));
    }

    @Test
    @DisplayName("V4 checks every assumption before it creates or changes anything")
    public void testV4ChecksBeforeChanging() throws IOException {
        String sql = withoutLineComments(read(V4));
        int firstChange = sql.indexOf("CREATE TABLE departments");
        assertTrue(firstChange > 0);

        Matcher raises = Pattern.compile("RAISE EXCEPTION").matcher(sql);
        int count = 0;
        while (raises.find()) {
            count++;
            assertTrue(raises.start() < firstChange, "Every RAISE EXCEPTION must come before the first change");
        }
        assertTrue(count >= 9, "Expected the precondition guards, found " + count);

        for (String guard : List.of(
                "staff or %.tasks not found",            // tables exist
                "must be character varying",             // column types
                ".departments already exists",           // no existing departments relation
                "outside the six built-in departments",  // all stored codes are known
                "is not the six-value enum check")) {    // only the Hibernate enum CHECK is dropped
            int at = sql.indexOf(guard);
            assertTrue(at >= 0 && at < firstChange, "Missing guard before the change: " + guard);
        }
        assertTrue(sql.contains("SET LOCAL lock_timeout"), "Must fail fast on lock contention");
    }

    @Test
    @DisplayName("V4 never updates, deletes or truncates existing rows and drops only the two enum CHECK constraints")
    public void testV4DoesNotTouchExistingRows() throws IOException {
        String sql = withoutLineComments(read(V4));
        // "ON DELETE SET NULL" is the new table's own foreign-key rule (a removed staff member clears the head), not a row delete
        String withoutLiterals = sql.replaceAll("'[^']*'", "''").replace("ON DELETE SET NULL", "");
        assertFalse(Pattern.compile("(?i)\\b(UPDATE|DELETE|TRUNCATE|DROP\\s+TABLE|DROP\\s+SCHEMA|CASCADE)\\b").matcher(withoutLiterals).find(),
                "V4 must not update, delete, truncate or cascade");
        // INSERT only into the new departments table
        Matcher inserts = Pattern.compile("(?i)INSERT\\s+INTO\\s+(\\w+)").matcher(withoutLiterals);
        int seeded = 0;
        while (inserts.find()) {
            assertEquals("departments", inserts.group(1));
            seeded++;
        }
        assertEquals(6, seeded, "Exactly the six built-in departments are seeded");
        // The only DROP is of CHECK constraints picked by the guarded query
        Matcher drops = Pattern.compile("(?i)\\bDROP\\b[^;]*").matcher(withoutLiterals);
        while (drops.find()) {
            assertTrue(drops.group().toUpperCase().contains("DROP CONSTRAINT %I"), "Unexpected DROP: " + drops.group());
        }
        assertTrue(sql.contains("contype = 'c'") && sql.contains("pg_get_constraintdef(oid) ~* '\\mdepartment\\M'"),
                "Only CHECK constraints that mention department may be dropped");
        assertFalse(sql.contains("${"), "V4 must not contain Flyway placeholders");
    }

    @Test
    @DisplayName("V4 seeds exactly the built-in departments of the application, in order, with their codes, names, icons and colours")
    public void testV4SeedMatchesTheCatalog() throws IOException {
        String sql = read(V4);
        Pattern insert = Pattern.compile(
                "VALUES \\(gen_random_uuid\\(\\), '([A-Z_]+)', '([^']+)', NULL, '([a-z]+)', '([a-z]+)', TRUE, clock_timestamp\\(\\)\\);");
        Matcher matcher = insert.matcher(sql);
        List<DepartmentCatalog.BuiltIn> seeded = new ArrayList<>();
        while (matcher.find()) {
            seeded.add(new DepartmentCatalog.BuiltIn(matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4)));
        }
        assertEquals(DepartmentCatalog.BUILT_IN, seeded, "V4 seed rows must equal DepartmentCatalog.BUILT_IN, in the same order");
        for (DepartmentCatalog.BuiltIn builtIn : seeded) {
            assertTrue(DepartmentCatalog.ICON_KEYS.contains(builtIn.iconKey()), builtIn.iconKey());
            assertTrue(DepartmentCatalog.COLOR_KEYS.contains(builtIn.colorKey()), builtIn.colorKey());
        }
    }

    @Test
    @DisplayName("V4 creates the unique code, case-insensitive unique name, head foreign key, and the staff/task foreign keys and indexes")
    public void testV4Structure() throws IOException {
        String sql = withoutLineComments(read(V4));
        for (String expected : List.of(
                "id            UUID         PRIMARY KEY",
                "CONSTRAINT uk_departments_code UNIQUE (code)",
                "FOREIGN KEY (head_staff_id) REFERENCES staff (id) ON DELETE SET NULL",
                "CREATE UNIQUE INDEX uk_departments_name_lower ON departments (lower(name))",
                "ALTER TABLE staff ADD CONSTRAINT fk_staff_department_code FOREIGN KEY (department) REFERENCES departments (code)",
                "ALTER TABLE tasks ADD CONSTRAINT fk_tasks_department_code FOREIGN KEY (department) REFERENCES departments (code)",
                "CREATE INDEX idx_staff_department ON staff (department)",
                "CREATE INDEX idx_tasks_department ON tasks (department)")) {
            assertTrue(sql.contains(expected), "V4 is missing: " + expected);
        }
        assertTrue(read(V4).contains(V4_MARKER), "V4 must label its table for the rollback scripts");
        // The foreign keys come after the seed rows they depend on
        assertTrue(sql.indexOf("INSERT INTO departments") < sql.indexOf("ADD CONSTRAINT fk_staff_department_code"));
    }

    @Test
    @DisplayName("Both V4 rollback scripts are one guarded transaction that only undoes a V4 that is exactly as it was created")
    public void testRollbackScriptsAreGuarded() throws IOException {
        for (Path script : List.of(ROLLBACK_EXISTING, ROLLBACK_FRESH)) {
            String raw = read(script);
            String sql = withoutLineComments(raw).trim();
            assertTrue(sql.startsWith("BEGIN;") && sql.endsWith("COMMIT;"), script + " must run in one transaction");
            assertTrue(raw.contains(V4_MARKER), script + " must check the V4 marker");
            assertTrue(raw.contains("IS DISTINCT FROM '" + V4_MARKER + "'"), script + " must refuse a table V4 did not create");

            int firstChange = sql.indexOf("ALTER TABLE public.staff DROP CONSTRAINT");
            assertTrue(firstChange > 0);
            Matcher raises = Pattern.compile("RAISE EXCEPTION").matcher(sql);
            int count = 0;
            while (raises.find()) {
                count++;
                assertTrue(raises.start() < firstChange, "Every guard must come before the first change in " + script);
            }
            assertTrue(count >= 6, script + " expected at least 6 guards, found " + count);
            assertTrue(sql.contains("v_count <> 6"), script + " must refuse when departments were added");
            assertTrue(sql.contains("NOT IN ("), script + " must refuse when an original department was edited");
            assertTrue(sql.contains("DELETE FROM public.flyway_schema_history WHERE version = '4'"));
            assertFalse(Pattern.compile("(?i)\\b(TRUNCATE|CASCADE)\\b").matcher(sql).find(), script + " must not truncate or cascade");
            // No application rows are modified
            assertFalse(Pattern.compile("(?i)\\b(UPDATE|DELETE\\s+FROM)\\s+public\\.(staff|tasks|assignments|users)\\b").matcher(sql).find());
        }
        assertTrue(read(ROLLBACK_EXISTING).contains("IS DISTINCT FROM '2:BASELINE:true,3:SQL:true,4:SQL:true'"));
        assertTrue(read(ROLLBACK_FRESH).contains("IS DISTINCT FROM '1:SQL:true,2:SQL:true,3:SQL:true,4:SQL:true'"));
    }

    @Test
    @DisplayName("Only the rollback of an existing (Hibernate-built) database puts the enum CHECK constraints back")
    public void testOnlyExistingRollbackRestoresTheChecks() throws IOException {
        String existing = read(ROLLBACK_EXISTING);
        String fresh = read(ROLLBACK_FRESH);
        for (String check : List.of("staff_department_check", "tasks_department_check")) {
            assertTrue(existing.contains("ADD CONSTRAINT " + check), "V4_existing.sql must restore " + check);
            assertFalse(withoutLineComments(fresh).contains(check), "V4_fresh.sql must not add " + check + ": a fresh database never had it");
        }
        for (DepartmentCatalog.BuiltIn builtIn : DepartmentCatalog.BUILT_IN) {
            assertTrue(existing.contains("'" + builtIn.code() + "'::character varying"), builtIn.code() + " in the restored CHECK");
        }
    }
}
