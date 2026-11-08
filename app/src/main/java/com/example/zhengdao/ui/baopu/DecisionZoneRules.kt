// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百二十八批（派工单 §一/§二）：**决策中心主屏**的纯判据层——
// 待批／已办／可回滚三个区、批/驳/留三种动作，全部**读既有账、不新造形状**：
//   · 待批 ＝ 御案卡（278）＋ 确认卡/提案卡（104 引擎单槽）＋ 进化建议/效果卡（99/100）
//     ＋ 待抽检（208 §2.7——派工单判据①点名的「来自 DecisionLedger 的真实记录」）；
//   · 已办 ＝ `DecisionLedger.read`（新→旧）取最近 [DONE_LIMIT] 条；
//   · 可回滚 ＝ 同一本账里 `reversible` 的行（回滚执行走既有通道，回滚本身由
//     `DecisionRollbacks` 留 `rollback_of` 行——470 加固的那一路，本文件不碰执行）。
//
// 三条纪律（对应派工单判据）：
//   ① 不新增 kind、不动 `DecisionRecord` 字段——批/驳/留的裁定行走既有的
//     [DecisionKind.TOOL_GRANT]（ConsequenceLedger 已有「复用授权行记放行到哪一步」的先例），
//     `evidence` 统一带 [RULING_PREFIX] 前缀，与 allowance:/canary:/eval: 那几路读数互不认领；
//   ② 裁定行 `reversible=false`（只能降级，208 纪律②）——裁定本身不走通用撤销钮；
//   ③ 「留」只在**既有通道本来就有搁置档**的条目上提供（御案搁置/建议 defer/效果 later）；
//     引擎单槽与抽检结论没有既有搁置口，不新造（判据④「不新造权限判断」的同一口径）。
//
// 纪律：零 Android、零 Compose、零 IO——输入普通值与既有域对象，输出行模型。
// 渲染在 `DecisionZoneScreen.kt`（那一层不许出现任何判断），接线在 `BaopuChatController`。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.R
import com.example.zhengdao.baopu.DecisionActor
import com.example.zhengdao.baopu.DecisionDraft
import com.example.zhengdao.baopu.DecisionKind
import com.example.zhengdao.baopu.DecisionRecord
import com.example.zhengdao.baopu.DecisionSampling
import com.example.zhengdao.baopu.PostAdoptionNotice

/** 主屏唯一的三种动作（派工单 §一③：批 / 驳 / 留）。 */
internal enum class DecisionZoneAction(val wire: String, val afterCn: String) {
    APPROVE("approve", "已批"),
    REJECT("reject", "已驳"),
    HOLD("hold", "已留"),
}

/** 待批条目的来路——全是既有队列（派工单 §一②「不新造形状」）。 */
internal enum class DecisionZoneSource {
    ROYAL,
    AUTHORIZATION,
    PERSONA,
    SUGGESTION,
    EFFECT,
    REVIEW,
}

/**
 * 待批区的一行：三行（是什么/为什么问/不答会怎样）＋这条**本来有哪些动作通道**。
 *
 * @param royal 御案行原样带卡（渲染复用 [BaopuRoyalCard]：三行＋真假标注＋批/驳/搁置，
 *              选项 id 由控制器交给 `RoyalVerdict.ofOptionId`——本层与屏幕层都不解释它）
 * @param record 抽检行的原记录（动作要落回它：判「确认无误/判错了」写的是它的 id）
 * @param canApprove/canReject/canHold 这条有没有**既有**通道（判据③：留只给有搁置档的条目）
 */
internal data class ZonePendingRow(
    val key: String,
    val source: DecisionZoneSource,
    val lines: List<String>,
    val atMs: Long,
    val timeLabel: String,
    val canApprove: Boolean,
    val canReject: Boolean,
    val canHold: Boolean,
    val royal: RoyalRow? = null,
    val record: DecisionRecord? = null,
)

/** 已办区的一行：记录本体＋算好的时间话（屏幕层不算时刻差）。 */
internal data class ZoneDoneRow(val record: DecisionRecord, val timeLabel: String)

/** 可回滚区的一行（同 [ZoneDoneRow] 的形状，动作钮不同）。 */
internal data class ZoneRollbackRow(val record: DecisionRecord, val timeLabel: String)

/** 主屏三区的一次快照（一次读账出全量，不各读各的）。 */
internal data class ZoneSnapshot(
    val pending: List<ZonePendingRow>,
    val done: List<ZoneDoneRow>,
    val rollbackable: List<ZoneRollbackRow>,
)

/** 判据层（无状态；同入参同出参，JVM 直测）。 */
internal object DecisionZoneRules {

