// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 依据的公开接口：OpenCode 官方 CLI（serve/--port）、GitHub Releases API（digest 字段）、
// Apache Commons Compress（xz/tar）与 Android ProcessBuilder 官方文档。
package com.example.zhengdao.oc

import android.content.Context
import com.example.zhengdao.rootfs.RunLog
import com.example.zhengdao.rootfs.RootfsDownloader
import com.example.zhengdao.terminal.Workspace
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.net.URL

/**
 * 太极的 OpenCode（bionic 宿主版）管理器（批次 3，2026-10-06）。
 *
 * 来源：Hope2333/opencode-termux（native bionic 构建，NDK r27c，Android 28 linker，
 * 与本项目 targetSdk 28 的 App 域 exec 机制同源——proot 二进制同款先例）。
 *
 * 与终端 TUI 的 npm 版完全隔离：二进制独立（files/oc/usr）、数据独立（独立 XDG
 * 四目录），两边物理分开互不影响（用户定稿）。
 *
 * 已知取舍（用户拍板）：
 * - 落盘 289MB（xz 包 65MB）——bionic 路线的代价，换无 PRoot 开销
 * - 宿主侧无 node_modules → opencode-mem 记忆插件不可用（终端侧不受影响）
 * - serve 密码字段有输出但 HTTP 层未强制鉴权（PoC 实测），v1 直接接入
 */
object OcManager {

    const val VERSION = "2.0.22"
    const val PORT = 14000
    /** 2.0.22 定版 SHA256（GitHub Releases digest 字段）。检查更新时按新 digest 动态校验。 */
    private const val PINNED_SHA = "9830eb45f64b5caa1b797c6a399eab29a528a4ba3273cc4ad4430700cd877cbf"
    private const val REPO = "Hope2333/opencode-termux"
    private const val PKG_NAME = "opencode-$VERSION-1-aarch64.pkg.tar.xz"
    private const val VERSION_KEY = "oc_installed_version"

    fun binaryFile(ctx: Context): File = File(ctx.filesDir, "oc/usr/bin/opencode")

    fun installed(ctx: Context): Boolean =
        binaryFile(ctx).let { it.isFile && it.length() > 100L * 1024 * 1024 }

    fun installedVersion(ctx: Context): String? =
        if (installed(ctx)) Settings2.prefs(ctx).getString(VERSION_KEY, null) else null

    fun homeDir(ctx: Context): File = File(ctx.filesDir, "oc/home")
    private fun xdgDir(ctx: Context, kind: String): File = File(ctx.filesDir, "oc/xdg/$kind")

    /** 下载缓存（用户共享存储：Download/证道/opencode/，卸载重装不丢）。 */
    fun cacheDir(ctx: Context): File = File(Workspace.hostDir(ctx), "opencode")

    /**
     * serve 是否存活（HTTP ping，进程被杀/换 PID 都能正确判活）。
     *
     * ⚠️ **必须打 `/api/…` 并校验 content-type**，不能打无前缀路径。
     *
     * 实测（PoC #3，2.0.22）：这版 serve 把无前缀路径全部喂给 Web UI 的 SPA
     * catch-all，`/` 、`/global/health`、`/session`、`/doc` **统统返回 200 text/html**。
     * 打它们会把「serve 根本没起来 / 端口上是个空壳」误判成「已运行」——
     * 这是原实现最危险的一处：判活恒真，后续所有"已在运行就跳过启动"的分支全错。
     *
     * 判据：响应 content-type 是 JSON（说明命中的是 API 层而非 SPA 外壳）即视为存活。
     * 其中 **401 也算存活**——`/api/…` 一律要求 Basic auth，401 恰恰证明 serve
     * 正在监听且鉴权生效；真正该判 false 的是"连不上"或"返回 HTML"。
     */
    fun serveRunning(): Boolean = try {
        val c = URL("http://127.0.0.1:$PORT/api/session").openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 800; c.readTimeout = 800
        val code = c.responseCode
        val isApi = c.getHeaderField("content-type")
            ?.contains("application/json", ignoreCase = true) == true
        runCatching { c.inputStream?.close() }
        runCatching { c.errorStream?.close() }
        isApi && code in 200..499
    } catch (_: Throwable) {
        false
    }

