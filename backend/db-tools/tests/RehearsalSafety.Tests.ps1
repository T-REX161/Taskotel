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
    'CREATE TABLE public.staff (', ');',
    'CREATE TABLE public.tasks (', ');',
    'CREATE TABLE public.users (', ');',
    '\unrestrict k1') -join "`n"
$fingerprintText = @(
    'assignments 9 0123456789abcdef0123456789abcdef',
    'staff 22 0123456789abcdef0123456789abcdef',
    'tasks 9 0123456789abcdef0123456789abcdef',
    'users 25 0123456789abcdef0123456789abcdef',
    'tables assignments,staff,tasks,users',
    'flyway_schema_history absent') -join "`n"

# A complete fake backup set in its own sub-folder; individual parts can be broken per case
function New-BackupSet {
    param([string]$Case, [string]$Stamp = '20991231_235959', [string]$Fingerprint = $fingerprintText,
          [string]$CheckSchema = $schemaText, [string]$RestoreLog = '', [string[]]$Omit = @())
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
    return "$base.dump"
}
function Get-Sha([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }

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

    # -- Verified dump checksum ------------------------------------------------------------
    Assert-Case 'verified checksum constant is the P4a-2 dump' ($VerifiedDumpSha256 -ceq '37a58cb40577d51764edb72fe170d45adbb6e9a47c8442ba88e76b05c0586a90')
    $good = New-BackupSet 'good'
    $result = $null
    $accepted = -not (Test-Throws { $script:result = Assert-VerifiedDump -Path $good -ExpectedSha256 (Get-Sha $good) })
    Assert-Case 'complete backup set with matching checksum is accepted' $accepted
    Assert-Case 'accepted set returns the recorded fingerprint' ($accepted -and ($script:result.Fingerprint -ceq $fingerprintText))
    Assert-Case 'default expected checksum rejects any other dump' (Test-Throws { Assert-VerifiedDump -Path $good })
    Assert-Case 'one-character checksum difference is rejected' (Test-Throws { Assert-VerifiedDump -Path $good -ExpectedSha256 ((Get-Sha $good).Substring(0, 63) + 'x') })
    Assert-Case 'missing dump is rejected' (Test-Throws { Assert-VerifiedDump -Path (Join-Path $tempDir 'none\wb_20991231_235959.dump') })
    $renamed = Join-Path $tempDir 'good\backup.dump'; Copy-Item $good $renamed
    Assert-Case 'dump with an unexpected file name is rejected' (Test-Throws { Assert-VerifiedDump -Path $renamed -ExpectedSha256 (Get-Sha $renamed) })

    # -- Side files -------------------------------------------------------------------------
    foreach ($side in 'live-schema.sql', 'check-schema.sql', 'live-fingerprint.txt', 'restore-errors.log') {
        $dump = New-BackupSet "omit-$($side -replace '\W', '-')" -Omit @($side)
        Assert-Case "missing $side is rejected" (Test-Throws { Assert-VerifiedDump -Path $dump -ExpectedSha256 (Get-Sha $dump) })
    }
    $dump = New-BackupSet 'log' -RestoreLog 'restore reported an error'
    Assert-Case 'non-empty restore-errors.log is rejected' (Test-Throws { Assert-VerifiedDump -Path $dump -ExpectedSha256 (Get-Sha $dump) })
    $dump = New-BackupSet 'check' -CheckSchema ($schemaText -replace 'public.users \(', 'public.users (x int,')
    Assert-Case 'check schema that differs from the live schema is rejected' (Test-Throws { Assert-VerifiedDump -Path $dump -ExpectedSha256 (Get-Sha $dump) })
    $dump = New-BackupSet 'fp-bad' -Fingerprint ($fingerprintText -replace 'users 25', 'users x')
    Assert-Case 'malformed recorded fingerprint is rejected' (Test-Throws { Assert-VerifiedDump -Path $dump -ExpectedSha256 (Get-Sha $dump) })
    $dump = New-BackupSet 'fp-flyway' -Fingerprint ($fingerprintText -replace 'flyway_schema_history absent', 'flyway_schema_history fedcba9876543210fedcba9876543210')
    Assert-Case 'backup with Flyway history is rejected (copies must start pre-Flyway)' (Test-Throws { Assert-VerifiedDump -Path $dump -ExpectedSha256 (Get-Sha $dump) })

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
