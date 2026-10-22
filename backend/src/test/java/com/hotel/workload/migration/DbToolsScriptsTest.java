package com.hotel.workload.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Static safety checks on the backup / restore tooling in db-tools.
 * Reads files only: never runs PowerShell, never connects to a database.
 */
public class DbToolsScriptsTest {

    private static final Path DB_TOOLS = Path.of("db-tools");
    private static final Path COMMON = DB_TOOLS.resolve("DbToolsCommon.ps1");
    private static final Path BACKUP = DB_TOOLS.resolve("backup-and-verify.ps1");
    private static final Path RESTORE = DB_TOOLS.resolve("restore-to-new-db.ps1");
    private static final List<Path> SCRIPTS = List.of(COMMON, BACKUP, RESTORE,
            DB_TOOLS.resolve("RehearsalCommon.ps1"), DB_TOOLS.resolve("create-rehearsal-dbs.ps1"));

    private static String read(Path file) throws IOException {
        return Files.readString(file);
    }

    /** PowerShell code without <# #> help blocks and # comments, so checks look at executable text only. */
    private static String code(Path file) throws IOException {
        // Line-comment patterns must not cross line breaks ([ \t] and [^...\r\n], never \s)
        return read(file)
                .replaceAll("(?s)<#.*?#>", "")
                .replaceAll("(?m)^[ \\t]*#[^\\r\\n]*$", "")
                .replaceAll("(?m)[ \\t]+#[^'\"\\r\\n]*$", "");
    }

    /** SQL without "--" line comments. */
    private static String sql(Path file) throws IOException {
        return read(file).replaceAll("(?m)--.*$", "");
    }

    /** Asserts that an assignment like `$name = value` exists, ignoring alignment whitespace. */
    private static void assertAssignment(String text, String variable, String value) {
        Pattern p = Pattern.compile(Pattern.quote(variable) + "\\s*=\\s*" + Pattern.quote(value));
        assertTrue(p.matcher(text).find(), "Expected " + variable + " = " + value);
    }

    private static int indexOrFail(String text, String needle, String what) {
        int at = text.indexOf(needle);
        assertTrue(at >= 0, what + ": expected to find " + needle);
        return at;
    }

    @Test
    @DisplayName("Both scripts load the shared helpers, and all scripts are plain ASCII for Windows PowerShell 5.1")
    public void testScriptsShareHelpersAndAreAscii() throws IOException {
        for (Path script : List.of(BACKUP, RESTORE)) {
            assertTrue(code(script).contains(". (Join-Path $PSScriptRoot 'DbToolsCommon.ps1')"), script + " must dot-source DbToolsCommon.ps1");
        }
        for (Path script : SCRIPTS) {
            byte[] bytes = Files.readAllBytes(script);
            for (byte b : bytes) {
                assertTrue(b >= 0, script + " must be ASCII only (PowerShell 5.1 reads BOM-less files as ANSI)");
            }
            assertEquals(new String(bytes, StandardCharsets.US_ASCII), read(script));
        }
    }

    @Test
    @DisplayName("PostgreSQL 18 client tools and the project's live database name are used and checked first")
    public void testPostgres18ToolsAndLiveDatabaseName() throws IOException {
        String common = code(COMMON);
        assertAssignment(common, "$script:PgBin", "'C:\\Program Files\\PostgreSQL\\18\\bin'");
        assertAssignment(common, "$script:LiveDb", "'workload_balancer'");
        assertTrue(common.contains("'\\(PostgreSQL\\) 18\\.'"), "Assert-PgTools must require PostgreSQL 18 clients");
        for (Path script : List.of(BACKUP, RESTORE)) {
            String body = code(script);
            assertTrue(indexOrFail(body, "Assert-PgTools", script.toString()) < indexOrFail(body, "Save-PgEnvironment", script.toString()),
                    script + " must check the tools before connecting");
            assertFalse(body.contains("PostgreSQL\\17") || body.contains("PostgreSQL\\16"), script + " must not use other client versions");
        }
    }

