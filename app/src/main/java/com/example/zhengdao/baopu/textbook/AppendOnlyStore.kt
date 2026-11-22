// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 798 段 A · 只增不改存储。条目一旦以某 id 入库就永不覆写；
// 改 = 新增一条 + supersedes 指回旧 id，链可双向追。目录投放是唯一录入路径。
package com.example.zhengdao.baopu.textbook

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

internal data class RejectRow(val id: String?, val codes: String, val messages: String)

internal data class IngestReport(
    val accepted: List<String>,
    val pending: List<String>,
    val rejects: List<RejectRow>,
    val files: List<String>,
)

internal sealed interface AddResult {
    data class Ok(val id: String) : AddResult
    data class Duplicate(val id: String) : AddResult
    data class BrokenSupersedes(val id: String, val target: String) : AddResult
    data class NormativeOverride(val id: String, val target: String) : AddResult
}

internal class AppendOnlyStore(private val libraryDir: File) {
    private val entries = LinkedHashMap<String, TextbookEntry>()

    init {
        val journal = File(libraryDir, ENTRIES_FILE)
        if (journal.isFile) {
            journal.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.forEach { line ->
                val parsed = TextbookSchema.parse(JSONObject(line))
                if (parsed is EntryParse.Ok) entries[parsed.entry.id] = parsed.entry
            }
        }
    }

    fun all(): List<TextbookEntry> = entries.values.toList()

    fun get(id: String): TextbookEntry? = entries[id]

    fun add(entry: TextbookEntry): AddResult {
        if (entries.containsKey(entry.id)) return AddResult.Duplicate(entry.id)
        if (entry.supersedes.isNotBlank()) {
            val old = entries[entry.supersedes]
            if (old == null) return AddResult.BrokenSupersedes(entry.id, entry.supersedes)
            // 派工单追加：规范域标准不许被经验类取代（红线不向轶事低头）。
            if (old.domain == Domain.规范域 && entry.sourceType == SourceType.经验总结) {
                return AddResult.NormativeOverride(entry.id, entry.supersedes)
            }
        }
        entries[entry.id] = entry
        append(ENTRIES_FILE, TextbookSchema.toJson(entry).toString())
        return AddResult.Ok(entry.id)
    }

    /** 正向链：本条 → 取代它的条 → …（带环保护）。 */
    fun chainOf(id: String): List<String> {
        val chain = mutableListOf(id)
        val seen = mutableSetOf(id)
        while (true) {
            val next = entries.values.firstOrNull { it.supersedes == chain.last() }?.id ?: break
            if (!seen.add(next)) break
            chain += next
        }
        return chain
    }

    /** 反向链：本条 → 它取代的旧条 → …（带环保护）。 */
    fun lineageOf(id: String): List<String> {
        val chain = mutableListOf(id)
        val seen = mutableSetOf(id)
        while (true) {
            val parent = entries[chain.last()]?.supersedes?.takeIf { it.isNotBlank() } ?: break
            if (!seen.add(parent)) break
            chain += parent
        }
        return chain
    }

    fun due(today: LocalDate): List<ReviewView> = ReviewExpiry.due(all(), today)

    /** 一条命令：把一个投放目录里的 *.json / *.jsonl 全量投进来。 */
    fun ingest(dropDir: File): IngestReport {
        require(dropDir.isDirectory) { "投放路径不是目录：${dropDir.absolutePath}" }
        val accepted = mutableListOf<String>()
        val pending = mutableListOf<String>()
        val rejects = mutableListOf<RejectRow>()

        rawObjects(dropDir).forEach { (file, raw) ->
            val id = raw.optString("id").ifBlank { null }
            val validation = TextbookValidator.validate(raw)
            when (validation.pool) {
                Pool.REJECTED -> rejectOne(file, raw, id, validation, rejects)
                Pool.PENDING -> pendingOne(raw, id, pending)
                Pool.MAIN -> mainOne(file, id, validationEntry(raw), accepted, rejects)
            }
        }
        val files = dropDir.listFiles().orEmpty()
            .filter { it.isFile && (it.extension == "json" || it.extension == "jsonl") }
            .map { it.name }
        return IngestReport(accepted, pending, rejects, files)
    }

