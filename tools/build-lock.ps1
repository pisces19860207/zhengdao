# 构建锁（2026-10-31）：同一时刻只允许一个 Gradle 构建
# 用法：
#   .\tools\build-lock.ps1 -Status                 # 看谁持有
#   .\tools\build-lock.ps1 -Run ":app:testDebugUnitTest"   # 抢锁后跑（跑完自动释放）
#   .\tools\build-lock.ps1 -Acquire / -Release     # 手动抢/放
param(
  [switch]$Status,
  [switch]$Acquire,
  [switch]$Release,
  [string]$Run = "",
  [int]$TimeoutMin = 30
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$lock = Join-Path $root ".build-lock"
$me = "$env:COMPUTERNAME/$PID"

function Show-Lock {
  if (-not (Test-Path $lock)) { "（无锁：空闲）"; return }
  $txt = Get-Content $lock -Raw
  $age = ((Get-Date) - (Get-Item $lock).LastWriteTime).TotalMinutes
  "持有者：$txt`n心跳：{0:N1} 分钟前" -f $age
}

if ($Status) { Show-Lock; exit 0 }
if ($Release) { if (Test-Path $lock) { Remove-Item $lock -Force }; "已释放"; exit 0 }
if ($Acquire) {
  $deadline = (Get-Date).AddMinutes($TimeoutMin)
  while ($true) {
    if (Test-Path $lock) {
      $age = ((Get-Date) - (Get-Item $lock).LastWriteTime).TotalMinutes
      if ($age -gt 10) { "(强制) 僵尸锁 {0:N1} 分钟无心跳，抢占" -f $age; Remove-Item $lock -Force }
    }
    try {
      $fs = [System.IO.File]::Open($lock, 'CreateNew', 'Write')
      $bytes = [Text.Encoding]::UTF8.GetBytes("$me 开始于 $(Get-Date -Format o)")
      $fs.Write($bytes, 0, $bytes.Length); $fs.Close()
      "已抢到锁：$me"; exit 0
    } catch {
      if ((Get-Date) -gt $deadline) { "等锁超时（$TimeoutMin 分钟）"; exit 2 }
      Start-Sleep -Seconds 20
    }
  }
}
if ($Run -ne "") {
  & $PSCommandPath -Acquire | Write-Host
  if ($LASTEXITCODE -eq 2) { exit 2 }
  try {
    Push-Location $root
    & .\gradlew.bat $Run.Split(" ") --console=plain
    exit $LASTEXITCODE
  } finally {
    Pop-Location
    & $PSCommandPath -Release | Write-Host
  }
}
"用法：-Status | -Acquire | -Release | -Run ':<task>' [-TimeoutMin N]"