    /** 已办区「最近 N 条」的 N（派工单 §一②；显示档，不是账本形状）。 */
    const val DONE_LIMIT = 20

    /** 裁定行 `evidence` 的前缀（读数各路的认领前缀都不同，互不冒领）。 */
    const val RULING_PREFIX = "zone:"

    /** 待批键的前缀（控制器分发认它——键的构成只在这一个函数里）。 */
    const val ROYAL_PREFIX = "royal:"
    const val REVIEW_PREFIX = "review:"

    /**
     * 三区快照。
     *
     * @param judgedIds 已判过的抽检记录 id（待抽检＝抽中且没判的，§2.7 现成函数扣）
     */
    fun snapshot(
        s: DecisionStrRes,
        nowMs: Long,
        royal: List<RoyalRow>,
        authSpec: DecisionCardSpec?,
        personaSpec: DecisionCardSpec?,
        suggestions: List<EvolutionSuggestion>,
        effects: List<PostAdoptionNotice>,
        records: List<DecisionRecord>,
        judgedIds: Set<String>,
    ): ZoneSnapshot = ZoneSnapshot(
        pending = pendingRows(
            s,
            nowMs,
            royal,
            listOfNotNull(authSpec, personaSpec),
            suggestions,
            effects,
            DecisionSampling.pending(records, judgedIds),
        ),
        done = records.take(DONE_LIMIT).map { ZoneDoneRow(it, timeLabelOf(s, it.atMs, nowMs)) },
        rollbackable = records.filter { it.reversible }
            .map { ZoneRollbackRow(it, timeLabelOf(s, it.atMs, nowMs)) },
    )

    /** 待批聚合：各来路先成行，再**按时间倒序**（派工单 §一②；引擎卡没有时刻 ⇒ 排最后）。 */
    private fun pendingRows(
        s: DecisionStrRes,
        nowMs: Long,
        royal: List<RoyalRow>,
        engineSpecs: List<DecisionCardSpec>,
        suggestions: List<EvolutionSuggestion>,
        effects: List<PostAdoptionNotice>,
        reviews: List<DecisionRecord>,
    ): List<ZonePendingRow> = buildList {
        royal.forEach { add(royalRow(it, s, nowMs)) }
        engineSpecs.forEach { add(engineRow(it, s)) }
        suggestions.forEach { add(suggestionRow(DecisionSpecs.suggestion(it, s), it.createdAt, s, nowMs)) }
        effects.forEach { add(effectRow(DecisionSpecs.effect(it, s), it.appliedAtMs, s, nowMs)) }
        reviews.forEach { add(reviewRow(it, s, nowMs)) }
    }.sortedByDescending { it.atMs }

    /** 御案行：三行与钮都在卡上（[BaopuRoyalCard] 原样渲染），动作走既有御批账。 */
    private fun royalRow(r: RoyalRow, s: DecisionStrRes, nowMs: Long): ZonePendingRow =
        ZonePendingRow(
            key = "$ROYAL_PREFIX${r.id}",
            source = DecisionZoneSource.ROYAL,
            lines = r.card.lines.map { it.text },
            atMs = r.atMs,
            timeLabel = timeLabelOf(s, r.atMs, nowMs),
            canApprove = true,
            canReject = true,
            canHold = true,
            royal = r,
        )

    /**
     * 引擎单槽卡（确认/提案）：批＝卡自己的确认位、驳＝否定位——按钮 id 从规格里取，
     * 不在这里发明第四个选项；**留没有既有通道**（引擎在等这张卡，不答就是没答）。
     */
    private fun engineRow(spec: DecisionCardSpec, s: DecisionStrRes): ZonePendingRow =
        ZonePendingRow(
            key = spec.key,
            source = if (spec.variant == DecisionVariant.AUTHORIZATION) {
                DecisionZoneSource.AUTHORIZATION
            } else {
                DecisionZoneSource.PERSONA
            },
            lines = listOf(
                spec.title,
                spec.subtitle.ifBlank { spec.body },
                s.get(R.string.dz_stay_engine),
            ),
            atMs = 0L,
            timeLabel = s.get(R.string.dz_time_engine),
            canApprove = engineOptionId(spec, approve = true) != null,
            canReject = engineOptionId(spec, approve = false) != null,
            canHold = false,
        )

