// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-10-24-第一百八十二批 §3（整理：分类/去重/摘要/建议目录；
// 移动改名删除原件必须确认＋备份；整理产物落 整理/；审计可回滚）。
// 本层是**纯判据**：喂候选进，出「一张变更清单」，零 IO 零 Android。
package com.example.zhengdao.baopu.knowledge

import com.example.zhengdao.util.TimeFormats

/**
 * 抱朴整理的**纯规则层**（第一百八十二批）。
 *
 * 安全口径（这条比功能更重要）：本层只产出「**副本**写进 整理/<类>/<名字>.md」的计划，
 * **从不计划移动/改名/删除原件**。原件一个字节都不动，所以不需要「先备份再动原件」；
 * 需要动原件的动作留给将来单独立单（那时才走备份＋确认）。
 */
internal object KnowledgeOrganizeRules {

    enum class Category(val key: String) {
        DOC("doc"),
        CODE("code"),
        SHEET("sheet"),
        WEB("web"),
        OTHER("other"),
    }

    enum class Action { ADD, SKIP_DUPLICATE, SKIP_UNREADABLE }

    data class Candidate(
        val relPath: String,
        val size: Long,
        val mtime: Long,
        val sha256: String,
        val category: Category,
        /** 能不能读出正文（txt/md/html/docx）；读不出 ⇒ 不产出，只记账。 */
        val readable: Boolean,
        /** 正文开头（本层只拿它生成摘要；不落盘）。 */
        val excerpt: String,
        /** 读不出的可读原因。 */
        val reason: String = "",
    )

    data class Item(
        val action: Action,
        val sourceRel: String,
        val targetRel: String,
        val category: Category,
        val sha256: String,
        val summary: String,
        val reason: String = "",
    )

    data class Plan(
        val runId: String,
        val items: List<Item>,
    ) {
        val adds: List<Item> get() = items.filter { it.action == Action.ADD }
        val duplicates: Int get() = items.count { it.action == Action.SKIP_DUPLICATE }
        val unreadable: Int get() = items.count { it.action == Action.SKIP_UNREADABLE }
        val isEmpty: Boolean get() = adds.isEmpty()
        val summaryLine: String
            get() = "整理清单：写入 ${adds.size} 项，去重跳过 $duplicates 项，读不出 $unreadable 项"
    }

    /** 扩展名分类（只按扩展名，不猜内容）。 */
    fun categoryOf(name: String): Category {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "txt", "md", "markdown", "rtf", "doc", "docx", "odt", "pdf" -> Category.DOC
            "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "c", "h", "cc", "cpp", "hpp",
            "rs", "go", "rb", "sh", "bash", "zsh", "sql", "toml", "ini", "cfg", "conf",
            "properties", "gradle" -> Category.CODE
            "csv", "tsv", "json", "jsonl", "yaml", "yml", "xml", "svg" -> Category.SHEET
            "html", "htm" -> Category.WEB
            else -> Category.OTHER
        }
    }

    /** 能不能读正文（与 [com.example.zhengdao.baopu.kb.KbIndexer] 的认领口径一致）。 */
    fun readableName(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext == "docx") return true
        return ext in com.example.zhengdao.baopu.library.LibraryPaths.TEXT_EXTS
    }

    /**
     * 生成计划（**一张清单**，不是每文件一张）：
     * ① 同 sha ⇒ 后来者 SKIP_DUPLICATE（原件都留着，只在产物侧去重）；
     * ② 读不出 ⇒ SKIP_UNREADABLE（带原因，不静默）；
     * ③ 其余 ⇒ ADD，目标 `整理/<类>/<名字>.md`；同目标名撞车 ⇒ 加 sha 前 8 位。
     */
    fun plan(candidates: List<Candidate>, now: Long): Plan {
        val runId = TimeFormats.fileStamp(now)
        val seenSha = HashMap<String, String>()
        val takenTargets = HashSet<String>()
        val items = ArrayList<Item>(candidates.size)
        for (c in candidates.sortedBy { it.relPath }) {
            if (c.sha256.isNotBlank() && seenSha.containsKey(c.sha256)) {
                items += Item(
                    action = Action.SKIP_DUPLICATE,
                    sourceRel = c.relPath,
                    targetRel = "",
                    category = c.category,
                    sha256 = c.sha256,
                    summary = "",
                    reason = "与 ${seenSha[c.sha256]} 同内容，产物侧去重（原件都留着）",
                )
                continue
            }
            seenSha[c.sha256] = c.relPath
            if (!c.readable) {
                items += Item(
                    action = Action.SKIP_UNREADABLE,
                    sourceRel = c.relPath,
                    targetRel = "",
                    category = c.category,
                    sha256 = c.sha256,
                    summary = "",
                    reason = c.reason.ifBlank { "格式不认领，读不出正文" },
                )
                continue
            }
            val target = uniqueTarget(c, takenTargets)
            items += Item(
                action = Action.ADD,
                sourceRel = c.relPath,
                targetRel = target,
                category = c.category,
                sha256 = c.sha256,
                summary = summaryOf(c.excerpt),
            )
        }
        return Plan(runId, items)
    }

    /** 目标相对路径（`整理/<类>/<名字>.md`）；撞名加 sha 前 8 位。 */
    private fun uniqueTarget(c: Candidate, taken: MutableSet<String>): String {
        val folder = c.category.key
        val base = sanitize(c.relPath.substringAfterLast('/').substringBeforeLast('.'))
        var name = "$base.md"
        var rel = "整理/$folder/$name"
        if (rel in taken) {
            val suffix = c.sha256.take(8).ifBlank { "dup" }
            name = "$base.$suffix.md"
            rel = "整理/$folder/$name"
        }
        taken += rel
        return rel
    }

    /** 文件名清洗（去掉路径分隔符与 Windows 非法字符；空 ⇒ 未命名）。 */
    fun sanitize(base: String): String {
        val cleaned = base
            .replace('/', '_').replace('\\', '_')
            .replace(Regex("[\\p{Cntrl}:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.')
        return cleaned.ifBlank { "未命名" }
    }

    /** 摘要 = 正文开头收敛空白后取前 [SUMMARY_CHARS] 字（本地生成，不联网）。 */
    fun summaryOf(text: String, limit: Int = SUMMARY_CHARS): String {
        val flat = text.replace('\r', '\n').replace(Regex("\\s+"), " ").trim()
        if (flat.isEmpty()) return ""
        return if (flat.length <= limit) flat else flat.take(limit).trimEnd() + "…"
    }

    const val SUMMARY_CHARS = 160

    /** 计划里的写入目标是否全部落在 `整理/` 且不在 `原始/`（写盘前最后一道纯判据）。 */
    fun targetsSafe(plan: Plan): Boolean = plan.adds.all {
        it.targetRel.startsWith("整理/") &&
            !it.targetRel.replace('\\', '/').split('/').any { p -> p == ".." || p.isBlank() }
    }
}
