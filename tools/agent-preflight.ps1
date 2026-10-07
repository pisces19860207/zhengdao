<#
  agent-preflight.ps1 —— 开工前的核对闸门（证道 zhengdao）

  为什么需要它：
    这个仓库同时有多个 AI agent 在干活（WorkBuddy / Zcode / 其它），各自停在自己的旧提交上，
    开工前不核对，于是同一个功能被反复实现、删了又加。这个脚本把「动手前先核对」变成一条命令。

  用法（在任意 worktree 里跑）：
      powershell -ExecutionPolicy Bypass -File tools\agent-preflight.ps1
      powershell -ExecutionPolicy Bypass -File tools\agent-preflight.ps1 -Keywords 终端,SessionRouter,单会话
      powershell -ExecutionPolicy Bypass -File tools\agent-preflight.ps1 -Paths app/src/main/java/com/example/zhengdao/settings/ApiKeyStore.kt

  退出码：0 = 未发现阻塞项，可以开工
          1 = 有需要先处理的问题（落后 / 关键词命中 / 复活风险）
#>
[CmdletBinding()]
param(
    [string[]]$Keywords = @(),
    [string[]]$Paths = @(),
    [int]$MaxBehind = 0,
    [switch]$NoFetch
)

$ErrorActionPreference = 'Continue'
$gitArgs = @('-c', 'core.quotePath=false')

$root = (& git rev-parse --show-toplevel 2>$null)
if (-not $root) { Write-Host "！不在 git 仓库里，无法核对" -ForegroundColor Red; exit 2 }
$root = $root.Trim()
Set-Location $root

$blockers = New-Object System.Collections.ArrayList
function Say($msg, $color) {
    if ($color) { Write-Host $msg -ForegroundColor $color } else { Write-Host $msg }
}
function Sec($t) { Write-Host ""; Say "==== $t ====" 'Cyan' }

Sec "0. 同步远端"
if (-not $NoFetch) {
    & git @gitArgs fetch --prune --quiet origin 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Say "  ！git fetch 失败（离线？）——下面的判定基于本地上次的 origin/main，可能已过时" 'Yellow'
    }
}
$baseRef = 'origin/main'
$baseSha = (& git rev-parse --short $baseRef 2>$null)
if (-not $baseSha) { Say "！找不到 $baseRef，无法比对" 'Red'; exit 2 }
Say "  基准 $baseRef = $($baseSha.Trim())"

Sec "1. 这个仓库有几个工作树、各自停在哪"
$wtLines = & git @gitArgs worktree list --porcelain
$wts = @()
$cur = $null
foreach ($line in $wtLines) {
    if ($line -like 'worktree *') {
        if ($cur) { $wts += $cur }
        $cur = [ordered]@{ path = $line.Substring(9); head = ''; branch = '(detached)' }
    } elseif ($line -like 'HEAD *') { $cur.head = $line.Substring(5) }
    elseif ($line -like 'branch *') { $cur.branch = $line.Substring(7) -replace '^refs/heads/', '' }
}
if ($cur) { $wts += $cur }
foreach ($w in $wts) {
    $lr = (& git -C $w.path rev-list --left-right --count "$baseRef...HEAD" 2>$null)
    $behind = '?'; $ahead = '?'
    if ($lr) { $parts = ($lr -split '\s+') | Where-Object { $_ -ne '' }; $behind = $parts[0]; $ahead = $parts[1] }
    $dirty = @(& git -C $w.path status --porcelain 2>$null).Count
    $mark = ''
    if ($behind -ne '?' -and [int]$behind -gt 0) { $mark += " 落后$behind" }
    if ($dirty -gt 0) { $mark += " 未提交$dirty" }
    if ($mark -eq '') { $mark = ' 干净且同步' }
    $isCur = if ($w.path -eq $root) { ' <-当前' } else { '' }
    Say ("  {0,-46} {1,-34} {2}{3}{4}" -f $w.path, $w.branch, $w.head.Substring(0, [Math]::Min(7, $w.head.Length)), $mark, $isCur)
}

