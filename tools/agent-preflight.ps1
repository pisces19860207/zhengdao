<#
  agent-preflight.ps1 —— 【已改为薄壳】真正的实现统一在 tools/zd.py

  为什么改：这个仓库同时有多个 AI agent 在干活，工具各写一套必然分叉。
  2026-10-07 用户拍板：工具统一到 Python 单一入口 tools/zd.py（纯标准库、跨平台、三 agent 可用）。
  这个 .ps1 只负责把参数转发过去，**行为以 `python tools\zd.py preflight` 为准**。

  用法（在任意 worktree 里跑；本机 ExecutionPolicy = Restricted，必须带 -ExecutionPolicy Bypass）：
      powershell -ExecutionPolicy Bypass -File tools\agent-preflight.ps1
      powershell -ExecutionPolicy Bypass -File tools\agent-preflight.ps1 -Keywords 终端,单会话
      powershell -ExecutionPolicy Bypass -File tools\agent-preflight.ps1 -Paths app\src\main\java\...\x.kt
      powershell -ExecutionPolicy Bypass -File tools\agent-preflight.ps1 -MaxBehind 3 -NoFetch

  退出码：0 = 未发现阻塞项，可以开工；1 = 有需要先处理的问题；2 = 不在 git 仓库/找不到 zd.py
#>
[CmdletBinding()]
param(
    [string[]]$Keywords = @(),
    [string[]]$Paths = @(),
    [int]$MaxBehind = 0,
    [switch]$NoFetch
)

$ErrorActionPreference = 'Continue'

$root = (& git rev-parse --show-toplevel 2>$null)
if (-not $root) { Write-Host "！不在 git 仓库里，无法核对" -ForegroundColor Red; exit 2 }
$root = $root.Trim()

$zd = Join-Path $root 'tools\zd.py'
if (-not (Test-Path $zd)) {
    Write-Host "！找不到 tools\zd.py —— 你的分支可能太旧，先 git fetch origin 并同步" -ForegroundColor Red
    exit 2
}

$zdArgs = @($zd, 'preflight')
if ($Keywords.Count -gt 0) { $zdArgs += @('-k', ($Keywords -join ',')) }
foreach ($p in $Paths) { $zdArgs += @('-p', $p) }
if ($MaxBehind -gt 0) { $zdArgs += @('--max-behind', "$MaxBehind") }
if ($NoFetch) { $zdArgs += '--no-fetch' }

& python @zdArgs
exit $LASTEXITCODE
