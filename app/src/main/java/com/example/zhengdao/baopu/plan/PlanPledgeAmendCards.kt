// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-11-01-第三百四十三批-军令状范围变更 §三（变更要弹卡时走御案，
// ≤3 行：加了什么／改了什么／要不要重签）＋ §一 三条正路各自的回灌。
//
// 分工：判据在 `PlanPledgeAmend.kt`（纯函数，零 IO 零时钟），**这一层是卡面与话术**。
// 房型照 `PlanPledgeCards`（312）：inline 中文不走 strings（非 `@Composable` 位置拿
// 不到 Context），选项 id 是稳定契约（UI 只回传 id，改文案不动 id），沉默＝不改
// （fail-closed：军令状要她签名，没点「按新版推进」就当这次变更没发生过）。
//
// 排队归御案（343 §三）：这张卡交出去之后，本轮弹几张、今日弹几张由
// `agent/OpinionWiring` 与 `inspect/OpinionBudget` 说了算——本层只保证**内容三行**、
// 不自建第二套额度。接线归后续批次（同 312 的三个调用点）。
package com.example.zhengdao.baopu.plan

import com.example.zhengdao.baopu.agent.AskUserOption
import com.example.zhengdao.baopu.agent.AskUserRequest
import com.example.zhengdao.baopu.plan.PlanPledgeAmendRules.AmendChange
import com.example.zhengdao.baopu.plan.PlanPledgeAmendRules.AmendKind
import com.example.zhengdao.baopu.plan.PlanPledgeAmendRules.AmendOp

/**
 * 变更卡的判据与文案（纯函数，JVM 直测）。
 *
 * 卡面**固定三行**（[MAX_CARD_LINES]）：加了什么／改了什么／要不要重签。
 * 每行都出现，没有那一类就写「无」——三行的形状本身就是判据：她扫一眼就知道
 * 这一轮动了哪一格，而不是从一段叙述里挑好听的说（军令状的立意）。
 */
internal object PlanPledgeAmendCards {

    const val OPT_APPLY = "plan-pledge-amend-apply"
    const val OPT_KEEP = "plan-pledge-amend-keep"

    /** 卡面行数上限（派工单 §三「≤3 行」）。 */
    const val MAX_CARD_LINES = 3

    /** 只有真点了「按新版推进」才算批：没点／超时／不认识的 id ⇒ 不改。 */
    fun approved(pickedId: String?): Boolean = pickedId == OPT_APPLY

    /**
     * 三行正文（第一行加、第二行改与删、第三行要不要重签）。
     *
     * 预算超限的话挂在第三行尾上——她是唯一能改预算的人，这一行就是问她的凭据。
     */
    fun cardLines(change: AmendChange): List<String> {
        val adds = change.ops.filter { it.kind == AmendKind.ADD }
        val revises = change.ops.filter { it.kind == AmendKind.REVISE }
        val drops = change.ops.filter { it.kind == AmendKind.DROP }
        val addLine = if (adds.isEmpty()) {
            "加：无"
        } else {
            "加：" + adds.joinToString("、") { "「${it.text}」" } + "（共 ${adds.size} 项，追加人是${change.by.cn}）"
        }
        val changeLine = if (revises.isEmpty() && drops.isEmpty()) {
            "改：无"
        } else {
            "改：" + (
                revises.map { reviseLine(it) } +
                    drops.map { "删「${it.text}」（${it.reason.ifBlank { change.reason }}）" }
                ).joinToString("；")
        }
        return listOf(addLine, changeLine, resignLine(change, revises.isNotEmpty(), drops.isNotEmpty()))
            .map { it.take(PlanPledgeRules.MAX_WHY_CHARS) }
    }

    private fun reviseLine(op: AmendOp): String {
        val what = if (op.to.isEmpty()) "判据" else "这一项"
        val to = if (op.to.isEmpty()) op.text else "「${op.to}」"
        val accept = if (op.accept.isEmpty()) "" else "，验收改成：${op.accept}"
        return "改$what $to（原「${op.text}」）$accept"
    }

