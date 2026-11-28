// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第九百三十五批 · 判据测试。
// 每条用例打印完整输入与判定行——873 第一步要的「完整输出」就从这里取。
// 第九百四十三批 · 表搬 manifest：传闻行从 blacklist-manifest.json 装配（读端只认喂入的表）。
package com.example.zhengdao.baopu.state.bodyparts

import com.example.zhengdao.BlacklistManifestFeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test

class ShapeJudgeTest {

    companion object {
        /** 943：judgeText 默认参数读 ShapeRumorCatalog.entries，先把 manifest 行喂进去。 */
        @BeforeClass
        @JvmStatic
        fun feedManifestRows() {
            BlacklistManifestFeed.installShapeMyth()
        }
    }

    private fun findings(claims: List<Claim>): List<ShapeFinding> = ShapeJudge.all(claims)

    private fun denied(case: ShapeFixtures.Case): List<ShapeFinding> =
        ShapeJudge.judge(case.claim).filter { it.verdict == Verdict.DENY }

    /** 一条拒绝用例的通用核法：必须红，且代码对得上。 */
    private fun assertDenied(case: ShapeFixtures.Case) {
        val found = denied(case)
        println("FIXTURE=" + case.id + " voice=" + case.claim.voice.label + " code=" + case.code)
        println("JUDGE=" + ShapeJudge.judge(case.claim).joinToString(" | ") { it.line() })
        assertTrue("该红没红：" + case.id, found.isNotEmpty())
        assertTrue(
            "红的代码不对：" + case.id + " 期望 " + case.code + " 实得 " + found.map { it.code },
            found.any { it.code == case.code },
        )
    }

    @Test
    fun `三条必造夹具都能构造且判过`() {
        val cases = listOf(
            "F1-腹肌明显×能力差" to ShapeFixtures.absVisibleAbilityWeak,
            "F2-圆润×张力高" to ShapeFixtures.roundTensionHigh,
            "F3-尺寸偏小×吸引力强" to ShapeFixtures.smallAttractive,
        )
        cases.forEach { (name, claims) ->
            val profile = ShapeProfile(
                shape = claims.filter { it.judged == null }.flatMap { it.shape }.associateWith { tierOf(it) },
                preferences = claims.mapNotNull { it.preference },
                claims = claims,
            )
            val out = ShapeSheet.render(profile)
            println("===== " + name + " =====")
            println(out)
            val found = findings(claims)
            assertTrue(name + " 被判成矛盾：" + found.map { it.line() }, found.isEmpty())
        }
    }

    @Test
    fun `三枪对位的三条因果夹具必红`() {
        listOf(
            ShapeFixtures.absToTension,
            ShapeFixtures.sizeToAbility,
            ShapeFixtures.absToAttraction,
        ).forEach { assertDenied(it) }
    }

    @Test
    fun `形态推能力_推张力_推吸引力_其余三条也红`() {
        listOf(
            ShapeFixtures.absToAbility,
            ShapeFixtures.roundToTension,
            ShapeFixtures.pecToAttraction,
            ShapeFixtures.noAbsLostAttraction,
            ShapeFixtures.tensionShapeOnly,
        ).forEach { assertDenied(it) }
    }

    @Test
    fun `喉结不明显张力高允许_喉结大气概必红`() {
        val pass = findings(ShapeFixtures.larynxFaintTensionHigh)
        println("FIXTURE=F4 喉结不明显×张力高 findings=" + pass.size)
        assertTrue("喉结不明显×张力高 不许判红：" + pass.map { it.line() }, pass.isEmpty())
        assertDenied(ShapeFixtures.larynxMasculinity)
        // 反向：喉结大 ⇒ 不够男人，同样红（气概符号两头都禁）
        val reverse = Claim("喉结不明显，所以不够男人。", Voice.NARRATOR,
            shape = setOf(ShapeAspect.LARYNX), judged = Judged.MORAL)
        val found = ShapeJudge.judge(reverse)
        println("JUDGE=" + found.joinToString(" | ") { it.line() })
        assertTrue("喉结反推气概也要红", found.any { it.code == "LARYNX-MASCULINITY" && it.verdict == Verdict.DENY })
    }

    @Test
    fun `阴囊松弛吸引力强允许_松弛等于纵欲必红且角色说也红`() {
        val pass = findings(ShapeFixtures.scrotumLaxAttractive)
        println("FIXTURE=F5 阴囊松弛×吸引力强 findings=" + pass.size)
        assertTrue("阴囊松弛×吸引力强 不许判红：" + pass.map { it.line() }, pass.isEmpty())
        assertDenied(ShapeFixtures.scrotumStigma)
        assertEquals("污名化不分声音：角色说也是红", 1, denied(ShapeFixtures.scrotumStigma).size)
    }

    @Test
    fun `睾丸偏小能力正常允许_大小推生育力必红`() {
        val pass = findings(ShapeFixtures.testisSmallAbilityNormal)
        println("FIXTURE=F6 睾丸偏小×能力正常 findings=" + pass.size)
        assertTrue("睾丸偏小×能力正常 不许判红：" + pass.map { it.line() }, pass.isEmpty())
        assertDenied(ShapeFixtures.testisFertility)
        val asCharacter = ShapeFixtures.testisFertility.claim.copy(voice = Voice.CHARACTER)
        val found = ShapeJudge.judge(asCharacter)
        println("JUDGE=" + found.joinToString(" | ") { it.line() })
        assertTrue("生育力判断不分声音，角色说也红", found.any { it.code == "TESTIS-FERTILITY" })
    }

