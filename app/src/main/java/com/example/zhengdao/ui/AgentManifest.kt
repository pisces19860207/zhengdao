// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 依据的公开标准：Ed25519 签名（RFC 8032，java.security.Signature "Ed25519"，
// Android API 33+ Conscrypt 内置；本项目 minSdk 36）。
package com.example.zhengdao.ui

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.example.zhengdao.core.RunLog
import com.example.zhengdao.rootfs.RootfsDownloader
import com.example.zhengdao.terminal.crypto.Ed25519Verify

/**
 * Agent 清单 manifest（M3，骨架 §1 简化版）：
 * - 双通道拉取（GitHub raw + jsDelivr CDN），按序尝试；通道本身不可信，签名才是信任根；
 * - 安全闸：先验签后解析，验签不过 = 整份拒绝（防篡改 RCE）；
 * - 双通道都失败 / 验签不过 → 沿用最近一次验签通过的缓存；从无缓存 → 回退出厂版
 *   （AppState 内置清单，与 APK 同源发布）。
 * - 卡片数据合并规则见 [entriesOf]：manifest 按 id 覆盖内置，未知 id 追加。
 */
object AgentManifest {

    private const val TAG = "AgentManifest"
    private const val PREFS = "zhengdao-agents-manifest"
    private const val KEY_BODY = "verified_body"
    private const val KEY_TIME = "verified_at"
    private const val TTL_MS = 6 * 60 * 60 * 1000L

    /**
     * 固化在 APK 里的 Ed25519 公钥（32 字节 raw，base64）。
     * 私钥在发布者本地（仓库外 ~/.zhengdao-keys/），签发流程见 tools/sign-agents-manifest.py。
     * 断言：非占位符、长度必须 32 字节（骨架红线：构建期可断言，这里运行时兜底断言）。
     */
    private val PUBLIC_KEY_B64 = "LW7JtVXGiZGrFBFl8x1wlyPBtBez7tNNWzz4AhSI54Q="

    /** manifest 条目。npmPackage 非空时：已装版本从 rootfs 的 node_modules package.json
     *  探测，最新版从 npm registry 查询 → 卡片可显示「可更新」。 */
    data class Entry(
        val id: String,
        val name: String,
        val desc: String,
        val launchCmd: String,
        val installCmd: String,
        val npmPackage: String? = null,
        val uninstallCmd: String? = null, // P3：卸载命令（缺省 = 无卸载能力）
    )

    /**
     * Ed25519 验签（纯函数，先验签后解析的"验签"半边；JVM 可测）。
     *
     * R2 起计算搬进 Rust 核心（`CoreNative.verifyEd25519`，见 ERRATA E-051），平台实现保留做
     * **对拍与回退**；这段逻辑已抽到 `Ed25519Verify`，**与环境包索引共用同一条入口**
     * （2026-10-08 收口：此前只有清单走 Rust，索引那条仍直连平台实现）。
     */
    fun verify(body: ByteArray, sigBase64: String): Boolean = try {
        val sig = java.util.Base64.getDecoder().decode(sigBase64)
        val pub = java.util.Base64.getDecoder().decode(PUBLIC_KEY_B64)
        check(pub.size == 32) { "manifest 公钥配置非法（长度 ${pub.size} ≠ 32）" }
        Ed25519Verify.verify(pub, sig, body, "清单")
    } catch (t: Throwable) {
        Log.w(TAG, "验签异常（视为失败）: ${t.message}")
        false
    }

    /** 解析 manifest 文本（纯函数；只在验签通过后调用）。 */
    fun parse(text: String): List<Entry> {
        val ids = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").findAll(text).map { it.groupValues[1] }.toList()
        return ids.mapNotNull { id ->
            // 块锚点**每条只算一次**。原先这里是 `fun str(id, key)` 每次调用都重算 block，
            // 而 block 只是 (text, id) 的纯函数 ⇒ 一条目 6 个字段 = **6 次从文本头开始的
            // 全文正则扫描**。第一百一十八批 §2 T1：同轮 A/B/C 实测这样才快（20 条目 4.9×，
            // 真实清单 2.1×），而"缓存 Regex 对象"一档完全不快（Pattern.compile 不是成本大头）。
            // ⚠ 花括号必须转义：部分设备 regex 引擎（如 MagicOS/ICU）拒绝字符类内的裸 `}`，
            // 桌面 JVM 容忍——单测发现不了，真机上 parse 会整体抛 PatternSyntaxException。
            val block = Regex("\"id\"\\s*:\\s*\"$id\"[^\\}]*\\}").find(text)?.value ?: ""
            // 取不到块 ⇒ 六个字段一律空串，与搬移前"每次 str 各自找不到块"逐字等价。
            fun str(key: String): String =
                if (block.isEmpty()) ""
                else Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(block)?.groupValues?.get(1) ?: ""
            val name = str("name")
            val install = str("installCmd")
            if (name.isBlank() || install.isBlank()) null
            else Entry(
                id = id,
                name = name,
                desc = str("desc"),
                launchCmd = str("launchCmd").ifBlank { id },
                installCmd = install,
                npmPackage = str("npmPackage").ifBlank { null },
                uninstallCmd = str("uninstall").ifBlank { null },
            )
        }
    }

    /** 已安装 Agent 的版本探测：读 rootfs 内 npm 全局包的 package.json（host 侧直接可见）。 */
    fun installedVersion(ctx: Context, npmPackage: String): String? = try {
        val pkgJson = java.io.File(ctx.filesDir, "rootfs/usr/lib/node_modules/$npmPackage/package.json")
        if (pkgJson.isFile) {
            Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(pkgJson.readText())?.groupValues?.get(1)
        } else null
    } catch (_: Throwable) {
        null
    }

