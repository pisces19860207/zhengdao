// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十六批 P2 · 法典卷十 **L30b 三时钟**——止损、告知、追认，各走各的。
//
// 出处：第二百七十六批派工单 P2（L30b 三时钟：止损 0 秒／告知 3 秒、最严重 2 秒／追认 24h）；
// 判据原文＝法典卷十 L30b（私有侧《抱朴-法典》；不在本库内，别按库内路径找）。
// 本文件做的事：给三只时钟一个唯一的生产读数点（271 台账此前没有 L30b 的判据文件，本批新建门禁）。
//
// ## 判据（三条，逐条可执行）
//   ① **止损 0 秒**（[STOP_LOSS_MS]）：白名单内的止损动作**不等判定**，立即自执行——
//      事后由 L22 撤销单追问。止损等判定＝有人在旁边点头才敢动手，止损就没意义了。
//   ② **告知 3 秒／最严重 2 秒**（[INFORM_MS]/[INFORM_CRITICAL_MS]）：超过 ⇒ 旁路；
//      **旁路只影响"谁出卡"，不影响处置权**。最严重的判定属 L29 灾异四类，未接线 ⇒
//      本批只收 `critical: Boolean`（[informDeadlineMs]），L29 接线那天换判据、用例名不动。
//   ③ **追认 24 小时**（[RATIFY_MS]）：处置后满 24 小时未追认，走 L22 撤销单的追认口径。
//
// ## 为什么止损是 0 而不是"尽快"
// 0 是一个可以拿门禁钉死的数：差一毫秒都不行。"尽快"没法核——
// 这就是三只时钟写成常量加算式、而不是一句话注释的原因。
//
// ## 为什么只有判定
// 出卡、倒计时、旁路后的卡面都由接线层做（生产还没有调用方，与 236 先例同款）：
// 本层零 IO、零时钟读取，`elapsedMs`/`handledAtMs` 由调用方给，本层只算"到点没有、截止是几时"。
package com.example.zhengdao.baopu.law

/** L30b 三时钟的唯一生产读数点（三条判据各走各的，串档即违规）。 */
internal object EmergencyClocks {
    /** 止损时钟：0 秒——白名单内的止损动作不等判定，立即自执行。 */
    const val STOP_LOSS_MS = 0L

    /** 告知时钟（普通档）：3 秒。超过 ⇒ 旁路（只影响"谁出卡"）。 */
    const val INFORM_MS = 3_000L

    /** 告知时钟（最严重档）：2 秒。判定属 L29，未接线 ⇒ 本批只收 critical 布尔。 */
    const val INFORM_CRITICAL_MS = 2_000L

    /** 追认时钟：24 小时。处置后满 24h 未追认 ⇒ 走 L22 撤销单口径。 */
    const val RATIFY_MS = 24L * 3600 * 1000

    /** 告知截止时长：最严重档 2 秒，其余 3 秒。 */
    fun informDeadlineMs(critical: Boolean): Long = if (critical) INFORM_CRITICAL_MS else INFORM_MS

    /** 超过告知截止 ⇒ 旁路（只影响"谁出卡"，不影响处置权）。差一毫秒都不算到点。 */
    fun informBypass(elapsedMs: Long, critical: Boolean): Boolean = elapsedMs >= informDeadlineMs(critical)

    /** 追认截止时刻：处置时刻 + 24 小时，不多不少。 */
    fun ratifyDueAtMs(handledAtMs: Long): Long = handledAtMs + RATIFY_MS
}
