// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 依据的公开标准：Ed25519 签名（RFC 8032，java.security.Signature "Ed25519"，
// Android API 33+ Conscrypt 内置；本项目 minSdk 35）。
package com.example.zhengdao.ui

import android.content.Context
import android.util.Log
import com.example.zhengdao.rootfs.RootfsDownloader
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature

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

    private val CHANNELS = listOf(
        "https://raw.githubusercontent.com/pisces19860207/zhengdao/main/rootfs/agents.json",
        "https://cdn.jsdelivr.net/gh/pisces19860207/zhengdao@main/rootfs/agents.json",
    )

    /** manifest 条目（骨架 §2 字段子集：升级=重跑安装命令，helper 流程 M3 后期再扩）。 */
    data class Entry(
        val id: String,
        val name: String,
        val desc: String,
        val launchCmd: String,
        val installCmd: String,
    )

    private fun publicKey(): PublicKey {
        val raw = java.util.Base64.getDecoder().decode(PUBLIC_KEY_B64)
        check(raw.size == 32) { "manifest 公钥配置非法（长度 ${raw.size} ≠ 32）" }
        // RFC 8032：raw 编码 = y 小端 + 最高字节的最高位是 x 的奇偶符号位
        val xOdd = (raw[31].toInt() and 0x80) != 0
        val yBytes = raw.reversedArray().also { it[0] = (it[0].toInt() and 0x7F).toByte() }
        val kf = KeyFactory.getInstance("Ed25519")
        return kf.generatePublic(
            java.security.spec.EdECPublicKeySpec(
                java.security.spec.NamedParameterSpec.ED25519,
                java.security.spec.EdECPoint(xOdd, java.math.BigInteger(1, yBytes)),
            )
        )
    }

    /** Ed25519 验签（纯函数，先验签后解析的"验签"半边；JVM 可测）。 */
    fun verify(body: ByteArray, sigBase64: String): Boolean = try {
        val sig = java.util.Base64.getDecoder().decode(sigBase64)
        val s = Signature.getInstance("Ed25519")
        s.initVerify(publicKey())
        s.update(body)
        s.verify(sig)
    } catch (t: Throwable) {
        Log.w(TAG, "验签异常（视为失败）: ${t.message}")
        false
    }

    /** 解析 manifest 文本（纯函数；只在验签通过后调用）。 */
    fun parse(text: String): List<Entry> {
        fun str(id: String, key: String): String =
            Regex("\"$id\"[^{]*?\"$key\"\\s*:\\s*\"([^\"]*)\"").let { r ->
                // 逐 agent 块解析：以 "id" 为锚找块内字段
                val block = Regex("\"id\"\\s*:\\s*\"$id\"[^}]*}").find(text)?.value ?: return@let ""
                Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(block)?.groupValues?.get(1) ?: ""
            }
        val ids = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").findAll(text).map { it.groupValues[1] }.toList()
        return ids.mapNotNull { id ->
            val name = str(id, "name")
            val install = str(id, "installCmd")
            if (name.isBlank() || install.isBlank()) null
            else Entry(
                id = id,
                name = name,
                desc = str(id, "desc"),
                launchCmd = str(id, "launchCmd").ifBlank { id },
                installCmd = install,
            )
        }
    }

    /** 最近一次验签通过的条目（无缓存返回 null = 调用方用出厂版）。 */
    fun cached(ctx: Context): List<Entry>? = try {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.getString(KEY_BODY, null)?.let { parse(it) }
    } catch (_: Throwable) {
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
            for (url in CHANNELS) {
                try {
                    val body = RootfsDownloader.fetchText(url) ?: continue
                    val sig = RootfsDownloader.fetchText("$url.sig") ?: continue
                    if (!verify(body.toByteArray(Charsets.UTF_8), sig)) {
                        Log.w(TAG, "验签不过，丢弃该通道: $url")
                        continue
                    }
                    // 验签通过才落地缓存（先验签后解析、先验签后持久化）
                    prefs.edit().putString(KEY_BODY, body)
                        .putLong(KEY_TIME, System.currentTimeMillis()).apply()
                    RunLogCompat.log("Agent 清单已更新（${cachedVersionText(ctx)}，来自 ${hostOf(url)}）")
                    applied = true
                    break
                } catch (t: Throwable) {
                    Log.w(TAG, "通道失败: $url", t)
                }
            }
            if (!applied) RunLogCompat.log("Agent 清单刷新失败，沿用现有清单")
            onDone?.invoke(applied)
        }.start()
    }

    private fun hostOf(url: String): String =
        Regex("https://([^/]+)/").find(url)?.groupValues?.get(1) ?: url
}

/** RunLog 的轻引用（避免 ui 包反向依赖 rootfs 包名冲突的阅读成本）。 */
private object RunLogCompat {
    fun log(text: String) = com.example.zhengdao.rootfs.RunLog.log(text)
}
