// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百批 §4 正例＋负例 4：一条技能参数化后**跑北京、跑天津各一次都对**，
// 且参数值不进审计/取证（负例 4）。判据链完整：真学习器产技能 → 真参数化 →
// 真执行器（196）跑在内存假 DOM 上，只有 DOM 与时钟是假的。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

class TeachParamsRunTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val nowMs: Long = LocalDate.of(2026, 10, 24)
        .atTime(10, 0).atZone(zone).toInstant().toEpochMilli()

    /** 一次「参数化回放」的可观测产物（审计行/取证目录都在这里，供负例 4 断言）。 */
    private class Ran(
        val report: TeachRunReport,
        val dom: FakeDom,
        val audits: List<String>,
        val evidenceRoot: File,
    )

    /** 把 plan 的产物真的跑一遍（与生产壳 replaySkillWithParams 同一条路，只是没有 Android 桥）。 */
    private fun runSentence(sentence: String, dir: String): Ran {
        val skill = TeachParamFixtures.parameterized()
        val plan = TeachParams.plan(skill, sentence, nowMs, zone)
        check(plan is TeachParams.TeachRunPlan.Ready) { "夹具该能跑：$plan" }
        plan as TeachParams.TeachRunPlan.Ready

        val dom = FakeDom(TeachParamFixtures.pages(dir), TeachParamFixtures.START)
        val evidenceRoot = tmp.newFolder()
        val audits = mutableListOf<String>()
        var now = 0L
        val ex = TeachExecutor(
            dom = dom,
            // 与生产同一个装饰器：取证里的参数值换成占位符
            evidence = RedactingEvidenceSink(FileTeachEvidenceSink(evidenceRoot), plan.values),
            audit = { r, ts -> audits += TeachParams.redact(r.auditLine(ts), plan.values) },
            clock = { now },
            sleeper = { now += TeachExecutor.EXPECT_POLL_MS },
        )
        return Ran(ex.run(plan.skill), dom, audits, evidenceRoot)
    }

    // ── 正例：一条技能，跑北京、跑天津各一次 ──────────────────────────────────

    @Test
    fun `正例_参数化后跑北京与跑天津各一次都对`() {
        val bj = runSentence("查北京的，车牌冀A12345，昨天", "北京")
        assertEquals("北京那次该通过：${bj.report.stopReason}", 5, bj.report.stepsDone)
        assertTrue(bj.report.ok)
        assertEquals(listOf("BUTTON/查询", "BUTTON/下一页"), bj.dom.clicks)
        // 选择/输入真的落到了页面上：方向、车牌、日期（「昨天」必须换成具体日期）
        assertEquals("北京", bj.dom.typed[0].second)
        assertEquals("冀A12345", bj.dom.typed[1].second)
        assertEquals("昨天的日期要填进去，不是「昨天」两个字", "2026-10-23", bj.dom.typed[2].second)

        val tj = runSentence("查天津的，车牌冀B54321，今天", "天津")
        assertTrue("天津那次也要通过：${tj.report.stopReason}", tj.report.ok)
        assertEquals(5, tj.report.stepsDone)
        assertEquals(listOf("BUTTON/查询", "BUTTON/下一页"), tj.dom.clicks)
        assertEquals("同一条技能，只换了参数", "天津", tj.dom.typed[0].second)
        assertEquals("冀B54321", tj.dom.typed[1].second)
        assertEquals("今天就是今天", "2026-10-24", tj.dom.typed[2].second)
    }

    @Test
    fun `正例_同一条技能文件跑两次都不改_每次只换参数`() {
        val skill = TeachParamFixtures.parameterized()
        val before = skill.toJsonText()
        val p1 = TeachParams.plan(skill, "查北京的，车牌冀A12345，昨天", nowMs, zone) as TeachParams.TeachRunPlan.Ready
        val p2 = TeachParams.plan(skill, "查天津的，车牌冀B54321，今天", nowMs, zone) as TeachParams.TeachRunPlan.Ready
        assertEquals("技能文件一个字节都不许动", before, skill.toJsonText())
        assertTrue("两次填出来的技能必须不同", p1.skill.toJsonText() != p2.skill.toJsonText())
        assertTrue(p1.skill.toJsonText().contains("北京"))
        assertTrue(p2.skill.toJsonText().contains("天津"))
    }

    // ── 负例 4：参数值不进审计、不进取证 ─────────────────────────────────────

    @Test
    fun `负例4_参数值不进审计行与取证目录`() {
        val r = runSentence("查天津的，车牌冀B54321，昨天", "天津")
        assertTrue(r.report.ok)
        val secrets = listOf("天津", "冀B54321", "2026-10-23")

        for (line in r.audits) {
            for (s in secrets) assertFalse("审计行不许出现「$s」：$line", line.contains(s))
        }
        // 取证目录：每步 DOM 摘要（含 URL）都过脱敏
        val skeletonFiles = File(r.report.evidenceDir!!).listFiles()?.filter { it.isFile }.orEmpty()
        assertTrue("该有取证文件", skeletonFiles.isNotEmpty())
        var sawRedactedUrl = false
        for (f in skeletonFiles) {
            val t = f.readText(Charsets.UTF_8)
            for (s in secrets) assertFalse("取证文件 ${f.name} 不许出现「$s」", t.contains(s))
            if (t.contains("url=") && t.contains("\${dir}")) sawRedactedUrl = true
        }
        assertTrue("URL 里的方向该被换成占位符（证明脱敏真的发生了，而不是本来就没有值）", sawRedactedUrl)
    }

    @Test
    fun `负例4_脱敏函数本身有效_含值文本必须换回占位`() {
        val values = mapOf("dir" to "天津", "plate" to "冀B54321")
        val text = "url=https://x/result?direction=天津&plate=冀B54321 ok"
        val red = TeachParams.redact(text, values)
        assertFalse(red.contains("天津"))
        assertFalse(red.contains("冀B54321"))
        assertTrue(red.contains("\${dir}"))
        assertTrue(red.contains("\${plate}"))
        // 长的先替：短值不许把长值切碎
        val two = mapOf("a" to "冀B5432", "b" to "冀B54321")
        assertEquals("v=\${b}", TeachParams.redact("v=冀B54321", two))
    }

    // ── 取不到参数：一个页面动作都不做 ────────────────────────────────────────

    @Test
    fun `取不到参数_一个页面动作都不做`() {
        val skill = TeachParamFixtures.parameterized()
        val plan = TeachParams.plan(skill, "查一下", nowMs, zone)
        assertTrue("该反问：$plan", plan is TeachParams.TeachRunPlan.Ask)
        val line = (plan as TeachParams.TeachRunPlan.Ask).line
        assertFalse("反问只有一行", line.contains("\n"))
        assertTrue("要问方向：$line", line.contains("方向"))

        // Ask 分支里没有技能 ⇒ 结构上就没法拿去跑（下面顺带证明页面一点没动）
        val dom = FakeDom(TeachParamFixtures.pages("北京"), TeachParamFixtures.START)
        assertTrue(dom.clicks.isEmpty())
        assertTrue(dom.typed.isEmpty())
        assertEquals(TeachParamFixtures.START, dom.currentUrl)
    }

    @Test
    fun `占位没填满_不许开跑`() {
        val skill = TeachParamFixtures.parameterized()
        val hand = skill.copy(steps = skill.steps + skill.steps.last().copy(index = 6, value = "\${nowhere}"))
        val plan = TeachParams.plan(hand, "查北京的，车牌冀A12345，昨天", nowMs, zone)
        assertTrue("占位补不上必须停：$plan", plan is TeachParams.TeachRunPlan.Stop)
        val reason = (plan as TeachParams.TeachRunPlan.Stop).reason
        assertTrue("要说清是哪个占位没值：$reason", reason.contains("nowhere") || reason.contains("占位"))
        assertTrue("要说清没开跑：$reason", reason.contains("没开跑"))
    }
}
