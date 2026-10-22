// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.baopu

import java.io.File

/**
 * 情景记忆（日记）的**文件存取壳**（147 阶段①）——`MemoryStore` 同哲学：
 * **文件是真相**（`<工作区根>/记忆/日记/<yyyy-MM-dd>.md`，一日一文件、条目内追加），
 * `baopu.db` 的 `diary_index` 只是可重建的加速索引。
 *
 * 与 [MemoryStore] 同款的边界：本类不碰 `Context`、不建线程（写由调用方线程做），
 * 全部逻辑可用临时目录 JVM 直测。脱敏闸在写路径上（判据 21）：**摘要先过
 * [DiaryRules.containsSensitive]，命中 ⇒ 整条不写**——「脱敏失败宁可整条不写」。
 */
internal class DiaryStore(private val diaryRoot: File) {

    data class WriteResult(val ok: Boolean, val file: File?, val reason: String? = null)

    /** 全量快照：条目 ＋ 解析失败的文件数（重建报告用，不静默——坏文件要有数可查）。 */
    data class Snapshot(val entries: List<DiaryEntry>, val skippedFiles: Int)

    fun dayFile(date: String): File = File(diaryRoot, "$date.md")

    /**
     * upsert（判据 2/3/11/21）：同 id 替换、其余保留，按 `createdAt` 稳定排序落盘；
     * 脱敏闸命中 ⇒ 拒写（返回 [WriteResult.ok] = false 与原因，调用方记日志）。
     */
    fun upsert(entry: DiaryEntry): WriteResult {
        if (DiaryRules.containsSensitive(entry.summary, entry.artifacts)) {
            return WriteResult(false, null, "脱敏闸命中：条目含敏感样式，整条不写（判据 21）")
        }
        val f = dayFile(entry.date)
        val existing = if (f.isFile) splitBlocks(f.readText()).mapNotNull(DiaryCodec::parse) else emptyList()
        val merged = existing.filter { it.id != entry.id } + entry
        val sorted = merged.sortedBy { it.createdAt }
        f.parentFile?.mkdirs()
        f.writeText(sorted.joinToString("\n") { DiaryCodec.render(it) })
        return WriteResult(true, f, null)
    }

    /** 全量读取（`loadAll` 就是 reindex 的地基——把 `diary_index` 删掉，扫一遍目录全回来）。 */
    fun loadAll(): Snapshot {
        if (!diaryRoot.isDirectory) return Snapshot(emptyList(), 0)
        val entries = mutableListOf<DiaryEntry>()
        var skipped = 0
        diaryRoot.listFiles { f -> f.isFile && f.name.endsWith(".md") }
            ?.sortedBy { it.name }
            ?.forEach { f ->
                val parsed = splitBlocks(f.readText()).mapNotNull(DiaryCodec::parse)
                if (parsed.isEmpty()) skipped++ else entries += parsed
            }
        return Snapshot(entries, skipped)
    }

    /** 按日读取。 */
    fun loadDay(date: String): List<DiaryEntry> {
        val f = dayFile(date)
        if (!f.isFile) return emptyList()
        return splitBlocks(f.readText()).mapNotNull(DiaryCodec::parse)
    }

    /**
     * 按 id 删除（阶段③「真删」的文件半边；**只删日记**——本函数只摸 `diaryRoot`，
     * 调用方红线：不得把任何产物/用户文件路径传进本类的任何方法）。
     * @return 删掉的条数；0 = 没找到（包括已在月度摘要里被浓缩的情形，由调用方另行重算摘要）。
     */
    fun deleteById(id: String): Int {
        if (!diaryRoot.isDirectory) return 0
        var deleted = 0
        diaryRoot.listFiles { f -> f.isFile && f.name.endsWith(".md") }
            ?.sortedBy { it.name }
            ?.forEach { f ->
                val all = splitBlocks(f.readText()).mapNotNull(DiaryCodec::parse)
                val kept = all.filter { it.id != id }
                if (kept.size != all.size) {
                    deleted += all.size - kept.size
                    if (kept.isEmpty()) f.delete() else f.writeText(kept.joinToString("\n") { DiaryCodec.render(it) })
                }
            }
        return deleted
    }

    /**
     * 日文件拆条目：`---` 行开块，到下一个 `---` 行（下一块的字段头）为止。
     * 正文里结构化拼装内容不会产生裸 `---` 行（摘要固定「工具成功 …」开头、
     * 产物行固定 `- ` 前缀），块边界因此无歧义。
     */
    private fun splitBlocks(text: String): List<String> {
        val blocks = mutableListOf<String>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            if (lines[i] != "---") { i++; continue }
            val sb = StringBuilder("---\n")
            i++
            while (i < lines.size && lines[i] != "---") { sb.append(lines[i]).append('\n'); i++ }
            if (i < lines.size) { sb.append("---\n"); i++ }
            while (i < lines.size && lines[i] != "---") { sb.append(lines[i]).append('\n'); i++ }
            blocks.add(sb.toString())
        }
        return blocks
    }
}
