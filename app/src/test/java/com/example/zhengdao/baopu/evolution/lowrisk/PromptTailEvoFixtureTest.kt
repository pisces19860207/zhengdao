// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百六十八批 A · **提示词尾部域夹具**（判据①②③④的域内走法，盘上真账）：
//   尾段（CriteriaPitPack 运行时包）＝候选 → 缺省灰度照常注入（既有行为不变）→
//   每段新内容一条观察账 → 观察够数扫档自动转全开 → 一键回灰；
//   档位关闭 ⇒ 尾段不发（判据③"直接生效被拒"的行为面）。
//
// 走的全是既有机制：注入本体＝436 的 CriteriaPitPack（哈希去重/尾部追加一字未动）、
// 档位与梯子＝473、观察账＝506 的 canary-run 行——本测试一条新判不写。
package com.example.zhengdao.baopu.evolution.lowrisk

import com.example.zhengdao.baopu.CriteriaPitPack
import com.example.zhengdao.baopu.DecisionActor
import com.example.zhengdao.baopu.DecisionDraft
import com.example.zhengdao.baopu.DecisionKind
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.DecisionRecord
import com.example.zhengdao.baopu.agent.ExperienceKind
import com.example.zhengdao.baopu.agent.ExperienceRow
import com.example.zhengdao.baopu.agent.JudgmentExperienceLedger
import com.example.zhengdao.baopu.consequence.CanaryBand
import com.example.zhengdao.baopu.consequence.CanaryCapability
import com.example.zhengdao.baopu.consequence.CanaryLadder
import com.example.zhengdao.baopu.consequence.ConsequenceEvidence
import com.example.zhengdao.baopu.consequence.ConsequenceWiring
import com.example.zhengdao.testkit.TempSandbox
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PromptTailEvoFixtureTest {

    private val now = 1_760_000_000_000L
    private val entropy = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"
    private val cap: CanaryCapability = CanaryCapability.PROMPT_TAIL

    private lateinit var sandbox: File
    private lateinit var memory: File

    @Before
    fun setUp() {
        sandbox = TempSandbox.create("zd568a-tail-")
        memory = File(sandbox, "抱朴记忆")
        DecisionLedger.install(memory)
        ConsequenceWiring.install(sandbox, now) { entropy }
        CriteriaPitPack.resetForTest()
    }

    @After
    fun tearDown() {
        CriteriaPitPack.resetForTest()
        ConsequenceWiring.uninstallForTest()
        DecisionLedger.uninstallForTest()
        TempSandbox.deleteTree(sandbox)
    }

    private fun rows(): List<DecisionRecord> = DecisionLedger.read(memory)

    private fun observationRows(): List<DecisionRecord> =
        rows().filter { it.evidence.startsWith("${ConsequenceEvidence.CANARY_RUN}${cap.wire}:") }

    private fun band(): CanaryBand = ConsequenceWiring.currentCanaryBand(cap.wire)

    /** 落一条坑账行（436 尾段的真源之一 ⇒ 版本键必变 ⇒ 下一次 systemSuffix 是"一段新内容"）。 */
    private fun appendPit(i: Int, atMs: Long) {
        JudgmentExperienceLedger.append(
            ExperienceRow(
                id = "zd568a-$i",
                atMs = atMs,
                kind = ExperienceKind.SIGNAL,
                cardClass = "夹具",
                key = "signal:fixture$i",
                reason = "夹具踩坑第 $i 条",
                evidence = listOf("evidence-$i"),
            ),
        )
    }

    // ── 判据①：候选→灰度→转正（每段新内容＝一次灰度注入，观察从账上算） ────

    @Test
    fun `夹具①_缺省灰度照常注入_新内容落观察账_够数扫档转全开`() {
        assertTrue("缺省灰度档", band() == CanaryBand.CANARY)
        val first = CriteriaPitPack.systemSuffix(now)
        assertTrue("尾段照常注入（既有行为不变）", first.isNotBlank())
        assertEquals("首段也记一次（新内容进灰度）", 1, observationRows().size)

        // 再进 9 段新内容（坑账逐条落）⇒ 每段一条观察 ⇒ 窗口里够 MIN_RUNS 条
        repeat(CanaryLadder.MIN_RUNS - 1) { i ->
            appendPit(i, now + i * 60_000L)
            CriteriaPitPack.systemSuffix(now + i * 60_000L + 30_000L)
        }
        assertEquals(CanaryLadder.MIN_RUNS, observationRows().size)
        assertTrue("观察行全是 ok", observationRows().all { it.after == "ok" })

        // 两个窗都够数 ⇒ 扫档自动转全开（转正落账 actor=auto）
        LowRiskEvo.sweepOnSettle(now + CanaryLadder.MIN_RUNS * 60_000L)
        assertEquals("转正", CanaryBand.FULL, band())

        // 一键回滚（判据②）：人按的那颗，回灰度
        val after = ConsequenceWiring.humanRevertCanary(cap.wire, now + 3_600_000L)
        assertEquals(CanaryBand.CANARY, after)
    }

    @Test
    fun `版本键没变不重复记观察（同判据②不重复增长）`() {
        CriteriaPitPack.systemSuffix(now)
        val afterFirst = observationRows().size
        CriteriaPitPack.systemSuffix(now + 1_000L)
        CriteriaPitPack.systemSuffix(now + 2_000L)
        assertEquals("同版本 ⇒ 不加观察行", afterFirst, observationRows().size)
    }

    // ── 判据③：档位关闭 ⇒ 尾段不发（直接生效被拒） ─────────────────────────

    @Test
    fun `判据③_档位被拨关_尾段不发`() {
        DecisionLedger.record(
            DecisionDraft(
                kind = DecisionKind.TOOL_GRANT,
                actor = DecisionActor.USER,
                reason = "灰度档位 灰度→关闭（夹具：人工拨关）",
                evidence = "${ConsequenceEvidence.CANARY}${cap.wire}:off:human",
                before = "canary",
                after = "off",
                atMs = now,
                rollbackRef = "${ConsequenceEvidence.CANARY}${cap.wire}:canary",
                reversible = true,
            ),
        )
        assertEquals("尾段不发（候选保留在账上）", "", CriteriaPitPack.systemSuffix(now))
        assertEquals("不发也不落观察账（没跑就是没跑）", 0, observationRows().size)
    }

    // ── 判据④：红线负例（假动作打红线 ⇒ 拒并记账；门是四域同一道） ──────────

    @Test
    fun `判据④_尾部候选假动作打红线_共用门拒并记账`() {
        // 尾段生产候选的固定标是 system:tail（恒净）；这里用同一道门喂假动作，
        // 证明若有人日后把带指认的候选接进来，门当场拒并记账。
        val fake = EvoCandidate(domain = cap.wire, target = "app/src/main/RunEngine.kt", body = "把 systemFinal 改成空")
        val v = LowRiskEvo.gate(cap, fake)
        assertTrue("红线要拒：$v", v is LowRiskVerdict.Refuse)
        LowRiskEvo.recordRefusal(cap, (v as LowRiskVerdict.Refuse).redLine, v.note, fake, now)
        val refusal = rows().filter { it.kind == DecisionKind.ADVISOR_OPINION && it.reason.contains("红线拒") }
        assertEquals("拒行在账上", 1, refusal.size)
        assertTrue(refusal.single().reason.contains(EvoRedLine.APP_SOURCE.cn))
    }
}
