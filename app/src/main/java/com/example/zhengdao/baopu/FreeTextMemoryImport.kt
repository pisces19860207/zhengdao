// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单《第四百五十一批 · 记忆落地两件》②（自由文本两份 .md 收编）与
// 用户 2026-11-05 口径裁定（asOf 是「哪天查的」，缺就待补，不许编造）。
package com.example.zhengdao.baopu

import java.io.File

/**
 * 两份自由文本记忆（App 私有 `全局记忆.md`／工作区 `记忆/项目记忆.md`）的**收编解析层**。
 *
 * 438 把结构化记忆做成了「一条 .md ＋ front-matter」的条目体系，但这两份人手写的
 * 自由文本一直没进体系：没有 kind、没有 source、没有 asOf。本批把**看得懂的行**
 * 转成条目，看不懂的**原样保留并计数**，一个字节都不丢。
 *
 * 三条纪律：
 * 1. **不编造**：source／asOf 缺就写「待补」／`asOf = 0`，绝不拿当前时刻、文件名或
 *    正文里猜出来的日期去凑；条目状态一律 PENDING（复核）——机器收编的不自动确认，
 *    因此不会绕过 `MemoryStore.write` 的「事实必带 asOf」闸，也不进上下文参与判定。
 * 2. **不丢行**：解析不了的行（含标题、段落、乱码、超长行）原样进 [ImportResult.unresolved]
 *    并写进回执；源文件只读，不删、不改、不截断。
 * 3. **幂等**：纯函数，不读时钟、不读随机数；id 由「作用域＋行内容哈希」确定性生成
 *    （同一内容出现多次才追加序号），同一份输入连跑两次得到逐字相同的结果与文件字节。
 *
 * 边界（451 派工单 §三）：只做存储与解析，**不做界面**；「规矩」标签／待复核徽章／
 * 弹卡 asOf 输入位归 UI 批 U6/U7；不迁移旧账本。
 */
internal object FreeTextMemoryImport {

    /** 缺字段的人读标记（写进 front-matter，不编造值）。 */
    const val PENDING = "待补"

    /**
     * 回执文件名。**故意用 `.txt`**：条目目录里 `.md` 会被 `MemoryStore.scan()` 当条目解析，
     * 回执不是条目，用 `.md` 会每轮报「记忆读侧：1 个文件解析失败」。
     */
    const val RECEIPT_NAME = "_451_收编回执.txt"

    /** 可识别的行首键 → 记忆种类。键表之外一律不猜，原样留档。 */
    private val KEY_KINDS: Map<String, MemoryKind> = mapOf(
        "偏好" to MemoryKind.PREFERENCE,
        "画像" to MemoryKind.PROFILE,
        "规矩" to MemoryKind.RULE,
        "禁止" to MemoryKind.RULE,
        "要求" to MemoryKind.RULE,
        "口径" to MemoryKind.LESSON,
        "事实" to MemoryKind.FACT,
        "术语" to MemoryKind.TERM,
    )

    /** 列表标记（`- `／`* `／`1. `／`1) `）。 */
    private val BULLET = Regex("^\\s*(?:[-*]|\\d+[.)])\\s+(.+)$")

    /** 「键：值」或「键: 值」。 */
    private val KEYED = Regex("^([^：:]{1,6})[：:](.*)$")

    /** 一次收编的完整读数（data class ⇒ 两次结果可直接比较，判据③的落点）。 */
    data class ImportResult(
        /** 成功转成结构化条目（状态 PENDING，不自动确认）。 */
        val entries: List<MemoryEntry>,
        /** 文件名 → front-matter 文本（结构化存储的内容，逐字可比较）。 */
        val rendered: Map<String, String>,
        /** 无法解析的行，**原样**（不 trim、不改写、不排序）。 */
        val unresolved: List<String>,
        /** 无法解析行数（与 [unresolved] 分开给，便于对账，防止「清单有、计数没有」）。 */
        val unresolvedCount: Int,
        /** 空白行数（不进条目也不进未解析清单，但要进总行数对账）。 */
        val blankCount: Int,
        /** 两份文件的总行数（判据「不许丢」：= 条目数 ＋ 未解析数 ＋ 空白数）。 */
        val sourceLineCount: Int,
        /** 条目 id → 缺的字段名（固定为 `source`／`asOf`；结构化地告诉调用方别补）。 */
        val missingFields: Map<String, List<String>>,
        /** 人读回执（含两份文件的读数、计数、以及逐行原样的未解析内容）。 */
        val receipt: String,
    )

