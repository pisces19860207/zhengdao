// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百二十三批 · 法典卷十 **L29 灾异定义（四类，命中任一且不可局部回滚）**——
// 「造出灾异态」这件事本身第一次由生产判定说死：判成灾异 ⇒ 必须进紧急通道，不许当普通动作放过。
//
// 出处：第三百二十三批派工单（L29–L33 五条真缺口门禁；判据原文＝法典卷十 L29，
// 私有侧《抱朴-法典-2026-10-27》；不在本库内，别按库内路径找）。
// 本文件做的事：四类灾异各有可执行判据（阈值写死成常量，改阈值＝改本文件＋御批），
// 且把「判定为灾异 ⇒ 必须以紧急卡上报」接到 315 的 [EmergencyGate] 上——
// 紧急卡怎么弹、归谁批，口径唯一住在 EmergencyGate，本层不抄第二遍。
//
// ## 判据（输入→输出，逐条可执行）
//   ①**数据损毁**：不可逆写坏／误删 ≥ [DATA_DESTROYED_MIN] 条——不可逆本身就是量词，没有「小到没事」的档；
//   ②**外发泄露**：凭据或用户数据流出 ≥ [LEAKED_ITEMS_MIN] 条——泄露同样没有下限；
//   ③**权限失控**：越权动作 ≥ [GRANT_RUNAWAY_MIN] 次且还在发生
//     （「拦不住」由次数增长投影，计数事实由调用方给，本层不自造）；
//   ④**大面积不可用**：主链路连续失败 ≥ [OUTAGE_FAILS_MIN] 次，**或**已持续 ≥ [OUTAGE_MS] 毫秒。
// 命中任一 **且不可局部回滚** 且 **有证据行**（[EmergencyGate.reasonStated] 的复数口径）⇒ 灾异；
// 三者缺一都不算——可回滚的走普通通道照样追责，没证据的「感觉很糟」不许抬进紧急通道。
//
// ## 为什么「可局部回滚」能把灾异否掉
// 灾异的可怕在于「回不去」：能局部回滚的坏结果，走 L9／L24 的普通账照样能追；
// 把它抬进紧急通道只会让紧急权贬值——出口一贬值，真灾异那天就没人信了。
//
// ## 为什么只有判定
// 灾异的证据源（损毁计数、泄露留痕、失败连击的观测点）还没有生产调用方
// （与 276 的 EmergencyCard、315 的 executeStopLoss 同款：门禁反钉，先有判定后有接线）；
// 本层零 IO、零时钟，事实全部由调用方投影。
package com.example.zhengdao.baopu.law

/** 一件可能成灾的事实在 L29 眼里的投影（计数与时刻全由调用方给，本层不自造）。 */
internal data class AnomalyFacts(
    /** ①不可逆写坏／误删的条数。 */
    val destroyedItems: Int = 0,
    /** ②凭据或用户数据流出条数。 */
    val leakedItems: Int = 0,
    /** ③越权动作发生的次数（还在发生＝次数仍在涨）。 */
    val runawayGrants: Int = 0,
    /** ④主链路连续失败次数。 */
    val outageConsecutiveFails: Int = 0,
    /** ④主链路已持续失败的时长（毫秒）。 */
    val outageMs: Long = 0L,
    /** 可不可以局部回滚（能回滚就不算灾异——见文件头「为什么」）。 */
    val locallyReversible: Boolean = false,
    /** 证据行：机器键或可回放引用（「据说」「感觉很糟」不算——空表 ⇒ 不判）。 */
    val evidenceLines: List<String> = emptyList(),
)

/** 灾异四类（[wire] 是账上稳定键；文案会改，键不许改）。 */
internal enum class AnomalyKind(val wire: String, val cn: String) {
    DATA_DESTROYED("data_destroyed", "数据损毁"),
    LEAK("leak", "外发泄露"),
    GRANT_RUNAWAY("grant_runaway", "权限失控"),
    OUTAGE("outage", "大面积不可用"),
    ;
}

/** L29 的一次判定结论。 */
internal data class AnomalyVerdict(
    val lawRef: String,
    /** 命中的类别（四类取最先命中的一类；null＝一类都没命中）。 */
    val kind: AnomalyKind?,
    /** 为什么命中（人读的清单；空＝没命中）。 */
    val hitReasons: List<String>,
    /** 证据行算不算说清（[EmergencyGate.reasonStated] 的复数口径）。 */
    val evidenceStated: Boolean,
    /** 灾异与否：命中任一 且 不可局部回滚 且 证据算数。 */
    val isAnomaly: Boolean,
    /** 判成灾异 ⇒ 必须按 L21 紧急卡上报（进紧急通道的唯一路径）。 */
    val mustRaiseEmergencyCard: Boolean,
    /** 「怎么报」：一句可执行的话（真判成灾异时非空）。 */
    val howToReport: String,
)

/** L29 灾异定义的唯一判据实现（生产）。 */
internal object AnomalyGate {

    const val LAW_REF = "L29"

    /** ①数据损毁阈值：不可逆写坏／误删 ≥1 条。不可逆本身就是量词，没有「小到没事」的档。 */
    const val DATA_DESTROYED_MIN = 1

    /** ②外发泄露阈值：凭据或用户数据流出 ≥1 条。泄露同样没有下限。 */
    const val LEAKED_ITEMS_MIN = 1

