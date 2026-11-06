# Rehearsal-database safety rules for create-rehearsal-dbs.ps1. Dot-sourced after DbToolsCommon.ps1.
# Pure functions only: no database connections and no external programs, so they can be tested alone.
#
# - Rehearsal databases are always named wb_rehearsal_<yyyyMMdd_HHmmss>_<role>, role in
#   fresh | collide | copy | dupe (the same rule PostgresMigrationIT enforces).
# - The live database, both existing restore-check databases, any wb_restore_check_* / wb_restored_*
#   database and the PostgreSQL system databases are refused even if a name ever matched the pattern.
# - Only a verified backup is accepted: the dump's SHA-256 must equal the one recorded in its
#   wb_<stamp>.dump.sha256 sidecar, and its side files must show a completed, clean verification
#   (identical live and restored schema, valid seven-table fingerprint, empty restore log) of a
#   pre-Flyway database.

Set-StrictMode -Version Latest

$script:RehearsalRoles       = @('fresh', 'collide', 'copy', 'dupe')
$script:RehearsalNamePattern = '^wb_rehearsal_\d{8}_\d{6}_(fresh|copy|dupe|collide)$'
$script:ProtectedDatabases   = @('workload_balancer', 'wb_restore_check_20261020_140921', 'wb_restore_check_20261020_142245', 'postgres', 'template0', 'template1')
$script:ProtectedPrefixes    = @('wb_restore_check_', 'wb_restored_')

# True only when the pattern matches the WHOLE value. ECMAScript mode makes \d ASCII-only, and the
# index/length check closes .NET's "$ also matches before a trailing newline" gap.
function Test-WholeMatch {
    param([string]$Value, [string]$Pattern)
    $match = [regex]::Match($Value, $Pattern, [System.Text.RegularExpressions.RegexOptions]::ECMAScript)
    return ($match.Success -and $match.Index -eq 0 -and $match.Length -eq $Value.Length)
}

# Throws unless the name is a rehearsal database name that is not protected.
function Assert-RehearsalDatabaseName {
    param([string]$Name)
    if ([string]::IsNullOrEmpty($Name)) { throw 'Refusing: empty database name.' }
    foreach ($protected in $script:ProtectedDatabases) {
        if ([string]::Equals($Name, $protected, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing: '$Name' is a protected database."
        }
    }
    foreach ($prefix in $script:ProtectedPrefixes) {
        if ($Name.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing: '$Name' is a protected database ($prefix*)."
        }
    }
    if (-not (Test-WholeMatch -Value $Name -Pattern $script:RehearsalNamePattern)) {   # case-sensitive, whole string, ASCII digits
        throw "Refusing: '$Name' is not a rehearsal database name (wb_rehearsal_<yyyyMMdd_HHmmss>_<fresh|copy|dupe|collide>)."
    }
}

# The four rehearsal database names for one stamp, each validated.
function Get-RehearsalDatabaseNames {
    param([string]$Stamp)
    if (-not (Test-WholeMatch -Value $Stamp -Pattern '^\d{8}_\d{6}$')) { throw "Refusing: invalid rehearsal stamp '$Stamp' (expected yyyyMMdd_HHmmss)." }
    $names = [ordered]@{}
    foreach ($role in $script:RehearsalRoles) {
        $name = "wb_rehearsal_${Stamp}_$role"
        Assert-RehearsalDatabaseName $name
        $names[$role] = $name
    }
    return $names
}

# Validates the verified backup and its side files; returns the recorded live schema and fingerprint.
# The expected checksum comes from the backup's own sidecar file (wb_<stamp>.dump.sha256), never from a constant.
function Assert-VerifiedDump {
    param([string]$Path)
    if (-not $Path -or -not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Verified dump not found: $Path" }
    $full = Resolve-FullPath $Path
    if ((Split-Path $full -Leaf) -cnotmatch '^wb_\d{8}_\d{6}\.dump$') { throw "Refusing: '$full' is not a wb_<yyyyMMdd_HHmmss>.dump backup." }

    $base = $full.Substring(0, $full.Length - '.dump'.Length)
    $checksumFile = "$base.dump.sha256"
    try { $null = Assert-DumpMatchesSidecar -DumpPath $full -SidecarPath $checksumFile }
    catch { throw "Refusing: $($_.Exception.Message)" }

    $liveSchemaFile  = "$base.live-schema.sql"
    $checkSchemaFile = "$base.check-schema.sql"
    $fingerprintFile = "$base.live-fingerprint.txt"
    $restoreLogFile  = "$base.restore-errors.log"
    foreach ($f in @($liveSchemaFile, $checkSchemaFile, $fingerprintFile, $restoreLogFile)) {
        if (-not (Test-Path -LiteralPath $f -PathType Leaf)) { throw "Refusing: side file $f is missing, so this backup was not fully verified." }
    }
    if ((Get-Item -LiteralPath $restoreLogFile).Length -ne 0) {
        throw "Refusing: $restoreLogFile is not empty, so the verification restore reported messages."
    }

    $liveSchema  = Get-NormalizedSchema $liveSchemaFile
    $checkSchema = Get-NormalizedSchema $checkSchemaFile
    Assert-SameText -Expected $liveSchema -Actual $checkSchema -What "The recorded check schema ($checkSchemaFile)"

    $fingerprint = ((Get-Content -LiteralPath $fingerprintFile -Raw) -replace "`r`n", "`n").Trim()
    Assert-FingerprintFormat -Fingerprint $fingerprint -Source $fingerprintFile
    if ($fingerprint -cnotmatch "(?m)^flyway_schema_history absent$") {
        throw "Refusing: $fingerprintFile shows Flyway history; rehearsal copies must start from a pre-Flyway backup."
    }

    return [pscustomobject]@{ Path = $full; Base = $base; Schema = $liveSchema; Fingerprint = $fingerprint }
}
