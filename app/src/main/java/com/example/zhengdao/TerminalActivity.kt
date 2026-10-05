// 独立开发声明：本文件为本项目从零编写。终端渲染/模拟引擎为 Termux 官方
// terminal-emulator + terminal-view（Apache-2.0，v0.119.0-beta.3，聚合分发见
// PROVENANCE.md 与 THIRD-PARTY-LICENSES.md）。
//
// ⚠️ 许可证更正（2026-10-05）：此前误标为 GPL-3.0。上游 termux-app/LICENSE.md
// 的 Exceptions 一节明确：terminal-view 与 terminal-emulator 为 Apache-2.0，
// 仅 termux-app 主应用本体为 GPL-3.0（本项目未聚合主应用）。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.example.zhengdao.rootfs.RootfsDownloader
import com.example.zhengdao.rootfs.RootfsInstaller
import com.example.zhengdao.rootfs.RunLog
import com.example.zhengdao.ui.AgentRepository
import com.example.zhengdao.terminal.ProotLauncher
import com.example.zhengdao.terminal.SessionManager
import com.example.zhengdao.terminal.TerminalPrefs
import com.example.zhengdao.terminal.SessionService
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 全屏终端 Activity（Mac 风格窗卡 + Termux 原生 TerminalView + 快捷键条）。
 *
 * 架构（原生终端方案，替代 WebView/xterm.js——性能对齐 Termux/太墟）：
 *   键盘/快捷键条/粘贴 → SessionManager.write → pty → 子进程（proot→tmux→bash）
 *   子进程输出 → Termux 引擎（原生状态机）→ TerminalView 自绘
 * 会话归 SessionManager 进程级持有（M2：UI 关 ≠ 会话死，前台服务保活）。
 */
