// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：第六百一十批（通读框架 · 事实落盘）。三条硬规则：
// 1. 每章一个 jsonl（`资料库→产物/通读/<书>/chapter-NNNN.jsonl`），**append-only**——
//    只追加不重写；重跑只补缺章（判据⑦）；
// 2. 每条事实自带 chapter + startLine/endLine（0 起、含端点，read_file 同口径）——
//    「抽 5 条能点回章＋行」就是靠这两个字段（判据②）；
// 3. 归并产物（章摘要/块摘要/全书摘要）也走 jsonl 追加，与事实同房型，不另起一套。
package com.example.zhengdao.baopu.readthrough

import java.io.File
import org.json.JSONObject

/**
 * 一条事实（jsonl 的一行的内存形状）。
 *
 * [subjectId] 是实体的唯一键——**同名不同人靠它区分**（合并只认 id，见 [EntityMerge]）。
 * [quote] 是原文证据（≤30 字）：点回章＋行之后，人还要能对上原句。
 */
internal data class FactLine(
    val chapter: Int,
    val startLine: Int,
    val endLine: Int,
    val subject: String,
    val subjectId: String,
    val predicate: String,
    val obj: String,
    val quote: String,
    val sliceId: String,
    val createdAt: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", "fact")
        put("chapter", chapter)
        put("start_line", startLine)
        put("end_line", endLine)
        put("subject", subject)
        put("subject_id", subjectId)
        put("predicate", predicate)
        put("object", obj)
        put("quote", quote)
        put("slice_id", sliceId)
        put("created_at", createdAt)
    }

    companion object {
        const val MAX_FIELDS = 11

        /** 宽松解析：字段缺/类型不对 ⇒ null（调用方记异常行，不崩）。 */
        fun fromJson(o: JSONObject): FactLine? {
            val chapter = o.optInt("chapter", -1)
            val startLine = o.optInt("start_line", -1)
            val endLine = o.optInt("end_line", -1)
            val subject = o.optString("subject").trim()
            val subjectId = o.optString("subject_id").trim()
            val predicate = o.optString("predicate").trim()
            if (chapter < 0 || startLine < 0 || endLine < startLine) return null
            if (subject.isEmpty() || subjectId.isEmpty() || predicate.isEmpty()) return null
            return FactLine(
                chapter = chapter,
                startLine = startLine,
                endLine = endLine,
                subject = subject,
                subjectId = subjectId,
                predicate = predicate,
                obj = o.optString("object").trim(),
                quote = o.optString("quote").trim(),
                sliceId = o.optString("slice_id").trim(),
                createdAt = o.optLong("created_at"),
            )
        }
    }
}

/** 归并产物的一行（章摘要 / 块摘要 / 全书摘要共用一个形状，payload 原样进账）。 */
internal data class MergeLine(
    val type: String,
    val id: String,
    val inputs: Int,
    val payload: JSONObject,
    val createdAt: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type)
        put("id", id)
        put("inputs", inputs)
        put("payload", payload)
        put("created_at", createdAt)
    }

    companion object {
        const val TYPE_CHAPTER_MERGE = "chapter-merge"
        const val TYPE_BLOCK_MERGE = "block-merge"
        const val TYPE_BOOK_MERGE = "book-merge"

        /** 模型回文里捞一个 JSON 对象（宽松：取第一个 { 到最后一个 }）。 */
        fun payloadFrom(modelText: String): JSONObject? {
            val a = modelText.indexOf('{')
            val b = modelText.lastIndexOf('}')
            if (a < 0 || b <= a) return null
            return runCatching { JSONObject(modelText.substring(a, b + 1)) }.getOrNull()
        }
    }
}

/**
 * 事实落盘（append-only）。
 *
 * 命名：`chapter-0007.jsonl`（章号 0 起，与 [ReadSlice.chapterIndex] 一致）、
 * `block-03.jsonl`、`book.jsonl`、`.progress.json`（断点账本）、
 * `.anomalies.jsonl`（异常逐条报）。点号开头 ⇒ 不计入「产物 N 件」（512 口径）。
 */
internal object FactStore {

    const val BOOK_FILE = "book.jsonl"
    const val PROGRESS_FILE = ".progress.json"
    const val ANOMALY_FILE = ".anomalies.jsonl"

    private val CHAPTER_FILE = Regex("""^chapter-(\d+)\.jsonl$""")
    private val BLOCK_FILE = Regex("""^block-(\d+)\.jsonl$""")

    fun chapterFileName(chapter: Int): String = "chapter-%04d.jsonl".format(chapter)

    fun blockFileName(block: Int): String = "block-%03d.jsonl".format(block)

    fun chapterIndexOf(fileName: String): Int? =
        CHAPTER_FILE.matchEntire(fileName)?.groupValues?.get(1)?.toIntOrNull()

    fun blockIndexOf(fileName: String): Int? =
        BLOCK_FILE.matchEntire(fileName)?.groupValues?.get(1)?.toIntOrNull()

    /** 输出目录里已有事实文件的章（「重跑只补缺章」的 existing 集合）。 */
    fun existingChapters(dir: File): Set<Int> =
        dir.listFiles { f -> f.isFile }?.mapNotNull { chapterIndexOf(it.name) }?.toSet() ?: emptySet()

    /** 已有归并产物文件（块/书；续跑时书层块层不重跑的依据之一，另见 checkpoint）。 */
    fun existingBlocks(dir: File): Set<Int> =
        dir.listFiles { f -> f.isFile }?.mapNotNull { blockIndexOf(it.name) }?.toSet() ?: emptySet()

    /** 追加（append-only：FileWriter append，UTF-8；目录不存在就建）。 */
    fun append(dir: File, fileName: String, jsonLines: List<String>) {
        if (jsonLines.isEmpty()) return
        dir.mkdirs()
        val f = File(dir, fileName)
        jsonLines.forEach { line ->
            f.appendText(line + "\n", Charsets.UTF_8)
        }
    }

    /**
     * 断点账本落盘（覆盖写——账本是**状态**不是流水，与上面 append-only 的产物相反）。
     * 目录也在这里建：通读的落盘口只有 [append] 与本函数两个，agent 层零写 API。
     */
    fun writeProgress(dir: File, json: String) {
        dir.mkdirs()
        File(dir, PROGRESS_FILE).writeText(json, Charsets.UTF_8)
    }

    /** 逐行读回一个 jsonl（坏行跳过——账本不许被一条脏行卡死）。 */
    fun readLines(file: File): List<String> =
        if (!file.exists()) emptyList()
        else file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
}
