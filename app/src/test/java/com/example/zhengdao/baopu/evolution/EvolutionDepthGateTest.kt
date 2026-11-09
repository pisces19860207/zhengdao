// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百零五批 · 追加口径「自进化深度裁定」的闸（派工单追加判据①–⑥）。
//
// 这一层的规矩是**进化到哪一步不由模型说**，所以测法照规矩分条：
// · 判据①：定档要落账——谁定的、依据哪几项、当时读数，三样在同一行里（事后解释不算依据）；
// · 判据②：新装首跑＝浅档（没定过档就没有档可升；"不知道"一律偏保守，不许拿含糊升档）；
// · 判据③：升档要三条同时满足，**中→深另加三格**——灰度是不可绕的硬门，拿"中档三条"跨进
//   深档正是这句话要咬的那一口（负例 M7）；
// · 判据④：造一次 FAIL ⇒ 立刻降一档，不等周期；
// · 判据⑤：模型可写参谋意见，但「给自己的产出发通过」这条路在代码里不存在；而且单子说的是
//   「被拦**并记账**」——拦了不留痕＝没发生过，所以被拦的那条照样进账，只是带上理由。
//
// 两条源级用例钉形状：合成规则读不到意见（意见不是升档输入）、且中→深只有 canGoDeep 一条路；
// 本层的深度裁定今天**还没有生产调用方**（与 236／330 同一口径：判据没齐不硬接），
// 这一格写成断言而不是散文——接线批一动，这格就红着提醒改名册。
package com.example.zhengdao.baopu.evolution