    @Volatile
    private var serveProcess: Process? = null

    /** serve 的 HTTP Basic 密码（启动后从日志解析；WebView 鉴权用）。 */
    @Volatile
    var servePassword: String? = null
        private set

    /**
     * 取「最后一个**确认监听成功**的那场」的密码，而不是日志最后一行。
     *
     * 日志形态（真机 `files/oc/serve.log` 实录）：
     * ```
     * server password <pw1>
     * server listening on http://127.0.0.1:14000     ← pw1 绑上了
     * server password <pw2>
     * server listening on http://127.0.0.1:14000     ← pw2 绑上了
     * server password <pw3>                          ← 最后一场没绑上端口，无 listening
     * ```
     * ⚠️ **两种顺序都实测出现过**，解析必须同时兼容：
     *   - 调试期多场启动：`password` 在前、`listening` 在后
     *   - 干净生产路径单场启动：`listening` 在前、`password` 在后（2026-10-06 实机确认）
     *
     * 原实现 `lastOrNull { contains("server password") }` 在"最后一次启动没绑上端口"
     * （端口已被孤儿进程占用 / 启动失败）时，必然取到那场失败进程的密码 →
     * 拿它打 `/api/…` 全部 401。PoC #3 已复现：日志末行 `PduH4…` 无 listening，
     * 真正在监听的进程用的是更早一场的密码。
     *
     * 修复：只认"打了密码且后面跟着 listening"的场次，取最后一个这样的密码。
     * 拿不到就返回 null（让 UI 明确报"鉴权未就绪"，好过拿错密码到处 401）。
     */
    private fun parseServePassword(ctx: Context) {
        servePassword = runCatching {
            var pending: String? = null   // 已打密码、尚未见到 listening
            var awaitingPw = false        // 已见 listening、尚未见到密码（顺序相反的情形）
            var lastGood: String? = null  // 最后一个确认监听成功的密码
            File(ctx.filesDir, "oc/serve.log").useLines { lines ->
                for (line in lines) {
                    when {
                        line.contains("server password") -> {
                            val pw = line.substringAfter("server password").trim()
                            if (awaitingPw) { lastGood = pw; awaitingPw = false }
                            else pending = pw
                        }
                        line.contains("server listening") -> {
                            if (pending != null) { lastGood = pending; pending = null }
                            else awaitingPw = true
                        }
                    }
                }
            }
            // ⚠️ 竞态修复（本次「进不了 UI」真因）：若**最后看到一个 listening 但它的
            //   password 尚未落盘**（awaitingPw == true，即新场次正在启动），
            //   绝不能返回上一场的 lastGood —— 那是**过期密码**，拿它打 /api/* 会全程 401。
            //   返回 null，逼调用方（startServe 的轮询）继续等待新场次的 password 落盘。
            if (awaitingPw) null else lastGood
        }.getOrNull()
    }

