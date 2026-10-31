// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百一十三批 · GapItem／右相回灌的行为测试（判据②③④⑥的运行时面；
// 源级面在 GapFeedbackGateTest，采样四触发在 JinwuPatrolTest）。
package com.example.zhengdao.baopu.verify

import com.example.zhengdao.baopu.scout.UnfinishedLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GapItemTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUp() {
        GapFeedback.resetForTest()
    }

    // ── 判据③：同项两来源合并为一条 ──────────────────────────────────────────

    @Test
    fun `判据③_同项两来源合并为一条`() {
        GapFeedback.record(listOf(gap(GapSource.JINWU, evidence = listOf("step1 tool=write_file args=a:b code=failed"))))
        GapFeedback.record(listOf(gap(GapSource.RIGHT, evidence = listOf("验收判据:C2声称与事实一致"))))
        val pending = GapFeedback.pending()
        assertEquals("同项只许一行", 1, pending.size)
        assertEquals("attempts 累加", 2, pending[0].attempts)
        assertEquals("来源取在先的一家", GapSource.JINWU, pending[0].source)
        assertEquals("两家的证据都在", 2, pending[0].evidence.size)
    }

    @Test
    fun `异项不合并_不同target各占一行`() {
        GapFeedback.record(listOf(gap(target = "action:a")))
        GapFeedback.record(listOf(gap(target = "action:b")))
        assertEquals(2, GapFeedback.pending().size)
    }

    // ── 排序：按 attempts 降序 ────────────────────────────────────────────────

    @Test
    fun `回灌按attempts降序_最顽的先说`() {
        GapFeedback.record(listOf(gap(target = "plan:1")))
        GapFeedback.record(listOf(gap(target = "plan:2")))
        GapFeedback.record(listOf(gap(target = "plan:2")))
        val round = GapFeedback.round(nowMs = 1_000L)
        assertTrue(round.feedback.first().contains("plan:2"))
        assertTrue(round.feedback.last().contains("plan:1"))
    }

    // ── 判据④：第 3 次 ⇒ 交人 ────────────────────────────────────────────────

    @Test
    fun `判据④_第3次交人不回灌_未办成单落行`() {
        val dirs = listOf(tmp.newFolder("mem"))
        GapFeedback.attach(dirs)
        repeat(3) { GapFeedback.record(listOf(gap())) }
        val round = GapFeedback.round(nowMs = 1_000L)
        assertTrue("第 3 次不许再回灌", round.feedback.isEmpty())
        assertEquals(1, round.handedOver.size)
        assertTrue("板上销账", GapFeedback.pending().isEmpty())
        val rows = UnfinishedLedger(dirs).load()
        assertEquals("交人进未办成单", 1, rows.size)
        assertTrue("reasonRef 可点回差距", rows[0].reasonRef.startsWith("gap:tool_misuse|"))
        assertEquals("补法跟着差距走", gap().minimalFix, rows[0].fixHint)
    }

    @Test
    fun `判据④_第2次仍回灌_不交人`() {
        val dirs = listOf(tmp.newFolder("mem2"))
        GapFeedback.attach(dirs)
        repeat(2) { GapFeedback.record(listOf(gap())) }
        val round = GapFeedback.round(nowMs = 1_000L)
        assertEquals(1, round.feedback.size)
        assertTrue(round.handedOver.isEmpty())
        assertTrue(UnfinishedLedger(dirs).load().isEmpty())
    }

    @Test
    fun `判据④_未装配落点_交人照常出在返回值_单不落只log`() {
        repeat(3) { GapFeedback.record(listOf(gap())) }
        val round = GapFeedback.round(nowMs = 1_000L)
        assertEquals(1, round.handedOver.size)
        assertTrue(round.feedback.isEmpty())
    }

    // ── 判据⑥：回灌无价值判断词（运行时面） ──────────────────────────────────

    @Test
    fun `判据⑥_行里带价值词即转交人不回灌`() {
        GapFeedback.record(listOf(gap(evidence = listOf("对象:有人在证据里夹了建议"))))
        val round = GapFeedback.round(nowMs = 1_000L)
        assertTrue("带价值词的行不许回灌", round.feedback.isEmpty())
        assertEquals("转交人", 1, round.handedOver.size)
    }

    @Test
    fun `判据⑥_最小满足条件带禁词_构造即红`() {
        val err = runCatching {
            gap(minimalFix = "你应该先备份再重试")
        }.exceptionOrNull()
        assertTrue("构造期就拦", err is IllegalArgumentException)
    }

    // ── 判据②：产出必为 GapItem（右相面；回灌行逐条可溯源） ──────────────────

    @Test
    fun `判据②_右相发现逐条映射为GapItem_判据映射写死`() {
        val report = ChancellorReport(
            verdict = ChancellorVerdict.FAIL,
            findings = listOf(
                ChancellorFinding(ChancellorGate.C0_URGENT, "urgent=true", "紧急卡不进本闸"),
                ChancellorFinding(ChancellorGate.C1_USABLE, "产物/x.md", "交付物不存在"),
                ChancellorFinding(ChancellorGate.C2_LEDGER, "产物/x.md", "声称写了但账上没有"),
                ChancellorFinding(ChancellorGate.C3_CONTRACT, "技能/y", "契约不齐（缺 success）"),
                ChancellorFinding(ChancellorGate.C4_LEARNING, "技能/y", "③撞法条（L21）"),
                ChancellorFinding(ChancellorGate.C4_LEARNING, "技能/y", "契约缺失（一条 contract 行都没有）"),
                ChancellorFinding(ChancellorGate.C5_OPINION, "patrol/MAJOR/问题", "非建议级异议（法条 L7）"),
            ),
            notes = emptyList(),
            missing = emptyList(),
            directPass = false,
            elapsedMs = 1L,
        )
        val gaps = GapFeedback.chancellorGapsOf(report)
        assertEquals("每条确定发现都成差距", 7, gaps.size)
        assertEquals(
            listOf(
                GapKind.TOOL_MISUSE,
                GapKind.MISSING_EVIDENCE,
                GapKind.NO_LEDGER,
                GapKind.SCHEMA_VIOLATION,
                GapKind.GOAL_DRIFT,
                GapKind.SCHEMA_VIOLATION,
                GapKind.GOAL_DRIFT,
            ),
            gaps.map { it.kind },
        )
        gaps.forEach { assertEquals("右相产的差距都带来源", GapSource.RIGHT, it.source) }
    }

    @Test
    fun `判据②_UNVERIFIABLE不回灌_判不了不是确定差距`() {
        val report = ChancellorReport(
            verdict = ChancellorVerdict.UNVERIFIABLE,
            findings = emptyList(),
            notes = emptyList(),
            missing = listOf(ChancellorGate.NO_SAMPLES_LINE),
            directPass = false,
            elapsedMs = 1L,
        )
        assertTrue(GapFeedback.chancellorGapsOf(report).isEmpty())
        assertTrue(GapFeedback.pending().isEmpty())
    }

    @Test
    fun `判据②_回灌行逐条可溯源_没有第二个文本来源`() {
        GapFeedback.record(listOf(gap(target = "action:a")))
        GapFeedback.record(listOf(gap(kind = GapKind.GOAL_DRIFT, target = "plan:3", minimalFix = "先补 plan:3 这一步再继续")))
        val round = GapFeedback.round(nowMs = 1_000L)
        assertEquals("几条差距几行，没有自由文本批评的位置", 2, round.feedback.size)
        assertTrue(round.feedback.any { it.contains("action:a") && it.contains("tool_misuse") })
        assertTrue(round.feedback.any { it.contains("plan:3") && it.contains("goal_drift") })
    }

    // ── 唯一格式的基本形状 ────────────────────────────────────────────────────

    @Test
    fun `差距缺证据不进账_L14同款`() {
        val err = runCatching { gap(evidence = emptyList()) }.exceptionOrNull()
        assertTrue(err is IllegalArgumentException)
    }

    @Test
    fun `gapKey_同kind同target即同项_target去空白`() {
        assertEquals(gap(target = "action:a").gapKey, gap(target = " action:a ").gapKey)
        assertTrue(gap(target = "action:a").gapKey != gap(target = "action:b").gapKey)
    }

    // ── 内件 ─────────────────────────────────────────────────────────────────

    private fun gap(
        source: GapSource = GapSource.JINWU,
        kind: GapKind = GapKind.TOOL_MISUSE,
        target: String = "action:write_file:deadbeef01",
        evidence: List<String> = listOf("step1 tool=write_file args=path:a1b2 code=failed"),
        minimalFix: String = "先查明这个动作反复失败的原因，再换做法",
        attempts: Int = 1,
    ): GapItem = GapItem(source, kind, target, evidence, minimalFix, attempts)
}
