// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百三十八批 · 调和器 `HarmonyMediator` 的判据层。
//
// 这一层只钉三件事：
//   1) 四档状态机**逐条迁移**（CALM→CAREFUL→STRAINED→LOOP）与三种回落（两次成功 / 人给台阶 / 静置衰减）；
//   2) 双向额度真的在起作用（人 patienceBudget、共同 loopBudget、模型 askTolerance）；
//   3) 状态**随会话存、跨会话不带**——持久化是纯字符串编解码，坏数据一律回 CALM。
//
// 它**不测**：挂接点（BaopuChatController / BaopuPreset 本批不碰，见交付报告「挂接点」一页）。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HarmonyMediatorTest {

    private val fail = HarmonyMediator.Outcome.Failure("tap")
    private val ok = HarmonyMediator.Outcome.Success
    private val none = HarmonyMediator.Outcome.None

    private fun step(
        prev: HarmonyMediator.State,
        user: String = "",
        model: String = "",
        outcome: HarmonyMediator.Outcome = HarmonyMediator.Outcome.None,
        profile: PatienceProfile = PatienceProfile(),
        now: Long = 0L,
    ) = HarmonyMediator.observe(prev, user, model, outcome, profile, now)

    // ── 1. 四档迁移 ────────────────────────────────────────────────────

    @Test
    fun `迁移_一次失败进CAREFUL_且不发emoji`() {
        val s = step(HarmonyMediator.State(), outcome = fail, now = 1_000L)
        assertEquals(HarmonyMediator.Mood.CAREFUL, s.mood)
        assertEquals(1, s.taskFailures)
        assertEquals(1, s.consecutiveFailures)
        assertEquals(0, s.emojiBudget)
    }

    @Test
    fun `迁移_一次不对也进CAREFUL`() {
        val s = step(HarmonyMediator.State(), user = "不对")
        assertEquals(HarmonyMediator.Mood.CAREFUL, s.mood)
        assertEquals(1, s.consecutiveNegations)
        assertEquals(0, s.taskFailures)
    }

    @Test
    fun `迁移_同任务第二次失败进STRAINED_emoji预算为一`() {
        val s1 = step(HarmonyMediator.State(), outcome = fail, now = 1_000L)
        val s2 = step(s1, outcome = fail, now = 2_000L)
        assertEquals(HarmonyMediator.Mood.STRAINED, s2.mood)
        assertEquals(2, s2.taskFailures)
        assertEquals(2, s2.consecutiveFailures)
        assertEquals(1, s2.emojiBudget)
    }

    @Test
    fun `迁移_责备词一次就进STRAINED`() {
        val s = step(HarmonyMediator.State(), user = "你到底行不行")
        assertEquals(HarmonyMediator.Mood.STRAINED, s.mood)
        assertEquals(1, s.emojiBudget)
    }

    @Test
    fun `迁移_同类第三次失败熔断`() {
        val s1 = step(HarmonyMediator.State(), outcome = fail, now = 1L)
        val s2 = step(s1, outcome = fail, now = 2L)
        val s3 = step(s2, outcome = fail, now = 3L)
        assertEquals(HarmonyMediator.Mood.LOOP, s3.mood)
        assertEquals(3, s3.consecutiveFailures)
        assertTrue(HarmonyMediator.shouldBreakLoop(s3))
    }

    @Test
    fun `迁移_高压档里自贬两次熔断`() {
        val s1 = step(HarmonyMediator.State(), outcome = fail, now = 1L)
        val s2 = step(s1, model = "我太笨了，我真笨", now = 2L)
        assertEquals(HarmonyMediator.Mood.STRAINED, s2.mood)
        val s3 = step(s2, model = "我太笨了，我真笨", now = 3L)
        assertEquals(HarmonyMediator.Mood.LOOP, s3.mood)
    }

    @Test
    fun `常量_衰减窗口三十分钟_熔断看三次同类失败_指引上限六十字`() {
        assertEquals(30L * 60L * 1000L, HarmonyMediator.DECAY_WINDOW_MILLIS)
        assertEquals(3, HarmonyMediator.LOOP_SAME_KIND_FAILURES)
        assertEquals(60, HarmonyMediator.MAX_GUIDANCE_CHARS)
    }

    // ── 2. 回落 ────────────────────────────────────────────────────────

    @Test
    fun `回落_连续两次成功回CALM并清空计数`() {
        val s1 = step(HarmonyMediator.State(), outcome = fail, now = 1L)
        val s2 = step(s1, outcome = fail, now = 2L)
        assertEquals(HarmonyMediator.Mood.STRAINED, s2.mood)
        val s3 = step(s2, outcome = ok, now = 3L)
        assertEquals(HarmonyMediator.Mood.STRAINED, s3.mood)
        val s4 = step(s3, outcome = ok, now = 4L)
        assertEquals(HarmonyMediator.State(), s4)
    }

    @Test
    fun `回落_用户说没事了立即CALM`() {
        val s = step(HarmonyMediator.State(), user = "你怎么这么笨", outcome = fail, now = 1L)
        assertNotEquals(HarmonyMediator.Mood.CALM, s.mood)
        val reset = step(s, user = "没事了，继续吧", now = 2L)
        assertEquals(HarmonyMediator.State(), reset)
        assertFalse(HarmonyMediator.shouldBreakLoop(reset))
    }

    @Test
    fun `回落_静置三十分钟降一档_逐档降到CALM`() {
        val s1 = step(HarmonyMediator.State(), outcome = fail, now = 1L)
        val s2 = step(s1, outcome = fail, now = 2L)
        val s3 = step(s2, outcome = fail, now = 3L)
        assertEquals(HarmonyMediator.Mood.LOOP, s3.mood)

        val half = step(s3, now = 3L + (30L * 60L * 1000L))
        assertEquals(HarmonyMediator.Mood.STRAINED, half.mood)

        val one = step(half, now = 3L + 2 * (30L * 60L * 1000L))
        assertEquals(HarmonyMediator.Mood.CAREFUL, one.mood)

        val calm = step(one, now = 3L + 3 * (30L * 60L * 1000L))
        assertEquals(HarmonyMediator.Mood.CALM, calm.mood)
        assertEquals(0, calm.taskFailures)
        assertEquals(0, calm.consecutiveFailures)
    }

    @Test
    fun `回落_未满三十分钟不降档`() {
        val s = step(HarmonyMediator.State(), outcome = fail, now = 1L)
        val s2 = step(s, outcome = fail, now = 2L)
        assertEquals(HarmonyMediator.Mood.STRAINED, s2.mood)
        val still = step(s2, now = 2L + (30L * 60L * 1000L) - 1L)
        assertEquals(HarmonyMediator.Mood.STRAINED, still.mood)
    }

    @Test
    fun `回落_暂停不因两次成功自动解除`() {
        val paused = step(HarmonyMediator.State(), user = "我不想弄了")
        assertTrue(paused.paused)
        val ok1 = step(paused, outcome = ok)
        val ok2 = step(ok1, outcome = ok)
        assertTrue(ok2.paused)
        val resumed = step(ok2, user = "继续吧")
        assertFalse(resumed.paused)
        assertEquals(HarmonyMediator.Mood.CALM, resumed.mood)
    }

    // ── 3. 双向额度 ─────────────────────────────────────────────────────

    @Test
    fun `额度_人侧耐心额度为一则第一次失败就STRAINED`() {
        val tight = PatienceProfile(patienceBudget = 1)
        val s = step(HarmonyMediator.State(), outcome = fail, profile = tight)
        assertEquals(HarmonyMediator.Mood.STRAINED, s.mood)
        assertEquals(1, s.emojiBudget)
    }

    @Test
    fun `额度_共同熔断额度为三则交替失败累计三次熔断`() {
        val tight = PatienceProfile(loopBudget = 3)
        val a = HarmonyMediator.Outcome.Failure("tap")
        val b = HarmonyMediator.Outcome.Failure("net")
        val c = HarmonyMediator.Outcome.Failure("read")
        val s1 = step(HarmonyMediator.State(), outcome = a, profile = tight, now = 1L)
        val s2 = step(s1, outcome = b, profile = tight, now = 2L)
        assertEquals(HarmonyMediator.Mood.STRAINED, s2.mood)
        assertEquals(1, s2.consecutiveFailures)
        val s3 = step(s2, outcome = c, profile = tight, now = 3L)
        assertEquals(HarmonyMediator.Mood.LOOP, s3.mood)
        assertEquals(3, s3.taskFailures)
    }

    @Test
    fun `额度_模型侧同一问题追问超过容忍次数就换策略`() {
        val q = "为什么没成功"
        val s1 = step(HarmonyMediator.State(), user = q)
        val s2 = step(s1, user = q)
        assertFalse(s2.strategySwitch)
        val s3 = step(s2, user = q)
        assertTrue(s3.strategySwitch)
        assertTrue(HarmonyMediator.guidance(s3).contains("别再猜"))
    }

    @Test
    fun `额度_换策略只在没成功时计数`() {
        val q = "为什么没成功"
        val s1 = step(HarmonyMediator.State(), user = q, outcome = ok)
        val s2 = step(s1, user = q, outcome = ok)
        assertFalse(s2.strategySwitch)
        assertEquals(0, s2.askRepeats)
    }

    // ── 4. 暂停与熔断的输出 ─────────────────────────────────────────────

    @Test
    fun `暂停_输出只给存档与下次继续`() {
        val s = step(HarmonyMediator.State(), user = "我不想弄了")
        assertTrue(HarmonyMediator.shouldBreakLoop(s))
        assertTrue(HarmonyMediator.guidance(s).contains("暂停"))
        assertTrue(HarmonyMediator.guidance(s).contains("存档"))
    }

    @Test
    fun `熔断_输出只剩换件事与存档两条路`() {
        val s1 = step(HarmonyMediator.State(), outcome = fail, now = 1L)
        val s2 = step(s1, outcome = fail, now = 2L)
        val s3 = step(s2, outcome = fail, now = 3L)
        val g = HarmonyMediator.guidance(s3)
        assertTrue(s3.mood == HarmonyMediator.Mood.LOOP)
        assertTrue(g.contains("熔断"))
        assertTrue(g.contains("换件事"))
        assertTrue(g.contains("存档"))
        assertFalse(g.contains("再试"))
    }

    @Test
    fun `emoji_只有高压两档发且最多一个`() {
        var s = HarmonyMediator.State()
        assertEquals(0, s.emojiBudget)

        val careful = step(s, outcome = fail, now = 1L)
        assertEquals(0, careful.emojiBudget)

        val strained = step(careful, outcome = fail, now = 2L)
        assertEquals(1, strained.emojiBudget)

        val loop = step(strained, outcome = fail, now = 3L)
        assertEquals(1, loop.emojiBudget)

        val quiet = PatienceProfile(prefersEmoji = false)
        val noEmoji = step(HarmonyMediator.State(), outcome = fail, profile = quiet, now = 4L)
        val noEmoji2 = step(noEmoji, outcome = fail, profile = quiet, now = 5L)
        assertEquals(0, noEmoji2.emojiBudget)
    }

    @Test
    fun `guidance_每种状态都不超过六十个字`() {
        val states = listOf(
            HarmonyMediator.State(),
            HarmonyMediator.State(paused = true),
            HarmonyMediator.State(mood = HarmonyMediator.Mood.CAREFUL),
            HarmonyMediator.State(mood = HarmonyMediator.Mood.CAREFUL, nearAngerLine = true),
            HarmonyMediator.State(mood = HarmonyMediator.Mood.STRAINED),
            HarmonyMediator.State(mood = HarmonyMediator.Mood.STRAINED, strategySwitch = true),
            HarmonyMediator.State(mood = HarmonyMediator.Mood.LOOP),
        )
        states.forEach { state ->
            val g = HarmonyMediator.guidance(state)
            assertTrue("guidance 超长：$g", g.length <= 60)
            assertTrue("guidance 不许带换行：$g", !g.contains("\n"))
        }
    }

    @Test
    fun `reframe_入口转给ReframeFilter`() {
        assertEquals("约束：不得修改原文件", HarmonyMediator.reframe("别动原文件！", Side.USER))
        assertEquals("该步失败：原因 未说明", HarmonyMediator.reframe("我太笨了", Side.MODEL))
    }

    // ── 5. 持久化：随会话存、跨会话不带 ─────────────────────────────────

    private fun richState(): HarmonyMediator.State {
        val s1 = step(HarmonyMediator.State(), user = "不对", outcome = fail, now = 1_000L)
        return step(s1, user = "这都不行？", outcome = HarmonyMediator.Outcome.Failure("tap"), now = 2_000L)
    }

    @Test
    fun `持久化_同一会话往返一致`() {
        val state = richState()
        val decoded = HarmonyStateCodec.decode(HarmonyStateCodec.encode("sess-1", state), "sess-1")
        assertEquals(state, decoded)
    }

    @Test
    fun `持久化_会话对不上回CALM`() {
        val state = richState()
        assertNotEquals(HarmonyMediator.State(), state)
        val decoded = HarmonyStateCodec.decode(HarmonyStateCodec.encode("sess-1", state), "sess-2")
        assertEquals(HarmonyMediator.State(), decoded)
    }

    @Test
    fun `持久化_坏数据一律回CALM`() {
        assertEquals(HarmonyMediator.State(), HarmonyStateCodec.decode(null, "sess-1"))
        assertEquals(HarmonyMediator.State(), HarmonyStateCodec.decode("", "sess-1"))
        assertEquals(HarmonyMediator.State(), HarmonyStateCodec.decode("垃圾数据", "sess-1"))
        assertEquals(HarmonyMediator.State(), HarmonyStateCodec.decode("h1\nsession=zz\nmood=LOOP", "sess-1"))
        val good = HarmonyStateCodec.encode("sess-1", richState())
        val brokenMood = good.replace("mood=STRAINED", "mood=???")
        assertEquals(HarmonyMediator.State(), HarmonyStateCodec.decode(brokenMood, "sess-1"))
        val brokenCount = good.replace("tf=2", "tf=abc")
        assertEquals(HarmonyMediator.State(), HarmonyStateCodec.decode(brokenCount, "sess-1"))
        assertEquals(HarmonyMediator.State(), HarmonyStateCodec.decode(good, ""))
    }

    @Test
    fun `持久化_编码结果里没有原话`() {
        val state = HarmonyMediator.State(lastFailureKind = "点击「查询」没找到元素")
        val encoded = HarmonyStateCodec.encode("会话甲", state)
        assertFalse(encoded.contains("查询"))
        assertFalse(encoded.contains("没找到"))
        assertFalse(encoded.contains("会话甲"))
        assertEquals(state, HarmonyStateCodec.decode(encoded, "会话甲"))
    }
}
