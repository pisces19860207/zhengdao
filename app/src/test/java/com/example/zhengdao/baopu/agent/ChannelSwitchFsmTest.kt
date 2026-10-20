// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.LlmProtocol
import com.example.zhengdao.baopu.Provider
import com.example.zhengdao.baopu.Providers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 资源机迁移表（第六十四批派工单 §5）——**全部合法迁移 + 全部非法迁移**。
 *
 * 判据口径：`Accepted(changed)` / `Ignored` / `Rejected` 三分类；
 * 未定义迁移一律 Rejected 且带原因（不许静默落态）。
 */
class ChannelSwitchFsmTest {

    private fun deepseek() = Providers.DEEPSEEK
    private fun anthropic() = Providers.ANTHROPIC

    private fun fsm() = ChannelSwitchFsm()

    // ── 合法迁移（派工单 §1 明条） ───────────────────────────────────────

    @Test
    fun `CURRENT_加_SwitchRequested_得_PENDING`() {
        val o = fsm().dispatch(SwitchEvent.Requested(deepseek()))
        assertTrue(o is SwitchOutcome.Accepted)
        o as SwitchOutcome.Accepted
        assertTrue("状态必须真的变了", o.changed)
        assertEquals(ChannelSwitchState.PENDING, o.snapshot.state)
        assertEquals(deepseek().id, o.snapshot.requestedTarget?.id)
    }

    @Test
    fun `PENDING_加_ReachedSafePoint_得_SWITCHING`() {
        val f = fsm()
        f.dispatch(SwitchEvent.Requested(deepseek()))
        val o = f.dispatch(SwitchEvent.ReachedSafePoint)
        assertTrue(o is SwitchOutcome.Accepted)
        o as SwitchOutcome.Accepted
        assertTrue(o.changed)
        assertEquals(ChannelSwitchState.SWITCHING, o.snapshot.state)
    }

    @Test
    fun `SWITCHING_加_SwitchStarted_不迁移但接受_标记`() {
        val f = fsm()
        f.dispatch(SwitchEvent.Requested(deepseek()))
        f.dispatch(SwitchEvent.ReachedSafePoint)
        val o = f.dispatch(SwitchEvent.SwitchStarted)
        assertTrue("SwitchStarted 是效应窗口标记，必须被接受", o is SwitchOutcome.Accepted)
        o as SwitchOutcome.Accepted
        assertFalse("只是标记，不该改状态", o.changed)
        assertEquals(ChannelSwitchState.SWITCHING, o.snapshot.state)
    }

    @Test
    fun `SWITCHING_加_SwitchSucceeded_得_SWITCHED_并留lastResult`() {
        val f = fsm()
        f.dispatch(SwitchEvent.Requested(deepseek()))
        f.dispatch(SwitchEvent.ReachedSafePoint)
        f.dispatch(SwitchEvent.SwitchStarted)
        val o = f.dispatch(SwitchEvent.SwitchSucceeded)
        assertTrue(o is SwitchOutcome.Accepted)
        o as SwitchOutcome.Accepted
        assertEquals(ChannelSwitchState.SWITCHED, o.snapshot.state)
        assertEquals(SwitchResult.Ok, o.snapshot.lastResult)
        assertNull("请求负荷用完即清", o.snapshot.requestedTarget)
    }

    @Test
    fun `SWITCHING_加_SwitchFailed_得_FALLBACK_并留原因`() {
        val f = fsm()
        f.dispatch(SwitchEvent.Requested(deepseek()))
        f.dispatch(SwitchEvent.ReachedSafePoint)
        val o = f.dispatch(SwitchEvent.SwitchFailed("Key 无效（401）"))
        assertTrue(o is SwitchOutcome.Accepted)
        o as SwitchOutcome.Accepted
        assertEquals(ChannelSwitchState.FALLBACK, o.snapshot.state)
        assertEquals(SwitchResult.Failed("Key 无效（401）"), o.snapshot.lastResult)
        assertNull(o.snapshot.requestedTarget)
    }

