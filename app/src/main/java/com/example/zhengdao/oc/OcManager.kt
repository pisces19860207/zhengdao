// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 依据的公开接口：OpenCode 官方 CLI（serve/--port）、GitHub Releases API（digest 字段）、
// Apache Commons Compress（xz/tar）与 Android ProcessBuilder 官方文档。
package com.example.zhengdao.oc

import android.content.Context
import android.util.Base64
import com.example.zhengdao.R
import com.example.zhengdao.core.IssueCenter
import com.example.zhengdao.rootfs.RunLog
import com.example.zhengdao.rootfs.RootfsDownloader
import com.example.zhengdao.terminal.Workspace
import com.example.zhengdao.util.HumanizeError
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
 * - serve 的 HTTP Basic 鉴权**确实生效**（2026-10-11 实测：无鉴权 `GET /api/session` = 401，
 *   只有无前缀的 SPA 外壳 `GET /` 公开；密码由 `parseServePassword` 从 serve.log 解析、
 *   经 `OcClient` 的 AuthInterceptor 注入）。
 *   ⚠️ 本行旧文曾写「serve 密码字段有输出但 HTTP 层未强制鉴权（PoC 实测），v1 直接接入」——
 *   那是把**无前缀路径**当成 API 打（`/session` ≠ `/api/session`）造成的误记，也是
 *   2026-10-11 那份外部安全审查抄错的源头；更正记录见 ERRATA 与台账 §A #29。
 */
object OcManager {

    const val VERSION = "2.0.22"
    const val PORT = 14000
    /** 2.0.22 定版 SHA256（GitHub Releases digest 字段）。检查更新时按新 digest 动态校验。 */
    private const val PINNED_SHA = "9830eb45f64b5caa1b797c6a399eab29a528a4ba3273cc4ad4430700cd877cbf"
    /** opencode.json 的 schema 声明（官方固定值）。 */
    private const val SCHEMA = "https://opencode.ai/config.json"

    /**
     * 保守权限策略：一切敏感动作都 `ask`（问用户），不静默放行。
     * 语法取自官方 V2 权限文档——跑 shell 的 action 是 **`shell`**（v1 叫 `bash`），
     * 文件修改是 `edit`（覆盖 write/patch）；规则 = `[{action, resource, effect}]`。
     * ⚠️ 实测本版（bionic 2.0.22）`OPENCODE_PERMISSION` 环境变量**不生效**，只有写进
     * opencode.json 才真正生效，所以这是真正的落地机制。
     */
    private const val PERMISSIONS_JSON = """[
        { "action": "shell",    "resource": "*", "effect": "ask" },
        { "action": "bash",     "resource": "*", "effect": "ask" },
        { "action": "edit",     "resource": "*", "effect": "ask" },
        { "action": "write",    "resource": "*", "effect": "ask" },
        { "action": "webfetch", "resource": "*", "effect": "ask" }
    ]"""

    private const val REPO = "Hope2333/opencode-termux"
    private const val PKG_NAME = "opencode-$VERSION-1-aarch64.pkg.tar.xz"
    /** `.pkg.tar.xz` 里条目的固定前缀（Termux 打包布局）。剥掉它，剩下的就是 `oc/usr` 下的相对路径。 */
    private const val PKG_ENTRY_PREFIX = "data/data/com.termux/files/usr/"
    private const val VERSION_KEY = "oc_installed_version"

    fun binaryFile(ctx: Context): File = File(ctx.filesDir, "oc/usr/bin/opencode")

    fun installed(ctx: Context): Boolean =
        binaryFile(ctx).let { it.isFile && it.length() > 100L * 1024 * 1024 }

    fun installedVersion(ctx: Context): String? =
        if (installed(ctx)) Settings2.prefs(ctx).getString(VERSION_KEY, null) else null

    fun homeDir(ctx: Context): File = File(ctx.filesDir, "oc/home")

    /** XDG 四目录（data/cache/config/state）宿主路径。对外可见：插件管理要按同一套路径定位缓存。 */
    fun xdgDir(ctx: Context, kind: String): File = File(ctx.filesDir, "oc/xdg/$kind")

    /** 太极实例的 opencode 配置目录（= serve 的 `XDG_CONFIG_HOME/opencode`）。 */
    fun configDir(ctx: Context): File = File(xdgDir(ctx, "config"), "opencode")

    /** 太极实例的 opencode.json —— **App 侧唯一真相源**。
     *
     * ⚠️ v1.2 阶段 2.0 前的历史坑：`PluginManager` 曾指向 `files/home/.zhengdao/taiji/...`
     * （那是终端 taiji 脚本的 XDG 目录，随 v1.2 卸载终端 opencode 后已成死路径），
     * 与 serve 真正读取的本文件**不是一个**，导致插件开关改的配置根本没人读。
     * 现在两类写入（权限策略 / 插件 / 性能调优）统一走 [updateConfig] 打到这一个文件。 */
    fun configFile(ctx: Context): File = File(configDir(ctx), "opencode.json")

    /**
     * 太极 opencode.json 的**唯一写入口**（v1.2 阶段 2.0）：读-改-写。
     *
     * 只更新 [mutate] 动过的字段，其余（`plugin` 数组、`snapshot`、`watcher`、
     * 用户手写项……）**原样保留**。此前 `ensurePermissionPolicy()` 是整文件覆盖写，
     * 会把它没写过的字段全部抹掉（v1.1.1 只是因为 serve 已运行走了提前返回分支才没炸）。
     *
     * @param mutate 就地修改传入的 JSONObject；返回 true 表示确实改了东西（需要落盘）。
     */
    fun updateConfig(ctx: Context, mutate: (org.json.JSONObject) -> Boolean) {
        mergeConfigFile(configFile(ctx), mutate)
    }