    /** 最近一次验签通过的条目（无缓存返回 null = 调用方用出厂版）。 */
    fun cached(ctx: Context): List<Entry>? = try {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.getString(KEY_BODY, null)?.let { parse(it) }
    } catch (t: Throwable) {
        // 不再静默：正则兼容性这类问题静默 null 会让 manifest 全链路失效且无迹可查
        Log.w(TAG, "清单缓存读取/解析失败，回退出厂版: $t")
        null
    }

    fun cachedVersionText(ctx: Context): String = try {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.getString(KEY_BODY, null)
            ?.let { Regex("\"version\"\\s*:\\s*([0-9]+)").find(it)?.groupValues?.get(1) }
            ?: "出厂版"
    } catch (_: Throwable) {
        "出厂版"
    }

    /**
     * 刷新（后台线程调用；结果回调任意线程）。
     * @param force true = 忽略 TTL 立即拉取（设置页手动按钮）；false = 6 小时内跳过
     */
    fun refresh(ctx: Context, force: Boolean = false, onDone: ((applied: Boolean) -> Unit)? = null) {
        Log.i(TAG, "refresh 被调用 force=$force")
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!force &&
            System.currentTimeMillis() - prefs.getLong(KEY_TIME, 0) < TTL_MS &&
            prefs.contains(KEY_BODY)
        ) {
            onDone?.invoke(false)
            return
        }
        Thread {
            var applied = false
            var idx = 0
            for (channel in channels(ctx)) {
                idx++
                try {
                    Log.i(TAG, "刷新：尝试通道 $idx")
                    val (body, sig) = channel()
                    Log.i(TAG, "刷新：通道 $idx 拉取成功，开始验签")
                    Log.i(TAG, "通道 $idx 内容指纹 body=${sha256Hex(body.toByteArray())} sig=${sha256Hex(sig.toByteArray())}")
                    if (!verify(body.toByteArray(Charsets.UTF_8), sig)) {
                        Log.w(TAG, "验签不过，丢弃该通道")
                        continue
                    }
                    // 验签通过才落地缓存（先验签后解析、先验签后持久化）
                    prefs.edit(commit = true) {
                        putString(KEY_BODY, body)
                        putLong(KEY_TIME, System.currentTimeMillis())
                    }
                    Log.i(TAG, "Agent 清单已更新（${cachedVersionText(ctx)}）")
                    RunLogCompat.log("Agent 清单已更新（${cachedVersionText(ctx)}）")
                    applied = true
                    break
                } catch (t: Throwable) {
                    Log.w(TAG, "通道 $idx 失败: ${t.message}")
                }
            }
            if (!applied) RunLogCompat.log("Agent 清单刷新失败，沿用现有清单")
            onDone?.invoke(applied)
        }.apply { isDaemon = true; name = "zd-manifest-refresh" }.start()
    }

    /**
     * 通道列表（惰性，每通道返回 <manifest 正文, 签名 base64>）。
     * 实测排序依据（2026-10-04，本机）：raw.githubusercontent 与 jsDelivr 在国内网络
     * 不稳定，而 api.github.com 稳定可达（环境更新按钮同域验证）——Contents API 排第一。
     */
    private fun channels(ctx: Context): List<() -> Pair<String, String>> {
        val repoPath = "pisces19860207/zhengdao/main"
        return listOf(
            // ① GitHub Contents API（base64 包裹，走 api.github.com）
            {
                val body = fetchContentsApi("$repoPath/rootfs/agents.json")
                val sig = fetchContentsApi("$repoPath/rootfs/agents.json.sig")
                require(body != null && sig != null) { "Contents API 不可达" }
                body to sig
            },
            // ② GitHub raw（trimEnds=false：签名覆盖完整字节，末尾换行不能裁）
            {
                val u = "https://raw.githubusercontent.com/$repoPath/rootfs/agents.json"
                val body = RootfsDownloader.fetchText(u, trimEnds = false)
                val sig = RootfsDownloader.fetchText("$u.sig")
                require(body != null && sig != null) { "raw 不可达" }
                body to sig
            },
            // ③ jsDelivr CDN（同上）
            {
                val u = "https://cdn.jsdelivr.net/gh/pisces19860207/zhengdao@main/rootfs/agents.json"
                val body = RootfsDownloader.fetchText(u, trimEnds = false)
                val sig = RootfsDownloader.fetchText("$u.sig")
                require(body != null && sig != null) { "jsDelivr 不可达" }
                body to sig
            },
        )
    }
    private fun sha256Hex(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /** 经 GitHub Contents API 抓文件内容（base64 解包；任何失败返回 null）。 */    private fun fetchContentsApi(path: String): String? {
        val json = RootfsDownloader.fetchText("https://api.github.com/repos/$path") ?: return null
        // 必须用正规 JSON 解析：GitHub 会把 \n / \/ / = (\u003d) 等全部转义，
        // 手写替换无法覆盖 \uXXXX 形态（实测 2026-10-04 内容被静默破坏致验签恒败）
        val content = org.json.JSONObject(json).getString("content")
        return String(
            java.util.Base64.getMimeDecoder().decode(content),
            Charsets.UTF_8,
        )
    }
}

/** RunLog 的轻引用（避免 ui 包反向依赖 rootfs 包名冲突的阅读成本）。 */
private object RunLogCompat {
    fun log(text: String) = com.example.zhengdao.core.RunLog.log(text)
}
