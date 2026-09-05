# Firebase security rules linter (82차 audit follow-up).
# Scans firebase-database.rules.json for any ".read"/".write" node whose value is literal
# boolean true (an open rule) instead of a string condition, and scans firebase-storage.rules
# for any "allow ... if true" line. Exits non-zero and prints a warning list if anything is found.
# Run this before publishing new RTDB paths, or wire it into VM_BUILD_HANDOFF.md as a pre-deploy step.
#
# Usage: powershell -File tools\check-fb-rules.ps1

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$dbRulesPath = Join-Path $repoRoot "phone-lock-android\firebase-database.rules.json"
$storageRulesPath = Join-Path $repoRoot "phone-lock-android\firebase-storage.rules"

$openFindings = New-Object System.Collections.Generic.List[string]

function Test-OpenRule {
    param($Node, [string]$Path)
    if ($null -eq $Node) { return }
    if ($Node -is [System.Management.Automation.PSCustomObject]) {
        foreach ($prop in $Node.PSObject.Properties) {
            $childPath = if ($Path -eq "") { $prop.Name } else { "$Path.$($prop.Name)" }
            if (($prop.Name -eq ".read" -or $prop.Name -eq ".write") -and ($prop.Value -is [bool]) -and ($prop.Value -eq $true)) {
                $openFindings.Add("OPEN RULE: $childPath = true")
            }
            Test-OpenRule -Node $prop.Value -Path $childPath
        }
    }
}

if (Test-Path $dbRulesPath) {
    $json = Get-Content $dbRulesPath -Raw | ConvertFrom-Json
    Test-OpenRule -Node $json -Path ""
    Write-Host "[check-fb-rules] Scanned database rules: $dbRulesPath"
} else {
    Write-Host "[check-fb-rules] WARNING: database rules file not found at $dbRulesPath"
}

if (Test-Path $storageRulesPath) {
    $storageLines = Get-Content $storageRulesPath
    $lineNo = 0
    foreach ($line in $storageLines) {
        $lineNo++
        if ($line -match "allow\s+[^;]*if\s+true\s*;") {
            $openFindings.Add("OPEN STORAGE RULE: line $lineNo -> $($line.Trim())")
        }
    }
    Write-Host "[check-fb-rules] Scanned storage rules: $storageRulesPath"
} else {
    Write-Host "[check-fb-rules] WARNING: storage rules file not found at $storageRulesPath"
}

if ($openFindings.Count -gt 0) {
    Write-Host ""
    Write-Host "[check-fb-rules] FAILED - open rules found:" -ForegroundColor Red
    foreach ($f in $openFindings) { Write-Host "  - $f" -ForegroundColor Red }
    exit 1
}

Write-Host "[check-fb-rules] OK - no open (unconditional true) read/write rules found."
exit 0
