// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.Provider
import com.example.zhengdao.baopu.Providers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 正交协作层用例（第六十四批派工单 §5）——**模拟任务在跑**，不跑真 SQLite/网络。
 *
 * 模拟循环与 RunEngine.runLoop 的同构点（派工单 §2 只认这三个安全点）：
 * 一步开始前调 `atSafePoint(StepBoundary)`、出确认卡前调 `BeforeConfirmCard`、
 * 收尾调 `AfterSettled`；**一步内部没有任何 atSafePoint 调用** ⇒ "step 中途不切"
 * 由结构保证（协调器没有任何 mid-step API），本文件用循环把这条钉成可测事实。
 */
class ChannelSwitchCoordinatorTest {

    private val current = Providers.DEEPSEEK // vision=true
    private val target = Providers.ANTHROPIC.copy(id = "anthropic", label = "Anthropic")

    /** 效应替身：成功/失败/记账三态可配。 */
    private class FakeEffect(var outcome: SwitchApplyOutcome? = null) {
        val calls = mutableListOf<Provider>()
        operator fun invoke(target: Provider): SwitchApplyOutcome {
            calls += target
            return outcome ?: SwitchApplyOutcome.Applied(target)
        }
    }

    private fun coordinator(
        effect: FakeEffect = FakeEffect(),
        images: () -> Boolean = { false },
        log: (String) -> Unit = {},
    ) = ChannelSwitchCoordinator(applySwitch = effect::invoke, hasPendingImages = images, log = log)

    // ── 受理与话术 ─────────────────────────────────────────────────────

    @Test
    fun `请求切换_受理且进PENDING_话术说清不打断当前步`() {
        val c = coordinator()
        val msg = c.requestSwitch(current, target)
        assertTrue(msg.contains("不打断当前步"))
        assertEquals(ChannelSwitchState.PENDING, c.state())
        assertEquals(target.id, c.snapshot().requestedTarget?.id)
    }

    @Test
    fun `目标就是当前通道_效应层拒绝前先有话术`() {
        val c = coordinator()
        val msg = c.requestSwitch(current, current.copy())
        assertTrue("同一个通道也要进 PENDING（效应层拒绝），话术不误导", msg.isNotBlank())
    }

    // ── 安全点一：两步之间（模拟任务在跑） ─────────────────────────────

    @Test
    fun `step中途不切_到安全点才切`() {
        val effect = FakeEffect()
        val c = coordinator(effect)
        c.requestSwitch(current, target)

        // 模拟跑一步：step 内部（读图/请求/工具批）没有任何 atSafePoint 调用
        var step = 0
        var providerInUse = current
        step++
        assertEquals("step 中途效应一次都不该跑", 0, effect.calls.size)
        assertEquals("step 中途 provider 原样", current.id, providerInUse.id)

        // 一步结束、下一步开始前 = StepBoundary 安全点
        val o = c.atSafePoint(SafePoint.StepBoundary)
        assertTrue(o.switched)
        providerInUse = o.provider!!
        assertEquals(target.id, providerInUse.id)
        assertEquals(1, effect.calls.size)
    }

    @Test
    fun `三步循环_切换只在边界发生一次_后续步不重复切`() {
        val effect = FakeEffect()
        val c = coordinator(effect)
        c.requestSwitch(current, target)
        var switchCount = 0
        var providerInUse = current
        repeat(3) { i ->
            val o = c.atSafePoint(SafePoint.StepBoundary)
            if (o.switched) {
                switchCount++
                providerInUse = o.provider!!
            }
            // step 体：断言此步用的 provider
            if (i == 0) assertEquals(target.id, providerInUse.id) else assertEquals(target.id, providerInUse.id)
        }
        assertEquals("一次切换只在第一个边界发生", 1, switchCount)
        assertEquals("效应只跑一次", 1, effect.calls.size)
        assertEquals("之后回到 CURRENT 稳态", ChannelSwitchState.CURRENT, c.state())
    }

