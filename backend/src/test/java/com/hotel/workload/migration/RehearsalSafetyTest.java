package com.hotel.workload.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Safety checks for the P4a rehearsal tooling (create-rehearsal-dbs.ps1, RehearsalCommon.ps1) and the
 * name guard of PostgresMigrationIT. Runs in the default `mvn test`: never connects to a database.
 */
public class RehearsalSafetyTest {

    private static final Path DB_TOOLS = Path.of("db-tools");
    private static final Path CREATE = DB_TOOLS.resolve("create-rehearsal-dbs.ps1");
    private static final Path REHEARSAL_COMMON = DB_TOOLS.resolve("RehearsalCommon.ps1");
    private static final Path SAFETY_TESTS = DB_TOOLS.resolve("tests").resolve("RehearsalSafety.Tests.ps1");
    private static final Path IT_SOURCE = Path.of("src/test/java/com/hotel/workload/migration/PostgresMigrationIT.java");
    private static final String VERIFIED_SHA256 = "37a58cb40577d51764edb72fe170d45adbb6e9a47c8442ba88e76b05c0586a90";

    private static String read(Path file) throws IOException {
        return Files.readString(file);
    }

    /** PowerShell code without <# #> help and # comments (patterns never cross line breaks). */
    private static String code(Path file) throws IOException {
        return read(file)
                .replaceAll("(?s)<#.*?#>", "")
                .replaceAll("(?m)^[ \\t]*#[^\\r\\n]*$", "")
                .replaceAll("(?m)[ \\t]+#[^'\"\\r\\n]*$", "");
    }

    private static int indexOrFail(String text, String needle) {
        int at = text.indexOf(needle);
        assertTrue(at >= 0, "Expected to find: " + needle);
        return at;
    }

