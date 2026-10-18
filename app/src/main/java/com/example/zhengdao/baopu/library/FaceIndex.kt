// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据的公开标准：RFC 4637（utf-8）／UTF-8 BOM（EF BB BF）语义；SHA-256（FIPS 180-4，
// JDK 标准库 java.security.MessageDigest 实现）；JSON（RFC 8259）。
package com.example.zhengdao.baopu.library

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * 认脸（sha256）＋ 去重 ＋ 认脸索引（刀 2 B 段第 1/2/5 步）。
 *
 * ## 三件事，全是纯函数（无 IO、无 Android）——
 * 1. **认脸**：[sha256] 流式算（大文件不许一次读进内存）；
 * 2. **去重**：[dedup] 同 sha256 即同一件，索引里已有 ⇒ 记 `duplicate`，不重复处理；
 * 3. **索引读写**：[render]/[parse] `整理/_认脸索引.json`。
 *
 * ## 索引为什么必须字节稳定
 * 索引是**缓存前缀的一部分**（§E.2 缓存友好）：同一批内容两次生成必须字节相同 ⇒
 * [render] 里**没有生成时间戳字段**，items 按 `(name, sha256)` **显式排序**
 * （不依赖 HashMap 迭代顺序——负例演练第三条就是把这个改成 HashMap 序，
 * 「两次字节相同」必须红）。
 *
 * ## 为什么手搓 JSON 而不用 org.json
 * 本机无 Robolectric，JVM 单测里 android.jar 的 org.json 是 stub（抛 `Stub!`）；
 * 且索引要**逐字节可控**（一行一条 item，好 diff）。schema 固定 ⇒ 手搓成本低、
 * 可控性高。解析端宽容空白、严控字段（缺字段/类型错 ⇒ 返回 null，不猜）。
 */
internal object FaceIndex {

    /** 索引 schema 版本（渲染与解析共认；升级只增字段，旧字段不许删）。 */
    const val VERSION = 1

    /** 索引文件名（落在共享区 `整理/` 下——原件私有后，索引必须放共享输出区，
     *  否则终端 agent 连「有什么」都看不到，§E.12）。 */
    const val FILE_NAME = "_认脸索引.json"

    /** 一条索引项——字段与派工单 §1 B 段逐字对齐（八件，不多不少）。 */
    data class Item(
        val sha256: String,
        val name: String,
        val size: Long,
        val mtime: Long,
        /** 存储码（`deliver`/`import`，见 [LibrarySource]）；JSON 里渲染成 `投递`/`导入`。 */
        val source: String,
        val exportedAt: Long = 0,
        val isolated: Boolean = true,
        val kind: String = LibraryKind.TEXT,
    )

    /** 整份索引文档。两个汇总字段**由 items 派生**（不单独存 ⇒ 不会与 items 不一致）。 */
    data class Doc(
        val version: Int = VERSION,
        val items: List<Item> = emptyList(),
    ) {
        val lastExportAt: Long get() = items.maxOfOrNull { it.exportedAt } ?: 0L
        val unexportedCount: Int get() = items.count { it.exportedAt == 0L }
    }

    // ── 认脸 ─────────────────────────────────────────────────────────────────

