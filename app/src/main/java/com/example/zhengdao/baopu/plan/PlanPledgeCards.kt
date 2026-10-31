// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-10-31-第三百一十二批-计划军令状 §做什么 1
// （开工卡一次问清并冻结）＋ 判据④（短任务不弹卡——卡只由
// [PlanPledgeRules.PledgeParse.Need] 触发）＋ 追加④。
//
// 分工：判据在 `PlanPledge.kt`（纯函数，零 IO 零时钟），**这一层是卡面与话术**——
// 开工卡（项数＋每项一句话＋验收方式全列在问题里）、冻结／不立状的回灌。
// 房型照 `PlanGuardCards` / `PlanContractCards`：inline 中文，不走 strings——
// 非 `@Composable` 位置拿不到 Context，硬搬字符串会让门禁的双语对账多一张表。
package com.example.zhengdao.baopu.plan

import com.example.zhengdao.baopu.agent.AskUserOption
import com.example.zhengdao.baopu.agent.AskUserRequest
import com.example.zhengdao.baopu.plan.PlanPledgeRules.PledgeSnapshot

/**
 * 开工卡的判据与文案（纯函数，JVM 直测）。
 *
 * 选项 id 是稳定契约（与 `PlanGuardRules` 同纪律）：UI 只回传 id，引擎只按 id 走，
 * 改文案不动 id。
 */
internal object PlanPledgeCards {

    const val OPT_FREEZE = "plan-pledge-freeze"
    const val OPT_DECLINE = "plan-pledge-decline"

    /**
     * 只有真点了「立状」才算立：没点／超时／传了没认识的 id ⇒ 不冻结。
     *
     * fail-closed 的方向与确认卡相反、道理相同：确认卡沉默＝不做（动作不许偷跑），
     * 军令状沉默＝不立（**军令状要人签名**——替她立一份她没点头的状，等于替她
     * 做了一次治理决定；不立状的任务照走既有口径，140/242 两层一点不少）。
     */
    fun approved(pickedId: String?): Boolean = pickedId == OPT_FREEZE

    /** 开工卡：一次问清——项数＋每项一句话＋验收方式，全部列在问题里。 */
    fun pledgeRequest(s: PledgeSnapshot): AskUserRequest = AskUserRequest(
        question = buildString {
            append("把这份计划立成军令状吗？共 ${s.entries.size} 项，立状后删项/加项必须显式重签。")
            if (s.goal.isNotBlank()) append("\n目标：${s.goal}")
            s.entries.forEachIndexed { i, e ->
                append("\n${i + 1}. ${e.text}——验收：${e.accept}")
            }
        },
        options = listOf(
            AskUserOption(
                id = OPT_FREEZE,
                label = "立状并冻结",
                detail = "快照写进账：之后每一项都要有结局——done 带证据，或 abandoned 带原因；" +
                    "有一项没有结局，任务就记未完成并进「未办成单」",
                recommended = true,
            ),
            AskUserOption(
                id = OPT_DECLINE,
                label = "先不立状",
                detail = "不冻结、不设结项门（计划照常显示，该有的确认卡一张不少）；" +
                    "要改计划先改，改完重新立状",
            ),
        ),
    )

    /** 冻结后的回灌：模型必须知道军令状已生效、结项要什么、改计划走哪条路。 */
    fun frozenAck(s: PledgeSnapshot): String =
        "【军令状已立】第 ${s.version} 版计划已冻结（${s.entries.size} 项，账行 pledge:${s.id}）。" +
            "每一项都必须有结局：done 要附证据（文件/输出/账行），abandoned 必填原因 " +
            "（blocked/needs_human/unsupported）。收口时有一项没有结局，任务就记未完成。" +
            "删项/加项必须重签（resign 参数写明删哪项、加哪项、为什么）并经人批或自决单；" +
            "不许就地改计划。"

    /** 人拒绝立状后的回灌：不设结项门，但不许把这读成「可以少做」。 */
    fun declinedAck(s: PledgeSnapshot): String =
        "【未立状】用户没有把这份计划立成军令状：不设结项门，按既有口径走。" +
            "这不改变计划本身——${s.entries.size} 项照旧逐项推进、逐项打勾，" +
            "该有的确认卡与契约（如有）一张不少。"
}
