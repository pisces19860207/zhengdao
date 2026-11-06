// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百零七批 · 停机记录落盘壳的 JVM 用例：mark→load 回读、clear→没有、
// 坏文件/没有文件都不猜（判不了就不编）、会话 id 可空（叫停时没记住会话＝全停口径）。
package com.example.zhengdao.baopu.agent

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StopStateStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `mark后load回读_会话id与时刻原样`() {
        val store = StopStateStore(File(tmp.root, "baopu/run-stop-state.json"))
        assertTrue(store.mark(42L, 1_234L))
        assertEquals(StoppedRun(sessionId = 42L, atMs = 1_234L), store.load())
    }

    @Test
    fun `叫停时没有会话_sessionId为null_读回仍是停`() {
        val store = StopStateStore(File(tmp.root, "run-stop-state.json"))
        store.mark(null, 9L)
        assertEquals(StoppedRun(sessionId = null, atMs = 9L), store.load())
    }

    @Test
    fun `clear后load为空_重复clear幂等`() {
        val store = StopStateStore(File(tmp.root, "run-stop-state.json"))
        store.mark(7L, 1L)
        assertTrue(store.clear())
        assertNull(store.load())
        assertTrue("文件已不在 ⇒ clear 仍算成功（幂等）", store.clear())
    }

    @Test
    fun `文件不在_等于没有叫停`() {
        assertNull(StopStateStore(File(tmp.root, "absent/run-stop-state.json")).load())
    }

    @Test
    fun `坏文件与假记录都不猜_按没有叫停算`() {
        val bad = File(tmp.root, "run-stop-state.json")
        bad.writeText("不是json{")
        assertNull("读不动 ⇒ null（不抛不猜）", StopStateStore(bad).load())
        bad.writeText("""{"stopped":false,"sessionId":1,"atMs":2}""")
        assertNull("stopped=false ＝ 解除过 ⇒ null", StopStateStore(bad).load())
    }

    @Test
    fun `mark覆盖写_同一时刻至多一条叫停记录`() {
        val store = StopStateStore(File(tmp.root, "run-stop-state.json"))
        store.mark(1L, 1L)
        store.mark(2L, 3L)
        assertEquals(StoppedRun(sessionId = 2L, atMs = 3L), store.load())
    }
}
