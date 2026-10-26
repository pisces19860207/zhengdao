// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-10-24-第一百八十二批 §3（整理产物落 整理/；原件只读；
// 整理一次出一张变更清单；审计可回滚；不自动删任何原件）。
package com.example.zhengdao.baopu.knowledge

import com.example.zhengdao.baopu.agent.TextDecode
import com.example.zhengdao.baopu.kb.KbChunker
import com.example.zhengdao.baopu.kb.KbIndexer
import com.example.zhengdao.baopu.library.FaceIndex
import com.example.zhengdao.baopu.library.LibraryPaths
import com.example.zhengdao.core.doc.DocxTextExtractor
import org.json.JSONObject
import java.io.File

/**
 * 抱朴整理的**执行层**（第一百八十二批）。
 *
 * ## 为什么原件不会丢
 * 本类**没有任何移动/改名/删除原件的代码**：只读 `原始/` 的字节，把整理后的副本
 * （md，带来源头＋摘要＋正文）写进 `整理/<类>/`。目标撞名不同内容 ⇒ 加 sha 后缀让路，
 * **永不覆盖**。原件零丢失不是靠"小心"，是靠没有这条写路径。
 *
 * ## 一张卡、一次审计、可回滚
 * - `propose` 只读，产出一份 [KnowledgeOrganizeRules.Plan]（界面用它渲染**一张**变更清单卡）；
 * - `execute` 逐项写副本，并 append 一行 JSONL 台账（runId/源/目标/两个 sha）；
 * - `rollback(runId)` 只删台账里「本类写进 整理/、且现在 sha 还对得上」的副本。
 */
internal object KnowledgeOrganize {

    data class ExecReport(
        val runId: String,
        val created: Int,
        val skipped: Int,
        val failed: Int,
        val notes: List<String> = emptyList(),
    ) {
        fun summary(): String = "整理完成：写入 $created 项，跳过 $skipped 项，失败 $failed 项"
    }

    /** 只读扫描 `原始/`，生成整理清单（不写任何文件）。 */
    fun propose(workspace: File, now: Long = System.currentTimeMillis()): KnowledgeOrganizeRules.Plan {
        val raw = KnowledgeLayout.rawDir(workspace)
        val scan = KnowledgeLayout.scan(raw)
        val candidates = ArrayList<KnowledgeOrganizeRules.Candidate>(scan.files.size)
        for (e in scan.files) {
            val file = File(raw, e.relPath)
            val (kind, reason) = LibraryPaths.classify(e.name)
            val readable = kind != LibraryPaths.FileKind.UNCLAIMED
            val sha = runCatching { FaceIndex.sha256(file) }.getOrDefault("")
            val excerpt = if (readable && e.size <= KbIndexer.MAX_FILE_BYTES) {
                readExcerpt(file, kind)
            } else {
                ""
            }
            candidates += KnowledgeOrganizeRules.Candidate(
                relPath = e.relPath,
                size = e.size,
                mtime = e.mtime,
                sha256 = sha,
                category = KnowledgeOrganizeRules.categoryOf(e.name),
                readable = readable && excerpt.isNotBlank(),
                excerpt = excerpt,
                reason = if (!readable) reason else if (excerpt.isBlank()) "读不出正文（编码/格式/超限）" else "",
            )
        }
        return KnowledgeOrganizeRules.plan(candidates, now)
    }

