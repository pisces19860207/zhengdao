// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：第四百九十一批判据——「manifest hash 一致」「导出里没有 key 与正文」
// 「冲突不覆盖」三条要在**纯函数**上钉死，而不是靠界面点一遍看着像对。
package com.example.zhengdao.backup

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 备份的**纯判据**：hash、路径安全、密钥识别、设置的白名单导出与差异。
 *
 * 零 Android 依赖（只吃 [File] 与字符串）⇒ JVM 直测。落盘与界面在别处。
 */
internal object BackupRules {

    /** sha256 十六进制长度（64 字符，小写）。 */
    const val SHA256_HEX_LEN = 64

    /** 单条目字节上限：账本/人设/技能都是文本，30 MiB 已经远超正常量（超了就说，不硬塞）。 */
    const val MAX_ENTRY_BYTES: Long = 30L * 1024 * 1024

    /** 整包字节上限：只做文本，256 MiB 之外一定是哪里不对（超了只带走已收下的条目）。 */
    const val MAX_TOTAL_BYTES: Long = 256L * 1024 * 1024

    /** 拷贝缓冲（顺序写，64 KB 少系统调用）。 */
    const val BUF_BYTES = 64 * 1024

    // ── hash ────────────────────────────────────────────────────────────────

    private val HEX = "0123456789abcdef".toCharArray()

