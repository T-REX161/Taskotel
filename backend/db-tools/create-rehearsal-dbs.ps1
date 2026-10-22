<#
.SYNOPSIS
    Create the four isolated P4a rehearsal databases from the verified backup.

.DESCRIPTION
    Creates, with one shared stamp (yyyyMMdd_HHmmss):
      wb_rehearsal_<stamp>_fresh    empty database
      wb_rehearsal_<stamp>_collide  empty database
      wb_rehearsal_<stamp>_copy     restore of the verified dump
      wb_rehearsal_<stamp>_dupe     restore of the verified dump
    Each restored copy must match the schema and data fingerprint recorded for the live database when
    the backup was verified. Prints REHEARSAL DATABASES READY and the WB_MIGRATION_TEST_STAMP value
    only if every database was created and checked.

    Safety:
    - Only the verified backup is accepted (SHA-256 37a58cb4...6089 and complete, clean side files).
    - Every name must match ^wb_rehearsal_\d{8}_\d{6}_(fresh|copy|dupe|collide)$, and workload_balancer,
      both existing restore-check databases, any wb_restore_check_* / wb_restored_* database and the
      PostgreSQL system databases are refused. Names are checked again right before each createdb.
    - The copies are made from the dump FILE. workload_balancer and the restore-check databases are
      never connected to; the only other connection is a read-only existence check on 'postgres'.
    - No database or file is dropped, renamed, overwritten or cleaned up. On failure the script lists
      what it created and leaves it in place.
    - Runs no migrations. Flyway is not involved.
    - PGPASSWORD and PGOPTIONS are restored on success and on failure.

.PARAMETER Dump
    The verified backup. Defaults to $HOME\db-backups\wb_20261020_142245.dump.

.NOTES
    Requires DB_PASSWORD in the current session. Uses DB_HOST, DB_PORT, DB_USER if set.
#>
[CmdletBinding()]
param(
    [string]$Dump = (Join-Path $HOME 'db-backups\wb_20261020_142245.dump')
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'DbToolsCommon.ps1')
. (Join-Path $PSScriptRoot 'RehearsalCommon.ps1')

# Creates exactly one rehearsal database, re-checking its name immediately before createdb.
function New-RehearsalDatabase {
    param([string]$Name)
    Assert-RehearsalDatabaseName $Name
    Register-Database $Name
    Invoke-Pg 'createdb.exe' ($Conn + @($Name))   # fails if the name exists; never overwrites
}

function Assert-EmptyDatabase {
    param([string]$Name)
    Assert-RehearsalDatabaseName $Name
    $tables = Get-QueryResult -Database $Name -Sql "SELECT count(*) FROM pg_class WHERE relnamespace = 'public'::regnamespace AND relkind IN ('r', 'p')"
    if ($tables -cne '0') { throw "Database $Name is not empty ($tables tables in public)." }
}

# -- Preconditions: validate everything before connecting or creating anything --------
if (-not $env:DB_PASSWORD) { throw 'DB_PASSWORD is not set in this session. Nothing was changed.' }
Assert-PgTools
$verified = Assert-VerifiedDump -Path $Dump
$stamp    = Get-Date -Format 'yyyyMMdd_HHmmss'
$names    = Get-RehearsalDatabaseNames -Stamp $stamp

$outputs = [ordered]@{}
foreach ($role in @('copy', 'dupe')) {
    $outputs[$role] = [pscustomobject]@{
        RestoreLog = Join-Path (Split-Path $verified.Path) "$($names[$role]).restore-errors.log"
        Schema     = Join-Path (Split-Path $verified.Path) "$($names[$role]).schema.sql"
    }
}
Assert-FilesDoNotExist @($outputs.Values | ForEach-Object { $_.RestoreLog; $_.Schema })

Save-PgEnvironment
try {
    $env:PGPASSWORD = $env:DB_PASSWORD

    # Read-only check on the maintenance database: none of the four names may exist yet
    Use-ReadOnlySession
    $nameList = ($names.Values | ForEach-Object { "'$_'" }) -join ', '
    $existing = Get-QueryResult -Database 'postgres' -Sql "SELECT coalesce(string_agg(datname, ', '), '') FROM pg_database WHERE datname IN ($nameList)"
    if ($existing) { throw "Rehearsal database(s) already exist: $existing. Nothing was changed." }

    Use-DefaultSession
    foreach ($role in @('fresh', 'collide')) {
        Write-Host "Creating empty database $($names[$role]) ..."
        New-RehearsalDatabase $names[$role]
        Assert-EmptyDatabase $names[$role]
    }

    foreach ($role in @('copy', 'dupe')) {
        $name = $names[$role]
        Write-Host "Creating $name from $($verified.Path) ..."
        New-RehearsalDatabase $name
        Register-File $outputs[$role].RestoreLog
        Invoke-PgRestoreLogged -ArgList ($Conn + @('-d', $name, '--no-owner', '--no-privileges', '--exit-on-error', '--single-transaction', $verified.Path)) -ErrorLog $outputs[$role].RestoreLog

        Register-File $outputs[$role].Schema
        Invoke-Pg 'pg_dump.exe' ($Conn + @('-d', $name, '--schema-only', '--no-owner', '--no-privileges', '-f', $outputs[$role].Schema))
        $schema = Get-NormalizedSchema $outputs[$role].Schema
        if (-not [string]::Equals($verified.Schema, $schema, [System.StringComparison]::Ordinal)) {
            Show-SchemaDifference -Expected $verified.Schema -Actual $schema
        }
        Assert-SameText -Expected $verified.Schema -Actual $schema -What "The schema of $name"
        Assert-SameText -Expected $verified.Fingerprint -Actual (Get-VerificationFingerprint -Database $name) -What "The data fingerprint of $name"
    }

    Write-Host ''
    Write-Host "REHEARSAL DATABASES READY (stamp $stamp)"
    foreach ($role in $names.Keys) { Write-Host ("  {0,-8} {1}" -f $role, $names[$role]) }
    Write-Host '  fresh and collide are empty; copy and dupe match the verified backup exactly (schema and data).'
    Write-Host "  For the opt-in migration tests: `$env:WB_MIGRATION_TEST_STAMP = '$stamp'"
    Write-ArtifactReport -Heading 'Created by this run (kept; nothing is removed automatically):'
} catch {
    Write-Host ''
    Write-Host "REHEARSAL DATABASES NOT READY: $($_.Exception.Message)"
    Write-ArtifactReport -Heading 'Left behind by this run (inspect them; nothing is removed automatically):'
    throw
} finally {
    Restore-PgEnvironment
}