    @Test
    fun `无待切换时_任何安全点都是空转`() {
        val effect = FakeEffect()
        val c = coordinator(effect)
        assertFalse(c.atSafePoint(SafePoint.StepBoundary).switched)
        assertFalse(c.atSafePoint(SafePoint.BeforeConfirmCard).switched)
        assertFalse(c.atSafePoint(SafePoint.AfterSettled).switched)
        assertEquals(0, effect.calls.size)
    }

    // ── 安全点二/三：确认卡前、Settled 后 ──────────────────────────────

    @Test
    fun `出确认卡之前_也切`() {
        val effect = FakeEffect()
        val c = coordinator(effect)
        c.requestSwitch(current, target)
        val o = c.atSafePoint(SafePoint.BeforeConfirmCard)
        assertTrue(o.switched)
        assertEquals(target.id, o.provider?.id)
    }

    @Test
    fun `本轮Settled之后_立刻切`() {
        val effect = FakeEffect()
        val c = coordinator(effect)
        c.requestSwitch(current, target)
        val o = c.atSafePoint(SafePoint.AfterSettled)
        assertTrue("收尾后要立刻切，不等下一轮", o.switched)
        assertEquals(target.id, o.provider?.id)
    }

    @Test
    fun `三类安全点都能触发_互不重复`() {
        // 同一请求只会在第一个遇到的安全点兑现
        val effect = FakeEffect()
        val c = coordinator(effect)
        c.requestSwitch(current, target)
        assertTrue(c.atSafePoint(SafePoint.StepBoundary).switched)
        assertFalse(c.atSafePoint(SafePoint.BeforeConfirmCard).switched)
        assertFalse(c.atSafePoint(SafePoint.AfterSettled).switched)
        assertEquals(1, effect.calls.size)
    }

    // ── 单飞 ──────────────────────────────────────────────────────────

    @Test
    fun `PENDING期第二个请求_被忽略_话术说清在等安全点`() {
        val c = coordinator()
        c.requestSwitch(current, target)
        val msg = c.requestSwitch(current, Providers.OPENAI_COMPAT)
        assertTrue(msg.contains("已忽略"))
        assertTrue(msg.contains("安全点"))
        assertEquals(ChannelSwitchState.PENDING, c.state())
        assertEquals("请求负荷不许被第二个顶掉", target.id, c.snapshot().requestedTarget?.id)
    }

    @Test
    fun `切换效应执行期间_第二个请求被单飞忽略`() {
        // 效应内部重入：模拟「SWITCHING 窗口期 UI 又发一个请求」
        lateinit var coord: ChannelSwitchCoordinator
        var secondMsg: String? = null
        val effect: (Provider) -> SwitchApplyOutcome = { t ->
            secondMsg = coord.requestSwitch(current, Providers.OPENAI_COMPAT)
            SwitchApplyOutcome.Applied(t)
        }
        coord = ChannelSwitchCoordinator(applySwitch = effect)
        coord.requestSwitch(current, target)
        val o = coord.atSafePoint(SafePoint.StepBoundary)
        assertTrue(o.switched)
        assertNotNull("窗口期内第二个请求必须有回话", secondMsg)
        assertTrue(secondMsg!!.contains("已忽略"))
        assertEquals("第二个请求不许把状态带跑", ChannelSwitchState.SWITCHED, coord.state())
    }

    // ── 失败回退 ──────────────────────────────────────────────────────

    @Test
    fun `切换失败_留在原通道_lastResult可读_日志含原因`() {
        val logs = mutableListOf<String>()
        val effect = FakeEffect(SwitchApplyOutcome.Failed("Key 无效（401）"))
        val c = coordinator(effect, log = { logs += it })
        c.requestSwitch(current, target)
        val o = c.atSafePoint(SafePoint.StepBoundary)
        assertFalse("失败不算切换成功", o.switched)
        assertNull("失败不该给新 provider", o.provider)
        assertTrue(o.message!!.contains("Key 无效（401）"))
        assertEquals(ChannelSwitchState.FALLBACK, c.state())
        assertEquals(SwitchResult.Failed("Key 无效（401）"), c.snapshot().lastResult)
        assertTrue("日志必须含原因", logs.any { it.contains("Key 无效（401）") })
    }