    /** ③权限失控阈值：越权动作 ≥3 次且还在发生。一次是事故，两次是巧合，三次是拦不住。 */
    const val GRANT_RUNAWAY_MIN = 3

    /** ④大面积不可用阈值（次数档）：主链路连续失败 ≥5 次。 */
    const val OUTAGE_FAILS_MIN = 5

    /** ④大面积不可用阈值（时长档）：已持续 ≥10 分钟（与次数档命中任一即成）。 */
    const val OUTAGE_MS = 10L * 60_000

    /**
     * 判一件事是不是灾异。
     *
     * 四类各按自己的阈值卡（改阈值＝改常量＋御批）；命中取最先的一类，理由逐条给出——
     * 「必须能给出证据行」钉在 [AnomalyVerdict.evidenceStated]（复用 [EmergencyGate.reasonStated]，不抄第二遍）。
     */
    fun classify(facts: AnomalyFacts): AnomalyVerdict {
        require(facts.destroyedItems >= 0 && facts.leakedItems >= 0 && facts.runawayGrants >= 0) {
            "L29 的计数不许为负：负数是填错，不等于「没有发生」"
        }
        require(facts.outageConsecutiveFails >= 0 && facts.outageMs >= 0) {
            "L29 的不可用观测不许为负：负数是填错，不等于「没有发生」"
        }
        val kind = kindOf(facts)
        val evidenceStated = EmergencyGate.reasonStated(facts.evidenceLines)
        val isAnomaly = kind != null && !facts.locallyReversible && evidenceStated
        return AnomalyVerdict(
            lawRef = LAW_REF,
            kind = kind,
            hitReasons = hitReasonsOf(facts),
            evidenceStated = evidenceStated,
            isAnomaly = isAnomaly,
            mustRaiseEmergencyCard = isAnomaly,
            howToReport = if (isAnomaly) {
                "判成灾异（${kind!!.cn}）：按 L21 紧急卡上报——EMERGENCY 标记＋为什么无法可依＋危险时间窗，" +
                    "止损白名单内的动作按 L30 先做（0 秒）；不许当普通意见送预算"
            } else {
                ""
            },
        )
    }

    /** 四类各自卡阈值（[AnomalyKind] 取最先命中的一类）；命中理由见 [hitReasonsOf]。 */
    private fun kindOf(facts: AnomalyFacts): AnomalyKind? = when {
        facts.destroyedItems >= DATA_DESTROYED_MIN -> AnomalyKind.DATA_DESTROYED
        facts.leakedItems >= LEAKED_ITEMS_MIN -> AnomalyKind.LEAK
        facts.runawayGrants >= GRANT_RUNAWAY_MIN -> AnomalyKind.GRANT_RUNAWAY
        facts.outageConsecutiveFails >= OUTAGE_FAILS_MIN || facts.outageMs >= OUTAGE_MS -> AnomalyKind.OUTAGE
        else -> null
    }

    /** 为什么命中（人读的清单；四类逐条给理由，空表＝一类都没命中）。 */
    private fun hitReasonsOf(facts: AnomalyFacts): List<String> = buildList {
        if (facts.destroyedItems >= DATA_DESTROYED_MIN) {
            add("数据损毁：不可逆写坏／误删 ${facts.destroyedItems} 条 ≥ $DATA_DESTROYED_MIN")
        }
        if (facts.leakedItems >= LEAKED_ITEMS_MIN) {
            add("外发泄露：凭据或用户数据流出 ${facts.leakedItems} 条 ≥ $LEAKED_ITEMS_MIN")
        }
        if (facts.runawayGrants >= GRANT_RUNAWAY_MIN) {
            add("权限失控：越权动作 ${facts.runawayGrants} 次 ≥ $GRANT_RUNAWAY_MIN 且还在发生")
        }
        if (facts.outageConsecutiveFails >= OUTAGE_FAILS_MIN) {
            add("大面积不可用：主链路连续失败 ${facts.outageConsecutiveFails} 次 ≥ $OUTAGE_FAILS_MIN")
        }
        if (facts.outageMs >= OUTAGE_MS) {
            add("大面积不可用：已持续 ${facts.outageMs} 毫秒 ≥ $OUTAGE_MS")
        }
    }

    /**
     * 灾异被当成普通动作送进来 ⇒ 拒（不许放过）；真按紧急卡上报了，或本来就不算灾异 ⇒ null。
     *
     * 「这张卡算不算紧急卡」唯一问 [EmergencyGate.isEmergencyCard]——本层只回答
     * 「灾异有没有走紧急通道」，紧急卡的口径（标记＋致命＋L21）不抄第二遍。
     */
    fun normalChannelRejection(
        facts: AnomalyFacts,
        hasMarker: Boolean,
        severityIsCritical: Boolean,
        lawRefIsL21: Boolean,
    ): String? {
        val verdict = classify(facts)
        if (!verdict.isAnomaly) return null
        if (EmergencyGate.isEmergencyCard(hasMarker, severityIsCritical, lawRefIsL21)) return null
        val missing = buildList {
            if (!hasMarker) add("EMERGENCY 标记")
            if (!severityIsCritical) add("致命级")
            if (!lawRefIsL21) add("L21 紧急上报")
        }
        return "灾异（${verdict.kind!!.cn}）不许当普通动作放过：必须进紧急通道——" +
            "缺 ${missing.joinToString("、")}，按 L21 紧急卡补齐再走"
    }
}
