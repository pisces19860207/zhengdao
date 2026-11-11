// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百五十批 · 相处模式的**左相判档**（派工单 §二：这里加的是"用什么口吻"，
// 左相原来那句"要不要打扰"一个字不改）。
//
// 三档的字面（派工单 §一）：
//   ① 工作（成人对成人）：结论先行、少解释、直接给方案与风险；
//   ② 学习（成人对小孩）：先讲为什么、逐步、给例子、确认理解后再推进；
//   ③ 默认＝工作：判不出就用它，**别猜"你是不是想学习"**。
// 所以枚举只有两值——"第三档"是**默认落点**，不是第三个值。真做成三个值就会多出一格
// "不知道"，而"不知道"渲染不进提示词（渲染出来就成了第四种口吻，派工单 §六 明禁加档）。
//
// 三条纪律：
//   1. **不新增采集**（派工单 §七）：判据只吃两样本来就在手上的东西——本轮 `userText`
//      （`MediatorWiring.onUserTurn` 的第一个参数，接线前就在收）与 [TaskModeBoundary]
//      的任务态结论（253 的词表＋500 的英文层）。本文件不读盘、不读时钟、不读画像、不问模型。
//   2. **不新造判定**：任务态那一轴**原样调** [TaskModeBoundary.classify]，不抄词表过来——
//      抄一份就是两套边界，早晚对不上（`QuietMode` 纪律 6 同一句话）。本文件新增的只有
//      "显式要求"与"用户措辞"两张**口吻**词表：既有词表判的是「是不是在办事」，
//      判不了「要我怎么答」，这一格生产里确实没有（是缺口，不是重复造尺）。
//   3. **显式要求最高优先**（派工单 §二第三条）：用户点名"教我"就盖过自动判定——
//      自动信号是从措辞里猜的，点名不是猜。三级顺序写在 [CompanionJudge.judge]，
//      门禁逐级钉住（`CompanionModeTest`），改顺序即红。
//
// 提示词那一段（判据③）：恒常追加、**两档都必须出现模式行**（用户 2026-11-11 裁定：
// 省略＝没信号，模型分不清"这次没判"和"判成工作"，上一轮学习档的行为会残留到这一轮）。
// 全部是固定字符串：模式行 1 行，学习档再带 2 行教学提示 ⇒ 总 ≤ [CompanionJudge.MAX_SEGMENT_LINES] 行；
// **判档依据／信号值／命中词一律不进这一段**（它们进账与日志），也不许出现变量与时间戳——
// 随轮次变的尾巴等于自毁前缀缓存（258 对 `PROMPT_CONTRACT` 的同一条裁定）。
//
// 派工单 §六 的"判定规则候选"与"灰度后生效"**不在本文件**：候选生成归 568-B，
// 制度条目归 571。本批只产出**误判样本**（[CompanionJudge.correctionOf]），不产出规则。
//
// 零 Android／零 IO／零时钟：判据是纯函数，`reason` 由判据自己产出（不许调用方替它编一句）。
package com.example.zhengdao.baopu

/**
 * 相处模式的档（闭集两值；"默认＝工作"是落点不是第三值，见文件头）。
 *
 * @param wire 稳定机器键（进 208 的 `reason`／日志；改字面量等于让老账读不出来）
 * @param cn 给人看的说法（**不进提示词**——提示词用 [CompanionJudge] 那两句固定文案）
 */
enum class CompanionMode(val wire: String, val cn: String) {
    /** 成人对成人：结论先行、少解释、直接给方案与风险。 */
    WORK("work", "工作"),

    /** 成人对小孩：先讲为什么、逐步、给例子、确认理解后再推进。 */
    STUDY("study", "学习"),
    ;

