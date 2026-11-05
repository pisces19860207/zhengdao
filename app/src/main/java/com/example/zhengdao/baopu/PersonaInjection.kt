// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单 2026-11-05 第四百六十四批 追加②（按需注入）。
//
// 现状（工单点名的那一行）：`ContextAssembler` 把 B/C **整段**塞进每轮上下文，
// 人设越长、每轮越贵。这一层把口径换成三层：
//   常驻 ＝ A 内核 ＋ B/C 各自的前三条（[PersonaRules.LOCKED_HEAD_LINES]，与 ② 的锁头**同一个数**）
//   按需 ＝ 其余各节按本轮任务检索，命中的才注入
//   兜底 ＝ 一行索引「另有 N 条人设未注入本轮，可用 read_file 读全文」
// 目标不是"删掉人设"，是**每轮注入近似恒定**（[RESIDENT_BUDGET_TOKENS]）——
// 文件可以越来越长，上下文不该跟着涨。
//
// 四条设计口径（都有测试钉）：
// 1. **默认口径逐字不变**：这一层没超预算 ⇒ [prepare] 原样返回整段文本。
//    所以 [PersonaRules.assemble] 与 `ContextAssembler` 一个字都不用改，
//    前缀缓存那套不变式（段序＝枚举序、追加不改早期字节）也就不会被本批碰到。
// 2. **A 内核不参与**：`prepare(…, A_KERNEL, …)` 恒等原样返回（它本来也没有文件）。
// 3. **常驻不可削**：常驻段自己就超预算 ⇒ 照样整段留着，只在结论里标 [budgetConflict]。
//    不许为了「数字达标」静默砍规矩（464 追加② 把这三条定成**每轮必在**的常驻段；
//    设计文档 §二③ 的口径是降权可恢复、原文不删）⇒ 超了就报出来，让人看见。
// 4. **纯确定性检索**：汉字二元组 ＋ ASCII 词重叠打分，零依赖、零网络、零模型，
//    同一份文件＋同一句话每轮算出的**完全同一串**（前缀稳定的前提）。
//    「按任务类型」的"类型"就是这句话本身带的字；类型表要不要做成配置，留给 UI 批。
//
// 每轮**只算一次**（引擎在轮次入口调，步循环里复用）：这是上下文前缀稳定的硬要求，
// 也和 `diaryQuery` / `nowMillis` 那两个信号同一个口径。
package com.example.zhengdao.baopu

/** 一层人设这一轮注入后的可观测结论（判据「注入量可观测」直接读它）。 */
internal data class PersonaInjected(
    val layer: PersonaLayer,
    /** 文件里的整段原文（一个字没改）。 */
    val original: String,
    /** 真正进上下文的那一段；没降级时与 [original] 逐字相等。 */
    val text: String,
    val totalClauses: Int,
    val residentClauses: Int,
    val injectedClauses: Int,
    val omittedClauses: Int,
    val tokens: Int,
    /** 常驻段本身已超注入预算 ⇒ true（**只报不删**）。 */
    val budgetConflict: Boolean,
) {
    /** 这一轮被降级过（按需注入生效了）。 */
    val degraded: Boolean get() = text != original
}

/**
 * 人设的按需注入（纯函数，零 I/O）。
 *
 * 判据（见 `PersonaInjectionTest`）：常驻段每轮都在／无关任务不注入那一节／
 * 命中的任务能注入对应段／没命中就只多一行索引／整层注入量 ≤ 预算（常驻保底除外）／
 * A 内核与未超预算的层逐字节不变。
 */
internal object PersonaInjection {

    /**
     * 一层人设每轮注入的预算（token）。
     *
     * 工单给的「如 ≤2500」是**注入侧**的尺（464 追加② 一段话里同时给了这两个数），
     * 与单层 8000 那条**文件侧**旧口径不是同一把尺。
     * 两者关系：这里是"注入多少"，[PersonaRules.PERSONA_TOKEN_LIMIT] 是"文件能存多少"，
     * 后者没动。真冲突（常驻段就超 2500）时按口径 3 只报不删。
     */
    const val RESIDENT_BUDGET_TOKENS = 2_500

