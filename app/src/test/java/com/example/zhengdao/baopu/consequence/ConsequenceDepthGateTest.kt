// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百零六批（2026-11-07）· 追加口径「自进化深度裁定」的判据与负例。
//
// 追加判据①–⑤逐条对号：
//   ① 深度档落账（谁定的／依据哪几项／当时读数）
//   ② 新装首跑＝浅档（只记不改）
//   ③ 升档需三条同时满足
//   ④ 造一次 FAIL ⇒ 深度立刻降档（不等周期）
//   ⑤ 模型给自己的产出发通过 ⇒ 被拦**并记账**
// 负例：
//   D1 浅→深一步跳过中档
//   D2 灰度没到全开就升深（「深必须过本单的灰度」）
//   D3 坏消息排在好消息后面（降档等周期）
//   D4 落账没写依据／没写读数
//   D5 参谋意见里带了「判定结果」那一格（覆盖右相三态）
//   D6 参谋在理由里点名要升到某档（决定深度）
package com.example.zhengdao.baopu.consequence

import com.example.zhengdao.baopu.verify.ChancellorVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsequenceDepthGateTest {

    private val now = 1_760_000_000_000L
    private val cap = CanaryCapability.REFLECTION_AUTO_EDIT

    private val readyCost = DepthCostFacts(budgetLeft = true, patient = true, highConfidence = true, interruptionsToday = 1)
    private val passEvidence = DepthEvidenceFacts(
        verdict = ChancellorVerdict.PASS,
        evidenceEnough = true,
        hasCounterEvidence = false,
        consecutiveSuccess = 3,
        rollbackVerified = true,
    )
    private val basis = listOf("预算有余", "右相 PASS")
    private val readings = listOf("近 14 天 20 条／失败 0%")

    private fun dc(from: EvolutionDepth, to: EvolutionDepth, actor: String = "auto", b: List<String> = basis, r: List<String> = readings) =
        DepthChange(cap.wire, from.wire, to.wire, actor, now, b, r)

    // ── 追加判据② 首跑＝浅 ────────────────────────────────────────────────

    @Test
    fun `追加判据2a_新装首跑就是浅档`() {
        assertEquals(EvolutionDepth.SHALLOW, DepthGate.firstRunDepth())
        assertEquals(EvolutionDepth.DEFAULT, EvolutionDepth.SHALLOW)
        assertEquals(3, EvolutionDepth.entries.size)
    }

    @Test
    fun `追加判据2b_三档线值认不出返回null`() {
        assertNull(EvolutionDepth.ofWire("ultra"))
        assertFalse(EvolutionDepth.ofWire("deep") == null)
    }

    // ── 追加判据③ 升档三条件同时 ───────────────────────────────────────────

    @Test
    fun `追加判据3a_成本齐且右相PASS才升中`() {
        assertEquals(EvolutionDepth.MEDIUM, DepthGate.nextDepth(EvolutionDepth.SHALLOW, readyCost, passEvidence, CanaryBand.CANARY))
        // 成本缺一位
        assertFalse(DepthCostFacts(budgetLeft = false, patient = true, highConfidence = true).readyToRise)
        assertFalse(DepthCostFacts(budgetLeft = true, patient = true, highConfidence = false).readyToRise)
        // 当日打扰超上限也不动档（动档本身就是一次打扰）
        assertFalse(DepthCostFacts(budgetLeft = true, patient = true, highConfidence = true, interruptionsToday = 99).readyToRise)
        assertEquals(
            EvolutionDepth.SHALLOW,
            DepthGate.nextDepth(EvolutionDepth.SHALLOW, DepthCostFacts(budgetLeft = true, patient = true, highConfidence = true, interruptionsToday = 99), passEvidence, CanaryBand.FULL),
        )
        // 右相不是 PASS
        val weak = passEvidence.copy(verdict = ChancellorVerdict.UNVERIFIABLE)
        assertEquals(EvolutionDepth.SHALLOW, DepthGate.nextDepth(EvolutionDepth.SHALLOW, readyCost, weak, CanaryBand.FULL))
        // 有反证
        val countered = passEvidence.copy(hasCounterEvidence = true)
        assertEquals(EvolutionDepth.SHALLOW, DepthGate.nextDepth(EvolutionDepth.SHALLOW, readyCost, countered, CanaryBand.FULL))
    }

    @Test
    fun `追加判据3b_负例D2_升深必须灰度已全开`() {
        val atMedium = DepthGate.nextDepth(EvolutionDepth.MEDIUM, readyCost, passEvidence, CanaryBand.CANARY)
        assertEquals("灰度没到全开就不许升深", EvolutionDepth.MEDIUM, atMedium)
        assertEquals(EvolutionDepth.DEEP, DepthGate.nextDepth(EvolutionDepth.MEDIUM, readyCost, passEvidence, CanaryBand.FULL))
    }

    @Test
    fun `追加判据3c_升深要连续成功够N次且回滚已验证`() {
        assertFalse(DepthGate.canRiseToDeep(passEvidence.copy(consecutiveSuccess = DepthGate.CONSECUTIVE_SUCCESS_FOR_DEEP - 1), CanaryBand.FULL))
        assertFalse(DepthGate.canRiseToDeep(passEvidence.copy(rollbackVerified = false), CanaryBand.FULL))
        assertTrue(DepthGate.canRiseToDeep(passEvidence, CanaryBand.FULL))
        assertFalse(DepthGate.canRiseToMedium(passEvidence.copy(evidenceEnough = false)))
    }

    @Test
    fun `负例D1_一次跳两档被拒`() {
        val r = DepthGate.refusalOf(dc(EvolutionDepth.SHALLOW, EvolutionDepth.DEEP))
        assertNotNull(r)
        assertTrue(r!!.contains("一次只许动一档"))
        assertNull(ConsequenceLedger.draftOf(dc(EvolutionDepth.SHALLOW, EvolutionDepth.DEEP)))
    }

    @Test
    fun `负例D1b_一次踢两档向下也不行`() {
        // 只写 `to - from > 1` 的实现抓不到这条（深→浅是 -2）。
        val r = DepthGate.refusalOf(dc(EvolutionDepth.DEEP, EvolutionDepth.SHALLOW))
        assertNotNull("降档同样一次一档", r)
        assertTrue(r!!.contains("一次只许动一档"))
    }

    // ── 追加判据④ 坏消息立刻降一档 ─────────────────────────────────────────

    @Test
    fun `追加判据4_负例D3_一次FAIL立刻降档不等周期`() {
        val failed = passEvidence.copy(verdict = ChancellorVerdict.FAIL)
        // 成本三位全 good（readyToRise=true）也必须降——坏消息排在升档判断之前
        assertTrue(readyCost.readyToRise)
        assertEquals(EvolutionDepth.MEDIUM, DepthGate.nextDepth(EvolutionDepth.DEEP, readyCost, failed, CanaryBand.FULL))
        assertEquals(EvolutionDepth.MEDIUM, DepthGate.nextDepth(EvolutionDepth.DEEP, readyCost, passEvidence.copy(unverifiableUp = true), CanaryBand.FULL))
        assertEquals(EvolutionDepth.MEDIUM, DepthGate.nextDepth(EvolutionDepth.DEEP, readyCost, passEvidence.copy(falseBlockUp = true), CanaryBand.FULL))
        assertEquals("浅档没有可降的下一档", EvolutionDepth.SHALLOW, DepthGate.nextDepth(EvolutionDepth.SHALLOW, readyCost, failed, CanaryBand.FULL))
    }

    // ── 追加判据① 落账带谁定的／依据／读数 ────────────────────────────────

    @Test
    fun `追加判据1_深度行落账带三样`() {
        val d = ConsequenceLedger.draftOf(dc(EvolutionDepth.SHALLOW, EvolutionDepth.MEDIUM))!!
        assertEquals("tool_grant", d.kind.wire)
        assertTrue(d.evidence.startsWith("${ConsequenceEvidence.DEPTH}${cap.wire}:medium"))
        assertTrue("依据哪几项要写进理由", d.reason.contains("预算有余") && d.reason.contains("右相 PASS"))
        assertTrue("当时的读数要进 evidence", d.evidence.contains("失败 0%"))
        assertEquals("shallow", d.before)
        assertEquals("medium", d.after)
        assertEquals("auto", d.actor.wire)
        assertNotNull(d.rollbackRef)
    }

    @Test
    fun `负例D4_没依据或没读数不落账`() {
        assertNotNull(DepthGate.refusalOf(dc(EvolutionDepth.SHALLOW, EvolutionDepth.MEDIUM, b = emptyList())))
        assertNotNull(DepthGate.refusalOf(dc(EvolutionDepth.SHALLOW, EvolutionDepth.MEDIUM, b = listOf("只有左相"))))
        assertNotNull(DepthGate.refusalOf(dc(EvolutionDepth.SHALLOW, EvolutionDepth.MEDIUM, r = emptyList())))
        assertEquals(2, DepthGate.MIN_BASIS)
    }

    @Test
    fun `追加判据1b_模型定的档不落账`() {
        val r = DepthGate.refusalOf(dc(EvolutionDepth.SHALLOW, EvolutionDepth.MEDIUM, actor = IdentityRules.ACTOR_MODEL))
        assertNotNull(r)
        assertTrue(r!!.contains("模型定"))
        assertNull(ConsequenceLedger.draftOf(dc(EvolutionDepth.SHALLOW, EvolutionDepth.MEDIUM, actor = "model")))
    }

    @Test
    fun `档位没动不落空变动`() {
        assertNotNull(DepthGate.refusalOf(dc(EvolutionDepth.MEDIUM, EvolutionDepth.MEDIUM)))
    }

    // ── 追加判据⑤ 参谋不给票 ──────────────────────────────────────────────

    private fun op(
        advice: String = "pass",
        reason: String = "这一步的改动只动了措辞",
        own: Boolean = false,
        advisor: String = IdentityRules.ACTOR_MODEL,
    ) = AdvisorOpinion(advisor, advice, reason, listOf("改错了不好回滚"), own, now)

    @Test
    fun `追加判据5a_模型给自己的产出发通过被拦`() {
        val r = AdvisorRules.rejection(op(own = true))
        assertNotNull(r)
        assertTrue(r!!.contains("给自己的产出发通过"))
    }

    @Test
    fun `负例D5_参谋不许冒充右相三态`() {
        val r = AdvisorRules.rejection(op(advice = "PASS"))
        assertNotNull(r)
        assertTrue(r!!.contains("三态"))
    }

    @Test
    fun `负例D6_参谋不许在理由里点名档位`() {
        val r = AdvisorRules.rejection(op(reason = "建议把这族升到深档"))
        assertNotNull(r)
        assertTrue(r!!.contains("深度档"))
        assertNotNull(AdvisorRules.rejection(op(reason = "should go deep now")))
    }

    @Test
    fun `追加判据5b_拦下来也要落一行`() {
        val o = op(own = true)
        val rejection = AdvisorRules.rejection(o)
        assertNotNull(rejection)
        val d = ConsequenceLedger.draftOf(o, rejection)
        assertEquals("advisor_opinion", d.kind.wire)
        assertEquals("blocked", d.after)
        assertTrue(d.evidence.startsWith("${ConsequenceEvidence.ADVISOR}model:blocked"))
        assertTrue("被拦的原因要留在账上", d.reason.contains("给自己的产出发通过"))
        assertEquals("own-output", d.before)
        // 收下的那一条长成另一样
        val ok = ConsequenceLedger.draftOf(op(advice = "fail", own = false), null)
        assertEquals("accepted", ok.after)
        assertEquals("others-output", ok.before)
    }

    @Test
    fun `追加判据5c_形状上就没有那一格可以装票`() {
        // 判据⑤「不给自己发票／不覆盖三态／不定深度档」最硬的一条是**类型里没有那几格**：
        // 反射点名字段，谁将来加一个 verdict/depth 字段，这条就红。
        val fields = AdvisorOpinion::class.java.declaredFields
            // Kotlin 还会给 data class 发一个 `public static final int $stable`（javap 实测，
            // isSynthetic=false），它不是"能装票的那一格"，按名字前缀一起剔掉。
            .filterNot { it.isSynthetic || it.name.startsWith('$') }
            .map { it.name }
            .sorted()
        assertEquals(listOf("aboutOwnOutput", "advice", "advisor", "atMs", "reason", "risks"), fields)
        assertTrue(AdvisorRules.suggestsPass(op(advice = AdvisorRules.ADVICE_PASS)))
        assertNotNull(AdvisorRules.rejection(op(advice = "maybe")))
        assertNotNull(AdvisorRules.rejection(op(reason = "  ")))
    }
}