    /** 一份文件的中间读数。 */
    private data class FileResult(
        val label: String,
        val entries: List<MemoryEntry>,
        val rendered: Map<String, String>,
        val unresolved: List<String>,
        val blank: Int,
        val lines: Int,
    )

    /**
     * 收编两份自由文本。**纯函数**：不碰盘、不读时钟 ⇒ 连跑两次结果逐字一致。
     *
     * @param globalText `全局记忆.md` 全文（没有 ⇒ null／空串，按 0 行算）
     * @param projectText `项目记忆.md` 全文（同上）
     */
    fun parse(globalText: String?, projectText: String?): ImportResult {
        val global = parseFile(globalText, MemoryScope.GLOBAL, "全局记忆.md")
        val project = parseFile(projectText, MemoryScope.PROJECT, "项目记忆.md")
        val files = listOf(global, project)
        val entries = files.flatMap { it.entries }
        val rendered = LinkedHashMap<String, String>()
        val missing = LinkedHashMap<String, List<String>>()
        for (f in files) {
            rendered.putAll(f.rendered)
            for (e in f.entries) missing[e.id] = listOf("source", "asOf")
        }
        val unresolved = files.flatMap { it.unresolved }
        val blank = files.sumOf { it.blank }
        val lines = files.sumOf { it.lines }
        return ImportResult(
            entries = entries,
            rendered = rendered,
            unresolved = unresolved,
            unresolvedCount = unresolved.size,
            blankCount = blank,
            sourceLineCount = lines,
            missingFields = missing,
            receipt = renderReceipt(files, unresolved, entries.size, lines, blank),
        )
    }

    /** 单份文件逐行处理：可解析 ⇒ 条目；空白 ⇒ 只计数；其余 ⇒ 原样留档。 */
    private fun parseFile(text: String?, scope: MemoryScope, label: String): FileResult {
        if (text.isNullOrEmpty()) return FileResult(label, emptyList(), emptyMap(), emptyList(), 0, 0)
        val entries = mutableListOf<MemoryEntry>()
        val rendered = LinkedHashMap<String, String>()
        val unresolved = mutableListOf<String>()
        val seen = mutableMapOf<String, Int>()
        var blank = 0
        // lines() 按 CRLF/LF/CR 切并去掉末尾空段（更接近人说的「几行」，不把收尾换行数成一行）
        val rows = text.lines()
        for (line in rows) {
            if (line.isBlank()) { blank++; continue }
            val e = entryOf(line, scope, seen)
            if (e == null) unresolved += line else {
                entries += e
                rendered[e.id + ".md"] = EntryCodec.render(e)
            }
        }
        return FileResult(label, entries, rendered, unresolved, blank, rows.size)
    }

    /** 一行 → 条目；识别不了（含超长）就返回 null，调用方原样留档，不猜也不截断。 */
    private fun entryOf(line: String, scope: MemoryScope, seen: MutableMap<String, Int>): MemoryEntry? {
        val bare = BULLET.matchEntire(line)?.groupValues?.get(1) ?: line
        val m = KEYED.matchEntire(bare.trim()) ?: return null
        val kind = KEY_KINDS[m.groupValues[1].trim()] ?: return null
        val content = m.groupValues[2].trim()
        if (content.isEmpty()) return null
        // 原文超过正文上限 ⇒ 整行进「未解析」，原样留档；静默 take() 就是从结构化副本里丢字。
        if (bare.trim().length > MemoryRules.BODY_LIMIT) return null
        val title = bare.trim().take(MemoryRules.TITLE_LIMIT)
        return MemoryEntry(
            id = stableId(scope, line, seen),
            scope = scope,
            kind = kind,
            title = title,
            body = bare.trim(),
            tags = emptyList(),
            // 收编出处本身不是「外部事实的 source」：这里如实写「待补」，不拿文件名冒充出处。
            sourceSession = PENDING,
            sourceFile = PENDING,
            // 机器收编不自动确认：PENDING 不进上下文、不绕过事实 asOf 收录闸。
            status = MemoryStatus.PENDING,
            createdAt = 0L,
            updatedAt = 0L,
            // 451 判据④：不编造 asOf，缺就 0（入库时绑 NULL／标待补）。
            asOf = 0L,
        )
    }

