<#
.SYNOPSIS
    Restore a verified backup into a NEW database and prove it matches the backed-up live database.

.DESCRIPTION
    Restores wb_<stamp>.dump (made by backup-and-verify.ps1) into a new database named
    wb_restored_<yyyyMMdd_HHmmss>, then compares its schema and verification fingerprint, exactly,
    with the wb_<stamp>.live-schema.sql and wb_<stamp>.live-fingerprint.txt files saved next to the
    dump. Prints RESTORE VERIFIED only if both match.

    Safety:
    - Never connects to workload_balancer, and never drops, renames or overwrites a database.
    - Every output file is checked before anything is created; existing files are never overwritten.
    - On failure the script lists the files and the new database it created; it never removes them.
    - PGPASSWORD and PGOPTIONS are restored to their previous values on success and on failure.
    - pg_restore messages go to a private log file next to the dump, not the console, because they
      can quote row data.

    To roll back, start the PREVIOUS application build with DB_NAME set to the restored database (and
    WB_FLYWAY_ENABLED unset or false). Renaming databases is a separate manual decision.

.PARAMETER Dump
    Path to a wb_<yyyyMMdd_HHmmss>.dump file created by backup-and-verify.ps1.

.NOTES
    Requires DB_PASSWORD in the current session. Uses DB_HOST, DB_PORT, DB_USER if set.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Dump
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'DbToolsCommon.ps1')

$restoredDb = 'wb_restored_' + (Get-Date -Format 'yyyyMMdd_HHmmss')

# -- Preconditions: validate everything before creating anything ---------------
if (-not $env:DB_PASSWORD) { throw 'DB_PASSWORD is not set in this session. Nothing was changed.' }
if ($restoredDb -eq $LiveDb -or $restoredDb -notmatch '^wb_restored_\d{8}_\d{6}$') { throw "Refusing: unexpected target database name '$restoredDb'." }
if (-not (Test-Path -LiteralPath $FingerprintFile)) { throw "Missing $FingerprintFile" }
if (-not (Test-Path -LiteralPath $Dump)) { throw "Dump file not found: $Dump" }
$Dump = Resolve-FullPath $Dump
if ((Split-Path $Dump -Leaf) -notmatch '^wb_\d{8}_\d{6}\.dump$') { throw 'Expected a wb_<yyyyMMdd_HHmmss>.dump file created by backup-and-verify.ps1.' }
Assert-PgTools

$base               = $Dump.Substring(0, $Dump.Length - '.dump'.Length)
$expectedSchemaFile = "$base.live-schema.sql"
$expectedPrintFile  = "$base.live-fingerprint.txt"
$restoredSchemaFile = "$base.$restoredDb-schema.sql"
$restoreErrorLog    = "$base.$restoredDb-restore-errors.log"
foreach ($f in @($expectedSchemaFile, $expectedPrintFile)) {
    if (-not (Test-Path -LiteralPath $f)) { throw "Missing $f (needed to verify the restore). Nothing was changed." }
}
Assert-FilesDoNotExist @($restoredSchemaFile, $restoreErrorLog)

# The recorded values must themselves be complete before they can be compared against
$expectedSchema = Get-NormalizedSchema $expectedSchemaFile
$expectedFp = ((Get-Content -LiteralPath $expectedPrintFile -Raw) -replace "`r`n", "`n").Trim()
Assert-FingerprintFormat -Fingerprint $expectedFp -Source $expectedPrintFile

Save-PgEnvironment
try {
    $env:PGPASSWORD = $env:DB_PASSWORD

    Use-ReadOnlySession
    if (Get-QueryResult -Database 'postgres' -Sql "SELECT 1 FROM pg_database WHERE datname = '$restoredDb'") {
        throw "Database $restoredDb already exists. Nothing was changed."
    }

    Use-DefaultSession
    Write-Host "Restoring $Dump into new database $restoredDb ..."
    Register-Database $restoredDb
    Invoke-Pg 'createdb.exe' ($Conn + @($restoredDb))   # fails if the name exists; never overwrites
    Register-File $restoreErrorLog
    Invoke-PgRestoreLogged -ArgList ($Conn + @('-d', $restoredDb, '--no-owner', '--no-privileges', '--exit-on-error', '--single-transaction', $Dump)) -ErrorLog $restoreErrorLog

    Register-File $restoredSchemaFile
    Invoke-Pg 'pg_dump.exe' ($Conn + @('-d', $restoredDb, '--schema-only', '--no-owner', '--no-privileges', '-f', $restoredSchemaFile))
    $restoredSchema = Get-NormalizedSchema $restoredSchemaFile
    if (-not [string]::Equals($expectedSchema, $restoredSchema, [System.StringComparison]::Ordinal)) {
        Show-SchemaDifference -Expected $expectedSchema -Actual $restoredSchema
    }
    Assert-SameText -Expected $expectedSchema -Actual $restoredSchema -What "The schema of $restoredDb"

    $restoredFp = Get-VerificationFingerprint -Database $restoredDb
    Assert-SameText -Expected $expectedFp -Actual $restoredFp -What "The data fingerprint of $restoredDb"

    Write-Host ''
    Write-Host "RESTORE VERIFIED: $restoredDb matches the backed-up live database exactly (schema and data)."
    Write-Host "To use it, start the PREVIOUS application build with DB_NAME=$restoredDb and WB_FLYWAY_ENABLED unset or false."
    Write-Host "$LiveDb was not touched."
    Write-ArtifactReport -Heading 'Created by this run (kept; nothing is removed automatically):'
} catch {
    Write-Host ''
    Write-Host "RESTORE NOT VERIFIED: $($_.Exception.Message)"
    Write-ArtifactReport -Heading 'Left behind by this run (inspect them; nothing is removed automatically):'
    throw
} finally {
    Restore-PgEnvironment
}
