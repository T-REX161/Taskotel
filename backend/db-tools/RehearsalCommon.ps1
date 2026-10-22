# Rehearsal-database safety rules for create-rehearsal-dbs.ps1. Dot-sourced after DbToolsCommon.ps1.
# Pure functions only: no database connections and no external programs, so they can be tested alone.
#
# - Rehearsal databases are always named wb_rehearsal_<yyyyMMdd_HHmmss>_<role>, role in
#   fresh | collide | copy | dupe (the same rule PostgresMigrationIT enforces).
# - The live database, both existing restore-check databases, any wb_restore_check_* / wb_restored_*
#   database and the PostgreSQL system databases are refused even if a name ever matched the pattern.
# - Only the verified backup is accepted: its SHA-256 must match, and its side files must show a
#   completed, clean verification of a pre-Flyway database.

Set-StrictMode -Version Latest

$script:RehearsalRoles       = @('fresh', 'collide', 'copy', 'dupe')
$script:RehearsalNamePattern = '^wb_rehearsal_\d{8}_\d{6}_(fresh|copy|dupe|collide)$'
$script:ProtectedDatabases   = @('workload_balancer', 'wb_restore_check_20261020_140921', 'wb_restore_check_20261020_142245', 'postgres', 'template0', 'template1')
$script:ProtectedPrefixes    = @('wb_restore_check_', 'wb_restored_')
$script:VerifiedDumpSha256   = '37a58cb40577d51764edb72fe170d45adbb6e9a47c8442ba88e76b05c0586a90'

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
# -ExpectedSha256 exists only so tests can use their own files; the creation script never sets it.
function Assert-VerifiedDump {
    param([string]$Path, [string]$ExpectedSha256 = $script:VerifiedDumpSha256)
    if (-not $Path -or -not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Verified dump not found: $Path" }
    $full = Resolve-FullPath $Path
    if ((Split-Path $full -Leaf) -cnotmatch '^wb_\d{8}_\d{6}\.dump$') { throw "Refusing: '$full' is not a wb_<yyyyMMdd_HHmmss>.dump backup." }

    $actual = (Get-FileHash -LiteralPath $full -Algorithm SHA256).Hash.ToLowerInvariant()
    if (-not [string]::Equals($actual, $ExpectedSha256.ToLowerInvariant(), [System.StringComparison]::Ordinal)) {
        throw "Refusing: SHA-256 of $full is $actual, not the verified $ExpectedSha256."
    }

    $base = $full.Substring(0, $full.Length - '.dump'.Length)
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