    @Test
    fun `失败后可重试_且成功后lastResult变Ok`() {
        val effect = FakeEffect(SwitchApplyOutcome.Failed("网络不通"))
        val c = coordinator(effect)
        c.requestSwitch(current, target)
        assertFalse(c.atSafePoint(SafePoint.StepBoundary).switched)
        effect.outcome = SwitchApplyOutcome.Applied(target)
        val msg = c.requestSwitch(current, target)
        assertTrue(msg.contains("不打断当前步"))
        val o = c.atSafePoint(SafePoint.AfterSettled)
        assertTrue(o.switched)
        assertEquals(SwitchResult.Ok, c.snapshot().lastResult)
    }

    // ── 能力差异（§3 三选一：默认拒切，用户显式选「去图再切」） ──────────

    @Test
    fun `有图要发_目标无vision_默认拒切_二选一话术`() {
        val effect = FakeEffect()
        val noVision = Providers.DEEPSEEK.copy(id = "text-only", label = "纯文本通道", vision = false)
        val c = coordinator(effect, images = { true })
        val msg = c.requestSwitch(current /*vision*/, noVision, allowDropImages = false)
        assertTrue(msg.contains("不支持看图"))
        assertTrue("必须给两个明确选项", msg.contains("留在原通道") && msg.contains("去掉图再切"))
        assertEquals("默认拒切 ⇒ 连 PENDING 都不进", ChannelSwitchState.CURRENT, c.state())
        assertEquals("效应一次未跑", 0, effect.calls.size)
    }

    @Test
    fun `用户显式选去图再切_受理`() {
        val effect = FakeEffect()
        val noVision = Providers.DEEPSEEK.copy(id = "text-only", label = "纯文本通道", vision = false)
        val c = coordinator(effect, images = { true })
        val msg = c.requestSwitch(current, noVision, allowDropImages = true)
        assertTrue(msg.contains("不打断当前步"))
        assertEquals(ChannelSwitchState.PENDING, c.state())
    }

    @Test
    fun `本轮无图_切到无vision通道不受影响`() {
        val effect = FakeEffect()
        val noVision = Providers.DEEPSEEK.copy(id = "text-only", label = "纯文本通道", vision = false)
        val c = coordinator(effect, images = { false })
        val msg = c.requestSwitch(current, noVision)
        assertTrue(msg.contains("不打断当前步"))
        assertEquals(ChannelSwitchState.PENDING, c.state())
    }

    @Test
    fun `无vision切到有vision_不拦`() {
        val c = coordinator(images = { true })
        val textOnly = Providers.DEEPSEEK.copy(id = "text-only", vision = false)
        val msg = c.requestSwitch(textOnly, current)
        assertTrue(msg.contains("不打断当前步"))
    }

    // ── usage 不丢不重（模拟口径：步号连续、每步一行 usage） ────────────

    @Test
    fun `切换前后_步号连续_usage行不丢不重`() {
        // 模拟 RunEngine 的 persistUsage(sessionId, step, usage)：每步一行，行键=(session,step)
        val rows = LinkedHashMap<Int, Int>() // step -> tokens
        val effect = FakeEffect()
        val c = coordinator(effect)
        var step = 0
        fun runOneStep(tokens: Int) {
            // StepBoundary 安全点（stepCount 增长前）
            val o = c.atSafePoint(SafePoint.StepBoundary)
            step++
            rows[step] = tokens // 本步的 usage 落这行
        }
        // 三步后请求切换，第四步边界切换，继续两步
        repeat(3) { runOneStep(100) }
        c.requestSwitch(current, target)
        runOneStep(100) // 这步的边界上兑现切换
        assertTrue(rows.keys.toList() == listOf(1, 2, 3, 4))
        repeat(2) { runOneStep(120) }
        assertEquals(listOf(1, 2, 3, 4, 5, 6), rows.keys.toList())
        assertEquals("token 总数守恒（切换不给账）", 640, rows.values.sum())
        assertEquals("切换只发生一次", 1, effect.calls.size)
    }
}
