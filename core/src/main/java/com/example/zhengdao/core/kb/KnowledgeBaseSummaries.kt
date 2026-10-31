// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 依据的公开接口：java.security.MessageDigest（SHA-256，JDK/Android 官方 API）、
//   java.io.File 语义、RFC 8259 JSON（编码手写、解码走平台 org.json）。
package com.example.zhengdao.core.kb

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * 资料库 P2 摘要链的**账本层**（301，2026-10-30）。
 *
 * ## 它解决的是什么问题
 *
 * 摘要链原来三件都在、只缺**触发者**：`listRaw` 取输入 → `writeSummaries` 落盘 → `markBusy` 标记。
 * 缺触发者是因为原设计要"调模型逐文件生成摘要"，而那一步在 E-080 被用户拍板摘掉
 * （文件内容会出设备）。301 的裁定是：**接上，但改成纯规则生成，全程不调模型**。
 *
 * 于是本文件干三件事，都不联网、不读模型：
 * 1. **规则摘要**（[summarize]）：类型/标题/首句/体量/关键词，全由字符串规则得出
 * 2. **账本**（[Entry] ＋ 侧车 `产物/00-摘要.json`）：记住每条摘要的
 *    源文件 sha256 ＋ 生成时间 ＋ 摘要器版本，任一不符即**过期**（[staleness]）
 * 3. **上限淘汰**（[prune]）：≤ [MAX_ENTRIES] 条 / [MAX_BYTES] 字节，超了按 `lastHitAt` 升序走。
 *    ⚠️ **口径**：判据的原文是"最久未命中"，但**现状淘汰的是「最久未生成」**——生产侧还没有
 *    检索消费者，`lastHitAt` 恒等于 `genAt`；**接检索面板后恢复真 LRU**（缺陷台账：
 *    「301 摘要无检索面板」，预期接入＝UI 批次）。别按"最久未命中"理解现状，见 [lookup]。
 *
 * ## 为什么账本在侧车、正文在清单
 *
 * 正文必须落在 `产物/00-目录.md` 的 `## 文件摘要` 一节 —— 那是 agent 唯一读得到的面
 * （落盘口仍是 [KnowledgeBase.writeSummaries]，本文件不另开写口）。
 * 但"这条还算不算数"的元数据不能塞进同一份 markdown：`carryOverSummary` 按
 * `- **名** — 摘要` 的行形态搬旧摘要，多三类脏行就可能搬乱。所以元数据放同目录的
 * `00-摘要.json`，**可单独删除**（删了就等于把摘要全丢，索引毫发无伤，下次重建全量重算）。
 *
 * ## 三条不许破的规矩
 *
 * - **不碰 `KEY_SIG`**：摘要不是"原始文件变了"。写盘却更新指纹 ⇒ 下一轮真该重算清单的改动被跳过。
 * - **过期的不许当新用**：不渲染进清单、不参与检索，且触发重算。
 * - **不调模型**：`SummaryChainGateTest` 扫本文件的**代码行**（剥注释）禁掉一切网络/模型符号。
 */
object KnowledgeBaseSummaries {

    /** 侧车账本的文件名（与 `00-目录.md` 同目录 ⇒ 可单独清理）。 */
    const val FILE_NAME = "00-摘要.json"

    /** 账本格式版本。改了字段语义就加一，旧账本会被 [decode] 判为不可读 ⇒ 全量重算。 */
    const val SCHEMA = 1

    /**
     * **摘要器版本**。规则一改（断句、关键词、模板措辞）就必须加一 ——
     * 否则老摘要会以旧措辞继续冒充新结果。它是过期判据三件之一（见 [staleness]）。
     */
    const val SUMMARIZER_VERSION = "rule-1"

    /** 条目数上限（与 [KnowledgeBase.MAX_SCAN] 同量级：扫不到的文件本来也进不了清单）。 */
    const val MAX_ENTRIES = 2000

    /** 账本字节上限（按编码后的 JSON 算，不是内存占用）。 */
    const val MAX_BYTES = 20L * 1024L * 1024L