    /**
     * 确定性 id：作用域 ＋ 行内容哈希。
     * 不掺行号——源文件上方插一行不会让后面所有条目换 id 变孤儿；
     * 同内容出现多次才追加 `-2`／`-3`（计数也是确定性的，不读时钟）。
     */
    private fun stableId(scope: MemoryScope, line: String, seen: MutableMap<String, Int>): String {
        val base = "451-" + scope.name.lowercase() + "-" + stableHash(line)
        val n = (seen[base] ?: 0) + 1
        seen[base] = n
        return if (n == 1) base else "$base-$n"
    }

    /** 行内容的稳定短哈希（JVM `String.hashCode` 是规范定义的确定性值，跨进程不抖）。 */
    private fun stableHash(line: String): String = Integer.toHexString(line.hashCode())

    /** 回执：两份读数 ＋ 缺字段声明 ＋ 逐行原样的未解析内容。 */
    private fun renderReceipt(
        files: List<FileResult>,
        unresolved: List<String>,
        entryCount: Int,
        lines: Int,
        blank: Int,
    ): String = buildString {
        append("# 451 记忆收编回执\n\n")
        for (f in files) {
            append("- ").append(f.label).append("：").append(f.lines).append(" 行，收编 ")
                .append(f.entries.size).append(" 条，未解析 ").append(f.unresolved.size).append(" 行\n")
        }
        append("- 合计：").append(lines).append(" 行 ＝ ").append(entryCount).append(" 条结构化 ＋ ")
            .append(unresolved.size).append(" 行未解析 ＋ ").append(blank).append(" 行空白\n")
        append("- 缺字段：source／asOf 一律标「").append(PENDING).append("」，未编造任何时刻或出处\n")
        append("- 源文件：只读，未删除、未改写任何一行\n\n")
        append("## 无法解析的行（原样保留，共 ").append(unresolved.size).append(" 行）\n\n")
        if (unresolved.isEmpty()) append("（无）\n")
        else unresolved.forEachIndexed { i, s -> append(i + 1).append(". ").append(s).append('\n') }
    }
}

/**
 * 收编结果的**落盘薄壳**（[FreeTextMemoryImport] 是纯解析，IO 全在这里）。
 *
 * 目录由调用方给定（本批不接线、不指定生产落点）；条目文件名与 [EntryCodec] 同形，
 * 回执单独一份（`.txt`，不会被 `MemoryStore.scan()` 当条目）。写的是固定内容
 * （无时钟、无随机）⇒ 连跑两次文件字节逐字一致。源文件只读：这里一个字节都不碰。
 */
internal object FreeTextMemoryStore {

    /**
     * 落盘条目与回执。
     * @return 写成的文件数（含回执）；失败返回 -1（不静默当成功）。
     */
    fun importInto(dir: File, result: FreeTextMemoryImport.ImportResult): Int = runCatching {
        dir.mkdirs()
        for ((name, text) in result.rendered) File(dir, name).writeText(text, Charsets.UTF_8)
        File(dir, FreeTextMemoryImport.RECEIPT_NAME).writeText(result.receipt, Charsets.UTF_8)
        result.rendered.size + 1
    }.getOrDefault(-1)

    /**
     * 从两份真实文件读入并收编（**本批唯一的生产可用入口**；接线由调用方决定，
     * 本批不替 U6/U7 做主）。文件不存在按「没有」算，不报错、不编造。
     */
    fun importFromFiles(global: File?, project: File?, outDir: File): FreeTextMemoryImport.ImportResult {
        val g = global?.takeIf { it.isFile }?.let { runCatching { it.readText(Charsets.UTF_8) }.getOrNull() }
        val p = project?.takeIf { it.isFile }?.let { runCatching { it.readText(Charsets.UTF_8) }.getOrNull() }
        val result = FreeTextMemoryImport.parse(g, p)
        importInto(outDir, result)
        return result
    }
}