    /**
     * 拉起 serve（宿主进程，不经 PRoot）。已在运行返回 null；失败返回错误消息。
     * 工作目录 = 用户工作区（Workspace.hostDir）。
     */
    fun startServe(ctx: Context): String? {
        // serve 可能是孤儿（上一轮 App 被杀、子进程存活监听 14000——App 重启后
        // Web 会话不丢反而是特性）。无论 serve 是本轮拉起还是孤儿，代理必须就绪：
        // 代理线程随旧 App 进程死亡，这里幂等重启。
        if (serveRunning()) {
            // ⚠️ 竞态/孤儿修复：serve 已在运行（典型：重装/重启后上一进程的孤儿仍在监听）
            //   也必须**解析密码并写入 OcManager.servePassword** —— 否则原生路径的
            //   passwordProvider（{ OcManager.servePassword }）恒为 null，请求无
            //   Authorization → 全程 401。原实现只把密码喂给 LocalProxy，漏写了 servePassword。
            parseServePassword(ctx)
            com.example.zhengdao.oc.LocalProxy.start(PORT) { servePassword }
            return null
        }
        val bin = binaryFile(ctx)
        if (!installed(ctx)) return "OpenCode 尚未安装"
        return try {
            homeDir(ctx).mkdirs()
            listOf("data", "cache", "config", "state").forEach { xdgDir(ctx, it).mkdirs() }
            ensurePermissionPolicy(ctx)
            val pb = ProcessBuilder(bin.absolutePath, "serve", "--port=$PORT")
            pb.directory(Workspace.hostDir(ctx)) // 项目 = 用户工作区
            val env = pb.environment()
            env["HOME"] = homeDir(ctx).absolutePath
            env["XDG_DATA_HOME"] = xdgDir(ctx, "data").absolutePath
            env["XDG_CACHE_HOME"] = xdgDir(ctx, "cache").absolutePath
            env["XDG_CONFIG_HOME"] = xdgDir(ctx, "config").absolutePath
            env["XDG_STATE_HOME"] = xdgDir(ctx, "state").absolutePath
            env["PATH"] = "/system/bin"
            // 🔧 输出预算修复（2026-10-06，用户定稿）：
            //    opencode 把每次补全硬性封顶在 **32000 输出 token（含思考）**，与模型自身上限无关。
            //    推理模型会把这 32k 全花在 thinking 上 → 补全以 reason=length 结束、**不产出正文**，
            //    即「回复只有思考过程、没有最终文本」的根因。
            //    OPENCODE_EXPERIMENTAL_OUTPUT_TOKEN_MAX 是唯一覆盖项（opencode 会再按模型上限夹一次，故安全）。
            //    变量名已对官方文档核实：https://opencode.ai/docs/cli → Experimental。
            env["OPENCODE_EXPERIMENTAL_OUTPUT_TOKEN_MAX"] = "64000"
            // 以下两项**未在官方文档中查到**（用户提供，疑为社区命名/上游新增）。
            // 设置为未知 env 无副作用（进程忽略），故一并设置以完整覆盖用户方案；若无效属预期。
            env["OPENCODE_EXPERIMENTAL_LENGTH_NUDGE"] = "true"
            env["OPENCODE_EXPERIMENTAL_LENGTH_NUDGE_MAX"] = "3"
            // 🔐 保守权限策略：**真正的落地机制是 [ensurePermissionPolicy] 写的 opencode.json**
            //    （实测本版 bionic 2.0.22 的 env 变量不生效）。这里额外设一份 env，
            //    兼容将来会读它的版本；未知/无效 env 无副作用。
            //    ⚠️ V2 换了 action 名：跑 shell 命令是 `shell`（非 v1 的 `bash`），文件修改是 `edit`。
            env["OPENCODE_PERMISSION"] =
                "[{\"action\":\"shell\",\"resource\":\"*\",\"effect\":\"ask\"}," +
                    "{\"action\":\"bash\",\"resource\":\"*\",\"effect\":\"ask\"}," +
                    "{\"action\":\"edit\",\"resource\":\"*\",\"effect\":\"ask\"}," +
                    "{\"action\":\"write\",\"resource\":\"*\",\"effect\":\"ask\"}," +
                    "{\"action\":\"webfetch\",\"resource\":\"*\",\"effect\":\"ask\"}]"
            // API Key 注入（与终端同一套密钥库；OpenCode 识别 OPENAI_API_KEY 等）
            runCatching {
                ApiKeyStore.PROVIDERS.forEach { (id, envName) ->
                    ApiKeyStore.get(ctx, id)?.let { env[envName] = it }
                }
            }
            val log = File(ctx.filesDir, "oc/serve.log")
            pb.redirectErrorStream(true)
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log))
            serveProcess = pb.start()
            // 等待 HTTP 就绪（最多 15 秒）+ 密码行落盘（密码在 listening 之后打印，
            // 过早返回会漏读 → WebView 401 卡死）
            repeat(30) {
                if (serveRunning()) {
                    repeat(10) {
                        parseServePassword(ctx)
                        if (servePassword != null) {
                            // 本地透传代理（fetch/XHR 的 401 不触发 WebView 认证回调，
                            // 真机实测——用 TCP 层注入 Authorization 绕开）
                            com.example.zhengdao.oc.LocalProxy.start(PORT) { servePassword }
                            return null
                        }
                        Thread.sleep(500)
                    }
                    RunLog.log("太极: serve 就绪但密码未解析到（WebView 将无法通过 API 鉴权）")
                    return null
                }
                Thread.sleep(500)
            }
            serveProcess?.destroy()
            "启动超时：serve 未在 15 秒内就绪（详见 RunLog）"
        } catch (t: Throwable) {
            RunLog.log("太极 serve 启动失败: ${t.message}")
            "启动失败: ${t.message}"
        }
    }

    /**
     * 写入**保守权限策略**到 `XDG_CONFIG_HOME/opencode/opencode.json`。
     *
     * ⚠️ 为什么用文件而不是环境变量：实测本版（bionic 2.0.22）`OPENCODE_PERMISSION`
     * 环境变量**不生效** —— 只设 env 时 shell 工具仍被静默放行。写这个文件才真正生效。
     *
     * 语法取自**官方 V2 权限文档**：
     * - 顶层字段 `permissions`（v1 叫 `permission`）
     * - 跑 shell 命令的 action 是 **`shell`**（v1 叫 `bash`）；文件修改是 `edit`（覆盖 write/patch）
     * - 规则 = `[{action, resource, effect}]`，effect ∈ allow|deny|ask，resource 支持 `*`/`?` 通配
     *
     * 每次冷启动**无条件重写**（自愈，避免旧配置残留）。
     */
    private fun ensurePermissionPolicy(ctx: Context) {
        runCatching {
            val dir = File(xdgDir(ctx, "config"), "opencode").apply { mkdirs() }
            val json = """
                {
                  "${'$'}schema": "https://opencode.ai/config.json",
                  "permissions": [
                    { "action": "shell",    "resource": "*", "effect": "ask" },
                    { "action": "bash",     "resource": "*", "effect": "ask" },
                    { "action": "edit",     "resource": "*", "effect": "ask" },
                    { "action": "write",    "resource": "*", "effect": "ask" },
                    { "action": "webfetch", "resource": "*", "effect": "ask" }
                  ]
                }
            """.trimIndent()
            File(dir, "opencode.json").writeText(json)
        }.onFailure { RunLog.log("太极: 写权限配置失败 ${it.message}") }
    }

    /** 停止 serve（App 进程死亡时子进程随之消亡，ping 判活可自动恢复）。 */
    fun stopServe() {
        serveProcess?.destroy()
        serveProcess = null
        // 孤儿 serve（App 重启后 Process 引用已丢）：同 uid 进程互相可杀，扫 /proc 清理
        runCatching {
            File("/proc").listFiles { f -> f.name.all { it.isDigit() } }?.forEach { p ->
                runCatching {
                    val cmd = p.resolve("cmdline").readText()
                    if (cmd.contains("usr/bin/opencode")) {
                        android.os.Process.killProcess(p.name.toInt())
                    }
                }
            }
        }
        LocalProxy.stop()
    }

    @Suppress("unused")
    private fun parseServePasswordFromLog(ctx: Context): String? = runCatching {
        // ⚠️ 已废弃、不再调用：这是 `lastOrNull` 的**有 bug 版本**（会取到最后一场
        //   "打了密码但没绑上端口"的失败进程的密码 → 全程 401）。保留仅为历史溯源，
        //   统一改用 parseServePassword()（只认"密码+listening"配对的 lastGood）。
        File(ctx.filesDir, "oc/serve.log").readText()
            .lineSequence().lastOrNull { it.contains("server password") }
            ?.substringAfter("server password ")?.trim()
    }.getOrNull()

    class DownloadResult(val ok: Boolean, val message: String)

    /**
     * 按需下载并释放（阻塞调用，放后台线程）。
     * 缓存：Download/证道/opencode/<包名>（卸载重装不丢）；SHA256 按 GitHub digest 校验。
     */
    fun downloadAndInstall(ctx: Context, onProgress: (String) -> Unit): DownloadResult {
        val cache = cacheDir(ctx).apply { mkdirs() }
        val dest = File(cache, PKG_NAME)
        // 缓存命中：包在且 SHA 对 → 跳过下载
        if (dest.isFile && dest.length() > 50L * 1024 * 1024) {
            onProgress("检测到已下载的安装包，校验中…")
            val hit = runCatching { RootfsDownloader.verifySha256(dest, PINNED_SHA) }.isSuccess
            if (hit) {
            onProgress("安装包校验通过，释放中…")
            return extract(ctx, dest, onProgress, VERSION)
            }
            dest.delete()
        }
        onProgress("开始下载 OpenCode $VERSION（约 65MB，断点续传）…")
        return try {
            RootfsDownloader.download(
                urls = listOf(
                    "https://github.com/$REPO/releases/download/Push261005/$PKG_NAME",
                    "https://gh-proxy.com/https://github.com/$REPO/releases/download/Push261005/$PKG_NAME",
                ),
                dest = dest,
                shaUrl = null, // digest 无 sidecar；下载后用定版 SHA 手动校验
                onProgress = { done, total ->
                    if (total > 0) onProgress("下载中 ${(done * 100 / total).coerceIn(0, 100)}%（${done / 1048576}/${total / 1048576} MB）")
                },
            )
            RootfsDownloader.verifySha256(dest, PINNED_SHA)
            onProgress("校验通过，释放中（约需一分钟）…")
            extract(ctx, dest, onProgress, VERSION)
        } catch (t: Throwable) {
            RunLog.log("太极: OpenCode 下载失败 ${t.message}")
            DownloadResult(false, "下载失败: ${t.message}")
        }
    }

    /** 更新流程（P2.5）：安装 checkUpdate 给出的新版本——URL 与 digest 全部来自
     * release API 动态数据，不落定版 SHA。digest 缺失直接拒绝（无校验 = 不装）。 */
    fun downloadAndInstall(ctx: Context, update: UpdateInfo, onProgress: (String) -> Unit): DownloadResult {
        if (update.sha256 == null) {
            return DownloadResult(false, "更新包缺少 SHA256（release digest 缺失），已拒绝安装")
        }
        val cache = cacheDir(ctx).apply { mkdirs() }
        val dest = File(cache, "opencode-${update.version}-aarch64.pkg.tar.xz")
        onProgress("开始下载 OpenCode ${update.version}（约 65MB，断点续传）…")
        return try {
            RootfsDownloader.download(
                urls = listOf(
                    update.url,
                    "https://gh-proxy.com/${update.url}",
                ),
                dest = dest,
                shaUrl = null, // digest 无 sidecar；下载后用 API digest 手动校验
                onProgress = { done, total ->
                    if (total > 0) onProgress("下载中 ${(done * 100 / total).coerceIn(0, 100)}%（${done / 1048576}/${total / 1048576} MB）")
                },
            )
            RootfsDownloader.verifySha256(dest, update.sha256)
            onProgress("校验通过，释放中（约需一分钟）…")
            extract(ctx, dest, onProgress, update.version)
        } catch (t: Throwable) {
            RunLog.log("太极: OpenCode ${update.version} 更新失败 ${t.message}")
            DownloadResult(false, "更新失败: ${t.message}")
        }
    }

    /** xz + tar 双层解包 → files/oc/usr/...（跳过元数据点文件，bin 补执行位）。 */
    private fun extract(ctx: Context, pkg: File, onProgress: (String) -> Unit, version: String): DownloadResult {
        val ocRoot = File(ctx.filesDir, "oc")
        var count = 0
        try {
            BufferedInputStream(pkg.inputStream(), 512 * 1024).use { buffered ->
                val tar = TarArchiveInputStream(XZCompressorInputStream(buffered))
                var entry: TarArchiveEntry? = tar.nextTarEntry
                while (entry != null) {
                    val name = entry.name
                    if (!name.startsWith("data/data/com.termux/files/usr/") || name.endsWith("/")) {
                        entry = tar.nextTarEntry; continue
                    }
                    val rel = name.substringAfter("files/usr/")
                    if (rel.startsWith(".")) { entry = tar.nextTarEntry; continue }
                    val target = File(ocRoot, "usr/$rel")
                    target.parentFile?.mkdirs()
                    target.outputStream().use { out -> tar.copyTo(out) }
                    if (rel.startsWith("bin/")) {
                        runCatching { android.system.Os.chmod(target.absolutePath, 493) } // 0755
                    }
                    count++
                    if (count % 5 == 0) onProgress("释放中… $count")
                    entry = tar.nextTarEntry
                }
            }
        } catch (t: Throwable) {
            RunLog.log("太极: 释放失败 ${t.message}")
            return DownloadResult(false, "释放失败: ${t.message}")
        }
        if (!installed(ctx)) return DownloadResult(false, "释放后二进制缺失（包不完整？）")
        Settings2.prefs(ctx).edit().putString(VERSION_KEY, version).apply()
        RunLog.log("太极: OpenCode $version 释放完成（$count 个文件）")
        return DownloadResult(true, "OpenCode $version 安装完成")
    }

    /** 可安装的更新包（P2.5）：版本 + 直链 + release asset 的 digest（"sha256:…"去前缀）。
     * digest 由 GitHub API 动态取回——更新流程不落定版 SHA，无 digest 拒绝安装。 */
    data class UpdateInfo(val version: String, val url: String, val sha256: String?)

    /** 查最新版本（GitHub API）；有可更新版本返回 [UpdateInfo]，无更新/失败返回 null。
     * 与**已装版本**比较（更新过的不再重复报；出厂 VERSION 兜底未装/未知）。 */
    fun checkUpdate(ctx: Context): UpdateInfo? = try {
        val conn = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 10000; conn.readTimeout = 10000
        conn.setRequestProperty("User-Agent", "zhengdao")
        val body = conn.inputStream.bufferedReader().readText()
        conn.inputStream.close()
        val arr = org.json.JSONObject(body).getJSONArray("assets")
        val re = Regex("^opencode-([\\d.]+)-\\d+-aarch64\\.pkg\\.tar\\.xz$")
        var latest: Triple<List<Int>, String, String?>? = null // (版本段, 下载URL, digest SHA)
        for (i in 0 until arr.length()) {
            val a = arr.getJSONObject(i)
            val m = re.find(a.optString("name")) ?: continue
            val ver = m.groupValues[1].split('.').map { it.toInt() }
            if (latest == null || verNewer(ver, latest.first)) {
                val digest = a.optString("digest")
                    .takeIf { it.startsWith("sha256:") }?.substringAfter(':')
                latest = Triple(ver, a.optString("browser_download_url"), digest)
            }
        }
        val cur = (installedVersion(ctx) ?: VERSION).split('.').map { it.toInt() }
        if (latest != null && verNewer(latest.first, cur)) {
            UpdateInfo(latest.first.joinToString("."), latest.second, latest.third)
        } else null
    } catch (_: Throwable) {
        null
    }

    /** 版本段逐位比较（List<Int> 无运算符比较）。 */
    private fun verNewer(a: List<Int>, b: List<Int>): Boolean {
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** 隔离命名空间：避免与 Settings 混淆的轻量偏好入口。 */
    private object Settings2 {
        fun prefs(ctx: Context) =
            ctx.getSharedPreferences("zhengdao-taiji", Context.MODE_PRIVATE)
    }
}
