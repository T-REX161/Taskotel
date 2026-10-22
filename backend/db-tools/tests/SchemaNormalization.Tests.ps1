# Pure-function tests for the schema normalization in DbToolsCommon.ps1.
# No database connection and no external programs: it only calls PowerShell functions and writes
# temporary schema files into its own folder under $env:TEMP, which it removes at the end.
# Run: powershell -NoProfile -ExecutionPolicy Bypass -File SchemaNormalization.Tests.ps1
# Exit code 0 = all cases passed. Also run by DbToolsScriptsTest during `mvn test` on Windows.

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot '..\DbToolsCommon.ps1')

# The exact lines from the failed P4a-2 run: live database (form A) and its restored copy (form B).
$liveChecks = @(
    "    CONSTRAINT staff_availability_check CHECK (((availability)::text = ANY ((ARRAY['AVAILABLE'::character varying, 'UNAVAILABLE'::character varying])::text[]))),",
    "    CONSTRAINT staff_department_check CHECK (((department)::text = ANY ((ARRAY['HOUSEKEEPING'::character varying, 'FRONT_DESK'::character varying, 'MAINTENANCE'::character varying, 'ROOM_SERVICE'::character varying, 'FOOD_AND_BEVERAGE'::character varying, 'SECURITY'::character varying])::text[])))",
    "    CONSTRAINT tasks_department_check CHECK (((department)::text = ANY ((ARRAY['HOUSEKEEPING'::character varying, 'FRONT_DESK'::character varying, 'MAINTENANCE'::character varying, 'ROOM_SERVICE'::character varying, 'FOOD_AND_BEVERAGE'::character varying, 'SECURITY'::character varying])::text[]))),",
    "    CONSTRAINT tasks_priority_check CHECK (((priority)::text = ANY ((ARRAY['LOW'::character varying, 'MEDIUM'::character varying, 'HIGH'::character varying, 'URGENT'::character varying])::text[]))),",
    "    CONSTRAINT tasks_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'ASSIGNED'::character varying, 'IN_PROGRESS'::character varying, 'COMPLETED'::character varying, 'CANCELLED'::character varying])::text[])))",
    "    CONSTRAINT users_role_check CHECK (((role)::text = ANY ((ARRAY['ADMIN'::character varying, 'MANAGER'::character varying, 'STAFF'::character varying])::text[])))"
)
$restoredChecks = @(
    "    CONSTRAINT staff_availability_check CHECK (((availability)::text = ANY (ARRAY[('AVAILABLE'::character varying)::text, ('UNAVAILABLE'::character varying)::text]))),",
    "    CONSTRAINT staff_department_check CHECK (((department)::text = ANY (ARRAY[('HOUSEKEEPING'::character varying)::text, ('FRONT_DESK'::character varying)::text, ('MAINTENANCE'::character varying)::text, ('ROOM_SERVICE'::character varying)::text, ('FOOD_AND_BEVERAGE'::character varying)::text, ('SECURITY'::character varying)::text])))",
    "    CONSTRAINT tasks_department_check CHECK (((department)::text = ANY (ARRAY[('HOUSEKEEPING'::character varying)::text, ('FRONT_DESK'::character varying)::text, ('MAINTENANCE'::character varying)::text, ('ROOM_SERVICE'::character varying)::text, ('FOOD_AND_BEVERAGE'::character varying)::text, ('SECURITY'::character varying)::text]))),",
    "    CONSTRAINT tasks_priority_check CHECK (((priority)::text = ANY (ARRAY[('LOW'::character varying)::text, ('MEDIUM'::character varying)::text, ('HIGH'::character varying)::text, ('URGENT'::character varying)::text]))),",
    "    CONSTRAINT tasks_status_check CHECK (((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('ASSIGNED'::character varying)::text, ('IN_PROGRESS'::character varying)::text, ('COMPLETED'::character varying)::text, ('CANCELLED'::character varying)::text])))",
    "    CONSTRAINT users_role_check CHECK (((role)::text = ANY (ARRAY[('ADMIN'::character varying)::text, ('MANAGER'::character varying)::text, ('STAFF'::character varying)::text])))"
)

