package com.hotel.workload.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * File-level checks on the Flyway migrations and the db-tools rollback scripts.
 * Reads files only: no Spring context and no database connection.
 */
public class MigrationFilesTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
    private static final Path DB_TOOLS = Path.of("db-tools");
    private static final String V3_MARKER = "Created by Flyway V3: one assignment row per task";

    /**
     * Existing databases are baselined at version 2, but fresh databases run V1 and V2, and Flyway
     * validates their checksums on every start. Editing them would break that, so they are pinned.
     */
    private static final String V1_SHA256 = "a9fcbb29382ce069dd149edc8af1a78984838818951fa6f1e0a598fe13eb5cfd";
    private static final String V2_SHA256 = "281402ad8c9f4788d3754897f146f9b1d55887e99fc858eb743f3203e2777b15";

    private static String sha256(Path file) throws IOException, NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file);
    }

    /** SQL with "--" line comments removed, so keyword checks look at executable text only. */
    private static String withoutLineComments(String sql) {
        return sql.lines().map(line -> line.replaceFirst("--.*$", "")).collect(Collectors.joining("\n"));
    }

    /** SQL with single-quoted string literals blanked, so keyword checks ignore message text. */
    private static String withoutStringLiterals(String sql) {
        return sql.replaceAll("'[^']*'", "''");
    }

    @Test
    @DisplayName("V1 and V2 are unchanged (pinned SHA-256)")
    public void testV1AndV2AreUnchanged() throws Exception {
        assertEquals(V1_SHA256, sha256(MIGRATIONS.resolve("V1__initial_schema.sql")), "V1__initial_schema.sql must never be edited");
        assertEquals(V2_SHA256, sha256(MIGRATIONS.resolve("V2__add_credentials_setup_required.sql")), "V2__add_credentials_setup_required.sql must never be edited");
    }

    @Test
    @DisplayName("The migration folder holds only V1 to V7 with valid, unique Flyway names")
    public void testMigrationFolderContents() throws IOException {
        List<String> names;
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            names = files.map(p -> p.getFileName().toString()).sorted().toList();
        }
        assertEquals(List.of(
                "V1__initial_schema.sql",
                "V2__add_credentials_setup_required.sql",
                "V3__assignment_task_unique.sql",
                "V4__departments_table.sql",
                "V5__support_assignments.sql",
                "V6__support_manual_end_reason.sql",
                "V7__support_archive.sql"), names);

        Pattern flywayName = Pattern.compile("^V(\\d+)__[a-z0-9_]+\\.sql$");
        List<Integer> versions = names.stream().map(name -> {
            Matcher m = flywayName.matcher(name);
            assertTrue(m.matches(), "Not a valid versioned Flyway file name: " + name);
            return Integer.parseInt(m.group(1));
        }).toList();
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7), versions, "Versions must be 1 to 7 with no gaps or duplicates");
    }

    @Test
    @DisplayName("V7 only adds two nullable columns to support_assignments: no data, constraint or other table is touched")
    public void testV7OnlyAddsTwoNullableColumns() throws IOException {
        String executable = withoutLineComments(read(MIGRATIONS.resolve("V7__support_archive.sql")));
        String code = withoutStringLiterals(executable);
        assertFalse(Pattern.compile("(?i)\\b(DELETE|UPDATE|INSERT|TRUNCATE|CASCADE|RENAME|DROP|NOT\\s+NULL|DEFAULT|CONSTRAINT|INDEX)\\b").matcher(code).find(),
                "V7 must not change data, constraints or indexes, and the new columns must be nullable with no default");
        assertEquals(2, Pattern.compile("(?i)ADD COLUMN IF NOT EXISTS").matcher(executable).results().count());
        assertTrue(executable.contains("ALTER TABLE support_assignments ADD COLUMN IF NOT EXISTS archived_at TIMESTAMP;"));
        assertTrue(executable.contains("ALTER TABLE support_assignments ADD COLUMN IF NOT EXISTS archived_by_username VARCHAR(255);"));
        assertEquals(2, Pattern.compile("(?i)ALTER TABLE").matcher(executable).results().count(), "only support_assignments is altered");
        assertTrue(executable.contains("SET LOCAL lock_timeout"));
    }

    @Test
    @DisplayName("V6 only swaps two constraints on support_assignments (end-reason check, task uniqueness); no data is touched")
    public void testV6OnlySwapsTheEndReasonConstraint() throws IOException {
        String executable = withoutLineComments(read(MIGRATIONS.resolve("V6__support_manual_end_reason.sql")));
        String code = withoutStringLiterals(executable);
        assertFalse(Pattern.compile("(?i)\\b(DELETE|UPDATE|INSERT|TRUNCATE|CASCADE|RENAME|DROP\\s+(TABLE|COLUMN|INDEX))\\b").matcher(code).find(),
                "V6 must not remove or rewrite data or objects other than the one constraint");
        assertEquals(3, Pattern.compile("(?i)DROP CONSTRAINT IF EXISTS").matcher(executable).results().count(),
                "V6 drops the Flyway-named and the Hibernate-named end-reason check, and the old task uniqueness, if present");
        assertTrue(executable.contains("DROP CONSTRAINT IF EXISTS uk_support_assignments_task"));
        assertTrue(executable.contains("CREATE UNIQUE INDEX IF NOT EXISTS uk_support_assignments_active_task ON support_assignments (task_id) WHERE status = 'ACTIVE'"));
        assertTrue(executable.indexOf("CREATE UNIQUE INDEX") < executable.indexOf("DROP CONSTRAINT IF EXISTS uk_support_assignments_task"),
                "the new guard exists before the old one is dropped");
        assertTrue(executable.contains("DROP CONSTRAINT IF EXISTS ck_support_assignments_end_reason"));
        assertTrue(executable.contains("DROP CONSTRAINT IF EXISTS support_assignments_end_reason_check"));
        assertEquals(1, Pattern.compile("(?i)ADD CONSTRAINT").matcher(executable).results().count());
        // Every reason that V5 allowed must still be allowed, plus the new one
        for (String reason : List.of("TASK_COMPLETED", "TASK_CANCELLED", "TASK_DELETED", "TASK_REASSIGNED", "TASK_MOVED", "MANUALLY_ENDED")) {
            assertTrue(executable.contains("'" + reason + "'"), "V6 must allow " + reason);
        }
        assertTrue(executable.contains("SET LOCAL lock_timeout"));
    }

    @Test
    @DisplayName("V3 is additive only: no statements that remove, rewrite or cascade data or schema objects")
    public void testV3IsAdditiveOnly() throws IOException {
        String executable = withoutLineComments(read(MIGRATIONS.resolve("V3__assignment_task_unique.sql")));
        Matcher forbidden = Pattern.compile("(?i)\\b(DROP|DELETE|UPDATE|INSERT|TRUNCATE|CASCADE|RENAME)\\b").matcher(executable);
        assertFalse(forbidden.find(), () -> "V3 must not contain " + forbidden.group());
        assertFalse(executable.contains("${"), "V3 must not contain Flyway placeholders");
        assertEquals(1, Pattern.compile("(?i)ADD CONSTRAINT").matcher(executable).results().count(),
                "V3 must add exactly one constraint");
        assertTrue(executable.contains("ADD CONSTRAINT uk_assignments_task_id UNIQUE (task_id)"));
    }

    @Test
    @DisplayName("V3 checks duplicates, column type, foreign key and name collisions before changing anything")
    public void testV3ChecksPreconditionsBeforeTheChange() throws IOException {
        String v3 = withoutLineComments(read(MIGRATIONS.resolve("V3__assignment_task_unique.sql")));
        int change = v3.indexOf("ADD CONSTRAINT uk_assignments_task_id");

        String[] requiredChecks = {
                "SET LOCAL lock_timeout",                                   // fail fast on locks
                "v_col.data_type <> 'uuid' OR v_col.is_nullable <> 'NO'",   // task_id is uuid NOT NULL
                "contype = 'f'",                                            // validated FK to tasks(id)
                "HAVING count(*) > 1",                                      // duplicate detection
                "conname = 'uk_assignments_task_id'",                       // constraint-name collision
                "relname = 'uk_assignments_task_id'",                       // relation-name collision
                "indisunique",                                              // unique index under another name
        };
        for (String check : requiredChecks) {
            int at = v3.indexOf(check);
            assertTrue(at >= 0, "V3 is missing the check: " + check);
            assertTrue(at < change, "V3 must run this check before adding the constraint: " + check);
        }
    }

    @Test
    @DisplayName("V3 labels its constraint, and both rollback scripts only drop a constraint carrying that label")
    public void testV3MarkerIsSharedWithRollbackScripts() throws IOException {
        assertTrue(read(MIGRATIONS.resolve("V3__assignment_task_unique.sql")).contains(V3_MARKER));
        for (String script : List.of("V3_existing.sql", "V3_fresh.sql")) {
            String rollback = read(DB_TOOLS.resolve("rollback").resolve(script));
            assertTrue(rollback.contains("IS DISTINCT FROM '" + V3_MARKER + "'"),
                    script + " must refuse to drop a constraint that V3 did not create");
            assertFalse(rollback.contains("\\set") || rollback.contains("\\i "),
                    script + " must be plain SQL (no psql meta-commands) so tests can run it too");
        }
    }

    @Test
    @DisplayName("V3_existing.sql changes nothing unless the exact history and the V3-labelled constraint are present")
    public void testExistingInstallRollbackIsFullyGuarded() throws IOException {
        String sql = withoutLineComments(read(DB_TOOLS.resolve("rollback").resolve("V3_existing.sql"))).trim();

        // One transaction around everything
        assertTrue(sql.startsWith("BEGIN;"), "V3_existing.sql must start with BEGIN;");
        assertTrue(sql.endsWith("COMMIT;"), "V3_existing.sql must end with COMMIT;");

        // Exactly one of each change, both inside the guarded DO block, constraint before history table
        String dropConstraint = "ALTER TABLE public.assignments DROP CONSTRAINT uk_assignments_task_id;";
        String dropHistory = "DROP TABLE public.flyway_schema_history;";
        assertEquals(1, sql.split(Pattern.quote(dropConstraint), -1).length - 1, "Exactly one constraint drop");
        assertEquals(1, sql.split(Pattern.quote(dropHistory), -1).length - 1, "Exactly one history-table drop");
        // Keyword scans ignore quoted messages such as 'Refusing to drop it'
        String statementsOnly = withoutStringLiterals(sql.replace(dropConstraint, "").replace(dropHistory, ""));
        assertFalse(Pattern.compile("(?i)\\bDROP\\b").matcher(statementsOnly).find(), "No other DROP statements are allowed");
        assertFalse(Pattern.compile("(?i)\\b(DELETE|UPDATE|INSERT|TRUNCATE|CASCADE)\\b").matcher(statementsOnly).find(),
                "The existing-install rollback must not modify rows or cascade");

        int blockStart = sql.indexOf("DO $$");
        int blockEnd = sql.indexOf("$$;", blockStart);
        int constraintAt = sql.indexOf(dropConstraint);
        int historyAt = sql.indexOf(dropHistory);
        assertTrue(blockStart >= 0 && blockEnd > blockStart, "The checks and changes must live in one DO block");
        assertTrue(blockStart < constraintAt && constraintAt < historyAt && historyAt < blockEnd,
                "Both drops must be inside the guarded block, constraint first, then the history table");

        // Every check raises before any change; no check merely logs and carries on
        Matcher exceptions = Pattern.compile("RAISE EXCEPTION").matcher(sql);
        int exceptionCount = 0;
        while (exceptions.find()) {
            exceptionCount++;
            assertTrue(exceptions.start() < constraintAt, "Every RAISE EXCEPTION must come before the first change");
        }
        assertEquals(4, exceptionCount, "Expected 4 guards: history table present, exact history, constraint present, V3 marker");
        Matcher notices = Pattern.compile("RAISE NOTICE").matcher(sql);
        while (notices.find()) {
            assertTrue(notices.start() > historyAt, "A notice may only report success after both drops; a failed check must raise");
        }

        // The exact guards
        assertTrue(sql.contains("to_regclass('public.flyway_schema_history') IS NULL THEN"),
                "Must refuse when the history table is missing");
        assertTrue(sql.contains("ORDER BY installed_rank"), "History must be compared in installation order");
        assertTrue(sql.contains("|| success"), "History comparison must include the success flag");
        assertTrue(Pattern.compile("IS DISTINCT FROM '2:BASELINE:true,3:SQL:true' THEN\\s+RAISE EXCEPTION").matcher(sql).find(),
                "History must be exactly a successful version-2 BASELINE then a successful version-3 SQL entry");
        assertTrue(Pattern.compile("IF v_found IS NULL THEN\\s+RAISE EXCEPTION").matcher(sql).find(),
                "A missing uk_assignments_task_id must raise, never just log a notice");
        assertTrue(sql.contains("conrelid = 'public.assignments'::regclass"),
                "The constraint must be looked up on public.assignments");
        assertTrue(Pattern.compile("IS DISTINCT FROM '" + Pattern.quote(V3_MARKER) + "' THEN\\s+RAISE EXCEPTION").matcher(sql).find(),
                "A constraint without the exact V3 marker must raise");
    }

    @Test
    @DisplayName("V3_fresh.sql changes nothing unless history is exactly 1:SQL:true,2:SQL:true,3:SQL:true and the V3-labelled constraint is present")
    public void testFreshInstallRollbackIsFullyGuarded() throws IOException {
        String sql = withoutLineComments(read(DB_TOOLS.resolve("rollback").resolve("V3_fresh.sql"))).trim();

        // One transaction around everything
        assertTrue(sql.startsWith("BEGIN;"), "V3_fresh.sql must start with BEGIN;");
        assertTrue(sql.endsWith("COMMIT;"), "V3_fresh.sql must end with COMMIT;");

        // Exactly two changes: drop the V3 constraint, then delete only the V3 history row
        String dropConstraint = "ALTER TABLE public.assignments DROP CONSTRAINT uk_assignments_task_id;";
        String deleteV3Row = "DELETE FROM public.flyway_schema_history WHERE version = '3';";
        assertEquals(1, sql.split(Pattern.quote(dropConstraint), -1).length - 1, "Exactly one constraint drop");
        assertEquals(1, sql.split(Pattern.quote(deleteV3Row), -1).length - 1, "Exactly one deletion, of the V3 history row only");

        // Keyword scans ignore quoted messages such as 'Refusing to drop it'
        String statementsOnly = withoutStringLiterals(sql.replace(dropConstraint, "").replace(deleteV3Row, ""));
        assertFalse(Pattern.compile("(?i)\\b(DROP|DELETE|UPDATE|INSERT|TRUNCATE|CASCADE)\\b").matcher(statementsOnly).find(),
                "No other DROP, DELETE, UPDATE, INSERT, TRUNCATE or CASCADE is allowed; V1/V2 history must be kept");

        int blockStart = sql.indexOf("DO $$");
        int blockEnd = sql.indexOf("$$;", blockStart);
        int constraintAt = sql.indexOf(dropConstraint);
        int deleteAt = sql.indexOf(deleteV3Row);
        assertTrue(blockStart >= 0 && blockEnd > blockStart, "The checks and changes must live in one DO block");
        assertTrue(blockStart < constraintAt && constraintAt < deleteAt && deleteAt < blockEnd,
                "Both changes must be inside the guarded block, constraint first, then the V3 history row");

        // Every check raises before any change; no check merely logs and carries on
        Matcher exceptions = Pattern.compile("RAISE EXCEPTION").matcher(sql);
        int exceptionCount = 0;
        while (exceptions.find()) {
            exceptionCount++;
            assertTrue(exceptions.start() < constraintAt, "Every RAISE EXCEPTION must come before the first change");
        }
        assertEquals(4, exceptionCount, "Expected 4 guards: history table present, exact history, constraint present, V3 marker");
        Matcher notices = Pattern.compile("RAISE NOTICE").matcher(sql);
        while (notices.find()) {
            assertTrue(notices.start() > deleteAt, "A notice may only report success after both changes; a failed check must raise");
        }

        // The exact guards
        assertTrue(sql.contains("to_regclass('public.flyway_schema_history') IS NULL THEN"),
                "Must refuse when the history table is missing");
        assertTrue(sql.contains("ORDER BY installed_rank"), "History must be compared in installation order");
        assertTrue(sql.contains("|| success"), "History comparison must include the success flag");
        assertTrue(Pattern.compile("IS DISTINCT FROM '1:SQL:true,2:SQL:true,3:SQL:true' THEN\\s+RAISE EXCEPTION").matcher(sql).find(),
                "History must be exactly successful V1, V2 and V3 SQL entries, in order");
        assertFalse(sql.contains("'1:SQL,2:SQL,3:SQL'"), "The weaker comparison without success flags must be gone");
        assertTrue(Pattern.compile("IF v_found IS NULL THEN\\s+RAISE EXCEPTION").matcher(sql).find(),
                "A missing uk_assignments_task_id must raise");
        assertTrue(sql.contains("conrelid = 'public.assignments'::regclass"),
                "The constraint must be looked up on public.assignments");
        assertTrue(Pattern.compile("IS DISTINCT FROM '" + Pattern.quote(V3_MARKER) + "' THEN\\s+RAISE EXCEPTION").matcher(sql).find(),
                "A constraint without the exact V3 marker must raise");
    }

    @Test
    @DisplayName("Rollback scripts and db-tools stay outside the Flyway classpath location")
    public void testDbToolsAreNotOnTheMigrationPath() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src/main/resources"))) {
            List<String> stray = files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.contains("rollback") || name.endsWith(".ps1") || name.equals("fingerprint.sql") || name.equals("preflight.sql"))
                    .toList();
            assertTrue(stray.isEmpty(), "db-tools files must not be on the classpath: " + stray);
        }
        assertTrue(Files.exists(DB_TOOLS.resolve("fingerprint.sql")));
        assertTrue(Files.exists(DB_TOOLS.resolve("preflight.sql")));
    }
}
