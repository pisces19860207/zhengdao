// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十八批（派工单 §1②）：**读一篇文件**的全部判据——认领什么、多大算超、怎么认出二进制、
// 标题取哪一行、指纹算哪个范围。全文件无 Android，JVM 表驱动直测（派工单 §2 点名的
// 「分词/打分/指纹的 JVM 表驱动测，这是选 B 方案的全部理由，不许只写真机验」里那两项之一）。
//
// ## 认领口径（派工单 §1② 逐字对应）
// - `TEXT_EXTS` ＋ `docx`：扩展名认领**不在本文件重算**，用 `LibraryPaths.classify` 的结果
//   （由薄壳把 `FileKind` 的名字带进来）——一份规则一个地方说，这里是第二个消费者。
// - 单文件 ≤2 MiB，超了记 `TOO_BIG`（建行、不认领正文；`KbIndexer.MAX_FILE_BYTES`（:29）同一个数）。
// - **前 8KB 有 NUL ⇒ 跳过**：连 `doc` 行都不建（它是二进制，不是「读不了的文本」）。
//   这条判据用 `BaopuCostAndArtifacts.hasNulByte`（:232，公开纯函数，产物白名单同款），
//   不另写一份 `bytes.any { it == 0.toByte() }`——两处各写一遍就是将来漂的开始。
//   **两条例外不是放宽，是不例外就错**（见 [PhoneText.headCall]）：① `.docx` 是 zip，头部天然带
//   `0x00`，对它用 NUL 判据＝一个 docx 都进不了索引（派工单点名要准入 docx）；② 判序走
//   `FileInfoTool.guessEncoding` 那一把（BOM 先于 NUL，2026-10-17 被 UTF-16 用例逼出来的修正），
//   自己重写判序会把每个多行 UTF-16 文本判成二进制。
// - `pdf/xlsx/压缩包` ⇒ `UNCLAIMED` **建行**，报告里给「未认领 N 个」（设计 §6.2「不认领要建行、
//   被排除不建行」那条分界线，本批仍然由 [SearchAdmission] 守住）。
//
// ## 两处「读得到」的差别
// `.docx` 抽不出正文时 [DocxTextExtractor.extract] 返回**空串**（它自己吞掉了原因，:56 的契约），
// 这里因此**不建这一行**（[docStatusOf] 对空正文给 null）并落进 `files_skipped` 计数——
// 关键是**绝不落 `ENCRYPTED`**：`ENCRYPTED` 是个有具体所指的判断（带密码的文档），
// 从空串推不出这个判断，写了就是假账。同理不落 `UNCLAIMED`：扩展名明明认了。
// 字面量没被产出这件事在
// `SearchSqlTest.status_字面量照设计62加63补的UNCLAIMED` 里仍然成立（那条只要求 DDL 与 enum 同源）。
package com.example.zhengdao.baopu.search

import com.example.zhengdao.baopu.agent.FileInfoTool
import com.example.zhengdao.baopu.agent.TextDecode
import com.example.zhengdao.baopu.hasNulByte
import com.example.zhengdao.core.doc.DocxTextExtractor
import java.io.File
import java.security.MessageDigest

/** 一篇文件的处置（`SKIP_*` 三种里，只有 `SKIP_BINARY` 连行都不建，见 [docStatusOf]）。 */
internal enum class ReadCall {
    /** 文本认领件：按编码判据解出来（UTF-8/UTF-16/GBK，同一把尺子在 [TextDecode]）。 */
    PARSE_TEXT,

    /** `.docx`：零依赖抽正文（[DocxTextExtractor]）。 */
    PARSE_DOCX,

    /** 超过 [MAX_FILE_BYTES] ⇒ 建 `doc` 行、状态 `TOO_BIG`、不切词。 */
    SKIP_TOO_BIG,

    /** 扩展名不认领（pdf/xlsx/图片/压缩包/无扩展名）⇒ 建 `doc` 行、状态 `UNCLAIMED`。 */
    SKIP_UNCLAIMED,

    /** 前 8KB 出现 NUL ⇒ **不建行**，只在计数里留一笔（派工单 §1②「跳过」）。 */
    SKIP_BINARY,

    /** 认领件但解不出编码 ⇒ **不建行**，计数里留 `undecodable`（不假装知道内容）。 */
    SKIP_UNDECODABLE,
}

