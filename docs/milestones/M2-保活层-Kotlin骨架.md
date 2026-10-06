# M2 保活层 Kotlin 骨架（本里程碑工程约束与验收标准；实现以代码为准，约束与红线以本文档为准）

> 📌 **存储结论已反转（E-005，2026-10-06）**：App 真身下 `/sdcard` 读写均可用，SAF 镜像同步降级为**备用方案**。本文件不含存储方案约定；存储权威说明见 `docs/ERRATA.md` 与 `已知限制.md`。
> 📌 **执行总览**：M1-M5 与 P1-P8 的整合路线图见 `证道-执行路线图.md`（本文档为功能骨架，整体顺序与优先级以路线图为准）。
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
        // ⚠️ 顺序要求（2026-10-06 审核修正）：通知渠道必须在首次创建通知**之前**建好，
        //否则首次 startForeground 的通知无渠道归属、渠道相关的权限弹窗也不触发。
        // 骨架原写法把 startForeground 放在 createNotificationChannel 之前，与下方注释矛盾，已调换。
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        acquireWakeLockIfActive()   // 仅在会话活跃时持有
    }

    override fun onDestroy() {
        // ⚠️ 必须释放：WakeLock 不释放会持续耗电并阻止 CPU 休眠（骨架原缺此方法）
        releaseWakeLock()
        super.onDestroy()
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
        // ⚠️ pid 未就绪（初值 0）时不显示 pid 段，避免渲染成「tmux 会话 #0」
        val sessionLine =
            if (tmuxServerPid > 0) "tmux 会话 #$tmuxServerPid · 已运行 xx:xx" else "正在建立会话…"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_terminal)
            .setContentTitle("证道 · 会话运行中")
            .setContentText(sessionLine)
            .setOngoing(true)
            .setContentIntent(pendingIntentToTerminal())
            // ⚠️ 图标不可传 0：用自有 drawable，否则通知按钮无图标（部分 ROM 直接不显示）
            .addAction(R.drawable.ic_pause, if (wakeLock.isHeld) "暂停保活" else "保持运行", pauseIntent())
            .build()
    }

    private fun stopSession() {
        // 仅「停止会话」显式调用；进程被杀后重进 App 走 attach 恢复
        // ⚠️ 实现走 sessionManager.killSession()（tmux kill-session -t zhengdao）。
        //    骨架原写 Runtime.exec("kill $tmuxServerPid") 有两个问题：① kill 的是宿主 pid，
        //    而目标是 guest 内 proot 里的 tmux server，两者命名空间与权限模型不同；
        //    ② 会额外拉起一个 system shell 进程。勿照抄。
        sessionManager.killSession()
        stopSelf()
    }
}
```

## 2. SessionManager（tmux 会话兜底）

> v3.6 定案：**单会话模型**。自由终端与 Agent 安装/启动复用同一会话。无多会话、无会话切换器。多会话需求由 tmux window/pane 分屏满足（高级用户自己在会话内 `Ctrl+B` 分屏，不占额外 UI）。
>
> 🔺 **v3.11 例外（2026-10-06，代码已落地 `3f27844`）**：
> - 自由终端会话在代码中名为 **`zhengdao`**（本文档与 v3.6 文字稿曾写作 `free`，**以代码为准**）。
> - **太极 Tab 拥有独立的 `taiji` 会话**，"单会话"约束的对象是**洞天 + 丹房**，不含太极。两个会话并存于同一 tmux server。
> - **`SessionManager.ensureSession()` 需支持会话名参数**（默认 `zhengdao`，太极传 `taiji`）——原v3.6 收窄为"无 name 参数"已随太极落地分叉，实现方不要照旧签名。
> - **只有 `zhengdao` 允许 `tmux kill-server`**（杀 server 会连带杀掉太极会话），太极侧只attach-or-create。
> - **XDG 隔离由pane 主程序承担**：`taiji` 会话 pane = `/usr/local/bin/taiji` wrapper（export XDG 四目录 → `exec opencode`），`zhengdao` 不注入任何 `XDG_*`。详见执行路线图 §2 P3。

```kotlin
class SessionManager(private val prootLauncher: ProotSessionLauncher) {

