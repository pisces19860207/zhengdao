// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百八十批 · 真验证臂读数收件箱的行为门禁。
//
// 判据①（用户）：ref 落盘防重放——进程重启后仍拒同一 ref（本件零内存态，盘是唯一真相）。
// 判据②（用户）：时钟容差 ±5 分钟；明显未来 ⇒ 时钟可疑，理由落 ref 账（别静默弃）。
// 判据④（用户）：体检单有"进化读数通道"一格——channelLine 两态各一断言。
// 判据⑤（用户）：坏行只记日志不消耗 ref，好行才消耗——各有一条测试分开钉。
package com.example.zhengdao.baopu.evolution

import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.agent.RoyalDecisionLedger
import com.example.zhengdao.baopu.agent.RoyalVerdict
import com.example.zhengdao.baopu.inspect.DeviationEntry
import com.example.zhengdao.baopu.inspect.DeviationKind
import com.example.zhengdao.baopu.inspect.DeviationLedger
import com.example.zhengdao.baopu.policy.AdmissionState
import com.example.zhengdao.baopu.policy.AnchorCase
import com.example.zhengdao.baopu.policy.CriteriaCandidate
import com.example.zhengdao.baopu.policy.CriteriaOrigin
import com.example.zhengdao.baopu.policy.CriteriaOutputForm
import com.example.zhengdao.baopu.policy.EpochRegistry
import com.example.zhengdao.baopu.policy.EpochRuntime
import com.example.zhengdao.baopu.policy.EvolutionAttemptRow
import com.example.zhengdao.baopu.policy.GapTrigger
import com.example.zhengdao.baopu.policy.ReplayProof
import com.example.zhengdao.baopu.verify.GapFeedback
import com.example.zhengdao.baopu.verify.GapItem
import com.example.zhengdao.baopu.verify.GapKind
import com.example.zhengdao.baopu.verify.GapSource
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProofReadingTest {

    private lateinit var root: File

    /** 收件箱文件（账根下，与提案/尝试账同房）。 */
    private fun inbox(): File = File(root, ProofReading.READINGS_FILE)

    private fun refsFile(): File = File(root, ProofReading.USED_REFS_FILE)

    @Before
    fun setUp() {
        root = java.nio.file.Files.createTempDirectory("proof-reading-").toFile()
        DecisionLedger.install(root)
        DeviationLedger.install(root)
        EvolutionWiring.resetForTest()
    }

    @After
    fun tearDown() {
        runCatching { root.deleteRecursively() }
        EvolutionWiring.resetForTest()
        GapFeedback.resetForTest()
    }

    // ── 判据① ref 落盘防重放 ────────────────────────────────────────────────

    @Test
    fun `判据⑤_好行才消耗_读数交给链那一刻落_USED`() {
        val now = NOW
        writeInbox(rowJson(at = now - 1_000L, ref = "ci-1"))
        val reading = ProofReading.pick(PROPOSAL, OPENED_AT, now)
        assertNotNull("合格行要被选中", reading)
        assertTrue("pick 本身不许消耗 ref", ProofReading.usedRefs(root).isEmpty())
        reading!!.runner(reading.snapshot)
        assertEquals("runner 被调 ⇒ ref 落账", setOf("ci-1"), ProofReading.usedRefs(root))
        assertTrue("落的是盘上的 ref 账", refsFile().readText().startsWith("USED|ci-1|"))
    }

    @Test
    fun `判据①_进程重启后仍拒同一_ref`() {
        writeInbox(rowJson(at = NOW - 1_000L, ref = "ci-1"))
        val first = ProofReading.pick(PROPOSAL, OPENED_AT, NOW)
        assertNotNull(first)
        first!!.runner(first.snapshot)
        // 本件零内存态：第二次 pick 与"重启后再来一遍"是同一件事——都从盘上现读。
        assertNull("一票一用：同一 ref 重放必须被拒", ProofReading.pick(PROPOSAL, OPENED_AT, NOW))
    }

    // ── 判据⑤ 坏行与重放分开 ────────────────────────────────────────────────

    @Test
    fun `判据⑤_坏行只记日志不消耗_ref_修正后同_ref还收`() {
        // 构造器拒的坏行：ran=true 却 0 例（376：空集的全绿是假绿，GateRun 建不出来）。
        writeInbox(rowJson(at = NOW - 1_000L, ref = "ci-x", unitRan = true, unitTotal = 0))
        assertNull("坏行不许被选中", ProofReading.pick(PROPOSAL, OPENED_AT, NOW))
        assertTrue("坏行不许烧 ref", ProofReading.usedRefs(root).isEmpty())
        // 同 ref 修正后重推：还收（笔误不该永久烧掉一个 CI run）。
        writeInbox(rowJson(at = NOW - 1_000L, ref = "ci-x"))
        assertNotNull("修正后的好行（同 ref）要收", ProofReading.pick(PROPOSAL, OPENED_AT, NOW))
    }

    @Test
    fun `判据⑤_多行取最新_坏行让位`() {
        val older = NOW - 3_000L
        val newer = NOW - 1_000L
        writeInbox(
            "{ 不是 json",
            rowJson(at = older, ref = "ci-old"),
            rowJson(at = newer, ref = "ci-new"),
        )
        val reading = ProofReading.pick(PROPOSAL, OPENED_AT, NOW)
        assertNotNull(reading)
        reading!!.runner(reading.snapshot)
        assertEquals("最新一行胜出", setOf("ci-new"), ProofReading.usedRefs(root))
    }

    // ── 判据② 时钟容差与时钟可疑 ────────────────────────────────────────────

    @Test
    fun `判据②_容差内不弃_早晚各一读`() {
        // 晚于"此刻"但没超容差（+4 分钟）：收。
        writeInbox(rowJson(at = NOW + 4L * 60_000L, ref = "ci-future-ok"))
        assertNotNull(ProofReading.pick(PROPOSAL, OPENED_AT, NOW))
        // 早于提案开出但没超容差（-4 分钟）：收（证据刚好压着提议的线）。
        writeInbox(rowJson(at = OPENED_AT - 4L * 60_000L, ref = "ci-early-ok"))
        assertNotNull(ProofReading.pick(PROPOSAL, OPENED_AT, NOW))
    }

    @Test
    fun `判据②_时钟可疑_弃读数_理由落账_且不刷屏`() {
        writeInbox(rowJson(at = NOW + 10L * 60_000L, ref = "ci-clock"))
        assertNull("明显未来 ⇒ 弃", ProofReading.pick(PROPOSAL, OPENED_AT, NOW))
        val lines = refsFile().readText().trim().lines()
        assertEquals(1, lines.size)
        assertTrue("理由要写进账（CLOCK_SUSPECT）", lines.single().startsWith("CLOCK_SUSPECT|ci-clock|"))
        assertTrue("账行里要点明容差", lines.single().contains("容差"))
        // 第二次评估：不重复落（dedupe by ref），也不再采用。
        assertNull(ProofReading.pick(PROPOSAL, OPENED_AT, NOW))
        assertEquals("同 ref 不刷第二行", 1, refsFile().readText().trim().lines().size)
    }

    @Test
    fun `负例_读数早于提案开出_弃且不落_ref 账`() {
        writeInbox(rowJson(at = OPENED_AT - 10L * 60_000L, ref = "ci-stale"))
        assertNull(ProofReading.pick(PROPOSAL, OPENED_AT, NOW))
        assertFalse("时刻不符只记日志，ref 账不许有行", refsFile().isFile)
    }

    @Test
    fun `负例_proposal 不匹配_不取`() {
        writeInbox(rowJson(at = NOW - 1_000L, ref = "ci-1", proposal = "gap:other"))
        assertNull("别场的读数不许替本场作证", ProofReading.pick(PROPOSAL, OPENED_AT, NOW))
        assertTrue(ProofReading.usedRefs(root).isEmpty())
    }

    @Test
    fun `负例_snapshot 撞不可进化清单_310 构造期拦_坏行不消耗`() {
        writeInbox(
            rowJson(
                at = NOW - 1_000L,
                ref = "ci-immutable",
                target = "app/src/main/java/com/example/zhengdao/baopu/verify/ArtifactVerifier#check",
            ),
        )
        assertNull("撞清单的表根本建不出来（310 构造闸，本件不另判）", ProofReading.pick(PROPOSAL, OPENED_AT, NOW))
        assertTrue(ProofReading.usedRefs(root).isEmpty())
    }

    @Test
    fun `负例_收件箱缺失_pick 为空_链只能停在准入`() {
        assertNull(ProofReading.pick(PROPOSAL, OPENED_AT, NOW))
    }

    // ── 真读数进链：落版与回滚两个收场 ──────────────────────────────────────

    @Test
    fun `正例_真读数进链_四条全绿_落版`() {
        writeInbox(rowJson(at = NOW - 1_000L, ref = "ci-ok"))
        val reading = ProofReading.pick(PROPOSAL, OPENED_AT, NOW)!!
        val outcome = EvolutionWiring.runEvolutionChain(
            trigger = GapTrigger(listOf(gapItem(attempts = GapFeedback.HAND_OVER_ATTEMPTS))),
            candidate = candidateWithReplay(PROPOSAL),
            next = reading.snapshot,
            rulingRef = "御批-380",
            anchors = signedAnchors(),
            proofRunner = reading.runner,
            nowMs = NOW,
        )
        assertEquals(AdmissionState.ADMIT, outcome.admission)
        assertTrue(outcome.applied)
        assertTrue("真读数全绿 ⇒ 落版", outcome.landed())
        assertTrue("纪元册落纪", EpochRegistry.epochs().isNotEmpty())
        assertEquals(EvolutionAttemptRow.VERDICT_PASS, EvolutionWiring.attempts().last().verdict)
        assertEquals("读数被 verify 用掉 ⇒ ref 落账", setOf("ci-ok"), ProofReading.usedRefs(root))
    }

    @Test
    fun `正例_真读数红_自动回滚_失败纪不进册`() {
        writeInbox(rowJson(at = NOW - 1_000L, ref = "ci-red", unitFailed = 1))
        val reading = ProofReading.pick(PROPOSAL, OPENED_AT, NOW)!!
        val outcome = EvolutionWiring.runEvolutionChain(
            trigger = GapTrigger(listOf(gapItem(attempts = GapFeedback.HAND_OVER_ATTEMPTS))),
            candidate = candidateWithReplay(PROPOSAL),
            next = reading.snapshot,
            rulingRef = "御批-380-red",
            anchors = signedAnchors(),
            proofRunner = reading.runner,
            nowMs = NOW,
        )
        assertTrue("单测红 1 条 ⇒ 回滚", outcome.rolledBack())
        assertFalse(outcome.landed())
        assertNull("回滚后回到无表", EpochRuntime.currentSnapshot())
        assertTrue("失败的纪不进纪元册", EpochRegistry.epochs().isEmpty())
        val last = EvolutionWiring.attempts().last()
        assertEquals(EvolutionAttemptRow.VERDICT_FAIL, last.verdict)
        assertTrue(last.rolledBack)
        assertEquals("红读数也是真用掉的读数 ⇒ ref 照落账", setOf("ci-red"), ProofReading.usedRefs(root))
    }

    // ── 生产入口：sweep 真调读数臂（准入拒也不静默、不烧 ref） ────────────────

    @Test
    fun `生产入口_sweep_御批后走读数臂_准入拒不静默且不烧_ref`() {
        val now = NOW
        repeat(3) { i ->
            DeviationLedger.write(
                root,
                DeviationEntry("dev:$i", now - i * 1_000L, "run-$i", "", DeviationKind.TIMEOUT, emptyMap()),
            )
        }
        EvolutionWiring.onRunSettled(now)
        val proposal = EvolutionWiring.proposals().single()
        assertEquals("misjudgment:${DeviationKind.TIMEOUT.wire}", proposal.id)
        // 御批＋送读数，再收工一轮：sweep 会 pick 到读数并跑链。
        RoyalDecisionLedger.record(proposal.id, RoyalVerdict.APPROVE, now)
        writeInbox(rowJson(proposal = proposal.id, at = now - 1_000L, ref = "ci-sweep"))
        EvolutionWiring.onRunSettled(now + 1_000L)
        // 生产候选还没有回放证据 ⇒ 准入拒；拒必须落账带原因（不许静默）。
        val last = EvolutionWiring.attempts().last()
        assertEquals(EvolutionAttemptRow.VERDICT_FAIL, last.verdict)
        assertTrue("拒行要带准入原因", last.reason.contains("准入"))
        // 链没走到 verify ⇒ ref 不许被烧（消耗只在 verify 拿读数那一刻）。
        assertTrue("准入拒 ⇒ ref 不烧", ProofReading.usedRefs(root).isEmpty())
    }

    // ── 判据④ 体检格 ────────────────────────────────────────────────────────

    @Test
    fun `判据④_channelLine 两态_debug_release 都显自动送达_手送只剩debug`() {
        val debug = ProofReading.channelLine(true)
        val release = ProofReading.channelLine(false)
        assertTrue("debug 要说可用", debug.contains("可用"))
        assertFalse("debug 不许说不可用", debug.contains("不可用"))
        // 461 起 release 也走远端自动送达（判据"release 也能用"落在体检格上）。
        assertTrue("release 要说自动送达可用", release.contains("可用（release）"))
        assertTrue("release 行要写明是远端自动送达", release.contains("自动送达"))
        assertTrue("release 行要说明缺网/缺分支时的语义（等下一轮，不是进化总在失败）", release.contains("等下一轮"))
    }

    // ── 第四百九十五批：取数层分流（没送到 ≠ 不合格） ──────────────────────────

    @Test
    fun `495判据①_gapFor_收件箱没行_Missing`() {
        val gap = ProofReading.gapFor(PROPOSAL, OPENED_AT, NOW)
        assertTrue("一行都没有是没送到：$gap", gap is ProofGap.Missing)
        assertTrue("缺因要说出口：$gap", (gap as ProofGap.Missing).why.contains("收件箱"))
    }

    @Test
    fun `495判据①_gapFor_整箱都是别场的行也算_Missing`() {
        writeInbox(rowJson(at = NOW - 1_000L, ref = "ci-other", proposal = "gap:other"))
        assertTrue("别场的行不归本场，不算本场不合格", ProofReading.gapFor(PROPOSAL, OPENED_AT, NOW) is ProofGap.Missing)
    }

    @Test
    fun `495_垃圾行不判死别人_归属只认读得出的 proposal 字段`() {
        writeInbox("{ 不是 json")
        assertTrue(
            "整行连 JSON 都不是 ⇒ 谁也不归 ⇒ 仍算没送到（380「一次笔误不烧 CI run」同款豁免）",
            ProofReading.gapFor(PROPOSAL, OPENED_AT, NOW) is ProofGap.Missing,
        )
    }

    @Test
    fun `495判据②_gapFor_本场行时间窗不符_Invalid_且带得出新表`() {
        writeInbox(rowJson(at = OPENED_AT - 10L * 60_000L, ref = "ci-stale"))
        val gap = ProofReading.gapFor(PROPOSAL, OPENED_AT, NOW)
        assertTrue("本场的行被弃 ⇒ 不合格：$gap", gap is ProofGap.Invalid)
        assertNotNull("建得出表 ⇒ 链能换表再判红（②的回滚就从这里来）", (gap as ProofGap.Invalid).snapshot)
        assertTrue("弃因要说出口：${gap.why}", gap.why.contains("早于提案"))
    }

    @Test
    fun `495判据②_gapFor_本场行格式建不出_Invalid_却没表`() {
        writeInbox(rowJson(at = NOW - 1_000L, ref = "ci-broken", unitRan = true, unitTotal = 0))
        val gap = ProofReading.gapFor(PROPOSAL, OPENED_AT, NOW)
        assertTrue(gap is ProofGap.Invalid)
        assertNull("构造器连表都拒 ⇒ 没得换，链不许假称回滚", (gap as ProofGap.Invalid).snapshot)
        assertTrue("弃因要说「格式」：${gap.why}", gap.why.contains("格式建不出"))
    }

    @Test
    fun `495判据③_gapFor_有合格行_Available`() {
        writeInbox(rowJson(at = NOW - 1_000L, ref = "ci-good"))
        assertTrue(ProofReading.gapFor(PROPOSAL, OPENED_AT, NOW) is ProofGap.Available)
    }

    @Test
    fun `495_gapFor 安静分类_不重复落时钟可疑账`() {
        writeInbox(rowJson(at = NOW + 10L * 60_000L, ref = "ci-clock"))
        assertNull("pick 先喊过并落过一行", ProofReading.pick(PROPOSAL, OPENED_AT, NOW))
        val before = refsFile().readText().trim().lines().size
        assertTrue(ProofReading.gapFor(PROPOSAL, OPENED_AT, NOW) is ProofGap.Invalid)
        assertEquals("分流只分类，不第二遍落账", before, refsFile().readText().trim().lines().size)
    }

    @Test
    fun `495_待验队列的闸_只有拿得出能换的表才值得再过准入`() {
        val snapshot = ProofRow.parse(rowJson(at = NOW - 1_000L, ref = "ci-table"))!!.snapshot
        assertFalse("没送到 ⇒ 原地等", ProofGap.Missing("收件箱没有一行").actionable())
        assertFalse("坏到建不出表 ⇒ 原地等", ProofGap.Invalid("格式建不出", null).actionable())
        assertTrue("弃行带得出表 ⇒ 这一轮能判（495②）", ProofGap.Invalid("ref 已用", snapshot).actionable())
        assertTrue("合格读数 ⇒ 照走", ProofGap.Available.actionable())
    }

    // ── 辅助 ────────────────────────────────────────────────────────────────

    private val PROPOSAL = "misjudgment:TIMEOUT"
    private val OPENED_AT = 1_700_000_000_000L
    private val NOW = OPENED_AT + 2_000L

    private fun writeInbox(vararg lines: String) {
        inbox().writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
    }

    private fun rowJson(
        at: Long,
        ref: String,
        proposal: String = PROPOSAL,
        unitRan: Boolean = true,
        unitTotal: Int = 100,
        unitFailed: Int = 0,
        target: String = "some/target",
    ): String = JSONObject()
        .put("v", 1)
        .put("proposal", proposal)
        .put("ref", ref)
        .put("at", at)
        .put(
            "unit",
            JSONObject().put("ran", unitRan).put("total", unitTotal).put("failed", unitFailed),
        )
        .put("regression", JSONObject().put("ran", true).put("total", 13).put("failed", 0))
        .put("anchor", JSONObject().put("total", 50).put("mismatched", 0))
        .put("detekt", JSONObject().put("before", 341).put("after", 341))
        .put(
            "snapshot",
            JSONObject()
                .put("epochMs", at)
                .put("seq", 1L)
                .put(
                    "criteria",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("id", "c1")
                                .put("description", "新判据")
                                .put("target", target)
                                .put("admittedInEpoch", 1L),
                        ),
                ),
        )
        .toString()

    private fun gapItem(attempts: Int): GapItem = GapItem(
        source = GapSource.JINWU,
        kind = GapKind.TOOL_MISUSE,
        target = "x",
        evidence = listOf("ev:x"),
        minimalFix = "fix x",
        attempts = attempts,
    )

    private fun candidateWithReplay(id: String): CriteriaCandidate = CriteriaCandidate(
        id = id,
        intent = "判据进化测试",
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
            signedAt = "2026-11-04",
        ),
    )
}
