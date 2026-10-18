// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：2026-10-18 真机验收（抱朴工具补强二）抓到的挂账——「跑完一轮后换一个会话发送，
// 被回『已有另一个会话在跑（全局单活跃）』，重启才恢复」。根因：`AtomicReference`
// 的 compareAndSet 是**引用相等**，而 sessionKey 是 `"id:$sessionId"` 现拼的新字符串
// ⇒ release 的 CAS 永远失败，门放不掉；同一会话重发却能过，因为 tryAcquire 用的是 `==`。
//
// ⚠️ 测试必须**用值相同但引用不同的 key** 去 release（`String(...)` 现造一个），
// 否则捕获不到这个 bug：直接把同一个 String 对象传回 release，引用相等 CAS 也会成功。
package com.example.zhengdao.baopu.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveSessionGateTest {

    /** 值相同、引用不同的 key（模拟生产里 `"id:$sessionId"` 每次现拼）。 */
    private fun freshKey(n: Int): String = String(("id:" + n).toCharArray())

    @Test
    fun `释放后_另一个会话必须抢得到_引用不同值相同也要放掉`() {
        val held = freshKey(991001)
        val other = freshKey(991002)
        assertTrue(ActiveSessionGate.tryAcquire(held))
        assertFalse("别的会话不能抢", ActiveSessionGate.tryAcquire(other))
        ActiveSessionGate.release(freshKey(991001)) // ← 关键：新引用、同值
        assertNull(ActiveSessionGate.current())
        assertTrue("释放后必须换会话能抢到", ActiveSessionGate.tryAcquire(other))
        ActiveSessionGate.release(freshKey(991002))
        assertNull(ActiveSessionGate.current())
    }

    @Test
    fun `释放别人的key_不动当前持有者`() {
        val held = freshKey(992001)
        assertTrue(ActiveSessionGate.tryAcquire(held))
        ActiveSessionGate.release(freshKey(992002))
        assertEquals(held, ActiveSessionGate.current())
        ActiveSessionGate.release(freshKey(992001))
    }

    @Test
    fun `同一会话重复抢算抢到_同会话重复释放不炸`() {
        val held = freshKey(993001)
        assertTrue(ActiveSessionGate.tryAcquire(held))
        assertTrue(ActiveSessionGate.tryAcquire(freshKey(993001)))
        ActiveSessionGate.release(freshKey(993001))
        ActiveSessionGate.release(freshKey(993001))
        assertNull(ActiveSessionGate.current())
    }
}
