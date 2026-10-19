# 真机冒烟：启动 / 存活 / 不崩 / 前台 / 截图（P0-4，2026-10-19）
# 用法见同目录 README.md。退出码 = 失败项数。

param(
    [string]$Adb = "",
    [string]$Apk = "",
    [int]$WaitSeconds = 15
)

$ErrorActionPreference = "Continue"
$Pkg = "com.example.zhengdao"
$Comp = "$Pkg/.MainActivity"
$OutDir = Join-Path $PSScriptRoot "out"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Resolve-Adb {
    param([string]$Given)
    if ($Given -and (Test-Path $Given)) { return $Given }
    $cands = @()
    if ($env:ANDROID_HOME) { $cands += (Join-Path $env:ANDROID_HOME "platform-tools\adb.exe") }
    if ($env:ANDROID_SDK_ROOT) { $cands += (Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe") }
    $cands += "C:\Users\guoli\AppData\Local\Android\Sdk\platform-tools\adb.exe"
    foreach ($c in $cands) { if (Test-Path $c) { return $c } }
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $null
}

$adb = Resolve-Adb $Adb
if (-not $adb) {
    Write-Host "FAIL 找不到 adb（用 -Adb 指定，或设 ANDROID_HOME）" -ForegroundColor Red
    exit 1
}
Write-Host "adb = $adb"
$fail = 0
function Check($name, [bool]$ok, $detail) {
    if ($ok) { Write-Host ("PASS {0,-10} {1}" -f $name, $detail) -ForegroundColor Green }
    else { Write-Host ("FAIL {0,-10} {1}" -f $name, $detail) -ForegroundColor Red; $script:fail++ }
}

# 1) 设备在线
$devRaw = & $adb devices
$devices = @($devRaw | Select-Object -Skip 1 | Where-Object { $_ -match "\sdevice$" })
Check "设备在线" ($devices.Count -ge 1) ("device 数=" + $devices.Count)

if ($devices.Count -lt 1) { Write-Host "没有可用设备，后续检查跳过"; exit $fail }
$serial = ($devices[0] -split "\s+")[0]
Write-Host "serial = $serial"

# 2) 可选安装
if ($Apk) {
    if (Test-Path $Apk) {
        $ins = & $adb -s $serial install -r $Apk 2>&1
        Check "安装" (($ins -join " ") -match "Success") ("install -r 返回: " + ($ins -join " "))
    } else {
        Check "安装" $false "APK 不存在: $Apk"
    }
}

# 记录启动前时间，便于只看启动之后的崩溃
$startMark = (Get-Date).ToUniversalTime().ToString("MM-dd HH:mm:ss.fff")
$launch = & $adb -s $serial shell am start -W -n $Comp 2>&1
$launchText = ($launch -join "`n")
Check "启动" (($launchText -match "Status: ok") -or ($launchText -match "Warning: Activity not started")) (($launchText -split "`n" | Select-Object -First 3) -join " | ")

# 3) 存活
Start-Sleep -Seconds $WaitSeconds
$pidText = (& $adb -s $serial shell pidof $Pkg 2>&1) -join ""
Check "存活" ($pidText.Trim().Length -gt 0) ("pidof = " + $pidText.Trim())

# 4) 不崩：只看启动标记之后的 AndroidRuntime
$log = & $adb -s $serial logcat -d -t 400 -s AndroidRuntime:E 2>&1
$relevant = $log | Select-String -Pattern "FATAL EXCEPTION" -SimpleMatch
$pkgCrash = $false
if ($relevant) {
    $after = $log | Select-String -Pattern $Pkg -SimpleMatch
    $pkgCrash = [bool]$after
}
Check "不崩" (-not $pkgCrash) ("FATAL EXCEPTION 涉及本包: " + $pkgCrash)

# 5) 在前台
$acts = (& $adb -s $serial shell dumpsys activity activities 2>&1) -join "`n"
$resumed = ($acts | Select-String -Pattern "topResumedActivity|mResumedActivity" -AllMatches)
$front = ($resumed -ne $null) -and (($resumed | Out-String) -match [regex]::Escape($Pkg))
Check "在前台" $front "topResumedActivity 含包名: $front"

# 截图
$shot = Join-Path $OutDir ("smoke-" + (Get-Date).ToString("yyyyMMdd-HHmmss") + ".png")
& $adb -s $serial exec-out screencap -p > $shot 2>$null
if ((Test-Path $shot) -and ((Get-Item $shot).Length -gt 1000)) {
    Write-Host "截图: $shot"
} else {
    Write-Host "WARN 截图失败或为空: $shot" -ForegroundColor Yellow
}

Write-Host ""
if ($fail -eq 0) { Write-Host "冒烟通过（5/5）" -ForegroundColor Green } else { Write-Host "冒烟失败项: $fail" -ForegroundColor Red }
exit $fail