    // ── The integration test's own guard, exercised offline ───────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "workload_balancer", "WORKLOAD_BALANCER",
            "wb_restore_check_20261020_140921", "wb_restore_check_20261020_142245", "WB_Restore_Check_20261020_142245",
            "wb_restore_check_20991231_000000", "wb_restored_20261020_150000",
            "postgres", "template0", "template1", "",
            "wb_rehearsal_20261020_150000_live", "wb_rehearsal_20261020_150000_copy2", "WB_REHEARSAL_20261020_150000_COPY",
            "wb_rehearsal_2026102_150000_copy", " wb_rehearsal_20261020_150000_copy", "wb_rehearsal_20261020_150000_copy\n",
            "wb_rehearsal_20261020_150000_copy\nworkload_balancer", "wb_rehearsal_\u0662\u0662\u0662\u0662\u0662\u0662\u0662\u0662_150000_copy"
    })
    @DisplayName("PostgresMigrationIT refuses protected and malformed database names before connecting")
    public void testIntegrationTestGuardRefuses(String name) {
        assertThrows(AssertionError.class, () -> PostgresMigrationIT.assertRehearsalDatabaseName(name));
    }

    @ParameterizedTest
    @ValueSource(strings = { "fresh", "collide", "copy", "dupe" })
    @DisplayName("PostgresMigrationIT accepts the four rehearsal roles")
    public void testIntegrationTestGuardAccepts(String role) {
        assertDoesNotThrow(() -> PostgresMigrationIT.assertRehearsalDatabaseName("wb_rehearsal_20261020_150000_" + role));
    }

    @Test
    @DisplayName("PowerShell and Java guards use the same pattern and the same protected databases")
    public void testPowerShellAndJavaGuardsAgree() throws IOException {
        String common = code(REHEARSAL_COMMON);
        String it = read(IT_SOURCE);
        Matcher psPattern = Pattern.compile("\\$script:RehearsalNamePattern\\s*=\\s*'([^']+)'").matcher(common);
        assertTrue(psPattern.find());
        Matcher javaPattern = Pattern.compile("Pattern\\.compile\\(\"(\\^wb_rehearsal_[^\"]+)\"\\)").matcher(it);
        assertTrue(javaPattern.find());
        assertEquals(psPattern.group(1), javaPattern.group(1).replace("\\\\", "\\"), "Both guards must use the identical pattern");
        assertEquals("^wb_rehearsal_\\d{8}_\\d{6}_(fresh|copy|dupe|collide)$", psPattern.group(1));

        for (String name : List.of("workload_balancer", "wb_restore_check_20261020_140921", "wb_restore_check_20261020_142245",
                "postgres", "template0", "template1")) {
            assertTrue(common.contains("'" + name + "'"), "RehearsalCommon.ps1 must protect " + name);
            assertTrue(it.contains("\"" + name + "\""), "PostgresMigrationIT must protect " + name);
        }
        for (String prefix : List.of("wb_restore_check_", "wb_restored_")) {
            assertTrue(common.contains("'" + prefix + "'") && it.contains("\"" + prefix + "\""), "Both guards must protect " + prefix + "*");
        }
    }

    // ── RehearsalCommon.ps1 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("RehearsalCommon.ps1 pins the verified dump checksum, matches whole names with ASCII digits, and is pure")
    public void testRehearsalCommonRules() throws IOException {
        String common = code(REHEARSAL_COMMON);
        assertTrue(Pattern.compile("\\$script:VerifiedDumpSha256\\s*=\\s*'" + VERIFIED_SHA256 + "'").matcher(common).find(),
                "The verified dump checksum must be pinned");
        assertTrue(common.contains("[System.Text.RegularExpressions.RegexOptions]::ECMAScript"), "\\d must be ASCII-only");
        assertTrue(common.contains("$match.Index -eq 0 -and $match.Length -eq $Value.Length"), "Names must match as a whole string");
        assertTrue(common.contains("Assert-RehearsalDatabaseName $name"), "Every generated name must be validated");
        for (String sideFile : List.of(".live-schema.sql", ".check-schema.sql", ".live-fingerprint.txt", ".restore-errors.log")) {
            assertTrue(common.contains("\"$base" + sideFile + "\""), "Side file must be required: " + sideFile);
        }
        assertTrue(common.contains("flyway_schema_history absent"), "Copies must start from a pre-Flyway backup");
        assertFalse(Pattern.compile("(?i)(Invoke-Pg|Get-QueryResult|psql|pg_dump|pg_restore|createdb|dropdb)").matcher(common).find(),
                "RehearsalCommon.ps1 must not run database tools");
    }

    // ── create-rehearsal-dbs.ps1 ───────────────────────────────────────────────────

    @Test
    @DisplayName("create-rehearsal-dbs.ps1 validates the dump, names and outputs before connecting")
    public void testCreationScriptValidatesFirst() throws IOException {
        String script = code(CREATE);
        assertTrue(script.contains(". (Join-Path $PSScriptRoot 'DbToolsCommon.ps1')"));
        assertTrue(script.contains(". (Join-Path $PSScriptRoot 'RehearsalCommon.ps1')"));
        assertTrue(script.contains("$verified = Assert-VerifiedDump -Path $Dump"), "The dump must be validated");
        assertFalse(script.contains("-ExpectedSha256"), "The script must never override the pinned checksum");

        int connect = indexOrFail(script, "Save-PgEnvironment");
        for (String precondition : List.of("Assert-PgTools", "Assert-VerifiedDump -Path $Dump",
                "Get-RehearsalDatabaseNames -Stamp $stamp", "Assert-FilesDoNotExist")) {
            assertTrue(indexOrFail(script, precondition) < connect, precondition + " must run before any connection");
        }
        int readOnly = indexOrFail(script, "Use-ReadOnlySession");
        int existence = indexOrFail(script, "-Database 'postgres'");
        int defaultSession = indexOrFail(script, "Use-DefaultSession");
        assertTrue(readOnly < existence && existence < defaultSession, "The existence check must run in the read-only session");
        assertTrue(defaultSession < indexOrFail(script, "New-RehearsalDatabase $names"), "Nothing is created before the existence check");
    }

    @Test
    @DisplayName("create-rehearsal-dbs.ps1 can only create, restore into and query the generated rehearsal databases")
    public void testCreationScriptTargetsOnlyRehearsalDatabases() throws IOException {
        String script = code(CREATE);
        assertFalse(script.contains("workload_balancer") || script.contains("wb_restore_check") || script.contains("$LiveDb"),
                "The creation script must not name the live or restore-check databases");
        assertFalse(Pattern.compile("(?i)(dropdb|DROP\\s+DATABASE|ALTER\\s+DATABASE|RENAME\\s+TO|--clean|--if-exists|--create\\b|Remove-Item|New-Item|-Force|createdb\\.exe\\s+-T|'-T')").matcher(script).find(),
                "No drop, rename, clean, template copy, deletion or forced writes");
        assertFalse(Pattern.compile("(?i)(flyway|mvn|migrate)").matcher(script.replaceAll("(?i)WB_MIGRATION_TEST_STAMP", "")).find(),
                "The creation script must not run migrations");

        Matcher newDb = Pattern.compile("(?s)function New-RehearsalDatabase \\{(.*?)\\n\\}").matcher(script);
        assertTrue(newDb.find());
        String body = newDb.group(1);
        int check = indexOrFail(body, "Assert-RehearsalDatabaseName $Name");
        assertTrue(check < indexOrFail(body, "Register-Database $Name") && check < indexOrFail(body, "'createdb.exe'"),
                "The name must be re-checked immediately before createdb");
        assertEquals(1, Pattern.compile("createdb\\.exe").matcher(script).results().count(), "createdb only inside New-RehearsalDatabase");
        Matcher calls = Pattern.compile("New-RehearsalDatabase (\\S+)").matcher(script.replace(body, ""));
        int callCount = 0;
        while (calls.find()) {
            if (calls.group(1).equals("{")) continue;
            callCount++;
            assertTrue(List.of("$names[$role]", "$name").contains(calls.group(1)), "Only generated names may be created: " + calls.group());
        }
        assertEquals(2, callCount, "One call for the empty databases loop and one for the restored copies loop");

        assertEquals(1, Pattern.compile("Invoke-PgRestoreLogged").matcher(script).results().count());
        assertTrue(script.contains("'-d', $name, '--no-owner'"), "pg_restore must target the generated copy/dupe name");
        assertTrue(script.contains("$name = $names[$role]"));
        assertTrue(script.contains("foreach ($role in @('copy', 'dupe'))"), "Only copy and dupe receive the dump");

        Matcher queries = Pattern.compile("-Database (\\$\\w+|'[^']*')").matcher(script);
        while (queries.find()) {
            assertTrue(List.of("$Name", "$name", "'postgres'").contains(queries.group(1)), "Unexpected query target: " + queries.group());
        }
        Matcher empty = Pattern.compile("(?s)function Assert-EmptyDatabase \\{(.*?)\\n\\}").matcher(script);
        assertTrue(empty.find() && empty.group(1).indexOf("Assert-RehearsalDatabaseName $Name") < empty.group(1).indexOf("Get-QueryResult"),
                "The empty check must validate the name before connecting");
    }

    @Test
    @DisplayName("create-rehearsal-dbs.ps1 reports success only after every check, and reports and re-throws failures")
    public void testCreationScriptSuccessAndFailureHandling() throws IOException {
        String script = code(CREATE);
        int success = indexOrFail(script, "REHEARSAL DATABASES READY");
        Matcher checks = Pattern.compile("Assert-SameText|Assert-EmptyDatabase \\$names").matcher(script);
        int count = 0;
        while (checks.find()) {
            count++;
            assertTrue(checks.start() < success, "Every check must come before the success message");
        }
        assertEquals(3, count, "Empty check, schema comparison and fingerprint comparison");

        Matcher handler = Pattern.compile("(?s)\\} catch \\{(.*?)\\} finally \\{").matcher(script);
        assertTrue(handler.find());
        assertTrue(handler.group(1).contains("Write-ArtifactReport"), "Failures must list what was left behind");
        assertTrue(Pattern.compile("(?m)^\\s*throw\\s*$").matcher(handler.group(1)).find(), "Failures must be re-thrown");
        assertTrue(script.trim().endsWith("} finally {\n    Restore-PgEnvironment\n}") || script.trim().replace("\r\n", "\n").endsWith("} finally {\n    Restore-PgEnvironment\n}"),
                "The environment must be restored in finally");
        assertTrue(indexOrFail(script, "Register-Database $Name") < indexOrFail(script, "'createdb.exe'"), "Register before createdb");
        assertTrue(indexOrFail(script, "Register-File $outputs[$role].RestoreLog") < indexOrFail(script, "Invoke-PgRestoreLogged"));
        assertTrue(indexOrFail(script, "Register-File $outputs[$role].Schema") < indexOrFail(script, "'--schema-only'"));
    }

    @Test
    @DisplayName("All rehearsal PowerShell files are ASCII, and the pure-function safety tests pass (Windows only)")
    public void testRehearsalSafetyPowerShellTestsPass() throws Exception {
        for (Path file : List.of(CREATE, REHEARSAL_COMMON, SAFETY_TESTS)) {
            byte[] bytes = Files.readAllBytes(file);
            for (byte b : bytes) {
                assertTrue(b >= 0, file + " must be ASCII only");
            }
            assertEquals(new String(bytes, StandardCharsets.US_ASCII), read(file));
        }
        String tests = code(SAFETY_TESTS);
        assertFalse(Pattern.compile("(?i)(psql|pg_dump|pg_restore|createdb|dropdb|Invoke-Pg|Get-QueryResult|Get-VerificationFingerprint|Assert-PgTools)").matcher(tests).find(),
                "The pure-function tests must not run database tools");

        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("windows");
        org.junit.jupiter.api.Assumptions.assumeTrue(windows, "Windows PowerShell is only available on Windows");
        Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-File", SAFETY_TESTS.toAbsolutePath().toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS), "PowerShell tests timed out");
        assertEquals(0, process.exitValue(), "RehearsalSafety.Tests.ps1 failed:\n" + output);
        assertTrue(output.contains("ALL PASSED"), output);
    }

    // ── PostgresMigrationIT stays opt-in and includes the recovery scenarios ────────────

    @Test
    @DisplayName("PostgresMigrationIT is opt-in, guards names before connecting, and includes both recovery scenarios")
    public void testIntegrationTestIsOptInAndComplete() throws IOException {
        String pom = read(Path.of("pom.xml"));
        assertTrue(pom.contains("<wb.test.excludedGroups>pg-migration</wb.test.excludedGroups>"));
        assertTrue(pom.contains("<excludedGroups>${wb.test.excludedGroups}</excludedGroups>"));
        String it = read(IT_SOURCE);
        assertTrue(it.contains("@Tag(\"pg-migration\")"));

        Matcher target = Pattern.compile("(?s)private static Target target\\(String role\\) throws SQLException \\{(.*?)\\n    \\}").matcher(it);
        assertTrue(target.find());
        String body = target.group(1);
        assertTrue(indexOrFail(body, "assertRehearsalDatabaseName(database)") < indexOrFail(body, "queryString(target"),
                "The name must be checked before the first connection");
        assertTrue(body.contains("assertRehearsalDatabaseName(connectedTo)"), "The connected database must be re-checked");

        Matcher fresh = Pattern.compile("(?s)public void freshDatabaseRunsAllMigrationsOnce\\(\\).*?\\n    \\}").matcher(it);
        assertTrue(fresh.find());
        assertTrue(fresh.group().contains("execute(fresh, Files.readString(ROLLBACK_FRESH))"), "Fresh-install rollback success path");
        assertTrue(fresh.group().contains("List.of(\"1:SQL:true\", \"2:SQL:true\")"), "Rollback removes only the V3 history row");
        assertTrue(fresh.group().contains("Re-applied V3 must produce the same schema as the first run"), "Fresh-install re-application");

        Matcher copy = Pattern.compile("(?s)public void existingCopyIsBaselinedAndRolledBackExactly\\(\\).*?\\n    \\}").matcher(it);
        assertTrue(copy.find());
        assertEquals(2, Pattern.compile("execute\\(copy, Files\\.readString\\(ROLLBACK_EXISTING\\)\\)").matcher(copy.group()).results().count(),
                "Copy: rollback, re-apply, rollback again");
        assertTrue(copy.group().contains("The second rollback must again restore the exact pre-Flyway schema"));
    }
}
