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
        findViewById<android.view.View>(R.id.btn_split).setOnClickListener {
            if (!usesTmux) {
                Toast.makeText(this, "当前会话未启用 tmux，无法分屏", Toast.LENGTH_SHORT).show()
            } else {
                runCatching {
                    SessionManager.write(byteArrayOf(0x02, ':'.code.toByte()))
                    mainHandler.postDelayed({
                        runCatching {
                            SessionManager.write("split-window -v".toByteArray(Charsets.UTF_8))
                            mainHandler.postDelayed({
                                runCatching { SessionManager.write(byteArrayOf(0x0D)) }
                            }, 150)
                        }
                    }, 150)
                }.onFailure { Toast.makeText(this, "分屏失败：${it.message}", Toast.LENGTH_SHORT).show() }
            }
        }
        findViewById<android.view.View>(R.id.window_card).clipToOutline = true

        termView = findViewById(R.id.terminal_native)
        termView.mClient = this
        wireKeyBar()
    }

    /** 回退 shell 活跃标记：fallback 下不注入 autocmd（命令会打进系统 sh） */
    private var fallbackActive = false

    override fun onResume() {
        super.onResume()
        ensureStartedAndAttach()
    }

    /** 会话存活 → 仅 attach（引擎自动重排恢复画面）；否则启动新会话。安装后复用。 */
    private fun ensureStartedAndAttach() {
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
            // 回退 shell 不注入 autocmd（命令会打进系统 sh）——保留待 Debian 会话就绪时注入
            if (!fallbackActive) injectPendingAutocmd()
        }
        termView.requestFocus()
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
        )
        for ((id, seq) in sequences) {
            findViewById<TextView>(id)?.setOnClickListener { SessionManager.write(seq) }
        }
        // 粘贴：读系统剪贴板直写会话（移动端选择复制不便，粘贴是高频需求）
        findViewById<TextView>(R.id.key_paste)?.setOnClickListener {
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
            if (text.isNotEmpty()) {
                SessionManager.write(text)
                Toast.makeText(this, "已粘贴 ${text.length} 个字符", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<TextView>(R.id.key_tab)?.setOnClickListener {
            // SHIFT+TAB = Backtab（\u001b[Z），Claude Code 的模式切换依赖它
            SessionManager.write(if (stickyShift) "\u001b[Z" else "\t")
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
    override fun onScale(scale: Float): Float = scale
    override fun onSingleTapUp(e: android.view.MotionEvent) { termView.requestFocus() }
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
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {}
    override fun onPasteTextFromClipboard(session: TerminalSession?) {}
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
    override fun getTerminalCursorStyle(): Int = 0  // 0=块状（TUI 应用会自行覆盖样式）
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

    override fun onDestroy() {
        // M2：会话归 SessionManager 持有，UI 销毁不杀会话（前台服务继续保活）
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            com.example.zhengdao.rootfs.RootfsDownloader.releaseIdleResources()
        }
    }
}