$results = New-Object System.Collections.Generic.List[string]
$failures = 0
function Assert-Case {
    param([string]$Name, [bool]$Condition)
    if ($Condition) { $results.Add("PASS  $Name") } else { $results.Add("FAIL  $Name"); $script:failures++ }
}

$tempDir = Join-Path $env:TEMP ('wb-schema-tests-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tempDir | Out-Null

# A minimal schema dump: the four application tables, each followed by the given CHECK lines
function New-SchemaFile {
    param([string]$Name, [string[]]$CheckLines, [string]$RestrictKey = 'k1', [string]$ColumnType = 'character varying(255)')
    $content = @(
        "\restrict $RestrictKey",
        'CREATE TABLE public.assignments (',
        '    id uuid NOT NULL',
        ');',
        'CREATE TABLE public.staff (',
        "    availability $ColumnType NOT NULL,"
    ) + $CheckLines + @(
        ');',
        'CREATE TABLE public.tasks (',
        '    id uuid NOT NULL',
        ');',
        'CREATE TABLE public.users (',
        '    id uuid NOT NULL',
        ');',
        "\unrestrict $RestrictKey"
    )
    $path = Join-Path $tempDir $Name
    [System.IO.File]::WriteAllText($path, ($content -join "`n") + "`n")
    return $path
}

function Test-SameSchema {
    param([string]$A, [string]$B)
    return [string]::Equals((Get-NormalizedSchema $A), (Get-NormalizedSchema $B), [System.StringComparison]::Ordinal)
}

try {
    # -- Each observed pair canonicalizes to the same text, and that text is exact --------
    for ($i = 0; $i -lt $liveChecks.Count; $i++) {
        $a = ConvertTo-CanonicalEnumCheck $liveChecks[$i]
        $b = ConvertTo-CanonicalEnumCheck $restoredChecks[$i]
        Assert-Case "observed pair $($i + 1): form A and form B canonicalize identically" ([string]::Equals($a, $b, [System.StringComparison]::Ordinal))
        Assert-Case "observed pair $($i + 1): both lines were recognised (not left unchanged)" (($a -ne $liveChecks[$i]) -and ($b -ne $restoredChecks[$i]))
    }
    Assert-Case 'canonical text keeps indentation, name, column, ordered values and trailing comma' (
        [string]::Equals((ConvertTo-CanonicalEnumCheck $liveChecks[0]),
            "    CONSTRAINT staff_availability_check CHECK [enum] availability IN ('AVAILABLE', 'UNAVAILABLE'),",
            [System.StringComparison]::Ordinal))
    Assert-Case 'canonical text without trailing comma keeps none' (
        [string]::Equals((ConvertTo-CanonicalEnumCheck $restoredChecks[5]),
            "    CONSTRAINT users_role_check CHECK [enum] role IN ('ADMIN', 'MANAGER', 'STAFF')",
            [System.StringComparison]::Ordinal))

    # -- Whole dumps: the observed live and restored forms compare equal ----------------------
    $live = New-SchemaFile 'live.sql' $liveChecks 'liveKey'
    $restored = New-SchemaFile 'restored.sql' $restoredChecks 'otherKey'
    Assert-Case 'whole schema: observed live vs restored forms compare equal' (Test-SameSchema $live $restored)

    # -- Any real change must still fail ----------------------------------------------------
    $changedValue = $restoredChecks.Clone(); $changedValue[0] = $changedValue[0].Replace("'UNAVAILABLE'", "'UNAVAILABLX'")
    Assert-Case 'changed allowed value fails' (-not (Test-SameSchema $live (New-SchemaFile 'value.sql' $changedValue)))

    $removedValue = $restoredChecks.Clone(); $removedValue[3] = $removedValue[3].Replace(", ('URGENT'::character varying)::text", '')
    Assert-Case 'removed allowed value fails' (-not (Test-SameSchema $live (New-SchemaFile 'removed.sql' $removedValue)))

    $addedValue = $restoredChecks.Clone(); $addedValue[5] = $addedValue[5].Replace("('STAFF'::character varying)::text", "('STAFF'::character varying)::text, ('GUEST'::character varying)::text")
    Assert-Case 'added allowed value fails' (-not (Test-SameSchema $live (New-SchemaFile 'added.sql' $addedValue)))

    $reordered = $restoredChecks.Clone(); $reordered[0] = "    CONSTRAINT staff_availability_check CHECK (((availability)::text = ANY (ARRAY[('UNAVAILABLE'::character varying)::text, ('AVAILABLE'::character varying)::text]))),"
    Assert-Case 'reordered allowed values fail (order is preserved)' (-not (Test-SameSchema $live (New-SchemaFile 'order.sql' $reordered)))

    $renamed = $restoredChecks.Clone(); $renamed[0] = $renamed[0].Replace('staff_availability_check', 'staff_availability_chk')
    Assert-Case 'changed constraint name fails' (-not (Test-SameSchema $live (New-SchemaFile 'name.sql' $renamed)))

    $otherColumn = $restoredChecks.Clone(); $otherColumn[0] = $otherColumn[0].Replace('(((availability)::text', '(((department)::text')
    Assert-Case 'changed column fails' (-not (Test-SameSchema $live (New-SchemaFile 'column.sql' $otherColumn)))

    $noComma = $restoredChecks.Clone(); $noComma[0] = $noComma[0].TrimEnd(',')
    Assert-Case 'missing trailing comma fails' (-not (Test-SameSchema $live (New-SchemaFile 'comma.sql' $noComma)))

    $lowercase = $restoredChecks.Clone(); $lowercase[0] = $lowercase[0].Replace("'AVAILABLE'", "'available'")
    Assert-Case 'lowercase value is not recognised and fails' (-not (Test-SameSchema $live (New-SchemaFile 'lower.sql' $lowercase)))
    Assert-Case 'lowercase value line is returned unchanged' ((ConvertTo-CanonicalEnumCheck $lowercase[0]) -ceq $lowercase[0])

    # -- Nothing else is normalized ---------------------------------------------------------
    Assert-Case 'unrelated statement difference still fails' (-not (Test-SameSchema $live (New-SchemaFile 'type.sql' $restoredChecks 'k2' 'character varying(100)')))
    $otherCheck = '    CONSTRAINT tasks_duration_check CHECK ((estimated_duration > 0)),'
    Assert-Case 'non-enum CHECK constraint is returned unchanged' ((ConvertTo-CanonicalEnumCheck $otherCheck) -ceq $otherCheck)
    $mixedForm = "    CONSTRAINT staff_availability_check CHECK (((availability)::text = ANY ((ARRAY[('AVAILABLE'::character varying)::text])::text[]))),"
    Assert-Case 'malformed mix of both forms is returned unchanged' ((ConvertTo-CanonicalEnumCheck $mixedForm) -ceq $mixedForm)
    $trailingText = $liveChecks[0] + ' -- note'
    Assert-Case 'line with extra trailing text is returned unchanged' ((ConvertTo-CanonicalEnumCheck $trailingText) -ceq $trailingText)
    Assert-Case 'CREATE TABLE line is returned unchanged' ((ConvertTo-CanonicalEnumCheck 'CREATE TABLE public.staff (') -ceq 'CREATE TABLE public.staff (')
    Assert-Case '\restrict lines are still ignored (different keys compare equal)' (Test-SameSchema (New-SchemaFile 'r1.sql' $liveChecks 'keyOne') (New-SchemaFile 'r2.sql' $liveChecks 'keyTwo'))
} finally {
    Remove-Item -LiteralPath $tempDir -Recurse -Force   # only the folder this script created
}

$results | ForEach-Object { Write-Host $_ }
if ($failures -gt 0) {
    Write-Host "$failures of $($results.Count) schema normalization cases FAILED"
    exit 1
}
Write-Host "ALL PASSED: $($results.Count) schema normalization cases"
exit 0
