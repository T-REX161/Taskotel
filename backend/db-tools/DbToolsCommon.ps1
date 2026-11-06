# Shared helpers for backup-and-verify.ps1 and restore-to-new-db.ps1.
# Dot-sourced by those scripts; it only defines settings and functions and does nothing when loaded.
#
# Safety rules implemented here:
# - PostgreSQL 18 client tools only, from C:\Program Files\PostgreSQL\18\bin.
# - Reads of the live database run with default_transaction_read_only=on (Use-ReadOnlySession).
# - PGPASSWORD and PGOPTIONS are saved and restored exactly (Save-/Restore-PgEnvironment).
# - pg_restore messages go to a private log file, never the console: PostgreSQL error context
#   for a failed data load can include the failing row (emails, password hashes).
# - Comparisons are exact (ordinal, case-sensitive) and refuse empty or malformed input, so a
#   failed or empty verification can never be reported as success.
# - Nothing here drops, renames or overwrites a database or file.

Set-StrictMode -Version Latest

$script:PgBin           = 'C:\Program Files\PostgreSQL\18\bin'
$script:LiveDb          = 'workload_balancer'
$script:AppTables       = @('assignments', 'departments', 'notification_preferences', 'staff', 'support_assignments', 'tasks', 'users')
$script:KnownTables     = @('assignments', 'departments', 'flyway_schema_history', 'notification_preferences', 'staff', 'support_assignments', 'tasks', 'users')
$script:ReadOnlyOptions = '-c default_transaction_read_only=on'
$script:DbHost          = if ($env:DB_HOST) { $env:DB_HOST } else { 'localhost' }
$script:DbPort          = if ($env:DB_PORT) { $env:DB_PORT } else { '5432' }
$script:DbUser          = if ($env:DB_USER) { $env:DB_USER } else { 'postgres' }
$script:Conn            = @('-h', $script:DbHost, '-p', $script:DbPort, '-U', $script:DbUser, '-w')
$script:FingerprintFile = Join-Path $PSScriptRoot 'fingerprint.sql'
$script:Artifacts       = New-Object System.Collections.Generic.List[object]
$script:SavedPgPassword = $null
$script:SavedPgOptions  = $null

# -- Tools ---------------------------------------------------------------------

function Assert-PgTools {
    foreach ($exe in @('pg_dump.exe', 'pg_restore.exe', 'psql.exe', 'createdb.exe')) {
        $path = Join-Path $script:PgBin $exe
        if (-not (Test-Path -LiteralPath $path)) { throw "PostgreSQL 18 client tool not found: $path" }
    }
    foreach ($exe in @('pg_dump.exe', 'pg_restore.exe', 'psql.exe')) {
        $version = & (Join-Path $script:PgBin $exe) --version
        if ($LASTEXITCODE -ne 0 -or ("$version" -notmatch '\(PostgreSQL\) 18\.')) {
            throw "$exe must be a PostgreSQL 18 client (found: $version)."
        }
    }
}

# -- Environment variables ---------------------------------------------------------

function Save-PgEnvironment {
    $script:SavedPgPassword = $env:PGPASSWORD
    $script:SavedPgOptions  = $env:PGOPTIONS
}

function Restore-PgEnvironment {
    if ($null -eq $script:SavedPgPassword) { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue } else { $env:PGPASSWORD = $script:SavedPgPassword }
    if ($null -eq $script:SavedPgOptions)  { Remove-Item Env:PGOPTIONS  -ErrorAction SilentlyContinue } else { $env:PGOPTIONS  = $script:SavedPgOptions }
}

# Every connection made while this is in effect runs read-only.
function Use-ReadOnlySession { $env:PGOPTIONS = $script:ReadOnlyOptions }

# Connections to the new copy use no inherited options (a leftover PGOPTIONS must not affect them).
function Use-DefaultSession { Remove-Item Env:PGOPTIONS -ErrorAction SilentlyContinue }

# -- Paths ---------------------------------------------------------------------------

function Resolve-FullPath {
    param([Parameter(Mandatory = $true)][string]$Path)
    # Resolves relative to the current PowerShell location without creating anything
    return $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Path).TrimEnd('\')
}

function Test-IsInsideFolder {
    param([string]$Path, [string]$Folder)
    $p = (Resolve-FullPath $Path) + '\'
    $f = (Resolve-FullPath $Folder) + '\'
    return $p.StartsWith($f, [System.StringComparison]::OrdinalIgnoreCase)
}

