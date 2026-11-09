// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百六十三批 · 深度三档「改读 495 评估账」的门禁。
//
// 517 把深度裁定接进生产时，右相证据只认「本场有没有合格的四条验证读数」；本批再挂一本
// 495 评估账（`evolution/trigger-ledger.jsonl`，304 的触发账，带 run/passed/passRate）。
// 四件事必须测得出来：
//   ① 判定输入可追 495 行：定档行的 `rd` 里要留下账上那一行的地址（at/type/ref/ran/pass），
//      拿 at 回账上一找就能找到原行；反过来，账没了就**不许**说证据足；
//   ② 两本旧账一字未改：尝试账仍 7 列、三个触发名仍那三个、一次定档仍只落一行；
//   ③ `latest()` 不被顶掉：读账是纯读，读多少遍都不许把当前档位换成新的一行；
//   ④ 历史深度账仍能读：老格式金样例照样 parse，且读它不回填不改写。
package com.example.zhengdao.baopu.evolution

import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.evolution.trigger.TriggerLedgerStore
import com.example.zhengdao.baopu.evolution.trigger.TriggerRow
import com.example.zhengdao.baopu.evolution.trigger.TriggerType
import com.example.zhengdao.baopu.policy.AnchorCase
import com.example.zhengdao.baopu.policy.CriteriaCandidate
import com.example.zhengdao.baopu.policy.CriteriaOrigin
import com.example.zhengdao.baopu.policy.CriteriaOutputForm
import com.example.zhengdao.baopu.policy.EvolutionAttemptRow
import com.example.zhengdao.baopu.policy.GapTrigger
import com.example.zhengdao.baopu.policy.ReplayProof
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EvolutionDepthEvalFeedTest {

    private lateinit var root: File
    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        root = Files.createTempDirectory("zd563-depth-eval-").toFile()
        DecisionLedger.install(root)
        EvolutionWiring.resetForTest()
        EvolutionDepth.resetForTest()
        DepthFactsWiring.resetForTest()
        DepthEvalFeed.resetForTest()
    }

    @After
    fun tearDown() {
        DepthEvalFeed.resetForTest()
        DepthFactsWiring.resetForTest()
        EvolutionDepth.resetForTest()
        EvolutionWiring.resetForTest()
        DecisionLedger.uninstallForTest()
        root.deleteRecursively()
    }

    // ── 判据① 判定输入可追 495 行 ───────────────────────────────────────────

    @Test
    fun `判据一_定档行的读数里有495账那一行的地址_回账能对上原行`() {
        // 用**真编码器**造账（字段名漂移由这一跳守住：TriggerRowCodec.encode → DepthEvalFeed.read）
        val ledger = File(root, "evolution/trigger-ledger.jsonl")
        val older = evalRow(atMs = now - 90_000, ran = 4, passed = 1, rate = 0.25, ref = "manual")
        val newer = evalRow(atMs = now - 30_000, ran = 8, passed = 6, rate = 0.75, ref = "task:run-563")
        TriggerLedgerStore(ledger).append(older)
        TriggerLedgerStore(ledger).append(newer)
        DepthEvalFeed.ledgerFile = { ledger }

        runChain()

        val d = DepthLedger.replay().last()
        assertEquals("账上有读数 ⇒ 留地址", newer.atMs.toString(), d.readings["evalAt"])
        assertEquals(newer.type.wire, d.readings["evalType"])
        assertEquals("8", d.readings["evalRan"])
        assertEquals("0.75", d.readings["evalPass"])
        assertEquals("task:run-563", d.readings["evalRef"])
        assertEquals("四条读数齐＋本机评过 ⇒ 证据足", "true", d.readings["evidenceEnough"])
        // 可追：拿 at 回账上找，原行还在
        val hit = TriggerLedgerStore(ledger).read().filter { it.atMs.toString() == d.readings["evalAt"] }
        assertEquals("按 at 必须能在 495 账上找回原行", 1, hit.size)
        assertEquals("找回的那行就是它", newer.ref, hit.first().ref)
    }

    @Test
    fun `判据一_495账读不到时_即使四条读数齐也不许说证据足`() {
        // 装配缝没装（生产里等于账根没挂/文件不在）⇒ 右相证据不足 ⇒ 停在浅档。
        runChain()
        val d = DepthLedger.replay().last()
        assertEquals("没评估账 ⇒ rd 的 evalAt 留空（不是 0）", "", d.readings["evalAt"])
        assertEquals("没评估账 ⇒ 证据不足", "false", d.readings["evidenceEnough"])
        assertEquals("证据不足 ⇒ 浅档", DepthTier.SHALLOW, d.depth)
    }

    @Test
    fun `判据一_账里只有被限频挡下的行或零例行_仍算没读数`() {
        val ledger = File(root, "evolution/trigger-ledger.jsonl")
        val store = TriggerLedgerStore(ledger)
        // 被限频挡下（triggered=false，必须带抑制原因）
        store.append(
            TriggerRow(
                atMs = now - 60_000,
                type = TriggerType.RUN_FAILED,
                triggered = false,
                ref = "task:skipped",
                intent = "同日再失败不触发",
                suppressReason = "同日已触发过",
            ),
        )
        // 跑了但 0 例（没跑出读数）
        store.append(evalRow(atMs = now - 30_000, ran = 0, passed = 0, rate = null, ref = "task:zero"))
        DepthEvalFeed.ledgerFile = { ledger }

        runChain()
        val d = DepthLedger.replay().last()
        assertEquals("挡下行与零例行都不是读数", "", d.readings["evalAt"])
        assertEquals("false", d.readings["evidenceEnough"])
    }

    // ── 判据② 两本旧账一字未改（对拍） ──────────────────────────────────────

    @Test
    fun `判据二_尝试账仍是七列闭集_三个触发名一字未改`() {
        val row = EvolutionAttemptRow(
            triggerKind = EvolutionAttemptRow.TRIGGER_GAP,
            basis = "依据一句话",
            rulingRef = "御批-563",
            verdict = EvolutionAttemptRow.VERDICT_PASS,
            rolledBack = false,
            reason = "原因一句话",
            atMs = now,
        )
        val cols = row.wire().split("|")
        assertEquals("七列：触发名|依据|御批编号|通过与否|回滚没|原因|时刻", 7, cols.size)
        assertEquals(
            listOf(
                "GAP", "依据一句话", "御批-563", "通过", "未回滚", "原因一句话", now.toString(),
            ),
            cols,
        )
        assertEquals(
            "三个触发名一字未改",
            setOf("MISJUDGMENT", "HUMAN_ORDER", "GAP"),
            EvolutionAttemptRow.TRIGGER_KINDS,
        )
        assertEquals("判决仍是两态闭集", setOf("通过", "失败"), EvolutionAttemptRow.VERDICTS)
    }

    @Test
    fun `判据二_一次定档只落一行_读数再多也不许多落`() {
        val ledger = File(root, "evolution/trigger-ledger.jsonl")
        TriggerLedgerStore(ledger).append(evalRow(now - 10_000, 5, 5, 1.0, "task:one"))
        DepthEvalFeed.ledgerFile = { ledger }
        val before = DepthLedger.replay().size
        runChain()
        assertEquals("一场链＝一次定档＝一行", before + 1, DepthLedger.replay().size)
    }

    // ── 判据③ latest() 不被顶掉（负例） ────────────────────────────────────

    @Test
    fun `判据三_读一万遍495账也不许把当前档位顶掉`() {
        val ledger = File(root, "evolution/trigger-ledger.jsonl")
        TriggerLedgerStore(ledger).append(evalRow(now - 10_000, 5, 4, 0.8, "task:stable"))
        DepthEvalFeed.ledgerFile = { ledger }

        val first = EvolutionDepth.adjudicate(
            cost = CostFacts(),
            evidence = EvidenceFacts(last = EvolutionConclusion.LANDED, evidenceEnough = true),
            nowMs = now,
        )
        val sizeBefore = DepthLedger.replay().size
        repeat(200) {
            // 纯读：不落账、不改档
            assertNotNull(DepthEvalFeed.readInstalled())
            assertNotNull(DepthEvalFeed.read(ledger))
        }
        val latest = DepthLedger.latest()
        assertNotNull(latest)
        assertEquals("当前档位仍是那一行", first.atMs, latest!!.atMs)
        assertEquals("读账不许加行", sizeBefore, DepthLedger.replay().size)
        assertEquals("档位也没变", first.depth, EvolutionDepth.current())
    }

    // ── 判据④ 历史深度账仍能读（不回填、不改写） ─────────────────────────────

    @Test
    fun `判据四_老格式金样例仍读得出_且读它不改文件一个字节`() {
        // 563 之前写下的行：rd 里没有 eval* 五格，版本仍是 1
        val golden = """{"v":1,"at":$now,"by":"左右相合成","depth":"medium",""" +
            """"why":["升到中档：上一场 PASS","证据足且无反证"],"rd":{"cardBudgetLeft":"3",""" +
            """"profileConfidence":"0.9","interruptedToday":"0","authTier":"2",""" +
            """"last":"LANDED","evidenceEnough":"true","counter":"false",""" +
            """"unverifiableRising":"false","falseBlockRising":"false","streak":"3",""" +
            """"rollbackVerified":"false","canary":"unavailable","userAskedLower":"false"}}"""
        val f = File(root, DepthLedger.FILE_NAME)
        f.writeText(golden + "\n", Charsets.UTF_8)
        val beforeBytes = f.readBytes()

        val d = DepthLedger.latest()
        assertNotNull("老行必须读得出（不回填、不因缺新键而丢）", d)
        assertEquals(DepthTier.MEDIUM, d!!.depth)
        assertEquals("左右相合成", d.by)
        assertEquals("老读数原样", "0.9", d.readings["profileConfidence"])
        assertNull("老行没有 eval 那格（不凭空补）", d.readings["evalAt"])
        assertEquals("DEEP 以下的既有语义没变", DepthTier.MEDIUM, EvolutionDepth.current())
        assertTrue("读账不许改写文件", beforeBytes.contentEquals(f.readBytes()))
    }

    // ── 桩件 ────────────────────────────────────────────────────────────────

    private fun evalRow(atMs: Long, ran: Int, passed: Int, rate: Double?, ref: String) = TriggerRow(
        atMs = atMs,
        type = TriggerType.RUN_FAILED,
        triggered = true,
        ref = ref,
        intent = "563 造账",
        evalRan = ran,
        evalPassed = passed,
        evalTotal = ran,
        evalPassRate = rate,
    )

    private fun runChain(): ChainOutcome = EvolutionWiring.runEvolutionChain(
        trigger = GapTrigger(emptyList()),
        candidate = admittedCandidate("gap:563|x"),
        next = null,
        rulingRef = "御批-563",
        anchors = signedAnchors(),
        proofRunner = { EvolutionWiring.defaultProof() },
        nowMs = now,
    )

    private fun admittedCandidate(id: String): CriteriaCandidate = CriteriaCandidate(
        id = id,
        intent = "563 深度改读评估账",
        target = "some/target",
        outputForm = CriteriaOutputForm.CHECK_ITEMS,
        origin = CriteriaOrigin.CANDIDATE_POOL,
        replay = ReplayProof(moreBlocked = 2, lessBlocked = 0, moreAsked = 0, ranAtMs = 0L),
        verdicts = signedAnchors().associate { it.id to it.humanVerdict },
    )

    private fun signedAnchors(): List<AnchorCase> = listOf(
        AnchorCase(
            id = "A1",
            scene = "场景 1",
            oldVerdict = "旧判据通过",
            humanVerdict = "通过：人工确认",
            evidence = "ev1",
            screenedBy = "蓝总管",
            signedBy = "用户",
            signedAt = "2026-11-07",
        ),
    )
}
