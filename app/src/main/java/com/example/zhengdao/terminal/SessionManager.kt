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
 * M2/M3 会话持有者（**全局单会话模型**）：pty + tmux 客户端归本进程级对象所有，
 * 终端 UI（TerminalView）只是挂上来的视图——UI 关闭 ≠ 会话死。
 * 会话引擎 = Termux terminal-emulator（原生状态机，替代 WebView/xterm.js 架构）。
 *
 * 生命周期：start() 创建会话对象（进程尚未 spawn）→ 视图 attachSession →
 * 首次 updateSize 时 spawn → onEmulatorSet 回调（视图层注入 autocmd）。
 * 进程级语义：proot/tmux 是本进程子进程，进程被杀全部消亡——前台服务的
 * 职责是「阻止被杀」；重进 App 时进程活着 → attach 恢复，死了 → 全新启动。
 *
 * ⚠️ 单会话模型的硬约束（用户 2026-10-07 定稿）：
 *   全局**只有一条** tmux 会话（[TMUX_SESSION]，名字固定不做参数化），
 *   分屏用 tmux 的 pane（`C-b %` / `C-b "`），**不开第二个窗口、也不开第二个会话**。
 *   换 Agent = 把这条会话整条 kill 掉再起一条新的——不存在"并存"。
 *   因此本对象只需记一个 [currentAgentId]（这条会话里跑的是谁），
 *   不需要"扫进程判重"那套东西。
 */
object SessionManager {

    private const val PREFS = "zhengdao-session"
    private const val KEY_CURRENT_AGENT = "current_agent"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null

    /**
     * 全局唯一的 tmux 会话名。
     *
     * 为什么是常量而不是参数：单会话模型下名字必须**处处一致**，否则"换个入口
     * 就 attach 到另一条会话"，用户会看到自己刚跑的东西凭空消失。
     * 终端里 `tmux ls` 永远只该有一行 `zhengdao:`。
     */
    const val TMUX_SESSION = "zhengdao"

