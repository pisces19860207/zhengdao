// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百九十四批：把「坏文件」拆成四类，各带名字与原因。
//
// 原 MemoryStore.scan() 把「读不动／非 UTF-8／超大／没有 front-matter 的合法 markdown」
// 一股脑塞进 brokenFiles，引擎再报「N 个文件解析失败」——用户会以为「我的记忆坏了」，
// 其实那份 `项目记忆.md` 只是非结构化的合法 markdown，一个字节都没坏。
//
// 三条口径（工单 §二）：
// 1. 只有 unreadable / badEncoding / tooBig 算「坏」；formatMismatch 是「能读、只是
//    没结构化」⇒ **原样保留**，只标「未结构化」，永不进坏文件计数。
// 2. 合法 .md 最多标「未结构化／无 asOf」；不许拿「解析失败」这种词盖所有。
// 3. 统计与展示分开：本文件只出数据与一行人读汇总，不碰 UI、不碰收编策略。
package com.example.zhengdao.baopu

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** 四类「判不了」的稳定键（闭集；wire 是回执/日志上的名字，不许拿中文标签当键）。 */
internal enum class ScanSkipKind(val wire: String) {
    UNREADABLE("unreadable"),
    BAD_ENCODING("badEncoding"),
    TOO_BIG("tooBig"),
    FORMAT_MISMATCH("formatMismatch"),
}

/** 一条被判的文件：名字 ＋ 哪一类 ＋ 人读原因（判据④「每条可查原因」）。 */
internal data class ScanSkipRow(val name: String, val kind: ScanSkipKind, val reason: String)

/** 一次扫描的体检单：四类分别计数、能列名、每条带 reason。 */
internal data class ScanSheet(val rows: List<ScanSkipRow>) {
    /** 真坏＝前三类；[ScanSkipKind.FORMAT_MISMATCH] 是「能读、只是没结构化」，永不进坏。 */
    val badRows: List<ScanSkipRow> = rows.filter { it.kind != ScanSkipKind.FORMAT_MISMATCH }
    val unstructuredRows: List<ScanSkipRow> = rows.filter { it.kind == ScanSkipKind.FORMAT_MISMATCH }
    val badNames: List<String> = badRows.map { it.name }
    val unstructuredNames: List<String> = unstructuredRows.map { it.name }

    fun count(kind: ScanSkipKind): Int = rows.count { it.kind == kind }
    fun hasFindings(): Boolean = rows.isNotEmpty()

    /** 一行汇总：`坏 2（1 读不了 / 1 编码错）· 未结构化 5`（零坏也照样给数）。 */
    fun line(): String = buildString {
        append("坏 ").append(badRows.size)
        if (badRows.isNotEmpty()) {
            val parts = BAD_LABELS.mapNotNull { (kind, label) ->
                count(kind).takeIf { it > 0 }?.let { "$it $label" }
            }
            append("（").append(parts.joinToString(" / ")).append("）")
        } else {
            // 零坏时留一个空格，免得「坏 0· 未结构化 1」粘在一起
            append(" ")
        }
        append("· 未结构化 ").append(unstructuredRows.size)
    }

    /** 逐条原因（名字 — 类 — 原因），给日志／界面点开看。 */
    fun detailLines(): List<String> = rows.map { "${it.name} — ${it.kind.wire}：${it.reason}" }

    companion object {
        val EMPTY: ScanSheet = ScanSheet(emptyList())

        /** 前三类在汇总行里的人读名字（顺序固定；第四类单列「未结构化」）。 */
        private val BAD_LABELS: List<Pair<ScanSkipKind, String>> = listOf(
            ScanSkipKind.UNREADABLE to "读不了",
            ScanSkipKind.BAD_ENCODING to "编码错",
            ScanSkipKind.TOO_BIG to "超限",
        )
    }
}

/** 一个 .md 的判定结果：收编（[Ok]）或按四类之一跳过（[Skip]）。 */
internal sealed class ScanVerdict {
    data class Ok(val entry: MemoryEntry) : ScanVerdict()
    data class Skip(val row: ScanSkipRow) : ScanVerdict()
}

/** 494 判定层：IO 由 [read] 注入 ⇒ 权限/IO 失败能在 JVM 上稳定直测（Windows chmod 不作数）。 */
internal object MemoryScanRules {
    /** 单个 .md 上限：与 `search_in_files` 的 8 MiB 同口径（不硬读超大文件）。 */
    const val MAX_FILE_BYTES: Long = 8L * 1024 * 1024

    /** 判定顺序固定：超限 → 读不了 → 编码错 → 格式不符 → 收编。 */
    fun classify(file: File, read: (File) -> ByteArray = { it.readBytes() }): ScanVerdict {
        val size = file.length()
        if (size > MAX_FILE_BYTES) return skip(file, ScanSkipKind.TOO_BIG, tooBigReason(size))
        val bytes = try {
            read(file)
        } catch (e: Exception) {
            return skip(file, ScanSkipKind.UNREADABLE,
                "读不了（${e.javaClass.simpleName}：权限或 IO 失败），原样保留")
        }
        val text = decodeUtf8(bytes)
            ?: return skip(file, ScanSkipKind.BAD_ENCODING,
                "不是 UTF-8 且无法解码，原样保留（没拿替换字符硬解）")
        val entry = EntryCodec.parse(text)
            ?: return skip(file, ScanSkipKind.FORMAT_MISMATCH,
                "能读、是合法 markdown，但格式不在预期内（没有 front-matter／无 asOf）⇒ 原样保留，只标未结构化")
        return ScanVerdict.Ok(entry)
    }

    /** 严格 UTF-8：畸形字节报错，而不是替换成 U+FFFD（替换会把「编码错」伪装成「格式不符」）。 */
    private fun decodeUtf8(bytes: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }

    private fun tooBigReason(size: Long): String =
        "超过 $MAX_FILE_BYTES 字节上限（实为 $size 字节），不硬读，原样保留"

    private fun skip(file: File, kind: ScanSkipKind, reason: String): ScanVerdict =
        ScanVerdict.Skip(ScanSkipRow(file.name, kind, reason))
}
