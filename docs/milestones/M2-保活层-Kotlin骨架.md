# M2 保活层 Kotlin 骨架（本里程碑工程约束与验收标准；实现以代码为准，约束与红线以本文档为准）

> 📌 **存储结论已反转（E-005，2026-10-06）**：App 真身下 `/sdcard` 读写均可用，SAF 镜像同步降级为**备用方案**。本文件不含存储方案约定；存储权威说明见 `docs/ERRATA.md` 与 `已知限制.md`。
>
> 项目：证道 · 依据：v3 文档 §5 防杀矩阵
> 核心原则：**SessionService（前台服务）拥有 tmux server 与 proot 常驻实例；终端 UI 只是 attach/detach 的视图——UI 死 ≠ 会话死。**

---

## 1. SessionService（前台服务）

```kotlin
// 前台服务：保活锚点。targetSdk 28 下无需声明 foregroundServiceType。
class SessionService : Service() {

    private val wakeLock: PowerManager.WakeLock by lazy {
        (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zhengdao:session")
            .apply { setReferenceCounted(false) }
    }

    private var tmuxServerPid: Long = 0L   // tmux server 所在 proot 进程

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        // 通知渠道必须在首次创建通知前创建（targetSdk 28 + Android 13+ 靠渠道触发权限弹窗）
        createNotificationChannel()
        acquireWakeLockIfActive()   // 仅在会话活跃时持有
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE_KEEP_ALIVE -> releaseWakeLock()   // 通知栏「暂停保持运行」
            ACTION_RESUME_KEEP_ALIVE -> acquireWakeLockIfActive()
            ACTION_STOP_SESSION -> stopSession()
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        // 通知必须「有用」：显示当前会话状态、运行时长、一键回到终端；降低被用户关闭的概率
        // 按钮：回到终端 / 暂停保持运行（切换 WakeLock）/ 停止会话
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_terminal)
            .setContentTitle("证道 · 会话运行中")
            .setContentText("tmux 会话 #${tmuxServerPid} · 已运行 xx:xx")
            .setOngoing(true)
            .setContentIntent(pendingIntentToTerminal())
            .addAction(0, if (wakeLock.isHeld) "暂停保活" else "保持运行", pauseIntent())
            .build()
    }

    private fun stopSession() {
        // 仅「停止会话」显式调用；进程被杀后重进 App 走 attach 恢复
        Runtime.getRuntime().exec("kill $tmuxServerPid")  // 实际走 sessionManager.kill()
        stopSelf()
    }
}
```

## 2. SessionManager（tmux 单会话兜底）

> v3.6 定案：**单会话模型**。全局只有一个 tmux 会话（`free`），自由终端与 Agent 安装/启动复用同一会话。无多会话、无会话切换器。多会话需求由 tmux window/pane 分屏满足（高级用户自己在会话内 `Ctrl+B` 分屏，不占额外 UI）。

```kotlin
class SessionManager(private val prootLauncher: ProotSessionLauncher) {

    // 全局唯一会话，固定名 free（自由终端 + Agent 安装/启动复用）
    fun ensureSession(): ProotSession {
        // 1. 查 tmux has-session -t free
        // 2. 不存在 -> prootLauncher.launch() 里先起 tmux new-session -d -s free
        // 3. 存在 -> 直接 attach（进程被杀后恢复的关键）
        // 返回值是 ProotSession（拥有 tmux server 的 proot 常驻实例）
    }

    fun attach(session: ProotSession, terminalView: TerminalView) {
        // PTY 桥接到终端 UI；仅视图层动作，不影响 tmux server 生命周期
    }

    fun detach(session: ProotSession) { /* UI 释放，proot 继续跑 */ }

    fun killSession() {
        // 仅「修复环境」等显式操作调用；执行 tmux kill-session -t free
    }
}
```

## 3. 常驻通知渠道（时机是关键）

```kotlin
fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            CHANNEL_ID, "会话状态", NotificationManager.IMPORTANCE_LOW  // LOW：无声音无打扰
        ).apply { description = "显示后台会话状态；关闭会导致会话无法保活" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        // 创建渠道即触发系统权限弹窗（Android 13+，targetSdk 28 下无法主动 requestPermissions）
        // 时机应放在 Onboarding 第①步（v3 §2），由用户主动点击触发，而非服务后台静默创建
    }
}
```

## 4. 电池优化白名单（直发渠道可直接弹）

```kotlin
fun requestIgnoreBatteryOptimizations(activity: Activity) {
    val pm = activity.getSystemService(POWER_SERVICE) as PowerManager
    if (!pm.isIgnoringBatteryOptimizations(activity.packageName)) {
        // GitHub 直发不受 Play 政策限制，可直接使用 ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
        activity.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
        )
    }
}
```

## 6. 内存治理（主动降耗，降低被「清道夫」盯上的概率）

> 设计说明：`ulimit -v` 限制的是**虚拟地址空间**而非物理内存。Node/V8 在 64 位下默认保留数 GB 虚拟地址空间，3GB 硬限会让 Claude Code/Hermes 直接崩。正确做法是**软监控 + 主动清理**（对普通用户零感知），而不是硬性地址空间上限。

**资源释放边界清单（v3.9 补，实现方必读——防止 `onTrimMemory` 误伤会话）**

`onTrimMemory` 与「释放内存」只允许触碰**可重建资源**。下列清单写死，任何一项越界 = 用户任务被杀：