    /** 执行整理（只写 `整理/` 副本；原件一个字节都不动）。 */
    fun execute(workspace: File, plan: KnowledgeOrganizeRules.Plan, now: Long = System.currentTimeMillis()): ExecReport {
        if (!KnowledgeOrganizeRules.targetsSafe(plan)) {
            return ExecReport(plan.runId, 0, 0, plan.adds.size, listOf("整理目标越界（不在 整理/ 内），整批拒绝"))
        }
        val raw = KnowledgeLayout.rawDir(workspace)
        val doneDir = KnowledgeLayout.organizedDir(workspace)
        if (!doneDir.isDirectory && !doneDir.mkdirs()) {
            return ExecReport(plan.runId, 0, 0, plan.adds.size, listOf("建不了 整理/ 目录"))
        }
        var created = 0
        var skipped = 0
        var failed = 0
        val notes = mutableListOf<String>()
        for (item in plan.adds) {
            val source = File(raw, item.sourceRel)
            if (!source.isFile) {
                failed++
                notes += "${item.sourceRel} 原件不在了"
                continue
            }
            val sha = runCatching { FaceIndex.sha256(source) }.getOrDefault("")
            if (sha.isBlank() || sha != item.sha256) {
                failed++
                notes += "${item.sourceRel} 内容已变（sha 对不上），这一项不整理"
                continue
            }
            val body = readBody(source, item.sourceRel)
            if (body.isBlank()) {
                failed++
                notes += "${item.sourceRel} 读不出正文"
                continue
            }
            val content = render(item, body)
            // 计划的 targetRel 是相对「知识库根」的（`整理/<类>/<名>.md`），不是相对 整理/。
            val target = pickTarget(KnowledgeLayout.knowledgeDir(workspace), item.targetRel, content)
            if (target == null) {
                failed++
                notes += "${item.targetRel} 撞名且内容不同，没有覆盖"
                continue
            }
            if (target.isFile && sameBytes(target, content)) {
                skipped++
                continue
            }
            val ok = LibraryPaths.atomicWrite(target, content)
            if (!ok) {
                failed++
                notes += "${item.targetRel} 写盘失败（原件未动）"
                continue
            }
            created++
            appendAudit(workspace, plan.runId, item, target, sha, now)
        }
        return ExecReport(plan.runId, created, skipped, failed, notes)
    }

