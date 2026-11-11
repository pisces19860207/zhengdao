// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百四十九批 · 五条流**法条**的判据（纯函数；570 床上的全链夹具在 EnvoyFlow570Test）。
// 每条流一条正例（合法链逐格断言）＋ 一组反例（非法迁移被认出来——账记事实，法条咬人）。
package com.example.zhengdao.baopu.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvoyTrackTest {

    // ── 流 submitted：∅→submitted 是收条；submitted 之后还没开干时可再读到 submitted ──

    @Test
    fun `流一_submitted_第一步是收条_没批准的状态不算走`() {
        assertTrue("∅→submitted（send）合法", EnvoyLaw.legal(null, A2aTaskState.SUBMITTED))
        assertTrue("∅→working（对端收下即开干）合法", EnvoyLaw.legal(null, A2aTaskState.WORKING))
        assertTrue("∅→completed（对端一口气答完，spec 允许）合法", EnvoyLaw.legal(null, A2aTaskState.COMPLETED))
        assertTrue("∅→canceled 合法", EnvoyLaw.legal(null, A2aTaskState.CANCELED))
        assertTrue("∅→input-required 合法", EnvoyLaw.legal(null, A2aTaskState.INPUT_REQUIRED))
        assertEquals("第一步的账面形状", "∅→submitted(send)", step(null, A2aTaskState.SUBMITTED, EnvoyAction.SEND).wire())
    }

    @Test
    fun `流二_working_开干与还在干_合法`() {
        assertTrue("submitted→working 合法", EnvoyLaw.legal(A2aTaskState.SUBMITTED, A2aTaskState.WORKING))
        assertTrue("submitted→submitted（对端还没开干再轮到一次）合法", EnvoyLaw.legal(A2aTaskState.SUBMITTED, A2aTaskState.SUBMITTED))
        assertTrue("working→working（轮询不算跳变）合法", EnvoyLaw.legal(A2aTaskState.WORKING, A2aTaskState.WORKING))
    }

    @Test
    fun `流三_input-required_停下来要人与回话续任务_合法`() {
        assertTrue("working→input-required 合法", EnvoyLaw.legal(A2aTaskState.WORKING, A2aTaskState.INPUT_REQUIRED))
        assertTrue("input-required→working（回话续任务）合法", EnvoyLaw.legal(A2aTaskState.INPUT_REQUIRED, A2aTaskState.WORKING))
        assertTrue("auth-required→working 同一支", EnvoyLaw.legal(A2aTaskState.AUTH_REQUIRED, A2aTaskState.WORKING))
    }

    @Test
    fun `流四_completed_终态可直达_且粘住`() {
        assertTrue("working→completed 合法", EnvoyLaw.legal(A2aTaskState.WORKING, A2aTaskState.COMPLETED))
        assertTrue("completed→completed（幂等重读）合法", EnvoyLaw.legal(A2aTaskState.COMPLETED, A2aTaskState.COMPLETED))
        assertFalse("completed→working（终态翻案）非法", EnvoyLaw.legal(A2aTaskState.COMPLETED, A2aTaskState.WORKING))
        assertFalse("completed→input-required 非法", EnvoyLaw.legal(A2aTaskState.COMPLETED, A2aTaskState.INPUT_REQUIRED))
    }

    @Test
    fun `流五_canceled_终态粘住_再查仍取消`() {
        assertTrue("working→canceled 合法", EnvoyLaw.legal(A2aTaskState.WORKING, A2aTaskState.CANCELED))
        assertTrue("canceled→canceled（取消后再查仍取消）合法", EnvoyLaw.legal(A2aTaskState.CANCELED, A2aTaskState.CANCELED))
        assertFalse("canceled→completed（取消后冒出产物）非法", EnvoyLaw.legal(A2aTaskState.CANCELED, A2aTaskState.COMPLETED))
        assertTrue("failed 也是终态：failed→failed 幂等合法", EnvoyLaw.legal(A2aTaskState.FAILED, A2aTaskState.FAILED))
    }

    @Test
    fun `边界_要人是合法的停_未知状态不拦`() {
        assertTrue(
            "submitted→input-required（对端收下就缺料）合法——要人是 A2A 状态机的一部分",
            EnvoyLaw.legal(A2aTaskState.SUBMITTED, A2aTaskState.INPUT_REQUIRED),
        )
        assertTrue(
            "unknown→working 不拦（读侧兜底：对端给了认不出的状态，记账归记账，法条不误伤）",
            EnvoyLaw.legal(A2aTaskState.UNKNOWN, A2aTaskState.WORKING),
        )
    }

    // ── 轨迹记账：非法迁移照样落 trail（事实不许抹），但 observe 返回 false ──────────

    @Test
    fun `轨迹_非法迁移也落账_但返回false_执行层据此停手`() {
        var now = 1000L
        val track = EnvoyTrack("t-1", nowMs = { now })
        assertTrue(track.observe(A2aTaskState.SUBMITTED, EnvoyAction.SEND))
        now = 2000L
        assertTrue(track.observe(A2aTaskState.WORKING, EnvoyAction.POLL))
        now = 3000L
        assertTrue(track.observe(A2aTaskState.COMPLETED, EnvoyAction.POLL))
        now = 4000L
        assertFalse("终态翻案：记了账但法条说不合法", track.observe(A2aTaskState.WORKING, EnvoyAction.POLL))
        assertEquals("四步全在（事实不许抹）", 4, track.trail.size)
        assertFalse(
            "非法迁移记进来之后 current 是对端嘴里的新状态——settled 不许再替它担保（执行层已停手）",
            track.settled,
        )
        assertEquals(
            "账面摘要可读",
            "∅→submitted(send)→working(poll)→completed(poll)→working(poll)",
            track.wire(),
        )
        assertEquals("步上带时刻", 4000L, track.trail.last().atMs)
    }

    @Test
    fun `轨迹_空轨迹的摘要_不是空串`() {
        val track = EnvoyTrack("t-0")
        assertEquals("（一步未走）", track.wire())
        assertTrue("current 为 null", track.current == null)
        assertFalse("没走到终态", track.settled)
    }

    private fun step(from: A2aTaskState?, to: A2aTaskState, action: EnvoyAction): EnvoyStep =
        EnvoyStep(from, to, action, 0L)
}
