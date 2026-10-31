# Build lock (2026-10-31 v2): one Gradle build at a time across ALL worktrees.
# v2 fixes: (1) ASCII-only messages (PowerShell 5.1 reads .ps1 as ANSI without BOM -> mojibake);
#           (2) lock file lives at the COMMON parent of all repos, so worktrees share one lock.
# Usage:
#   .\tools\build-lock.ps1 -Status
#   .\tools\build-lock.ps1 -Run ":app:testDebugUnitTest"
#   .\tools\build-lock.ps1 -Acquire   |  .\tools\build-lock.ps1 -Release
param(
  [switch]$Status,
  [switch]$Acquire,
  [switch]$Release,
  [string]$Run = "",
  [int]$TimeoutMin = 30
)
$ErrorActionPreference = "Stop"

# repo root = parent of tools/
$repoRoot = Split-Path -Parent $PSScriptRoot
# common parent (c:\Users\<user>\AndroidStudioProjects) -> shared by all worktrees
$commonRoot = Split-Path -Parent $repoRoot
$lock = Join-Path $commonRoot ".build-lock"
$me = "$env:COMPUTERNAME/$PID"

function Show-Lock {
  if (-not (Test-Path $lock)) { "(free: no lock)"; return }
  $txt = (Get-Content $lock -Raw).Trim()
  $age = ((Get-Date) - (Get-Item $lock).LastWriteTime).TotalMinutes
  "holder: $txt`nheartbeat: {0:N1} min ago" -f $age
}

if ($Status) { Show-Lock; exit 0 }

if ($Release) {
  if (Test-Path $lock) { Remove-Item $lock -Force }
  "released"; exit 0
}

if ($Acquire) {
  $deadline = (Get-Date).AddMinutes($TimeoutMin)
  while ($true) {
    if (Test-Path $lock) {
      $age = ((Get-Date) - (Get-Item $lock).LastWriteTime).TotalMinutes
      if ($age -gt 10) { "(stale) no heartbeat for {0:N1} min, taking over" -f $age; Remove-Item $lock -Force }
    }
    try {
      $fs = [System.IO.File]::Open($lock, 'CreateNew', 'Write')
      $bytes = [Text.Encoding]::ASCII.GetBytes("$me since $(Get-Date -Format o)")
      $fs.Write($bytes, 0, $bytes.Length); $fs.Close()
      "acquired: $me"; exit 0
    } catch {
      if ((Get-Date) -gt $deadline) { "timeout waiting for lock ($TimeoutMin min)"; exit 2 }
      Start-Sleep -Seconds 20
    }
  }
}

if ($Run -ne "") {
  & $PSCommandPath -Acquire | Write-Host
  if ($LASTEXITCODE -eq 2) { exit 2 }
  try {
    Push-Location $repoRoot
    & (Join-Path $repoRoot "gradlew.bat") $Run.Split(" ") --console=plain
    exit $LASTEXITCODE
  } finally {
    Pop-Location
    & $PSCommandPath -Release | Write-Host
  }
}

"usage: -Status | -Acquire | -Release | -Run ':<task>' [-TimeoutMin N]"
