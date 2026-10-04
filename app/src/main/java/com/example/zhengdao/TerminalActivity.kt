// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
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
import com.example.zhengdao.rootfs.RunLog
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
class TerminalActivity : ComponentActivity() {

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
    /** 当前会话是否由 tmux 保持（绿点分屏按钮的前置条件） */
    private var usesTmux = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 终端打开期间保持屏幕常亮；进程级保活（前台服务）在 M2 实现，见设计文档 §5
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 清理上次中断的解压残局（解压原子性，设计文档 §6）
        RootfsInstaller.cleanupPartial(applicationContext)
        RunLog.init(applicationContext)
        setContentView(R.layout.activity_main)

        toolbarTitle = findViewById(R.id.toolbar_title)
        // 红点 = 关闭终端返回主界面（Mac 工具栏隐喻：用户点红点就该退出去）
        findViewById<android.view.View>(R.id.btn_close).setOnClickListener { finish() }
        // 绿点 = tmux 上下分屏（用户指定）。走 tmux 前缀键通道（C-b : 命令行），
        // 即使前台是 Agent 的 TUI 也不会把命令打进它的输入框。默认单会话，分屏按需。
        // ⚠️ 必须分段发送：tmux 的命令提示符是异步打开的，一次性灌入的字节会
        // 穿透到前台应用（实测：整串发送时命令漏进了 bash 报 command not found）。
        findViewById<android.view.View>(R.id.btn_split).setOnClickListener {
            if (!usesTmux) {
                Toast.makeText(this, "当前会话未启用 tmux，无法分屏", Toast.LENGTH_SHORT).show()
            } else {
                try {
                    session?.write(byteArrayOf(0x02, ':'.code.toByte()))  // C-b + 命令提示符
                    mainHandler.postDelayed({
                        try {
                            session?.write("split-window -v".toByteArray(Charsets.UTF_8))
                            mainHandler.postDelayed({
                                try {
                                    session?.write(byteArrayOf(0x0D))
                                } catch (_: Throwable) { }
                            }, 150)
                        } catch (_: Throwable) { }
                    }, 150)
                } catch (t: Throwable) {
                    Toast.makeText(this, "分屏失败：${t.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
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
        usesTmux = plan.usesTmux
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
        intent.getStringExtra("autocmd")?.takeIf { it.isNotBlank() }?.let { cmd ->
            postToWeb("[证道] 执行: $cmd\r\n".toByteArray(Charsets.UTF_8))
            mainHandler.post { session?.write(cmd + "\n") }
        }
        if (plan.isFallback) promptInstallOnce()
    }

    /**
     * 本地归档自动安装（用户需求：Download/证道 里的安装包持久存在时，
     * 重装 App 后直接本地安装，不再弹下载）。
     * 返回本地归档路径；存储权限未授权或文件不存在时返回 null。
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
            postToWeb("[证道] 检测到本地安装包，直接安装（无需下载）\r\n".toByteArray(Charsets.UTF_8))
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
                .setPositiveButton("开始下载") { _, _ ->
                    startInstall(ProotLauncher.DEFAULT_ROOTFS_URL)
                }
                .setNeutralButton("从文件选择") { _, _ ->
                    // SAF 文件选择：全安卓版本可用、零权限（安卓 16 上 /sdcard 原始路径
                    // 对 target 28 应用不可达，实测 2026-10-04；SAF 是唯一通用本地通道）
                    try {
                        startActivityForResult(
                            android.content.Intent(
                                android.content.Intent.ACTION_OPEN_DOCUMENT
                            ).apply {
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
            // 持久化读取授权（本会话与重启后均可再读该文件）
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Throwable) {
            }
            installFromSafUri(uri)
        }
    }

    /** SAF 选中归档：拷入公共缓存 → 尽力校验 → 解压 → 切 bash。压缩包保留（重装免下载）。 */
    private fun installFromSafUri(uri: android.net.Uri) {
        if (!installing.compareAndSet(false, true)) return
        val appContext = applicationContext
        Thread {
            try {
                postToWeb("[证道] 从本地文件安装: $uri\r\n".toByteArray(Charsets.UTF_8))
                val archive = File(
                    com.example.zhengdao.rootfs.RootfsCache.dir(appContext),
                    "debian-13.7-base-arm64.tar.zst"
                )
                archive.delete()
                contentResolver.openInputStream(uri)?.use { input ->
                    archive.outputStream().use { input.copyTo(it) }
                } ?: throw IllegalStateException("无法读取所选文件")
                RunLog.log("SAF 归档已拷入: ${archive.length()} bytes")
                postToWeb("本地包读取完成（${archive.length() / (1024 * 1024)} MB），开始解压\r\n".toByteArray(Charsets.UTF_8))
                RootfsInstaller.ensureFreeSpace(appContext, archive.length())
                var lastReported = ""
                RootfsInstaller.install(appContext, archive) { path ->
                    if (path.contains("/bin/") || path.hashCode() % 300 == 0) {
                        if (path != lastReported) {
                            lastReported = path
                            postToWeb("正在解压: $path\r\n".toByteArray(Charsets.UTF_8))
                        }
                    }
                }
                com.example.zhengdao.rootfs.RootfsCache.pruneKeep(appContext)
                postToWeb("[证道] 安装完成！安装包已保留在缓存（重装免下载）\r\n".toByteArray(Charsets.UTF_8))
                postToWeb("[证道] 正在切换到 Debian 13.7 (bash)…\r\n".toByteArray(Charsets.UTF_8))
                session?.kill()
                session = null
                mainHandler.post { ensureSession(lastCols, lastRows) }
            } catch (t: Throwable) {
                postToWeb("[安装失败] ${t.message}\r\n重进 App 可再次尝试\r\n".toByteArray(Charsets.UTF_8))
            } finally {
                installing.set(false)
            }
        }.start()
    }

    /** 从本地归档安装：归档已在缓存目录则直接用，否则拷入 → 校验 → 解压 → 切 bash。压缩包保留。 */
    private fun startInstallFromFile(local: File) {
        if (!installing.compareAndSet(false, true)) return
        val appContext = applicationContext
        Thread {
            try {
                postToWeb("[证道] 使用本地安装包: ${local.path}\r\n".toByteArray(Charsets.UTF_8))
                val cacheCopy = File(
                    com.example.zhengdao.rootfs.RootfsCache.dir(appContext), local.name
                )
                val archive = if (local.canonicalPath == cacheCopy.canonicalPath) local else run {
                    if (!cacheCopy.isFile || cacheCopy.length() != local.length()) {
                        postToWeb("复制本地安装包到缓存（约 1 分钟）…\r\n".toByteArray(Charsets.UTF_8))
                        local.copyTo(cacheCopy, overwrite = true)
                    }
                    cacheCopy
                }
                // 完整性校验：优先同目录 .sha256 边车；无则跳过并明示（本地文件由用户放置）
                val sidecar = File(local.parentFile, local.name + ".sha256")
                val expectedSha = when {
                    sidecar.isFile -> sidecar.readText().trim()
                    else -> RootfsDownloader.fetchText(ProotLauncher.DEFAULT_ROOTFS_URL + ".sha256")
                }
                if (expectedSha.isNullOrBlank()) {
                    postToWeb("[提示] 未找到校验文件，跳过完整性校验\r\n".toByteArray(Charsets.UTF_8))
                } else {
                    RootfsDownloader.verifySha256(archive, expectedSha)
                    postToWeb("SHA256 校验通过\r\n".toByteArray(Charsets.UTF_8))
                }
                postToWeb("开始解压（解压约需几分钟，请勿离开）\r\n".toByteArray(Charsets.UTF_8))
                RootfsInstaller.ensureFreeSpace(appContext, archive.length())
                var lastReported = ""
                RootfsInstaller.install(appContext, archive) { path ->
                    if (path.contains("/bin/") || path.hashCode() % 300 == 0) {
                        if (path != lastReported) {
                            lastReported = path
                            postToWeb("正在解压: $path\r\n".toByteArray(Charsets.UTF_8))
                        }
                    }
                }
                com.example.zhengdao.rootfs.RootfsCache.pruneKeep(appContext)
                postToWeb("[证道] 安装完成！安装包已保留在缓存（重装免下载）\r\n".toByteArray(Charsets.UTF_8))
                postToWeb("[证道] 正在切换到 Debian 13.7 (bash)…\r\n".toByteArray(Charsets.UTF_8))
                session?.kill()
                session = null
                mainHandler.post { ensureSession(lastCols, lastRows) }
            } catch (t: Throwable) {
                postToWeb("[安装失败] ${t.message}\r\n重进 App 可再次尝试\r\n".toByteArray(Charsets.UTF_8))
            } finally {
                installing.set(false)
            }
        }.start()
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

    /** 下载 → SHA256 校验 → 解压（原子）→ 杀掉回退会话 → 以 Debian bash 重开会话。压缩包落公共缓存并保留。 */
    private fun startInstall(url: String) {
        if (!installing.compareAndSet(false, true)) return
        val appContext = applicationContext
        Thread {
            try {
                postToWeb("[证道] 开始下载运行环境\r\n来自: $url\r\n".toByteArray(Charsets.UTF_8))
                // 公共缓存（Download/zhengdao/cache）：重装 App 不丢，装完保留
                val archive = com.example.zhengdao.rootfs.RootfsCache.archiveFor(appContext, url)

                // 完整性校验值：优先抓取同目录 .sha256 边车文件；抓不到则明示跳过
                // （正式发布后由 ed25519 验签的 manifest 提供校验值，见设计文档 §6 安全闸）
                val expectedSha = RootfsDownloader.fetchText("$url.sha256")
                if (expectedSha.isNullOrBlank()) {
                    postToWeb("[警告] 未获取到 .sha256 边车文件，本次下载跳过完整性校验\r\n".toByteArray(Charsets.UTF_8))
                }

                // 重试不浪费：已有完整包且 SHA256 通过 → 跳过下载直接解压（环境缓存复用）
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
                        shaUrl = "$url.sha256",
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
                com.example.zhengdao.rootfs.RootfsCache.pruneKeep(appContext)

                postToWeb("[证道] 安装完成！安装包已保留在缓存（重装免下载）\r\n".toByteArray(Charsets.UTF_8))
                postToWeb("[证道] 正在切换到 Debian 13.7 (bash)…\r\n".toByteArray(Charsets.UTF_8))
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

    /** 内存看护（用户指定）：退后台释放空闲 HTTP 连接；WebView/Chromium 由系统自动管理。 */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            com.example.zhengdao.rootfs.RootfsDownloader.releaseIdleResources()
        }
    }
}