    private fun rejectOne(file: String, raw: JSONObject, id: String?, v: Validation, out: MutableList<RejectRow>) {
        val codes = v.rejections.joinToString(",") { it.code.name }
        val messages = v.rejections.joinToString("；") { it.message }
        append(REJECTS_FILE, JSONObject()
            .put("id", id).put("file", file).put("codes", codes).put("messages", messages).toString())
        // 引不到字段/判据的 = 读书笔记，另存不入库（拒收账里也留一份）。
        if (RejectCode.NO_FIELD_REFERENCE.name in codes) {
            append(NOTES_FILE, JSONObject()
                .put("id", id).put("file", file).put("content", raw.optString("content")).toString())
        }
        out += RejectRow(id, codes, messages)
    }

    private fun pendingOne(raw: JSONObject, id: String?, out: MutableList<String>) {
        append(PENDING_FILE, TextbookSchema.toJson(validationEntry(raw)).toString())
        out += id ?: "(无 id)"
    }

    private fun mainOne(
        file: String, id: String?, entry: TextbookEntry,
        accepted: MutableList<String>, rejects: MutableList<RejectRow>,
    ) {
        when (val added = add(entry)) {
            is AddResult.Ok -> accepted += added.id
            else -> rejectAdd(file, id, added, rejects)
        }
    }

    private fun rejectAdd(file: String, id: String?, added: AddResult, out: MutableList<RejectRow>) {
        val code = when (added) {
            is AddResult.Ok -> "OK"
            is AddResult.Duplicate -> "DUPLICATE_ID"
            is AddResult.BrokenSupersedes -> "BROKEN_SUPERSEDES"
            is AddResult.NormativeOverride -> "NORMATIVE_OVERRIDE"
        }
        val message = when (added) {
            is AddResult.Ok -> "已入库"
            is AddResult.Duplicate -> "只增不改：id 已存在"
            is AddResult.BrokenSupersedes -> "supersedes 指向不存在的旧条"
            is AddResult.NormativeOverride -> "规范域标准不许被经验类教材取代"
        }
        append(REJECTS_FILE, JSONObject()
            .put("id", id).put("file", file).put("codes", code).put("messages", message).toString())
        out += RejectRow(id, code, message)
    }

    /** 派生读数文件（不是条目库本身），可覆写。 */
    fun writeDueReport(today: LocalDate): File {
        val target = File(libraryDir, DUE_FILE)
        val text = due(today).joinToString("\n") { view ->
            JSONObject()
                .put("id", view.entry.id)
                .put("status", view.status.name)
                .put("ageDays", view.ageDays)
                .toString()
        }
        libraryDir.mkdirs()
        target.writeText(if (text.isEmpty()) "" else text + "\n", Charsets.UTF_8)
        return target
    }

    private fun validationEntry(raw: JSONObject): TextbookEntry {
        val parsed = TextbookSchema.parse(raw)
        return (parsed as EntryParse.Ok).entry
    }

    private fun rawObjects(dropDir: File): List<Pair<String, JSONObject>> {
        val out = mutableListOf<Pair<String, JSONObject>>()
        val sources = dropDir.listFiles().orEmpty()
            .filter { it.isFile && (it.extension == "json" || it.extension == "jsonl") }
            .sortedBy { it.name }
        sources.forEach { file ->
            val text = file.readText(Charsets.UTF_8)
            if (file.extension == "jsonl") {
                text.lineSequence().filter { it.isNotBlank() }.forEach { out += file.name to JSONObject(it) }
            } else {
                val trimmed = text.trim()
                if (trimmed.startsWith("[")) {
                    JSONArray(trimmed).let { arr ->
                        for (i in 0 until arr.length()) out += file.name to arr.getJSONObject(i)
                    }
                } else {
                    out += file.name to JSONObject(trimmed)
                }
            }
        }
        return out
    }

    private fun append(name: String, line: String) {
        libraryDir.mkdirs()
        File(libraryDir, name).appendText(line + "\n", Charsets.UTF_8)
    }

    private companion object {
        const val ENTRIES_FILE = "entries.jsonl"
        const val PENDING_FILE = "待验证池.jsonl"
        const val REJECTS_FILE = "rejects.jsonl"
        const val NOTES_FILE = "笔记.jsonl"
        const val DUE_FILE = "待复核.jsonl"
    }
}
