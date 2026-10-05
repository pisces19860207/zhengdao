// 独立开发声明：本文件为本项目从零编写。会话引擎为 Termux 官方
// terminal-emulator（Apache-2.0，v0.119.0-beta.3，聚合分发见 PROVENANCE.md）。
//
// ⚠️ 许可证更正（2026-10-05）：此前本行误标为 GPL-3.0。经核对上游
// termux-app-0.119.0-beta.3/LICENSE.md，"Exceptions" 一节明确写明：
// "Terminal Emulator for Android (jackpal/Android-Terminal-Emulator) code is
//  used which is released under Apache 2.0 license. Check terminal-view and
//  terminal-emulator libraries."
// 即 terminal-view / terminal-emulator 两个库为 Apache-2.0，仅 termux-app 主
// 应用本体为 GPL-3.0（本项目未聚合主应用）。误标为 GPL 会导致闭源决策误判。
package com.example.zhengdao.terminal

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.zhengdao.rootfs.RunLog
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

/**
 * M2/M3 会话持有者（单会话模型）：pty + tmux 客户端归本进程级对象所有，
 * 终端 UI（TerminalView）只是挂上来的视图——UI 关闭 ≠ 会话死。
 * 会话引擎 = Termux terminal-emulator（原生状态机，替代 WebView/xterm.js 架构）。
 *
 * 生命周期：start() 创建会话对象（进程尚未 spawn）→ 视图 attachSession →
 * 首次 updateSize 时 spawn → onEmulatorSet 回调（视图层注入 autocmd）。
 * 进程级语义：proot/tmux 是本进程子进程，进程被杀全部消亡——前台服务的
 * 职责是「阻止被杀」；重进 App 时进程活着 → attach 恢复，死了 → 全新启动。
 */
object SessionManager {

    private const val PREFS = "zhengdao-session"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null

    var session: TerminalSession? = null
        private set
    var startedAtMs: Long = 0L
        private set
    @Volatile
    var usesTmux: Boolean = false

    /** 当前会话是否为回退系统 shell（无 Debian 环境）。视图层据此拦下 autocmd 注入。 */
    @Volatile
    var isFallback: Boolean = false

    var onSessionDied: ((Int) -> Unit)? = null
    /** 视图刷新回调（TerminalActivity 设置：termView.onScreenUpdated()） */
    var onViewUpdate: (() -> Unit)? = null
    /** 复制转发（文字选择菜单 ACTION_COPY → 宿主实现写剪贴板） */
    var onCopyText: ((String) -> Unit)? = null
    /** 粘贴请求转发（ACTION_PASTE → 宿主实现读剪贴板写入会话） */
    var onPasteRequest: (() -> Unit)? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun isAlive(): Boolean = session?.isRunning == true

    fun start(context: Context): ProotLauncher.LaunchPlan {
        kill(context)
        val plan = ProotLauncher.buildLaunchPlan(context)
        val s = TerminalSession(
            plan.cmd,                       // 宿主侧 execve 目标（proot / 回退 shell）
            context.filesDir.absolutePath,  // 宿主侧 cwd
            plan.args, plan.env,
            1000,                           // 回滚行数
            client,
        )
        session = s
        startedAtMs = System.currentTimeMillis()
        isFallback = plan.isFallback
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong("started_at", startedAtMs).apply()
        SessionService.start(context)
        RunLog.log("会话启动（Termux 引擎）isFallback=$isFallback usesTmux=${plan.usesTmux}")
        return plan
    }

    fun write(text: String) {
        val s = session ?: return
        val bytes = text.toByteArray(Charsets.UTF_8)
        s.write(bytes, 0, bytes.size)
    }

    fun write(bytes: ByteArray) {
        session?.write(bytes, 0, bytes.size)
    }

    /**
     * 手动改 pty 尺寸：attach 恢复时先缩后放，触发 tmux 客户端 SIGWINCH 全量重绘。
     * 日常尺寸同步由 TerminalView 自动完成（视图尺寸变化即 updateSize），无需调用本方法。
     */
    fun resize(cols: Int, rows: Int) {
        runCatching { session?.updateSize(cols, rows, 0, 0) }
    }

    fun kill(context: Context) {
        val s = session ?: return
        session = null
        startedAtMs = 0L
        isFallback = false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove("started_at").apply()
        s.finishIfRunning()
        SessionService.stop(context)
        RunLog.log("会话已停止")
    }

    private fun onFinished(code: Int) {
        mainHandler.post {
            if (session?.isRunning == false) {
                session = null
                startedAtMs = 0L
                appContext?.let {
                    it.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().remove("started_at").apply()
                    SessionService.stop(it)
                }
                RunLog.log("会话退出 code=$code")
                onSessionDied?.invoke(code)
            }
        }
    }

    // ── TerminalSessionClient：引擎回调桥（视图刷新转发 + 退出处理）──
    private val client = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) { onViewUpdate?.invoke() }
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {
            val code = runCatching { finishedSession.getExitStatus() }.getOrDefault(-1)
            onFinished(code)
        }
        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            onCopyText?.invoke(text)
        }
        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            onPasteRequest?.invoke()
        }
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
        override fun getTerminalCursorStyle(): Int = 0
        override fun logError(tag: String, message: String) { RunLog.log("E: $message") }
        override fun logWarn(tag: String, message: String) { RunLog.log("W: $message") }
        override fun logInfo(tag: String, message: String) {}
        override fun logDebug(tag: String, message: String) {}
        override fun logVerbose(tag: String, message: String) {}
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
            RunLog.log("E: $message ${e.message}")
        }
        override fun logStackTrace(tag: String, e: Exception) {
            RunLog.log("E: ${e.message}")
        }
    }
}
