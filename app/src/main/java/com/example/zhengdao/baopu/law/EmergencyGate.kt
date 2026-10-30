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
        card.whyNoLaw.none { it.isNotBlank() } -> "没写「为什么无法可依」：紧急权的前提是现行条文无一覆盖"
        card.windowMinutes <= 0 -> "没给危险时间窗：说不出多久内成真，就不是即时危险"
        !card.options.containsAll(setOf("立刻处置", "先停下")) -> "卡面必须同时给「立刻处置」与「先停下」"
        !card.defaultIsStop -> "默认必须是「先停下」（安全侧）——默认动手等于每次紧急都在执行"
        else -> null
    }
}
