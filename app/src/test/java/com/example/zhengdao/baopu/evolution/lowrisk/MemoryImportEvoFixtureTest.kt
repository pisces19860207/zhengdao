// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百六十八批 A · **记忆组织域夹具**（判据①②③④的域内走法，盘上真账）：
//   一次自动收编＝候选 → 缺省灰度放行（既有行为不变）→ runImportOnce 真跑 →
//   结局一条观察账 → 观察够数扫档自动转全开 → 一键回灰；
//   档位关闭 ⇒ 不自动扫（候选保留）；红线假动作 ⇒ 共用门拒并记账。
//
// 走的全是既有机制：收编本体＝451/455 的 runImportOnce（幂等/不覆盖人工版一字未动）、
// 档位与梯子＝473、观察账＝506 的 canary-run 行——本测试一条新判不写。
// Context 胶水不可 JVM 直测（本仓无 Robolectric，455 同款口径）：
// 夹具走生产同款三步（gate → runImportOnce → observeRun），接线顺序由源级门禁钉（G5）。
package com.example.zhengdao.baopu.evolution.lowrisk

import com.example.zhengdao.baopu.DecisionActor
import com.example.zhengdao.baopu.DecisionDraft
import com.example.zhengdao.baopu.DecisionKind
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.DecisionRecord
import com.example.zhengdao.baopu.consequence.CanaryBand
import com.example.zhengdao.baopu.consequence.CanaryCapability
import com.example.zhengdao.baopu.consequence.CanaryLadder
import com.example.zhengdao.baopu.consequence.ConsequenceEvidence
import com.example.zhengdao.baopu.consequence.ConsequenceWiring
import com.example.zhengdao.baopu.memoryimport.FreeTextMemoryWiring
import com.example.zhengdao.baopu.memoryimport.MemoryImportRules
import com.example.zhengdao.testkit.TempSandbox
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MemoryImportEvoFixtureTest {

    private val now = 1_760_000_000_000L
    private val entropy = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"
    private val cap: CanaryCapability = CanaryCapability.MEMORY_IMPORT

    private lateinit var sandbox: File
    private lateinit var memory: File
    private lateinit var globalSrc: File
    private lateinit var projectSrc: File
    private lateinit var globalOut: File
    private lateinit var projectOut: File
    private lateinit var staging: File

    @Before
    fun setUp() {
        sandbox = TempSandbox.create("zd568a-mem-")
        memory = File(sandbox, "抱朴记忆")
        globalSrc = File(sandbox, "全局记忆.md").apply { writeText(GLOBAL_TEXT) }
        projectSrc = File(sandbox, "项目记忆.md").apply { writeText(PROJECT_TEXT) }
        globalOut = File(sandbox, "global-out")
        projectOut = File(sandbox, "project-out")
        staging = File(sandbox, "staging")
        DecisionLedger.install(memory)
        ConsequenceWiring.install(sandbox, now) { entropy }
    }

    @After
    fun tearDown() {
        ConsequenceWiring.uninstallForTest()
        DecisionLedger.uninstallForTest()
        TempSandbox.deleteTree(sandbox)
    }

    private fun rows(): List<DecisionRecord> = DecisionLedger.read(memory)

    private fun observationRows(): List<DecisionRecord> =
        rows().filter { it.evidence.startsWith("${ConsequenceEvidence.CANARY_RUN}${cap.wire}:") }

    private fun band(): CanaryBand = ConsequenceWiring.currentCanaryBand(cap.wire)

    /** 生产同款三步（FreeTextMemoryWiring.autoRun 的顺序；接线由源级门禁 G5 钉）。 */
    private fun gateThenImport(atMs: Long): LowRiskVerdict {
        val candidate = EvoCandidate(
            domain = cap.wire,
            target = "memory-import:运行时目录",
        )
        val v = LowRiskEvo.gate(cap, candidate)
        if (v !is LowRiskVerdict.Allow) {
            if (v is LowRiskVerdict.Refuse) {
                LowRiskEvo.recordRefusal(cap, v.redLine, v.note, candidate, atMs)
            }
            return v
        }
        val outcome = FreeTextMemoryWiring.runImportOnce(
            globalSource = globalSrc,
            projectSource = projectSrc,
            globalOut = globalOut,
            projectOut = projectOut,
            stagingRoot = staging,
            log = {},
        )
        LowRiskEvo.observeRun(cap, ok = MemoryImportRules.shouldStamp(outcome), atMs = atMs)
        return v
    }

    // ── 判据①：候选→灰度→转正（一次自动收编＝一条观察，够数扫档转全开） ────

    @Test
    fun `夹具①_缺省灰度放行_收编真跑并落观察_够数扫档转全开`() {
        assertTrue("缺省灰度档", band() == CanaryBand.CANARY)
        repeat(CanaryLadder.MIN_RUNS) { i ->
            val v = gateThenImport(now + i * 60_000L)
            assertTrue("第 ${i + 1} 轮放行：$v", v is LowRiskVerdict.Allow)
        }
        assertEquals("每轮一条观察（收编幂等 ⇒ uses 不虚涨，观察不虚涨）", CanaryLadder.MIN_RUNS, observationRows().size)
        assertTrue("干净收编全 ok", observationRows().all { it.after == "ok" })
        assertTrue("收编真的出了条目", projectOut.walk().any { it.isFile } || globalOut.walk().any { it.isFile })

        LowRiskEvo.sweepOnSettle(now + CanaryLadder.MIN_RUNS * 60_000L)
        assertEquals("转正", CanaryBand.FULL, band())
        val after = ConsequenceWiring.humanRevertCanary(cap.wire, now + 3_600_000L)
        assertEquals("一键回滚（判据②）", CanaryBand.CANARY, after)
    }

    @Test
    fun `失败收编记fail观察_读不动的源不算干净`() {
        // 源文件内容合法但 readOk 探针说读不动 ⇒ 455 口径"这轮不算导过" ⇒ 观察 ok=false
        val candidate = EvoCandidate(domain = cap.wire, target = "memory-import:运行时目录")
        assertTrue(LowRiskEvo.gate(cap, candidate) is LowRiskVerdict.Allow)
        val outcome = FreeTextMemoryWiring.runImportOnce(
            globalSource = globalSrc,
            projectSource = projectSrc,
            globalOut = globalOut,
            projectOut = projectOut,
            stagingRoot = staging,
            log = {},
            readOk = { false },
        )
        assertFalse(MemoryImportRules.shouldStamp(outcome))
        LowRiskEvo.observeRun(cap, ok = false, atMs = now)
        assertEquals("fail", observationRows().single().after)
    }

    // ── 判据③：档位关闭 ⇒ 不自动扫（直接生效被拒） ─────────────────────────

    @Test
    fun `判据③_档位被拨关_自动收编不出门`() {
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
        val v = gateThenImport(now)
        assertTrue("关闭档只出 Hold：$v", v is LowRiskVerdict.Hold)
        assertEquals("没跑就没观察账", 0, observationRows().size)
        assertFalse("没跑就没收编产物", globalOut.walk().any { it.isFile } || projectOut.walk().any { it.isFile })
    }

    // ── 判据④：红线负例（假动作 ⇒ 拒并记账） ────────────────────────────────

    @Test
    fun `判据④_收编候选假动作打A内核红线_拒并记账`() {
        val fake = EvoCandidate(
            domain = cap.wire,
            target = "memory-import:执行体/身份表.json",
            body = "把身份表整份收编进结构化存储",
        )
        val v = LowRiskEvo.gate(cap, fake)
        assertTrue("A 内核红线要拒：$v", v is LowRiskVerdict.Refuse)
        LowRiskEvo.recordRefusal(cap, (v as LowRiskVerdict.Refuse).redLine, v.note, fake, now)
        val refusal = rows().filter { it.kind == DecisionKind.ADVISOR_OPINION && it.reason.contains("红线拒") }
        assertEquals(1, refusal.size)
        assertTrue(refusal.single().reason.contains(EvoRedLine.KERNEL.cn))
    }

    private companion object {
        /** 与 MemoryImportWiringTest 同款源形状（什么形状都有的手写记忆）。 */
        private val GLOBAL_TEXT = """
            # 全局记忆（手写）
            - 偏好：回答先给结论
            术语：抱朴 = 本 App 的 agent 名字
        """.trimIndent()

        private val PROJECT_TEXT = """
            规矩：报告只落证道设计报告目录
            口径：行数对账不许四舍五入
        """.trimIndent()
    }
}