function Assert-FilesDoNotExist {
    param([string[]]$Paths)
    foreach ($path in $Paths) {
        if (Test-Path -LiteralPath $path) { throw "Refusing to overwrite existing file $path. Nothing was changed." }
    }
}

# -- Artifacts this run creates (reported, never deleted) -------------------------------

function Register-File     { param([string]$Path) $script:Artifacts.Add([pscustomobject]@{ Kind = 'file'; Name = $Path }) }
function Register-Database { param([string]$Name) $script:Artifacts.Add([pscustomobject]@{ Kind = 'database'; Name = $Name }) }

function Write-ArtifactReport {
    param([string]$Heading)
    Write-Host $Heading
    $reported = 0
    foreach ($item in $script:Artifacts) {
        if ($item.Kind -eq 'file') {
            if (Test-Path -LiteralPath $item.Name) { Write-Host "  - file:     $($item.Name)"; $reported++ }
        } else {
            Write-Host "  - database: $($item.Name) (created by this run if createdb succeeded; it is never dropped automatically)"
            $reported++
        }
    }
    if ($reported -eq 0) { Write-Host '  (none)' }
    Write-Host '  Nothing was dropped, renamed or overwritten. Dump files and logs can contain private data.'
}

# -- Running PostgreSQL tools ------------------------------------------------------------

function Invoke-Pg {
    param([string]$Exe, [string[]]$ArgList)
    & (Join-Path $script:PgBin $Exe) @ArgList
    if ($LASTEXITCODE -ne 0) { throw "$Exe exited with code $LASTEXITCODE." }
}

function ConvertTo-NativeArgument {
    param([string]$Value)
    if ($Value -notmatch '[\s"]') { return $Value }
    return '"' + ($Value -replace '"', '\"') + '"'
}

# pg_restore's messages go to a private log file: PostgreSQL error context for a failed data load
# can quote the failing row, so they are never echoed to the console.
function Invoke-PgRestoreLogged {
    param([string[]]$ArgList, [string]$ErrorLog)
    $arguments = ($ArgList | ForEach-Object { ConvertTo-NativeArgument $_ }) -join ' '
    $process = Start-Process -FilePath (Join-Path $script:PgBin 'pg_restore.exe') -ArgumentList $arguments `
        -NoNewWindow -Wait -PassThru -RedirectStandardError $ErrorLog
    if ($null -eq $process.ExitCode -or $process.ExitCode -ne 0) {
        throw "pg_restore failed (exit code: $($process.ExitCode)). Its messages are in $ErrorLog and are not shown here because they can include row data; keep that file private."
    }
}

function Get-QueryResult {
    param([string]$Database, [string]$Sql, [string]$File)
    # Windows PowerShell 5.1 drops double quotes from arguments it passes to psql.exe, which silently changes the
    # query (an identifier like "C" becomes C). Inline SQL must therefore use single quotes only; anything that needs
    # a double quote belongs in a .sql file passed with -File.
    if ($Sql -and $Sql.Contains('"')) { throw 'Inline SQL passed to psql must not contain double quotes (PowerShell would strip them); use a .sql file.' }
    $source = if ($File) { @('-f', $File) } else { @('-c', $Sql) }
    $out = & (Join-Path $script:PgBin 'psql.exe') @script:Conn -d $Database -X -At -v ON_ERROR_STOP=1 @source
    if ($LASTEXITCODE -ne 0) { throw "psql failed on database $Database." }
    return ((@($out) -join "`n").Trim())
}

# -- Verification ------------------------------------------------------------------------

# Enum CHECK constraints created by Hibernate are printed in one of two equivalent forms. A
# dump/restore round trip turns the first into the second, because PostgreSQL re-parses the
# dumped expression and stores the casts differently:
#   A: CHECK (((col)::text = ANY ((ARRAY['X'::character varying, ...])::text[])))
#   B: CHECK (((col)::text = ANY (ARRAY[('X'::character varying)::text, ...])))
# Only whole lines matching exactly one of these patterns (case-sensitive, enum values made of
# A-Z, 0-9 and _) are rewritten, into a canonical text that keeps the indentation, constraint
# name, column, ordered value list and trailing comma. Every other line is returned unchanged.
$script:EnumCheckPatterns = @(
    '^(?<indent>[ \t]*)CONSTRAINT (?<name>[a-z_][a-z0-9_]*) CHECK \(\(\((?<column>[a-z_][a-z0-9_]*)\)::text = ANY \(\(ARRAY\[(?<values>''[A-Z0-9_]+''::character varying(?:, ''[A-Z0-9_]+''::character varying)*)\]\)::text\[\]\)\)\)(?<comma>,?)$',
    '^(?<indent>[ \t]*)CONSTRAINT (?<name>[a-z_][a-z0-9_]*) CHECK \(\(\((?<column>[a-z_][a-z0-9_]*)\)::text = ANY \(ARRAY\[(?<values>\(''[A-Z0-9_]+''::character varying\)::text(?:, \(''[A-Z0-9_]+''::character varying\)::text)*)\]\)\)\)(?<comma>,?)$'
)

