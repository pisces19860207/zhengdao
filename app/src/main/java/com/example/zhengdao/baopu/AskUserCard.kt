// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 形状依据：用户 2026-10-16 逐字「我想让抱朴像你一样，有个推荐和其他选项和一个空白行那种」
// （台账 §E.10 ②）；DSH 提问卡现状＝一问一屏：推荐项排第一并标注、其余 2–4 条、
// **永远留一行空白输入**。本文件只放数据与校验（纯函数，可 JVM 直测），渲染在
// `ui/baopu/BaopuAskUserCard.kt`——UI 只是把它画出来，不许自己发明形状。
package com.example.zhengdao.baopu

/**
 * `ask_user` 提问卡（刀 2 C 段）。
 *
 * 四条硬形状（用户逐字定的口径，[AskUserCardRules.validate] 逐条钉死）：
 * 1. **一个问题一行**（[question] 单行，渲染时不折行成段落）；
 * 2. **选项 2–4 个**，**推荐项必须排第一并标「推荐」**（[AskUserOption.recommended]）；
 * 3. **永远留一行空白输入**（[freeTextPrompt] 必填非空——卡片没有它就不合法，
 *    从数据结构上杜绝「只有选项、没有自由回答」的房型）；
 * 4. **选项文案是「下一步做什么」**，不是错误码/术语（这条靠构造方自律＋评审，校验只查非空）。
 *
 * 卡片是**纯数据**：不含 Context、不含 Compose、不含 IO ⇒ 单测直接构造、直接断言。
 */
data class AskUserCard(
    val question: String,
    val options: List<AskUserOption>,
    val freeTextPrompt: String,
)

/** 一个选项。[label] 写「下一步做什么」（例：「搬进 App 私有（推荐）」）。 */
data class AskUserOption(
    val id: String,
    val label: String,
    val recommended: Boolean = false,
)

/** 提问卡校验的产物：OK 或**全部**问题（一次列全，不让调用方猜第二条）。 */
sealed interface AskUserCheck {
    object Ok : AskUserCheck
    data class Invalid(val problems: List<String>) : AskUserCheck
}

/**
 * 提问卡校验（**纯函数**；负例演练的落点之一——把空白行校验删掉，
 * `AskUserCardTest` 里「空白行必须永远在」那条必须红）。
 */
object AskUserCardRules {

    const val MIN_OPTIONS = 2
    const val MAX_OPTIONS = 4

    /** 空白输入行的默认提示语（调用方可以换文案，但不许留空）。 */
    const val FREE_TEXT_PROMPT = "或者直接输入你的想法"

    fun validate(card: AskUserCard): AskUserCheck {
        val problems = mutableListOf<String>()

        if (card.question.isBlank()) problems += "问题不能为空（提问卡必须有一行问题）"
        if (card.freeTextPrompt.isBlank()) {
            // 这一条就是「永远留一行空白输入」的机器判据：卡片结构里没有它 ⇒ 不合法。
            problems += "必须留一行空白输入（freeTextPrompt 不能为空）"
        }

        val n = card.options.size
        if (n < MIN_OPTIONS || n > MAX_OPTIONS) {
            problems += "选项要 $MIN_OPTIONS–$MAX_OPTIONS 个（当前 $n 个）"
        }

        val blankLabels = card.options.withIndex()
            .filter { it.value.label.isBlank() }
            .map { "第 ${it.index + 1} 个选项文案为空" }
        problems += blankLabels

        val blankIds = card.options.withIndex()
            .filter { it.value.id.isBlank() }
            .map { "第 ${it.index + 1} 个选项 id 为空" }
        problems += blankIds

        val dupIds = card.options.map { it.id }
            .groupBy { it }
            .filter { it.key.isNotBlank() && it.value.size > 1 }
            .keys
            .map { "选项 id 重复：$it" }
        problems += dupIds

        val recommendedCount = card.options.count { it.recommended }
        if (recommendedCount != 1) {
            problems += "必须恰好一个推荐项（当前 $recommendedCount 个）"
        }
        if (card.options.isNotEmpty() && !card.options.first().recommended) {
            problems += "推荐项必须排第一（当前第一个是「${card.options.first().label}」）"
        }

        return if (problems.isEmpty()) AskUserCheck.Ok else AskUserCheck.Invalid(problems)
    }

    /** 便捷判定（调用方多数只问「合法吗」）。 */
    fun isValid(card: AskUserCard): Boolean = validate(card) is AskUserCheck.Ok
}
