// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十六批 P2 · 法典卷八 **L25 窄门自执行**——人不在场时唯一能自己动手的门。
//
// 出处：第二百七十六批派工单 P2（L25 四条件缺一退回「停下等人」、含删除一律拒、30 天 >3 次自动关闭）；
// 判据原文＝法典卷八 L25（私有侧《抱朴-法典》；不在本库内，别按库内路径找）。
// 本文件做的事：把 271 批写在测试侧的 L25 判据（law/spec/L25NarrowSpec.kt，随本批删除）
// 搬进 `app/src/main`——判据文案逐字不动，用例名照 spec 承诺原样升格（#271-L25 销账）。
//
// ## 判据（输入→输出，逐条可执行）
// 自执行要成立，**四条件同时满足**，缺一条就退回「停下等人」：
//   ① 有快照/回滚点（[NarrowRequest.hasSnapshot]，可完全回滚，配 [NarrowRequest.reversible]）；
//   ② 动作在 L25 白名单（[NarrowGate.WHITELIST]：回滚/隔离/暂停服务/冻结写入）；
//   ③ 人 **5 分钟内无应答**（[NarrowRequest.humanSilentMs]，门槛 [NarrowGate.SILENCE_MS]）；
//   ④ 动作**时长 ≤5 分钟**（[NarrowRequest.actionMinutes]，上限 [NarrowGate.MAX_ACTION_MINUTES]）。
// 另外两条硬边界：**含"删除"必拒**（永久排除，不进白名单）；
// **30 天内该通道 >3 次 ⇒ 通道自动关闭**（[NarrowGate.MAX_USES_30D]），降回"必须等人"，重开须御批。
//
// ## 为什么删除永远进不了白名单
// 白名单里的四件事都能在事后被追认或撤销（L22 撤销单兜底）；删除不可逆，再快的追认也补不回数据——
// 所以它不是"门槛不够高"，是**根本不许进这道门**，判据连看都不看它第二眼。
//
// ## 为什么只有判定
// 自执行本身由执行层做（快照、隔离、冻结都在那边）：本层零 IO、零时钟，请求由调用方给，
// 本层只把"这次能不能自己动手"一次算清，并在放行话术里提醒自执行后必须出 L22 撤销单。
package com.example.zhengdao.baopu.law

/** L25 的两种结局：四条件齐才自执行，缺一退回停下等人。 */
internal enum class NarrowOutcome {
    SELF_EXECUTE,
    STOP_AND_WAIT,
}

/** L25 窄门的请求（六个字段；四条件读数＋30 天台账由调用方给）。 */
internal data class NarrowRequest(
    val action: String,
    /** 有快照/回滚点（可完全回滚）。 */
    val hasSnapshot: Boolean = true,
    /** 动作本身可不可逆。 */
    val reversible: Boolean = true,
    /** 人已经多久没应答（毫秒）。 */
    val humanSilentMs: Long = 6 * 60_000L,
    /** 这次动作预计持续多久（分钟）。 */
    val actionMinutes: Int = 3,
    /** 近 30 天该通道已自执行次数。 */
    val window30dCount: Int = 0,
)

/** L25 窄门的唯一判定入口（`judge`＝这次能不能自己动手）。 */
internal object NarrowGate {

    /** 白名单（法典原文：回滚、隔离、暂停服务、冻结写入）。 */
    val WHITELIST: Set<String> = setOf("rollback", "isolate", "pause_service", "freeze_write")

    /** 人无应答的门槛：5 分钟。 */
    const val SILENCE_MS = 5 * 60_000L

    /** 单次动作时长上限：5 分钟。 */
    const val MAX_ACTION_MINUTES = 5

    /** 30 天通道次数上限（>3 即关）。 */
    const val MAX_USES_30D = 3

    /** 永久排除的动作（删除不可逆，再快的追认也补不回数据）。 */
    private val NEVER: Set<String> = setOf("delete", "purge", "empty_trash")

    fun judge(r: NarrowRequest): Pair<NarrowOutcome, String> = when {
        r.action.lowercase() in NEVER || r.action.contains("删除") ->
            NarrowOutcome.STOP_AND_WAIT to "删除永久排除在窄门之外：不可逆的事只能等人"
        r.action.lowercase() !in WHITELIST ->
            NarrowOutcome.STOP_AND_WAIT to "动作「${r.action}」不在 L25 白名单（回滚/隔离/暂停服务/冻结写入）"
        !r.hasSnapshot || !r.reversible ->
            NarrowOutcome.STOP_AND_WAIT to "没有快照/回滚点：窄门只做能完全回滚的动作"
        r.humanSilentMs < SILENCE_MS ->
            NarrowOutcome.STOP_AND_WAIT to "人还没到 5 分钟无应答：先等"
        r.actionMinutes > MAX_ACTION_MINUTES ->
            NarrowOutcome.STOP_AND_WAIT to "动作时长 ${r.actionMinutes} 分钟超过 5 分钟上限"
        r.window30dCount >= MAX_USES_30D ->
            NarrowOutcome.STOP_AND_WAIT to "30 天内已用 ${r.window30dCount} 次：通道自动关闭，重开须御批"
        else -> NarrowOutcome.SELF_EXECUTE to "四条件齐：自执行一次（2 小时内必须出 L22 撤销单）"
    }
}