function ConvertTo-CanonicalEnumCheck {
    param([string]$Line)
    foreach ($pattern in $script:EnumCheckPatterns) {
        $match = [regex]::Match($Line, $pattern)   # .NET Regex: case-sensitive by default
        if ($match.Success) {
            $values = @([regex]::Matches($match.Groups['values'].Value, "'[A-Z0-9_]+'") | ForEach-Object { $_.Value })
            return ('{0}CONSTRAINT {1} CHECK [enum] {2} IN ({3}){4}' -f
                $match.Groups['indent'].Value, $match.Groups['name'].Value, $match.Groups['column'].Value,
                ($values -join ', '), $match.Groups['comma'].Value)
        }
    }
    return $Line
}

# Schema-only dump text without PostgreSQL 18 \restrict lines (they carry a random key per dump),
# with the two equivalent enum CHECK forms above made identical. Nothing else is normalized.
# Refuses empty dumps and dumps missing an application table.
function Get-NormalizedSchema {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path) -or (Get-Item -LiteralPath $Path).Length -eq 0) {
        throw "Schema dump $Path is missing or empty."
    }
    $lines = Get-Content -LiteralPath $Path |
        Where-Object { $_ -notmatch '^\\(un)?restrict ' } |
        ForEach-Object { ConvertTo-CanonicalEnumCheck $_ }
    $text = ($lines -join "`n").Trim()
    foreach ($table in $script:AppTables) {
        if ($text -cnotmatch "CREATE TABLE public\.$table \(") { throw "Schema dump $Path does not define public.$table." }
    }
    return $text
}

function Assert-SameText {
    param([string]$Expected, [string]$Actual, [string]$What)
    if (-not [string]::Equals($Expected, $Actual, [System.StringComparison]::Ordinal)) {
        throw "$What does not match exactly. Do not rely on this copy."
    }
}

function Show-SchemaDifference {
    param([string]$Expected, [string]$Actual)
    # Schema-only DDL; contains no row data
    Compare-Object ($Expected -split "`n") ($Actual -split "`n") | Select-Object -First 40 |
        Format-Table -AutoSize | Out-String | Write-Host
}

# Throws unless the text is exactly nine lines: the seven application-table lines (count + MD5) in the
# fixed order of $script:AppTables, the table list, and the Flyway history line. The table list must
# contain every application table once and nothing outside $script:KnownTables. Empty, incomplete or
# malformed output can therefore never compare as "equal".
function Assert-FingerprintFormat {
    param([string]$Fingerprint, [string]$Source)
    $expectedLines = $script:AppTables.Count + 2
    $lines = @($Fingerprint -split "`n")
    if ($lines.Count -ne $expectedLines) { throw "Fingerprint from $Source has $($lines.Count) lines instead of $expectedLines; refusing to compare." }
    for ($i = 0; $i -lt $script:AppTables.Count; $i++) {
        if ($lines[$i] -cnotmatch ('^' + $script:AppTables[$i] + ' \d+ [0-9a-f]{32}$')) {
            throw "Fingerprint line $($i + 1) from $Source is malformed; refusing to compare."
        }
    }
    $tableLine = $lines[$script:AppTables.Count]
    if ($tableLine -cnotmatch '^tables [a-z_]+(,[a-z_]+)*$') { throw "Table list from $Source is malformed; refusing to compare." }
    $listed = @($tableLine.Substring('tables '.Length) -split ',')
    if (@($listed | Select-Object -Unique).Count -ne $listed.Count) { throw "Table list from $Source repeats a table; refusing to compare." }
    foreach ($table in $script:AppTables) {
        if ($listed -cnotcontains $table) { throw "Table list from $Source is missing public.$table; refusing to compare." }
    }
    foreach ($table in $listed) {
        if ($script:KnownTables -cnotcontains $table) { throw "Table list from $Source contains public.$table, which the fingerprint does not cover; refusing to compare." }
    }
    if ($lines[$script:AppTables.Count + 1] -cnotmatch '^flyway_schema_history (absent|[0-9a-f]{32})$') { throw "Flyway history line from $Source is malformed; refusing to compare." }
}