    /**
     * [updateConfig] 的可测核心：对**指定文件**做读-改-写（不依赖 Context，单测可直接调用）。
     *
     * 规则：
     * - 文件不存在 / 解析失败 → 从空对象起步（`\$schema` 会补上）；
     * - [mutate] 只应改它负责的那几个字段，**其余字段原样保留**；
     * - 只有真的改了东西才落盘（幂等，避免每次冷启动都产生一次无谓写盘）。
     */
    internal fun mergeConfigFile(file: File, mutate: (org.json.JSONObject) -> Boolean) {
        runCatching {
            file.parentFile?.mkdirs()
            val obj = if (file.isFile) runCatching {
                org.json.JSONObject(file.readText())
            }.getOrElse {
                RunLog.log("太极: opencode.json 解析失败，按空配置重建（原内容已损坏）")
                org.json.JSONObject()
            } else org.json.JSONObject()
            var changed = !file.isFile
            if (!obj.has("\$schema")) {
                obj.put("\$schema", SCHEMA)
                changed = true
            }
            if (mutate(obj)) changed = true
            if (changed) file.writeText(obj.toString(2))
        }.onFailure { RunLog.log("太极: 写 opencode.json 失败 ${it.message}") }
    }

    /**
     * 太极自己的工作区（**App 私有**：`files/oc/workspace/`，2026-10-09 从公共区迁出）。
     *
     * 迁移动机（用户原话）：「把太极的所有工作区什么的和 APP 归置到一起……彻底把
     * opencode 和终端的分开」。太极的 HOME / XDG 四目录 / 人设 / 工作区现在全在
     * `files/oc/` 这一棵私有树下，卸载 App 一起清掉；终端的公共区工作区
     * （`Download/证道`、资料库、logs）完全不受影响。
     *
     * serve 的**进程工作目录**（模型眼里的项目根）取这里，不再是 `Workspace.hostDir()`。
     */
    fun workspaceDir(ctx: Context): File = File(ctx.filesDir, "oc/workspace")

    /**
     * 太极的安装包下载缓存（**App 私有**：`files/oc/pkg/`）。
     *
     * 历史：v2.0.6 及以前放在公共区 `Download/证道/opencode/`，当初的理由是
     * 「卸载重装不丢，省一次 68MB 下载」；2026-10-09 用户裁决跟工作区一起搬进 App
     * （接受代价：卸载重装后重新下）。
     *
     * 旧位置的残留由 [migrateLegacyCache] 一次性搬走；万一没搬成（无权限等），
     * 缓存清理按钮的那条「旧位置」目标也会把它收掉。
     */
    fun cacheDir(ctx: Context): File = File(ctx.filesDir, "oc/pkg")

    /**
     * 一次性搬家（幂等）：把公共区遗留的 `<工作区>/opencode/`（v2.0.6 及以前的安装包缓存）
     * 整体挪进 App 私有的 [cacheDir]。
     *
     * 为什么搬而不是直接删：那里面通常躺着一个 68MB 的 `.pkg.tar.xz`，删了下次安装要重下；
     * 搬完把旧目录删掉——**太极在公共区不该有任何痕迹**（用户要求）。
     *
     * @return 是否真的动了东西（供日志与单测断言）。
     */
    fun migrateLegacyCache(ctx: Context): Boolean {
        val legacy = runCatching { File(Workspace.hostDir(ctx), "opencode") }
            .getOrNull() ?: return false
        if (!legacy.isDirectory) return false
        return runCatching {
            val moved = migrateLegacyCacheDir(legacy, cacheDir(ctx))
            legacy.delete()
            moved
        }.getOrElse {
            RunLog.log("太极: 旧安装包缓存搬家失败 ${it.message}（下轮启动重试）")
            false
        }
    }

    /**
     * [migrateLegacyCache] 的可测核心（不依赖 Context）：把 [legacy] 下的文件挪进 [target]。
     *
     * 语义：同名的**不覆盖**（新位置更权威，旧的那份直接丢弃——反正是可再下载的安装包缓存）；
     * 处理完把 [legacy] 整个删掉（太极不该在公共区留任何痕迹）；一次都没搬成返回 false（调用方可留痕）。
     */
    internal fun migrateLegacyCacheDir(legacy: File, target: File): Boolean {
        val files = legacy.listFiles() ?: return false
        if (files.isEmpty()) return false
        target.mkdirs()
        var moved = false
        files.forEach { f ->
            val dst = File(target, f.name)
            if (dst.exists()) {
                runCatching { f.delete() }
                return@forEach
            }
            val ok = runCatching {
                if (f.renameTo(dst)) true else {
                    f.copyTo(dst, overwrite = false)
                    f.delete()
                }
            }.getOrDefault(false)
            if (ok) moved = true
        }
        runCatching { legacy.delete() }
        return moved
    }

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

    /**
     * C 步（2026-10-13）：候选口令上限 —— 每个候选要打一次 `/api/session` 探针，必须封顶
     * （[startServe] 的孤儿分支是 150ms 一轮在调它，不封顶会堆出一串 HTTP）。
     */
    private const val SERVE_PW_MAX_CANDIDATES = 3

    /** C 步：单次探测超时（毫秒）—— 回环调用是毫秒级，这里只兜住「端口在、没人应答」。 */
    private const val SERVE_PROBE_TIMEOUT_MS = 700

    /** C 步：测试接缝 —— 非空时替代真实 HTTP 探测；生产路径永远为 null。 */
    internal var serveProbeForTest: ((String) -> Boolean)? = null

    /** serve 的 HTTP Basic 密码（启动后从日志解析；OcClient 直连鉴权用）。 */
    @Volatile
    var servePassword: String? = null
        private set

    /** A 步（2026-10-13）：最近一次用过的应用上下文 —— 供 refreshServePassword() 无参重解析。 */
    @Volatile
    private var lastCtx: Context? = null

