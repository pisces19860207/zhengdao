# 真机冒烟（5 项，30 秒）

> P0-4（2026-10-19）。替代「每次都人肉点一遍」的最小网兜：**启动、存活、不崩、在前台、留一张截图**。
> 不代替功能测试（抱朴发消息会花模型额度，那条留人工）。

## 用法

```powershell
# 只跑冒烟（不装包）
.\scripts\smoke\device-smoke.ps1

# 先装 debug 包再冒烟
.\scripts\smoke\device-smoke.ps1 -Apk app\build\outputs\apk\debug\app-debug.apk

# 指定 adb / 等待秒数
.\scripts\smoke\device-smoke.ps1 -Adb "C:\...\adb.exe" -WaitSeconds 20
```

退出码 = 失败项数（0 全过）。截图落在 `scripts\smoke\out\`（已 gitignore）。

## 五项

| # | 检查 | 判据 |
|---|---|---|
| 1 | 设备在线 | `adb devices` 有且只有一个 `device`（unauthorized/offline 直接红） |
| 2 | 启动 | `am start -W` 返回 `Status: ok`，且 `LaunchState` 为 `COLD`/`WARM` |
| 3 | 存活 | 等待 N 秒后 `pidof com.example.zhengdao` 非空（没被系统/崩溃杀掉） |
| 4 | 不崩 | 启动后的 `AndroidRuntime:E` 里没有本包名 + `FATAL EXCEPTION` |
| 5 | 在前台 | `dumpsys activity activities` 顶部 resumed activity 是本包 |

## 纪律

- 建议接 USB、屏幕解锁后跑；模拟器也行。
- **不自动发消息**：那会消耗模型额度，且需要选会话，留人工。
- 失败时先看截图和 `adb logcat -d -b crash | Select-String com.example.zhengdao`。
