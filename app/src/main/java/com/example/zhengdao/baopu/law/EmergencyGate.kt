// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十六批 P2 · 法典卷七 **L21 紧急上报权**——法外的唯一合法出口，由生产判定说死。
//
// 出处：第二百七十六批派工单 P2（L21 三要件缺一即拒、默认＝先停下）；
// 判据原文＝法典卷七 L21（私有侧《抱朴-法典》；这两份文档不在本库内，别按库内路径找）。
// 本文件做的事：把 271 批写在测试侧的 L21 判据（law/spec/L21EmergencyCardSpec.kt，随本批删除）
// 搬进 `app/src/main`——判据文案逐字不动，用例名照 spec 承诺原样升格（#271-L21 销账）。
//
// ## 判据（输入→输出，逐条可执行）
// 紧急卡要弹得出去，三样缺一不可：
//   ① `EMERGENCY` 标记（[EmergencyCard.hasMarker]）；
//   ② **为什么无法可依**（[EmergencyCard.whyNoLaw]，逐条写明哪些条文都不适用）；
//   ③ **危险的时间窗**（[EmergencyCard.windowMinutes]，多久内成真）。
// 卡面还必须同时给「立刻处置」与「先停下」两个选项，且**默认＝先停下**（[EmergencyCard.defaultIsStop]）。
//
// ## 为什么默认必须是"先停下"
// L21 是"法外的唯一合法出口"，出口的默认位一旦偏向"动手"，每一次紧急都会变成一次未经审查的执行；
// 默认停下，最坏结果是慢一点——两者的代价不对称，所以口径只能往保守一侧写死。
//
// ## 为什么只有判定
// 生产里还没有触发紧急通道的调用方（与 249-B 的 `openGrant` 同款、236 先例：门禁反钉）；
// 出卡、弹卡、倒计时都由接线层做（后续批次）：本层零 IO、零时钟，卡面由调用方给，
// 本层只把"这张卡能不能以紧急的名义弹出去"一次算清。
//
// ## 第三百零三批补的两个入口（资格／理由）为什么在这儿而不是在预算层
// 303 对账：L21 的"critical＋L21 才有豁免资格"这条判据在 `app/src/main` 里被手抄三遍
// （`inspect/OpinionBudget.kt:14`、`:26`、`agent/OpinionWiring.kt:250`），而本文件当时**根本没有**
// 这条判据——`admit` 判的是卡面三要件。所以"law/ 当唯一判定源"的第一步只能是先把它写进 law/，
// 再让那三处改成调它。替换没做（两处被别的批次占着，见交付报告 §4 接点清单），
// 但 [qualifiesForExemption]／[reasonStated] 已就位，且源级门禁
// `app/src/test/java/com/example/zhengdao/baopu/law/LawSourceWiringGateTest.kt`
// 把"手抄几份、谁还没换"钉成账：多抄一份即红，换成本文件也要红一次（逼登记）。
package com.example.zhengdao.baopu.law

/** L21 紧急卡的要素（判据缺一即拒；见 [EmergencyGate.admit] 的拒的理由）。 */
internal data class EmergencyCard(
    val hasMarker: Boolean = true,
    /** 逐条写明"哪些条文都不适用"；空 ⇒ 拒。 */
    val whyNoLaw: List<String> = listOf("L5/L7/L8 都不覆盖「账号将被改」"),
    /** 危险多久内会成真（分钟）；≤0 ⇒ 拒。 */
    val windowMinutes: Int = 10,
    val options: Set<String> = setOf("立刻处置", "先停下"),
    /** 卡面默认选项是不是"先停下"。 */
    val defaultIsStop: Boolean = true,
)

/** L21 紧急上报权的唯一判定入口（`admit`＝这张卡许不许以紧急的名义弹）。 */
internal object EmergencyGate {
    const val MARKER = "EMERGENCY"

    /** null = 允许弹卡；否则是拒的理由。 */
    fun admit(card: EmergencyCard): String? = when {
        !card.hasMarker -> "缺 $MARKER 标记：这只是一张普通卡，不许借紧急的名义绕过每日限额"
        !reasonStated(card.whyNoLaw) -> "没写「为什么无法可依」：紧急权的前提是现行条文无一覆盖"
        card.windowMinutes <= 0 -> "没给危险时间窗：说不出多久内成真，就不是即时危险"
        !card.options.containsAll(setOf("立刻处置", "先停下")) -> "卡面必须同时给「立刻处置」与「先停下」"
        !card.defaultIsStop -> "默认必须是「先停下」（安全侧）——默认动手等于每次紧急都在执行"
        else -> null
    }

    /**
     * L21 越预算的**资格**判据：致命 **且** 条文本尊是 L21，缺一就没有"紧急豁免"这回事。
     *
     * 收两个布尔而不是 `Opinion`：`Opinion`／`Severity`／`LawRef` 属 `baopu/inspect/` 的契约层，
     * 判定层不反过来依赖它（与 275 的 `Violation`、276 的 `EmergencyCard` 同一投影口径——事实由调用方给）。
     *
     * 303 批把它写在这儿，是因为同一条判据在生产里被手抄了三遍
     * （`inspect/OpinionBudget.kt:14`、`:26`、`agent/OpinionWiring.kt:250`）：
     * 抄一处改一处 ⇒ 同一个意见在预算层"能豁免"、在判定层"没资格"。本函数是那三处唯一的替身。
     */
    fun qualifiesForExemption(severityIsCritical: Boolean, lawRefIsL21: Boolean): Boolean =
        severityIsCritical && lawRefIsL21

    /**
     * 一句「为什么无法可依」说没说清（303 起这是 L21 理由判据的唯一口径）。
     *
     * 与 [admit] 第②要件、`EmergencyExemption` 构造器那条 `isNotBlank` 同尺：三处各自写
     * `isNotBlank`／`none { }` 就是三个口径，改一个漏两个。
     */
    fun reasonStated(reason: String): Boolean = reason.isNotBlank()

    /** 多条不适用条文：有一条成话即算说清（[reasonStated] 的复数形式，同一把尺）。 */
    fun reasonStated(reasons: List<String>): Boolean = reasons.any { reasonStated(it) }

    // ── 第三百一十五批：紧急卡必须人批（L21 的执行面） ────────────────────────
    //
    // 276 只判了「卡能不能以紧急的名义弹出去」，315 判「弹出去之后归谁定」：
    // 紧急卡**必须人批**——任何自决路径撞上它只能拒，没有"特殊情况"。
    // 判定层照旧不依赖契约层：三个布尔由调用方投影（与 [qualifiesForExemption] 同一口径）。

    /** 一条处置诉求算不算紧急卡：标记＋致命＋L21，三样缺一就只是普通意见。 */
    fun isEmergencyCard(hasMarker: Boolean, severityIsCritical: Boolean, lawRefIsL21: Boolean): Boolean =
        hasMarker && severityIsCritical && lawRefIsL21

    /** 紧急卡必须人批：认成紧急卡 ⇒ 恒 true——这一位不许任何"特殊情况"改写。 */
    fun requiresApproval(isEmergencyCard: Boolean): Boolean = isEmergencyCard

    /** 自决路径撞上紧急卡 ⇒ 一律拒（null＝非紧急，按各路径原判走；拒因唯一出自本层）。 */
    fun selfRulingRejection(isEmergencyCard: Boolean): String? =
        if (isEmergencyCard) "紧急卡必须人批：自决路径不许放行（L21／L30b——止损 0 秒可以自执行，处置授权不行）"
        else null
}