    @Test
    @DisplayName("No database is dropped, renamed, overwritten or recreated, and nothing but environment variables is removed")
    public void testNoDatabaseIsDroppedRenamedOrOverwritten() throws IOException {
        Pattern forbidden = Pattern.compile("(?i)(dropdb|DROP\\s+DATABASE|ALTER\\s+DATABASE|RENAME\\s+TO|--clean|--if-exists|--create\\b)");
        for (Path script : SCRIPTS) {
            Matcher m = forbidden.matcher(code(script));
            assertFalse(m.find(), () -> script + " must not contain " + m.group());
            assertFalse(code(script).contains("'-C'"), script + " must not pass pg_restore -C (recreate the source database)");
            Matcher removals = Pattern.compile("Remove-Item\\s+(\\S+)").matcher(code(script));
            while (removals.find()) {
                assertTrue(removals.group(1).startsWith("Env:"), script + " may only remove environment variables, found: " + removals.group());
            }
        }
    }

    @Test
    @DisplayName("Backup: every access to the live database happens inside the read-only session")
    public void testBackupReadsLiveDatabaseOnlyInReadOnlySession() throws IOException {
        assertAssignment(code(COMMON), "$script:ReadOnlyOptions", "'-c default_transaction_read_only=on'");
        assertTrue(code(COMMON).contains("function Use-ReadOnlySession { $env:PGOPTIONS = $script:ReadOnlyOptions }"));

        String body = code(BACKUP);
        int readOnlyStart = indexOrFail(body, "Use-ReadOnlySession", "backup");
        int readOnlyEnd = indexOrFail(body, "Use-DefaultSession", "backup");
        assertEquals(readOnlyStart, body.lastIndexOf("Use-ReadOnlySession"), "Exactly one read-only session");
        assertEquals(readOnlyEnd, body.lastIndexOf("Use-DefaultSession"), "Exactly one switch back to the default session");
        assertTrue(readOnlyStart < readOnlyEnd);

        Matcher liveAccess = Pattern.compile("'-d', \\$LiveDb|-Database \\$LiveDb").matcher(body);
        int accesses = 0;
        while (liveAccess.find()) {
            accesses++;
            assertTrue(liveAccess.start() > readOnlyStart && liveAccess.start() < readOnlyEnd,
                    "Live database access outside the read-only session: " + liveAccess.group());
        }
        assertEquals(3, accesses, "Expected exactly 3 live reads: data dump, schema dump, verification fingerprint");
    }

    @Test
    @DisplayName("Writes (createdb, pg_restore) only target the newly named copy; the restore script never connects to live")
    public void testWritesOnlyTargetTheNewCopy() throws IOException {
        String backup = code(BACKUP);
        assertTrue(backup.contains("Invoke-Pg 'createdb.exe' ($Conn + @($checkDb))"));
        assertTrue(backup.contains("'-d', $checkDb, '--no-owner'"), "Backup pg_restore must target the check database");
        assertAssignment(backup, "$checkDb", "\"wb_restore_check_$stamp\"");
        assertAssignment(code(RESTORE), "$restoredDb", "'wb_restored_' + (Get-Date -Format 'yyyyMMdd_HHmmss')");

        String restore = code(RESTORE);
        assertTrue(restore.contains("Invoke-Pg 'createdb.exe' ($Conn + @($restoredDb))"));
        assertTrue(restore.contains("'-d', $restoredDb, '--no-owner'"), "Restore pg_restore must target the new database");
        assertFalse(Pattern.compile("'-d', \\$LiveDb|-Database \\$LiveDb").matcher(restore).find(),
                "The restore script must never connect to the live database");

        for (Path script : List.of(BACKUP, RESTORE)) {
            assertEquals(1, Pattern.compile("createdb\\.exe").matcher(code(script)).results().count(), script + ": one createdb");
            assertEquals(1, Pattern.compile("Invoke-PgRestoreLogged").matcher(code(script)).results().count(), script + ": one pg_restore");
        }
    }