    @Test
    fun `任何态_不在PENDING_收到ReachedSafePoint_不变`() {
        // 派工单明条。CURRENT 上发安全点 ⇒ Accepted 且 changed=false。
        val o = fsm().dispatch(SwitchEvent.ReachedSafePoint)
        assertTrue(o is SwitchOutcome.Accepted)
        o as SwitchOutcome.Accepted
        assertFalse(o.changed)
        assertEquals(ChannelSwitchState.CURRENT, o.snapshot.state)
    }

    // ── 单飞（PENDING/SWITCHING 收第二个请求 ⇒ 忽略，不排队两个） ──────────

    @Test
    fun `PENDING_加第二个Requested_忽略且状态不变`() {
        val f = fsm()
        f.dispatch(SwitchEvent.Requested(deepseek()))
        val o = f.dispatch(SwitchEvent.Requested(anthropic()))
        assertTrue("第二个请求必须被忽略，不许排队", o is SwitchOutcome.Ignored)
        o as SwitchOutcome.Ignored
        assertEquals(ChannelSwitchState.PENDING, o.snapshot.state)
        assertTrue("话术要让人知道在等安全点", o.message.contains("安全点"))
    }

    @Test
    fun `SWITCHING_加第二个Requested_忽略_话术说清正在切换`() {
        val f = fsm()
        f.dispatch(SwitchEvent.Requested(deepseek()))
        f.dispatch(SwitchEvent.ReachedSafePoint)
        val o = f.dispatch(SwitchEvent.Requested(anthropic()))
        assertTrue(o is SwitchOutcome.Ignored)
        o as SwitchOutcome.Ignored
        assertEquals(ChannelSwitchState.SWITCHING, o.snapshot.state)
        assertTrue(o.message.contains("已忽略第二个请求"))
        assertTrue("话术要点名正在切去哪个通道", o.message.contains(deepseek().label))
    }

    // ── 派生迁移 D1：SWITCHED 瞬态即返 ─────────────────────────────────

    @Test
    fun `SWITCHED_加任何事件_先自动回CURRENT_lastResult保留`() {
        val f = fsm()
        f.dispatch(SwitchEvent.Requested(deepseek()))
        f.dispatch(SwitchEvent.ReachedSafePoint)
        f.dispatch(SwitchEvent.SwitchSucceeded)
        assertEquals(ChannelSwitchState.SWITCHED, f.snapshot().state)
        // ReachedSafePoint ⇒ 先回 CURRENT 再按「不在 PENDING ⇒ 不变」处理
        val o = f.dispatch(SwitchEvent.ReachedSafePoint)
        assertTrue(o is SwitchOutcome.Accepted)
        o as SwitchOutcome.Accepted
        assertTrue("自动回 CURRENT 是状态变化", o.changed)
        assertEquals(ChannelSwitchState.CURRENT, o.snapshot.state)
        assertEquals("lastResult 必须留着（可观察）", SwitchResult.Ok, o.snapshot.lastResult)
    }

    @Test
    fun `SWITCHED_态直接再请求_受理并回PENDING`() {
        val f = fsm()
        f.dispatch(SwitchEvent.Requested(deepseek()))
        f.dispatch(SwitchEvent.ReachedSafePoint)
        f.dispatch(SwitchEvent.SwitchSucceeded)
        // 成功后再请求另一个通道：自动回 CURRENT ⇒ CURRENT + Requested ⇒ PENDING
        val o = f.dispatch(SwitchEvent.Requested(anthropic()))
        assertTrue(o is SwitchOutcome.Accepted)
        o as SwitchOutcome.Accepted
        assertEquals(ChannelSwitchState.PENDING, o.snapshot.state)
        assertEquals(anthropic().id, o.snapshot.requestedTarget?.id)
        assertEquals("成功后 lastResult 仍是 Ok", SwitchResult.Ok, o.snapshot.lastResult)
    }

    // ── 派生迁移 D2：FALLBACK 是稳态变体，可重试 ────────────────────────

    @Test
    fun `FALLBACK_态可再收请求_得PENDING`() {
        val f = fsm()
        f.dispatch(SwitchEvent.Requested(deepseek()))
        f.dispatch(SwitchEvent.ReachedSafePoint)
        f.dispatch(SwitchEvent.SwitchFailed("网络不通"))
        assertEquals(ChannelSwitchState.FALLBACK, f.snapshot().state)
        val o = f.dispatch(SwitchEvent.Requested(anthropic()))
        assertTrue("失败后必须还能重试，否则这台机器一次失败就报废", o is SwitchOutcome.Accepted)
        o as SwitchOutcome.Accepted
        assertEquals(ChannelSwitchState.PENDING, o.snapshot.state)
    }