/**
 * 读取判据（**纯函数**）。
 *
 * `head` 只有 8KB，`bytes` 是整篇——分两步是为了冷扫的账：绝大多数文件在
 * [preCall]／[headCall] 这两步就被挡掉了，只有真要切词的那些才付一次全文读取
 * （报告 §4 的冷扫数字就按这两步的计数分开报）。
 */
internal object PhoneText {

    /** 指纹与二进制判据只看前 8KB（与产物白名单那条判据同一个宽度：`BaopuCostAndArtifacts` :232）。 */
    const val HEAD_BYTES = 8192

    /** 单文件正文上限 2 MiB（派工单 §1②），超了 `TOO_BIG`。 */
    const val MAX_FILE_BYTES = 2L * 1024L * 1024L

    /** `doc.title` 最多留多少字（一行的量；标题是给人认文件的，不是正文摘要）。 */
    const val TITLE_MAX_CHARS = 120

    /** 正文里挑标题时最多扫前多少行（超过就是「没有标题」，不为一行标题读完整本）。 */
    const val TITLE_SCAN_LINES = 40

    /** `LibraryPaths.classify` 的三种 `FileKind` 里，本批认的两种的名字（字符串同源门禁用）。 */
    const val KIND_TEXT = "TEXT"
    const val KIND_DOCX = "DOCX"
    const val KIND_UNCLAIMED = "UNCLAIMED"

    /** [ReadCall.SKIP_BINARY] 在跳过计数里的名字（与 `SearchAdmission` 那批 tag 并列，不是排除清单的成员）。 */
    const val TAG_BINARY = "binary"
    const val TAG_UNDECODABLE = "undecodable"

    /**
     * 第一步（不用读盘）：大小与扩展名就能定的事。
     *
     * 顺序是**先大小后扩展名**：一个 40MB 的 pdf 既超大小又不认领，记 `TOO_BIG` 更有用
     * （用户能做的动作是「拆文件」，不是「换阅读器」）。
     * 认不出的 `kindTag`（`FileKind` 以后添了新值）落 `SKIP_UNCLAIMED`——不猜扩展名。
     */
    fun preCall(size: Long, kindTag: String): ReadCall = when {
        size > MAX_FILE_BYTES -> ReadCall.SKIP_TOO_BIG
        kindTag == KIND_UNCLAIMED -> ReadCall.SKIP_UNCLAIMED
        kindTag == KIND_TEXT -> ReadCall.PARSE_TEXT
        kindTag == KIND_DOCX -> ReadCall.PARSE_DOCX
        else -> ReadCall.SKIP_UNCLAIMED
    }

    /**
     * 第二步：读了前 8KB 之后的判定。
     *
     * 两条必须按 kind 分开，否则「准入 docx」这一条会被自己的二进制判据吃掉：
     * - `.docx` 是 zip，头部天然带 `0x00`（`PK\x03\x04\x14\x00…`）⇒ 对它也用 NUL 判据，
     *   结果是**每一个 docx 都被当成二进制**，一行都不建——派工单 §1① 明写 docx 要准入，
     *   所以这一步只对 [ReadCall.PARSE_TEXT] 生效；
     * - 文本件的判序用 [FileInfoTool.guessEncoding] 那一把（**BOM 先于 NUL**，它在 2026-10-17
     *   被 UTF-16 用例逼出来的修正）：UTF-16 的换行与码点低位在字节层面就带 `0x00`，
     *   「见 NUL 即二进制」会把每一个多行 UTF-16 文本判成二进制。判序不在这里重写，
     *   重写一次就是将来两处答案不一样的开始。
     *
     * `SKIP_*` 的结论在第一步就定了，不再往里塞 NUL 判据（不认领的文件本来就没读）。
     */
    fun headCall(call: ReadCall, head: ByteArray): ReadCall {
        if (call != ReadCall.PARSE_TEXT) return call
        val enc = FileInfoTool.guessEncoding(head, hasNulByte(head))
        return if (enc == FileInfoTool.Encodings.BINARY) ReadCall.SKIP_BINARY else call
    }

