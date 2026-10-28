// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// ⚠️ 本文件是**规范（spec）**，不是门禁：生产未接线（#271-L25），它**不会红**。
//    它钉的是「判据长什么样」；接线那天把判据搬进生产、用例名不变，它才升格为门禁。
//
// 第二百七十一批 · 法典规范（spec，非门禁） **L25 紧急自执行窄门**（法典卷八；2026-10-28 御批准）。
//
// ## 判据（可执行）
// 自执行要成立，**四条件同时满足**，缺一条就退回「停下等人」：
//   ① 有快照/回滚点（可完全回滚）；② 动作在 L25 白名单（回滚/隔离/暂停服务/冻结写入）；
//   ③ 人 **5 分钟内无应答**；④ 动作**时长 ≤5 分钟**。
// 另外两条硬边界：**含"删除"必拒**（永久排除，不进白名单）；**30 天内该通道 >3 次 ⇒ 通道自动关闭**，
// 降回"必须等人"，重开须御批。
//
// ## 生产状态（诚实标注）——**本文件是规范，不是门禁；生产未接线（#271-L25），它不会红**
// 生产码里还没有窄门（`grep 窄门 app/src/main` 零命中）⇒ 判据形状规范，缺陷 #271-L25 在账。
//
// ## 负例（造违规即红——红的是**判据**，不是生产：生产里还没有它）
// 四条件各缺一条、动作是删除、30 天已用 3 次 ⇒ 六种情形都必须退回「停下等人」。
package com.example.zhengdao.law.spec


import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class L25NarrowSpec {

    internal enum class Outcome { SELF_EXECUTE, STOP_AND_WAIT }

    internal data class NarrowRequest(
        val action: String,
        val hasSnapshot: Boolean = true,
        val reversible: Boolean = true,
        /** 人已经多久没应答（毫秒）。 */
        val humanSilentMs: Long = 6 * 60_000L,
        /** 这次动作预计持续多久（分钟）。 */
        val actionMinutes: Int = 3,
        /** 近 30 天该通道已自执行次数。 */
        val window30dCount: Int = 0,
    )

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

        fun judge(r: NarrowRequest): Pair<Outcome, String> = when {
            r.action.lowercase() in NEVER || r.action.contains("删除") ->
                Outcome.STOP_AND_WAIT to "删除永久排除在窄门之外：不可逆的事只能等人"
            r.action.lowercase() !in WHITELIST ->
                Outcome.STOP_AND_WAIT to "动作「${r.action}」不在 L25 白名单（回滚/隔离/暂停服务/冻结写入）"
            !r.hasSnapshot || !r.reversible ->
                Outcome.STOP_AND_WAIT to "没有快照/回滚点：窄门只做能完全回滚的动作"
            r.humanSilentMs < SILENCE_MS ->
                Outcome.STOP_AND_WAIT to "人还没到 5 分钟无应答：先等"
            r.actionMinutes > MAX_ACTION_MINUTES ->
                Outcome.STOP_AND_WAIT to "动作时长 ${r.actionMinutes} 分钟超过 5 分钟上限"
            r.window30dCount >= MAX_USES_30D ->
                Outcome.STOP_AND_WAIT to "30 天内已用 ${r.window30dCount} 次：通道自动关闭，重开须御批"
            else -> Outcome.SELF_EXECUTE to "四条件齐：自执行一次（2 小时内必须出 L22 撤销单）"
        }
    }

    // ── 正例：四条件齐 ─────────────────────────────────────────────────────

    @Test
    fun `L25_正例_四条件齐才自执行一次`() {
        val (outcome, why) = NarrowGate.judge(NarrowRequest(action = "freeze_write"))
        assertEquals("四条件齐应当自执行：$why", Outcome.SELF_EXECUTE, outcome)
        assertTrue("自执行必须提醒事后出撤销单", why.contains("撤销单"))
        // 白名单四种动作都放行
        NarrowGate.WHITELIST.forEach { action ->
            assertEquals(
                "白名单动作 $action 应可自执行",
                Outcome.SELF_EXECUTE,
                NarrowGate.judge(NarrowRequest(action = action)).first,
            )
        }
        // 刚好处在边界上（5 分钟无应答、动作 5 分钟、30 天用了 2 次）仍然成立
        assertEquals(
            Outcome.SELF_EXECUTE,
            NarrowGate.judge(
                NarrowRequest(
                    action = "isolate",
                    humanSilentMs = NarrowGate.SILENCE_MS,
                    actionMinutes = NarrowGate.MAX_ACTION_MINUTES,
                    window30dCount = 2,
                ),
            ).first,
        )
    }

    // ── 负例：缺一条就退回停下等人 ─────────────────────────────────────────

    @Test
    fun `L25_负例_四条件缺一或含删除或通道用满都必须退回停下等人`() {
        val base = NarrowRequest(action = "rollback")
        assertEquals("基线应能自执行（否则下面的对照没意义）", Outcome.SELF_EXECUTE, NarrowGate.judge(base).first)

        assertEquals("没快照", Outcome.STOP_AND_WAIT, NarrowGate.judge(base.copy(hasSnapshot = false)).first)
        assertEquals("不可回滚", Outcome.STOP_AND_WAIT, NarrowGate.judge(base.copy(reversible = false)).first)
        assertEquals("人还没静默 5 分钟", Outcome.STOP_AND_WAIT, NarrowGate.judge(base.copy(humanSilentMs = 60_000L)).first)
        assertEquals("动作超过 5 分钟", Outcome.STOP_AND_WAIT, NarrowGate.judge(base.copy(actionMinutes = 6)).first)
        assertEquals("动作不在白名单", Outcome.STOP_AND_WAIT, NarrowGate.judge(base.copy(action = "write_file")).first)
        assertEquals("30 天用满 3 次 ⇒ 关通道", Outcome.STOP_AND_WAIT, NarrowGate.judge(base.copy(window30dCount = 3)).first)

        // 删除永久排除：即使其余四条全满足也不许
        val delete = NarrowGate.judge(base.copy(action = "delete"))
        assertEquals("含删除必拒", Outcome.STOP_AND_WAIT, delete.first)
        assertTrue("理由要点明删除不可逆：${delete.second}", delete.second.contains("删除"))
        assertEquals(
            "中文「删除」也必须被认出来",
            Outcome.STOP_AND_WAIT,
            NarrowGate.judge(base.copy(action = "删除文件")).first,
        )
    }
}