    @Test
    fun `FALLBACK_态失败原因一直可读_直到下次结果覆盖`() {
        val f = fsm()
        f.dispatch(SwitchEvent.Requested(deepseek()))
        f.dispatch(SwitchEvent.ReachedSafePoint)
        f.dispatch(SwitchEvent.SwitchFailed("服务端 500"))
        assertEquals(SwitchResult.Failed("服务端 500"), f.snapshot().lastResult)
        f.dispatch(SwitchEvent.Requested(anthropic()))
        assertEquals("还在失败态的原因上排着，原因不许丢", SwitchResult.Failed("服务端 500"), f.snapshot().lastResult)
    }

    // ── 非法迁移（未定义一律拒绝并带原因） ───────────────────────────────

    @Test
    fun `CURRENT_态_SwitchStarted_拒绝`() {
        val o = fsm().dispatch(SwitchEvent.SwitchStarted)
        assertTrue(o is SwitchOutcome.Rejected)
        assertTrue((o as SwitchOutcome.Rejected).reason.contains("未定义迁移"))
        assertEquals("拒绝后状态不许动", ChannelSwitchState.CURRENT, fsm().snapshot().state)
    }

    @Test
    fun `CURRENT_态_SwitchSucceeded_拒绝`() {
        val o = fsm().dispatch(SwitchEvent.SwitchSucceeded)
        assertTrue(o is SwitchOutcome.Rejected)
        assertTrue((o as SwitchOutcome.Rejected).reason.contains("未定义迁移"))
    }

    @Test
    fun `CURRENT_态_SwitchFailed_拒绝`() {
        val o = fsm().dispatch(SwitchEvent.SwitchFailed("x"))
        assertTrue(o is SwitchOutcome.Rejected)
        assertTrue((o as SwitchOutcome.Rejected).reason.contains("未定义迁移"))
    }

    @Test
    fun `PENDING_态_SwitchStarted_拒绝`() {
        val f = fsm()
        f.dispatch(SwitchEvent.Requested(deepseek()))
        val o = f.dispatch(SwitchEvent.SwitchStarted)
        assertTrue(o is SwitchOutcome.Rejected)
        assertEquals(ChannelSwitchState.PENDING, f.snapshot().state)
    }

    @Test
    fun `PENDING_态_SwitchSucceeded与SwitchFailed_拒绝`() {
        val f = fsm()
        f.dispatch(SwitchEvent.Requested(deepseek()))
        assertTrue(f.dispatch(SwitchEvent.SwitchSucceeded) is SwitchOutcome.Rejected)
        assertTrue(f.dispatch(SwitchEvent.SwitchFailed("y")) is SwitchOutcome.Rejected)
        assertEquals("两条拒绝都不许动状态", ChannelSwitchState.PENDING, f.snapshot().state)
    }

