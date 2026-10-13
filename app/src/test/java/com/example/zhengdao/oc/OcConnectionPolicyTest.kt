// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.oc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「重进太极要不要重新建连」判据（[shouldReopenConnection]）的边界单测。
 *
 * 背景（2026-10-11 用户报「每次重进太极都会重新连接」）：repository 现在挂在 [OcRuntime]
 * 上（进程级，切 Tab 不拆连接），于是**每次重进页面都会调一次 `open()`**。这几条把
 * "什么时候该沉默地复用、什么时候必须重开"钉死——写松了会退化成原来那样每次重连，
 * 写紧了会在真断线/切会话/失败重试时卡住不动。
 */
class OcConnectionPolicyTest {

    @Test
    fun `同会话且轮询与 SSE 都活 —— 复用，不重开`() {
        assertFalse(
            shouldReopenConnection(
                requested = "s1", current = "s1",
                pollAlive = true, sseAlive = true, isFailed = false,
            )
        )
    }

    @Test
    fun `requested 为空但连接活着 —— 复用（切 Tab 回来走的就是这条）`() {
        // 页面重建后 rememberSaveable 里的会话 id 会丢，launched 时传的是 null；
        // 若这里判成"要重开"，用户看到的就是"又连了一次"。
        assertFalse(
            shouldReopenConnection(
                requested = null, current = "s1",
                pollAlive = true, sseAlive = true, isFailed = false,
            )
        )
    }

    @Test
    fun `明确要求切到别的会话 —— 必须重开`() {
        assertTrue(
            shouldReopenConnection(
                requested = "s2", current = "s1",
                pollAlive = true, sseAlive = true, isFailed = false,
            )
        )
    }

    @Test
    fun `请求的就是当前会话 —— 不重开（点历史里的当前条目也一样）`() {
        assertFalse(
            shouldReopenConnection(
                requested = "s1", current = "s1",
                pollAlive = true, sseAlive = true, isFailed = false,
            )
        )
    }

    @Test
    fun `轮询任务停了 —— 重开（REST 轮询才是主数据源，ERRATA E-008）`() {
        assertTrue(
            shouldReopenConnection(
                requested = null, current = "s1",
                pollAlive = false, sseAlive = true, isFailed = false,
            )
        )
    }

    @Test
    fun `SSE 任务停了 —— 重开`() {
        assertTrue(
            shouldReopenConnection(
                requested = null, current = "s1",
                pollAlive = true, sseAlive = false, isFailed = false,
            )
        )
    }

    @Test
    fun `两条 Job 都不在（进程里换过 repository）—— 重开`() {
        assertTrue(
            shouldReopenConnection(
                requested = null, current = "s1",
                pollAlive = false, sseAlive = false, isFailed = false,
            )
        )
    }

    @Test
    fun `失败态 —— 即使 Job 还在也要重开（重试按钮靠这条）`() {
        // FailedPane 的「重试」直接调 open()；若被复用分支拦下，按钮就成了摆设。
        assertTrue(
            shouldReopenConnection(
                requested = null, current = "s1",
                pollAlive = true, sseAlive = true, isFailed = true,
            )
        )
    }

    @Test
    fun `还没有会话 id —— 必须开`() {
        assertTrue(
            shouldReopenConnection(
                requested = null, current = null,
                pollAlive = false, sseAlive = false, isFailed = false,
            )
        )
    }

    @Test
    fun `空串会话 id 等同于没有 —— 必须开`() {
        // 服务端字段缺失时 optString 会给空串（本项目多处踩过，见 OcRepository.unwrapData）
        assertTrue(
            shouldReopenConnection(
                requested = null, current = "",
                pollAlive = true, sseAlive = true, isFailed = false,
            )
        )
    }

    // ── E-123 回归（2026-10-13 真机：太极页切会话后页面停在空列表、轮询还挂在旧会话上，
    //    杀掉 App 重进才能恢复）────────────────────────────────────────────────

    @Test
    fun `切会话的预置状态只清残留，绝不动 sessionId`() {
        val current = TaijiState(
            sessionId = "s1",
            loadedOnce = true,
            input = "半句话",
            isStreaming = true,
        )

        val preset = current.afterSessionSwitchStarted()

        // ⚠️ 这一条是 E-095 的要害：预置里若把 sessionId 写成目标 id，
        //    下面的判据就会把「切会话」当成「同会话」。
        assertEquals("s1", preset.sessionId)
        assertFalse(preset.loadedOnce)
        assertEquals("", preset.input)
        assertFalse(preset.isStreaming)
        assertEquals(TaijiPhase.Loading, preset.phase)
        assertTrue(preset.messages.isEmpty())
        assertTrue(preset.parts.isEmpty())
    }

    @Test
    fun `切会话预置后 + 判据 ⇒ 必须重开（E-123 的真正断点）`() {
        val preset = TaijiState(sessionId = "s1", loadedOnce = true).afterSessionSwitchStarted()

        // 正确写法：预置不动 sessionId ⇒ requested(s2) != current(s1) ⇒ 重开
        assertTrue(
            shouldReopenConnection(
                requested = "s2", current = preset.sessionId,
                pollAlive = true, sseAlive = true, isFailed = false,
            )
        )

        // 反例（老写法）：预置时就把 sessionId 写成 s2 ⇒ 判据看到 requested == current
        // ⇒ 走复用分支 return ⇒ 不 loadAll、不重建轮询/SSE、页面停在空列表。
        assertFalse(
            shouldReopenConnection(
                requested = "s2", current = "s2",
                pollAlive = true, sseAlive = true, isFailed = false,
            )
        )
    }
}