    /** 建议行：批/驳/留对应既有三决策（ADOPT/REJECT/DEFER）；采纳钮被禁时批不给（enabled 是规格里的既有判定）。 */
    private fun suggestionRow(
        spec: DecisionCardSpec,
        atMs: Long,
        s: DecisionStrRes,
        nowMs: Long,
    ): ZonePendingRow = ZonePendingRow(
        key = spec.key,
        source = DecisionZoneSource.SUGGESTION,
        lines = listOf(spec.title, spec.subtitle, s.get(R.string.dz_stay_suggestion)),
        atMs = atMs,
        timeLabel = timeLabelOf(s, atMs, nowMs),
        canApprove = spec.buttons.any { it.id == "adopt" && it.enabled },
        canReject = spec.buttons.any { it.id == "reject" },
        canHold = spec.buttons.any { it.id == "defer" },
    )

    /** 效果行：批＝先留着、驳＝回滚到采纳前、留＝稍后处理（100 的既有三钮，按 id 认领不按角色）。 */
    private fun effectRow(
        spec: DecisionCardSpec,
        atMs: Long,
        s: DecisionStrRes,
        nowMs: Long,
    ): ZonePendingRow = ZonePendingRow(
        key = spec.key,
        source = DecisionZoneSource.EFFECT,
        lines = listOf(spec.title, spec.body, s.get(R.string.dz_stay_effect)),
        atMs = atMs,
        timeLabel = timeLabelOf(s, atMs, nowMs),
        canApprove = spec.buttons.any { it.id == "keep" },
        canReject = spec.buttons.any { it.id == "rollback" },
        canHold = spec.buttons.any { it.id == "later" },
    )

    /**
     * 抽检行（判据①：这一行的本体就是 `DecisionLedger` 的真记录）。
     *
     * 批＝「确认无误」、驳＝「判错了」（208 §2.7 的两个既有结论）；
     * **留没有既有通道**——抽检结论是闭集（confirmed/wrong），不新造第三值。
     */
    private fun reviewRow(r: DecisionRecord, s: DecisionStrRes, nowMs: Long): ZonePendingRow =
        ZonePendingRow(
            key = "$REVIEW_PREFIX${r.id}",
            source = DecisionZoneSource.REVIEW,
            lines = listOf(r.reason, "${r.before}→${r.after}", s.get(R.string.dz_stay_review)),
            atMs = r.atMs,
            timeLabel = timeLabelOf(s, r.atMs, nowMs),
            canApprove = true,
            canReject = true,
            canHold = false,
            record = r,
        )

    /** 引擎卡的批/驳落在哪个选项（确认位＝PRIMARY/DANGER，否定位＝NEGATIVE——规格里已有的角色）。 */
    fun engineOptionId(spec: DecisionCardSpec, approve: Boolean): String? {
        val role = if (approve) {
            listOf(DecisionButtonRole.PRIMARY, DecisionButtonRole.DANGER)
        } else {
            listOf(DecisionButtonRole.NEGATIVE)
        }
        return spec.buttons.firstOrNull { it.enabled && it.role in role }?.id
    }

    /**
     * 裁定行（判据②：一行账带 actor 与理由）。
     *
     * kind 复用 [DecisionKind.TOOL_GRANT]（「这批动作被放行到哪一步」——ConsequenceLedger
     * 已开这个复用先例）；`reversible=false` 走 208 的**只降**档：裁定行不吃通用撤销钮。
     * [itemSummary] 空白 ⇒ null：说不出「办的是哪件事」的裁定不落账（208「不落空账」的同一口径）。
     */
    fun rulingDraft(
        key: String,
        action: DecisionZoneAction,
        itemSummary: String,
        nowMs: Long,
        sessionId: Long,
    ): DecisionDraft? {
        if (itemSummary.isBlank()) return null
        return DecisionDraft(
            kind = DecisionKind.TOOL_GRANT,
            actor = DecisionActor.USER,
            reason = "${action.afterCn}｜$itemSummary",
            evidence = "$RULING_PREFIX$key",
            before = "待批",
            after = action.afterCn,
            atMs = nowMs,
            sessionId = sessionId,
            reversible = false,
        )
    }

    /** 「多久前」那一句（与记录页同一套档，不另造时间格式）。 */
    private fun timeLabelOf(s: DecisionStrRes, atMs: Long, nowMs: Long): String {
        val age = decisionAgeOf(atMs, nowMs)
        return when (age.bucket) {
            DecisionTimeBucket.NOW -> s.get(R.string.decision_record_time_now)
            DecisionTimeBucket.MINUTES -> s.get(R.string.decision_record_time_min, age.n)
            DecisionTimeBucket.HOURS -> s.get(R.string.decision_record_time_hour, age.n)
            DecisionTimeBucket.DAYS -> s.get(R.string.decision_record_time_day, age.n)
            DecisionTimeBucket.OLDER -> s.get(R.string.decision_record_time_older)
        }
    }
}
