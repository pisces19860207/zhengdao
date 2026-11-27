// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第九百三十九批 · 判据：唯一硬红（未成年＋性目的）、撤销过紧口径的放行、仍禁两条。
// 每个断言都按 code 说话，不只断言「有红」——否则拆掉一条判据也看不出来。
package com.example.zhengdao.baopu.state.bodyparts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PubertyJudgeTest {

    private fun codes(frame: PurposeFrame): List<String> =
        PubertyJudge.purposeCheck(frame).findings().map { it.code }

    private fun render(
        history: PubertyHistory = PubertyFixtures.boy14(),
        frame: PurposeFrame = PubertyFixtures.nonSexualMinor,
    ): String = PubertySheet.render(history, frame)

    private fun findingsOf(text: String): List<PubertyFinding> = PubertyJudge.textFindings(text)

    // ── 目的判定：允许侧 ──────────────────────────────────────────────

    @Test
    fun `未成年但非性目的_允许`() {
        val check = PubertyJudge.purposeCheck(PubertyFixtures.nonSexualMinor)
        assertEquals(PurposeVerdict.ALLOW, check.verdict)
        assertTrue("允许侧不给要素", check.elements.isEmpty())
        assertEquals(PubertyJudge.ALLOW_MINOR, check.why)
        assertEquals(listOf(PurposeCheck.CODE_ALLOW), codes(PubertyFixtures.nonSexualMinor))
        println(check.findings().joinToString("\n") { it.line() })
    }

    @Test
    fun `成年加三要素也允许`() {
        val check = PubertyJudge.purposeCheck(PubertyFixtures.adultAllThree)
        assertEquals("成年不在硬红里", PurposeVerdict.ALLOW, check.verdict)
        assertEquals(PubertyJudge.ALLOW_ADULT, check.why)
        assertEquals(listOf(PurposeCheck.CODE_ALLOW), codes(PubertyFixtures.adultAllThree))
    }

    // ── 目的判定：红侧，三要素各一枪 ──────────────────────────────────

    @Test
    fun `未成年加性场景_红`() {
        val check = PubertyJudge.purposeCheck(PubertyFixtures.minorScene)
        assertEquals(PurposeVerdict.RED, check.verdict)
        assertEquals(listOf(SexualElement.SCENE), check.elements)
        assertEquals(listOf("MINOR-SEXUAL-SCENE"), codes(PubertyFixtures.minorScene))
        println(check.findings().first().line())
    }

    @Test
    fun `未成年加特写性器官_红`() {
        assertEquals(listOf("MINOR-GENITAL-FOCUS"), codes(PubertyFixtures.minorGenital))
        assertEquals(PurposeVerdict.RED, PubertyJudge.purposeCheck(PubertyFixtures.minorGenital).verdict)
    }

    @Test
    fun `未成年加性化凝视_红`() {
        assertEquals(listOf("MINOR-SEXUALIZED-GAZE"), codes(PubertyFixtures.minorGaze))
        assertEquals(PurposeVerdict.RED, PubertyJudge.purposeCheck(PubertyFixtures.minorGaze).verdict)
    }

    @Test
    fun `三要素齐_三条码全给`() {
        val check = PubertyJudge.purposeCheck(PubertyFixtures.minorAllThree)
        assertEquals(PurposeVerdict.RED, check.verdict)
        assertEquals("三要素一条不漏", 3, check.elements.size)
        assertEquals(
            setOf("MINOR-SEXUAL-SCENE", "MINOR-GENITAL-FOCUS", "MINOR-SEXUALIZED-GAZE"),
            codes(PubertyFixtures.minorAllThree).toSet(),
        )
        println(check.findings().joinToString("\n") { it.line() })
    }

    @Test
    fun `判定只有两值_红必须给得出要素`() {
        assertEquals("ALLOW／RED 两值", 2, PurposeVerdict.entries.size)
        assertEquals(PurposeVerdict.COUNT, PurposeVerdict.entries.size)
        assertEquals("三要素闭集", 3, SexualElement.entries.size)
        assertEquals(SexualElement.COUNT, SexualElement.entries.size)
        val thrown = runCatching {
            PurposeCheck(PurposeVerdict.RED, emptyList(), "说红却没说为什么")
        }.exceptionOrNull()
        assertTrue("红而无要素构造即抛", thrown is IllegalArgumentException)
    }

    // ── 撤销过紧口径：三类旧禁项必须放行 ──────────────────────────────

    @Test
    fun `年龄数值放行`() {
        val text = render()
        assertTrue("年龄要原样出线", text.contains("年龄：14 岁"))
        val findings = PubertyJudge.check(PubertyFixtures.boy14(), PubertyFixtures.nonSexualMinor)
        assertEquals("年龄不产生红", 0, findings.count { it.verdict == Verdict.DENY })
        assertEquals("只有一条允许行", listOf(PurposeCheck.CODE_ALLOW), findings.map { it.code })
        println(text)
    }

    @Test
    fun `身体变化细节放行`() {
        val text = render()
        assertTrue(text.contains("嗓子哑了一阵，后来又稳下来"))
        assertTrue(text.contains("一年里裤腿短了两回"))
        val findings = PubertyJudge.check(PubertyFixtures.boy14(), PubertyFixtures.nonSexualMinor)
        assertFalse("细节多不等于红", findings.any { it.verdict == Verdict.DENY })
    }

    @Test
    fun `既往史细节放行`() {
        val text = render()
        assertTrue("既往史要原样出线", text.contains("小时候摔过一次，膝盖留了疤"))
        assertTrue("既往史不进判定", findingsOf("小时候摔过一次，膝盖留了疤").isEmpty())
    }

    // ── 仍禁两条：医学结论与推断 ──────────────────────────────────────

    @Test
    fun `医学结论_性早熟直接红`() {
        val findings = findingsOf(PubertyFixtures.DIAGNOSIS_EARLY)
        assertEquals("诊断结论一条红", listOf(PubertyJudge.CODE_DIAGNOSIS), findings.map { it.code })
        assertEquals(Verdict.DENY, findings.first().verdict)
        val hit = PubertyGuard.diagnosisHit(PubertyFixtures.DIAGNOSIS_EARLY)
        assertEquals("命中项要能点名（供测试，不出现在产物里）", "性早熟", hit)
        println(findings.first().line())
    }

    @Test
    fun `医学结论_发育迟直接红`() {
        val findings = findingsOf(PubertyFixtures.DIAGNOSIS_DELAYED)
        assertEquals(listOf(PubertyJudge.CODE_DIAGNOSIS), findings.map { it.code })
        assertFalse("不能把整句回显成产物", findings.first().why.contains("发育迟"))
    }

    @Test
    fun `诊断结论在单里_行被拦下且留红`() {
        val history = PubertyFixtures.boy14().copy(
            details = PubertyFixtures.boy14().details + PubertyFixtures.DIAGNOSIS_EARLY,
        )
        val text = render(history)
        assertTrue("越界那一行要拦下", text.contains(PubertySheet.BLOCK_PREFIX))
        assertFalse("拦下的行不外显", text.contains("性早熟"))
        assertTrue("判据仍出红", text.contains("[红] " + PubertyJudge.CODE_DIAGNOSIS))
        assertTrue("其余细节照常读", text.contains("膝盖留了疤"))
        println(text)
    }

    @Test
    fun `由发育推能力_红`() {
        val findings = findingsOf(PubertyFixtures.INFER_ABILITY)
        assertEquals(listOf(PubertyJudge.CODE_INFER), findings.map { it.code })
        assertEquals(Verdict.DENY, findings.first().verdict)
        println(findings.first().line())
    }

    @Test
    fun `由发育推品行_红`() {
        val findings = findingsOf(PubertyFixtures.INFER_MORAL)
        assertEquals(listOf(PubertyJudge.CODE_INFER), findings.map { it.code })
    }

    @Test
    fun `三环缺一不红_合规负例两条`() {
        assertTrue("有推导词、无结论词 ⇒ 不红", findingsOf(PubertyFixtures.OK_HOST).isEmpty())
        assertTrue("推的是身高这种形态 ⇒ 不红", findingsOf(PubertyFixtures.OK_TALLER).isEmpty())
        assertEquals("推断要三环齐", null, PubertyGuard.inferenceHit(PubertyFixtures.OK_HOST))
        assertEquals("结论词能被点名（供测试）", "能力强", PubertyGuard.inferenceHit(PubertyFixtures.INFER_ABILITY))
    }

    // ── 结构与自洽 ────────────────────────────────────────────────────

    @Test
    fun `同一份发育史换目的_档位段一字不改`() {
        val allow = render(frame = PubertyFixtures.nonSexualMinor)
        val deny = render(frame = PubertyFixtures.minorScene)
        val allowTiers = allow.substringBefore(PubertySheet.MILESTONE_HEAD)
        val denyTiers = deny.substringBefore(PubertySheet.MILESTONE_HEAD)
        assertEquals("目的不该动到档位", allowTiers, denyTiers)
        assertFalse("允许段不该出现要素码", allow.contains("MINOR-SEXUAL-SCENE"))
        assertTrue("红段必须出现要素码", deny.contains("MINOR-SEXUAL-SCENE"))
    }

    @Test
    fun `同输入跑两遍逐字一致`() {
        val a = render()
        val b = render()
        assertEquals("纯函数：两遍必须逐字相同", a, b)
        assertTrue("头标记在", a.startsWith(PubertySheet.HEAD))
        assertTrue("尾标记在", a.endsWith(PubertySheet.TAIL))
    }

    @Test
    fun `结论行不出长度百分比`() {
        val findings = PubertyJudge.check(PubertyFixtures.boy14(), PubertyFixtures.minorAllThree) +
            findingsOf(PubertyFixtures.DIAGNOSIS_EARLY) + findingsOf(PubertyFixtures.INFER_ABILITY)
        findings.forEach { finding ->
            assertEquals("结论行不许带长度或比例：" + finding.code, null, BodyPartGuard.measureHit(finding.line()))
            assertTrue("结论行要有出处", finding.line().contains(PUBERTY_SOURCE))
        }
        println(PubertyJudge.summary(findings))
    }
}