Sec "2. 我在哪、落后多少"
$curBranch = (& git symbolic-ref --short -q HEAD 2>$null)
if (-not $curBranch) { $curBranch = '(detached HEAD)' } else { $curBranch = $curBranch.Trim() }
$curHead = (& git rev-parse --short HEAD).Trim()
Say "  分支 = $curBranch     HEAD = $curHead"
$lr = & git rev-list --left-right --count "$baseRef...HEAD"
$parts = ($lr -split '\s+') | Where-Object { $_ -ne '' }
$behind = [int]$parts[0]; $ahead = [int]$parts[1]
Say "  相对 ${baseRef}：落后 $behind 个提交，领先 $ahead 个提交"
if ($behind -gt $MaxBehind) {
    [void]$blockers.Add("HEAD 落后 $baseRef $behind 个提交（先 git fetch + rebase/merge 再动手）")
    Say "  ✗ 你落后了：你看到的代码不是大家看到的代码，在这里动手就是在重做别人已经做完的事" 'Red'
    Say "    先看一眼别人改了什么：" 'Yellow'
    & git log --oneline --date=short --pretty=format:'      %h %ad %s' "$curHead..$baseRef" 2>$null | Select-Object -First 25 | ForEach-Object { Say $_ 'DarkGray' }
} else {
    Say "  ✓ 没有落后" 'Green'
}
$dirtyCur = @(& git status --porcelain).Count
if ($dirtyCur -gt 0) {
    Say "  ！本工作树有 $dirtyCur 个未提交改动——它们随时可能被 checkout/rebase/清理冲掉" 'Yellow'
}

Sec "3. 这是不是已经做过了（关键词反查）"
if ($Keywords.Count -eq 0) {
    Say "  （没给 -Keywords，跳过。开工前建议带上功能关键词再跑一次）" 'DarkGray'
} else {
    foreach ($kw in $Keywords) {
        $code = @(& git log --all --date=short --pretty=format:'%h %ad %s' -S"$kw" 2>$null)
        $msg = @(& git log --all --date=short --pretty=format:'%h %ad %s' --grep="$kw" 2>$null)
        $hits = @($code + $msg | Where-Object { $_ } | Select-Object -Unique)
        if ($hits.Count -eq 0) {
            Say ("  [{0}] 无命中" -f $kw) 'Green'
        } else {
            [void]$blockers.Add("关键词 [$kw] 在历史里有 $($hits.Count) 条命中，先确认不是重复劳动")
            Say ("  [{0}] 命中 {1} 条 —— 先读这些再决定要不要重写：" -f $kw, $hits.Count) 'Red'
            $hits | Select-Object -First 12 | ForEach-Object { Say "      $_" 'DarkGray' }
        }
    }
}

Sec "4. 复活检测（你要新增的文件，历史上是不是被删过）"
if ($Paths.Count -eq 0) {
    Say "  （没给 -Paths，跳过。要新建文件时建议带上它的路径再跑一次）" 'DarkGray'
} else {
    foreach ($p in $Paths) {
        $del = @(& git log --all --diff-filter=D --date=short --pretty=format:'%h %ad %s' -- $p 2>$null)
        if ($del.Count -eq 0) {
            Say ("  {0} —— 没被删过" -f $p) 'Green'
        } else {
            [void]$blockers.Add("$p 在历史上被删除过，复活它必须先问用户")
            Say ("  ✗ {0} 被删除过：" -f $p) 'Red'
            $del | Select-Object -First 6 | ForEach-Object { Say "      $_" 'DarkGray' }
        }
    }
}

Sec "5. 结论"
if ($blockers.Count -eq 0) {
    Say "  ✓ 未发现阻塞项。开工前再去 docs/FEATURE-LEDGER.md 的 §2 扫一眼这个功能在不在。" 'Green'
    exit 0
} else {
    Say "  ✗ 发现 $($blockers.Count) 个需要先处理的问题：" 'Red'
    $blockers | ForEach-Object { Say "      - $_" 'Red' }
    Say "  处理顺序：先同步，再查重，最后才写代码。" 'Yellow'
    exit 1
}