# -- SHA-256 sidecar for a dump file ---------------------------------------------------------
# The sidecar wb_<stamp>.dump.sha256 holds one line, "<64 hex chars>  <dump file name>" (the format
# sha256sum uses), written when the dump is made and checked again before the dump is trusted.

function Get-DumpSha256 {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Dump file not found: $Path" }
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Format-Sha256Sidecar {
    param([string]$Hash, [string]$DumpFileName)
    if ($Hash -cnotmatch '^[0-9a-f]{64}$') { throw 'Refusing to record a malformed SHA-256 value.' }
    if ($DumpFileName -cnotmatch '^wb_\d{8}_\d{6}\.dump$') { throw "Refusing to record a checksum for unexpected file name '$DumpFileName'." }
    return "$Hash  $DumpFileName"
}

# Returns the recorded hash after checking that the sidecar is exactly one well-formed line naming this dump.
function Read-Sha256Sidecar {
    param([string]$SidecarPath, [string]$DumpFileName)
    if (-not (Test-Path -LiteralPath $SidecarPath -PathType Leaf)) { throw "Checksum file $SidecarPath is missing." }
    $text = ((Get-Content -LiteralPath $SidecarPath -Raw) -replace "`r`n", "`n").Trim()
    if (-not $text) { throw "Checksum file $SidecarPath is empty." }
    $match = [regex]::Match($text, '^(?<hash>[0-9a-f]{64})  (?<name>wb_\d{8}_\d{6}\.dump)$')
    if (-not $match.Success) { throw "Checksum file $SidecarPath is malformed (expected one line: <sha256>  <dump file name>)." }
    if (-not [string]::Equals($match.Groups['name'].Value, $DumpFileName, [System.StringComparison]::Ordinal)) {
        throw "Checksum file $SidecarPath names $($match.Groups['name'].Value), not $DumpFileName."
    }
    return $match.Groups['hash'].Value
}

# Throws unless the dump's actual SHA-256 equals the recorded one.
function Assert-DumpMatchesSidecar {
    param([string]$DumpPath, [string]$SidecarPath)
    $recorded = Read-Sha256Sidecar -SidecarPath $SidecarPath -DumpFileName (Split-Path $DumpPath -Leaf)
    $actual = Get-DumpSha256 $DumpPath
    if (-not [string]::Equals($recorded, $actual, [System.StringComparison]::Ordinal)) {
        throw "SHA-256 of $DumpPath ($actual) does not match the recorded $recorded. The dump has changed since it was verified."
    }
    return $actual
}

# Application-data fingerprint (fingerprint.sql) plus: the list of tables in public, which must
# contain every application table and nothing the fingerprint does not cover, and an MD5 of the
# Flyway history rows when that table exists. Prints counts, table names and hashes only.
function Get-VerificationFingerprint {
    param([string]$Database)
    # The table list is checked first, so a missing or unexpected table is reported by name instead of as a failed query
    $tables  = Get-QueryResult -Database $Database -Sql "SELECT coalesce(string_agg(relname::text, ',' ORDER BY convert_to(relname::text, 'UTF8')), '') FROM pg_class WHERE relnamespace = 'public'::regnamespace AND relkind IN ('r', 'p')"
    $tableList = @($tables -split ',' | Where-Object { $_ })
    foreach ($table in $script:AppTables) {
        if ($tableList -cnotcontains $table) { throw "Table public.$table is missing in $Database." }
    }
    foreach ($table in $tableList) {
        if ($script:KnownTables -cnotcontains $table) {
            throw "Table public.$table in $Database is not covered by the fingerprint. Review before relying on this copy."
        }
    }
    $appData = Get-QueryResult -Database $Database -File $script:FingerprintFile
    $history = Get-QueryResult -Database $Database -Sql "SELECT CASE WHEN to_regclass('public.flyway_schema_history') IS NULL THEN 'flyway_schema_history absent' ELSE 'flyway_schema_history ' || md5(query_to_xml('SELECT * FROM public.flyway_schema_history ORDER BY installed_rank', true, false, '')::text) END"
    $fingerprint = (@($appData, "tables $tables", $history) -join "`n").Trim()
    Assert-FingerprintFormat -Fingerprint $fingerprint -Source $Database
    return $fingerprint
}