import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.law.LawIo
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EvolutionDepthGateTest {

    private lateinit var ledger: File
    private val t0 = 1_700_000_000_000L

    @Before
    fun setUp() {
        ledger = Files.createTempDirectory("zd505-depth-ledger").toFile()
        DecisionLedger.install(ledger)
        EvolutionDepth.resetForTest()
        AdvisorGate.resetForTest()
    }

    @After
    fun tearDown() {
        EvolutionDepth.resetForTest()
        AdvisorGate.resetForTest()
        DecisionLedger.uninstallForTest()
        ledger.deleteRecursively()
    }

    /** 左相读数（默认＝还有余量；"拿默认升档"由另一格专门测）。 */
    private fun cost(
        budgetLeft: Int = 2,
        conf: Double = 0.9,
        interrupted: Int = 1,
        tier: Int = 1,
    ) = CostFacts(budgetLeft, conf, interrupted, tier)

    /** 右相读数（默认＝PASS 且证据足无反证；深档那三格一律默认不给）。 */
    private fun evidence(
        last: EvolutionConclusion = EvolutionConclusion.LANDED,
        enough: Boolean = true,
        counter: Boolean = false,
        rising: Boolean = false,
        falseBlock: Boolean = false,
        streak: Int = 0,
        rollback: Boolean = false,
        canary: CanaryStatus = CanaryStatus.UNAVAILABLE,
        userLower: Boolean = false,
    ) = EvidenceFacts(
        last = last,
        evidenceEnough = enough,
        hasCounterEvidence = counter,
        unverifiableRising = rising,
        falseBlockRising = falseBlock,
        sameKindSuccessStreak = streak,
        rollbackVerified = rollback,
        canary = canary,
        userAskedLower = userLower,
    )

    /** 深档要的那三格全给（连续 N 次同类成功＋可回滚已验证＋灰度可用）。 */
    private fun deepEvidence() = evidence(
        streak = EvidenceFacts.SAME_KIND_SUCCESS_MIN,
        rollback = true,
        canary = CanaryStatus.AVAILABLE,
    )

    private fun decide(prev: DepthTier?, c: CostFacts = cost(), e: EvidenceFacts = evidence()) =
        DepthRules.decide(prev, c, e, t0, EvolutionDepth.DECIDER)

    // ── 判据②：新装首跑 = 浅档 ─────────────────────────────────────────────────

    @Test
    fun `判据二_新装首跑是浅档_没定过档就没有档可升`() {
        DecisionLedger.uninstallForTest()
        assertNull("没账＝这台机器还没定过档", EvolutionDepth.currentOrNull())
        assertEquals(DepthTier.SHALLOW, EvolutionDepth.current())
        assertTrue(EvolutionDepth.sheetLines().first().contains("还没定过档"))
        assertTrue("浅档那句话＝只记不改", DepthTier.SHALLOW.oneLine.contains("只记不改"))
        DecisionLedger.install(ledger)
        assertEquals("装了落点但账上没行 ⇒ 仍是浅档", DepthTier.SHALLOW, EvolutionDepth.current())
    }

    @Test
    fun `判据二_拿不知道当不了升档理由_默认读数全偏保守`() {
        val d = DepthRules.decide(null, CostFacts(), EvidenceFacts(), t0, EvolutionDepth.DECIDER)
        assertEquals(DepthTier.SHALLOW, d.depth)
        assertFalse(CostFacts().budgetSpares())
        assertFalse(CostFacts().profileConfident())
        assertFalse(EvidenceFacts().passWithEvidence())
        assertFalse("默认三态是没验过，不是 PASS", EvidenceFacts().last == EvolutionConclusion.LANDED)
    }

    // ── 判据①：定档账（谁定的／依据哪几项／当时读数） ──────────────────────────

    @Test
    fun `判据一_定档落账三样齐_谁定的_依据哪几项_当时读数`() {
        val d = EvolutionDepth.adjudicate(cost(), evidence(), nowMs = t0)
        assertEquals("定档人＝两相合成（不是模型，也不是某一个人）", EvolutionDepth.DECIDER, d.by)
        assertTrue("依据至少要有一条（没依据的定档＝事后编）", d.reasons.isNotEmpty())
        // 13 ⇒ 18（563）：右相新增 495 评估账的五格（evalAt/evalType/evalRan/evalPass/evalRef）。
        // 只加键：老账缺这五格照样 parse（563 的金样例用例钉住），版本号不动。
        assertEquals("当时读数全量进账", 18, d.readings.size)
        assertEquals("2", d.readings["cardBudgetLeft"])
        assertEquals("0.9", d.readings["profileConfidence"])
        assertEquals(EvolutionConclusion.LANDED.name, d.readings["last"])
        assertEquals(CanaryStatus.UNAVAILABLE.wire, d.readings["canary"])
        assertEquals("没装评估账 ⇒ 五格留空串（不是 0）", "", d.readings["evalAt"])
        assertTrue(File(ledger, DepthLedger.FILE_NAME).isFile)
        val on = DepthLedger.replay()
        assertEquals(1, on.size)
        assertEquals(d.depth, on[0].depth)
        assertEquals(d.by, on[0].by)
        assertEquals(d.reasons, on[0].reasons)
        assertEquals(d.readings, on[0].readings)
        assertEquals(t0, on[0].atMs)
    }

    @Test
    fun `判据一_账的行格式是闭集六键_没有模型投票那一格`() {
        EvolutionDepth.adjudicate(cost(), evidence(), nowMs = t0)
        val raw = File(ledger, DepthLedger.FILE_NAME).readLines(Charsets.UTF_8).first()
        val keys = JSONObject(raw).keys().asSequence().toSet()
        assertEquals(setOf("v", "at", "by", "depth", "why", "rd"), keys)
        assertFalse("深度账里不许有意见／投票键：$keys", keys.any { it.contains("adv") || it == "vote" })
        assertEquals(DepthLedger.VERSION, JSONObject(raw).getInt("v"))
    }

    @Test
    fun `判据一_内存清了盘还在_落点没装配就不编历史`() {
        EvolutionDepth.adjudicate(cost(), evidence(), nowMs = t0)
        DepthLedger.resetForTest()
        val fromDisk = DepthLedger.latest()
        assertNotNull("重启后档位从盘上读回来", fromDisk)
        assertEquals("这一场是从浅升上来的中档", DepthTier.MEDIUM, fromDisk!!.depth)
        DecisionLedger.uninstallForTest()
        DepthLedger.resetForTest()
        assertNull("落点没装配 ⇒ 读不到档（不拿内存当账）", DepthLedger.latest())
    }

    // ── 判据③：升档需三条同时满足 ─────────────────────────────────────────────

    @Test
    fun `判据三_三条同时才从浅进中`() {
        val d = decide(null)
        assertEquals(DepthTier.MEDIUM, d.depth)
        assertTrue("依据要点名那三条：${d.reasons}", d.reasons.any { it.contains("中档") })
    }

    @Test
    fun `判据三_右相缺一格就不升`() {
        listOf(
            "证据不足" to evidence(enough = false),
            "有反证" to evidence(counter = true),
            "没验过" to evidence(last = EvolutionConclusion.UNVERIFIABLE),
            "上一场 FAIL" to evidence(last = EvolutionConclusion.FAILED),
        ).forEach { (name, e) ->
            assertFalse("$name 时右相不该通过", e.passWithEvidence() && !e.mustDowngrade())
            assertEquals("$name ⇒ 不升档", DepthTier.SHALLOW, decide(null, cost(), e).depth)
        }
    }

    @Test
    fun `判据三_左相缺一格就不升`() {
        listOf(
            "额度用尽" to cost(budgetLeft = 0),
            "当日打扰满" to cost(interrupted = CostFacts.INTERRUPT_SOFT_MAX),
            "只读档" to cost(tier = 0),
            "画像低置信" to cost(conf = CostFacts.PROFILE_CONF_HIGH - 0.01),
        ).forEach { (name, c) ->
            assertFalse("$name 时左相不该点头", c.budgetSpares() && c.profileConfident())
            assertEquals("$name ⇒ 不升档", DepthTier.SHALLOW, decide(null, c, evidence()).depth)
        }
        assertTrue(cost().budgetSpares())
        assertTrue(cost().profileConfident())
    }

    @Test
    fun `判据三_中到深另加三格_灰度没交付就判不出深档`() {
        val e = deepEvidence()
        assertEquals("中档＋三格齐 ⇒ 深", DepthTier.DEEP, decide(DepthTier.MEDIUM, cost(), e).depth)
        val holes = listOf(
            "灰度没交付" to e.copy(canary = CanaryStatus.UNAVAILABLE),
            "回滚只是理论上能回" to e.copy(rollbackVerified = false),
            "连续成功差一格" to e.copy(sameKindSuccessStreak = EvidenceFacts.SAME_KIND_SUCCESS_MIN - 1),
        )
        holes.forEach { (name, ev) ->
            assertFalse("$name 时深档条件不该成立", DepthRules.canGoDeep(cost(), ev))
            assertEquals("$name ⇒ 到不了深档", DepthTier.MEDIUM, decide(DepthTier.MEDIUM, cost(), ev).depth)
        }
    }

    @Test
    fun `判据三_一次只升一档_浅档不跨格进深`() {
        val e = deepEvidence()
        assertEquals("深档条件全满足也要先过中档这一格", DepthTier.MEDIUM, decide(DepthTier.SHALLOW, cost(), e).depth)
        assertEquals("已经到顶还在顶", DepthTier.DEEP, decide(DepthTier.DEEP, cost(), e).depth)
    }

    @Test
    fun `判据三_拿中档三条跨进深档这条路不存在`() {
        // 中档三条全满足、深档三格全没给——必须待在中档（负例 M7 咬的就是这一口）
        val d = decide(DepthTier.MEDIUM)
        assertEquals(DepthTier.MEDIUM, d.depth)
        assertTrue("理由要说清为什么不升：${d.reasons}", d.reasons.any { it.contains("不跨格升") })
    }

    // ── 判据④：造一次 FAIL ⇒ 立刻降一档 ────────────────────────────────────────

    @Test
    fun `判据四_造一次FAIL立刻降一档_不等周期`() {
        val fail = evidence(last = EvolutionConclusion.FAILED)
        assertEquals("中档一场 FAIL ⇒ 浅", DepthTier.SHALLOW, decide(DepthTier.MEDIUM, cost(), fail).depth)
        assertEquals("深档 FAIL ⇒ 只降一档", DepthTier.MEDIUM, decide(DepthTier.DEEP, cost(), fail).depth)
        assertEquals("浅档不降出闭集", DepthTier.SHALLOW, decide(DepthTier.SHALLOW, cost(), fail).depth)
        val r = decide(DepthTier.MEDIUM, cost(), fail).reasons
        assertTrue("降档要说出是哪一格触的：$r", r.contains("降一档：即时降档条件命中") && r.contains("上一场 FAIL"))
    }

    @Test
    fun `判据四_含糊增多_误拦上升_用户降档_各是一条即时降档`() {
        listOf(
            "没法验增多" to evidence(rising = true),
            "误拦率上升" to evidence(falseBlock = true),
            "用户明确降档" to evidence(userLower = true),
        ).forEach { (name, e) ->
            assertTrue("$name 要触发即时降档", e.mustDowngrade())
            val d = decide(DepthTier.MEDIUM, cost(), e)
            assertEquals("$name ⇒ 降一档", DepthTier.SHALLOW, d.depth)
            assertTrue("$name 的理由要点名是哪一格触的：${d.reasons}", d.reasons.size >= 2)
        }
    }

    @Test
    fun `判据四_降档优先_升档条件再齐也压不过一次FAIL`() {
        val e = evidence(
            last = EvolutionConclusion.FAILED,
            streak = 9,
            rollback = true,
            canary = CanaryStatus.AVAILABLE,
        )
        assertTrue("升档那三格此刻其实全在", DepthRules.canGoDeep(cost(), e.copy(last = EvolutionConclusion.LANDED)))
        assertEquals("深档撞上 FAIL ⇒ 降，不维持", DepthTier.MEDIUM, decide(DepthTier.DEEP, cost(), e).depth)
        assertEquals(DepthTier.SHALLOW, decide(DepthTier.MEDIUM, cost(), e).depth)
    }

    @Test
    fun `判据四_深档条件掉了就退回中档_灰度掉了也待不住`() {
        val d = decide(DepthTier.DEEP, cost(), deepEvidence().copy(canary = CanaryStatus.UNAVAILABLE))
        assertEquals(DepthTier.MEDIUM, d.depth)
        assertTrue("要说清是深档站不住不是升档：${d.reasons}", d.reasons.any { it.contains("深档站不住") })
    }

    // ── 判据⑤：模型可参谋，不给票 ──────────────────────────────────────────────

    private fun advice(
        selfIssued: Boolean = false,
        about: String = "提案 327 的判据改动",
        reason: String = "边界收得太紧",
    ) = EvolutionAdvice(
        id = "ad-505-1",
        atMs = t0,
        about = about,
        stance = "建议通过",
        reason = reason,
        risks = listOf("误拦上升"),
        selfIssued = selfIssued,
    )

    @Test
    fun `判据五_参谋意见可给_建议加理由加风险点_落账`() {
        assertNotNull(AdvisorGate.accept(advice()))
        val on = AdvisorGate.diskReplay()
        assertEquals(1, on.size)
        assertEquals("建议通过", on[0].stance)
        assertEquals("边界收得太紧", on[0].reason)
        assertEquals(listOf("误拦上升"), on[0].risks)
        assertFalse("收下的不标被拦", on[0].blocked)
    }

    @Test
    fun `判据五_模型给自己的产出发通过_被拦并记账`() {
        assertNull("同源自评没有票", AdvisorGate.accept(advice(selfIssued = true)))
        val on = AdvisorGate.diskReplay()
        assertEquals("被拦也要落账（拦了不留痕＝没发生过）", 1, on.size)
        assertTrue(on[0].blocked)
        assertTrue(on[0].selfIssued)
        assertTrue("理由照实落：${on[0].blockNote}", on[0].blockNote.contains("同源自评"))
    }

    @Test
    fun `判据五_含糊的意见同样被拦并留痕`() {
        assertNull(AdvisorGate.accept(advice(about = "  ")))
        assertNull(AdvisorGate.accept(advice(reason = "  ")))
        val on = AdvisorGate.diskReplay()
        assertEquals(2, on.size)
        assertTrue(on.all { it.blocked })
        assertTrue("要分得清拦在哪一格：${on[0].blockNote}", on[0].blockNote.contains("评的是谁"))
        assertTrue("要分得清拦在哪一格：${on[1].blockNote}", on[1].blockNote.contains("为什么"))
    }

    @Test
    fun `判据五_意见改不动深度档也覆盖不了右相三态`() {
        assertFalse(AdvisorGate.affectsDepth())
        assertFalse(AdvisorGate.overridesVerdict())
        // 形状上就办不到：深度档与三态都不是意见对象的字段
        val text = advice().toString()
        assertFalse("意见对象没有档位字段：$text", text.contains("depth=") || text.contains("verdict="))
        assertEquals(DepthTier.MEDIUM, decide(null).depth)
        assertEquals(EvolutionConclusion.LANDED, evidence().last)
    }

    @Test
    fun `追加判据六_制度类出现在候选里直接拒_不进任何档`() {
        listOf("kernel", "persona_head", "criteria").forEach { k ->
            assertTrue("$k 是制度类", InstitutionalGuard.isInstitutional(k))
            assertFalse("$k 不许进候选", InstitutionalGuard.allowed(k))
        }
        listOf("skill_step", "prompt_shape", "inject_policy", "memory_org").forEach { k ->
            assertFalse(InstitutionalGuard.isInstitutional(k))
            assertTrue("非制度类才谈得上进候选：$k", InstitutionalGuard.allowed(k))
        }
    }

    // ── 源级：合成规则读不到意见，中→深只有一条路 ──────────────────────────────

    @Test
    fun `源级_合成规则里没有模型意见这一格`() {
        LawIo.assertNotBlind(LawIo.MAIN_ROOT, minFiles = 100, minLines = 10_000)
        val rel = "src/main/java/com/example/zhengdao/baopu/evolution/EvolutionDepth.kt"
        val code = LawIo.stripComments(LawIo.read(rel))
        val rules = code.substringAfter("internal object DepthRules {").substringBefore("\n}")
        assertTrue("切片要真切到规则本体（防空转）", rules.contains("fun decide("))
        assertFalse("合成规则不许读参谋意见（意见不是升档的输入）", rules.contains("Advice"))
        assertFalse("合成规则是纯函数：不碰盘", rules.contains("appendText") || rules.contains("readLines"))
        val deepGate = "base == DepthTier.MEDIUM && canGoDeep(cost, evidence) -> nextUp(base)"
        val mediumGate = "base == DepthTier.SHALLOW && canGoMedium(cost, evidence) -> nextUp(base)"
        assertTrue("中→深只有 canGoDeep 一条路：$deepGate", rules.contains(deepGate))
        assertTrue("浅→中那道门钉着 base＝浅（拿它跨格＝绕灰度）：$mediumGate", rules.contains(mediumGate))
        assertEquals("升档的门恰有两道（浅→中、中→深），第三道＝没灰度也能进深",
            2, Regex("nextUp\\(base\\)").findAll(rules).count())
        assertTrue("灰度那一格在（没灰度就没深档）", code.contains("evidence.canary == CanaryStatus.AVAILABLE"))
        assertTrue(code.contains("fun affectsDepth(): Boolean = false"))
        assertTrue(code.contains("fun overridesVerdict(): Boolean = false"))
        assertEquals("深度档闭集三格", 3, DepthTier.entries.size)
    }

    @Test
    fun `源级_深度裁定的生产调用方必须在本格登记`() {
        val callers = LawIo.sources(LawIo.MAIN_ROOT)
            .filter { (rel, raw) ->
                !rel.endsWith("evolution/EvolutionDepth.kt") &&
                    LawIo.stripComments(raw).contains("EvolutionDepth.")
            }
            .map { it.first }
        // 第五百零五批原文：本批只做判定与账，接线归后续批——那一格当时断言 emptyList()。
        // 第五百零六批（后果治理）正是那个接线批：`consequence/` 两件开始读深度裁定
        // ⇒ 按 505 自己的话「接线批一旦接上，这格红着提醒改名册」，这里改名册，
        // 而不是把断言删掉（删了就等于以后谁都能悄悄接）。
        // 蓝总管验收接管（2026-11-07）：只改名册，语义仍是「新增调用方必须先改这格再合」。
        // 第五百一十七批（生产调用点）：`evolution/EvolutionWiring.kt` 在提案进入判定前调
        // [EvolutionDepth.adjudicate]（§一①），同轮 FAIL 当场降档（§一③）——进名册。
        assertEquals(
            "深度裁定的生产调用方名册（517 起进化链接线：EvolutionWiring 定档；新增必须先改这格再合）",
            listOf(
                "baopu/consequence/ConsequenceLedger.kt",
                "baopu/consequence/EvolutionDepth.kt",
                "baopu/evolution/EvolutionWiring.kt",
            ),
            callers.sorted(),
        )
    }
}
