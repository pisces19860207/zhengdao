// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十六批 P2 · 法典卷七 **L22 事后追认与撤销建议**——处置之后必须被追问。
//
// 出处：第二百七十六批派工单 P2（L22 24h 内出撤销单、五栏缺一不可、逾期记违规）；
// 判据原文＝法典卷七 L22（私有侧《抱朴-法典》；不在本库内，别按库内路径找）。
// 本文件做的事：把 271 批写在测试侧的 L22 判据（law/spec/L22RevocationSlipSpec.kt，随本批删除）
// 搬进 `app/src/main`——判据文案逐字不动，用例名照 spec 承诺原样升格（#271-L22 销账）。
//
// ## 判据（输入→输出，逐条可执行）
// 任何 L21 处置，**24 小时内**必须产出「撤销单」，五栏缺一不可：
//   发生了什么／当时授权了什么／风险／**建议撤销还是保留**／回滚点与影响面。
// 逾期未出 ⇒ **记一次违规**（不是"补一份就算"）；时间倒挂 ⇒ 视为没出；
// 缺哪一栏就点名哪一栏，不许一句话糊弄。
//
// ## 两个时限不是一个数（2026-10-29 御裁）：**2 小时初稿 → 24 小时定稿**
// L25 自执行话术里的 **2 小时**是**初稿时限**（发生了什么/动了什么/回滚点，别让现场冷掉）；
// 本条的 **24 小时**是**违规线**，到点要的是含风险与建议的**完整撤销单**。
// 两句都留、不合一个数：**初稿不等于定稿；初稿不豁免 24h 违规线。**
//
// ## 建议栏的三个合法值
// 撤销这次授权／保留／保留但收紧（[SlipAdvice]）——判据只管"这栏不许空"，选哪个是人的裁量。
//
// ## 为什么只有判定
// 撤销单的拟稿（中书舍人）与核实（暗卫）在 278 的链上；存账、催办都由接线层做：
// 本层零 IO、零时钟，`handledAtMs`/`producedAtMs` 由调用方给，
// 本层只把"这份撤销单收不收、逾不逾期、缺没缺栏"一次算清。
package com.example.zhengdao.baopu.law

/** L22 建议栏的三个合法值（判据只管"不许空"，选哪个是人的裁量）。 */
internal enum class SlipAdvice(val label: String) {
    REVOKE("撤销这次授权"),
    KEEP("保留"),
    KEEP_TIGHTEN("保留但收紧"),
}

/** L22 撤销单（五栏＋两个时间戳；五栏缺一不可，见 [Revocation.judge]）。 */
internal data class RevocationSlip(
    val eventId: String = "emg-1",
    val handledAtMs: Long,
    val producedAtMs: Long,
    /** 发生了什么。 */
    val what: String = "冻结了写入",
    /** 当时授权了什么。 */
    val authorized: String = "L25 窄门自执行（白名单：冻结写入）",
    /** 风险。 */
    val risk: String = "若判断错，写入延迟 10 分钟",
    /** 建议撤销还是保留。 */
    val advice: SlipAdvice = SlipAdvice.KEEP,
    /** 回滚点与影响面。 */
    val rollbackPoint: String = "快照 snap-2026-10-28-01",
)

/** L22 事后追认的唯一判定入口（`judge`＝这份撤销单收不收）。 */
internal object Revocation {
    /** 24 小时窗：逾期 ⇒ 记一次违规。 */
    const val WINDOW_MS = 24L * 3600 * 1000

    /** null = 合规；否则是违规理由（缺哪一栏就点名哪一栏，不许一句话糊弄）。 */
    fun judge(slip: RevocationSlip): String? {
        if (slip.producedAtMs - slip.handledAtMs > WINDOW_MS) {
            return "撤销单逾期（超过 24 小时）⇒ 记一次违规：紧急处置必须当天被追问"
        }
        if (slip.producedAtMs < slip.handledAtMs) {
            return "撤销单的时间早于处置本身（时间倒挂）⇒ 视为没出"
        }
        val missing = listOf(
            "发生了什么" to slip.what,
            "当时授权了什么" to slip.authorized,
            "风险" to slip.risk,
            "回滚点与影响面" to slip.rollbackPoint,
        ).filter { it.second.isBlank() }.map { it.first }
        if (missing.isNotEmpty()) {
            return "撤销单缺项（缺：${missing.joinToString("、")}）⇒ 拒收：五栏缺一不可"
        }
        return null
    }
}
