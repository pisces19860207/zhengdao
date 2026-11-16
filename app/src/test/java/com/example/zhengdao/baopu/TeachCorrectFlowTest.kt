// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十四批 C（阶段三前段）· 判据①③的运行时证据（FakeDom 直打真执行器）：
//   判据① 插话纠正 ⇒ 当步结束即暂停（负例：暂停闸被忽略/不接 ⇒ 红）；
//   判据③ 从第 N 步重跑——前 N-1 步**不重执行**（断言 FakeDom 的调用次数）；
//   判据② 暂停报告与建议里**不出现重录话术**（runtime 半边；源码半边在 GateTest）。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.correct.CorrectInbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TeachCorrectFlowTest {

    @get:Rule
    val tmp = TemporaryFolder()

    val START = "https://search.example.com/start"
    val RESULTS = "https://search.example.com/results?wb=WB12345678"
    val EXPORT = "https://search.example.com/export"

    private fun rows(n: Int, prefix: String = "行"): List<FakeEl> =
        (1..n).map { FakeEl(tag = "tr", text = "$prefix$it") }

    private fun searchPages(): Map<String, FakePage> {
        val startPage = FakePage(
            START,
            listOf(
                FakeEl(tag = "th", text = "关键词"),
                FakeEl(tag = "input", id = "search-input", nameAttr = "q", inputType = "search", placeholder = "输入关键词", formLabel = "关键词"),
                FakeEl(
                    tag = "button", text = "搜索", testid = "search-btn", id = "btn-search",
                    classes = listOf("btn", "primary"),
                    onClick = { it.navigate(RESULTS) },
                ),
                // 诱饵（与 TeachExecutorTest 同一件）：指纹锚里有它——少了它开场指纹闸必假阳性
                FakeEl(tag = "button", text = "提交", classes = listOf("trap")),
            ),
        )
        val resultsPage = FakePage(
            RESULTS,
            listOf(
                FakeEl(tag = "th", text = "运单号"),
                FakeEl(tag = "input", id = "filter-input", nameAttr = "filter", inputType = "text", placeholder = "筛选运单号", formLabel = "筛选"),
                FakeEl(tag = "button", text = "下一页", classes = listOf("pager-next"), onClick = { dom -> dom.replacePage(RESULTS) { els -> els.filter { it.upperTag != "TR" } + rows(15, "次") } }),
                FakeEl(tag = "button", text = "导出", classes = listOf("btn", "export"), onClick = { it.navigate(EXPORT) }),
            ) + rows(20),
        )
        val exportPage = FakePage(EXPORT, listOf(FakeEl(tag = "th", text = "导出清单"), FakeEl(tag = "button", text = "返回")))
        return mapOf(START to startPage, RESULTS to resultsPage, EXPORT to exportPage)
    }

    private fun skillFromTeaching(): TeachSkill {
        val draft = TeachLearner.buildDraft(
            TeachLearnerTestAccess.fiveEvents(), "查货", "查当日报文",
            TeachPageFacts(START, listOf("关键词"), listOf("搜索", "提交")),
            nowMs = 1_728_000_000_000L,
        )
        return draft.skill
    }

    private fun executor(
        dom: FakeDom,
        audits: MutableList<String> = mutableListOf(),
        pauseGate: () -> String? = { null },
    ): TeachExecutor {
        val sink = FileTeachEvidenceSink(tmp.newFolder())
        var now = 0L
        return TeachExecutor(
            dom = dom,
            evidence = sink,
            audit = { report, ts -> audits += report.auditLine(ts) },
            clock = { now },
            sleeper = { now += TeachExecutor.EXPECT_POLL_MS },
            pauseGate = pauseGate,
        )
    }

    // ── 判据①：插话纠正 ⇒ 当步结束即暂停（负例：忽略 ⇒ 红） ─────────────────────

    @Test
    fun `判据1_插话纠正_当步结束即暂停`() {
        val dom = FakeDom(searchPages(), START)
        val skill = skillFromTeaching()
        val audits = mutableListOf<String>()
        // 有状态的暂停闸：模拟「第 3 步执行期间用户插话」——两个点击动作（第 2/3 步）完成后闸非空
        val ex = executor(dom, audits) { if (dom.clicks.size >= 2) "单号要填新运单 WB999" else null }
        val report = ex.run(skill)
        assertTrue("暂停报告：${report.stopReason}", report.paused)
        assertEquals("当步结束即暂停：已完成 3 步后停，第 4 步没动手", 3, report.stepsDone)
        assertEquals("暂停落点＝下一个待重跑的步", 4, report.pausedAtStep)
        assertEquals("纠正原话进暂停报告（判据①：该句作为针对第 N 步的纠正）", "单号要填新运单 WB999", report.correction)
        assertNull("暂停不是失败定位（failedStep 留给真失败）", report.failedStep)
        assertEquals("第 5 步的导出没动手（调用次数断言）", 2, dom.clicks.size)
        assertEquals("审计行照落（留痕）", 1, audits.size)
        assertTrue("暂停不冒充成功", !report.ok)
    }

    @Test
    fun `判据1负例_暂停闸被忽略_回放照跑完_判别函数必红`() {
        val dom = FakeDom(searchPages(), START)
        val skill = skillFromTeaching()
        val audits = mutableListOf<String>()
        // 负例场景：插话从未被采纳（闸恒 null＝「忽略」）
        val ex = executor(dom, audits) { null }
        val report = ex.run(skill)
        assertTrue("被忽略的插话不会暂停（负例场景本身成立）", !report.paused && report.ok)
        assertEquals("负例下整段跑完", totalStepsOf(skill), report.stepsDone)
        // 判别函数（判据①的牙）：「有纠正时必须暂停且不许跑完全程」
        fun adopted(report: TeachRunReport, total: Int): Boolean =
            if (report.correction != null) report.paused && report.stepsDone < total else true
        // 正世界：纠正被采纳 ⇒ 暂停且没跑完全程
        val adoptedWorld = report.copy(correction = "单号要填新运单 WB999", paused = true, stepsDone = 2)
        assertTrue(adopted(adoptedWorld, totalStepsOf(skill)))
        // 负世界：纠正在场但被忽略（没暂停、跑完了）⇒ 判别函数必抓
        val ignoredWorld = report.copy(correction = "单号要填新运单 WB999", paused = false, stepsDone = totalStepsOf(skill))
        assertFalse("负例（忽略）必须被同一个判别函数抓红", adopted(ignoredWorld, totalStepsOf(skill)))
    }

    @Test
    fun `CorrectInbox_回放在跑才收纠正_最新一句生效_收场即清`() {
        CorrectInbox.beginReplay("查货", 5)
        assertNull("没插话 ⇒ 闸放行", CorrectInbox.pendingCorrection())
        CorrectInbox.noteInterjection("单号填 WB999")
        assertEquals("回放在跑 ⇒ 收", "单号填 WB999", CorrectInbox.pendingCorrection())
        CorrectInbox.noteInterjection("改成 WB777")
        assertEquals("连说几句：最新一句是当前意图", "改成 WB777", CorrectInbox.pendingCorrection())
        CorrectInbox.endReplay()
        assertNull("收场即清（暂停/失败/成功一律清）", CorrectInbox.pendingCorrection())
        CorrectInbox.noteInterjection("回放外的话不进收件箱")
        assertNull(CorrectInbox.pendingCorrection())
    }

    // ── 判据③：从第 N 步重跑——前 N-1 步不重执行（断言调用次数） ──────────────────

    @Test
    fun `判据3_从第2步重跑_第1步不重执行_调用次数断言`() {
        val skill = skillFromTeaching()
        val first = FakeDom(searchPages(), START)
        executor(first).run(skill)
        val firstClicks = first.clicks.toList()
        // 续跑：页面摆回第 2 步的**执行前态**（START；第 1 步的输入已填过）——
        // 回放不自动回导航（230-D 的可视化回放负责），用户/模型把页面带到第 N 步前
        val second = FakeDom(searchPages(), START)
        val report = executor(second).run(skill, fromStep = 2)
        assertTrue("续跑跑完：${report.stopReason}", report.ok)
        assertEquals("stepsDone 计本次执行的步数", skill.steps.size - 1, report.stepsDone)
        assertEquals(
            "从第 2 步起各执行一次（第 2 步是被纠正的步，重跑含它）",
            firstClicks,
            second.clicks.toList(),
        )
        assertFalse("第 1 步的输入没有重打（前 N-1 步不重执行）", second.typed.any { it.first.contains("search-input") })
        assertFalse("续跑不冒充暂停", report.paused)
    }

    @Test
    fun `判据3_续跑时开场指纹查询被豁免_每步域闸与安全闸不豁免`() {
        val skill = skillFromTeaching()
        // 起点页**故意缺指纹锚**（去掉「关键词」表头）——若开场指纹闸没豁免，续跑第一查就停
        val pagesWithoutAnchor = searchPages().toMutableMap().also { m ->
            m[START] = FakePage(START, m[START]!!.elements.filterNot { it.text == "关键词" })
        }
        val second = FakeDom(pagesWithoutAnchor, START)
        val report = executor(second).run(skill, fromStep = 2)
        assertTrue("开场指纹豁免（锚缺失也不拦中途续跑）：${report.stopReason}", report.ok)
        // 每步域闸不豁免：跳出授权域照样停（第 2 步执行前查域）
        val outDomain = FakeDom(
            mapOf("https://other.example.net/x" to FakePage("https://other.example.net/x", listOf(FakeEl(tag = "th", text = "x")))),
            "https://other.example.net/x",
        )
        val blocked = executor(outDomain).run(skill, fromStep = 2)
        assertTrue("续跑时每步域闸照拦", !blocked.ok && blocked.stopReason!!.contains("授权域"))
    }

    // ── 判据②：纠正流程不出现重录话术（runtime 半边） ────────────────────────────

    @Test
    fun `判据2_暂停报告与建议不出现重录话术_给的是续跑指引`() {
        val dom = FakeDom(searchPages(), START)
        val skill = skillFromTeaching()
        val ex = executor(dom, mutableListOf()) { if (dom.clicks.size >= 2) "单号要填新运单 WB999" else null }
        val report = ex.run(skill)
        val visible = listOfNotNull(report.stopReason, report.suggestion, report.correction).joinToString("；")
        assertFalse("不许要求重录（判据②；「不重录」是承诺不算话术）", visible.contains("请重新录制") || visible.contains("重新录制"))
        assertTrue("给的是就地纠正＋续跑指引", report.suggestion!!.contains("teach_correct_resume"))
        assertTrue(report.suggestion!!.contains("不重录"))
    }

    private fun totalStepsOf(skill: TeachSkill): Int = skill.steps.size
}
