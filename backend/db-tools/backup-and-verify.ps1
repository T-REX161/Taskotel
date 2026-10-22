<#
.SYNOPSIS
    Back up the live workload_balancer database and prove that the backup restores correctly.

.DESCRIPTION
    1. Reads the live database in a read-only session (default_transaction_read_only=on):
       a custom-format pg_dump, a schema-only dump and a verification fingerprint.
    2. Restores the dump into a NEW, uniquely named database: wb_restore_check_<yyyyMMdd_HHmmss>.
    3. Compares the restored schema (ignoring PostgreSQL 18 \restrict lines, which carry a random key)
       and the fingerprint with the live database, exactly. Prints BACKUP VERIFIED only if both match.

    Safety:
    - The live database is only read, and only inside the read-only session.
    - The output folder and every output file are validated before anything is created; existing
      files are never overwritten and the folder may not be inside the project.
    - No database is dropped, renamed or overwritten. On failure the script lists the files and the
      new database it created, so you can inspect them; it never removes them itself.
    - The password comes from DB_PASSWORD and is never printed. PGPASSWORD and PGOPTIONS are
      restored to their previous values on success and on failure.
    - pg_restore messages go to wb_<stamp>.restore-errors.log, not the console, because they can
      quote row data. Dumps and that log contain private data: keep them outside the project and
      delete old copies.

.NOTES
    Requires DB_PASSWORD in the current session. Uses DB_HOST, DB_PORT, DB_USER if set
    (defaults: localhost, 5432, postgres). Stop the backend first when taking the go-live backup.
#>
[CmdletBinding()]
param(
    [string]$OutputDirectory = (Join-Path $HOME 'db-backups')
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'DbToolsCommon.ps1')

$stamp       = Get-Date -Format 'yyyyMMdd_HHmmss'
$checkDb     = "wb_restore_check_$stamp"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path

# -- Preconditions: validate everything before creating anything ---------------
if (-not $env:DB_PASSWORD) { throw 'DB_PASSWORD is not set in this session. Nothing was changed.' }
if ($env:DB_NAME -and $env:DB_NAME -ne $LiveDb) {
    Write-Warning "DB_NAME is '$env:DB_NAME'. This script always backs up the live database '$LiveDb'."
}
if ($checkDb -eq $LiveDb -or $checkDb -notmatch '^wb_restore_check_\d{8}_\d{6}$') { throw "Refusing: unexpected check database name '$checkDb'." }
if (-not (Test-Path -LiteralPath $FingerprintFile)) { throw "Missing $FingerprintFile" }
Assert-PgTools

$outputPath = Resolve-FullPath $OutputDirectory
if (Test-IsInsideFolder -Path $outputPath -Folder $projectRoot) {
    throw "Refusing to write backups inside the project folder ($projectRoot). Choose a folder outside it. Nothing was changed."
}
$base            = Join-Path $outputPath "wb_$stamp"
$dumpFile        = "$base.dump"
$liveSchemaFile  = "$base.live-schema.sql"
$liveFingerprint = "$base.live-fingerprint.txt"
$checkSchemaFile = "$base.check-schema.sql"
$restoreErrorLog = "$base.restore-errors.log"
Assert-FilesDoNotExist @($dumpFile, $liveSchemaFile, $liveFingerprint, $checkSchemaFile, $restoreErrorLog)

if (-not (Test-Path -LiteralPath $outputPath)) {
    New-Item -ItemType Directory -Path $outputPath | Out-Null
}

Save-PgEnvironment
try {
    $env:PGPASSWORD = $env:DB_PASSWORD

    # -- 1. Read-only session: everything that touches the live database ------
    Use-ReadOnlySession
    if (Get-QueryResult -Database 'postgres' -Sql "SELECT 1 FROM pg_database WHERE datname = '$checkDb'") {
        throw "Database $checkDb already exists. Nothing was changed."
    }
    Write-Host "Backing up $LiveDb (read-only session) ..."
    Register-File $dumpFile
    Invoke-Pg 'pg_dump.exe' ($Conn + @('-d', $LiveDb, '-Fc', '-f', $dumpFile))
    Register-File $liveSchemaFile
    Invoke-Pg 'pg_dump.exe' ($Conn + @('-d', $LiveDb, '--schema-only', '--no-owner', '--no-privileges', '-f', $liveSchemaFile))
    $liveFp = Get-VerificationFingerprint -Database $LiveDb
    Register-File $liveFingerprint
    [System.IO.File]::WriteAllText($liveFingerprint, $liveFp + "`n")
    $liveSchema = Get-NormalizedSchema $liveSchemaFile

    # -- 2. Default session: create and fill the NEW copy only ----------------
    Use-DefaultSession
    Write-Host "Restoring into new database $checkDb ..."
    Register-Database $checkDb
    Invoke-Pg 'createdb.exe' ($Conn + @($checkDb))   # fails if the name exists; never overwrites
    Register-File $restoreErrorLog
    Invoke-PgRestoreLogged -ArgList ($Conn + @('-d', $checkDb, '--no-owner', '--no-privileges', '--exit-on-error', '--single-transaction', $dumpFile)) -ErrorLog $restoreErrorLog

    # -- 3. Verify: exact schema and fingerprint match ------------------------
    Register-File $checkSchemaFile
    Invoke-Pg 'pg_dump.exe' ($Conn + @('-d', $checkDb, '--schema-only', '--no-owner', '--no-privileges', '-f', $checkSchemaFile))
    $checkSchema = Get-NormalizedSchema $checkSchemaFile
    if (-not [string]::Equals($liveSchema, $checkSchema, [System.StringComparison]::Ordinal)) {
        Show-SchemaDifference -Expected $liveSchema -Actual $checkSchema
    }
    Assert-SameText -Expected $liveSchema -Actual $checkSchema -What "The schema of $checkDb"

    $checkFp = Get-VerificationFingerprint -Database $checkDb
    Assert-SameText -Expected $liveFp -Actual $checkFp -What "The data fingerprint of $checkDb"

    Write-Host ''
    Write-Host "BACKUP VERIFIED: $dumpFile"
    Write-Host "  Schema and data of the restore-check database $checkDb match the live database exactly."
    Write-Host '  Verification fingerprint (counts, table names and MD5 hashes only):'
    Write-Host $liveFp
    Write-ArtifactReport -Heading 'Created by this run (kept; nothing is removed automatically):'
} catch {
    Write-Host ''
    Write-Host "BACKUP NOT VERIFIED: $($_.Exception.Message)"
    Write-ArtifactReport -Heading 'Left behind by this run (inspect them; nothing is removed automatically):'
    throw
} finally {
    Restore-PgEnvironment
}