    /** 流式 sha256（64KB 缓冲；大文件不整读进内存）。IO 失败**抛出**，不吞。 */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().toHex()
    }

    /** 字节数组版（测试与短文本用；同一算法，不走文件）。 */
    fun sha256Bytes(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()

    // ── 去重 ────────────────────────────────────────────────────────────────

    /**
     * 去重结果。[kept] = 要进流水线的（索引里没见过）；[duplicates] = 同内容已认过脸的
     * （**保留来源路径与时间**：firstSeen 里记着它第一次的模样，不重复处理）。
     */
    data class DedupResult(
        val kept: List<Item>,
        val duplicates: List<Pair<Item, Item>>, // (本次候选, 索引里已有那条)
    )

    /**
     * 按 sha256 去重（**纯函数**）。
     *
     * 两条规则：
     * 1. 候选之间同 sha ⇒ 只留**排序后第一个**（稳定：先按 name 再按 sha 排，不依赖输入顺序）；
     * 2. 与 [existing] 同 sha ⇒ duplicate（不重复处理）。
     */
    fun dedup(candidates: List<Item>, existing: List<Item>): DedupResult {
        val existingBySha = existing.associateBy { it.sha256 }
        val kept = LinkedHashMap<String, Item>()
        val duplicates = mutableListOf<Pair<Item, Item>>()
        val ordered = candidates.sortedWith(sortKey)
        for (c in ordered) {
            val seen = existingBySha[c.sha256]
            if (seen != null) {
                duplicates += c to seen
                continue
            }
            val already = kept[c.sha256]
            if (already != null) {
                duplicates += c to already
                continue
            }
            kept[c.sha256] = c
        }
        return DedupResult(kept.values.toList(), duplicates)
    }

    // ── 渲染（字节稳定） ─────────────────────────────────────────────────────

    /**
     * 渲染成索引 JSON 文本。**纯函数**：同样 Doc ⇒ 同样字节。
     *
     * 形制：一行一条 item（好 diff）；items 按 `(name, sha256)` 显式排序；
     * **无生成时间戳**。布尔渲染 `true`/`false`，source 渲染 `投递`/`导入`（人看的口径）。
     */
    fun render(doc: Doc): String {
        val sorted = doc.items.sortedWith(sortKey)
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"version\": ").append(doc.version).append(",\n")
        sb.append("  \"last_export_at\": ").append(doc.lastExportAt).append(",\n")
        sb.append("  \"unexported_count\": ").append(doc.unexportedCount).append(",\n")
        if (sorted.isEmpty()) {
            sb.append("  \"items\": []\n")
        } else {
            sb.append("  \"items\": [\n")
            sorted.forEachIndexed { i, it ->
                sb.append("    ").append(renderItem(it))
                sb.append(if (i == sorted.lastIndex) "\n" else ",\n")
            }
            sb.append("  ]\n")
        }
        sb.append("}\n")
        return sb.toString()
    }

    private fun renderItem(it: Item): String = buildString {
        append("{\"sha256\": \"").append(it.sha256)
        append("\", \"name\": \"").append(jsonEscape(it.name))
        append("\", \"size\": ").append(it.size)
        append(", \"mtime\": ").append(it.mtime)
        append(", \"source\": \"").append(LibrarySource.label(it.source))
        append("\", \"exported_at\": ").append(it.exportedAt)
        append(", \"isolated\": ").append(it.isolated)
        append(", \"kind\": \"").append(it.kind)
        append("\"}")
    }

    /** 只做最小转义（引号/反斜杠/控制字符）——文件名进 JSON 前必须过它。 */
    internal fun jsonEscape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        return sb.toString()
    }

    // ── 解析（宽容空白，严控字段） ───────────────────────────────────────────

    /**
     * 解析索引文本。**任何结构问题 ⇒ null**（调用方按「没有索引」处理并重写），
     * 不许半份猜着用。宽容：空白/换行任意；严控：八个字段必须齐全、类型必须对。
     */
    fun parse(text: String): Doc? {
        val version = findInt(text, "\"version\"") ?: return null
        // version 是 schema 小整数；越界/溢出 ⇒ 结构坏 ⇒ null（不猜着用）
        if (version < 0 || version > Int.MAX_VALUE) return null
        val items = parseItems(text) ?: return null
        return Doc(version = version.toInt(), items = items)
    }

    /** 取出 items 数组的文本切片（括号配对，跳过字符串内的括号）。 */
    private fun parseItems(text: String): List<Item>? {
        val key = text.indexOf("\"items\"")
        if (key < 0) return null
        var i = text.indexOf('[', key)
        if (i < 0) return null
        val start = i
        var depth = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '"') { i = skipJsonString(text, i); continue }
            if (ch == '[' || ch == '{') depth++
            if (ch == ']' || ch == '}') {
                depth--
                if (depth == 0) break
            }
            i++
        }
        if (i >= text.length) return null
        val body = text.substring(start + 1, i)
        if (body.isBlank()) return emptyList()
        val objects = splitTopLevelObjects(body) ?: return null
        // **任何一条不合格 ⇒ 整份索引判坏**（返回 null，调用方按「没有索引」重写）。
        // 不许 mapNotNull 悄悄丢：少一个字段的条目被丢空后，索引看起来「合法但空的」，
        // 下一轮流水线会拿新条目把旧索引**覆盖掉**——用户的脸白白丢一遍。
        val parsed = objects.map { parseItem(it) }
        return if (parsed.any { it == null }) null else parsed.filterNotNull()
    }

    /** 按顶层 `{…}` 切（字符串内/嵌套花括号不切）。 */
    private fun splitTopLevelObjects(body: String): List<String>? {
        val out = mutableListOf<String>()
        var depth = 0
        var start = -1
        var i = 0
        while (i < body.length) {
            val ch = body[i]
            if (ch == '"') { i = skipJsonString(body, i); continue }
            when (ch) {
                '{' -> { depth++; if (depth == 1) start = i }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        out += body.substring(start, i + 1)
                        start = -1
                    }
                    if (depth < 0) return null
                }
            }
            i++
        }
        return if (depth == 0) out else null
    }

    private fun parseItem(obj: String): Item? {
        val sha = findString(obj, "\"sha256\"") ?: return null
        val name = findString(obj, "\"name\"") ?: return null
        val size = findInt(obj, "\"size\"") ?: return null
        val mtime = findInt(obj, "\"mtime\"") ?: return null
        val sourceLabel = findString(obj, "\"source\"") ?: return null
        val exportedAt = findInt(obj, "\"exported_at\"") ?: return null
        val isolated = findBool(obj, "\"isolated\"") ?: return null
        val kind = findString(obj, "\"kind\"") ?: return null
        val source = if (sourceLabel == "导入") LibrarySource.IMPORT else LibrarySource.DELIVER
        return Item(
            sha256 = sha,
            name = name,
            size = size,
            mtime = mtime,
            source = source,
            exportedAt = exportedAt,
            isolated = isolated,
            kind = kind,
        )
    }

    private fun findInt(text: String, key: String): Long? {
        val k = text.indexOf(key)
        if (k < 0) return null
        val colon = text.indexOf(':', k)
        if (colon < 0) return null
        var i = colon + 1
        while (i < text.length && text[i].isWhitespace()) i++
        val sb = StringBuilder()
        if (i < text.length && (text[i] == '-' || text[i] == '+')) { sb.append(text[i]); i++ }
        while (i < text.length && text[i].isDigit()) { sb.append(text[i]); i++ }
        return sb.toString().toLongOrNull()
    }

    private fun findString(text: String, key: String): String? {
        val k = text.indexOf(key)
        if (k < 0) return null
        val colon = text.indexOf(':', k)
        if (colon < 0) return null
        val q1 = text.indexOf('"', colon)
        if (q1 < 0) return null
        val end = skipJsonString(text, q1)
        if (end <= q1 + 1) return ""
        return jsonUnescape(text.substring(q1 + 1, end - 1))
    }

    private fun findBool(text: String, key: String): Boolean? {
        val k = text.indexOf(key)
        if (k < 0) return null
        val colon = text.indexOf(':', k)
        if (colon < 0) return null
        val rest = text.substring(colon + 1).trimStart()
        return when {
            rest.startsWith("true") -> true
            rest.startsWith("false") -> false
            else -> null
        }
    }

    /** 从开引号跳到闭引号之后一位（处理 `\"` 转义）。 */
    private fun skipJsonString(text: String, start: Int): Int {
        var i = start + 1
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i += 2
                '"' -> return i + 1
                else -> i++
            }
        }
        return text.length
    }

    private fun jsonUnescape(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    '"' -> { sb.append('"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    'u' -> {
                        if (i + 5 < s.length + 1 && i + 6 <= s.length) {
                            val hex = s.substring(i + 2, i + 6)
                            val code = hex.toIntOrNull(16)
                            if (code != null) { sb.append(code.toChar()); i += 6 } else { sb.append(ch); i++ }
                        } else { sb.append(ch); i++ }
                    }
                    else -> { sb.append(ch); i++ }
                }
            } else {
                sb.append(ch); i++
            }
        }
        return sb.toString()
    }

    /**
     * 排序键：索引与库查询共用**同一把尺子**（`(name, sha256)`，显式、稳定）。
     *
     * 第二十七批（死函数清理）把它从死函数变成唯一来源：
     * 原来这里是 `fun sortKey(item: Item)` —— 参数 `item` 从头到尾没被用过（写错了），
     * 而 [dedup] 与 [render] 各自**内联复制**了一遍同样的表达式。三处同源是"改一处忘两处"
     * 的经典土壤（排序规则一改，索引字节就与去重顺序不一致，而且是静默的）。
     * 现在只留这一处，两处调用点都改用它。
     */
    val sortKey: Comparator<Item> = compareBy({ it.name }, { it.sha256 })
}