    /**
     * 解析 `oc/serve.log` 里当前**真正在生效**的密码，写入 [servePassword]。
     *
     * 日志形态（真机 `files/oc/serve.log` 实录）：
     * ```
     * server password <pw1>
     * server listening on http://127.0.0.1:14000     ← pw1 绑上了
     * server password <pw2>
     * server listening on http://127.0.0.1:14000     ← pw2 绑上了
     * server password <pw3>                          ← 最后一场没绑上端口，无 listening
     * ```
     * ⚠️ **两种顺序都实测出现过**：调试期多场启动 `password` 在前，干净生产路径单场启动
     * `listening` 在前（2026-10-06 实机确认）⇒ 任何"只看顺序猜"的解析都必然踩坑。
     *
     * 历代教训：原实现取 `lastOrNull { contains("server password") }`，在"最后一次启动没绑上
     * 端口"时必取失败场次的密码 → `/api/…` 全程 401（PoC #3 复现）；改成"只认后面跟着
     * listening 的那一场"后，又漏掉「新场次已打密码、它的 listening 行尚未落盘」这个中间态 ——
     * 那时返回上一场的 lastGood 就是**过期密码**（E-093 的另一半；2026-10-13 16:17 真机十条
     * `创建会话失败：HTTP 401` 就是它）。
     *
     * C 步（2026-10-13）定稿：**不猜顺序，直接问端口** —— 把日志里所有候选口令按新→旧收集，
     * 逐个拿 `GET /api/session` 试，谁能过鉴权就用谁。日志里会长期留着**已退出场次**的口令，
     * 只有探测能区分"谁在听"：活着的若是旧场次，旧口令自然胜出（这才是对的）。
     * 都试不过 ⇒ 置 null，让 UI 明确报"鉴权未就绪"，好过拿错口令到处 401。
     */
    private fun parseServePassword(ctx: Context) {
        lastCtx = ctx.applicationContext   // A 步（2026-10-13）：给 refreshServePassword() 留一份上下文
        val candidates = runCatching {
            File(ctx.filesDir, "oc/serve.log").useLines { passwordCandidates(it) }
        }.getOrDefault(emptyList())

        val prober = serveProbeForTest ?: ::serveAcceptsPassword
        val picked = if (candidates.isEmpty()) null else pickServePassword(candidates, prober)
        if (picked != servePassword) {
            RunLog.log(
                when {
                    candidates.isEmpty() -> "太极: serve.log 里还没有口令"
                    picked != null -> "太极: 用第 ${candidates.indexOf(picked) + 1} 个候选口令过了 /api/session 鉴权（共 ${candidates.size} 个）"
                    else -> "太极: ${candidates.size} 个候选口令都没过 /api/session 鉴权，按未就绪处理（E-093）"
                }
            )
        }
        servePassword = picked
    }

    /** C 步（2026-10-13）：从日志行收集候选口令 —— 新→旧、去重、封顶 [SERVE_PW_MAX_CANDIDATES]。纯函数。 */
    internal fun passwordCandidates(lines: Sequence<String>): List<String> =
        lines.filter { it.contains("server password") }
            .map { it.substringAfter("server password").trim() }
            .filter { it.isNotEmpty() }
            .toList()
            .asReversed()
            .distinct()
            .take(SERVE_PW_MAX_CANDIDATES)

    /** C 步：按「新→旧」逐个探测，返回当前真能过鉴权的那个；都不过返回 null。纯函数（探测逻辑外置）。 */
    internal fun pickServePassword(candidates: List<String>, prober: (String) -> Boolean): String? =
        candidates.firstOrNull { pw -> runCatching { prober(pw) }.getOrDefault(false) }