    @Test
    @DisplayName("Output paths and files are validated before any folder or file is created")
    public void testOutputsAreValidatedBeforeCreation() throws IOException {
        String backup = code(BACKUP);
        int insideCheck = indexOrFail(backup, "Test-IsInsideFolder -Path $outputPath -Folder $projectRoot", "backup");
        int fileCheck = indexOrFail(backup, "Assert-FilesDoNotExist @($dumpFile, $liveSchemaFile, $liveFingerprint, $checkSchemaFile, $restoreErrorLog)", "backup");
        int mkdir = indexOrFail(backup, "New-Item -ItemType Directory", "backup");
        int firstConnection = indexOrFail(backup, "Save-PgEnvironment", "backup");
        assertTrue(insideCheck < mkdir && fileCheck < mkdir && mkdir < firstConnection,
                "Validate the folder and every output file before creating the folder or connecting");
        assertEquals(1, Pattern.compile("New-Item").matcher(backup).results().count(), "Only the validated output folder is created");
        assertFalse(backup.contains("-Force"), "Nothing may be created or written with -Force");

        String restore = code(RESTORE);
        int restoreCheck = indexOrFail(restore, "Assert-FilesDoNotExist @($restoredSchemaFile, $restoreErrorLog)", "restore");
        assertTrue(restoreCheck < indexOrFail(restore, "Save-PgEnvironment", "restore"));
        assertFalse(restore.contains("New-Item") || restore.contains("-Force"));

        String common = code(COMMON);
        assertTrue(common.contains("GetUnresolvedProviderPathFromPSPath"), "Paths are resolved without creating them");
        assertTrue(common.contains("(Resolve-FullPath $Folder) + '\\'"), "The inside-folder check needs a separator boundary");
    }

    @Test
    @DisplayName("Every created file and database is registered before creation and reported on failure; errors are re-thrown")
    public void testFailuresReportWhatWasLeftBehind() throws IOException {
        for (Path script : List.of(BACKUP, RESTORE)) {
            String body = code(script);
            int createdb = indexOrFail(body, "Invoke-Pg 'createdb.exe'", script.toString());
            assertTrue(body.lastIndexOf("Register-Database", createdb) >= 0, script + ": register the new database before createdb");

            Matcher catchBlock = Pattern.compile("(?s)\\} catch \\{(.*?)\\} finally \\{").matcher(body);
            assertTrue(catchBlock.find(), script + " must have try / catch / finally");
            String handler = catchBlock.group(1);
            assertTrue(handler.contains("Write-ArtifactReport"), script + ": a failure must list what was left behind");
            assertTrue(Pattern.compile("(?m)^\\s*throw\\s*$").matcher(handler).find(), script + ": the failure must be re-thrown, not swallowed");
        }
        String backup = code(BACKUP);
        for (String file : List.of("$dumpFile", "$liveSchemaFile", "$liveFingerprint", "$restoreErrorLog", "$checkSchemaFile")) {
            assertTrue(backup.contains("Register-File " + file), "backup must register " + file + " before creating it");
        }
        assertTrue(backup.indexOf("Register-File $dumpFile") < backup.indexOf("'-Fc', '-f', $dumpFile"));
        assertTrue(backup.indexOf("Register-File $liveFingerprint") < backup.indexOf("WriteAllText($liveFingerprint"));
    }

    @Test
    @DisplayName("PGPASSWORD and PGOPTIONS are saved before and restored after, on success and failure")
    public void testEnvironmentIsRestored() throws IOException {
        String common = code(COMMON);
        assertAssignment(common, "$script:SavedPgPassword", "$env:PGPASSWORD");
        assertAssignment(common, "$script:SavedPgOptions", "$env:PGOPTIONS");
        assertTrue(common.contains("if ($null -eq $script:SavedPgPassword) { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue } else { $env:PGPASSWORD = $script:SavedPgPassword }"));
        assertTrue(common.contains("if ($null -eq $script:SavedPgOptions)  { Remove-Item Env:PGOPTIONS  -ErrorAction SilentlyContinue } else { $env:PGOPTIONS  = $script:SavedPgOptions }"));
        for (Path script : List.of(BACKUP, RESTORE)) {
            String body = code(script);
            int save = indexOrFail(body, "Save-PgEnvironment", script.toString());
            int tryAt = indexOrFail(body, "try {", script.toString());
            int setPassword = indexOrFail(body, "$env:PGPASSWORD = $env:DB_PASSWORD", script.toString());
            assertTrue(save < tryAt && tryAt < setPassword, script + ": save the environment, then set the password inside try");
            Matcher finallyBlock = Pattern.compile("(?s)\\} finally \\{\\s*Restore-PgEnvironment\\s*\\}\\s*$").matcher(body.trim());
            assertTrue(finallyBlock.find(), script + " must restore the environment in finally");
        }
    }

