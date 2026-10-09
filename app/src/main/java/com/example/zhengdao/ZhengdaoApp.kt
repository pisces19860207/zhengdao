package com.example.zhengdao

import android.app.Application
import com.example.zhengdao.rootfs.RunLog

/**
 * 应用入口。
 *
 * 存在的**唯一理由**：保证 [RunLog] 在**任何入口**都被初始化。
 *
 * ## 历史教训（2026-10-06）
 *
 * `RunLog.init()` 原先只在 `TerminalActivity` 里调用。WebView 时代用户必然先进
 * 终端页，所以日志一直有；换到 Compose 原生 UI 之后用户**根本不进终端**
 * → `appContext == null` → `RunLog.log()` 把日志**静默丢掉**，跨多轮构建零输出，
 * SSE 断连原因完全无法定位（当时还被误判成"异常分支没触发"）。
 *
 * 放在 Application 里初始化，生命周期覆盖所有 Activity 与进程入口，
 * **以后新增任何入口都不必再记得调 init**——这是根治，不是打补丁。
 */
class ZhengdaoApp : Application() {

    override fun onCreate() {
        super.onCreate()
        RunLog.init(this)
        logSignature()
        keepalive()
        autoCleanJunk()
    }

    /**
     * 保活留档（#5，2026-10-09）。
     *
     * 分两半，**同步 / 异步的界线是有意的**：
     * - [com.example.zhengdao.keepalive.KeepaliveWatcher.install] 同步跑（注册生命周期回调、
     *   `onTrimMemory`、未捕获异常处理器、巡检线程）——它不读磁盘也不起进程，必须赶在
     *   第一个 Activity 之前装好，否则"用户刚进界面就被杀"这一档会漏；
     * - [com.example.zhengdao.keepalive.KeepaliveArchive.onStartup] 丢后台线程——它要
     *   `logcat -d`（起一个进程）并读写若干文件，放在 `onCreate` 里会拖着冷启动。
     *   顺序上它**必须在 RunLog 之后**：归档出来的结论本身也要进日志。
     */
    private fun keepalive() {
        runCatching {
            com.example.zhengdao.keepalive.KeepaliveWatcher.install(this)
            Thread {
                runCatching {
                    com.example.zhengdao.keepalive.KeepaliveArchive.onStartup(this)
                    com.example.zhengdao.keepalive.KeepaliveWatcher.heartbeat(this, "进程启动（归档完成）")
                }
            }.apply { isDaemon = true; name = "zhengdao-keepalive-archive" }.start()
        }
    }

    /**
     * 启动签名自检（2026-10-09，ERRATA E-065）。
     *
     * 只落一行日志 —— 用户看得见的那份提示在 `MainActivity`（非官方包启动时弹一次）
     * 与设置页「关于」（常驻一行来源）。放这里是因为 [RunLog] 刚 init 完，
     * 日志是任何入口（含终端独立 Activity）都会有的共同出口。
     */
    private fun logSignature() {
        runCatching {
            when (com.example.zhengdao.util.SigningCheck.check(this)) {
                com.example.zhengdao.util.SigningCheck.Result.OFFICIAL ->
                    RunLog.log("启动自检：安装包签名 = 官方 ✓")
                com.example.zhengdao.util.SigningCheck.Result.UNOFFICIAL ->
                    RunLog.log("⚠️ 启动自检：安装包签名**不是官方的** —— 这个包被重新签过名（重打包）。如果你不是从作者 GitHub 下载的，建议换回官方包。")
                com.example.zhengdao.util.SigningCheck.Result.UNKNOWN ->
                    RunLog.log("启动自检：读不到安装包签名（已跳过）")
            }
        }
    }

    /**
     * 自动清理垃圾（2026-10-08 落地二档；**2026-10-10 按 Issue #3 接上一档**）。
     *
     * 用户原话：「终端的缓存机制还有自动缓存清理、自动清理垃圾信息可以有吗？」
     * ——代码层核实（当时）：`CacheCleaner.autoCleanNeeded()` / `maybeNotify()` **全仓库零调用点**，
     * 文档里那条"启动时 >500MB 才清"从未落地（另有 ERRATA 记档）。
     *
     * 现在这条路径 = `CacheCleaner.autoCleanIfDue()`：到阈值就清**第一档**（npm/uv/pip 与
     * Agent 包缓存、旧版安装包只留最新 2 个）+ **二档**（rootfs/tmp 里带固定指纹、且 24 小时
     * 没动过的宿主侧残留），清完发一条**静默通知**报释放量。
     *
     * 跳过条件是硬的（Issue #3 的原文要求）：会话活着、或 `AgentProcesses` 探到
     * uv / npm / apt / git / python / hermes / opencode 等进程在跑 ⇒ 只写日志、不删不通知。
     * 三档（`.hermes/tools`、rootfs 系统层、home 用户数据）**永不**参与自动清理。
     *
     * 一档里那几条官方 CLI（`npm cache clean` / `uv cache prune` / `apt-get clean`）仍然只走
     * 设置页按钮：它们要在 guest 里跑、输出应当可见可中断。这里删的是**宿主侧**能直接判定的
     * 缓存目录（同 `cleanAgentCaches` 的白名单），不冒充"跑过官方命令"。
     */
    private fun autoCleanJunk() {
        runCatching {
            Thread {
                runCatching {
                    val report = com.example.zhengdao.terminal.CacheCleaner.autoCleanIfDue(this)
                    if (report.freedMb > 0) {
                        com.example.zhengdao.terminal.CacheNotifier.cleaned(
                            this, report.freedMb, report.totalMb
                        )
                    }
                }
            }.start()
        }
    }
}
