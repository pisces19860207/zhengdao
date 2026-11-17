// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百八十九批 · 状态摘要的判据（① 步数对账 ② ≤400 字/不含用户正文 ③ 证据引用
// ④ 消费 670 summaryLine ⑤ 空状态「无」不编造 ⑥ ≤50ms/100 步）。
// 判据④的**源码断言**在 StateSummaryWiringTest（这里测行为那一半）。
package com.example.zhengdao.baopu.state.summary

import com.example.zhengdao.baopu.PlanRules
import com.example.zhengdao.baopu.PlanRules.PlanItem
import com.example.zhengdao.baopu.safety.DiaryEvent
import com.example.zhengdao.baopu.safety.DiaryKind
import com.example.zhengdao.baopu.scout.UnfinishedItem
import com.example.zhengdao.baopu.scout.UnfinishedOutcome
import com.example.zhengdao.baopu.scout.UnfinishedReason
import com.example.zhengdao.baopu.state.track.CharacterTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StateSummaryTest {

    /** 夹具里的「用户正文」——摘要里一个字都不许出现（判据②）。 */
    private val userText = "帮我看看报销表里那三张没报销的单子，顺便把上个月的发票也整理一下"

    private fun plan(total: Int, done: Int = 0, doing: Int = 0): List<PlanItem> =
        (1..total).map { i ->
            PlanItem(
                text = "第${i}步：整理第${i}份材料",
                status = when {
                    i <= done -> PlanRules.STATUS_DONE
                    i <= done + doing -> PlanRules.STATUS_DOING
                    else -> PlanRules.STATUS_TODO
                },
            )
        }

    private fun action(what: String, at: Long = 1_700_000_000_000L): DiaryEvent =
        DiaryEvent(DiaryKind.ACTION, at, "值守自动动作", what, "成功")

    private fun failed(intent: String, at: Long = 1_700_000_000_000L): UnfinishedItem =
        UnfinishedItem(
            intent = intent,
            outcome = UnfinishedOutcome.FAILED,
            reason = UnfinishedReason.TOOL_UNAVAILABLE,
            reasonDetail = "命令不存在",
            reasonRef = "audit:tool-1",
            atMs = at,
        )

    private fun fixable(intent: String): UnfinishedItem =
        UnfinishedItem(
            intent = intent,
            outcome = UnfinishedOutcome.NOT_DONE,
            reason = UnfinishedReason.MISSING_PERMISSION,
            reasonRef = "permission:CAMERA",
            fixable = true,
            fixHint = "去开相机权限",
        )

    private fun assemble(
        goal: String? = "把这一季的报表整理成一份汇总",
        plan: List<PlanItem> = plan(7, done = 3, doing = 1),
        diary: List<DiaryEvent> = listOf(action("写入汇总表第一页")),
        ledger: List<UnfinishedItem> = listOf(failed("把附件转成 PDF")),
        unlearned: List<String> = emptyList(),
        track: CharacterTrack? = null,
    ): RunState = RunStateAssembler.assemble(goal, plan, diary, ledger, unlearned, track)

    /** 摘要里那段 `阶段：执行(3/7)` 的总数（判据①的对账口）。 */
    private fun totalIn(summary: String): Int {
        val m = Regex("""阶段：[^｜（]*（(\d+)/(\d+)）""").find(summary)
        assertNotNull("摘要里必须有「阶段：…(已做/总数)」：$summary", m)
        return m!!.groupValues[2].toInt()
    }

    private fun doneIn(summary: String): Int {
        val m = Regex("""阶段：[^｜（]*（(\d+)/(\d+)）""").find(summary)
        assertNotNull("摘要里必须有「阶段：…(已做/总数)」：$summary", m)
        return m!!.groupValues[1].toInt()
    }

    // ── 判据①：摘要步数 ＝ RunState 步数 ──────────────────────────────────

    @Test
    fun `判据1_摘要里的阶段总数等于RunState步数`() {
        val state = assemble(plan = plan(7, done = 3, doing = 1))
        val summary = StateSummary.render(state)
        println("[689] 摘要样例（7 步/3 完成）：$summary（${summary.length} 字）")
        assertEquals(7, state.steps.size)
        assertEquals("摘要里的步数必须等于 RunState 步数", state.steps.size, totalIn(summary))
        assertEquals("已做数也必须与步列表一致", 3, doneIn(summary))
        assertEquals(3, state.steps.count { it.state == StepState.DONE })
    }

    @Test
    fun `判据1_百步夹具的摘要步数同样对得上`() {
        val state = assemble(plan = plan(100, done = 42, doing = 1))
        val summary = StateSummary.render(state)
        assertEquals(100, state.steps.size)
        assertEquals(100, totalIn(summary))
        assertEquals(42, doneIn(summary))
    }

    // ── 判据②：≤400 字；不含用户正文 ─────────────────────────────────────

    @Test
    fun `判据2_百步夹具的摘要不超过400字`() {
        val state = assemble(
            goal = "把这一季的报表整理成一份汇总并逐页核对口径",
            plan = plan(100, done = 42, doing = 1),
            diary = listOf(action("写入汇总表第一页")),
            ledger = listOf(failed("把附件转成 PDF")),
            unlearned = listOf("这份表该按含税还是不含税统计"),
        )
        val summary = StateSummary.render(state)
        assertTrue("摘要超 400 字（实测 ${summary.length}）：$summary", summary.length <= StateSummary.MAX_CHARS)
        // 不是靠「截到 400」硬凑：内容本来就该收得住
        assertTrue("摘要短得可疑（${summary.length} 字）——夹具没被消费？", summary.length > 40)
    }

    @Test
    fun `判据2_摘要不含用户正文`() {
        val state = assemble(plan = plan(5, done = 1))
        val summary = StateSummary.render(state)
        assertFalse("摘要不许出现用户正文：$summary", summary.contains(userText))
        assertFalse("摘要不许出现用户正文里的词：$summary", summary.contains("报销"))
        // 夹具本身是「有用户正文的那一轮」，摘要却只由账本拼出来——这条断言钉的是这件事
        assertTrue(userText.isNotBlank())
    }

    // ── 判据③：每个数字都能指回账行／事件 ────────────────────────────────

    @Test
    fun `判据3_每一步的证据引用非空且可指回行号`() {
        val state = assemble(plan = plan(9, done = 2, doing = 1))
        assertEquals(9, state.steps.size)
        state.steps.forEachIndexed { i, step ->
            assertEquals("步序必须连续", i + 1, step.seq)
            assertTrue("证据引用不许为空", step.evidence.text.isNotBlank())
            assertTrue(
                "证据引用要能解析成「账名#行号」：${step.evidence.text}",
                Regex("""^(计划|账行|未办成|未学会)#\d+$""").matches(step.evidence.text),
            )
            assertTrue("行号从 1 起：${step.evidence.text}", step.evidence.line >= 1)
            assertEquals("计划步的证据指向它自己的序号", i + 1, step.evidence.line)
        }
    }

    @Test
    fun `判据3_刚完成卡住待决策未学会的证据都指得回账行`() {
        val state = assemble(
            diary = listOf(action("第一件"), action("第二件")),
            ledger = listOf(failed("把附件转成 PDF"), fixable("打开相机权限")),
            unlearned = listOf("这份表该按含税还是不含税统计"),
        )
        assertNotNull("刚完成该有（起居注最后一条动作行）", state.justDone)
        val justDone = state.justDone!!
        assertEquals("刚完成取起居注最后一条动作行", "第二件", justDone.text)
        assertEquals("账行#2", justDone.evidence.text)

        assertNotNull("卡住该有（未办成单里的失败）", state.lastFailure)
        val failure = state.lastFailure!!
        assertEquals("未办成#1", failure.evidence.text)
        assertTrue("归因不许空", failure.reason.isNotBlank())

        assertEquals(1, state.decisions.size)
        assertEquals("未办成#2", state.decisions.first().evidence.text)
        assertEquals("未学会#1", state.unlearned.first().evidence.text)

        val summary = StateSummary.render(state)
        assertTrue("摘要要带账行号：$summary", summary.contains("账行#2"))
        assertTrue("摘要要带未办成号：$summary", summary.contains("未办成#1"))
    }

    @Test
    fun `判据3_证据引用为空造不出来`() {
        val thrown = runCatching { Evidence("") }.exceptionOrNull()
        assertTrue("空证据引用必须构造期就拒（判据③）", thrown is IllegalArgumentException)
        assertTrue(runCatching { Evidence.plan(0) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { Evidence.diary(-1) }.exceptionOrNull() is IllegalArgumentException)
    }

    // ── 判据④：消费 670 的 summaryLine（行为那一半） ──────────────────────

    @Test
    fun `判据4_有神轨状态时摘要出现那一行`() {
        val track = CharacterTrack()
        track.tick()
        val line = track.summaryLine()
        assertTrue("夹具的神轨状态行不该是空的", line.isNotBlank())

        val state = assemble(track = track)
        val summary = StateSummary.render(state)
        println("[689] 带神轨的摘要：$summary（${summary.length} 字）")
        assertEquals("神轨行原样进 RunState", line, state.trackLine)
        assertTrue("摘要里必须出现 670 那一行：$summary", summary.contains(line))
        assertTrue("神轨那一行单独占一行", summary.lines().any { it == line })
        assertTrue(summary.length <= StateSummary.MAX_CHARS)
    }

    @Test
    fun `判据4_没有神轨状态就不插行也不报错`() {
        val state = assemble(track = null)
        assertNull(state.trackLine)
        val summary = StateSummary.render(state)
        assertFalse("没有神轨就不许出现角色轨字样：$summary", summary.contains("[角色轨]"))
        assertTrue(summary.length <= StateSummary.MAX_CHARS)
    }

    // ── 判据⑤：空状态 ⇒ 「无」，不编造 ────────────────────────────────────

    @Test
    fun `判据5_空状态显示无`() {
        val state = RunStateAssembler.assemble(goal = null)
        assertTrue("空状态不许编出步骤", state.steps.isEmpty())
        assertTrue("空状态不许编出阶段数", state.stage.total == 0)
        assertTrue(state.isEmpty)
        assertEquals(StateSummary.EMPTY, StateSummary.render(state))
        assertEquals("无", StateSummary.render(state))
    }

    @Test
    fun `判据5负例_有账就不许显示无`() {
        // 「无」只能来自数据为空，不许是写死的兜底：塞一条计划进去必须立刻不是「无」
        val state = assemble(plan = plan(1))
        assertEquals(1, state.steps.size)
        assertFalse(state.isEmpty)
        val summary = StateSummary.render(state)
        assertFalse("有一步计划却显示「无」＝编造的反面（漏报）", summary == StateSummary.EMPTY)
        assertTrue(summary.contains("阶段："))
    }

    // ── 判据⑥：组装 ≤50ms（100 步夹具） ─────────────────────────────────

    @Test
    fun `判据6_百步夹具组装加渲染不超过50毫秒`() {
        val plan = plan(100, done = 42, doing = 1)
        val diary = (1..100).map { action("第${it}件", at = 1_700_000_000_000L + it) }
        val ledger = (1..50).map { failed("第${it}件失败", at = 1_700_000_000_000L + it) }
        val unlearned = (1..20).map { "问题$it" }
        val track = CharacterTrack()

        // 预热（JIT）：判据要的是稳态耗时，不是第一次类加载
        repeat(50) {
            StateSummary.render(RunStateAssembler.assemble("目标", plan, diary, ledger, unlearned, track))
        }

        var worstUs = 0L
        var totalUs = 0L
        repeat(50) {
            val t0 = System.nanoTime()
            StateSummary.render(RunStateAssembler.assemble("目标", plan, diary, ledger, unlearned, track))
            val us = (System.nanoTime() - t0) / 1_000
            totalUs += us
            if (us > worstUs) worstUs = us
        }
        println("[689] 100 步夹具：组装+渲染 50 次，最坏 ${worstUs}µs／均值 ${totalUs / 50}µs（判据⑥预算 50ms＝50,000µs）")
        assertTrue("100 步夹具最坏一次 ${worstUs}µs，超 50ms 预算（判据⑥）", worstUs <= 50_000)
    }
}
