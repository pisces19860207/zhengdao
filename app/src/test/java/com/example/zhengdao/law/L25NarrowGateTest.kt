// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十六批 P2 · 法典门禁 **L25 窄门自执行**（法典卷八 L25；2026-10-28 御批准）。
//
// 由 271 批的规范（law/spec/L25NarrowSpec.kt，随本批删除）升格：
// 判据已搬进生产 `baopu/law/NarrowGate.kt`，用例名照 spec 承诺逐字不变（#271-L25 销账）。
//
// ## 判据（可执行，判的是生产 NarrowGate.judge）
// 自执行要成立，**四条件同时满足**，缺一条就退回「停下等人」：
//   ① 有快照/回滚点（可完全回滚）；② 动作在 L25 白名单（回滚/隔离/暂停服务/冻结写入）；
//   ③ 人 **5 分钟内无应答**；④ 动作**时长 ≤5 分钟**。
// 另外两条硬边界：**含"删除"必拒**（永久排除，不进白名单）；**30 天内该通道 >3 次 ⇒ 通道自动关闭**，
// 降回"必须等人"，重开须御批。
//
// ## 负例（造违规即红，红在生产 judge 上）
// 四条件各缺一条、动作是删除、30 天已用 3 次 ⇒ 六种情形都必须退回「停下等人」。
package com.example.zhengdao.law

import com.example.zhengdao.baopu.law.NarrowGate
import com.example.zhengdao.baopu.law.NarrowOutcome
import com.example.zhengdao.baopu.law.NarrowRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class L25NarrowGateTest {

    // ── 正例：四条件齐 ─────────────────────────────────────────────────────

    @Test
    fun `L25_正例_四条件齐才自执行一次`() {
        val (outcome, why) = NarrowGate.judge(NarrowRequest(action = "rollback"))
        assertEquals("四条件齐应当自执行：$why", NarrowOutcome.SELF_EXECUTE, outcome)
        assertTrue("自执行必须提醒事后出撤销单", why.contains("撤销单"))
        // 2026-10-29 御裁：2 小时初稿 → 24 小时定稿，两句话都留、不合一个数
        assertTrue("放行话术必须点名「初稿」：$why", why.contains("初稿"))
        assertTrue("放行话术必须留 24h 违规线：$why", why.contains("24h 违规线"))
        // 白名单四种动作都放行（法典点名，一个不许少）
        listOf("rollback", "isolate", "pause_service", "freeze_write").forEach { action ->
            assertEquals(
                "白名单动作 $action 应可自执行",
                NarrowOutcome.SELF_EXECUTE,
                NarrowGate.judge(NarrowRequest(action = action)).first,
            )
        }
        // 刚好处在边界上（5 分钟无应答、动作 5 分钟、30 天用了 2 次）仍然成立
        assertEquals(
            NarrowOutcome.SELF_EXECUTE,
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
        assertEquals("基线应能自执行（否则下面的对照没意义）", NarrowOutcome.SELF_EXECUTE, NarrowGate.judge(base).first)

        assertEquals("没快照", NarrowOutcome.STOP_AND_WAIT, NarrowGate.judge(base.copy(hasSnapshot = false)).first)
        assertEquals("不可回滚", NarrowOutcome.STOP_AND_WAIT, NarrowGate.judge(base.copy(reversible = false)).first)
        assertEquals("人还没静默 5 分钟", NarrowOutcome.STOP_AND_WAIT, NarrowGate.judge(base.copy(humanSilentMs = 60_000L)).first)
        assertEquals("动作超过 5 分钟", NarrowOutcome.STOP_AND_WAIT, NarrowGate.judge(base.copy(actionMinutes = 6)).first)
        assertEquals("动作不在白名单", NarrowOutcome.STOP_AND_WAIT, NarrowGate.judge(base.copy(action = "write_file")).first)
        assertEquals("30 天用满 3 次 ⇒ 关通道", NarrowOutcome.STOP_AND_WAIT, NarrowGate.judge(base.copy(window30dCount = 3)).first)

        // 删除永久排除：即使其余四条全满足也不许
        val delete = NarrowGate.judge(base.copy(action = "delete"))
        assertEquals("含删除必拒", NarrowOutcome.STOP_AND_WAIT, delete.first)
        assertTrue("理由要点明删除不可逆：${delete.second}", delete.second.contains("删除"))
        assertEquals(
            "中文「删除」也必须被认出来",
            NarrowOutcome.STOP_AND_WAIT,
            NarrowGate.judge(base.copy(action = "删除文件")).first,
        )
    }
}