    /**
     * C 步：带 Basic 口令打一次 `/api/session` 做鉴权探针。
     *
     * 判据用 `!= 401` 而不是 `== 200`：401 才是"口令不对"，其它状态码（200/204/404…）
     * 说明请求已经过了鉴权这一关 —— 比死等 200 更抗 opencode 的接口变化。
     * 端口没人听 / 超时 ⇒ 抛异常 ⇒ false（那个口令就不可用）。
     */
    private fun serveAcceptsPassword(password: String): Boolean {
        val conn = URL("http://127.0.0.1:$PORT/api/session").openConnection() as java.net.HttpURLConnection
        return try {
            conn.connectTimeout = SERVE_PROBE_TIMEOUT_MS
            conn.readTimeout = SERVE_PROBE_TIMEOUT_MS
            conn.setRequestProperty(
                "Authorization",
                "Basic " + Base64.encodeToString(
                    "opencode:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP
                )
            )
            conn.responseCode != 401
        } catch (_: Exception) {
            false
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /**
     * A 步（2026-10-13）：OkHttp 的 [okhttp3.Authenticator] 收到 401 时回调 —— 重解析
     * `oc/serve.log` 并返回当前密码；拿不到就返回 null（调用方放弃重试，退回今天的行为）。
     *
     * 为什么不要 Context 参数：能发出请求就说明 serve 已经起步，必然先经过
     * [startServe] / [parseServePassword] 中任一处，[lastCtx] 已留存（存的是
     * applicationContext，不会泄漏 Activity）；万一为空就返回 null，只是不重试。
     */
    fun refreshServePassword(): String? {
        val c = lastCtx ?: return null
        parseServePassword(c)
        return servePassword
    }

    /**
     * 拉起 serve（宿主进程，不经 PRoot）。已在运行返回 null；失败返回错误消息。
     * 工作目录 = 太极自己的工作区（[workspaceDir]，App 私有；2026-10-09 与终端分开）。
     */
    fun startServe(ctx: Context): String? {
        // serve 可能是孤儿（上一轮 App 被杀、子进程存活监听 14000——App 重启后
        // Web 会话不丢反而是特性）。
        if (serveRunning()) {
            // ⚠️ 竞态/孤儿修复：serve 已在运行（典型：重装/重启后上一进程的孤儿仍在监听）
            //   也必须**解析密码并写入 OcManager.servePassword** —— 否则原生路径的
            //   passwordProvider（{ OcManager.servePassword }）恒为 null，请求无
            //   Authorization → 全程 401。
            parseServePassword(ctx)
            // ⚠️ E-093（2026-10-11 真机抓到，就在发 v2.0.12 那次）：
            //   孤儿 serve 这条路径**没有**下面那次"轮询等 password 落盘"的兜底，而 serve 的日志
            //   顺序是「listening 先、password 后」（见 parseServePassword 的注释）。恰好卡在两次
            //   落盘之间解析，就会拿到 null ⇒ 客户端不带 Authorization ⇒ `/api/*` 全程 401。
            //   真机现象：刚 `adb install -r` 完第一次进太极报
            //   `太极: 创建会话失败：HTTP 401 …Authentication required`，退出来再进一次就好了
            //   （那时 password 已落盘）。**未实测**这条竞态是否就是那次 401 的唯一成因
            //   （另一种可能是取到了过期场次的密码），但"孤儿分支缺轮询"是实打实的缺口，
            //   而它恰好落在"更新完 App 第一次进太极"这条必经路径上，故补一段**有界等待**：
            //   最多 3 秒，每 150ms 重解析一次；仍拿不到就照旧返回 null，由 UI 报"鉴权未就绪"。
            if (servePassword == null) {
                for (i in 0 until 20) {
                    Thread.sleep(150)
                    parseServePassword(ctx)
                    if (servePassword != null) break
                }
            }
            return null
        }
        val bin = binaryFile(ctx)
        if (!installed(ctx)) return ctx.getString(R.string.oc_not_installed)
        return try {
            homeDir(ctx).mkdirs()
            listOf("data", "cache", "config", "state").forEach { xdgDir(ctx, it).mkdirs() }
            workspaceDir(ctx).mkdirs()
            ensurePermissionPolicy(ctx)
            // 自愈（2026-10-07 事故）：exec 前确保执行位——解压流程已保证，这里兜底
            // 任何 mode 漂移（如本次事故中未走到 chmod 的 600 文件）。失败不阻断，
            // 下面 exec 若仍失败会带真因上报。
            runCatching { android.system.Os.chmod(bin.absolutePath, 493) } // 0755
            val pb = ProcessBuilder(bin.absolutePath, "serve", "--port=$PORT")
            pb.directory(workspaceDir(ctx)) // 项目 = 太极自己的私有工作区（2026-10-09 起，不再是终端共享工作区）
            val env = pb.environment()
            env["HOME"] = homeDir(ctx).absolutePath
            env["XDG_DATA_HOME"] = xdgDir(ctx, "data").absolutePath
            env["XDG_CACHE_HOME"] = xdgDir(ctx, "cache").absolutePath
            env["XDG_CONFIG_HOME"] = xdgDir(ctx, "config").absolutePath
            env["XDG_STATE_HOME"] = xdgDir(ctx, "state").absolutePath
            env["PATH"] = "/system/bin"
            // 本机环回**不走代理**（v1.2 网络优化 P0）：代理 App 若注入 HTTP_PROXY，
            // 太极 UI 打 127.0.0.1:14000 的请求会被错误地送进代理、形成路由回环
            // （现象：CPU 飙升 + 发热 + 太极连不上）。大小写两种写法都设，兼容不同读取方。
            env["NO_PROXY"] = "localhost,127.0.0.1,::1"
            env["no_proxy"] = "localhost,127.0.0.1,::1"
            // 禁 IPv6（P0）：太极是 Bun 编译的原生二进制，禁掉 IPv6 避免在 IPv6 黑洞网络下
            // 先去连不可达地址、挂到长超时才回落 IPv4（Bun 源码 src/env_var.zig 已核对存在）。
            env["BUN_FEATURE_FLAG_DISABLE_IPV6"] = "1"
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
            // 说明：这里**故意不注入任何 API Key**。用户已拍板「凭据类信息不落 App」
            // （见 commit e882050），由用户在自己终端里 export，App 不代管、不持久化。
            val log = File(ctx.filesDir, "oc/serve.log")
            pb.redirectErrorStream(true)
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log))
            serveProcess = pb.start()
            // 等待 HTTP 就绪（最多 15 秒）+ 密码行落盘（密码在 listening 之后打印，
            // 过早返回会漏读 → API 全程 401）
            repeat(30) {
                if (serveRunning()) {
                    repeat(10) {
                        parseServePassword(ctx)
                        if (servePassword != null) {
                            // 起来了就撤下"上次留下的问题"（#4：report / resolve 语义配对）
                            IssueCenter.resolve("oc-serve-timeout")
                            IssueCenter.resolve("oc-serve-password")
                            return null
                        }
                        Thread.sleep(500)
                    }
                    // ⚠️ 这条分支以前**只写日志**：serve 在跑、但密码没读到，调用方拿到
                    //   null（= 成功），而接下来每个 /api/* 请求都 401 —— 用户侧表现为
                    //   「太极连上了但什么都不工作」，界面上一个字都没有。现在同时报进
                    //   「最近问题」，并给一条去路（重启 serve 会重新打印并重读密码）。
                    val msg = "太极: serve 就绪但密码未解析到（UI 将无法通过 API 鉴权）"
                    RunLog.log(msg)
                    IssueCenter.report(
                        id = "oc-serve-password",
                        title = ctx.getString(R.string.oc_serve_no_password_title),
                        detail = ctx.getString(R.string.oc_serve_no_password_detail),
                        actionLabel = ctx.getString(R.string.oc_restart_taiji),
                        actionId = IssueCenter.ACTION_RESTART_SERVE,
                    )
                    return null
                }
                Thread.sleep(500)
            }
            serveProcess?.destroy()
            IssueCenter.report(
                id = "oc-serve-timeout",
                title = ctx.getString(R.string.oc_serve_timeout_title),
                detail = ctx.getString(R.string.oc_serve_timeout_detail),
                actionLabel = ctx.getString(R.string.common_retry),
                actionId = IssueCenter.ACTION_RETRY_SERVE,
            )
            ctx.getString(R.string.oc_serve_timeout_short)
        } catch (t: Throwable) {
            RunLog.log("太极 serve 启动失败: ${t.message}")
            // 2026-10-08：异常原文 → 人话（[HumanizeError]）。原文仍落 RunLog，UI 看到的是
            // 「网络超时 / 存储空间不足 / 没有写入权限」等。TaijiScreen:539/590 透传此 msg
            // 给用户；此处换为 HumanizeError.title 即可让用户读懂。
            val human = HumanizeError.title(t, ctx.resources)
            // #4：太极页会显示这句，但用户可能正在主页/丹房 —— 同步报一条到「最近问题」。
            IssueCenter.report(
                id = "oc-serve-start",
                title = ctx.getString(R.string.oc_serve_start_failed_title),
                detail = human,
                actionLabel = ctx.getString(R.string.common_retry),
                actionId = IssueCenter.ACTION_RETRY_SERVE,
            )
            ctx.getString(R.string.oc_serve_start_failed_short, human)
        }
    }