class TerminalActivity : ComponentActivity(), com.termux.view.TerminalViewClient,
    com.termux.terminal.TerminalSessionClient {

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var termView: TerminalView
    private var toolbarTitle: TextView? = null
    private var ctrlButton: TextView? = null
    private var shiftButton: TextView? = null
    private var stickyCtrl = false
    private var stickyShift = false
    private val installPromptShown = AtomicBoolean(false)
    private val installing = AtomicBoolean(false)
    /** 当前会话是否由 tmux 保持（绿点分屏按钮的前置条件） */
    private var usesTmux = false
    /** 待执行的自动命令（一键安装/启动）；attach 与 fresh 两条路径都要注入 */
    private var pendingAutocmd: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 终端打开期间保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        RootfsInstaller.cleanupPartial(applicationContext)
        RunLog.init(applicationContext)
        SessionManager.init(applicationContext)
        // 只在全新启动读取（Activity 异常重建会带原 intent，避免同一命令重跑两遍）
        if (savedInstanceState == null) {
            pendingAutocmd = intent?.getStringExtra("autocmd")?.takeIf { it.isNotBlank() }
        }
        setContentView(R.layout.activity_main)

        toolbarTitle = findViewById(R.id.toolbar_title)
        // 红点 = 关闭终端返回主界面（UI 关，会话由前台服务继续保活）
        findViewById<android.view.View>(R.id.btn_close).setOnClickListener { finish() }
        // 绿点 = tmux 上下分屏。分段发送（tmux 命令提示符异步打开，整串灌入会穿透）
        // 绿点 = tmux 上下分屏（最多 2 块，用户定）。pane 数以 tmux 服务端为准
        //（run-shell 查询，客户端计数会在会话重启后失同步）
        findViewById<android.view.View>(R.id.btn_split).setOnClickListener {
            if (!usesTmux) {
                Toast.makeText(this, "当前会话未启用 tmux，无法分屏", Toast.LENGTH_SHORT).show()
            } else {
                sendTmuxCommand("run-shell \"if [ ${'$'}(tmux list-panes | wc -l) -lt 2 ]; then tmux split-window -v; fi\"")
            }
        }
        // 黄点 = 关闭当前分屏（单 pane 时服务端拒绝，不会误关整个会话）
        findViewById<android.view.View>(R.id.btn_yellow).setOnClickListener {
            if (!usesTmux) {
                Toast.makeText(this, "当前会话未启用 tmux", Toast.LENGTH_SHORT).show()
            } else {
                sendTmuxCommand("run-shell \"if [ ${'$'}(tmux list-panes | wc -l) -gt 1 ]; then tmux kill-pane; fi\"")
            }
        }
        findViewById<android.view.View>(R.id.window_card).clipToOutline = true

        termView = findViewById(R.id.terminal_native)
        termView.mClient = this
        // 文字选择菜单的复制/粘贴落地（SessionManager 转发到本 Activity 实现）
        SessionManager.onCopyText = { text ->
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("zhengdao-term", text))
            runOnUiThread { Toast.makeText(this, "已复制 ${text.length} 个字符", Toast.LENGTH_SHORT).show() }
        }
        SessionManager.onPasteRequest = {
            runOnUiThread {
                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
                if (text.isNotEmpty()) SessionManager.write(text)
            }
        }
        // 「更多」菜单：showContextMenu() 需要注册上下文菜单才有效
        registerForContextMenu(termView)
        // ⚠️ 两个必须（均 2026-10-05 真机实测得出）：
        // 1) 必须先建渲染器再 attachSession——TerminalView 的 mRenderer 只在
        //    setTextSize()/setTypeface() 中创建（构造函数不建），而 updateSize()
        //    会读 mRenderer.mFontWidth。漏掉 → attachSession→updateSize 空指针崩溃
        //    （FATAL NPE at TerminalView.updateSize:988）。
        // 2) 字号必须换算成 px 再传——TerminalView.setTextSize 的形参虽标注
        //    "density-independent"，但内部直接 mTextPaint.setTextSize(textSize) 当
        //    **px** 用。传 14 会得到 14px 的极小字，进而算出 158 列 × 87 行的荒谬
        //    网格（实测 view=1270x1489、density=3.5、emu=158x87）。× density 后恢复正常。
        // 字号与配色改为可配置（设置页「终端外观」），默认 12dp + 经典黑底白字。
        // 内部同时完成两件事：写调色板与视图背景、按 dp→px 换算设置字号
        // ⚠️ 仍然必须先于 attachSession 调用——mRenderer 只在 setTextSize 里创建，
        //    漏掉会在 attachSession→updateSize 处空指针崩溃。
        TerminalPrefs.applyTo(termView, this)
        wireKeyBar()
    }

    /** 回退 shell 活跃标记：fallback 下不注入 autocmd（命令会打进系统 sh） */
    private var fallbackActive = false

    // 字号与配色已移到 TerminalPrefs（设置页可配），此处不再硬编码。

    override fun onResume() {
        super.onResume()
        ensureStartedAndAttach()
    }

    /** 会话存活 → 仅 attach（引擎自动重排恢复画面）；否则启动新会话。安装后复用。 */
    private fun ensureStartedAndAttach() {
        // ⚠️ 必须挂上"引擎输出 → 视图重绘"回调：
        // 会话由 SessionManager 持有，引擎回调（onTextChanged）打到 SessionManager 的
        // client 上；视图层不接这根线，pty 照常输出但画面永不刷新（2026-10-05 实测白屏根因）。
        SessionManager.onViewUpdate = { scheduleScreenUpdate() }
        if (!SessionManager.isAlive()) {
            val plan = SessionManager.start(this)
            usesTmux = plan.usesTmux
            SessionManager.usesTmux = plan.usesTmux
            fallbackActive = plan.isFallback
            toolbarTitle?.text = if (plan.isFallback) "证道 — 系统 shell（环境未安装）" else "证道 — Debian 13.7 · bash"
        } else {
            usesTmux = SessionManager.usesTmux
            fallbackActive = false
            toolbarTitle?.text = "证道 — Debian 13.7 · bash"
        }
        // 视尺寸就绪后 attach：首次 attach 触发进程 spawn（新会话）或重排恢复（旧会话）
        termView.post {
            SessionManager.session?.let { if (termView.mTermSession !== it) termView.attachSession(it) }
            // 兜底：attach 时若视图尚未完成测量，updateSize 会因宽高为 0 早退，且此后
            // 没有尺寸变化事件来重试——下一帧补一次，确保 emulator 初始化、进程 spawn。
            termView.post { termView.updateSize() }
            // 回退 shell 不注入 autocmd（命令会打进系统 sh）——保留待 Debian 会话就绪时注入
            if (!fallbackActive) injectPendingAutocmd()
        }
        termView.requestFocus()
    }

    /**
     * 合并重绘（每帧最多一次）：引擎的 onTextChanged 可能来自 pty 读取线程，
     * 且高频输出时每块都 post 会积压主线程队列。合并到单次 onScreenUpdated。
     */
    private val screenUpdateQueued = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun scheduleScreenUpdate() {
        if (screenUpdateQueued.compareAndSet(false, true)) {
            mainHandler.post {
                screenUpdateQueued.set(false)
                termView.onScreenUpdated()
            }
        }
    }

    /** 注入待执行命令。⚠️ attach/fresh 两条路径都要走，否则点[安装]进终端无反应。 */
    private fun injectPendingAutocmd() {
        if (fallbackActive) return
        val cmd = pendingAutocmd ?: return
        pendingAutocmd = null
        if (usesTmux) {
            // tmux 会话可能正跑着 Agent 的 TUI——命令走 tmux 命令提示符开新窗口执行，
            // 不打进 TUI 的输入框。分段发送（提示符异步打开，整串灌入会穿透）。
            runCatching {
                SessionManager.write(byteArrayOf(0x02, ':'.code.toByte()))  // C-b :
                mainHandler.postDelayed({
                    runCatching {
                        SessionManager.write("new-window".toByteArray(Charsets.UTF_8))
                        mainHandler.postDelayed({
                            runCatching {
                                SessionManager.write(byteArrayOf(0x0D))
                                SessionManager.write("$cmd\n".toByteArray(Charsets.UTF_8))
                            }
                        }, 150)
                    }
                }, 150)
            }
        } else {
            SessionManager.write("$cmd\n".toByteArray(Charsets.UTF_8))
        }
        // 一键安装：登记「安装中」，首页卡片轮询文件出现后自动转 [启动]
        intent?.getStringExtra("agent_id")?.takeIf { it.isNotBlank() }?.let { aid ->
            AgentRepository.markInstalling(this, aid)
        }
    }

    /** tmux 命令提示符分段发送（C-b : → 命令 → 回车；提示符异步打开，整串灌入会穿透）。 */
    private fun sendTmuxCommand(command: String) {
        runCatching {
            SessionManager.write(byteArrayOf(0x02, ':'.code.toByte()))
            mainHandler.postDelayed({
                runCatching {
                    SessionManager.write(command.toByteArray(Charsets.UTF_8))
                    mainHandler.postDelayed({
                        runCatching { SessionManager.write(byteArrayOf(0x0D)) }
                    }, 150)
                }
            }, 150)
        }
    }

    /** 轻量网络预检（异步，不阻塞）：npmjs ping 不通 → 键入一行代理提示。 */
    private fun networkPreCheck() {
        Thread {
            val ok = try {
                val c = java.net.URL("https://registry.npmjs.org/-/ping").openConnection()
                    as java.net.HttpURLConnection
                c.connectTimeout = 5000; c.readTimeout = 5000
                val r = c.responseCode in 200..299
                runCatching { c.inputStream.close() }
                r
            } catch (_: Throwable) { false }
            if (!ok) {
                SessionManager.write("echo '[网络] ⚠️ 检测失败：请确认代理已开启，并在分应用代理中勾选「证道」'\n".toByteArray(Charsets.UTF_8))
            }
        }.start()
    }

    /** 快捷键条：固定序列直发；CTRL/SHIFT 为粘滞键，修饰下一次输入（设计文档 §7）。 */
    private fun wireKeyBar() {
        ctrlButton = findViewById(R.id.key_ctrl)
        shiftButton = findViewById(R.id.key_shift)
        val sequences = mapOf(
            R.id.key_esc to "\u001b",
            R.id.key_up to "\u001b[A",
            R.id.key_down to "\u001b[B",
            R.id.key_left to "\u001b[D",
            R.id.key_right to "\u001b[C",
            R.id.key_pgup to "\u001b[5~",
            R.id.key_pgdn to "\u001b[6~",
            R.id.key_pipe to "|",
            R.id.key_minus to "-",
            R.id.key_slash to "/",
            // HOME / END：编辑长命令时跳到行首 / 行尾（bash readline 认这两组序列）
            R.id.key_home to "\u001b[H",
            R.id.key_end to "\u001b[F",
        )
        for ((id, seq) in sequences) {
            findViewById<TextView>(id)?.setOnClickListener { sendKey(seq) }
        }
        // 复制：把当前屏幕可见内容送剪贴板。
        // 想要复制「选定区域」请用**长按终端**唤起选择手柄 + 系统工具栏的复制
        // （走 onCopyTextToClipboard）。此按钮是"整屏快拷"的兜底，方便把报错整屏带走。
        findViewById<TextView>(R.id.btn_copy_top)?.setOnClickListener {
            val text = runCatching {
                val em = SessionManager.session?.emulator ?: return@runCatching null
                val top = termView.getTopRow()
                em.screen.getSelectedText(0, top, em.mColumns, top + em.mRows)?.toString()
            }.getOrNull()?.trimEnd()
            if (text.isNullOrEmpty()) {
                Toast.makeText(this, "没有可复制的内容", Toast.LENGTH_SHORT).show()
            } else {
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("terminal", text))
                Toast.makeText(this, "已复制屏幕内容（${text.length} 字）", Toast.LENGTH_SHORT).show()
            }
        }
        // ^C 中断：独立按钮。点一下直接发 Ctrl+C（0x03 = ETX），
        // 不必先点亮粘滞 CTRL——中断是高频应急操作，两步走来不及。
        findViewById<TextView>(R.id.key_interrupt)?.setOnClickListener {
            runCatching { SessionManager.write(byteArrayOf(0x03)) }
                .onFailure {
                    Toast.makeText(this, "发送中断失败：${it.message}", Toast.LENGTH_SHORT).show()
                }
        }
        // 粘贴：顶部工具栏入口（快捷键条末位让给更常用的退格）
        findViewById<TextView>(R.id.btn_paste_top)?.setOnClickListener { doPaste() }
        // 退格（⌫）：发 DEL(0x7F)。readline / bash 默认把 backward-delete-char
        // 绑在 0x7F，与桌面终端一致（不是 0x08 BS）。
        findViewById<TextView>(R.id.key_backspace)?.setOnClickListener {
            runCatching { SessionManager.write(byteArrayOf(0x7F)) }
                .onFailure {
                    Toast.makeText(this, "发送退格失败：${it.message}", Toast.LENGTH_SHORT).show()
                }
        }
        findViewById<TextView>(R.id.key_tab)?.setOnClickListener {
            // SHIFT+TAB = Backtab（\u001b[Z），Claude Code 的模式切换依赖它
            sendKey(if (stickyShift) "\u001b[Z" else "\t")
            if (stickyShift) clearSticky(ctrl = false, shift = true)
        }
        ctrlButton?.setOnClickListener {
            stickyCtrl = !stickyCtrl
            refreshStickyUi()
        }
        shiftButton?.setOnClickListener {
            stickyShift = !stickyShift
            refreshStickyUi()
        }
    }

    /**
     * 快捷键条统一发送口：优先走 TerminalView 的原生输入接口。
     * - 单字符（如 | - / TAB）→ `inputCodePoint()`：粘滞 CTRL/SHIFT 由视图层自动
     *   应用（TerminalView 内部读 readControlKey/readShiftKey），修饰键真正生效。
     * - 转义序列（方向键 / PGUP / PGDN / ESC / Backtab）→ 直接写 pty：视图层没有
     *   承载多字节序列的输入接口，这也是 Termux 官方 ExtraKeysView 的处理方式。
     */
    private fun sendKey(seq: String) {
        runCatching {
            if (seq.length == 1) {
                termView.inputCodePoint(
                    com.termux.view.TerminalView.KEY_EVENT_SOURCE_SOFT_KEYBOARD,
                    seq[0].code, false, false
                )
            } else {
                SessionManager.write(seq)
            }
        }.onFailure { Toast.makeText(this, "发送失败：${it.message}", Toast.LENGTH_SHORT).show() }
    }

    /** 粘贴：读系统剪贴板直写会话（顶部工具栏按钮与快捷键条按钮共用）。 */
    private fun doPaste() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (text.isEmpty()) {
            Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching { SessionManager.write(text) }
            .onFailure { Toast.makeText(this, "粘贴失败：${it.message}", Toast.LENGTH_SHORT).show() }
        Toast.makeText(this, "已粘贴 ${text.length} 个字符", Toast.LENGTH_SHORT).show()
    }

    private fun clearSticky(ctrl: Boolean, shift: Boolean) {
        if (ctrl) stickyCtrl = false
        if (shift) stickyShift = false
        refreshStickyUi()
    }

    private fun refreshStickyUi() {
        val active = ContextCompat.getColor(this, R.color.term_key_active)
        val idle = ContextCompat.getColor(this, R.color.term_key_idle)
        ctrlButton?.setTextColor(if (stickyCtrl) active else idle)
        shiftButton?.setTextColor(if (stickyShift) active else idle)
    }

    // ── TerminalViewClient（视图层回调；粘滞键供硬件键盘与 IME 共用）──
    /**
     * 双指缩放 → 调整字号（原先直接 return scale，等于手势空转）。
     * 与设置页「终端外观」共用同一份偏好（TerminalPrefs），缩放即快捷改字号，
     * 在设置页里也能看到档位跟着变了。
     *
     * 按 Termux 的惯例：累计缩放因子超过 ±10% 才触发一次档位切换，切完把因子
     * 重置为 1.0，避免一次捏合连跳好几档。
     */
    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            val options = TerminalPrefs.SIZE_OPTIONS
            val cur = TerminalPrefs.sizeDp(this)
            val idx = options.indexOf(cur).let { if (it < 0) options.indexOf(TerminalPrefs.DEFAULT_SIZE_DP) else it }
            val next = (if (scale > 1f) idx + 1 else idx - 1).coerceIn(0, options.size - 1)
            if (next != idx) {
                val newDp = options[next]
                TerminalPrefs.saveSize(this, newDp)
                // 字号单位换算见 TerminalPrefs 注释：必须 × density（TerminalView 按 px 处理）
                termView.setTextSize((newDp * resources.displayMetrics.density).toInt())
                Toast.makeText(this, "字号 $newDp", Toast.LENGTH_SHORT).show()
            }
            return 1.0f   // 重置累计因子
        }
        return scale
    }
    override fun onSingleTapUp(e: android.view.MotionEvent) {
        // 点击终端即弹软键盘（TerminalView 不是 EditText，不会自动弹）
        termView.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(termView, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true  // 中文 IME 组合输入需要
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean {
        // 粘滞 CTRL 对硬件键盘同样生效
        return false
    }
    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
    override fun onLongPress(event: android.view.MotionEvent?): Boolean = false  // 默认=文字选择
    override fun readControlKey(): Boolean = stickyCtrl
    override fun readAltKey(): Boolean = false
    override fun readShiftKey(): Boolean = stickyShift
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
        // 粘滞 CTRL：作用于下一个单字符输入（Ctrl+字母 = 0x01–0x1a）
        if (stickyCtrl && codePoint in 97..122) {
            session?.writeCodePoint(false, codePoint - 96)
            clearSticky(ctrl = true, shift = false)
            return true
        }
        return false
    }
    override fun onEmulatorSet() {
        // 光标持续闪烁（用户要求）：必须先设频率再启动状态，且只能在 emulator 就绪后调用。
        // 第二个参数 startOnlyIfCursorEnabled 传 false = 无条件开始闪烁（默认光标即启用）。
        // 600ms 一次，接近常见终端手感（可选范围 100–2000ms）。
        runCatching {
            termView.setTerminalCursorBlinkerRate(600)
            termView.setTerminalCursorBlinkerState(true, false)
        }.onFailure { RunLog.log("光标闪烁启动失败: ${it.message}") }

        // 进程 spawn 完成（新会话）：注入一键安装/启动命令 + 网络预检
        mainHandler.post {
            if (!fallbackActive) networkPreCheck()
            injectPendingAutocmd()
        }
    }
    override fun logError(tag: String, message: String) { RunLog.log("E: $message") }
    override fun logWarn(tag: String, message: String) { RunLog.log("W: $message") }
    override fun logInfo(tag: String, message: String) {}
    override fun logDebug(tag: String, message: String) {}
    override fun logVerbose(tag: String, message: String) {}
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        RunLog.log("E: $message ${e.message}")
    }
    override fun logStackTrace(tag: String, e: Exception) { RunLog.log("E: ${e.message}") }

    // ── TerminalSessionClient（引擎回调）──
    override fun onTextChanged(changedSession: TerminalSession) {
        // Termux 引擎自动驱动视图重绘；此处仅保留钩子
    }
    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) {
        val code = runCatching { finishedSession.getExitStatus() }.getOrDefault(-1)
        runOnUiThread {
            Toast.makeText(this, "会话已退出（code=$code）", Toast.LENGTH_LONG).show()
        }
    }
    /**
     * 系统文本选择工具栏点「复制」时走到这里（路径：
     * TextSelectionCursorController → session.onCopyTextToClipboard → 本回调）。
     * ⚠️ 此前是空实现，等于复制按钮点了没反应——必须把内容真正写进剪贴板。
     */
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        if (text.isEmpty()) return
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("terminal", text))
        Toast.makeText(this, "已复制 ${text.length} 个字符", Toast.LENGTH_SHORT).show()
    }

    /** 系统文本选择工具栏点「粘贴」时走到这里：读剪贴板并写回终端。 */
    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (text.isNotEmpty()) runCatching { SessionManager.write(text) }
    }

    // ── 「更多」上下文菜单（选择工具栏 MORE → showContextMenu → 本节）──
    // 用户点名的能力：选中文字 → 转浏览器搜索。Termux 的 ACTION_MORE 在弹菜单前会把
    // 选中文字存进 TerminalView.getStoredSelectedText()（选择模式已停，文字仍可用）。
    private val menuWebSearch = 101
    private val menuReset = 104

    override fun onCreateContextMenu(
        menu: android.view.ContextMenu,
        v: android.view.View,
        menuInfo: android.view.ContextMenu.ContextMenuInfo?
    ) {
        super.onCreateContextMenu(menu, v, menuInfo)
        val sel = runCatching { termView.storedSelectedText }.getOrNull()?.toString()?.trim()
        menu.setHeaderTitle("终端操作")
        // 复制/粘贴不再入列：顶部工具栏 + 选择工具条已有两条通路（用户定）
        menu.add(0, menuWebSearch, 0, "浏览器搜索选中文字").isEnabled = !sel.isNullOrEmpty()
        menu.add(0, menuReset, 0, "重置终端")
    }

    override fun onContextItemSelected(item: android.view.MenuItem): Boolean {
        val sel = runCatching { termView.storedSelectedText }.getOrNull()?.toString()
        when (item.itemId) {
            menuWebSearch -> {
                val q = sel?.trim().orEmpty()
                if (q.isEmpty()) {
                    Toast.makeText(this, "没有选中的文字", Toast.LENGTH_SHORT).show()
                } else {
                    runCatching {
                        val url = "https://www.bing.com/search?q=" +
                            java.net.URLEncoder.encode(q, "UTF-8")
                        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                    }.onFailure {
                        Toast.makeText(this, "打开浏览器失败：${it.message}", Toast.LENGTH_SHORT).show()
                    }
                }
                termView.unsetStoredSelectedText()
                return true
            }
            menuReset -> {
                runCatching {
                    SessionManager.session?.emulator?.reset()
                    Toast.makeText(this, "终端已重置", Toast.LENGTH_SHORT).show()
                }
                return true
            }
            else -> return super.onContextItemSelected(item)
        }
    }
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
    /**
     * 光标样式：**细竖线（BAR）**——用户要求"光标细一点"。
     * 可选值：BLOCK(0 块状) / UNDERLINE(1 下划线) / BAR(2 竖线)。
     * 注：TUI 应用（Claude Code / Hermes 等）运行时会用自己的转义序列覆盖此样式，属正常。
     */
    override fun getTerminalCursorStyle(): Int =
        com.termux.terminal.TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR
    // ── 安装流程（进度以 Toast 呈现里程碑；明细在 RunLog）──

    /**
     * 本地归档自动安装（用户需求：Download/证道 里的安装包持久存在时，
     * 重装 App 后直接本地安装，不再弹下载）。返回归档路径或 null。
     */
    private fun findLocalArchive(): File? {
        if (!ProotLauncher.storageGranted(this)) return null
        val f = File("/storage/emulated/0/Download/证道/debian-13.7-base-arm64.tar.zst")
        return if (f.isFile && f.length() > 100_000_000L) f else null
    }

    /** 回退会话首次出现时：本地有归档 → 直接自动安装（零交互）；否则给下载入口。 */
    private fun promptInstallOnce() {
        val local = findLocalArchive()
        if (local != null) {
            RunLog.log("检测到本地归档，自动安装: ${local.path}")
            Toast.makeText(this, "检测到本地安装包，直接安装", Toast.LENGTH_SHORT).show()
            startInstallFromFile(local)
            return
        }
        if (!installPromptShown.compareAndSet(false, true)) return
        mainHandler.post {
            val msg = if (!ProotLauncher.storageGranted(this)) {
                "首次使用需要下载运行环境：下载约 326MB，解压后占约 1.5–2GB。\n建议先点「授权存储」——授权后把安装包放进 Download/证道 文件夹，以后重装 App 无需重新下载。"
            } else {
                "首次使用需要下载运行环境：下载约 326MB，解压后占约 1.5–2GB。\n建议在 WiFi 下进行；支持断点续传，中断可重试。\n提示：也可以手动把安装包放到 Download/证道 文件夹，重启 App 即可免下载安装。"
            }
            AlertDialog.Builder(this)
                .setTitle("安装运行环境（Debian 13.7）")
                .setMessage(msg)
                .setPositiveButton("开始下载") { _, _ -> startInstall(ProotLauncher.DEFAULT_ROOTFS_URL) }
                .setNeutralButton("从文件选择") { _, _ ->
                    try {
                        startActivityForResult(
                            android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                                addCategory(android.content.Intent.CATEGORY_OPENABLE)
                                type = "*/*"
                            }, 2001
                        )
                    } catch (_: Throwable) {
                    }
                }
                .setNegativeButton("稍后", null)
                .show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2001 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Throwable) {
            }
            installFromSafUri(uri)
        }
    }

    private fun installStatus(text: String) {
        RunLog.log(text)
        runOnUiThread { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }
    }

    /** SAF 选中归档：拷入公共缓存 → 校验 → 解压 → 切 Debian。压缩包保留（重装免下载）。 */
    private fun installFromSafUri(uri: android.net.Uri) {
        if (!installing.compareAndSet(false, true)) return
        val appContext = applicationContext
        Thread {
            try {
                installStatus("从本地文件安装…")
                val archive = File(com.example.zhengdao.rootfs.RootfsCache.dir(appContext), "debian-13.7-base-arm64.tar.zst")
                archive.delete()
                contentResolver.openInputStream(uri)?.use { input ->
                    archive.outputStream().use { input.copyTo(it) }
                } ?: throw IllegalStateException("无法读取所选文件")
                installStatus("本地包读取完成（${archive.length() / (1024 * 1024)} MB），开始解压")
                RootfsInstaller.ensureFreeSpace(appContext, archive.length())
                RootfsInstaller.install(appContext, archive) { }
                com.example.zhengdao.rootfs.RootfsCache.pruneKeep(appContext)
                installStatus("安装完成！安装包已保留在缓存（重装免下载）")
                relaunchDebian()
            } catch (t: Throwable) {
                installStatus("安装失败：${t.message}（重进 App 可再试）")
            } finally {
                installing.set(false)
            }
        }.start()
    }

    /** 从本地归档安装：已在缓存则直接用，否则拷入 → 校验 → 解压 → 切 Debian。压缩包保留。 */
    private fun startInstallFromFile(local: File) {
        if (!installing.compareAndSet(false, true)) return
        val appContext = applicationContext
        Thread {
            try {
                val cacheCopy = File(com.example.zhengdao.rootfs.RootfsCache.dir(appContext), local.name)
                val archive = if (local.canonicalPath == cacheCopy.canonicalPath) local else run {
                    if (!cacheCopy.isFile || cacheCopy.length() != local.length()) {
                        installStatus("复制本地安装包到缓存（约 1 分钟）…")
                        local.copyTo(cacheCopy, overwrite = true)
                    }
                    cacheCopy
                }
                val sidecar = File(local.parentFile, local.name + ".sha256")
                val expectedSha = when {
                    sidecar.isFile -> sidecar.readText().trim()
                    else -> RootfsDownloader.fetchText(ProotLauncher.DEFAULT_ROOTFS_URL + ".sha256")
                }
                if (expectedSha.isNullOrBlank()) {
                    installStatus("未找到校验文件，跳过完整性校验")
                } else {
                    RootfsDownloader.verifySha256(archive, expectedSha)
                    installStatus("SHA256 校验通过")
                }
                installStatus("开始解压（约需几分钟，请勿离开）")
                RootfsInstaller.ensureFreeSpace(appContext, archive.length())
                RootfsInstaller.install(appContext, archive) { }
                com.example.zhengdao.rootfs.RootfsCache.pruneKeep(appContext)
                installStatus("安装完成！安装包已保留在缓存")
                relaunchDebian()
            } catch (t: Throwable) {
                installStatus("安装失败：${t.message}（重进 App 可再试）")
            } finally {
                installing.set(false)
            }
        }.start()
    }

    /** 下载 → SHA256 校验 → 解压（原子）→ 切 Debian。压缩包落公共缓存并保留。 */
    private fun startInstall(url: String) {
        if (!installing.compareAndSet(false, true)) return
        val appContext = applicationContext
        Thread {
            try {
                installStatus("开始下载运行环境（断点续传）…")
                val archive = com.example.zhengdao.rootfs.RootfsCache.archiveFor(appContext, url)
                val expectedSha = RootfsDownloader.fetchText("$url.sha256")
                var needDownload = true
                if (archive.isFile && !expectedSha.isNullOrBlank()) {
                    runCatching {
                        RootfsDownloader.verifySha256(archive, expectedSha)
                        needDownload = false
                        installStatus("检测到已下载的完整安装包，跳过下载")
                    }
                }
                if (needDownload) {
                    var lastPercent = -1L
                    RootfsDownloader.download(urls = listOf(url), dest = archive, shaUrl = "$url.sha256") { done, total ->
                        if (total > 0) {
                            val percent = ((done * 100 / total).coerceIn(0, 100) / 20) * 20
                            if (percent != lastPercent) {
                                lastPercent = percent
                                installStatus("下载中 $percent%（${done / (1024 * 1024)}/${total / (1024 * 1024)} MB）")
                            }
                        }
                    }
                } else {
                    installStatus("检测到已下载的完整安装包，跳过下载")
                }
                installStatus("开始解压（约需几分钟，请勿离开）")
                RootfsInstaller.ensureFreeSpace(appContext, archive.length())
                RootfsInstaller.install(appContext, archive) { }
                com.example.zhengdao.rootfs.RootfsCache.pruneKeep(appContext)
                installStatus("安装完成！安装包已保留在缓存")
                relaunchDebian()
            } catch (t: Throwable) {
                installStatus("安装失败：${t.message}（重进 App 可再试）")
            } finally {
                installing.set(false)
            }
        }.start()
    }

    /** 安装完成后切换到 Debian：杀旧会话（含回退 shell）→ 重开（自动 spawn 新 Debian）。 */
    private fun relaunchDebian() {
        SessionManager.kill(this)
        runOnUiThread {
            Toast.makeText(this, "正在切换到 Debian 13.7 (bash)…", Toast.LENGTH_SHORT).show()
            ensureStartedAndAttach()
        }
    }

    /**
     * 宽窄切换（竖屏 ↔ 横屏 / 折叠屏展开）：重载快捷键条布局。
     *
     * 资源限定符 res/layout[-w600dp]/term_keys.xml 只在**布局加载那一刻**参与匹配；
     * 而本 Activity 声明了 configChanges（旋转不重建，以免终端会话状态丢失），
     * 因此旋转不会自动重新选布局——必须手动 reinflate，
     * 否则横屏会一直沿用竖屏的两行版（2026-10-05 真机实测）。
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        reloadKeyBar()
    }

    /** 清空容器、按当前宽度重新加载快捷键条（两行 ↔ 单行），随后重新绑定事件。 */
    private fun reloadKeyBar() {
        val container = findViewById<android.widget.FrameLayout>(R.id.key_bar_container) ?: return
        container.removeAllViews()
        layoutInflater.inflate(R.layout.term_keys, container, true)
        wireKeyBar()
    }

    override fun onDestroy() {
        // M2：会话归 SessionManager 持有，UI 销毁不杀会话（前台服务继续保活）。
        // 但必须断开视图重绘回调，否则会持有已销毁的 View（内存泄漏 + 空刷）。
        SessionManager.onViewUpdate = null
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            com.example.zhengdao.rootfs.RootfsDownloader.releaseIdleResources()
        }
    }
}