    /**
     * 当前这条会话里跑的是哪个 Agent（null = 只有裸 bash / 不适用）。
     *
     * 为什么需要它：单会话模型下"点同一个 Agent 该 attach 复用、点别的该 kill 重开"
     * 全靠这一个判断。旧实现用"扫 /proc 数同名进程"来判重——那是"多窗口并存"
     * 思路的产物，方向反了（用户 2026-10-07 指出）：进程数只说明"有几个实例"，
     * 而单会话模型根本不允许并存，需要的是"这条会话里是谁"。
     *
     * 持久化到 [PREFS]：App 进程被杀后重启，还要知道上次跑的是谁才能把它接回来。
     */
    @Volatile
    var currentAgentId: String? = null
        private set

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
        // 进程重启后恢复"上次跑的是谁"——用户验收第 5 条（杀 App 再开，还是原来那个 Agent）
        // 就靠这一个字段。session 本身恢复不了（proot 是本进程子进程，一起被杀），
        // 所以能不能"回来还是它"，取决于这里读出的名字。
        currentAgentId = appContext
            ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.getString(KEY_CURRENT_AGENT, null)
    }

    /**
     * 登记"这条会话里现在是谁"。
     *
     * 传 null = 会话里没有 Agent（裸 bash、用户敲了 exit、宿主要换环境…）。
     * 写盘是刻意的：App 被杀时不会有任何回调，只能靠这份落盘的记录把 Agent 接回来。
     */
    fun setCurrentAgent(agentId: String?) {
        currentAgentId = agentId
        val ctx = appContext ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (agentId.isNullOrBlank()) remove(KEY_CURRENT_AGENT) else putString(KEY_CURRENT_AGENT, agentId)
        }.apply()
    }

    fun isAlive(): Boolean = session?.isRunning == true

    /**
     * 会话对象还在（进程已 spawn，或刚创建、即将在首次 updateSize 时 spawn）。
     *
     * 比 [isAlive] 宽一格，因为 Termux 引擎的 isRunning 在 spawn 之前是 false：
     * onNewIntent（复用实例，会立刻走一遍 attach）紧接着 onResume 又走一遍时，
     * 只认 isAlive 会把刚建好的会话当成"没有会话"，于是 kill 掉重来一次——
     * 同一句启动命令被注入两遍、正要启动的 Agent 被自己的第二次启动顶掉。
     */
    fun hasSession(): Boolean = session != null

    fun start(context: Context): ProotLauncher.LaunchPlan {
        // ⚠️ clearAgent=false：start() 的语义是"给**当前** Agent 起一条干净会话"，
        //    调用方（TerminalActivity 的路由）已经把它登记好了，这里不能抹掉。
        killInternal(context, clearAgent = false)
        val plan = ProotLauncher.buildLaunchPlan(context, TMUX_SESSION)
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
        RunLog.log(
            "会话启动（Termux 引擎）tmux=$TMUX_SESSION agent=${currentAgentId ?: "-"} " +
                "isFallback=$isFallback usesTmux=${plan.usesTmux}"
        )
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

    /**
     * 杀掉当前会话（单会话模型里 = 「killCurrentSession」）。
     *
     * 连 Agent 记录一起清掉：调用方的语义都是"这条会话作废了"（换 Agent、重装环境、
     * 用户主动关）——留着名字只会让下次进终端去接一个早就不存在的东西。
     */
    fun kill(context: Context) = killInternal(context, clearAgent = true)

    private fun killInternal(context: Context, clearAgent: Boolean) {
        if (clearAgent) setCurrentAgent(null)
        val s = session
        if (s == null) {
            // 会话早就没了（比如进程刚重启）——但 Agent 记录该清还是要清，
            // 否则下一次进终端会去"恢复"一个已经不存在的会话。
            if (clearAgent) SessionService.stop(context)
            return
        }
        session = null
        startedAtMs = 0L
        isFallback = false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove("started_at").apply()
        s.finishIfRunning()
        SessionService.stop(context)
        RunLog.log("会话已停止" + if (clearAgent) "（并清空当前 Agent 记录）" else "")
    }

    /**
     * 会话真的结束了才清理；**陈旧的退出回调必须丢掉**。
     *
     * ⚠️ 为什么必须比对"是哪一个会话结束了"，而不是看 [session] 字段现在的值：
     * [killInternal]（换 Agent / 重开终端都走它）会先 finish 掉旧会话、紧接着把**新**
     * 会话装上（[start]），而旧会话的 onSessionFinished 是引擎读线程**稍后**才回来的，
     * 本站的 post 又要等当前主线程消息跑完才执行。两条合起来：这条 runnable 真正跑起来
     * 时，[session] 往往已经是那条**新**会话——而新会话在首次 updateSize（spawn）之前
     * isRunning 也是 false（见 [hasSession] 的注释）。旧写法 `session?.isRunning == false`
     * 于是会把"刚要启动的新会话"当成"刚结束的旧会话"清掉：Agent 记录被抹（下次进终端
     * 接不回来）、前台服务被停（退到后台就可能被杀）、视图收到 [onSessionDied] 并关页。
     * 加身份比对后，凡是"结束的不是当前这条"一律丢弃，清理只可能发生在当前会话身上。
     */
    private fun onFinished(finished: TerminalSession, code: Int) {
        mainHandler.post {
            if (session !== finished) return@post
            if (!finished.isRunning) {
                session = null
                startedAtMs = 0L
                // 会话是「自己结束」的（用户敲了 exit / tmux 会话被拆掉），
                // 说明那条会话里已经什么都不跑了 —— 清掉记录，下次进终端不该再
                // 拿一个已经退出的 Agent 去"恢复"。注意：App 进程被杀时本回调
                // **不会触发**（进程直接消失），所以"杀 App 再开还是它"仍然成立。
                setCurrentAgent(null)
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
            onFinished(finishedSession, code)
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