    @Test
    fun `拒绝一律带原因并留痕_不静默落态`() {
        // 每个 Stokes 组合逐对过一遍：除了 Accepted/Ignored 白名单，其余全 Rejected。
        val f = fsm()
        val legal = mutableSetOf<Pair<ChannelSwitchState, String>>()
        // CURRENT：Requested/ReachedSafePoint
        legal += ChannelSwitchState.CURRENT to "Requested"
        legal += ChannelSwitchState.CURRENT to "ReachedSafePoint"
        // PENDING：Requested(Ignored)/ReachedSafePoint
        legal += ChannelSwitchState.PENDING to "Requested"
        legal += ChannelSwitchState.PENDING to "ReachedSafePoint"
        // SWITCHING：Requested(Ignored)/SwitchStarted/SwitchSucceeded/SwitchFailed/ReachedSafePoint
        legal += ChannelSwitchState.SWITCHING to "Requested"
        legal += ChannelSwitchState.SWITCHING to "SwitchStarted"
        legal += ChannelSwitchState.SWITCHING to "SwitchSucceeded"
        legal += ChannelSwitchState.SWITCHING to "SwitchFailed"
        legal += ChannelSwitchState.SWITCHING to "ReachedSafePoint"
        // FALLBACK：同 CURRENT
        legal += ChannelSwitchState.FALLBACK to "Requested"
        legal += ChannelSwitchState.FALLBACK to "ReachedSafePoint"
        // SWITCHED 是瞬态，单独用例覆盖，不进本矩阵

        val states = listOf(
            ChannelSwitchState.CURRENT,
            ChannelSwitchState.PENDING,
            ChannelSwitchState.SWITCHING,
            ChannelSwitchState.FALLBACK,
        )
        val events: (String) -> SwitchEvent = { name ->
            when (name) {
                "Requested" -> SwitchEvent.Requested(deepseek())
                "ReachedSafePoint" -> SwitchEvent.ReachedSafePoint
                "SwitchStarted" -> SwitchEvent.SwitchStarted
                "SwitchSucceeded" -> SwitchEvent.SwitchSucceeded
                else -> SwitchEvent.SwitchFailed("矩阵探针")
            }
        }
        for (s in states) {
            for (e in listOf("Requested", "ReachedSafePoint", "SwitchStarted", "SwitchSucceeded", "SwitchFailed")) {
                val f2 = ChannelSwitchFsm(s)
                val before = f2.snapshot()
                val o = f2.dispatch(events(e))
                val key = s to e
                if (key in legal) {
                    assertTrue("$key 应被接受/忽略，实得 $o", o !is SwitchOutcome.Rejected)
                } else {
                    assertTrue("$key 应被拒绝，实得 $o", o is SwitchOutcome.Rejected)
                    assertTrue("$key 拒绝必须带原因", (o as SwitchOutcome.Rejected).reason.isNotBlank())
                    assertEquals("$key 拒绝后状态必须原样", before.state, f2.snapshot().state)
                }
            }
        }
    }

    // ── 初始态 ─────────────────────────────────────────────────────────

    @Test
    fun `初始态是CURRENT_无负荷无结果`() {
        val s = fsm().snapshot()
        assertEquals(ChannelSwitchState.CURRENT, s.state)
        assertNull(s.requestedTarget)
        assertNull(s.lastResult)
    }
}

/**
 * 能力差异纯判据（派工单 §5：vision 有→无 + 本轮有图 ⇒ 明说；无图不受影响）。
 */
class CapabilityDiffTest {

    private fun noVision() = Providers.DEEPSEEK.copy(id = "text-only", label = "纯文本通道", vision = false)
    private fun vision() = Providers.DEEPSEEK.copy(id = "vision-chan", label = "视觉通道", vision = true)

    @Test
    fun `有vision切到无vision_标记需要用户决定`() {
        val d = capabilityDiff(vision(), noVision())
        assertTrue(d.visionLost)
        assertTrue(d.requiresUserDecision)
        assertTrue(d.render().contains("目标不能看图"))
    }

    @Test
    fun `无vision切到有vision_不拦`() {
        val d = capabilityDiff(noVision(), vision())
        assertFalse(d.visionLost)
        assertTrue(d.visionGained)
        assertFalse(d.requiresUserDecision)
    }

    @Test
    fun `两侧vision一致_不动`() {
        val d = capabilityDiff(noVision(), noVision().copy(id = "another"))
        assertFalse(d.visionLost)
        assertFalse(d.visionGained)
        assertFalse(d.requiresUserDecision)
    }

    @Test
    fun `上下文窗口与思考档位差异进数据_但不触发用户决定`() {
        val from = Providers.DEEPSEEK // contextWindow=1048576, effort=high
        val to = Providers.ANTHROPIC.copy(contextWindow = 200_000, reasoningEffort = null)
        val d = capabilityDiff(from, to)
        assertTrue(d.contextWindowFrom == 1_048_576)
        assertTrue(d.contextWindowTo == 200_000)
        assertFalse(d.requiresUserDecision)
        assertTrue(d.render().contains("上下文窗口"))
        assertTrue(d.render().contains("思考档位"))
    }

    @Test
    fun `差异渲染不含Key`() {
        val d = capabilityDiff(Providers.DEEPSEEK, Providers.ANTHROPIC)
        assertFalse("能力差异文本不许泄 Key", d.render().contains("apiKey"))
    }
}