    companion object {

        /** 判不出时的落点（派工单 §一③）。写成常量而不是在各处 `?: WORK`：改默认档只改这一处。 */
        val DEFAULT: CompanionMode = WORK

        /** 线值 ⇒ 档；认不出 ⇒ null（自造档不渲染、也不静默当某一档——加档要人拍板，派工单 §六）。 */
        fun ofWire(wire: String): CompanionMode? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * 一次判档的产物（派工单 §二：输出 `{mode, reason}`）。
 *
 * @param reason 一句依据（**必填**：没有依据的档位事后无法复盘，也当不了进化样本）
 * @param explicit 这一档是不是**用户点名**要来的。为什么要有这一格：只有点名才算"纠正"
 *   （[CompanionJudge.correctionOf]）——自动信号换档是"这一轮输入不一样"，不是"上一轮判错了"，
 *   把后者也记成误判，样本里全是噪声，进化读出来的规则候选就是错的。
 */
data class CompanionDecision(
    val mode: CompanionMode,
    val reason: String,
    val explicit: Boolean,
) {
    init {
        require(reason.isNotBlank()) { "判档必须带一句依据：没有依据的档位既复盘不了，也当不了进化样本" }
    }
}

/**
 * 判档的算式（纯函数；状态由 [MediatorWiring] 持有，本文件不记"上一轮判了什么"）。
 *
 * 三级优先级＝**顺序**，不许调（判据②的负例就钉在这儿）：
 * 显式要求 → 用户措辞 → 任务类型 → 默认工作档。
 */
object CompanionJudge {

    /** 提示词里那一段的行数上限（判据③：≤3 行）。 */
    const val MAX_SEGMENT_LINES = 3

    /** 工作档的模式行（用户 2026-11-11 给定文案，逐字）。 */
    const val LINE_WORK = "模式：工作（直接给结论）"

    /** 学习档的模式行（用户 2026-11-11 给定文案，逐字）。 */
    const val LINE_STUDY = "模式：学习（先讲步骤与为什么）"

    /**
     * 学习档允许再带的教学提示（派工单 §一②那四件事里，**能在提示词里说清**的两件）。
     *
     * 为什么不把"给例子"也写进来：三行是判据③的上限，而"确认理解后再推进"这一条
     * 管的是**节奏**（它会改变这一轮之后还走不走），比"多举一个例子"更值钱。
     */
    private val STUDY_HINTS = listOf(
        "先说这一步为什么这么做，再给做法。",
        "讲完一处停下来确认我跟上了，再往下走。",
    )

    // ── 第一级：显式要求（最高优先；闭集，不自造）─────────────────────────────
    //
    // 这一级收的是**点名"要我怎么答"**的话，不是"要干什么"的话——所以「帮我改一下」不在这儿
    // （那是任务类型那一级的活）。两张表都是中文原话＋一条英文；要加词先过派工单
    // （`DeviationLedger` 文件头同一句规矩）。

    /** 点名要讲解 ⇒ 学习档。 */
    private val EXPLICIT_STUDY = listOf(
        "教我", "教一下", "教一遍", "教会我", "带我", "讲解一下", "讲一下", "从头讲",
        "一步步教", "step by step",
    )

    /**
     * 点名要简短 ⇒ 工作档。
     *
     * 为什么登记的是**光杆词**（"啰嗦"／"废话"）而不是整句（"别啰嗦"）：派工单 §四⑥ 点名的
     * 那句是「别这么啰嗦」——中间插了"这么"，按整句 `contains` 就漏掉，而漏掉这一句等于
     * "用户纠正了、机器没听见"，误判样本永远收不到（§六 的进化就断在这儿）。
     * 这两个词在中文里只有"嫌话多"一种用法，光杆登记不会误伤。
     */
    private val EXPLICIT_WORK = listOf(
        "别废话", "别啰嗦", "啰嗦", "废话", "不要解释", "不用解释", "别解释", "直接给",
        "只要结果", "简单说", "结论先行", "少解释", "别讲那么多",
    )

    // ── 第二级：用户措辞（派工单 §二第二条点名的"为什么"在这一级）──────────────

    /** 求因／求讲 ⇒ 学习档（没点名，是从措辞里读出来的，所以 [CompanionDecision.explicit] 为假）。 */
    private val PHRASE_STUDY = listOf(
        "为什么", "为什么会", "怎么会", "什么意思", "怎么理解", "原理是什么", "举个例子",
    )

    /**
     * 判一次档（**一轮只许调一次**——由 [MediatorWiring.onUserTurn] 的调用位保证，
     * 判据"同一轮不重复判"是结构性质，不是计数纪律）。
     */
    fun judge(userText: String): CompanionDecision =
        explicitOf(userText) ?: phraseOf(userText) ?: taskTypeOf(userText)

    /** 第一级：显式要求。两张表都命中＝自相矛盾 ⇒ 矛盾不猜，落默认档（派工单 §一③）。 */
    private fun explicitOf(text: String): CompanionDecision? {
        val study = EXPLICIT_STUDY.firstOrNull { text.contains(it) }
        val work = EXPLICIT_WORK.firstOrNull { text.contains(it) }
        return when {
            study != null && work != null -> CompanionDecision(
                mode = CompanionMode.DEFAULT,
                reason = "显式要求自相矛盾（同时点名「$study」与「$work」）：矛盾不猜，落默认工作档",
                explicit = false,
            )
            study != null -> CompanionDecision(CompanionMode.STUDY, "显式要求讲解（点名「$study」）", true)
            work != null -> CompanionDecision(CompanionMode.WORK, "显式要求简短（点名「$work」）", true)
            else -> null
        }
    }

    /** 第二级：用户措辞（求因／求讲）。 */
    private fun phraseOf(text: String): CompanionDecision? {
        val hit = PHRASE_STUDY.firstOrNull { text.contains(it) } ?: return null
        return CompanionDecision(CompanionMode.STUDY, "用户措辞在求因／求讲（「$hit」）", false)
    }

    /**
     * 第三级：任务类型（写代码／改文件 ⇒ 工作档）。
     *
     * 这一级**原样调** [TaskModeBoundary.classify]（纪律 2）。判不出任务态（CHAT）不等于
     * "在讲解"——闲聊里没信号就是没信号，落默认工作档，别猜"你是不是想学习"。
     */
    private fun taskTypeOf(text: String): CompanionDecision =
        if (TaskModeBoundary.classify(text) == TaskMode.TASK) {
            CompanionDecision(CompanionMode.WORK, "任务类型＝办事（TaskModeBoundary 判 TASK）", false)
        } else {
            CompanionDecision(
                mode = CompanionMode.DEFAULT,
                reason = "三级信号都没命中：判不出就用默认工作档，不猜「你是不是想学习」",
                explicit = false,
            )
        }

    /**
     * 这一轮是不是**用户纠正了上一轮的判档**（派工单 §六：误判样本＝用户纠正）。
     *
     * 只认点名（[CompanionDecision.explicit]）：理由见 [CompanionDecision] 那一格的注释。
     *
     * @return `null`＝不是纠正（含第一轮——没有上一轮就没有可纠的判）
     */
    fun correctionOf(prev: CompanionDecision?, now: CompanionDecision): CompanionDecision? =
        if (prev != null && now.explicit && now.mode != prev.mode) now else null

    /** 提示词里那一段的**行**（判据③数的是行数，所以行是唯一真源，[segment] 只是把它们接起来）。 */
    fun lines(mode: CompanionMode): List<String> = when (mode) {
        CompanionMode.WORK -> listOf(LINE_WORK)
        CompanionMode.STUDY -> listOf(LINE_STUDY) + STUDY_HINTS
    }

    /** 提示词里那一段（固定字符串、零变量、零时间戳；两档各一种字节，一共两种）。 */
    fun segment(mode: CompanionMode): String = lines(mode).joinToString("\n")
}
