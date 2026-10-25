// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百零三批（批量问卡）的判据层，口径照 `baopu/AskUserCard.kt` 的 AskUserCardRules：
// 数据与校验住纯函数（JVM 直测），渲染层只画不准自己发明形状。
//
// 三件事：
// 1. [split] 一卡上限 [AskUserTool.MAX_PER_CARD] 项，超了拆卡；末张只剩 1 项时向前借
//    一个（6 ⇒ 4+2——派工单判据原话「6 项 ⇒ 拆 4+2，第一张提示还有 2 项」，
//    不许摆一张只有一个孤零零问题的卡）；
// 2. [validate] 卡内就地校验：必答未填 / NUMBER 非数字 / 答案不在选项里 /
//    BOOL 不是 true-false，一条条点名；提交按钮置灰由渲染层看它的结果；
// 3. [isGroupCard] 这张 pendingChoice 走不走「多问卡」——**只有 QUESTION 且带
//    questions 的请求走**；御批类（组授权/人设提案）就算被人塞进 questions 也
//    仍是显式批准的老路（红线：确认制语义不许动，负例 5 的第一道闸）。
package com.example.zhengdao.baopu.agent

/**
 * 提问组校验的产物：OK 或**全部**问题（一次列全，不让调用方猜第二条——同
 * `AskUserCheck` 的口径）。
 */
sealed interface AskGroupCheck {
    object Ok : AskGroupCheck

    data class Invalid(val problems: List<String>) : AskGroupCheck
}

internal object AskGroupRules {

    /** MULTI 的卡上草稿值（多个选中 id 用逗号连接；与工具 schema 的描述一致）。 */
    fun joinMulti(picked: Collection<String>): String = picked.filter { it.isNotBlank() }.joinToString(",")

    /** MULTI 草稿值拆回选中集合（空串＝一个没选）。 */
    fun splitMulti(value: String): List<String> =
        value.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * 拆卡（**纯函数**）：≤[AskUserTool.MAX_PER_CARD] 项原样一张；多了按 5 一切，
     * 末张恰好剩 1 项时向前一张借 1（6 ⇒ 4+2、11 ⇒ 5+4+2）。
     */
    fun split(questions: List<AskQuestion>): List<List<AskQuestion>> {
        if (questions.size <= AskUserTool.MAX_PER_CARD) return listOf(questions)
        val cards = mutableListOf<MutableList<AskQuestion>>()
        var rest = questions.toMutableList()
        while (rest.size > AskUserTool.MAX_PER_CARD) {
            cards += MutableList(AskUserTool.MAX_PER_CARD) { rest.removeAt(0) }
        }
        if (rest.size == 1 && cards.isNotEmpty()) {
            val prev = cards.last()
            if (prev.size >= 2) rest.add(0, prev.removeAt(prev.lastIndex))
        }
        cards += rest
        return cards
    }

    /** 第一张卡的续问提示（派工单原话）；单卡不提示。 */
    fun moreNote(total: Int, firstCardSize: Int): String =
        if (total > firstCardSize) "另有 ${total - firstCardSize} 项，答完这张接着来" else ""

    /**
     * 卡内就地校验（**纯函数**）：返回 [AskGroupCheck.Invalid] 时渲染层把
     * problems 逐条摆红字、提交按钮置灰——**不提交后才报是被禁的**。
     */
    fun validate(questions: List<AskQuestion>, draft: Map<String, String>): AskGroupCheck {
        val problems = mutableListOf<String>()
        for (q in questions) {
            val v = draft[q.id]?.trim().orEmpty()
            when (q.kind) {
                AskQuestionKind.SINGLE -> when {
                    v.isEmpty() && q.required -> problems += "「${q.title}」必答未填"
                    v.isNotEmpty() && q.options.none { it.id == v } ->
                        problems += "「${q.title}」的答案不在选项里（收到：$v）"
                }

                AskQuestionKind.MULTI -> {
                    val picked = splitMulti(v)
                    if (q.required && picked.isEmpty()) problems += "「${q.title}」必答未填"
                    val unknown = picked.filter { id -> q.options.none { it.id == id } }
                    if (unknown.isNotEmpty()) {
                        problems += "「${q.title}」选了不存在的选项：${unknown.joinToString("、")}"
                    }
                }

                AskQuestionKind.TEXT ->
                    if (q.required && v.isEmpty()) problems += "「${q.title}」必答未填"

                AskQuestionKind.NUMBER -> when {
                    q.required && v.isEmpty() -> problems += "「${q.title}」必答未填"
                    v.isNotEmpty() && v.toDoubleOrNull() == null ->
                        problems += "「${q.title}」要填数字（现在是：$v）"
                }

                AskQuestionKind.BOOL ->
                    if (q.required && v.isEmpty()) {
                        problems += "「${q.title}」必答未填"
                    } else if (v.isNotEmpty() && v != "true" && v != "false") {
                        problems += "「${q.title}」要选「是」或「否」"
                    }
            }
        }
        return if (problems.isEmpty()) AskGroupCheck.Ok else AskGroupCheck.Invalid(problems)
    }

    /**
     * 「都不改／按默认」的答案底：有 [AskQuestion.default] 用默认；
     * 没有的 BOOL 回 "false"、其余回空串（如实＝没改）。
     */
    fun defaultAnswers(questions: List<AskQuestion>): Map<String, String> = questions.associate { q ->
        q.id to when {
            q.default.isNotEmpty() -> q.default
            q.kind == AskQuestionKind.BOOL -> "false"
            else -> ""
        }
    }

    /** 提交时的最终答案：用户填了用用户的，空了退回默认（与 [defaultAnswers] 同一口径）。 */
    fun draftAnswers(questions: List<AskQuestion>, draft: Map<String, String>): Map<String, String> =
        questions.associate { q ->
            val v = draft[q.id]?.trim().orEmpty()
            q.id to when {
                v.isNotEmpty() -> v
                q.default.isNotEmpty() -> q.default
                q.kind == AskQuestionKind.BOOL -> "false"
                else -> ""
            }
        }

    /**
     * 各张卡的答案并成一条（引擎按卡序收集；同 id 后卡覆盖前卡——解析层已禁
     * 重复 id，这一条只是兜底不串位：键值对原样并入，不重排不改值）。
     */
    fun mergeAll(chunks: List<Map<String, String>>): Map<String, String> {
        val merged = LinkedHashMap<String, String>()
        chunks.forEach { merged.putAll(it) }
        return merged
    }

    /**
     * 这张 pendingChoice 走「多问卡」吗：**只有 QUESTION 且带 questions**。
     * 御批类（GROUP_AUTHORIZATION / PERSONA_PROPOSAL）恒 false——它们的批准
     * 只能点卡上的明确按钮（answerChoice），打字与「多问卡」重构都绕不过（负例 5）。
     */
    fun isGroupCard(req: AskUserRequest): Boolean =
        req.kind == AskKind.QUESTION && req.questions.isNotEmpty()
}
