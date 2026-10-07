<#
  install-hooks.ps1 —— 【已改为薄壳】真正的实现统一在 tools/zd.py

  为什么需要安装这一步：.git/hooks 不进版本库，而这里所有 worktree 共用同一个 .git，
  所以「装一次」就等于给所有 agent 的工作树都上了闸。

  为什么改成薄壳：2026-10-07 用户拍板，工具统一到 Python 单一入口 tools/zd.py。
  这个 .ps1 只是方便 Windows 上的人/agent 直接双击式调用，**行为以 `python tools\zd.py install-hooks` 为准**
  （由 zd.py 负责 CRLF→LF、去 BOM、chmod，并在装完后可以立刻用 hooks-status 自证）。

  用法：  powershell -ExecutionPolicy Bypass -File tools\install-hooks.ps1
  退出码：0 = 装好；1 = 出错；2 = 不在 git 仓库/找不到 zd.py
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Continue'

$root = (& git rev-parse --show-toplevel 2>$null)
if (-not $root) { Write-Host "！不在 git 仓库里，无法安装 hook" -ForegroundColor Red; exit 2 }
$root = $root.Trim()

$zd = Join-Path $root 'tools\zd.py'
if (-not (Test-Path $zd)) {
    Write-Host "！找不到 tools\zd.py —— 你的分支可能太旧，先 git fetch origin 并同步" -ForegroundColor Red
    exit 2
}

& python $zd install-hooks
$code = $LASTEXITCODE
if ($code -ne 0) { exit $code }

Write-Host ""
Write-Host "自证一遍（库里 vs 已装是否逐字节一致）：" -ForegroundColor Cyan
& python $zd hooks-status
exit $LASTEXITCODE