    @Test
    fun `能力只许四源_张力只许三源_给了就过`() {
        val ability = findings(ShapeFixtures.abilityFourSources)
        println("FIXTURE=F7 能力四源 findings=" + ability.size)
        assertTrue("四源都该过：" + ability.map { it.line() }, ability.isEmpty())
        val tension = findings(ShapeFixtures.tensionThreeSources)
        println("FIXTURE=F8 张力三源 findings=" + tension.size)
        assertTrue("三源都该过：" + tension.map { it.line() }, tension.isEmpty())
        assertDenied(ShapeFixtures.abilityNoSource)
        assertDenied(ShapeFixtures.attractionNoHolder)
    }

    @Test
    fun `描写侧不被误杀`() {
        val killed = ArrayList<String>()
        ShapeFixtures.descriptions.forEach { text ->
            val found = ShapeJudge.judgeText(text, Voice.NARRATOR)
            if (found.isNotEmpty()) killed += text + " -> " + found.map { it.code }
        }
        println("FIXTURE=DESC total=" + ShapeFixtures.descriptions.size + " killed=" + killed)
        assertEquals("描写被误杀：" + killed, emptyList<String>(), killed)
    }

    @Test
    fun `身材羞辱与健身崇拜两面都禁_角色说降为偏见`() {
        ShapeFixtures.shameCases.forEach { assertDenied(it) }
        val asCharacter = ShapeFixtures.shameCases.first().claim.copy(voice = Voice.CHARACTER)
        val found = ShapeJudge.judge(asCharacter)
        println("JUDGE=" + found.joinToString(" | ") { it.line() })
        assertTrue("角色说羞辱应是须核不是红", found.any { it.code == "BODY-SHAME" && it.verdict == Verdict.NEED_CHECK })
        assertFalse("角色说羞辱不许判红", found.any { it.code == "BODY-SHAME" && it.verdict == Verdict.DENY })
    }

    @Test
    fun `评分式说法任何声音都红`() {
        assertDenied(ShapeFixtures.scoreCase)
    }

    @Test
    fun `口味必须带who_三人口味冲突不算矛盾`() {
        try {
            Preference("", ShapeAspect.ABS_BLOCK, PreferenceTier.LIKES)
            fail("缺 who 必须构造不出来")
        } catch (expected: IllegalArgumentException) {
            println("GUARD=Preference 缺 who -> " + expected.message)
        }
        val preferences = listOf(
            Preference("甲", ShapeAspect.ABS_BLOCK, PreferenceTier.LIKES),
            Preference("乙", ShapeAspect.ABS_BLOCK, PreferenceTier.DISLIKES),
            Preference("丙", ShapeAspect.ABS_BLOCK, PreferenceTier.NEUTRAL),
        )
        val profile = ShapeProfile(
            shape = mapOf(ShapeAspect.ABS_BLOCK to "分块明显"),
            preferences = preferences,
            claims = preferences.map {
                Claim(it.line(), Voice.CHARACTER, judged = Judged.ATTRACTION,
                    sources = setOf(Source.PREFERENCE), preference = it)
            },
        )
        println("===== 三人口味冲突 =====")
        println(ShapeSheet.render(profile))
        assertTrue("口味冲突不算矛盾：" + ShapeJudge.all(profile.claims).map { it.line() },
            ShapeJudge.all(profile.claims).isEmpty())
        val lines = ShapeSheet.sourceLines(profile)
        val preferenceLines = lines.filter { it.contains("（口味）") }
        assertEquals("三条口味各成一行（不含断言来源行）：" + lines, 3, preferenceLines.size)
        preferences.forEach { p ->
            assertTrue("来源行缺持有者 " + p.who, preferenceLines.any { it.contains(p.who) })
        }
    }

    @Test
    fun `同一输入跑两遍逐字一致`() {
        val claims = ShapeFixtures.absVisibleAbilityWeak + ShapeFixtures.roundToTension.claim
        val first = ShapeJudge.all(claims).joinToString("\n") { it.line() }
        val second = ShapeJudge.all(claims).joinToString("\n") { it.line() }
        assertEquals("判据不纯：两遍结果不同", first, second)
        println("PURE=一致 lines=" + first.lines().size)
    }

    @Test
    fun `出口拒绝长度单位与判词`() {
        val dirty = listOf(
            "约 7" + "c" + "m",
            "直径 3" + "c" + "m",
            "异常",
            "综合" + "分 8",
        )
        dirty.forEach { text ->
            try {
                ShapeFinding(Verdict.DENY, "X", text)
                fail("脏结论必须造不出来：" + text)
            } catch (expected: IllegalArgumentException) {
                println("GUARD=出口拦下 " + text + " -> " + expected.message)
            }
        }
    }

    private fun tierOf(aspect: ShapeAspect): String = when (aspect) {
        ShapeAspect.ABS_BLOCK -> "分块明显"
        ShapeAspect.BODY_ROUNDNESS -> "圆润"
        ShapeAspect.GENITAL_LENGTH -> "偏短"
        ShapeAspect.LARYNX -> "只略见"
        ShapeAspect.SCROTUM_LAXITY -> "松"
        ShapeAspect.TESTIS_SIZE -> "偏小"
        else -> "中"
    }
}
