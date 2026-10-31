// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

package com.example.zhengdao.baopu.inspect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 336 · 意见生命周期的状态机测试（判据钉在这里；改迁移语义必须过这里）。
 */
class OpinionLifecycleTest {

    private fun opinion(evFirst: String) = OpinionAdapter.CLASSIFIER.adapt(
        severity = Severity.MAJOR,
        gist = "越界访问",
        action = ActionCode.REVIEW,
        lawRef = LawRef.L9,
        evidence = listOf(evFirst, "tool:write_file", "matrix:v1"),
    )

    @Test
    fun `初始相位是OPEN`() {
        assertEquals(OpinionLifecycle.Phase.OPEN, OpinionLifecycle.initial().phase)
    }

    @Test
    fun `DEFERRED累计到3轮自动SETTLED并带回结案原因`() {
        var s = OpinionLifecycle.initial()
        s = OpinionLifecycle.advance(s, OpinionLifecycle.Event.DEFERRED)
        assertEquals(OpinionLifecycle.Phase.DEFERRED, s.phase)
        assertEquals(1, s.deferRounds)
        s = OpinionLifecycle.advance(s, OpinionLifecycle.Event.DEFERRED)
        assertEquals(OpinionLifecycle.Phase.DEFERRED, s.phase)
        assertEquals(2, s.deferRounds)
        assertNull(OpinionLifecycle.autoSettlement("k", s)) // 第 2 轮还不结
        s = OpinionLifecycle.advance(s, OpinionLifecycle.Event.DEFERRED)
        assertEquals(OpinionLifecycle.Phase.SETTLED, s.phase)
        assertEquals(3, s.deferRounds)
        assertEquals(OpinionLifecycle.autoSettleWhy(), s.settleWhy)
        val rec = OpinionLifecycle.autoSettlement(OpinionMerger.eventKeyOf(opinion("risk:outside_workspace")), s)
        assertNotNull(rec)
        assertEquals(3, rec!!.deferRounds)
    }

    @Test
    fun `RESOLVED是终态_后续事件不改相`() {
        var s = OpinionLifecycle.initial()
        s = OpinionLifecycle.advance(s, OpinionLifecycle.Event.DISPOSITIONED)
        assertEquals(OpinionLifecycle.Phase.RESOLVED, s.phase)
        // 重试提交再来一轮 DEFERRED/POPPED——已处置的意见不许回到待处置
        s = OpinionLifecycle.advance(s, OpinionLifecycle.Event.DEFERRED)
        assertEquals(OpinionLifecycle.Phase.RESOLVED, s.phase)
        s = OpinionLifecycle.advance(s, OpinionLifecycle.Event.POPPED)
        assertEquals(OpinionLifecycle.Phase.RESOLVED, s.phase)
        assertTrue(OpinionLifecycle.autoSettlement("k", s) == null)
    }

    @Test
    fun `SETTLED也是终态`() {
        var s = OpinionLifecycle.initial()
        repeat(3) { s = OpinionLifecycle.advance(s, OpinionLifecycle.Event.DEFERRED) }
        assertEquals(OpinionLifecycle.Phase.SETTLED, s.phase)
        s = OpinionLifecycle.advance(s, OpinionLifecycle.Event.POPPED)
        assertEquals(OpinionLifecycle.Phase.SETTLED, s.phase)
    }

    @Test
    fun `POPPED回到OPEN并清空defer计数_SKIP不改相`() {
        var s = OpinionLifecycle.initial()
        s = OpinionLifecycle.advance(s, OpinionLifecycle.Event.DEFERRED)
        assertEquals(1, s.deferRounds)
        s = OpinionLifecycle.advance(s, OpinionLifecycle.Event.POPPED)
        assertEquals(OpinionLifecycle.Phase.OPEN, s.phase)
        assertEquals(0, s.deferRounds)
        val before = s
        assertEquals(before, OpinionLifecycle.advance(s, OpinionLifecycle.Event.SKIPPED))
    }

    @Test
    fun `只有OPEN和DEFERRED拦验收_RESOLVED与SETTLED不拦`() {
        assertTrue(OpinionLifecycle.blocksAcceptance(OpinionLifecycle.initial()))
        assertTrue(OpinionLifecycle.blocksAcceptance(OpinionLifecycle.advance(OpinionLifecycle.initial(), OpinionLifecycle.Event.DEFERRED)))
        assertFalse(OpinionLifecycle.blocksAcceptance(OpinionLifecycle.advance(OpinionLifecycle.initial(), OpinionLifecycle.Event.DISPOSITIONED)))
        var s = OpinionLifecycle.initial()
        repeat(3) { s = OpinionLifecycle.advance(s, OpinionLifecycle.Event.DEFERRED) }
        assertFalse(OpinionLifecycle.blocksAcceptance(s))
    }

    @Test
    fun `autoSettlement只认defer自动结案_不认其它相位`() {
        assertNull(OpinionLifecycle.autoSettlement("k", OpinionLifecycle.initial()))
        assertNull(
            OpinionLifecycle.autoSettlement(
                "k",
                OpinionLifecycle.advance(OpinionLifecycle.initial(), OpinionLifecycle.Event.DISPOSITIONED),
            ),
        )
    }

    @Test
    fun `事件键相同重试意见映射到同一状态`() {
        // 模拟 282 D 组死循环：同一越界动作重试提交，每次 OpinionWiring 产出一条"新"意见
        //（callId 不同但证据首项相同）——事件键必须把它们并到同一格状态上。
        val first = opinion("risk:outside_workspace")
        val retry = opinion("risk:outside_workspace")
        val k1 = OpinionMerger.eventKeyOf(first)
        val k2 = OpinionMerger.eventKeyOf(retry)
        assertEquals(k1, k2)
        var s = OpinionLifecycle.advance(OpinionLifecycle.initial(), OpinionLifecycle.Event.DISPOSITIONED)
        // 第二次提交的同事件意见查到的是同一个已 RESOLVED 的状态
        assertEquals(s, OpinionLifecycle.advance(s, OpinionLifecycle.Event.DISPOSITIONED))
    }
}
