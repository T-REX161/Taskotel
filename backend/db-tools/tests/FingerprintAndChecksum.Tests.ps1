# Pure-function tests for the seven-table fingerprint format check and the dump SHA-256 sidecar helpers in
# DbToolsCommon.ps1.
# No database connection and no external programs: it only calls PowerShell functions and writes
# small fake files into its own folder under $env:TEMP, which it removes at the end.
# Run: powershell -NoProfile -ExecutionPolicy Bypass -File FingerprintAndChecksum.Tests.ps1
# Exit code 0 = all cases passed. Also run by DbToolsScriptsTest during `mvn test` on Windows.

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot '..\DbToolsCommon.ps1')

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

$hash = '0123456789abcdef0123456789abcdef'
$appLines = @(
    "assignments 13 $hash",
    "departments 6 $hash",
    "notification_preferences 3 $hash",
    "staff 36 $hash",
    "support_assignments 8 $hash",
    "tasks 17 $hash",
    "users 38 $hash")
$tablesLine = 'tables assignments,departments,notification_preferences,staff,support_assignments,tasks,users'
$flywayAbsent = 'flyway_schema_history absent'
function New-Fingerprint {
    param([string[]]$App = $appLines, [string]$Tables = $tablesLine, [string]$Flyway = $flywayAbsent)
    return (@($App) + @($Tables, $Flyway)) -join "`n"
}
function Test-Format([string]$Text) { -not (Test-Throws { Assert-FingerprintFormat -Fingerprint $Text -Source 'test' }) }

# -- Table lists -------------------------------------------------------------------------------
Assert-Case 'the application tables are exactly the seven current tables' ((@($script:AppTables) -join ',') -ceq 'assignments,departments,notification_preferences,staff,support_assignments,tasks,users')
Assert-Case 'the known tables are the seven plus flyway_schema_history' ((@($script:KnownTables) -join ',') -ceq 'assignments,departments,flyway_schema_history,notification_preferences,staff,support_assignments,tasks,users')

# -- Fingerprint format: nine lines ---------------------------------------------------------------
$good = New-Fingerprint
Assert-Case 'a complete nine-line fingerprint is accepted' (Test-Format $good)
Assert-Case 'the accepted fingerprint has exactly nine lines' (@($good -split "`n").Count -eq 9)
Assert-Case 'a fingerprint with a Flyway history hash is accepted' (Test-Format (New-Fingerprint -Flyway 'flyway_schema_history fedcba9876543210fedcba9876543210' -Tables ($tablesLine -replace 'departments,', 'departments,flyway_schema_history,')))
Assert-Case 'an empty fingerprint is rejected' (-not (Test-Format ''))
Assert-Case 'the old six-line four-table fingerprint is rejected' (-not (Test-Format (@('assignments 9 ' + $hash, 'staff 22 ' + $hash, 'tasks 9 ' + $hash, 'users 25 ' + $hash, 'tables assignments,staff,tasks,users', $flywayAbsent) -join "`n")))
Assert-Case 'a fingerprint with a line missing is rejected' (-not (Test-Format (New-Fingerprint -App @($appLines | Select-Object -First 6))))
Assert-Case 'a fingerprint with an extra line is rejected' (-not (Test-Format ((New-Fingerprint) + "`nextra 1 $hash")))
foreach ($i in 0..6) {
    $name = ($appLines[$i] -split ' ')[0]
    Assert-Case "a fingerprint missing the $name line is rejected" (-not (Test-Format (New-Fingerprint -App @($appLines | Where-Object { $_ -cnotlike "$name *" }))))
    $broken = @($appLines); $broken[$i] = "$name x $hash"
    Assert-Case "a non-numeric $name count is rejected" (-not (Test-Format (New-Fingerprint -App $broken)))
    $broken = @($appLines); $broken[$i] = "$name 1 abc"
    Assert-Case "a short $name hash is rejected" (-not (Test-Format (New-Fingerprint -App $broken)))
    $broken = @($appLines); $broken[$i] = "$name 1 " + $hash.ToUpperInvariant()
    Assert-Case "an upper-case $name hash is rejected" (-not (Test-Format (New-Fingerprint -App $broken)))
}
$swapped = @($appLines); $swapped[0], $swapped[1] = $swapped[1], $swapped[0]
Assert-Case 'application lines in the wrong order are rejected' (-not (Test-Format (New-Fingerprint -App $swapped)))