    /** 主会话（洞天）：固定名 zhengdao。可 kill-server 兜底（孤儿 server 会让attach 失败）。 */
    fun ensureMainSession(): ProotSession {
        // tmux kill-server 2>/dev/null; exec tmux new-session -A -s zhengdao
        // pane = /bin/bash -l（不注入 XDG_*，走 guest 默认 /root/.config、/root/.local/share）
    }

    /** 副会话（太极）：不 kill server，直接 attach-or-create；pane = XDG 隔离 wrapper。 */
    fun ensureIsolatedSession(name: String, paneCmd: String): ProotSession {
        // exec tmux new-session -A -s $name $paneCmd
        // ⚠️ pane 主程序必须是 taiji wrapper，直接写 opencode 会绕过 wrapper、XDG 全不注入
    }
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

## 4. 电池优化白名单（两步引导的第 1 步，代码见 §6）

> 完整的两步引导（系统白名单 + 国产 ROM 自启动/后台锁图文）见 **§6**。本节只标注入口，勿重复实现。

## 5. 内存治理（主动降耗，降低被「清道夫」盯上的概率）

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
    /** @return true = 确实释放了；false = 当前环境不支持（UI 须如实告知用户） */
    fun trimGuestMemory(): Boolean {
        // 1. 在 guest 内执行 sync（落盘脏页）
        // 2. 依次 drop page cache / dentries / inodes：
        //    echo 3 > /proc/sys/vm/drop_caches
        //
        // ⚠️ 2026-10-06 审核补：proot + SELinux 下这一步**几乎必然失败**
        //   （guest 的 /proc/sys 由宿主 bind mount，内核参数写入口对非 root 进程只读）。
        //   实现要求：**不要假装成功**——
        //   返回 Boolean 给 UI，失败时明确提示「当前 ROM 不支持手动释放内存」，
        //   而不是弹个"已释放"却什么都没发生。用户点了没反应会直接失去信任。
        //   可选替代：能 root 时引导用户自行执行，或直接建议重启 guest。
        // 3. 不杀 tmux / proot / agent 进程——只释放缓存页
    }
}
```

**为什么不用 `ulimit -v` 做硬上限（合入方必须理解）**
- 虚拟地址空间 ≠ 物理内存。Node/V8/Java 都习惯保留巨大虚拟地址空间，物理占用往往只有几百 MB。
- 3GB 虚拟地址空间硬限会让 agent 在真实负载下直接 OOM/崩，制造"正常用也会死"的灾难。
- 编译类任务（npm install / tsc）瞬时内存峰值是合理的，硬限会误伤。
- 如果未来确需硬约束，正确做法是 `systemd-run --property=MemoryMax=`（cgroup v2）——Android 无 systemd，需换 cgroup 方案，作为 v1.x 技术储备，**不默认启用**。

## 6. 电池优化白名单引导（从"一次弹窗"升级为"两步走"）

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
        // 1. 检查上次是否有活跃会话标记（DataStore 持久化：proot pid + tmux socket）
        // 2. 若有 -> sessionManager 重新 attach（zhengdao 主会话 + taiji 太极会话，各自独立恢复）
        // 3. UI 提示「已恢复上次会话」
        //
        // ⚠️ 措辞红线（2026-10-06 审核）：**不要对外宣称"恢复现场"**。proot 与 tmux server
        //    共享 App 同一 UID，Android 的 LMK / 厂商清理按 UID 杀整棵进程树——一旦进程树
        //    被整体清除，tmux server 已死，现场（任务/日志/临时文件）必然丢失。此时只能
        //    "重建运行环境"，保住的是"已装 Agent 不必重装"这一层便利。
        //    UI 文案统一用「已重建运行环境」或「已恢复上次会话」，禁用「现场已恢复」。
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