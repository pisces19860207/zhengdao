// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.WindowManager
import android.webkit.WebView
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.example.zhengdao.rootfs.RootfsDownloader
import com.example.zhengdao.rootfs.RootfsInstaller
import com.example.zhengdao.terminal.ProotLauncher
import com.example.zhengdao.terminal.TerminalBridge
import com.example.zhengdao.terminal.TerminalSession
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 全屏终端 Activity（状态条 + 终端 + 快捷键条，见 activity_main.xml）。
 *
 * 数据流：
 *   键盘/快捷键条 → TerminalSession.write → 伪终端 → 子进程
 *   子进程输出 → 读取线程 → postToWeb → xterm.js(term.write)
 *
 * 会话目标由 ProotLauncher 决定：已安装 Debian 13.7 环境则经 proot 启动 bash；
 * 未安装则回退系统 shell，并弹出「安装运行环境」入口（默认地址已预填）。
 */
class MainActivity : ComponentActivity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var session: TerminalSession? = null
    private var toolbarTitle: TextView? = null
    private var ctrlButton: TextView? = null
    private var shiftButton: TextView? = null
    private var lastCols = 0
    private var lastRows = 0
    private var stickyCtrl = false
    private var stickyShift = false
    private val installPromptShown = AtomicBoolean(false)
    private val installing = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 终端打开期间保持屏幕常亮；进程级保活（前台服务）在 M2 实现，见设计文档 §5
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 清理上次中断的解压残局（解压原子性，设计文档 §6）
        RootfsInstaller.cleanupPartial(applicationContext)
        setContentView(R.layout.activity_main)

        toolbarTitle = findViewById(R.id.toolbar_title)
        // 圆角悬浮窗口：子内容按窗口卡片轮廓裁剪（API 21+ 标准 outline 裁剪）
        findViewById<android.view.View>(R.id.window_card).clipToOutline = true
        val web = findViewById<WebView>(R.id.terminal_web)
        configureWebView(web)
        webView = web
        wireKeyBar()
        web.loadUrl("file:///android_asset/terminal/index.html")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(web: WebView) {
        web.settings.javaScriptEnabled = true
        // 仅用于加载 APK 内置 assets；不加载任何远程页面
        web.settings.allowFileAccess = true
        // 屏蔽系统字体缩放，保证终端等宽网格稳定
        web.settings.textZoom = 100
        web.setBackgroundColor(android.graphics.Color.WHITE)
        web.addJavascriptInterface(makeBridge(), "AndroidBridge")
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
            findViewById<TextView>(id)?.setOnClickListener { sendKey(seq) }
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

    /** 发送固定按键序列（粘滞 SHIFT 仅对 TAB 有特殊语义）。 */
    private fun sendKey(raw: String) {
        session?.write(raw)
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

    private fun makeBridge(): TerminalBridge = TerminalBridge(
        onReady = { cols, rows ->
            mainHandler.post { ensureSession(cols, rows) }
        },
        onInput = { b64 ->
            mainHandler.post {
                try {
                    var text = String(Base64.decode(b64, Base64.NO_WRAP), Charsets.UTF_8)
                    // 粘滞 CTRL：作用于下一个单字符输入（Ctrl+字母 = 0x01–0x1a 控制码）
                    if (stickyCtrl && text.length == 1) {
                        val lower = Character.toLowerCase(text[0])
                        if (lower.code in 97..122) {
                            text = (lower.code - 96).toChar().toString()
                            clearSticky(ctrl = true, shift = false)
                        }
                    }
                    session?.write(text)
                } catch (t: Throwable) {
                    postToWeb("[输入处理失败: ${t.message}]\r\n".toByteArray(Charsets.UTF_8))
                }
            }
        },
        onResize = { cols, rows ->
            mainHandler.post { session?.resize(cols, rows) }
        },
    )

    /** 首次就绪时启动会话；之后 resize 只调整尺寸。 */
    private fun ensureSession(cols: Int, rows: Int) {
        val existing = session
        if (existing != null) {
            existing.resize(cols, rows)
            return
        }
        if (cols <= 0 || rows <= 0) return

        val plan = ProotLauncher.buildLaunchPlan(this)
        lastCols = cols
        lastRows = rows
        toolbarTitle?.text = if (plan.isFallback) "证道 — 系统 shell（环境未安装）" else "证道 — Debian 13.7 · bash"
        session = try {
            TerminalSession(
                cmd = plan.cmd,
                args = plan.args,
                env = plan.env,
                initialCols = cols,
                initialRows = rows,
                onData = { bytes -> postToWeb(bytes) },
                onExit = { code ->
                    mainHandler.post {
                        postToWeb("[会话已结束 code=$code]\r\n".toByteArray(Charsets.UTF_8))
                        session = null
                    }
                },
            )
        } catch (t: Throwable) {
            Toast.makeText(this, "无法启动终端会话: ${t.message}", Toast.LENGTH_LONG).show()
            postToWeb("[错误] 无法启动会话: ${t.message}\r\n".toByteArray(Charsets.UTF_8))
            null
        }
        postToWeb(plan.banner.toByteArray(Charsets.UTF_8))
        if (plan.isFallback) promptInstallOnce()
    }

    /** 回退会话首次出现时，提供「安装运行环境」一键入口；地址内置，用户无需知道 URL。 */
    private fun promptInstallOnce() {
        if (!installPromptShown.compareAndSet(false, true)) return
        mainHandler.post {
            AlertDialog.Builder(this)
                .setTitle("安装运行环境（Debian 13.7）")
                .setMessage("首次使用需要下载运行环境：下载约 326MB，解压后占约 1.5–2GB。\n建议在 WiFi 下进行；支持断点续传，中断可重试。")
                .setPositiveButton("开始下载") { _, _ ->
                    startInstall(ProotLauncher.DEFAULT_ROOTFS_URL)
                }
                .setNeutralButton("自定义地址") { _, _ -> showCustomUrlDialog() }
                .setNegativeButton("稍后", null)
                .show()
        }
    }

    /** 高级入口：自定义下载地址（局域网直传 / 备用镜像）。普通用户不会用到。 */
    private fun showCustomUrlDialog() {
        val input = EditText(this)
        input.setSingleLine(true)
        input.hint = "RootFS 压缩包直链（高级选项）"
        AlertDialog.Builder(this)
            .setTitle("自定义下载地址")
            .setMessage("一般用户无需填写。用于局域网直传或备用镜像，例如 http://192.168.2.3:8000/debian-13.7-base-arm64.tar.zst")
            .setView(input)
            .setPositiveButton("安装") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isEmpty()) {
                    postToWeb("[证道] 未输入下载地址\r\n".toByteArray(Charsets.UTF_8))
                } else {
                    startInstall(url)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 下载 → SHA256 校验 → 解压（原子）→ 杀掉回退会话 → 以 Debian bash 重开会话。 */
    private fun startInstall(url: String) {
        if (!installing.compareAndSet(false, true)) return
        val appContext = applicationContext
        Thread {
            try {
                postToWeb("[证道] 开始下载运行环境\r\n来自: $url\r\n".toByteArray(Charsets.UTF_8))
                val archive = File(appContext.cacheDir, "debian-13.7-base-arm64.tar.zst")
                archive.delete()

                // 完整性校验值：优先抓取同目录 .sha256 边车文件；抓不到则明示跳过
                // （正式发布后由 ed25519 验签的 manifest 提供校验值，见设计文档 §6 安全闸）
                val expectedSha = RootfsDownloader.fetchText("$url.sha256")
                if (expectedSha.isNullOrBlank()) {
                    postToWeb("[警告] 未获取到 .sha256 边车文件，本次下载跳过完整性校验\r\n".toByteArray(Charsets.UTF_8))
                }

                // 重试不浪费：已有完整包且 SHA256 通过 → 跳过下载直接解压
                var needDownload = true
                if (archive.isFile && !expectedSha.isNullOrBlank()) {
                    try {
                        RootfsDownloader.verifySha256(archive, expectedSha)
                        needDownload = false
                        postToWeb("检测到已下载的完整安装包，跳过下载\r\n".toByteArray(Charsets.UTF_8))
                    } catch (t: Throwable) {
                        postToWeb("已有安装包校验未通过，重新下载\r\n".toByteArray(Charsets.UTF_8))
                    }
                }
                if (needDownload) {
                    archive.delete()
                    RootfsDownloader.download(
                        urls = listOf(url),
                        dest = archive,
                        expectedSha256 = expectedSha,
                    ) { done, total ->
                        if (total > 0) {
                            val percent = (done * 100 / total).coerceIn(0, 100)
                            val mbDone = done / (1024 * 1024)
                            val mbTotal = total / (1024 * 1024)
                            postToWeb("下载中: ${percent}% (${mbDone}/${mbTotal} MB)\r\n".toByteArray(Charsets.UTF_8))
                        }
                    }
                } else {
                    postToWeb("检测到已下载的完整安装包，跳过下载\r\n".toByteArray(Charsets.UTF_8))
                }
                postToWeb("开始校验并解压（解压约需几分钟，请勿离开）\r\n".toByteArray(Charsets.UTF_8))

                RootfsInstaller.ensureFreeSpace(appContext, archive.length())
                var lastReported = ""
                RootfsInstaller.install(appContext, archive) { path ->
                    // 节流：每 300 个条目或遇到 /bin/ 关键路径时打一行，避免刷屏
                    if (path.contains("/bin/") || path.hashCode() % 300 == 0) {
                        if (path != lastReported) {
                            lastReported = path
                            postToWeb("正在解压: $path\r\n".toByteArray(Charsets.UTF_8))
                        }
                    }
                }
                archive.delete()

                postToWeb("[证道] 安装完成！正在切换到 Debian 13.7 (bash)…\r\n".toByteArray(Charsets.UTF_8))
                session?.kill()
                session = null
                mainHandler.post { ensureSession(lastCols, lastRows) }
            } catch (t: Throwable) {
                postToWeb("[安装失败] ${t.message}\r\n重进 App 可再次尝试安装\r\n".toByteArray(Charsets.UTF_8))
            } finally {
                installing.set(false)
            }
        }.start()
    }

    /** 把输出字节推给 xterm.js（base64 编码；evaluateJavascript 必须在主线程）。 */
    private fun postToWeb(bytes: ByteArray) {
        val wv = webView ?: return
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        mainHandler.post {
            wv.evaluateJavascript("window.termWrite('$b64')", null)
        }
    }

    override fun onDestroy() {
        session?.kill()
        session = null
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}
