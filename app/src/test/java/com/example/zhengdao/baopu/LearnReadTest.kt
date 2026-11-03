// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单第四百批（学习**读侧**接线）——学习写得进去、读不出来，本文件把三个读口
// 的判据钉死：①弹卡前问 shouldSuppress/mustAsk ②排序用 orderRulings/stats ③上下文带
// 最近同类经验。三条铁律各占一组判据：
//   读不许改三态   ⇒ 判据⑥（咨询前后账行数与内容逐字不变）；
//   样本 <5 不生效 ⇒ 判据①（先证 3 关在 LearnPolicy 口径下确会压，再证地板拦住它）；
//   因经验而变能说出依据 ⇒ 判据②③④⑦（每条都断言证据句非空且带样本/关闭读数）。
// 判据⑤是对拍：LearnReadJudge.windowSignals 与 LearnPolicy.stats 必须同一把尺
// （298 的教训：两把尺子必红一条）。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.agent.LearnPolicy
import com.example.zhengdao.baopu.agent.LearnWiring
import com.example.zhengdao.baopu.agent.OpinionDecision
import com.example.zhengdao.baopu.agent.OpinionWhy
import com.example.zhengdao.baopu.agent.Ruling
import com.example.zhengdao.baopu.agent.UserSignal
import com.example.zhengdao.baopu.inspect.ActionCode
import com.example.zhengdao.baopu.inspect.LawRef
import com.example.zhengdao.baopu.inspect.Opinion
import com.example.zhengdao.baopu.inspect.Severity
import com.example.zhengdao.baopu.inspect.Source
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LearnReadTest {

    /** 固定时刻（只用 24h 整数倍跨日，避开时区边界——照 283 房型）。 */
    private val now = 1_700_000_000_000L

    private val dayMs = 24L * 3600 * 1000

    @Before
    fun setUp() {
        LearnWiring.resetForTest()
        LearnWiring.installRoundSource { "0" }
    }

    @After
    fun tearDown() {
        LearnWiring.resetForTest()
    }

    private fun op(gist: String, law: LawRef, severity: Severity = Severity.MAJOR) =
        Opinion(Source.CLASSIFIER, severity, gist, ActionCode.REVIEW, law, listOf("e:$gist"))

    private fun ruling(gist: String, law: LawRef, at: Long) = Ruling(
        id = "op:$gist",
        opinion = op(gist, law),
        decision = OpinionDecision.DEFER,
        why = OpinionWhy.QUEUE_ONLY,
        detail = "d",
        round = "0",
        royalToday = 0,
        atMs = at,
        tool = "t",
    )

    private fun dismiss(cls: String, i: Int, at: Long) =
        LearnWiring.onUserSignal(UserSignal.DISMISS, cls, "op:d$i", at, "第${i}次关：$cls 别再问")

    private fun approve(cls: String, i: Int, at: Long) =
        LearnWiring.onUserSignal(UserSignal.APPROVE, cls, "op:a$i", at, "第${i}次批：$cls 问得对")

    private fun rows() = LearnWiring.replay()

    // ── 铁律②：样本 <5 不生效 ──────────────────────────────────────────────

    @Test
    fun `判据①_样本不足5_关满3次也压不下_行为与接线前一致`() {
        val cls = "越界访问"
        for (i in 1..3) dismiss(cls, i, now + i * dayMs)
        val t = now + 4 * dayMs
        // 先证前提：3 关在 LearnPolicy 口径下确会压——地板拦住的就是它
        assertTrue("前提：LearnPolicy.shouldSuppress 会压", LearnPolicy.shouldSuppress(rows(), cls, t).suppressed)
        val c = LearnReadJudge.consult(rows(), cls, op(cls, LawRef.L9), t)
        assertTrue("样本 3 <5 ⇒ 学习不生效，照旧摆卡（铁律②）", c.allowed)
        assertFalse(c.effective)
        assertEquals(3, c.samples)
        assertEquals("没生效就一句依据都不给", "", c.evidence)
    }

    @Test
    fun `判据②_样本满5关满3_压下且依据带样本与关闭读数`() {
        val cls = "越界访问"
        dismiss(cls, 1, now + 1 * dayMs)
        dismiss(cls, 2, now + 2 * dayMs)
        dismiss(cls, 3, now + 3 * dayMs)
        approve(cls, 1, now + 3 * dayMs + 3_600_000L)
        approve(cls, 2, now + 4 * dayMs)
        val t = now + 5 * dayMs
        val c = LearnReadJudge.consult(rows(), cls, op(cls, LawRef.L9), t)
        assertFalse("样本 5 ≥5 且关满 3 ⇒ 压", c.allowed)
        assertTrue(c.effective)
        assertEquals(5, c.samples)
        assertEquals(3, c.closes)
        assertTrue("依据要带关闭数（铁律③）", c.evidence.contains("关 3 次"))
        assertTrue("依据要带样本数（铁律③）", c.evidence.contains("样本 5"))
    }

    // ── 铁律①②的交点：法条必问集直通 ──────────────────────────────────────

    @Test
    fun `判据③_法条必问直通_学习想压也照摆且证据说清`() {
        val cls = "未分类"
        dismiss(cls, 1, now + 1 * dayMs)
        dismiss(cls, 2, now + 2 * dayMs)
        dismiss(cls, 3, now + 3 * dayMs)
        approve(cls, 1, now + 3 * dayMs + 3_600_000L)
        approve(cls, 2, now + 4 * dayMs)
        val t = now + 5 * dayMs
        val c = LearnReadJudge.consult(rows(), cls, op(cls, LawRef.L7), t)
        assertTrue("L7 必问——学习无权过问，照摆", c.allowed)
        assertTrue(c.effective)
        assertTrue("证据要写明法条优先（铁律③）", c.evidence.contains("必问"))
        assertTrue("证据要点名是哪条法条", c.evidence.contains("L7"))
    }

    // ── 铁律②：排序的地板 ──────────────────────────────────────────────────

    @Test
    fun `判据④_排序_地板类批过排前_没到地板保持新到旧`() {
        // A 类达地板（5 信号含 2 批）：批过的往前排
        val a = "越界访问"
        approve(a, 1, now + 1 * dayMs)
        dismiss(a, 1, now + 2 * dayMs)
        approve(a, 2, now + 3 * dayMs)
        dismiss(a, 2, now + 4 * dayMs)
        dismiss(a, 3, now + 5 * dayMs)
        val b = "重复失败"
        val out = LearnReadJudge.ordered(
            rows(),
            listOf(ruling(b, LawRef.L9, now + 6 * dayMs), ruling(a, LawRef.L9, now + 4 * dayMs)),
            now + 6 * dayMs,
        )
        assertEquals("批过的 A 排前（尽管比 B 旧）", "越界访问", out.rulings.first().opinion.gist)
        assertTrue("生效要带依据（铁律③）", out.activatedLine!!.contains(a))
        assertTrue("依据要点名样本地板", out.activatedLine!!.contains("5"))

        // C/D 都没到地板：保持接线前口径（atMs 新→旧），一个字不差
        val c1 = "技能排版"
        dismiss(c1, 1, now + 1 * dayMs)
        val out2 = LearnReadJudge.ordered(
            rows(),
            listOf(ruling(c1, LawRef.L9, now + 2 * dayMs), ruling(b, LawRef.L9, now + 6 * dayMs)),
            now + 6 * dayMs,
        )
        assertNull("没人达地板 ⇒ 排序未生效", out2.activatedLine)
        assertEquals("照旧：新→旧", listOf(b, c1), out2.rulings.map { it.opinion.gist })
    }

    // ── 判据⑤：windowSignals 与 LearnPolicy.stats 同一把尺（对拍） ──────────

    @Test
    fun `判据⑤_对拍_窗内信号数与stats各列严丝合缝`() {
        val cls = "越界访问"
        dismiss(cls, 1, now - 40 * dayMs) // 窗外（30 天窗）
        LearnWiring.onUserSignal(UserSignal.REASK, cls, "user:q1", now + 1 * dayMs, "再问一次")
        dismiss(cls, 2, now + 2 * dayMs)
        approve(cls, 1, now + 3 * dayMs)
        LearnWiring.onUserSignal(UserSignal.DEFER, cls, "op:f1", now + 4 * dayMs, "先搁着")
        dismiss(cls, 3, now + 5 * dayMs)
        val t = now + 6 * dayMs
        val signals = LearnReadJudge.windowSignals(rows(), cls, t)
        val stats = LearnPolicy.stats(rows(), cls, t)
        val defers = signals.count { it.key == "signal:defer" }
        assertEquals("关闭数两把尺必须相等", stats.closes, signals.count { it.key == "signal:dismiss" })
        assertEquals("批过数两把尺必须相等", stats.approves, signals.count { it.key == "signal:approve" })
        assertEquals("样本数＝关＋批＋搁（窗外与重问前的不算）", 4, signals.size)
        assertEquals(stats.closes + stats.approves + defers, signals.size)
    }

    // ── 铁律①：读不许改三态 ────────────────────────────────────────────────

    @Test
    fun `判据⑥_读不改三态_三问前后账行逐字不变`() {
        val cls = "越界访问"
        for (i in 1..3) dismiss(cls, i, now + i * dayMs)
        approve(cls, 1, now + 3 * dayMs + 3_600_000L)
        approve(cls, 2, now + 4 * dayMs)
        val before = rows()
        val t = now + 5 * dayMs
        LearnRead.cardAllowed(cls, op(cls, LawRef.L9), t)
        LearnRead.orderedRulings(listOf(ruling(cls, LawRef.L9, t)), t)
        LearnRead.recentExperience(cls, 3, t)
        LearnReadJudge.ordered(before, listOf(ruling(cls, LawRef.L9, t)), t)
        LearnReadJudge.recent(before, cls, 3, t)
        assertEquals("账行数不变（铁律①：经验账只读）", before.size, rows().size)
        assertEquals("账行内容逐字不变", before, rows())
    }

    // ── 铁律②③：上下文带最近同类经验 ──────────────────────────────────────

    @Test
    fun `判据⑦_上下文_样本满5带最近三条_不足5空表`() {
        val cls = "越界访问"
        for (i in 1..3) dismiss(cls, i, now + i * dayMs)
        approve(cls, 1, now + 3 * dayMs + 3_600_000L)
        approve(cls, 2, now + 4 * dayMs)
        val t = now + 5 * dayMs
        val recent = LearnRead.recentExperience(cls, 3, t)
        assertEquals("最多带 3 条（宁少不滥）", 3, recent.size)
        assertEquals("新→旧：最新那条打头", "第2次批：越界访问 问得对", recent.first().reason)
        assertTrue("每条经验都得能说出依据", recent.all { it.reason.isNotBlank() })
        // 薄样本（2 条）不进上下文
        val thin = "重复失败"
        dismiss(thin, 1, now + 1 * dayMs)
        dismiss(thin, 2, now + 2 * dayMs)
        assertTrue("样本 <5 ⇒ 一行不带（铁律②）", LearnRead.recentExperience(thin, 3, t).isEmpty())
    }
}
