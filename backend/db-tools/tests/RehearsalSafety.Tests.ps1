# Pure-function tests for RehearsalCommon.ps1: database-name safety, verified-dump checksum and
# side-file validation, and no-overwrite behaviour.
# No database connection and no external programs: it only calls PowerShell functions and writes
# fake backup files into its own folder under $env:TEMP, which it removes at the end.
# Run: powershell -NoProfile -ExecutionPolicy Bypass -File RehearsalSafety.Tests.ps1
# Exit code 0 = all cases passed. Also run by DbToolsScriptsTest during `mvn test` on Windows.

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot '..\DbToolsCommon.ps1')
. (Join-Path $PSScriptRoot '..\RehearsalCommon.ps1')

$results = New-Object System.Collections.Generic.List[string]
$failures = 0
function Assert-Case {
    param([string]$Name, [bool]$Condition)
    if ($Condition) { $results.Add("PASS  $Name") } else { $results.Add("FAIL  $Name"); $script:failures++ }
}
function Test-Throws {
    param([scriptblock]$Body)
    try { & $Body | Out-Null; return $false } catch { return $true }
}

$tempDir = Join-Path $env:TEMP ('wb-rehearsal-tests-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tempDir | Out-Null

$schemaText = @(
    '\restrict k1',
    'CREATE TABLE public.assignments (', ');',
    'CREATE TABLE public.departments (', ');',
    'CREATE TABLE public.notification_preferences (', ');',
    'CREATE TABLE public.staff (', ');',
    'CREATE TABLE public.support_assignments (', ');',
    'CREATE TABLE public.tasks (', ');',
    'CREATE TABLE public.users (', ');',
    '\unrestrict k1') -join "`n"
$fingerprintText = @(
    'assignments 9 0123456789abcdef0123456789abcdef',
    'departments 6 0123456789abcdef0123456789abcdef',
    'notification_preferences 3 0123456789abcdef0123456789abcdef',
    'staff 22 0123456789abcdef0123456789abcdef',
    'support_assignments 8 0123456789abcdef0123456789abcdef',
    'tasks 9 0123456789abcdef0123456789abcdef',
    'users 25 0123456789abcdef0123456789abcdef',
    'tables assignments,departments,notification_preferences,staff,support_assignments,tasks,users',
    'flyway_schema_history absent') -join "`n"

function Get-Sha([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }

# A complete fake backup set in its own sub-folder (dump, its SHA-256 sidecar and the four verification
# files); individual parts can be broken per case. -Sidecar replaces the sidecar's text.
function New-BackupSet {
    param([string]$Case, [string]$Stamp = '20991231_235959', [string]$Fingerprint = $fingerprintText,
          [string]$CheckSchema = $schemaText, [string]$RestoreLog = '', [string[]]$Omit = @(), [string]$Sidecar = '')
    $dir = Join-Path $tempDir $Case
    New-Item -ItemType Directory -Path $dir | Out-Null
    $base = Join-Path $dir "wb_$Stamp"
    $files = [ordered]@{
        'dump'               = "fake dump $Case"
        'live-schema.sql'    = $schemaText
        'check-schema.sql'   = $CheckSchema
        'live-fingerprint.txt' = $Fingerprint
        'restore-errors.log' = $RestoreLog
    }
    foreach ($ext in $files.Keys) {
        if ($Omit -notcontains $ext) { [System.IO.File]::WriteAllText("$base.$ext", $files[$ext]) }
    }
    if ($Omit -notcontains 'dump.sha256') {
        $text = if ($PSBoundParameters.ContainsKey('Sidecar')) { $Sidecar } else { (Format-Sha256Sidecar -Hash (Get-Sha "$base.dump") -DumpFileName "wb_$Stamp.dump") + "`n" }
        [System.IO.File]::WriteAllText("$base.dump.sha256", $text)
    }
    return "$base.dump"
}

try {
    # -- Database-name safety ------------------------------------------------------------
    foreach ($role in 'fresh', 'collide', 'copy', 'dupe') {
        Assert-Case "accepts wb_rehearsal_20261020_150000_$role" (-not (Test-Throws { Assert-RehearsalDatabaseName "wb_rehearsal_20261020_150000_$role" }))
    }
    $refused = @(
        'workload_balancer', 'WORKLOAD_BALANCER', 'Workload_Balancer',
        'wb_restore_check_20261020_140921', 'wb_restore_check_20261020_142245', 'WB_RESTORE_CHECK_20261020_142245',
        'wb_restore_check_20991231_000000', 'wb_restored_20261020_150000',
        'postgres', 'template0', 'template1', '',
        'wb_rehearsal_20261020_150000_live', 'wb_rehearsal_20261020_150000_workload_balancer',
        'WB_REHEARSAL_20261020_150000_COPY', 'wb_rehearsal_2026102_150000_copy', 'wb_rehearsal__copy',
        'wb_rehearsal_20261020_150000_copy2', ' wb_rehearsal_20261020_150000_copy', 'wb_rehearsal_20261020_150000_copy ',
        "wb_rehearsal_20261020_150000_copy`nworkload_balancer", "wb_rehearsal_20261020_150000_copy'; SELECT 1; --",
        "wb_rehearsal_20261020_150000_copy`n", "wb_rehearsal_20261020_150000_copy`r`n",
        ('wb_rehearsal_' + ([string][char]0x0662 * 8) + '_150000_copy')   # non-ASCII digits
    )
    foreach ($name in $refused) {
        $label = (($name -replace "`r", '<CR>') -replace "`n", '<LF>') -replace '[^\x20-\x7E]', '<U>'
        Assert-Case "refuses '$label'" (Test-Throws { Assert-RehearsalDatabaseName $name })
    }

    $names = Get-RehearsalDatabaseNames -Stamp '20261020_150000'
    Assert-Case 'generates exactly fresh, collide, copy, dupe in that order' ((@($names.Keys) -join ',') -ceq 'fresh,collide,copy,dupe')
    Assert-Case 'generated names share one stamp and are distinct' ((@($names.Values | Select-Object -Unique).Count -eq 4) -and (@($names.Values | Where-Object { $_ -cnotlike 'wb_rehearsal_20261020_150000_*' }).Count -eq 0))
    Assert-Case 'generated names never equal a protected database' (@($names.Values | Where-Object { $ProtectedDatabases -contains $_ }).Count -eq 0)
    foreach ($stamp in '2026102_150000', '20261020-150000', '20261020_150000; x', '', '20261020_1500001', "20261020_150000`n") {
        Assert-Case "refuses stamp '$stamp'" (Test-Throws { Get-RehearsalDatabaseNames -Stamp $stamp })
    }
    Assert-Case 'protected list names both existing restore-check databases and the live database' (
        ($ProtectedDatabases -ccontains 'workload_balancer') -and ($ProtectedDatabases -ccontains 'wb_restore_check_20261020_140921') -and ($ProtectedDatabases -ccontains 'wb_restore_check_20261020_142245'))

    # -- Verified dump checksum (recorded in the dump's own sidecar; there is no built-in checksum) ----
    Assert-Case 'no checksum constant is built in' ($null -eq (Get-Variable -Name VerifiedDumpSha256 -Scope Script -ErrorAction SilentlyContinue))
    Assert-Case 'Assert-VerifiedDump takes only the dump path (no checksum override)' (-not (Get-Command Assert-VerifiedDump).Parameters.ContainsKey('ExpectedSha256'))
    $good = New-BackupSet 'good'
    $result = $null
    $accepted = -not (Test-Throws { $script:result = Assert-VerifiedDump -Path $good })
    Assert-Case 'complete backup set with matching checksum sidecar is accepted' $accepted
    Assert-Case 'accepted set returns the recorded fingerprint (nine lines)' ($accepted -and ($script:result.Fingerprint -ceq $fingerprintText) -and (@($script:result.Fingerprint -split "`n").Count -eq 9))
    Assert-Case 'missing dump is rejected' (Test-Throws { Assert-VerifiedDump -Path (Join-Path $tempDir 'none\wb_20991231_235959.dump') })
    $renamed = Join-Path $tempDir 'good\backup.dump'; Copy-Item $good $renamed
    Assert-Case 'dump with an unexpected file name is rejected' (Test-Throws { Assert-VerifiedDump -Path $renamed })

    # -- Checksum sidecar -------------------------------------------------------------------
    $dump = New-BackupSet 'tampered'
    [System.IO.File]::AppendAllText($dump, 'x')
    Assert-Case 'a dump changed after its checksum was recorded is rejected' (Test-Throws { Assert-VerifiedDump -Path $dump })
    $dump = New-BackupSet 'sha-onechar' -Sidecar ((Format-Sha256Sidecar -Hash ('0' * 64) -DumpFileName 'wb_20991231_235959.dump') + "`n")
    Assert-Case 'a different recorded checksum is rejected' (Test-Throws { Assert-VerifiedDump -Path $dump })
    $realHash = Get-Sha (New-BackupSet 'sha-ref')
    $dump = New-BackupSet 'sha-upper' -Sidecar ("$($realHash.ToUpperInvariant())  wb_20991231_235959.dump`n")
    Assert-Case 'an upper-case checksum is malformed and rejected' (Test-Throws { Assert-VerifiedDump -Path $dump })
    $dump = New-BackupSet 'sha-short' -Sidecar "abc123  wb_20991231_235959.dump`n"
    Assert-Case 'a truncated checksum is rejected' (Test-Throws { Assert-VerifiedDump -Path $dump })
    $dump = New-BackupSet 'sha-empty' -Sidecar ''
    Assert-Case 'an empty checksum file is rejected' (Test-Throws { Assert-VerifiedDump -Path $dump })
    $dump = New-BackupSet 'sha-garbage' -Sidecar "not a checksum`n"
    Assert-Case 'a malformed checksum file is rejected' (Test-Throws { Assert-VerifiedDump -Path $dump })
    $dump = New-BackupSet 'sha-twolines'
    [System.IO.File]::AppendAllText("$($dump).sha256", (Format-Sha256Sidecar -Hash ('1' * 64) -DumpFileName 'wb_20991231_235959.dump') + "`n")
    Assert-Case 'a checksum file with a second line is rejected' (Test-Throws { Assert-VerifiedDump -Path $dump })
    $dump = New-BackupSet 'sha-othername' -Sidecar ((Format-Sha256Sidecar -Hash ('2' * 64) -DumpFileName 'wb_20991231_000001.dump') + "`n")
    Assert-Case 'a checksum recorded for a different dump file name is rejected' (Test-Throws { Assert-VerifiedDump -Path $dump })

    # -- Side files -------------------------------------------------------------------------
    foreach ($side in 'dump.sha256', 'live-schema.sql', 'check-schema.sql', 'live-fingerprint.txt', 'restore-errors.log') {
        $dump = New-BackupSet "omit-$($side -replace '\W', '-')" -Omit @($side)
        Assert-Case "missing $side is rejected" (Test-Throws { Assert-VerifiedDump -Path $dump })
    }
    $dump = New-BackupSet 'log' -RestoreLog 'restore reported an error'
    Assert-Case 'non-empty restore-errors.log is rejected' (Test-Throws { Assert-VerifiedDump -Path $dump })
    $dump = New-BackupSet 'check' -CheckSchema ($schemaText -replace 'public.users \(', 'public.users (x int,')
    Assert-Case 'check schema that differs from the live schema is rejected' (Test-Throws { Assert-VerifiedDump -Path $dump })
    $dump = New-BackupSet 'check-missing-table' -CheckSchema ($schemaText -replace 'CREATE TABLE public.departments \(', 'CREATE TABLE public.deptz (')
    Assert-Case 'check schema missing a seven-table table is rejected' (Test-Throws { Assert-VerifiedDump -Path $dump })
    $fourTableSchema = @('\restrict k1', 'CREATE TABLE public.assignments (', ');', 'CREATE TABLE public.staff (', ');', 'CREATE TABLE public.tasks (', ');', 'CREATE TABLE public.users (', ');', '\unrestrict k1') -join "`n"
    $dump = New-BackupSet 'four-table-schema' -CheckSchema $fourTableSchema
    Assert-Case 'a four-table check schema is rejected' (Test-Throws { Assert-VerifiedDump -Path $dump })
    $dump = New-BackupSet 'fp-bad' -Fingerprint ($fingerprintText -replace 'users 25', 'users x')
    Assert-Case 'malformed recorded fingerprint is rejected' (Test-Throws { Assert-VerifiedDump -Path $dump })
    $sixLine = @($fingerprintText -split "`n" | Where-Object { $_ -cnotmatch '^(departments|notification_preferences|support_assignments) ' }) -join "`n"
    $dump = New-BackupSet 'fp-old-format' -Fingerprint $sixLine
    Assert-Case 'an old four-table (six-line) recorded fingerprint is rejected' (Test-Throws { Assert-VerifiedDump -Path $dump })
    $dump = New-BackupSet 'fp-flyway' -Fingerprint ($fingerprintText -replace 'flyway_schema_history absent', 'flyway_schema_history fedcba9876543210fedcba9876543210')
    Assert-Case 'backup with Flyway history is rejected (copies must start pre-Flyway)' (Test-Throws { Assert-VerifiedDump -Path $dump })

    # -- No-overwrite ------------------------------------------------------------------------
    Assert-Case 'existing output file is refused' (Test-Throws { Assert-FilesDoNotExist @($good) })
    Assert-Case 'new output file is allowed' (-not (Test-Throws { Assert-FilesDoNotExist @((Join-Path $tempDir 'good\wb_rehearsal_20261020_150000_copy.schema.sql')) }))
} finally {
    Remove-Item -LiteralPath $tempDir -Recurse -Force   # only the folder this script created
}

$results | ForEach-Object { Write-Host $_ }
if ($failures -gt 0) {
    Write-Host "$failures of $($results.Count) rehearsal safety cases FAILED"
    exit 1
}
Write-Host "ALL PASSED: $($results.Count) rehearsal safety cases"
exit 0