    fun hexOf(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val b = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX[b ushr 4]
            out[i * 2 + 1] = HEX[b and 0x0F]
        }
        return String(out)
    }

    fun sha256Of(bytes: ByteArray): String =
        hexOf(runCatching { MessageDigest.getInstance("SHA-256").digest(bytes) }.getOrDefault(ByteArray(0)))

    /**
     * 文件的 sha256（流式，不整读进内存）。
     *
     * 读不出来时返回空串——调用方据此**跳过而不是当空文件**：空串过不了 [isSha256Hex]，
     * 于是"读不了"绝不会被写成"这文件是 0 字节"。
     */
    fun sha256Of(file: File): String = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(BUF_BYTES)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        hexOf(md.digest())
    }.getOrDefault("")

    fun isSha256Hex(s: String): Boolean =
        s.length == SHA256_HEX_LEN && s.all { it in '0'..'9' || it in 'a'..'f' }

    // ── 路径安全（zip-slip 与"清单被人手改过"）────────────────────────────────

    /**
     * zip 内路径是否可接受：相对、斜杠分隔、无 `..`、无盘符、无控制字符、非空段。
     *
     * 与 `baopu/archive` 的 zip-slip 判定同源口径，但这里是**清单**层面的第一道：
     * 解包那层还会再判一次（两个入口都不能省——清单在解包之前就要能拒）。
     */
    fun isSafeEntryPath(path: String): Boolean {
        if (path.isEmpty() || path.length > 4096) return false
        if (path.startsWith("/") || path.contains('\\') || path.contains(':')) return false
        if (path.any { it.code < 0x20 || it == '\u007f' }) return false
        val parts = path.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return false
        return true
    }

    // ── 密钥识别（"导出里没有 key"）─────────────────────────────────────────

    /**
     * 一个名字（文件名或首选项键名）是否**可能装密钥**。
     *
     * 判据是"按分隔符切词，任一段命中词表"而不是 `contains("key")`：
     * 后者会把 `keyboard_layout`、`monkey` 这类正常设置一并剔掉（宁可多留不可漏——
     * 但漏是事故、多剔只是少一条无关设置，所以这里反过来：**多剔优先**，
     * 连 `xxx_key`、`key_xxx` 这种整段都算）。
     */
    fun isSecretName(raw: String): Boolean {
        if (raw.isBlank()) return false
        val lower = raw.lowercase()
        val words = lower.split('_', '-', '.', '/', ' ', ':', '=')
        return words.any { it in SECRET_WORDS }
    }

    private val SECRET_WORDS = setOf(
        "key", "keys", "apikey", "token", "tokens", "secret", "secrets",
        "password", "passwd", "pwd", "auth", "authorization",
        "credential", "credentials", "cookie", "cookies", "bearer",
    )

    /** 设置导出时丢弃的键（含密钥形状的）。 */
    fun isSecretPrefKey(key: String): Boolean = isSecretName(key)

    // ── 设置的白名单导出 ────────────────────────────────────────────────────

    /**
     * 把**白名单内**几个 SharedPreferences 文件的快照编成一份 JSON（只留密钥以外的标量）。
     *
     * 为什么是"几个文件"而不是一个：设置散在两处真的存在——`zhengdao-settings`（App 设置
     * 的真源）与 `zhengdao-appearance`（外观）。只带前者的话，"换了手机还原"之后外观回默认，
     * 用户看到的就是"设置没还原全"。
     *
     * 为什么只留标量：`Set<String>` 之类没有稳定顺序，导出去再导回来会变成"看起来一样、
     * 比 bytes 不一样"，而判据要的是「同版本可完整还原（行数/字节一致）」。丢掉它们
     * 不静默——[SettingsDump.dropped] 会把键名带出来，界面能说"这几项没进包"。
     */
    fun dumpSettings(files: Map<String, Map<String, Any?>>): SettingsDump {
        val out = JSONObject()
        val dropped = ArrayList<String>()
        var kept = 0
        for ((fileName, values) in files) {
            val obj = JSONObject()
            for ((k, v) in values) {
                if (isSecretPrefKey(k)) {
                    dropped += "$fileName/$k"
                    continue
                }
                when (v) {
                    is Boolean, is Int, is Long, is String -> obj.put(k, v)
                    is Float -> obj.put(k, v.toDouble())
                    else -> dropped += "$fileName/$k"
                }
            }
            kept += obj.length()
            out.put(fileName, obj)
        }
        val json = JSONObject()
            .put("format", "zhengdao-settings")
            .put("formatVersion", 1)
            .put("fileCount", out.length())
            .put("keyCount", kept)
            .put("files", out)
            .toString(2)
        return SettingsDump(json, kept, dropped.sorted())
    }

    /** [dumpSettings] 的结果（[dropped] 是"没进包"的键，界面要说出来）。 */
    internal data class SettingsDump(val json: String, val keyCount: Int, val dropped: List<String>)

    /**
     * 解析 [dumpSettings] 产出的 JSON（文件 → 键 → 值）。
     *
     * 只认 format 标签与标量值；解不出 ⇒ null（调用方当作"这份设置坏了"，**不半信半疑地写**）。
     */
    fun parseSettings(json: String): Map<String, Map<String, Any>>? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (root.optString("format") != "zhengdao-settings") return null
        val files = root.optJSONObject("files") ?: return null
        val out = LinkedHashMap<String, Map<String, Any>>()
        for (fileName in files.keys()) {
            val obj = files.optJSONObject(fileName) ?: continue
            val values = LinkedHashMap<String, Any>()
            for (k in obj.keys()) {
                when (val v = obj.opt(k)) {
                    is Boolean, is Int, is Long, is Double, is String -> values[k] = v as Any
                    else -> Unit
                }
            }
            out[fileName] = values
        }
        return out
    }

    /** 设置的三档差异（用于"冲突列项让人选"里的那一行字）。 */
    internal data class SettingsDiff(val added: Int, val changed: Int, val same: Int) {
        val isConflict: Boolean get() = changed > 0
        val isEmpty: Boolean get() = added == 0 && changed == 0
    }

    fun diffSettings(
        current: Map<String, Map<String, Any?>>,
        incoming: Map<String, Map<String, Any>>,
    ): SettingsDiff {
        var added = 0
        var changed = 0
        var same = 0
        for ((fileName, values) in incoming) {
            val now = current[fileName] ?: emptyMap()
            for ((k, v) in values) {
                val old = now[k]
                when {
                    old == null -> added++
                    sameValue(old, v) -> same++
                    else -> changed++
                }
            }
        }
        return SettingsDiff(added, changed, same)
    }

    /** 数值跨类型比较（SP 里存 Int，JSON 里回来是 Int；存 Long 回来是 Long；Float→Double 要按值比）。 */
    private fun sameValue(a: Any?, b: Any): Boolean {
        if (a == b) return true
        if (a is Number && b is Number) return a.toDouble() == b.toDouble()
        return false
    }

    // ── 导入时的单条判定 ────────────────────────────────────────────────────

    /** 一个待还原条目在**本机现在**的样子（决定它进"新增/一样/冲突"哪一栏）。 */
    internal enum class ItemState {
        /** 本机没有 ⇒ 直接写（不用问）。 */
        NEW,

        /** 本机有、且内容与备份逐字节一样 ⇒ 什么都不做（不用问）。 */
        SAME,

        /** 本机有、内容不同 ⇒ **默认不覆盖**，列出来让人选。 */
        CONFLICT,

        /** 备份里有、但本机没有对应位置（如 `chat/`）⇒ 只报不写。 */
        UNTARGETED,
    }

    fun stateOf(live: File?, entrySha: String): ItemState = when {
        live == null -> ItemState.UNTARGETED
        !live.isFile -> ItemState.NEW
        sha256Of(live) == entrySha -> ItemState.SAME
        else -> ItemState.CONFLICT
    }
}