# -- Table list: missing and unexpected tables -----------------------------------------------------
foreach ($table in $script:AppTables) {
    $without = ($script:AppTables | Where-Object { $_ -cne $table }) -join ','
    Assert-Case "a table list without $table is rejected" (-not (Test-Format (New-Fingerprint -Tables "tables $without")))
}
Assert-Case 'a table list with an unexpected table is rejected' (-not (Test-Format (New-Fingerprint -Tables ($tablesLine + ',audit_log'))))
Assert-Case 'a table list that repeats a table is rejected' (-not (Test-Format (New-Fingerprint -Tables ($tablesLine + ',users'))))
Assert-Case 'a table list with an empty entry is rejected' (-not (Test-Format (New-Fingerprint -Tables ($tablesLine + ','))))
Assert-Case 'an empty table list is rejected' (-not (Test-Format (New-Fingerprint -Tables 'tables ')))
Assert-Case 'a malformed Flyway line is rejected' (-not (Test-Format (New-Fingerprint -Flyway 'flyway_schema_history present')))
Assert-Case 'a Flyway line with a short hash is rejected' (-not (Test-Format (New-Fingerprint -Flyway 'flyway_schema_history abc')))

# -- PowerShell-to-psql argument passing (regression: COLLATE "C" arrived as COLLATE C) ------------------
# Windows PowerShell 5.1 drops the double quotes inside an argument it hands to a native program (see the probe
# in the temp-folder section below, which runs a child PowerShell that only reports the arguments it received).
$queryFunction = (Get-Command Get-QueryResult).ScriptBlock.ToString()
$guardAt = $queryFunction.IndexOf("Contains('""')")
$psqlAt = $queryFunction.IndexOf('& (Join-Path $script:PgBin ''psql.exe'')')
Assert-Case 'Get-QueryResult refuses inline SQL containing a double quote' ($guardAt -ge 0)
Assert-Case 'the double-quote guard runs before psql is started' (($guardAt -ge 0) -and ($psqlAt -gt $guardAt))
Assert-Case 'the guard throws (it does not just warn)' ($queryFunction -cmatch "Contains\('""'\)\) \{ throw ")
$fingerprintFunction = (Get-Command Get-VerificationFingerprint).ScriptBlock.ToString()
Assert-Case 'the table-list query orders by bytes (convert_to), not by a quoted collation' ($fingerprintFunction.Contains("ORDER BY convert_to(relname::text, 'UTF8')") -and -not $fingerprintFunction.Contains('COLLATE'))
Assert-Case 'every inline query in the fingerprint function is free of double quotes' (-not ($fingerprintFunction -cmatch '-Sql\s+"[^"\r\n]*""'))

