<#
  install-hooks.ps1 —— 把 tools/hooks/ 下的闸门装进这个仓库的 .git/hooks

  为什么需要安装这一步：.git/hooks 不进版本库，而这里所有 worktree 共用同一个 .git，
  所以「装一次」就等于给所有 agent 的工作树都上了闸。

  用法：  powershell -ExecutionPolicy Bypass -File tools\install-hooks.ps1
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = (& git rev-parse --show-toplevel).Trim()
$common = (& git rev-parse --path-format=absolute --git-common-dir).Trim()
if (-not $common) { Write-Host "！拿不到 .git 公共目录" -ForegroundColor Red; exit 2 }

$srcDir = Join-Path $root 'tools\hooks'
$dstDir = Join-Path $common 'hooks'
New-Item -ItemType Directory -Force -Path $dstDir | Out-Null

$n = 0
Get-ChildItem -Path $srcDir -File | ForEach-Object {
    $dst = Join-Path $dstDir $_.Name
    Copy-Item -Path $_.FullName -Destination $dst -Force
    Write-Host ("  装上 {0}  ->  {1}" -f $_.Name, $dst) -ForegroundColor Green
    $n++
    if ($_.Name -like 'pre-*' -or $_.Name -like 'post-*') {
        # Git for Windows 用 MSYS sh 跑 hook，确认它不是 CRLF 结尾（CRLF 会让 #!/bin/sh 解析失败）
        $bytes = [System.IO.File]::ReadAllBytes($dst)
        $crlf = $false
        for ($i = 0; $i -lt [Math]::Min($bytes.Length - 1, 4096); $i++) {
            if ($bytes[$i] -eq 13 -and $bytes[$i + 1] -eq 10) { $crlf = $true; break }
        }
        if ($crlf) {
            $txt = [System.IO.File]::ReadAllText($dst) -replace "`r`n", "`n"
            [System.IO.File]::WriteAllText($dst, $txt, (New-Object System.Text.UTF8Encoding($false)))
            Write-Host "    （已把 CRLF 换成 LF，否则 sh 跑不起来）" -ForegroundColor Yellow
        }
    }
}

Write-Host ""
Write-Host "装好 $n 个 hook，公共 .git = $common" -ForegroundColor Cyan
Write-Host "所有 worktree 立即生效。紧急绕过：`$env:ZHENGDAO_HOOK_BYPASS='1' 再 commit。" -ForegroundColor Cyan