    /** 每个文件最多读开头多少字节来判断"讲什么"。 */
    const val HEAD_BYTES = 4096

    /** 单条摘要字数上限（与 `KnowledgeBaseSummarizer.MAX_SUMMARY_CHARS` 同口径）。 */
    const val MAX_SUMMARY_CHARS = 200

    // ── 数据 ────────────────────────────────────────────────────────────────

    /**
     * 一条摘要。
     *
     * @param rel 相对 `原始/` 的路径（清单里用的同一个键）
     * @param sha256 生成这条摘要时**源文件**的全文哈希（不是开头 4 KB）
     * @param genAt 生成时间（毫秒）
     * @param version 生成时的 [SUMMARIZER_VERSION]
     * @param lastHitAt 最后一次被检索命中的时间，[prune] 按它升序淘汰。
     *   ⚠️ **现状淘汰的是「最久未生成」**：生产侧还没有检索消费者 ⇒ 它恒等于 [genAt]；
     *   **接检索面板后恢复真 LRU**（缺陷台账「301 摘要无检索面板」，预期 UI 批次）。见 [lookup]。
     */
    data class Entry(
        val rel: String,
        val summary: String,
        val sha256: String,
        val genAt: Long,
        val version: String,
        val lastHitAt: Long,
    )

    /** 整个账本。`entries` 按 `rel` 升序 ⇒ 编码结果稳定（同一输入永远同一份字节）。 */
    data class Store(val entries: List<Entry>) {

        /** 渲染进清单用的 `文件名 → 摘要`（保持账本顺序）。 */
        fun toSummaryMap(): LinkedHashMap<String, String> {
            val out = LinkedHashMap<String, String>(entries.size)
            for (e in entries) out[e.rel] = e.summary
            return out
        }
    }

    /** 一条摘要相对于"当前源文件＋当前摘要器"的过期状态。 */
    enum class Staleness {
        /** 还能用。 */
        FRESH,

        /** 账本里没有（新文件，或侧车被清过）。 */
        ABSENT,

        /** 摘要器版本变了 ⇒ 旧措辞作废。 */
        VERSION_CHANGED,

        /** 源文件内容变了（含"读不出来"—— 保守当变了）。 */
        CONTENT_CHANGED,
    }

    /**
     * 一次刷新的下场（给人看与给测试断言用）。
     *
     * @param ran false ＝ 前置条件不满足，什么都没做（[why] 说明原因）
     * @param total 扫描到的文件数
     * @param fresh 沿用（未重算）的条数
     * @param recomputed 重算的条数（新文件 ＋ 内容变了 ＋ 版本变了）
     * @param dropped 源文件已不在、被丢掉的条数
     * @param evicted 超限被淘汰的条数
     * @param bytes 账本编码后的字节数
     */
    data class Report(
        val ran: Boolean = true,
        val why: String = "",
        val total: Int = 0,
        val fresh: Int = 0,
        val recomputed: Int = 0,
        val dropped: Int = 0,
        val evicted: Int = 0,
        val entries: Int = 0,
        val bytes: Long = 0,
        val wroteIndex: Boolean = false,
    ) {
        companion object {
            fun skipped(why: String): Report = Report(ran = false, why = why)
        }
    }

    /** [prune] 的结果。 */
    data class Pruned(val entries: List<Entry>, val evicted: Int, val bytes: Long)

    // ── 路径 ────────────────────────────────────────────────────────────────

    /** 侧车账本：与清单**同目录**（`<工作区>/产物/00-摘要.json`）。 */
    fun storeFile(ctx: Context): File = File(KnowledgeBase.doneDir(ctx), FILE_NAME)

    // ── ① 过期判据（纯函数）────────────────────────────────────────────────