# -- SHA-256 sidecar ---------------------------------------------------------------------------------
$tempDir = Join-Path $env:TEMP ('wb-fingerprint-tests-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tempDir | Out-Null
try {
    if ($PSVersionTable.PSVersion.Major -le 5) {
        $echoScript = Join-Path $tempDir 'echo-args.ps1'
        [System.IO.File]::WriteAllText($echoScript, '[Console]::Out.Write(($args -join [string][char]31))')
        $probe = 'a "C" b'
        $seen = & (Join-Path $PSHOME 'powershell.exe') -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $echoScript $probe
        Assert-Case 'PowerShell 5.1 strips double quotes from native-program arguments (the cause of the COLLATE bug)' ($seen -ceq 'a C b')
    }
    $dump = Join-Path $tempDir 'wb_20991231_235959.dump'
    $sidecar = "$dump.sha256"
    [System.IO.File]::WriteAllText($dump, 'fake dump content')
    $actual = Get-DumpSha256 $dump
    Assert-Case 'Get-DumpSha256 returns 64 lower-case hex characters' ($actual -cmatch '^[0-9a-f]{64}$')
    Assert-Case 'Get-DumpSha256 matches Get-FileHash' ($actual -ceq (Get-FileHash -LiteralPath $dump -Algorithm SHA256).Hash.ToLowerInvariant())
    Assert-Case 'Get-DumpSha256 refuses a missing file' (Test-Throws { Get-DumpSha256 (Join-Path $tempDir 'missing.dump') })

    $line = Format-Sha256Sidecar -Hash $actual -DumpFileName 'wb_20991231_235959.dump'
    Assert-Case 'the sidecar line is "<hash>  <dump file name>"' ($line -ceq "$actual  wb_20991231_235959.dump")
    Assert-Case 'Format-Sha256Sidecar refuses a malformed hash' (Test-Throws { Format-Sha256Sidecar -Hash 'abc' -DumpFileName 'wb_20991231_235959.dump' })
    Assert-Case 'Format-Sha256Sidecar refuses an upper-case hash' (Test-Throws { Format-Sha256Sidecar -Hash $actual.ToUpperInvariant() -DumpFileName 'wb_20991231_235959.dump' })
    Assert-Case 'Format-Sha256Sidecar refuses an unexpected file name' (Test-Throws { Format-Sha256Sidecar -Hash $actual -DumpFileName 'backup.dump' })

    [System.IO.File]::WriteAllText($sidecar, $line + "`n")
    Assert-Case 'a matching sidecar is accepted' (-not (Test-Throws { Assert-DumpMatchesSidecar -DumpPath $dump -SidecarPath $sidecar }))
    Assert-Case 'Assert-DumpMatchesSidecar returns the verified hash' ((Assert-DumpMatchesSidecar -DumpPath $dump -SidecarPath $sidecar) -ceq $actual)
    [System.IO.File]::WriteAllText($sidecar, ($line -replace "`n", '') + "`r`n")
    Assert-Case 'a sidecar with Windows line endings is accepted' (-not (Test-Throws { Assert-DumpMatchesSidecar -DumpPath $dump -SidecarPath $sidecar }))

    [System.IO.File]::WriteAllText($sidecar, (Format-Sha256Sidecar -Hash ('f' * 64) -DumpFileName 'wb_20991231_235959.dump') + "`n")
    Assert-Case 'a sidecar with a different hash is rejected' (Test-Throws { Assert-DumpMatchesSidecar -DumpPath $dump -SidecarPath $sidecar })
    [System.IO.File]::WriteAllText($sidecar, $line + "`n")
    [System.IO.File]::AppendAllText($dump, 'x')
    Assert-Case 'a dump modified after the checksum was recorded is rejected' (Test-Throws { Assert-DumpMatchesSidecar -DumpPath $dump -SidecarPath $sidecar })
    Assert-Case 'a missing sidecar is rejected' (Test-Throws { Assert-DumpMatchesSidecar -DumpPath $dump -SidecarPath (Join-Path $tempDir 'none.sha256') })
    foreach ($case in @(
            @{ Name = 'an empty sidecar'; Text = '' },
            @{ Name = 'a whitespace-only sidecar'; Text = "   `n" },
            @{ Name = 'a sidecar with only a hash'; Text = ('a' * 64) + "`n" },
            @{ Name = 'a sidecar with one space instead of two'; Text = ('a' * 64) + ' wb_20991231_235959.dump' + "`n" },
            @{ Name = 'a sidecar with a 63-character hash'; Text = ('a' * 63) + '  wb_20991231_235959.dump' + "`n" },
            @{ Name = 'a sidecar with an upper-case hash'; Text = ('A' * 64) + '  wb_20991231_235959.dump' + "`n" },
            @{ Name = 'a sidecar with non-hex characters'; Text = ('g' * 64) + '  wb_20991231_235959.dump' + "`n" },
            @{ Name = 'a sidecar naming another dump'; Text = ('a' * 64) + '  wb_20991231_000001.dump' + "`n" },
            @{ Name = 'a sidecar with trailing text'; Text = ('a' * 64) + '  wb_20991231_235959.dump extra' + "`n" },
            @{ Name = 'a two-line sidecar'; Text = $line + "`n" + $line + "`n" })) {
        [System.IO.File]::WriteAllText($sidecar, $case.Text)
        Assert-Case "$($case.Name) is rejected" (Test-Throws { Read-Sha256Sidecar -SidecarPath $sidecar -DumpFileName 'wb_20991231_235959.dump' })
    }
} finally {
    Remove-Item -LiteralPath $tempDir -Recurse -Force   # only the folder this script created
}

$results | ForEach-Object { Write-Host $_ }
if ($failures -gt 0) {
    Write-Host "$failures of $($results.Count) fingerprint and checksum cases FAILED"
    exit 1
}
Write-Host "ALL PASSED: $($results.Count) fingerprint and checksum cases"
exit 0