    /** 回滚一次整理：只删台账里「本类写的、sha 仍对得上」的副本；原件永不参与。 */
    fun rollback(workspace: File, runId: String): Int {
        val audit = KnowledgeLayout.organizeAuditFile(workspace)
        if (!audit.isFile) return 0
        var removed = 0
        runCatching {
            audit.readLines(Charsets.UTF_8).forEach { line ->
                val o = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                if (o.optString("run") != runId) return@forEach
                if (o.optString("action") != "add") return@forEach
                val rel = o.optString("target")
                val sha = o.optString("targetSha")
                if (rel.isBlank() || sha.isBlank()) return@forEach
                if (!rel.startsWith("整理/") || rel.contains("..")) return@forEach
                val target = File(KnowledgeLayout.knowledgeDir(workspace), rel)
                val now = runCatching { FaceIndex.sha256(target) }.getOrNull()
                if (now == sha && target.delete()) removed++
            }
        }
        return removed
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /** 读正文（≤2 MiB；docx 走提取器，html 剥标签，其余走唯一解码真源）。 */
    private fun readBody(file: File, rel: String): String {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return ""
        val ext = rel.substringAfterLast('.', "").lowercase()
        if (ext == "docx") {
            return runCatching { DocxTextExtractor.extract(bytes) }.getOrDefault("")
        }
        val text = runCatching { TextDecode.decode(file, bytes).text }.getOrNull() ?: return ""
        return if (ext == "html" || ext == "htm") KbChunker.htmlToText(text) else text
    }

    /** 摘要只读开头一小段（文本 64 KiB；docx 必须整包解，但前面已限 ≤2 MiB）。 */
    private fun readExcerpt(file: File, kind: LibraryPaths.FileKind): String {
        val bytes = if (kind == LibraryPaths.FileKind.DOCX) {
            runCatching { file.readBytes() }.getOrNull() ?: return ""
        } else {
            readHead(file, EXCERPT_BYTES)
        }
        if (bytes.isEmpty()) return ""
        val text = if (kind == LibraryPaths.FileKind.DOCX) {
            runCatching { DocxTextExtractor.extract(bytes) }.getOrDefault("")
        } else {
            runCatching { TextDecode.decode(file, bytes).text }.getOrNull().orEmpty()
        }
        return text.replace(Regex("\\s+"), " ").trim()
    }

    private fun readHead(file: File, max: Int): ByteArray = runCatching {
        file.inputStream().use { input ->
            val buf = ByteArray(max)
            var got = 0
            while (got < buf.size) {
                val n = input.read(buf, got, buf.size - got)
                if (n < 0) break
                got += n
            }
            if (got == buf.size) buf else buf.copyOf(got.coerceAtLeast(0))
        }
    }.getOrDefault(ByteArray(0))

    /**
     * 整理产物的 md 形态：来源头＋摘要＋正文（正文过长时保留前 20 万字符，明说截断）。
     *
     * **不写整理时间**：产物内容必须只由原件决定，否则同一批跑两遍会因分钟戳不同
     * 而各写一份（幂等判据 `二遍跳过` 会红）。时间只进台账 JSONL。
     */
    private fun render(item: KnowledgeOrganizeRules.Item, body: String): String {
        val summary = KnowledgeOrganizeRules.summaryOf(body)
        val capped = if (body.length <= MAX_PRODUCT_CHARS) body else body.take(MAX_PRODUCT_CHARS) + "\n\n（正文过长，已截断；完整内容请读原件）"
        return buildString {
            append("# ").append(File(item.sourceRel).name).append("\n\n")
            append("> 来源：`原始/").append(item.sourceRel).append("`（原件未动）\n")
            append("> 分类：").append(item.category.key).append("\n\n")
            if (summary.isNotBlank()) append("## 摘要\n\n").append(summary).append("\n\n")
            append("## 正文\n\n").append(capped).append('\n')
        }
    }

    /** 目标名：默认用它；同内容已在 ⇒ 用它；撞名不同内容 ⇒ sha 后缀；再撞 ⇒ null（不覆盖）。 */
    private fun pickTarget(doneDir: File, rel: String, content: String): File? {
        notUnderRaw(rel) || return null
        val target = File(doneDir, rel)
        if (!target.exists()) return target
        if (sameBytes(target, content)) return target
        val name = target.name.substringBeforeLast('.')
        val alt = File(target.parentFile, "$name.${FaceIndex.sha256Bytes(content.toByteArray(Charsets.UTF_8)).take(8)}.md")
        if (!alt.exists()) return alt
        return if (sameBytes(alt, content)) alt else null
    }

    private fun sameBytes(file: File, content: String): Boolean =
        runCatching { file.readText(Charsets.UTF_8) == content }.getOrDefault(false)

    /** 目标必须落在 `整理/` 且没有 `..`（不碰 `原始/` 的最后一道纯判据）。 */
    private fun notUnderRaw(rel: String): Boolean {
        val n = rel.replace('\\', '/')
        return n.startsWith("整理/") && n.split('/').none { it == ".." || it.isBlank() }
    }

    private val auditLock = Any()

    private fun appendAudit(
        workspace: File,
        runId: String,
        item: KnowledgeOrganizeRules.Item,
        target: File,
        sourceSha: String,
        now: Long,
    ) {
        synchronized(auditLock) {
            runCatching {
                val f = KnowledgeLayout.organizeAuditFile(workspace)
                f.parentFile?.mkdirs()
                val targetRel = target.absolutePath
                    .removePrefix(KnowledgeLayout.knowledgeDir(workspace).absolutePath)
                    .trimStart('/', '\\').replace('\\', '/')
                val targetSha = runCatching { FaceIndex.sha256(target) }.getOrDefault("")
                val line = JSONObject()
                    .put("run", runId)
                    .put("at", now)
                    .put("action", "add")
                    .put("source", item.sourceRel)
                    .put("sourceSha", sourceSha)
                    .put("target", targetRel)
                    .put("targetSha", targetSha)
                    .put("category", item.category.key)
                    .toString()
                f.appendText(line + "\n", Charsets.UTF_8)
            }
        }
    }

    /** 产物正文硬上限（200k 字符；超过明说截断，不静默）。 */
    const val MAX_PRODUCT_CHARS = 200_000

    /** 摘要预读上限（文本件只读开头这么多字节）。 */
    const val EXCERPT_BYTES = 64 * 1024
}