    /**
     * 写入**保守权限策略**到太极的 opencode.json（经 [updateConfig] 做字段级 merge）。
     *
     * ⚠️ 为什么用文件而不是环境变量：实测本版（bionic 2.0.22）`OPENCODE_PERMISSION`
     * 环境变量**不生效** —— 只设 env 时 shell 工具仍被静默放行。写这个文件才真正生效。
     *
     * ⚠️ v1.2 阶段 2.0 修复：原来是 `File(dir,"opencode.json").writeText(json)` **整文件覆盖写**，
     * 会把它不认识的字段（`plugin` 数组、以及后续要加的 `snapshot` / `watcher`）**全部抹掉**。
     * 现改为只覆盖 `permissions` 一个字段，其余原样保留。
     */
    private fun ensurePermissionPolicy(ctx: Context) {
        updateConfig(ctx) { obj ->
            // 每次冷启动无条件自愈（用户手改成宽松策略也会被拉回保守策略）
            obj.put("permissions", org.json.JSONArray(PERMISSIONS_JSON))
            true
        }
    }

    /** 停止 serve（App 进程死亡时子进程随之消亡，ping 判活可自动恢复）。 */
    fun stopServe() {
        serveProcess?.destroy()
        serveProcess = null
        // 孤儿 serve（App 重启后 Process 引用已丢）：同 uid 进程互相可杀，扫 /proc 清理。
        //
        // ⚠️ 2026-10-12 修正（审计报告 A1）：判据原来是 `cmdline.contains("usr/bin/opencode")`，
        //    那会把**用户自己在终端 Debian 里装的 opencode** 一起杀掉 —— proot 里的 guest 进程
        //    在宿主 /proc 里就是真实进程，其 cmdline 形如
        //    `/data/.../files/rootfs/usr/bin/opencode`（同 uid），同样命中那个宽泛子串。
        //    症状：关一下太极 serve，终端里正跑着的 agent 莫名其妙死掉。
        //    现在只匹配**本 App 装的那一份**：`filesDir/oc/usr/bin/opencode`
        //    （见 [binaryFile] 与 `ProcessBuilder(bin.absolutePath, "serve", …)`，:332），
        //    它的路径里带 `/oc/usr/bin/opencode`，而 guest 的那份在 `.../files/rootfs/...` 之下 —— 前缀不同，不会误杀。
        runCatching {
            File("/proc").listFiles { f -> f.name.all { it.isDigit() } }?.forEach { p ->
                runCatching {
                    val cmd = p.resolve("cmdline").readText()
                    if (cmd.contains("/oc/usr/bin/opencode")) {
                        android.os.Process.killProcess(p.name.toInt())
                    }
                }
            }
        }
    }

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
            onProgress(ctx.getString(R.string.oc_cached_verifying))
            val hit = runCatching { RootfsDownloader.verifySha256(dest, PINNED_SHA) }.isSuccess
            if (hit) {
            onProgress(ctx.getString(R.string.oc_cached_verified))
            return extract(ctx, dest, onProgress, VERSION)
            }
            dest.delete()
        }
        onProgress(ctx.getString(R.string.oc_downloading_start, VERSION))
        return try {
            RootfsDownloader.download(
                // 2026-10-11：镜像链收口到 GithubMirror（原为手写的 gh-proxy.com 一行）；
                // 校验锚点仍是代码里的 PINNED_SHA，与走哪个源无关（见 :514）。
                urls = RootfsDownloader.withMirrorFallback(
                    "https://github.com/$REPO/releases/download/Push261005/$PKG_NAME",
                ),
                dest = dest,
                // digest 无 sidecar；下载后用定版 SHA 手动校验（故不传 shaUrls）
                shaUrls = emptyList(),
                onProgress = { done, total ->
                    if (total > 0) onProgress(
                        ctx.getString(
                            R.string.oc_downloading_pct,
                            (done * 100 / total).coerceIn(0, 100), done / 1048576, total / 1048576,
                        )
                    )
                },
            )
            RootfsDownloader.verifySha256(dest, PINNED_SHA)
            onProgress(ctx.getString(R.string.oc_verified_extracting))
            extract(ctx, dest, onProgress, VERSION)
        } catch (t: Throwable) {
            RunLog.log("太极: OpenCode 下载失败 ${t.message}")
            // 2026-10-08：见 [HumanizeError] 设计
            DownloadResult(false, ctx.getString(R.string.oc_download_failed, HumanizeError.title(t, ctx.resources)))
        }
    }

    /** 更新流程（P2.5）：安装 checkUpdate 给出的新版本——URL 与 digest 全部来自
     * release API 动态数据，不落定版 SHA。digest 缺失直接拒绝（无校验 = 不装）。 */
    fun downloadAndInstall(ctx: Context, update: UpdateInfo, onProgress: (String) -> Unit): DownloadResult {
        if (update.sha256 == null) {
            return DownloadResult(false, ctx.getString(R.string.oc_no_digest_rejected))
        }
        val cache = cacheDir(ctx).apply { mkdirs() }
        val dest = File(cache, "opencode-${update.version}-aarch64.pkg.tar.xz")
        onProgress(ctx.getString(R.string.oc_downloading_start, update.version))
        return try {
            RootfsDownloader.download(
                // 2026-10-11：同上，镜像链收口到 GithubMirror；校验锚点 = API digest（:546）。
                urls = RootfsDownloader.withMirrorFallback(update.url),
                dest = dest,
                // digest 无 sidecar；下载后用 API digest 手动校验（故不传 shaUrls）
                shaUrls = emptyList(),
                onProgress = { done, total ->
                    if (total > 0) onProgress(
                        ctx.getString(
                            R.string.oc_downloading_pct,
                            (done * 100 / total).coerceIn(0, 100), done / 1048576, total / 1048576,
                        )
                    )
                },
            )
            RootfsDownloader.verifySha256(dest, update.sha256)
            onProgress(ctx.getString(R.string.oc_verified_extracting))
            extract(ctx, dest, onProgress, update.version)
        } catch (t: Throwable) {
            RunLog.log("太极: OpenCode ${update.version} 更新失败 ${t.message}")
            // 2026-10-08：见 [HumanizeError] 设计
            DownloadResult(false, ctx.getString(R.string.oc_update_failed, HumanizeError.title(t, ctx.resources)))
        }
    }

    /**
     * 归档条目名 → `oc/usr` 下的相对路径；**不是本包布局的条目一律返回 null**（调用方跳过）。
     *
     * E-090（2026-10-11）：这里必须与准入判断共用 [PKG_ENTRY_PREFIX]。`removePrefix` 只剥
     * "字符串开头"的前缀，前缀写错既不抛错也不记日志——只会静默把整条
     * `data/data/com.termux/files/usr/bin/opencode` 当成相对路径，文件全部错位到
     * `oc/usr/data/…`（实测：整包约 289MB），最后 `installed()` 判否、安装报失败。
     */
    internal fun relPathOf(entryName: String): String? {
        if (!entryName.startsWith(PKG_ENTRY_PREFIX) || entryName.endsWith("/")) return null
        return entryName.removePrefix(PKG_ENTRY_PREFIX).ifEmpty { null }
    }

    /**
     * 归档条目名 → 落盘路径；**越界一律返回 null**（P3-5，2026-10-10）。
     *
     * ## 为什么需要它
     *
     * 旧实现只有 `File(ocRoot, "usr/$rel")` 一句拼路径：`rel` 里带 `..` 的条目
     * （`data/data/com.termux/files/usr/a/../../../../shared_prefs/x.xml`）会被原样写下去，
     * 也就是"用归档里的名字决定写到 App 私有目录之外的哪里"。包虽然有 digest 校验，
     * 但这道校验防的是"传坏了"，不是"上游被投毒"——路径穿越这条防线得自己修。
     *
     * ## 规则
     *
     * 绝对路径 / 空段 / 含 `..` 段 / 含 NUL ⇒ 直接拒（不做"规范化后再看"的博弈）；
     * 通过之后还要 `canonicalFile` 复核：归一化落点必须仍在 [destRoot] 里
     * （软链、奇形怪状的段都在这一步现形）。与解 RootFS 时那条
     * `RootfsInstaller.checkPathInside` 同一思路。
     */
    internal fun resolveExtractTarget(destRoot: File, rel: String): File? {
        if (rel.isEmpty() || rel.startsWith("/") || rel.indexOf('\u0000') >= 0) return null
        val segments = rel.split('/')
        if (segments.any { it.isEmpty() || it == ".." }) return null
        val root = runCatching { destRoot.canonicalFile }.getOrNull() ?: return null
        val canon = runCatching { File(root, rel).canonicalFile }.getOrNull() ?: return null
        if (canon.path == root.path) return null
        if (!canon.path.startsWith(root.path + File.separator)) return null
        return canon
    }

    /**
     * 把 `.part` 发布到最终路径，**再**给 `bin/` 下的可执行文件补执行位（§B 顺序 17）。
     *
     * 为什么顺序不能反：`renameTo` 失败时会退化成 `copyTo`（见下），而 **`copyTo` 不保留执行位**。
     * 旧写法把 `chmod` 打在 `part` 上，一旦走了复制分支，执行位就落在一个马上被删掉的临时文件上，
     * 最终文件是 0644 ⇒ serve 只能靠 `startServe` 的自愈兜底（那条注释自己写着）。
     * 现在两种落盘路径都补在 [target] 上。
     *
     * @param chmod 补执行位的动作（生产传 `android.system.Os.chmod(path, 0755)`；单测传记账替身）
     */
    internal fun publishExtractedFile(part: File, target: File, rel: String, chmod: (File) -> Unit) {
        if (!part.renameTo(target)) {
            // 同目录 rename 理论不失败；极端场景退化为复制（失败照旧抛出，不静默丢文件）
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        // 执行位补在**最终路径**上；只在 bin/ 下补，其余文件不动权限
        if (rel.startsWith("bin/")) chmod(target)
    }

    /** xz + tar 双层解包 → files/oc/usr/...（跳过元数据点文件，bin 补执行位）。 */
    private fun extract(ctx: Context, pkg: File, onProgress: (String) -> Unit, version: String): DownloadResult {
        val ocRoot = File(ctx.filesDir, "oc")
        var count = 0
        var skipped = 0
        // E-090（2026-10-11）：本次修复之前，剥错前缀会把整包写进 `oc/usr/data/`。
        // 只删这一个固定路径（`oc/usr` 由本模块独占），旧安装顺手把这几百 MB 收回来。
        runCatching {
            val ghost = File(ocRoot, "usr/data")
            if (ghost.isDirectory) {
                val freed = ghost.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
                ghost.deleteRecursively()
                RunLog.log("太极: 清理旧版错位释放残留（约 ${freed / 1048576} MB，E-090）")
            }
        }
        try {
            BufferedInputStream(pkg.inputStream(), 512 * 1024).use { buffered ->
                val tar = TarArchiveInputStream(XZCompressorInputStream(buffered))
                var entry: TarArchiveEntry? = tar.nextTarEntry
                while (entry != null) {
                    val name = entry.name
                    // P3-5（2026-10-10）：条目名不许逃出 oc/usr。
                    // 精确前缀（而不是 substringAfter 的"找第一个出现位置"）+ canonical 复核。
                    // E-090（2026-10-11）：剥前缀必须用**准入判断的同一个常量**。E-082 里曾误写成
                    // `removePrefix("files/usr/")`——条目名以 `data/data/com.termux/` 开头，
                    // 一个字都没剥掉，整包落到 `oc/usr/data/data/com.termux/…` 错位。
                    val rel = relPathOf(name)
                    if (rel == null) { entry = tar.nextTarEntry; continue }
                    if (rel.startsWith(".")) { entry = tar.nextTarEntry; continue }
                    if (entry.isSymbolicLink || entry.isLink) {
                        // 链接条目：名字可能不越界，但它**指向**哪儿就管不着了——后续条目
                        // 可能顺着链接写出去。证道的 tar 里不需要链接，直接跳过。
                        skipped++
                        RunLog.log("太极: 跳过链接条目 $rel（P3-5）")
                        entry = tar.nextTarEntry; continue
                    }
                    val target = resolveExtractTarget(File(ocRoot, "usr"), rel)
                    if (target == null) {
                        skipped++
                        RunLog.log("太极: 跳过越界条目 $name（P3-5）")
                        entry = tar.nextTarEntry; continue
                    }
                    target.parentFile?.mkdirs()
                    // 完整性（2026-10-07 事故）：先写 .part，与归档记录断言一致后原子改名。
                    // 中途被杀只留 .part、旧二进制完好——截断文件不可能再冒充「已安装」
                    // （此前：写到 87% 被杀 → 250MB>100MB 通过 installed() → chmod 从未
                    //  轮到 → mode 600 → serve exec error=13 EACCES）。
                    val part = File(target.parentFile, "${target.name}.part")
                    part.delete()
                    part.outputStream().use { out -> tar.copyTo(out) }
                    if (part.length() != entry.size) {
                        val got = part.length()
                        part.delete()
                        RunLog.log("太极: 释放不完整 $rel（$got/${entry.size} 字节）——存储不足或进程被中断")
                        return DownloadResult(false, ctx.getString(R.string.oc_extract_incomplete, rel, got, entry.size))
                    }
                    // §B 顺序 17：chmod 必须在**最终路径**上补（copyTo 分支不保留执行位）。
                    // chmod 失败不中断安装（startServe 仍兜底自愈），但必须留痕。
                    publishExtractedFile(part, target, rel) { final ->
                        val chmod = runCatching {
                            android.system.Os.chmod(final.absolutePath, 493) // 0755
                        }
                        if (chmod.isFailure) {
                            RunLog.log("太极: chmod 0755 失败 $rel: ${chmod.exceptionOrNull()?.message}")
                        }
                    }
                    count++
                    if (count % 5 == 0) onProgress(ctx.getString(R.string.oc_extracting_count, count))
                    entry = tar.nextTarEntry
                }
            }
        } catch (t: Throwable) {
            RunLog.log("太极: 释放失败 ${t.message}")
            // 2026-10-08：见 [HumanizeError] 设计
            return DownloadResult(false, ctx.getString(R.string.oc_extract_failed, HumanizeError.title(t, ctx.resources)))
        }
        if (skipped > 0) {
            RunLog.log("太极: 释放时跳过了 $skipped 个可疑条目（越界路径/链接，P3-5）——若 serve 报缺文件，多半是它")
        }
        if (!installed(ctx)) {
            // E-090（2026-10-11）：这条失败以前**一行日志都不写**——用户报「装不上」时，
            // 日志里查不到任何痕迹（本次查证就卡在这里）。失败可见原则要求它留痕。
            val bin = binaryFile(ctx)
            RunLog.log(
                "太极: 释放后二进制缺失——预期 ${bin.absolutePath}（>100MB），实际 " +
                    (if (bin.isFile) "${bin.length()} 字节" else "不存在") +
                    "；本轮写入 $count 个文件、跳过 $skipped 个（E-090）",
            )
            return DownloadResult(false, ctx.getString(R.string.oc_binary_missing))
        }
        Settings2.prefs(ctx).edit().putString(VERSION_KEY, version).apply()
        RunLog.log("太极: OpenCode $version 释放完成（$count 个文件）")
        return DownloadResult(true, ctx.getString(R.string.oc_install_done, version))
    }

    /** 可安装的更新包（P2.5）：版本 + 直链 + release asset 的 digest（"sha256:…"去前缀）。
     * digest 由 GitHub API 动态取回——更新流程不落定版 SHA，无 digest 拒绝安装。 */
    data class UpdateInfo(val version: String, val url: String, val sha256: String?)

    /**
     * 检查更新的三种结果（2026-10-08 补）。
     *
     * 为什么必须分开：旧实现把「查不到」（网络不通 / API 被墙 / 404）与「确实是最新」都返回
     * null，UI 一律显示「OpenCode 已是最新」——**把失败说成了好消息**，用户点一下什么都
     * 不会发生，看起来就像"根本没有更新入口"。现在失败会明说原因。
     */
    sealed class UpdateCheck {
        data class Available(val info: UpdateInfo) : UpdateCheck()
        object UpToDate : UpdateCheck()
        data class Failed(val reason: String) : UpdateCheck()
    }

    /** 查最新版本（GitHub API）并区分三种结果；[checkUpdate] 是只关心"有更新"时的薄封装。 */
    fun checkUpdateDetailed(ctx: Context): UpdateCheck = try {
        val conn = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 10000; conn.readTimeout = 10000
        conn.setRequestProperty("User-Agent", "zhengdao")
        val body = conn.inputStream.bufferedReader().readText()
        conn.inputStream.close()
        val arr = org.json.JSONObject(body).getJSONArray("assets")
        // P2-17①（第⑭刀 #7）：资产名正则收紧 `[\d.]+` → `\d+(\.\d+)*` —— 源头排除
        // `2..0` / `2.0.` / `.2` 这类空段畸形串（原正则允许，后面 toInt 就抛）。
        val re = Regex("^opencode-(\\d+(?:\\.\\d+)*)-\\d+-aarch64\\.pkg\\.tar\\.xz$")
        var latest: Triple<List<Int>, String, String?>? = null // (版本段, 下载URL, digest SHA)
        for (i in 0 until arr.length()) {
            val a = arr.getJSONObject(i)
            val m = re.find(a.optString("name")) ?: continue
            // P2-17②：解析失败 ⇒ **跳过该 asset、继续扫描**（原先 `it.toInt()` 抛
            // NumberFormatException 会中断整个 for 循环——丢掉同批其它合法 asset，
            // 且被最外层 catch 吞成「检查更新失败」）。
            val ver = parseVersionSegments(m.groupValues[1])
            if (ver == null) {
                RunLog.log("太极: 跳过版本号无法解析的 asset：${a.optString("name")}")
                continue
            }
            if (latest == null || verNewer(ver, latest.first)) {
                val digest = a.optString("digest")
                    .takeIf { it.startsWith("sha256:") }?.substringAfter(':')
                latest = Triple(ver, a.optString("browser_download_url"), digest)
            }
        }
        // P2-17②：已装版本解析失败 ⇒ 回退出厂版本比较并留痕（原先抛 NFE ⇒ 检查更新整体失败）。
        val curRaw = installedVersion(ctx) ?: VERSION
        val cur = parseVersionSegments(curRaw) ?: run {
            RunLog.log("太极: 已装版本号「$curRaw」无法解析，按出厂版本 $VERSION 比较")
            parseVersionSegments(VERSION) ?: emptyList()
        }
        if (latest != null && verNewer(latest.first, cur)) {
            UpdateCheck.Available(UpdateInfo(latest.first.joinToString("."), latest.second, latest.third))
        } else UpdateCheck.UpToDate
    } catch (t: Throwable) {
        // 2026-10-08：异常原文 → 人话。reason 字段经 TaijiScreen:551 Toast 弹给用户。
        // ⚠️ 人话只给 UI，原文必须留在 RunLog —— 与本文件 OcManager:300 同一条约定。
        //    审查（同日）发现这里把原文整个吞了：检查更新失败时用户只看到"出错了"，
        //    日志里也搜不到任何线索 ⇒ 不可诊断。原文 + 异常类名一律落 RunLog。
        RunLog.log("太极: 检查更新失败 ${t.javaClass.simpleName}: ${t.message}")
        UpdateCheck.Failed(HumanizeError.title(t))
    }

    /** 有可更新版本返回 [UpdateInfo]，无更新/检查失败返回 null。
     * 与**已装版本**比较（更新过的不再重复报；出厂 VERSION 兜底未装/未知）。
     * ⚠️ 需要区分"失败"与"已最新"时用 [checkUpdateDetailed]。 */
    fun checkUpdate(ctx: Context): UpdateInfo? =
        (checkUpdateDetailed(ctx) as? UpdateCheck.Available)?.info

    /** 版本段逐位比较（List<Int> 无运算符比较）。internal：OcVersionParseTest 钉语义用，行为未变。 */
    internal fun verNewer(a: List<Int>, b: List<Int>): Boolean {
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /**
     * P2-17②（第⑭刀 #7）：版本号原文 → 段列表；**失败返回 null，绝不抛**。
     *
     * 替代原先两处 `split('.').map { it.toInt() }`（畸形段抛 NumberFormatException）。
     * 规则：
     * - 任何一段为空 / 非数字 / 超 Int 范围 ⇒ 整体 null（`toIntOrNull`，不抛）；
     * - 段数 > 4 ⇒ null（拒绝并留痕优于静默截断——截断会让 `2.0.22.999` 与 `2.0.22`
     *   比出可疑的新旧关系）；
     * - 调用方策略（见 [checkUpdateDetailed]）：asset 版本失败 ⇒ 跳过该 asset 继续扫描；
     *   已装版本失败 ⇒ 回退出厂版本 [VERSION]。
     * 纯函数，表驱动单测见 OcVersionParseTest。
     */
    internal fun parseVersionSegments(raw: String): List<Int>? {
        // ⚠️ Kotlin split 默认去掉尾部空串："." 会得到空列表 —— 空列表也必须判 null。
        val segs = raw.split('.')
        if (segs.isEmpty() || segs.size > 4) return null
        if (segs.any { it.isEmpty() }) return null
        return segs.map { it.toIntOrNull() ?: return null }
    }

    /** 隔离命名空间：避免与 Settings 混淆的轻量偏好入口。 */
    private object Settings2 {
        fun prefs(ctx: Context) =
            ctx.getSharedPreferences("zhengdao-taiji", Context.MODE_PRIVATE)
    }
}