    private fun resignLine(change: AmendChange, hasRevise: Boolean, hasDrop: Boolean): String {
        val why = when {
            hasRevise -> "要——判据变了等于重新立约"
            hasDrop -> "要——删项要她确认"
            else -> "要——新项进表、版本 +1"
        }
        val tail = if (hasRevise) {
            "旧的第 ${change.fromVersion} 版原样留在账上；被改的那项回到待做。"
        } else {
            "旧的第 ${change.fromVersion} 版原样留在账上。"
        }
        val over = if (change.overBudget.isEmpty()) "" else "而且${change.overBudget.joinToString("；")}。"
        return "$why：$tail$over"
    }

    /** 变更卡：一行题头（谁提的、为什么）＋ 三行正文 ＋ 两个键。 */
    fun amendRequest(change: AmendChange): AskUserRequest {
        val lines = cardLines(change)
        val question = buildString {
            appendLine("军令状第 ${change.fromVersion} 版要变更（${change.by.cn}提出：${change.reason}）")
            lines.forEach { appendLine(it) }
        }.trimEnd()
        return AskUserRequest(
            question = question,
            // 两个键都不标推荐：改约是模型提的，推荐它＝替她做主（312 的开工卡推荐立状是因为
            // 卡的用途就是请她签名，这里不是）
            options = listOf(
                AskUserOption(
                    id = OPT_APPLY,
                    label = "改，按新的一张计划表推进",
                    detail = "这次变更生效：版本 +1、旧版留在账上、被改的项回到待做、删掉的项记 DROPPED(原因)",
                ),
                AskUserOption(
                    id = OPT_KEEP,
                    label = "不改，按第 ${change.fromVersion} 版继续",
                    detail = "计划与军令状都不动（申报的这条变更不生效，也不记变更账）；" +
                        "它按现役那一版接着推进并打勾",
                ),
            ),
        )
    }

    /** 变更生效后的回灌：模型要知道新版号、哪些项回到待做、删掉的项算什么。 */
    fun appliedAck(change: AmendChange, outcome: PlanPledgeAmendRules.Amended): String {
        val state = outcome.state
        val parts = mutableListOf(
            "第 ${outcome.snapshot.version} 版已生效（原第 ${change.fromVersion} 版留在账上，共 ${outcome.snapshot.entries.size} 项）。",
        )
        if (state.resetToTodo.isNotEmpty()) {
            parts += "回到待做：${state.resetToTodo.joinToString("、") { "「$it」" }}——判据变了，旧结论不算，要重新做完并申报结项。"
        }
        if (state.dropped.isNotEmpty()) {
            parts += "记下 DROPPED：${state.dropped.joinToString("、") { "「${it.text}」（${it.reason}）" }}——" +
                "这是「不要了」，不是 abandoned（做不完），收口不再要这些项的结局。"
        }
        if (state.keepEndings.isNotEmpty()) {
            parts += "未变的 ${state.keepEndings.size} 项结局照旧保留（做了就是做了，变更不作废已完成的项）。"
        }
        return "【军令状已重签】" + parts.joinToString("")
    }

    /** 她点了「不改」后的回灌：现役那一版一个字没动，但这次申报要在账外留一句。 */
    fun keptAck(snapshot: PlanPledgeRules.PledgeSnapshot): String =
        "【变更未生效】用户没有批这次变更：计划与军令状都保持第 ${snapshot.version} 版" +
            "（${snapshot.entries.size} 项，结项门照旧）。请按现役那一版继续推进；" +
            "要变更先在对话里问清，不要重发同一份申报。"

    /** 被本层拒掉的回灌（写法不对／通道不对／超预算想自己认）。 */
    fun rejectedAck(message: String): String = "【变更被拒】$message"

    /** 没立状时的回灌：本层不回话，计划改动照 140 的确认卡走。 */
    fun ignoredAck(): String =
        "【未立状】这份计划没立军令状，范围变更不走重签：按既有口径改计划表（140 的计划变更" +
            "确认卡该弹还弹），amend 参数本次忽略。"
}
