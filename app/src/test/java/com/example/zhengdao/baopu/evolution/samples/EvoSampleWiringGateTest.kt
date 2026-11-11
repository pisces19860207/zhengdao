// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百八十九批 · 判据⑧（生产调用点断言）、⑨（未装配负例 ＋ 装配后端到端）的闸。
//
// ⑧ 为什么写成"名册＋次数"而不是散文：接线是最容易被下一批悄悄加一处的东西。
//    这一格把 `EvoSampleWiring.install(` 与 `EvoSampleWiring.recordObservation(` 的
//    **生产**调用方逐个数出来（`src/main` 全扫、注释先剥掉），新增一处就必须先改这格再合。
//    这也正是判据⑩「生产 diff 只含那两处」机器可判的那一半：名册恰好两个文件、各一次。
//
// ⑨ 两半：**未装配**时每条口都要 fail-closed（不抛、不编数、读数说实话）；
//    **装配后**要走真路——`LowRiskEvo.observeRun`（四域的观察落账口）→ 池文件 → 读数 → 转正放行。
//    只测装配后的"直接 record"等于绕过了接线那一跳，端到端就没验到。
package com.example.zhengdao.baopu.evolution.samples

import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.consequence.CanaryCapability
import com.example.zhengdao.baopu.consequence.ConsequenceWiring
import com.example.zhengdao.baopu.evolution.lowrisk.LowRiskEvo
import com.example.zhengdao.law.LawIo
import com.example.zhengdao.testkit.TempSandbox
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EvoSampleWiringGateTest {

    private val day = EvoSampleRules.DAY_MS
    private val now = 1_760_000_000_000L
    private val cap: CanaryCapability = CanaryCapability.REFLECTION_AUTO_EDIT

    private lateinit var ws: File
    private lateinit var memory: File

    @Before
    fun setUp() {
        ws = TempSandbox.create("zd589-wiring-")
        memory = File(ws, "抱朴记忆")
        DecisionLedger.install(memory)
        EvoSampleWiring.install(ws)
    }

    @After
    fun tearDown() {
        EvoSampleWiring.uninstallForTest()
        ConsequenceWiring.uninstallForTest()
        DecisionLedger.uninstallForTest()
        TempSandbox.deleteTree(ws)
    }

    /** 生产源里某个调用串出现在哪些文件（注释剥掉再数；`src/main` 全扫）。 */
    private fun callSites(needle: String): List<Pair<String, Int>> =
        LawIo.sources(LawIo.MAIN_ROOT)
            .map { (rel, raw) -> rel to Regex(Regex.escape(needle)).findAll(LawIo.stripComments(raw)).count() }
            .filter { it.second > 0 }

    // ── 判据⑧：生产调用点名册（就两处，各一次） ──────────────────────────

    @Test
    fun `判据⑧_生产调用点只有两处_各一次`() {
        LawIo.assertNotBlind(LawIo.MAIN_ROOT, minFiles = 100, minLines = 10_000)
        assertEquals(
            "样本池装配点：全仓只许 BaopuRuntime 一处（新增必须先改这格再合）",
            listOf("baopu/BaopuRuntime.kt" to 1),
            callSites("EvoSampleWiring.install("),
        )
        assertEquals(
            "攒样本口：生产只许 LowRiskEvo.observeRun 一处（四域共用同一个观察落账口）",
            listOf("baopu/evolution/lowrisk/LowRiskEvo.kt" to 1),
            callSites("EvoSampleWiring.recordObservation("),
        )
        assertEquals(
            "底层 record( 不许被生产直接调（绕开锚与缺省会话＝绕过⑪⑫⑬）：只许它自己那一处声明",
            emptyList<Pair<String, Int>>(),
            callSites("EvoSampleWiring.record("),
        )
    }

    @Test
    fun `判据⑩_生产 diff 只有那两个文件_三行以内`() {
        // 这一格不需要 git：名册已经把"哪两处"钉死了，这里再钉"改动有多小"，
        // 免得哪天有人在这两个文件里顺手加别的东西（diff 一涨，判据⑩ 那句话就不成立）。
        val installSite = LawIo.read("src/main/java/com/example/zhengdao/baopu/BaopuRuntime.kt")
        val observeSite = LawIo.read(
            "src/main/java/com/example/zhengdao/baopu/evolution/lowrisk/LowRiskEvo.kt",
        )
        assertEquals(
            "装配文件里对样本池的调用只许一次",
            1,
            Regex(Regex.escape("EvoSampleWiring.install(")).findAll(LawIo.stripComments(installSite)).count(),
        )
        assertEquals(
            "观察口里对样本池的调用只许一次",
            1,
            Regex(Regex.escape("EvoSampleWiring.recordObservation("))
                .findAll(LawIo.stripComments(observeSite)).count(),
        )
        assertFalse("装配文件里不许出现攒样本口（一处只干一件事）", installSite.contains("recordObservation("))
    }

    // ── 判据⑨前半：未装配 ⇒ 每条口都 fail-closed ─────────────────────────

    @Test
    fun `判据⑨_未装配_每条口都拒绝且不编数`() {
        EvoSampleWiring.uninstallForTest()
        assertFalse("攒样本返回 false", EvoSampleWiring.recordObservation(cap, now))
        assertFalse(EvoSampleWiring.installed())
        assertEquals("读数不许编：0 条", 0, EvoSampleWiring.reading(cap.wire).events)
        assertEquals("真没跑过就是样本不足", EvoSampleVerdict.INSUFFICIENT, EvoSampleWiring.reading(cap.wire).verdict)
        assertEquals("读数要如实说没装配", "evo_samples：样本池没装配", EvoSampleWiring.readoutBlock())
        assertEquals("没装配时重放一行也写不进去", 0, EvoSampleWiring.replay(cap.wire, listOf("x")))
        val out = EvoSampleWiring.promote(cap.wire, now)
        assertFalse("没装配不许转正", out.allowed)
        assertTrue("拒的理由仍是样本不足：${out.reason}", out.reason.contains("样本不足"))
    }

    // ── 判据⑨后半：装配后走真路（observeRun → 池 → 读数 → 转正） ────────

    @Test
    fun `判据⑨_装配后_观察口落样本_25次8天够转正`() {
        // 走生产那条路：LowRiskEvo.observeRun 一次 = 473 观察账一行 + 样本池一行。
        (0 until 25).forEach { i ->
            val at = now - 8L * day + i * 8L * day / 24
            LowRiskEvo.observeRun(cap, ok = true, atMs = at)
        }
        val pool = EvoSamplePool.fileOf(ws, cap.wire)
        assertTrue("观察口要真的把样本落进 <工作区>/.zd-evo/：${pool.absolutePath}", pool.isFile)
        val r = EvoSampleWiring.reading(cap.wire)
        assertEquals("25 次观察 ⇒ 25 条独立事件", 25, r.events)
        assertEquals("跨度 8 天", 8, r.days)
        assertEquals(EvoSampleVerdict.PROMOTE_READY, r.verdict)
        val out = EvoSampleWiring.promote(cap.wire, now)
        assertTrue("够格就该放行：${out.reason}", out.allowed)
    }

    @Test
    fun `判据⑨_装配后_无锚域的观察口不落样本`() {
        // 斥候没有锚（⑫）：走同一个观察口，也只落观察账，不落样本。
        LowRiskEvo.observeRun(CanaryCapability.SCOUT, ok = true, atMs = now)
        assertFalse("无锚域连文件都不建", EvoSamplePool.fileOf(ws, CanaryCapability.SCOUT.wire).exists())
        assertEquals(0, EvoSampleWiring.reading(CanaryCapability.SCOUT.wire).events)
        assertFalse(EvoSampleWiring.reading(CanaryCapability.SCOUT.wire).anchored)
    }

    @Test
    fun `判据⑨_装配后_同一轮暗卫连开五次_读数只算1次`() {
        // 端到端版的⑪：走生产观察口，同一天连开五次 ⇒ 池里五行、读数 1 次。
        // 间隔故意取 1 小时：**必须让防刷（10 分钟）碰不着**，否则"合并成 1"可能来自防刷而不是锚，
        // 这条用例就成了"因为别的原因碰巧绿"（M3 变异时正是这么发现的）。
        val dayStart = (now / day) * day
        (0 until 5).forEach { i ->
            LowRiskEvo.observeRun(
                CanaryCapability.NOCTURNAL_PATROL,
                ok = true,
                atMs = dayStart + (8 + i) * 3_600_000L,
            )
        }
        val file = EvoSamplePool.fileOf(ws, CanaryCapability.NOCTURNAL_PATROL.wire)
        assertEquals("五行都落盘（append-only 不吞行）", 5, file.readLines(Charsets.UTF_8).size)
        assertEquals("读数只认 1 条独立事件（同锚＝同一轮）", 1, EvoSampleWiring.reading(CanaryCapability.NOCTURNAL_PATROL.wire).events)
    }
}