    @Test
    @DisplayName("Verification is exact and success is only printed after both checks; malformed input is refused")
    public void testVerificationCannotReportFalseSuccess() throws IOException {
        String common = code(COMMON);
        assertTrue(common.contains("[string]::Equals($Expected, $Actual, [System.StringComparison]::Ordinal)"), "Exact, case-sensitive comparison");
        assertTrue(common.contains("Assert-FingerprintFormat -Fingerprint $fingerprint -Source $Database"), "Computed fingerprints are format-checked");
        assertTrue(common.contains("is missing or empty"), "Empty schema dumps are refused");
        assertTrue(common.contains("does not define public.$table"), "Incomplete schema dumps are refused");

        for (Path script : List.of(BACKUP, RESTORE)) {
            String body = code(script);
            String success = script.equals(BACKUP) ? "BACKUP VERIFIED" : "RESTORE VERIFIED";
            int successAt = indexOrFail(body, success, script.toString());
            Matcher checks = Pattern.compile("Assert-SameText").matcher(body);
            int count = 0;
            while (checks.find()) {
                count++;
                assertTrue(checks.start() < successAt, script + ": every comparison must come before the success message");
            }
            assertEquals(2, count, script + ": exactly two exact comparisons (schema and fingerprint)");
            assertFalse(Pattern.compile("\\$(actual|expected|checkFp|liveFp|restoredFp)\\s+-(ne|eq)\\s").matcher(body).find(),
                    script + " must not use PowerShell's case-insensitive -ne / -eq for verification");
            assertFalse(body.contains("Compare-Object"), script + ": Compare-Object may only be used to display differences");
        }
        assertTrue(code(RESTORE).contains("Assert-FingerprintFormat -Fingerprint $expectedFp -Source $expectedPrintFile"),
                "The recorded fingerprint must be format-checked before it is trusted");
    }

    @Test
    @DisplayName("pg_restore messages go to a private log file; nothing prints passwords")
    public void testNoSecretsOrRowDataInOutput() throws IOException {
        String common = code(COMMON);
        assertTrue(common.contains("-RedirectStandardError $ErrorLog"), "pg_restore stderr must go to the log file");
        for (Path script : List.of(BACKUP, RESTORE)) {
            assertFalse(code(script).contains("'pg_restore.exe'"), script + " must use Invoke-PgRestoreLogged, never echo pg_restore output");
        }
        Pattern printsSecret = Pattern.compile("(?im)^.*\\bWrite-(Host|Output|Warning|Verbose|Information)\\b.*PASSWORD.*$");
        for (Path script : SCRIPTS) {
            Matcher m = printsSecret.matcher(code(script));
            assertFalse(m.find(), () -> script + " must not print password variables: " + m.group());
        }
    }

    @Test
    @DisplayName("fingerprint.sql covers the four application tables with counts and MD5 only; the scripts cover every other table")
    public void testFingerprintCoversApplicationTablesWithoutRowContents() throws IOException {
        String fingerprint = sql(DB_TOOLS.resolve("fingerprint.sql"));
        for (String table : List.of("assignments", "staff", "tasks", "users")) {
            String line = "SELECT '" + table + " ' || count(*) || ' ' || md5(coalesce(string_agg(t::text, '|' ORDER BY t.id), ''))";
            assertTrue(fingerprint.contains(line), "fingerprint.sql must hash " + table + " as count + MD5 only");
            assertEquals(1, Pattern.compile("FROM public\\." + table + " t\\b").matcher(fingerprint).results().count());
        }
        assertFalse(fingerprint.contains("SELECT *"));
        assertEquals(4, Pattern.compile("(?i)\\bFROM public\\.").matcher(fingerprint).results().count(), "Only the four application tables are read");

        String common = code(COMMON);
        assertAssignment(common, "$script:AppTables", "@('assignments', 'staff', 'tasks', 'users')");
        assertAssignment(common, "$script:KnownTables", "@('assignments', 'flyway_schema_history', 'staff', 'tasks', 'users')");
        assertTrue(common.contains("is not covered by the fingerprint"), "Any other table in public must stop the verification");
        assertTrue(common.contains("md5(query_to_xml('SELECT * FROM public.flyway_schema_history ORDER BY installed_rank'"),
                "Flyway history rows must be fingerprinted (MD5 only) when present");
    }

