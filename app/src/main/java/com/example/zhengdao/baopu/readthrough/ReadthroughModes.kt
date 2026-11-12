// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：第六百一十批（通读框架 · 模式与预算）。两条硬规则：
// 1. 略读只抽相关章，但**必须列出漏掉的章**——漏章不是「没选的」而是「这次没读的」，
//    隐瞒就是假略读（判据③的机检点）；
// 2. 预算在开跑**之前**算：{片数, 调用数, token} 任何一项超限 ⇒ 不启动（判据⑤）。
//    本文件全是纯函数——预算不许「跑了才知道」。
package com.example.zhengdao.baopu.readthrough

/** 通读模式。 */
internal enum class ReadMode(val label: String) {
    /** 精读：全部章都抽（预算先报，再开跑）。 */
    DEEP("精读"),

    /** 略读：只抽相关章；漏掉的章必须列出（[SkimPlanner.missed]）。 */
    SKIM("略读"),
}

/** 参与选章判断的一章（标题＋首句——book_outline 同源的两样，不引正文）。 */
internal data class ChapterBrief(val index: Int, val title: String, val preview: String)

internal data class SkimSelection(
    val selected: List<Int>,
    /** 这次没读的章（全集−选中；略读报告里必须原样列出，不许只给个数）。 */
    val missed: List<Int>,
)

/**
 * 略读选章（**纯函数、确定性**：同查询同书 ⇒ 同选择，断点续跑才有意义）。
 *
 * 判定口径：查询按空白拆成关键词，章的标题或首句**含**任一关键词即选中。
 * 故意不做语义相关（那是模型的事，选章要在模型外可复算）——标题/首句没提到
 * 但正文相关的章会漏，漏单里如实列出，人不被蒙在鼓里。
 */
internal object SkimPlanner {

    fun select(query: String, briefs: List<ChapterBrief>): SkimSelection {
        val keywords = query.split(whitespace).filter { it.isNotBlank() }
        if (keywords.isEmpty()) return SkimSelection(emptyList(), briefs.map { it.index })
        val selected = briefs.filter { b -> keywords.any { b.title.contains(it) || b.preview.contains(it) } }
        return SkimSelection(
            selected = selected.map { it.index },
            missed = briefs.map { it.index } - selected.map { it.index }.toSet(),
        )
    }

    /** 漏章压成区间串（「3–50、52、55–99」——列全但不刷屏）。 */
    fun renderMissed(missed: List<Int>): String {
        if (missed.isEmpty()) return "（无）"
        val parts = mutableListOf<String>()
        var runStart = missed.first()
        var prev = missed.first()
        missed.drop(1).forEach { n ->
            if (n == prev + 1) { prev = n; return@forEach }
            parts += range(runStart, prev)
            runStart = n
            prev = n
        }
        parts += range(runStart, prev)
        return parts.joinToString("、")
    }

    private fun range(a: Int, b: Int): String = if (a == b) "$a" else "$a–$b"

    private val whitespace = Regex("\\s+")
}

/** 预算估数（开跑前报给用户的三个数都在这里）。 */
internal data class BudgetEstimate(
    val sliceCount: Int,
    val extractionCalls: Int,
    val mergeCalls: Int,
    val totalCalls: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val totalTokens: Long,
)

/** 预算上限（工具参数可调；默认值给得保守——手机上跑，宁可分多次）。 */
internal data class BudgetLimits(val maxCalls: Int, val maxTokens: Long)

internal sealed interface BudgetVerdict {
    val estimate: BudgetEstimate

    data class Allow(override val estimate: BudgetEstimate) : BudgetVerdict

    data class Deny(val reasons: List<String>, override val estimate: BudgetEstimate) : BudgetVerdict
}

/**
 * 预算预检（**纯函数**）。
 *
 * token 口径（宁多估不少估，超额拒跑是安全侧错）：
 * - 输入 ≈ 1 字 1 token（中文保守值）＋ 每次调用 300 token 固定壳（指令/口径说明）；
 * - 输出 ≈ 每次调用 1500 token 上限（提示词里同步这么限）。
 */
internal object BudgetCheck {

    const val TOKENS_PER_CHAR = 1.0
    const val PROMPT_OVERHEAD_TOKENS = 300L
    const val OUTPUT_CAP_TOKENS = 1_500L

    fun estimate(sliceCount: Int, sliceCharsTotal: Int, mergeCalls: Int): BudgetEstimate {
        val totalCalls = sliceCount + mergeCalls
        val input = (sliceCharsTotal * TOKENS_PER_CHAR).toLong() + PROMPT_OVERHEAD_TOKENS * totalCalls
        val output = OUTPUT_CAP_TOKENS * totalCalls
        return BudgetEstimate(
            sliceCount = sliceCount,
            extractionCalls = sliceCount,
            mergeCalls = mergeCalls,
            totalCalls = totalCalls,
            inputTokens = input,
            outputTokens = output,
            totalTokens = input + output,
        )
    }

    /** @return Allow / Deny（Deny 带逐条原因：哪项超了多少——不是一句「超了」打发）。 */
    fun check(estimate: BudgetEstimate, limits: BudgetLimits): BudgetVerdict {
        val reasons = mutableListOf<String>()
        if (estimate.totalCalls > limits.maxCalls) {
            reasons += "调用数 ${estimate.totalCalls} 超上限 ${limits.maxCalls}（多 ${estimate.totalCalls - limits.maxCalls} 次）"
        }
        if (estimate.totalTokens > limits.maxTokens) {
            reasons += "token ${estimate.totalTokens} 超上限 ${limits.maxTokens}（多 ${estimate.totalTokens - limits.maxTokens}）"
        }
        return if (reasons.isEmpty()) BudgetVerdict.Allow(estimate)
        else BudgetVerdict.Deny(reasons, estimate)
    }
}