    /**
     * 一条摘要还算不算数。**三个判据任一不符即过期**（用户裁定 ③）。
     *
     * 顺序有意为之：先看"有没有"，再看"版本"，最后看"内容"——
     * 版本不符时内容就算一样也不该沿用（措辞规则已经换了）。
     *
     * @param sha256 源文件**当前**的哈希；null ＝ 读不出来（按"内容变了"处理，保守）
     */
    fun staleness(entry: Entry?, sha256: String?, version: String = SUMMARIZER_VERSION): Staleness =
        when {
            entry == null -> Staleness.ABSENT
            entry.version != version -> Staleness.VERSION_CHANGED
            sha256 == null || entry.sha256 != sha256 -> Staleness.CONTENT_CHANGED
            else -> Staleness.FRESH
        }

    // ── ② 哈希与"读个头"（IO，但纯 Java，JVM 可测）─────────────────────────

    /** 全文 SHA-256（小写十六进制）。读不出来回 null —— 调用方按"内容变了"处理。 */
    fun sha256Of(file: File): String? = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        hex(md.digest())
    }.getOrNull()

    /** 纯字节数组的 SHA-256（单测与自检用）。 */
    fun sha256(bytes: ByteArray): String =
        hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** 字节 → 小写十六进制。**不用 `%02x`**：Java 的 Formatter 对负 Byte 会打成长串（实测 8 个 f）。 */
    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append((b.toInt() and 0xFF).toString(16).padStart(2, '0'))
        return sb.toString()
    }

    /**
     * 读文件开头 [maxBytes] 字节当"试读文本"。
     *
     * 判"读不出"的三条（宁可只按文件名说，也不要把乱码当正文喂进摘要）：
     * - 读失败 / 空文件
     * - 含 NUL 字节（二进制）
     * - UTF-8 解码出的替换字符超过 5%（GBK 等非 UTF-8 文本会落到这条）
     */
    fun headText(file: File, maxBytes: Int = HEAD_BYTES): String? {
        val bytes = runCatching {
            file.inputStream().use { ins ->
                val buf = ByteArray(maxBytes)
                var n = 0
                while (n < maxBytes) {
                    val r = ins.read(buf, n, maxBytes - n)
                    if (r <= 0) break
                    n += r
                }
                buf.copyOf(n)
            }
        }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        if (bytes.any { it == 0.toByte() }) return null
        val text = String(bytes, Charsets.UTF_8)
        val bad = text.count { it == '\uFFFD' }
        if (bad > 8 && bad * 20 > text.length) return null
        return text
    }

    // ── ③ 规则摘要（纯字符串，不调模型）────────────────────────────────────

    /**
     * 生成一条摘要。**纯规则**：类型（扩展名）＋ 标题（首个非空行）＋ 首句 ＋ 体量 ＋ 关键词。
     *
     * @param head 试读文本（[headText] 的结果）；null ＝ 读不出 ⇒ 只按文件名定位
     */
    fun summarize(rel: String, head: String?, size: Long, maxChars: Int = MAX_SUMMARY_CHARS): String {
        // 两种分隔符都剥：真机是 `/`，而 JVM 单测跑在 Windows 上会拿到 `\`
        val name = rel.substringAfterLast('/').substringAfterLast('\\')
        val kind = kindOf(name)
        val stem = if (name.contains('.')) name.substringBeforeLast('.') else name
        val text = head?.let { normalize(it) }.orEmpty()
        if (text.isBlank()) {
            return clip("$kind「$stem」· ${KnowledgeBase.humanSize(size)} —— 没读出正文（非文本或读不出），按文件名定位。", maxChars)
        }
        val title = titleOf(text) ?: stem
        val sentence = firstSentence(text)
        val kws = keywords(text, 3)
        val sb = StringBuilder()
        sb.append(kind).append("「").append(title).append("」")
        if (sentence.isNotBlank()) sb.append("：").append(sentence)
        val n = text.count { !it.isWhitespace() }
        sb.append(if (size > HEAD_BYTES) "（开头 $n 字" else "（全文 $n 字")
        if (kws.isNotEmpty()) sb.append(" · 关键词：").append(kws.joinToString("、"))
        sb.append("）")
        return clip(sb.toString(), maxChars)
    }

    /** 按扩展名分类。没有扩展名/不认识的，老实说"文件"。 */
    fun kindOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (ext) {
            "md", "markdown" -> "Markdown 笔记"
            "txt", "log", "text" -> "纯文本"
            "csv", "tsv" -> "表格文本"
            "json", "yaml", "yml", "toml", "ini", "xml" -> "配置/数据"
            "kt", "java", "py", "js", "ts", "sh", "rs", "go", "c", "cpp", "h", "sql" -> "代码"
            "docx", "doc", "odt", "rtf" -> "Word 文档"
            "pdf" -> "PDF"
            "xlsx", "xls", "ods" -> "电子表格"
            "pptx", "ppt", "odp" -> "演示文稿"
            "png", "jpg", "jpeg", "webp", "gif", "bmp", "heic" -> "图片"
            "zip", "7z", "rar", "tar", "gz" -> "压缩包"
            "mp3", "wav", "m4a", "flac" -> "音频"
            "mp4", "mov", "mkv" -> "视频"
            else -> "文件"
        }
    }

    /** 首个非空行当标题：剥掉 `#`/`>`/`-`/`*`/`=` 与成对引号，超 40 字截断。 */
    fun titleOf(text: String): String? {
        for (line in text.lineSequence()) {
            val t = line.trim().trimStart('#', '>', '-', '*', '=', ' ', '\t').trim()
            if (t.isEmpty()) continue
            if (t.startsWith("<!--")) continue
            val bare = t.trim('"', '\'', '“', '”', '「', '」', '`')
            return if (bare.length > 40) bare.take(40).trimEnd() + "…" else bare
        }
        return null
    }

    /** 标题行之后的第一个完整句子（`。！？!?` 断句，≤90 字）。 */
    fun firstSentence(text: String): String {
        val flat = bodyAfterTitle(text).replace(Regex("\\s+"), " ").trim()
        if (flat.isEmpty()) return ""
        val end = flat.indexOfFirst { it == '。' || it == '！' || it == '？' || it == '!' || it == '?' }
        if (end in 0 until 90) return flat.substring(0, end + 1)
        val head = flat.take(90).trimEnd()
        return if (head.length < flat.length) "$head…" else head
    }

    /** 关键词：中文取 2-gram、英文取 ≥3 字母词，去停用词，按次数取前 [limit] 个。 */
    fun keywords(text: String, limit: Int = 3): List<String> {
        val counts = LinkedHashMap<String, Int>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (isCjk(c)) {
                var j = i
                while (j < text.length && isCjk(text[j])) j++
                val run = text.substring(i, j)
                if (run.length == 2) bump(counts, run) else {
                    for (k in 0..run.length - 2) bump(counts, run.substring(k, k + 2))
                }
                i = j
            } else if (c.code < 128 && c.isLetterOrDigit()) {
                var j = i
                while (j < text.length && text[j].code < 128 && text[j].isLetterOrDigit()) j++
                val w = text.substring(i, j).lowercase(Locale.ROOT)
                if (w.length >= 3 && w !in ASCII_STOP) bump(counts, w)
                i = j
            } else {
                i++
            }
        }
        val ranked = counts.entries
            .filter { (k, n) -> k !in CJK_STOP && (n > 1 || k.length >= 3) }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { counts.keys.indexOf(it.key) })
        if (ranked.isNotEmpty()) return ranked.take(limit).map { it.key }
        // 短文档：一个词都不重复 ⇒ 退回"最长且最先出现"的三个，至少别交白卷
        return counts.keys.filter { it !in CJK_STOP }.sortedWith(compareByDescending<String> { it.length }).take(limit)
    }

    // ── ④ 上限淘汰（纯函数）─────────────────────────────────────────────────

    /**
     * 超限淘汰：条目数 > [maxCount] 或编码字节 > [maxBytes] 时，按 **`lastHitAt` 升序**
     * （同值看 `genAt`，再看 `rel`）逐条丢，直到两条都满足。
     *
     * ⚠️ **口径**：判据的原文是"最久未命中"，但**现状淘汰的是「最久未生成」**——生产侧没有
     * 检索消费者，`lastHitAt` 恒等于 `genAt`（见 [Entry.lastHitAt]）；**接检索面板后恢复真 LRU**。
     * 别把现状读成"最久未命中"。
     *
     * 结果**确定**：同一批条目、同一上限，淘汰的永远是同一批人。
     */
    fun prune(
        entries: List<Entry>,
        maxCount: Int = MAX_ENTRIES,
        maxBytes: Long = MAX_BYTES,
    ): Pruned {
        val keep = entries.toMutableList()
        val byHit = entries.sortedWith(compareBy({ it.lastHitAt }, { it.genAt }, { it.rel }))
        var bytes = envelopeBytes() + entries.sumOf { entryBytes(it) }
        var evicted = 0
        var i = 0
        while ((keep.size > maxCount || bytes > maxBytes) && i < byHit.size) {
            val victim = byHit[i++]
            if (keep.remove(victim)) {
                bytes -= entryBytes(victim)
                evicted++
            }
        }
        return Pruned(keep, evicted, bytes)
    }

    // ── ⑤ 编解码与落盘 ──────────────────────────────────────────────────────

    /**
     * 编码成 JSON（**手写**，不用 org.json 序列化）。
     *
     * 为什么要手写：字节上限（[MAX_BYTES]）要按**真实文件大小**算，而 org.json 的
     * `toString()` 键序/转义不可控 ⇒ 估算与实际对不上，上限就成了摆设。
     * 这里 `prefix + join(",") + suffix` 与 [entryBytes] 的记账是同一套算式，误差为 0。
     */
    fun encode(store: Store): String =
        buildString {
            append(prefix())
            store.entries.forEachIndexed { i, e ->
                if (i > 0) append(',')
                append(entryJson(e))
            }
            append(SUFFIX)
        }

    /** 解码。任何读不懂的东西一律回 null（调用方按"账本不存在"⇒ 全量重算），绝不半信半疑。 */
    fun decode(text: String?): Store? {
        if (text.isNullOrBlank()) return null
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (obj.optInt("schema", -1) != SCHEMA) return null
        val arr = obj.optJSONArray("entries") ?: return null
        val out = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val rel = o.optString("rel")
            val summary = o.optString("summary")
            val sha = o.optString("sha256")
            if (rel.isEmpty() || summary.isEmpty() || sha.isEmpty()) continue
            out += Entry(
                rel = rel,
                summary = summary,
                sha256 = sha,
                genAt = o.optLong("genAt", 0L),
                version = o.optString("version"),
                lastHitAt = o.optLong("lastHitAt", 0L),
            )
        }
        return Store(out)
    }

    /** 落盘（原子写：先 `.tmp` 再 rename）。内容没变就不写。@return true ＝ 真写了 */
    fun persist(storeFile: File, store: Store): Boolean {
        val text = encode(store)
        val old = readOrNull(storeFile)
        if (old == text) return false
        storeFile.parentFile?.mkdirs()
        val tmp = File(storeFile.parentFile, storeFile.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(storeFile)) {
            runCatching { storeFile.writeText(text, Charsets.UTF_8) }
            runCatching { tmp.delete() }
        }
        return true
    }

    // ── ⑥ 刷新（触发者调的那一步）───────────────────────────────────────────

    /**
     * 刷一次摘要：**只重算该重算的**，然后落两处盘（侧车账本 ＋ 清单正文）。
     *
     * 顺序有意：先落账本再渲染清单 —— 渲染是幂等的，账本先落地，万一渲染失败，
     * 下一轮还能凭账本把"已算过"的沿用下来，不会白算一遍。
     *
     * ⚠️ 前置守则与 [KnowledgeBase.writeSummaries] 一致：**清单已存在**才做
     * （摘要只是给清单追加一节，不能凭空造一份清单）。
     *
     * @param rows [KnowledgeBase.listRaw] 的结果（相对路径 ＋ 大小）
     * @param force true ＝ 全量重算（只有手动「重新整理」走这条）
     */
    fun refresh(
        rows: List<Pair<String, Long>>,
        rawDir: File,
        indexFile: File,
        storeFile: File,
        force: Boolean = false,
        now: Long = System.currentTimeMillis(),
        version: String = SUMMARIZER_VERSION,
        maxCount: Int = MAX_ENTRIES,
        maxBytes: Long = MAX_BYTES,
    ): Report {
        if (!indexFile.isFile) return Report.skipped("清单还没生成")
        val old = decode(readOrNull(storeFile))?.entries.orEmpty()
        val byRel = old.associateBy { it.rel }
        val nowRels = rows.mapTo(HashSet(rows.size)) { it.first }

        val kept = ArrayList<Entry>(rows.size)
        var fresh = 0
        var recomputed = 0
        for ((rel, size) in rows) {
            val file = File(rawDir, rel)
            val sha = sha256Of(file)
            val prev = byRel[rel]
            if (!force && staleness(prev, sha, version) == Staleness.FRESH) {
                kept += prev!!
                fresh++
            } else {
                kept += Entry(
                    rel = rel,
                    summary = summarize(rel, headText(file), size),
                    // 读不出哈希时记空串：下一轮 staleness 会判 CONTENT_CHANGED ⇒ 每次重建都重试
                    sha256 = sha ?: "",
                    genAt = now,
                    version = version,
                    // 命中历史要跟着走，否则"改了一个文件"会把它的命中时间重置成最新（淘汰顺序被带偏）
                    lastHitAt = prev?.lastHitAt ?: now,
                )
                recomputed++
            }
        }
        val dropped = old.count { e -> e.rel !in nowRels }
        val pruned = prune(kept.sortedBy { it.rel }, maxCount, maxBytes)
        if (pruned.entries.isNotEmpty() || storeFile.isFile) persist(storeFile, Store(pruned.entries))
        val wroteIndex = KnowledgeBase.writeSummariesTo(indexFile, Store(pruned.entries).toSummaryMap())
        return Report(
            ran = true,
            total = rows.size,
            fresh = fresh,
            recomputed = recomputed,
            dropped = dropped,
            evicted = pruned.evicted,
            entries = pruned.entries.size,
            bytes = pruned.bytes,
            wroteIndex = wroteIndex,
        )
    }

    // ── ⑦ 检索口（命中即记 lastHitAt）───────────────────────────────────────

    /**
     * 按相对路径取一条**未过期**的摘要；取到就记一次命中（`lastHitAt = now`）。
     *
     * ## "命中"只有这一个信号源（诚实说明）
     *
     * 清单渲染**不算命中**：每轮刷新都会把所有留存条目渲染一遍，记了就等于把所有
     * `lastHitAt` 一起推到 `now`，淘汰顺序被抹平、退化成先来先走。
     *
     * ## 现状口径：淘汰的是「最久未生成」，不是「最久未命中」
     *
     * App 内还没有"按名/按词查摘要"的界面（agent 是直接读 `00-目录.md` 的），所以生产侧
     * `lastHitAt` **恒等于 `genAt`** ⇒ [prune] 实际淘汰的是**最久未生成**的那条。
     * **接检索面板后恢复真 LRU**。这条已记进缺陷台账（「301 摘要无检索面板」，预期接入＝UI 批次），
     * 也写在 301 交付报告里 —— 改这里的人应该先知道它，再决定要不要把命中信号接到别处。
     */
    internal fun lookup(storeFile: File, rawDir: File, rel: String, now: Long = System.currentTimeMillis()): String? {
        val store = decode(readOrNull(storeFile)) ?: return null
        val hit = store.entries.firstOrNull { it.rel == rel } ?: return null
        if (staleness(hit, sha256Of(File(rawDir, rel))) != Staleness.FRESH) return null
        touch(storeFile, store, listOf(hit), now)
        return hit.summary
    }

    /** 按关键词查**未过期**的摘要（`rel` 或摘要正文含该词，忽略大小写）；命中即记一次。 */
    internal fun search(
        storeFile: File,
        rawDir: File,
        query: String,
        limit: Int = 20,
        now: Long = System.currentTimeMillis(),
    ): List<Pair<String, String>> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val store = decode(readOrNull(storeFile)) ?: return emptyList()
        val hits = ArrayList<Entry>()
        for (e in store.entries) {
            if (hits.size >= limit) break
            if (!e.rel.contains(q, ignoreCase = true) && !e.summary.contains(q, ignoreCase = true)) continue
            if (staleness(e, sha256Of(File(rawDir, e.rel))) != Staleness.FRESH) continue
            hits += e
        }
        touch(storeFile, store, hits, now)
        return hits.map { it.rel to it.summary }
    }

    /** 把命中的条目 `lastHitAt` 推到 [now] 并落盘（没命中就不写盘）。 */
    private fun touch(storeFile: File, store: Store, hits: List<Entry>, now: Long) {
        if (hits.isEmpty()) return
        val hitRels = hits.map { it.rel }.toHashSet()
        persist(storeFile, Store(store.entries.map { if (it.rel in hitRels) it.copy(lastHitAt = now) else it }))
    }

    // ── 内部：编码细节 ──────────────────────────────────────────────────────

    private const val SUFFIX = "]}"

    private fun prefix(): String =
        "{\"schema\":$SCHEMA,\"version\":\"$SUMMARIZER_VERSION\",\"entries\":["

    /** 一条条目的 JSON。字段顺序固定 ⇒ 同一 Entry 永远同一串字节。 */
    private fun entryJson(e: Entry): String =
        "{\"rel\":${quote(e.rel)},\"summary\":${quote(e.summary)},\"sha256\":${quote(e.sha256)}," +
            "\"genAt\":${e.genAt},\"version\":${quote(e.version)},\"lastHitAt\":${e.lastHitAt}}"

    /** 条目在账本里占的字节（含它后面那个逗号）。 */
    private fun entryBytes(e: Entry): Long =
        (entryJson(e).toByteArray(Charsets.UTF_8).size + 1).toLong()

    /** 账本骨架占的字节（`prefix` ＋ `suffix`，减去最后一行的逗号位）。 */
    private fun envelopeBytes(): Long =
        (prefix().toByteArray(Charsets.UTF_8).size + SUFFIX.toByteArray(Charsets.UTF_8).size - 1).toLong()

    /** JSON 字符串转义（只做 RFC 8259 要求的那几种）。 */
    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format(Locale.ROOT, "\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    // ── 内部：字符串规则 ────────────────────────────────────────────────────

    private fun normalize(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n')

    /** 标题行之后的正文（标题行前面允许有空行）。 */
    private fun bodyAfterTitle(text: String): String {
        val lines = text.lines()
        var i = 0
        while (i < lines.size && lines[i].isBlank()) i++
        return if (i < lines.size) lines.drop(i + 1).joinToString("\n") else ""
    }

    private fun clip(s: String, max: Int): String =
        if (s.length <= max) s else s.take(max - 1).trimEnd() + "…"

    private fun bump(counts: LinkedHashMap<String, Int>, token: String) {
        counts[token] = (counts[token] ?: 0) + 1
    }

    private fun isCjk(c: Char): Boolean = c.code in 0x4E00..0x9FFF

    /** 中文二元组里最常见的虚词 —— 出现在关键词里毫无信息量。 */
    private val CJK_STOP = setOf(
        "的", "了", "是", "在", "和", "有", "我", "你", "他", "这", "那", "不", "也", "就", "都",
        "要", "会", "到", "对", "与", "及", "或", "以", "为", "被", "把", "从", "而", "等", "个",
        "一个", "我们", "你们", "他们", "可以", "如果", "因为", "所以", "但是", "并且", "这个",
        "那个", "什么", "怎么", "已经", "还是", "只是", "以及", "进行", "没有", "不是", "就是",
    )

    private val ASCII_STOP = setOf(
        "the", "and", "for", "with", "this", "that", "from", "are", "was", "were", "will", "not",
        "you", "your", "our", "but", "can", "has", "have", "all", "any", "one", "two", "its",
    )

    private fun readOrNull(file: File): String? =
        runCatching { if (file.isFile) file.readText(Charsets.UTF_8) else null }.getOrNull()
}