    // ── Schema normalization of the two equivalent enum CHECK forms (P4a-2 fix) ──

    private static final Path NORMALIZATION_TESTS = DB_TOOLS.resolve("tests").resolve("SchemaNormalization.Tests.ps1");

    /** Exact lines from the failed P4a-2 run: live database (form A) and its restored copy (form B). */
    private static final String LIVE_FORM =
            "    CONSTRAINT staff_availability_check CHECK (((availability)::text = ANY ((ARRAY['AVAILABLE'::character varying, 'UNAVAILABLE'::character varying])::text[]))),";
    private static final String RESTORED_FORM =
            "    CONSTRAINT staff_availability_check CHECK (((availability)::text = ANY (ARRAY[('AVAILABLE'::character varying)::text, ('UNAVAILABLE'::character varying)::text]))),";

    /** The two regexes exactly as written in $script:EnumCheckPatterns (PowerShell '' unescaped to '). */
    private static List<Pattern> enumCheckPatterns() throws IOException {
        Matcher block = Pattern.compile("(?s)\\$script:EnumCheckPatterns = @\\((.*?)\\n\\)").matcher(read(COMMON));
        assertTrue(block.find(), "$script:EnumCheckPatterns must be defined");
        Matcher entries = Pattern.compile("(?m)^\\s*'((?:[^']|'')*)',?\\s*$").matcher(block.group(1));
        List<Pattern> patterns = new java.util.ArrayList<>();
        while (entries.find()) {
            patterns.add(Pattern.compile(entries.group(1).replace("''", "'")));
        }
        return patterns;
    }

    /** name|column|ordered values|comma captured by the first matching pattern, or null when none matches. */
    private static String canonical(List<Pattern> patterns, String line) {
        for (Pattern p : patterns) {
            Matcher m = p.matcher(line);
            if (m.matches()) {
                List<String> values = Pattern.compile("'[A-Z0-9_]+'").matcher(m.group("values")).results().map(r -> r.group()).toList();
                return m.group("indent") + "|" + m.group("name") + "|" + m.group("column") + "|" + values + "|" + m.group("comma");
            }
        }
        return null;
    }

    @Test
    @DisplayName("Schema normalization is narrow: two anchored, case-sensitive patterns; everything else unchanged")
    public void testSchemaNormalizationIsNarrow() throws IOException {
        List<Pattern> patterns = enumCheckPatterns();
        assertEquals(2, patterns.size(), "Exactly two supported enum CHECK forms");
        for (Pattern p : patterns) {
            assertTrue(p.pattern().startsWith("^(?<indent>[ \\t]*)CONSTRAINT ") && p.pattern().endsWith("(?<comma>,?)$"),
                    "Each pattern must match a whole line: " + p.pattern());
            assertFalse(p.pattern().contains("(?i)"), "Patterns must be case-sensitive");
        }

        String common = code(COMMON);
        Matcher convert = Pattern.compile("(?s)function ConvertTo-CanonicalEnumCheck \\{(.*?)\\n\\}").matcher(common);
        assertTrue(convert.find());
        assertTrue(convert.group(1).contains("[regex]::Match($Line, $pattern)"), "Use .NET Regex (case-sensitive), not -match");
        assertTrue(convert.group(1).trim().endsWith("return $Line"), "Lines that match no pattern must be returned unchanged");

        Matcher normalize = Pattern.compile("(?s)function Get-NormalizedSchema \\{(.*?)\\n\\}").matcher(common);
        assertTrue(normalize.find());
        String body = normalize.group(1);
        assertTrue(body.contains("ForEach-Object { ConvertTo-CanonicalEnumCheck $_ }"), "Enum CHECK lines must be canonicalized");
        assertFalse(Pattern.compile("-c?replace|\\.Replace\\(|-split").matcher(body).find(),
                "Get-NormalizedSchema must not rewrite anything except via ConvertTo-CanonicalEnumCheck");
        assertEquals(1, Pattern.compile("Where-Object").matcher(body).results().count(), "Only the \\restrict filter may drop lines");
    }