    /**
     * 一行都不命中时的索引话术（工单原话：「另有 N 条（可读全文）」）。
     *
     * [path] 是引擎给的**可读路径**：`read_file` 的相对路径按工作区根解析，而 B 层在 App
     * 私有目录、C 层在 `<工作区>/抱朴记忆/` 下——只报裸名等于给模型一个打不开的路径，
     * 所以引擎那边传 `PersonaFiles.fileOf(layer).absolutePath`；没传才退回裸名。
     */
    fun indexNote(omitted: Int, layer: PersonaLayer, path: String? = null): String =
        "（另有 $omitted 条人设未注入本轮，可用 read_file 读「${path ?: fileNameOf(layer)}」全文）"

    /** 这一层的文件叫什么（没有路径可给时的退路：至少名字是对的）。 */
    fun fileNameOf(layer: PersonaLayer): String = when (layer) {
        PersonaLayer.B_GLOBAL -> PersonaFiles.FILE_PERSONA
        else -> PersonaFiles.FILE_PERSONA_PROJECT
    }

    /**
     * 算出这一层本轮进上下文的文本。
     *
     * @param query 本轮用户那句话（引擎传 `userText ?: ""`；续跑轮没有新话 ⇒ 只保留常驻）
     * @param pathHint 索引行里给模型的**可读路径**（引擎传 `fileOf(layer).absolutePath`；null ⇒ 退裸名）
     */
    fun prepare(
        text: String?,
        layer: PersonaLayer,
        query: String,
        pathHint: String? = null,
        budget: Int = RESIDENT_BUDGET_TOKENS,
        tokensOf: (String) -> Int = { TokenEstimator.estimate(it) },
    ): PersonaInjected {
        val src = text.orEmpty()
        val total = countClauses(src)
        if (src.isBlank() || layer == PersonaLayer.A_KERNEL || tokensOf(src) <= budget) {
            return PersonaInjected(layer, src, src, total, total, 0, 0, tokensOf(src), false)
        }
        val (resident, rest) = PersonaRules.splitResident(src)
        val used = tokensOf(resident)
        val picked = pick(personaBlocks(rest, resident), query, (budget - used).coerceAtLeast(0), tokensOf)
        val injected = picked.sumOf { it.clauses.size }
        val omitted = total - countClauses(resident) - injected
        val parts = mutableListOf(resident)
        parts += picked.map { render(it) }
        if (omitted > 0) parts += indexNote(omitted, layer, pathHint)
        val out = parts.filter { it.isNotBlank() }.joinToString("\n\n").trim()
        return PersonaInjected(
            layer = layer,
            original = src,
            text = out,
            totalClauses = total,
            residentClauses = countClauses(resident),
            injectedClauses = injected,
            omittedClauses = omitted,
            tokens = tokensOf(out),
            budgetConflict = used > budget,
        )
    }

    /**
     * 体检那一行（判据「人设可以越来越长，每轮注入近似恒定」得能被看见）。
     *
     * 引擎每轮调一次，走 `RunLog`；设置页/以后的体检页同一句话复用，不另写文案。
     */
    fun report(items: List<PersonaInjected>): String {
        val tokens = items.sumOf { it.tokens }
        val clauses = items.sumOf { it.totalClauses }
        val resident = items.sumOf { it.residentClauses }
        val injected = items.sumOf { it.injectedClauses }
        val omitted = items.sumOf { it.omittedClauses }
        val tail = if (omitted > 0) "（常驻 $resident＋按需 $injected，未注入 $omitted）" else "（全部常驻）"
        val conflict = if (items.any { it.budgetConflict }) "｜常驻超预算，未削减" else ""
        return "本轮人设注入 $tokens token / 共 $clauses 条$tail$conflict"
    }