| 资源 | 可释放 | 说明 |
|---|---|---|
| Coil / 图片缓存 | ✅ | 可重建，按需重新解码 |
| 空闲 DB 连接 | ✅ | 可重建，用时重开 |
| 非前台页面的 Compose 状态 / Bitmap | ✅ | 可重建 |
| TerminalView 屏幕外滚动缓冲（超限部分） | ⚠️ 谨慎 | 仅可裁剪**历史回滚行**，当前屏幕与光标状态不可动 |
| tmux server 进程 | ❌ | 会话锚点，杀了 = 全部会话丢失 |
| proot 主进程 | ❌ | 会话锚点，杀了 = 环境整体退出 |
| Agent 子进程（claude / hermes 等） | ❌ | 用户正在跑的任务 |
| 正在进行的下载 / 解压任务 | ❌ | 中断 = 从头再来 |

判据一句话：**只释放"丢了能免费重来"的东西；凡是"用户正在等的结果"，一律不动。**

```kotlin
// 内存治理：防杀矩阵第 4 层。目标 = 别让「内存枯竭强杀」从最后一行跑到前面来。
class MemoryGovernor(
    private val sessionManager: SessionManager,
    private val prootLauncher: ProotSessionLauncher,  // 获取 proot 主进程 pid
) {
    // 软上限：guest RSS 超限 -> 通知栏警告 + 建议用户清理（不主动杀，不误伤编译任务）
    fun startMonitoring() {
        // 每 30 秒读 /proc/<prootPid>/status 的 VmRSS（或 VmHWM 峰值）
        // 超过 warnThreshold（如 3GB）-> 通知栏发警告，建议清理
        // 超过 hardWarn（如 5GB）-> 警告 + 提示"一键释放内存"（UI 上），仍不自动杀
    }

    // 主动释放（用户在通知/设置页点「释放内存」时调用）：
    fun trimGuestMemory() {
        // 1. 在 guest 内执行 sync（落盘脏页）
        // 2. 依次 drop page cache / dentries / inodes：
        //    echo 3 > /proc/sys/vm/drop_caches  （rootfs 内 /proc 是 bind mount 到设备的，
        //    需 root 或 proot 透传；无权限则跳过，静默失败不影响体验）
        // 3. 不杀 tmux / proot / agent 进程——只释放缓存页
    }
}
```

**为什么不用 `ulimit -v` 做硬上限（合入方必须理解）**
- 虚拟地址空间 ≠ 物理内存。Node/V8/Java 都习惯保留巨大虚拟地址空间，物理占用往往只有几百 MB。
- 3GB 虚拟地址空间硬限会让 agent 在真实负载下直接 OOM/崩，制造"正常用也会死"的灾难。
- 编译类任务（npm install / tsc）瞬时内存峰值是合理的，硬限会误伤。
- 如果未来确需硬约束，正确做法是 `systemd-run --property=MemoryMax=`（cgroup v2）——Android 无 systemd，需换 cgroup 方案，作为 v1.x 技术储备，**不默认启用**。

## 7. 电池优化白名单引导（从"一次弹窗"升级为"两步走"）

```kotlin
// 第 1 步：系统电池白名单（直发渠道可直接弹 ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS）
fun requestIgnoreBatteryOptimizations(activity: Activity) {
    val pm = activity.getSystemService(POWER_SERVICE) as PowerManager
    if (!pm.isIgnoringBatteryOptimizations(activity.packageName)) {
        activity.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
        )
    }
}

// 第 2 步：国产 ROM 自启动/后台锁引导（设置页「国产 ROM 保活指南」内）
// 小米/澎湃：设置→应用设置→应用管理→证道→省电策略→「无限制」+ 自启动
// 华为：设置→应用→应用启动管理→证道→「手动管理」全开
// OPPO/一加：设置→电池→应用耗电管理→证道→「允许完全后台行为」
// vivo：设置→电池→后台耗电管理→证道→「允许后台高耗电」
// 荣耀：设置→应用→应用启动管理→证道→「手动管理」全开
// 第 2 步纯图文引导，App 内只放说明页 + 各 ROM 入口路径 + 一键跳到对应设置页（能跳则跳）
```

## 8. 被杀的检测与恢复（App 启动时）

```kotlin
class SessionRecovery(private val sessionManager: SessionManager) {
    fun onAppLaunch() {
        // 1. 检查上次是否有活跃会话标记（DataStore 持久化：proot pid + tmux socket，单会话无需会话名）
        // 2. 若有 -> sessionManager.ensureSession() 重新 attach
        //     （tmux server 若还活着则恢复现场；已死则重新拉起，用户感知为「重新连接」）
        // 3. UI 提示「已恢复上次会话」
    }
}
```

## 验收要点（对应 v3 §5 / §9）

- [ ] 息屏 30 分钟会话存活（WakeLock + FGS + 电池白名单齐备）
- [ ] `adb shell am kill <package>` 模拟被杀 -> 重进 App 自动恢复会话
- [ ] 通知栏「暂停保持运行」释放 WakeLock、再次点击恢复
- [ ] 关闭通知权限后 FGS 仍存活但通知不可见（降级体验记录）
- [ ] 内存治理：guest RSS 超阈值出现通知栏警告；「释放内存」后 RSS 显著下降且会话/agent 不中断
- [ ] 电池白名单两步引导：系统弹窗 + 国产 ROM 图文路径均可达，一键跳转设置页在主流 ROM 可用
- [ ] 安卓 10 / 12 / 14 / 15 真机矩阵各跑一遍

## 红线（v3 实测结论）

- **PROOT_NO_SECCOMP=1 禁止设置**（本机实测反而致命）
- proot 必须用 Termux fork（GPL，聚合分发登记 PROVENANCE）