    @Test
    @DisplayName("The script's own patterns treat the observed live and restored forms as equal and catch real changes")
    public void testEnumCheckPatternsOnObservedForms() throws IOException {
        List<Pattern> patterns = enumCheckPatterns();
        assertTrue(patterns.get(0).matcher(LIVE_FORM).matches(), "Form A (live) must match the first pattern");
        assertFalse(patterns.get(1).matcher(LIVE_FORM).matches());
        assertTrue(patterns.get(1).matcher(RESTORED_FORM).matches(), "Form B (restored) must match the second pattern");
        assertFalse(patterns.get(0).matcher(RESTORED_FORM).matches());

        String live = canonical(patterns, LIVE_FORM);
        assertEquals("    |staff_availability_check|availability|['AVAILABLE', 'UNAVAILABLE']|,", live);
        assertEquals(live, canonical(patterns, RESTORED_FORM), "Equivalent forms must canonicalize identically");

        assertNotEquals(live, canonical(patterns, RESTORED_FORM.replace("'UNAVAILABLE'", "'UNAVAILABLX'")), "Changed value must differ");
        assertNotEquals(live, canonical(patterns, RESTORED_FORM.replace("staff_availability_check", "staff_availability_chk")), "Changed name must differ");
        assertNotEquals(live, canonical(patterns, RESTORED_FORM.replace("(((availability)", "(((department)")), "Changed column must differ");
        assertNull(canonical(patterns, RESTORED_FORM.replace("'AVAILABLE'", "'available'")), "Lowercase values are not recognised");
        assertNull(canonical(patterns, LIVE_FORM + " -- note"), "Extra trailing text is not recognised");
        assertNull(canonical(patterns, "    CONSTRAINT tasks_duration_check CHECK ((estimated_duration > 0)),"), "Other CHECKs are not recognised");
    }

    @Test
    @DisplayName("SchemaNormalization.Tests.ps1 passes when run with Windows PowerShell (skipped elsewhere)")
    public void testSchemaNormalizationPureFunctionTestsPass() throws Exception {
        assertTrue(Files.exists(NORMALIZATION_TESTS));
        for (byte b : Files.readAllBytes(NORMALIZATION_TESTS)) {
            assertTrue(b >= 0, "SchemaNormalization.Tests.ps1 must be ASCII only");
        }
        String test = code(NORMALIZATION_TESTS);
        assertFalse(Pattern.compile("(?i)(psql|pg_dump|pg_restore|createdb|dropdb|Invoke-Pg|Get-QueryResult|Get-VerificationFingerprint|Assert-PgTools)").matcher(test).find(),
                "The pure-function tests must not call database tools or connect to anything");
        assertTrue(test.contains("Remove-Item -LiteralPath $tempDir -Recurse -Force"), "It may only remove its own temp folder");

        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("windows");
        org.junit.jupiter.api.Assumptions.assumeTrue(windows, "Windows PowerShell is only available on Windows");

        Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-File", NORMALIZATION_TESTS.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS), "PowerShell tests timed out");
        assertEquals(0, process.exitValue(), "SchemaNormalization.Tests.ps1 failed:\n" + output);
        assertTrue(output.contains("ALL PASSED"), output);
        assertFalse(output.contains("FAIL "), output);
    }

    @Test
    @DisplayName("preflight.sql reads application tables only through counts")
    public void testPreflightPrintsNoRowContents() throws IOException {
        String preflight = sql(DB_TOOLS.resolve("preflight.sql"));
        assertFalse(preflight.contains("SELECT *"));
        Matcher reads = Pattern.compile("(?im)^.*\\bFROM\\s+public\\.(users|staff|tasks|assignments)\\b.*$").matcher(preflight);
        int count = 0;
        while (reads.find()) {
            count++;
            String line = reads.group();
            assertTrue(line.contains("count(") || line.contains("SELECT 1 FROM public."),
                    "preflight.sql may only count rows in application tables: " + line.trim());
        }
        assertTrue(count > 0);
        assertTrue(read(DB_TOOLS.resolve("preflight.sql")).contains("$savedPassword = $env:PGPASSWORD"),
                "The documented run command must restore previous environment variables");
    }
}