    /** 命中打分：汉字二元组 ×3 ＋ 单字 ×1（单字只算带信息量的，虚词不参与）。 */
    internal fun score(query: Set<String>, block: Set<String>): Int =
        query.count { it in block && it.length > ONE } * BI_WEIGHT + query.count { it in block && it.length == ONE }

    /**
     * 一段文本的检索键（CJK 二元组 ＋ 单字 ＋ ASCII 词）。
     *
     * 抽掉虚词单字（[STOP]）：否则「我的」「一个」这种字会把每一节都算成命中，
     * 按需注入就退化成"全注入"——那正是要治的病。
     */
    internal fun keysOf(text: String): Set<String> {
        val lower = text.lowercase()
        val out = mutableSetOf<String>()
        ASCII_WORD.findAll(lower).forEach { out += it.value }
        var run = StringBuilder()
        lower.forEach { c ->
            if (c in CJK) {
                run.append(c)
            } else {
                out += keysOfRun(run)
                run = StringBuilder()
            }
        }
        out += keysOfRun(run)
        return out
    }

    /** 一段连续汉字 → 二元组 ＋ 带信息量的单字。 */
    private fun keysOfRun(run: StringBuilder): List<String> {
        val bi = (0 until run.length - 1).map { run.substring(it, it + 2) }
        val uni = run.map { it.toString() }.filter { it !in STOP }
        return bi + uni
    }

    /** 按原文顺序切好的节（与 ② 共用 [PersonaRules.blocksOf]，行号还原成绝对行号）。 */
    private fun personaBlocks(rest: String, resident: String): List<PersonaClauseBlock> =
        PersonaRules.blocksOf(rest, if (resident.isEmpty()) 0 else resident.split("\n").size)
            .filter { it.clauses.isNotEmpty() }

    /** 挑节：先按命中分排序（同分按原文顺序），装不进剩余预算的就停在这里。 */
    private fun pick(
        blocks: List<PersonaClauseBlock>,
        query: String,
        leftTokens: Int,
        tokensOf: (String) -> Int,
    ): List<PersonaClauseBlock> {
        val q = keysOf(query)
        if (q.isEmpty() || leftTokens <= 0) return emptyList()
        val ranked = blocks.withIndex()
            .map { (i, b) -> Triple(score(q, keysOf(render(b))), i, b) }
            .filter { it.first > 0 }
            .sortedWith(compareByDescending<Triple<Int, Int, PersonaClauseBlock>> { it.first }
                .thenBy { it.second })
            .map { it.third }
        val taken = mutableListOf<PersonaClauseBlock>()
        var used = 0
        ranked.forEach { b ->
            val cost = tokensOf(render(b))
            if (used + cost <= leftTokens) {
                taken += b
                used += cost
            }
        }
        // 落回原文顺序：注入顺序跟着文件走，前缀才稳（同分同序，确定性）
        return taken.sortedBy { blocks.indexOf(it) }
    }

    /** 一节拼回文本（小标题在上，条款在下）。 */
    private fun render(block: PersonaClauseBlock): String =
        ((if (block.heading.isEmpty()) emptyList() else listOf(block.heading)) + block.clauses.map { it.second })
            .joinToString("\n")

    private fun countClauses(text: String): Int = text.split("\n").count { PersonaRules.isClause(it) }

    private const val BI_WEIGHT = 3
    private const val ONE = 1

    private val ASCII_WORD = Regex("[a-z0-9]{3,}")

    /** 只收常用汉字段（够用且不必带词表）。 */
    private val CJK = '一'..'鿿'

    /** 单字里要丢掉的虚词（整段命中它们等于没命中）。 */
    private val STOP = setOf("的", "了", "是", "在", "和", "就", "都", "与", "把", "我", "你", "他", "它", "这", "那", "个")
}