    /**
     * `doc.status` 的字面量从哪来（**返回 null = 不建这一行**）。
     *
     * 空正文同样不建行：一篇 0 字节的 `.md` 进了 `doc` 就只能靠 `OK` 骗过检索的
     * `status = 'OK'` 过滤，然后永远搜不到——那是「看起来索引了其实没有」的假账，
     * 计数里看得见（`watermark.files_skipped`）。
     */
    fun docStatusOf(call: ReadCall, text: String): DocStatus? = when (call) {
        ReadCall.PARSE_TEXT, ReadCall.PARSE_DOCX -> when {
            text.isBlank() -> null
            else -> DocStatus.OK
        }
        ReadCall.SKIP_TOO_BIG -> DocStatus.TOO_BIG
        ReadCall.SKIP_UNCLAIMED -> DocStatus.UNCLAIMED
        ReadCall.SKIP_BINARY, ReadCall.SKIP_UNDECODABLE -> null
    }

    /** 这一笔要不要进跳过计数、以什么名字进（[docStatusOf] 返回 null 的那些才有名字）。 */
    fun skipTagOf(call: ReadCall): String? = when (call) {
        ReadCall.SKIP_BINARY -> TAG_BINARY
        ReadCall.SKIP_UNDECODABLE -> TAG_UNDECODABLE
        else -> null
    }

    /**
     * 取正文（**不读盘**：`bytes` 由薄壳给）。
     *
     * `.docx` 抽不出正文 ⇒ 空串（[DocxTextExtractor] 的契约是「失败给空串、永不给 null」），
     * 由调用方按 [docStatusOf] 落账；文本件解不出编码 ⇒ [ReadCall.SKIP_UNDECODABLE]，
     * 这条只能在这里判出来（判据在 [TextDecode] 内部），所以返回体把「改判」也带出来。
     */
    fun bodyOf(call: ReadCall, file: File, bytes: ByteArray): Body {
        return when (call) {
            ReadCall.PARSE_DOCX -> Body(DocxTextExtractor.extract(bytes), call)
            ReadCall.PARSE_TEXT -> {
                val d = TextDecode.decode(file, bytes)
                val t = d.text
                if (t == null) Body("", ReadCall.SKIP_UNDECODABLE) else Body(t, call)
            }
            else -> Body("", call)
        }
    }

    /** 一篇文件的读取结果（[call] 可能被改判成 [ReadCall.SKIP_UNDECODABLE]）。 */
    internal data class Body(val text: String, val call: ReadCall)

    /**
     * 标题：第一行 markdown 标题（`# …`），没有标题就取第一条非空行；压成一行、截到
     * [TITLE_MAX_CHARS]（超长加 `…`，让人一眼看出是被截的，不是文件就这么短）。
     *
     * 井号**成串剥**（`trimStart('#')`）：`#### x` 的标题是 `x`，只剥一个会留下 `### x`——
     * 那是把标题的一部分当成了标题内容。整行只有井号（`#`／`###`）既不是标题也不是正文，
     * 直接跳过，不然它会当成「第一条非空行」被选回来。
     *
     * 只扫前 [TITLE_SCAN_LINES] 行——为一行标题读完整本不划算，而前 40 行没有标题的文档
     * （纯数据、纯日志）本来就没有标题可言。
     */
    fun titleOf(text: String): String {
        if (text.isBlank()) return ""
        val lines = text.lineSequence()
            .take(TITLE_SCAN_LINES)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.all { c -> c == '#' } }
            .toList()
        if (lines.isEmpty()) return ""
        val heading = lines.firstOrNull { it.startsWith("#") }
        val one = (heading?.trimStart('#')?.trim() ?: lines.first()).trim()
        return if (one.length <= TITLE_MAX_CHARS) one else one.take(TITLE_MAX_CHARS) + "…"
    }

    /**
     * 内容指纹：前 [HEAD_BYTES] 字节的 SHA-256（小写十六进制，64 个字符）。
     *
     * 为什么只算头 8KB（派工单 §1④ 的 `size+mtime` 是**变更检测**，指纹是**身份检测**）：
     * 全文件哈希要读完整本，而冷扫里「读完整本」恰恰是要省的那件事；头部哈希足够区分
     * 「同名同大小同时间但内容换了」这种极少见的情况，代价是每篇只多读 8KB。
     * 空输入给 SHA-256 的定值（`e3b0c442…`），不是空串——空串读起来像「没算」。
     */
    fun sha256Head(head: ByteArray): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-256").digest(head)
        digest.joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }
    }.getOrDefault("")

    /** 只取指纹要的那一段（薄壳读流头用；读不满就给读到的那些）。 */
    fun headOf(bytes: ByteArray): ByteArray = if (bytes.size <= HEAD_BYTES) bytes else bytes.copyOf(HEAD_BYTES)
}
