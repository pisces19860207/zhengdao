// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单 405（模型上下文读经验＝400 的留白半）——RunEngine 拼上下文处读最近同类
// 经验，只加一行「上次同类：<做法> ⇒ <结果>」（≤2 行）。三条铁律各占一组判据：
//   无经验一个字不加 ⇒ 判据②（空账／第 1 次出现 → null；extraNotes 空表 ⇒ prompt 逐字节一致）；
//   读不许改三态     ⇒ 判据③（judge/roundNote 前后经验账行逐字不变；判卷三态原样）；
//   样本 <5 不生效   ⇒ 判据④（400 口径：窗内同类信号行数——直接复用 LearnReadJudge.recent）。
// 判据①是验收正例：同类型第 2 次出现 ⇒ 该行进上下文，prompt 片段可抓。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.agent.DraftClaim
import com.example.zhengdao.baopu.agent.DraftGate
import com.example.zhengdao.baopu.agent.ExperienceKind
import com.example.zhengdao.baopu.agent.ExperienceRow
import com.example.zhengdao.baopu.agent.LearnWiring
import com.example.zhengdao.baopu.agent.StubVerifier
import com.example.zhengdao.baopu.agent.UserSignal
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LearnContextNoteTest {

    /** 固定时刻（只用 24h 整数倍跨日，避开时区边界——照 283/400 房型）。 */
    private val now = 1_700_000_000_000L

    private val dayMs = 24L * 3600 * 1000

    @Before
    fun setUp() {
        LearnWiring.resetForTest()
    }

    @After
    fun tearDown() {
        LearnWiring.resetForTest()
    }

    private fun dismiss(cls: String, i: Int, at: Long) =
        LearnWiring.onUserSignal(UserSignal.DISMISS, cls, "op:d$i", at, "第${i}次关：$cls 别再问")

    private fun approve(cls: String, i: Int, at: Long) =
        LearnWiring.onUserSignal(UserSignal.APPROVE, cls, "op:a$i", at, "第${i}次批：$cls 问得对")

    private fun rows() = LearnWiring.replay()

    /** 手搓一行（判据层纯函数直接吃数据；不进真账）。 */
    private fun row(
        cls: String,
        at: Long,
        kind: ExperienceKind = ExperienceKind.SIGNAL,
        key: String = "signal:dismiss",
        reason: String = "账上理由",
    ) = ExperienceRow(
        id = "lx:$at:$cls",
        atMs = at,
        kind = kind,
        cardClass = cls,
        key = key,
        reason = reason,
        evidence = listOf("ref:$cls"),
    )

    // ── 判据①：同类型第 2 次出现 ⇒ 该行进上下文（可抓 prompt 片段） ────────

    @Test
    fun `判据①_同类型多次出现且过地板_该行进上下文_可抓prompt片段`() {
        val cls = "越界访问"
        for (i in 1..5) dismiss(cls, i, now + i * dayMs)
        val note = LearnContextNoteJudge.line(rows(), now + 6 * dayMs)
        assertTrue(note != null)
        note!!
        assertTrue("行必须以「上次同类：」开头（派工单 405 的行模板）", note.startsWith("上次同类："))
        assertTrue("做法⇒结果的结构必须在（⇒）", note.contains("⇒"))
        assertTrue("类名必须在（模型得知道是哪类的事）", note.contains(cls))
        assertTrue("结果必须是账上那句理由（依据随行可查）", note.contains("别再问"))

        // prompt 片段可抓：经 ContextAssembler 组装后原样可见；不带它时一个字都没有
        val prompt = ContextAssembler.assemble(
            persona = emptyList(),
            facts = HardFacts(workspacePath = "/ws", model = "m"),
            stickyMemories = emptyList(),
            newMemories = emptyList(),
            history = emptyList(),
            current = "你好",
            extraNotes = listOf(note),
        ).render()
        assertTrue(prompt.contains("上次同类："))
        val baseline = ContextAssembler.assemble(
            persona = emptyList(),
            facts = HardFacts(workspacePath = "/ws", model = "m"),
            stickyMemories = emptyList(),
            newMemories = emptyList(),
            history = emptyList(),
            current = "你好",
        ).render()
        assertFalse("接线前（无经验）prompt 里不许有这一行", baseline.contains("上次同类："))
    }

    // ── 判据②：无经验一个字不加 ────────────────────────────────────────────

    @Test
    fun `判据②_空账不加_第1次出现不加_extraNotes空表与接线前逐字节一致`() {
        assertNull("空账 ⇒ null（一个字不加）", LearnContextNoteJudge.line(emptyList(), now))
        assertNull("空账 ⇒ roundNote 也 null", LearnContextNote.roundNote(resumed = false, now = now))
        // 第 1 次出现：只有 1 行，没有"上次"可说
        LearnWiring.onUserSignal(UserSignal.DISMISS, "孤立类", "op:x1", now, "第1次关：孤立类 别再问")
        assertNull("第 1 次出现不加", LearnContextNoteJudge.line(rows(), now + dayMs))
        // extraNotes 空表 ⇒ 渲染与接线前逐字节一致（铁律①的机械形状）
        val withNull = ContextAssembler.assemble(
            persona = emptyList(),
            facts = HardFacts(workspacePath = "/ws", model = "m"),
            stickyMemories = emptyList(),
            newMemories = emptyList(),
            history = emptyList(),
            current = "你好",
            extraNotes = listOfNotNull(null as String?),
        ).render()
        val without = ContextAssembler.assemble(
            persona = emptyList(),
            facts = HardFacts(workspacePath = "/ws", model = "m"),
            stickyMemories = emptyList(),
            newMemories = emptyList(),
            history = emptyList(),
            current = "你好",
        ).render()
        assertEquals("null ⇒ 一个字节不增", without, withNull)
    }

    // ── 判据③：读不许改三态 ────────────────────────────────────────────────

    @Test
    fun `判据③_读前后经验账逐字不变_判卷三态原样`() {
        val cls = "越界访问"
        for (i in 1..5) dismiss(cls, i, now + i * dayMs)
        val before = rows()
        LearnContextNoteJudge.line(before, now + 6 * dayMs)
        LearnContextNote.roundNote(resumed = false, now = now + 6 * dayMs)
        assertEquals("读不许改三态：经验账行数与内容逐字不变", before, rows())
        // 判卷三态（真/假/无法判定）原样：判卷是纯函数，经验的读与它零接触
        val claims = listOf(DraftClaim("C1", "一条事实主张", "ref:1"))
        val gateBefore = DraftGate.check(claims, StubVerifier)
        LearnContextNoteJudge.line(before, now + 6 * dayMs)
        assertEquals("判卷三态不受影响", gateBefore, DraftGate.check(claims, StubVerifier))
    }

    // ── 判据④：样本 <5 不生效（400 口径：窗内同类信号行数） ────────────────

    @Test
    fun `判据④_窗内信号不足5_行数再多也不加`() {
        val cls = "越界访问"
        for (i in 1..4) dismiss(cls, i, now + i * dayMs)
        assertNull("4 条窗内信号 ⇒ 不生效（400 地板）", LearnContextNoteJudge.line(rows(), now + 5 * dayMs))
    }

    @Test
    fun `判据④_窗外信号不算_重问后清零重数`() {
        val cls = "越界访问"
        for (i in 1..5) dismiss(cls, i, now - 40 * dayMs + i * dayMs)
        assertNull("5 条全在 30 天窗外 ⇒ 不算（账上不消失，但不生效）", LearnContextNoteJudge.line(rows(), now))
        for (i in 1..5) dismiss(cls, 10 + i, now - 40 * dayMs + i * dayMs)
        LearnWiring.onUserSignal(UserSignal.REASK, cls, "op:r1", now - dayMs, "用户重问：压错了")
        assertNull("最近一次重问之后的信号才算（与 400 同一把尺）", LearnContextNoteJudge.line(rows(), now))
    }

    // ── 判据⑤：跨类取最近一个合格类 ────────────────────────────────────────

    @Test
    fun `判据⑤_新出场的薄样本类不遮住_最近合格类的经验`() {
        val learned = "越界访问"
        for (i in 1..5) dismiss(learned, i, now - 3 * dayMs + i * dayMs)
        // 更新出场的一行薄样本类：不过地板 ⇒ 不得让它把合格类的经验挤掉
        LearnWiring.onUserSignal(UserSignal.DISMISS, "新坑", "op:n1", now + dayMs, "第1次关：新坑 别再问")
        val note = LearnContextNoteJudge.line(rows(), now + 2 * dayMs)
        assertTrue(note != null)
        assertTrue("取的是最近的合格类（越界访问）", note!!.contains(learned))
        assertFalse("薄样本类不上行", note.contains("新坑"))
    }

    // ── 判据⑥：同轮同字节（前缀判据的判据层形态） ──────────────────────────

    @Test
    fun `判据⑥_同输入重复调用返回逐字相同`() {
        val cls = "越界访问"
        for (i in 1..5) dismiss(cls, i, now + i * dayMs)
        val a = LearnContextNoteJudge.line(rows(), now + 6 * dayMs)
        val b = LearnContextNoteJudge.line(rows(), now + 6 * dayMs)
        assertEquals("同轮每一步同一句（前缀判据）", a, b)
        assertEquals(LearnContextNote.roundNote(resumed = false, now = now + 6 * dayMs), a ?: "")
        assertEquals(
            "IO 壳重复调用同字节",
            LearnContextNote.roundNote(resumed = false, now = now + 6 * dayMs),
            LearnContextNote.roundNote(resumed = false, now = now + 6 * dayMs),
        )
    }

    // ── 判据⑦：行形状——单行、封顶截断 ─────────────────────────────────────

    @Test
    fun `判据⑦_单行不折行_超长截断到上限`() {
        val cls = "越界访问"
        for (i in 1..5) dismiss(cls, i, now + i * dayMs)
        val note = LearnContextNoteJudge.line(rows(), now + 6 * dayMs)!!
        assertFalse("≤2 行＝单行不折行", note.contains("\n"))
        assertTrue("长度封顶", note.length <= LearnContextNoteJudge.MAX_CHARS)
        val long = LearnContextNoteJudge.line(
            listOf(
                row(cls, now, reason = "很".repeat(300)),
                row(cls, now - dayMs),
                row(cls, now - 2 * dayMs),
                row(cls, now - 3 * dayMs),
                row(cls, now - 4 * dayMs),
            ),
            now + dayMs,
        )!!
        assertTrue("超长截断到 MAX_CHARS（末尾 …）", long.length <= LearnContextNoteJudge.MAX_CHARS)
        assertTrue(long.endsWith("…"))
    }

    // ── 判据⑧：IO 壳——续跑不带；真账路径出行；读失败当没经验 ───────────────

    @Test
    fun `判据⑧_续跑不带_有账出行_读不出账当没经验`() {
        val cls = "越界访问"
        val t = System.currentTimeMillis()
        for (i in 1..5) dismiss(cls, i, t - dayMs + i)
        assertNull("续跑 ⇒ null（原轮已注过，同 turnImages/kbNote 口径）", LearnContextNote.roundNote(resumed = true))
        val note = LearnContextNote.roundNote(resumed = false)
        assertTrue("真账路径：过地板的类出行", note != null && note.startsWith("上次同类："))
        LearnWiring.resetForTest()
        assertNull("读不出账（空）当没经验", LearnContextNote.roundNote(resumed = false))
    }

    // ── 判据⑨：行内容随账上最新一行走（种类不限，400 读口③同一口径） ───────

    @Test
    fun `判据⑨_压制与归因与批过的理由都能当结果`() {
        // 学习判了不打扰（SUPPRESS）是最新一行 ⇒ 做法=学习判了不打扰
        val suppressed = LearnContextNoteJudge.line(
            listOf(
                row("删目录", now, kind = ExperienceKind.SUPPRESS, key = "suppress:closes=3", reason = "学到的阈值：不弹"),
                row("删目录", now - dayMs),
                row("删目录", now - 2 * dayMs),
                row("删目录", now - 3 * dayMs),
                row("删目录", now - 4 * dayMs),
                row("删目录", now - 5 * dayMs),
            ),
            now + dayMs,
        )!!
        assertTrue(suppressed.contains("学习判了不打扰"))
        assertTrue(suppressed.contains("学到的阈值：不弹"))
        // 用户批了（approve）也能当结果
        val approved = LearnContextNoteJudge.line(
            listOf(
                row("整表排序", now, key = "signal:approve", reason = "用户批了这张卡"),
                row("整表排序", now - dayMs),
                row("整表排序", now - 2 * dayMs),
                row("整表排序", now - 3 * dayMs),
                row("整表排序", now - 4 * dayMs),
                row("整表排序", now - 5 * dayMs),
            ),
            now + dayMs,
        )!!
        assertTrue(approved.contains("用户批了"))
    }
}
