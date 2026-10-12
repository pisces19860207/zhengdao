// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 粘贴通道（🔴3：主线程写 pty ⇒ 大文本 ANR）的单测。
 *
 * 这个类是"能单测"和"必须有单测"碰在一起的那种：分块、异常、线程切换、会话中途死掉，
 * 每一条都是纯逻辑，不需要真机也不需要 Robolectric。真机上验证不了的是"pty 写到底
 * 会不会真堵"（那要看 `ByteQueue` 的 50 ms 轮询），所以这里只钉"堵住了也不会堵住 UI 线程"。
 */
class TerminalPasteWriterTest {

    /** 同步执行器：让分块/异常逻辑在测试里按确定的顺序跑完。 */
    private val direct = Executor { it.run() }

    private fun utf8Size(text: String) = text.toByteArray(Charsets.UTF_8).size

    // ── 分块 ──

    @Test
    fun `大文本按 4 KiB 分块 且拼起来逐字不变`() {
        val text = "x".repeat(10_000)
        val chunks = mutableListOf<String>()
        var result: Result<Int>? = null

        TerminalPasteWriter(direct).submit(text, { true }, { chunks.add(it) }) { result = it }

        assertEquals(text, chunks.joinToString(""))
        assertEquals(3, chunks.size) // 4096 + 4096 + 1808
        assertTrue(chunks.all { utf8Size(it) <= TerminalPasteWriter.DEFAULT_CHUNK_BYTES })
        assertEquals(Result.success(text.length), result)
    }

    @Test
    fun `中文与 emoji 不会被切成半个码点`() {
        val text = "证道".repeat(2000) + "🦋".repeat(500)
        val chunks = mutableListOf<String>()

        TerminalPasteWriter(direct).submit(text, { true }, { chunks.add(it) }) {}

        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { utf8Size(it) <= TerminalPasteWriter.DEFAULT_CHUNK_BYTES })
        // 每块自己必须还是合法 UTF-8（被切断的代理项编码后会变 ?，round-trip 就不等了）
        assertTrue(chunks.all { String(it.toByteArray(Charsets.UTF_8), Charsets.UTF_8) == it })
    }

    @Test
    fun `分块函数 单块不超预算 且逐字可还原`() {
        val text = "证道a🦋".repeat(500)
        val chunks = TerminalPasteWriter.splitByByteBudget(text, 64)

        assertTrue(chunks.all { utf8Size(it) <= 64 })
        assertEquals(text, chunks.joinToString(""))
    }

    @Test
    fun `分块函数 单个码点超过预算时单独成块而不拆字`() {
        val chunks = TerminalPasteWriter.splitByByteBudget("🦋🦋", 2)

        assertEquals(listOf("🦋", "🦋"), chunks)
    }

    // ── 失败路径 ──

    @Test
    fun `会话中途死掉就停手并报出已写入多少`() {
        val chunks = mutableListOf<String>()
        var alive = 2
        var result: Result<Int>? = null

        TerminalPasteWriter(direct).submit("a".repeat(10_000), { alive-- > 0 }, { chunks.add(it) }) {
            result = it
        }

        assertEquals("第 3 块开工前就该发现会话没了", 2, chunks.size)
        assertTrue(result!!.isFailure)
        val message = result!!.exceptionOrNull()!!.message.orEmpty()
        assertTrue("失败消息要带上进度：$message", message.contains("已写入"))
        assertTrue(message.contains("8192"))
    }

    @Test
    fun `写入抛异常时立刻停手并原样报出`() {
        var calls = 0
        var result: Result<Int>? = null

        TerminalPasteWriter(direct).submit("a".repeat(10_000), { true }, { _ ->
            calls++
            if (calls == 2) throw IOException("pty 已关闭")
        }) { result = it }

        assertEquals(2, calls)
        assertTrue(result!!.exceptionOrNull() is IOException)
    }

    @Test
    fun `执行器已关闭时不抛异常 只回调失败`() {
        val executor = Executors.newSingleThreadExecutor()
        executor.shutdownNow()
        var result: Result<Int>? = null

        TerminalPasteWriter(executor).submit("hello", { true }, {}) { result = it }

        assertTrue(result!!.isFailure)
    }

    @Test
    fun `空文本是空操作`() {
        var wrote = false
        var result: Result<Int>? = null

        TerminalPasteWriter(direct).submit("", { true }, { wrote = true }) { result = it }

        assertFalse("空文本不该碰 pty", wrote)
        assertEquals(0, result!!.getOrNull())
    }

    // ── 线程 ──

    @Test
    fun `写入发生在给定的执行器线程上 而不是调用线程`() {
        val callerThread = Thread.currentThread().name
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "zd-paste-test") }
        try {
            val writeThread = AtomicReference<String>()
            val done = CountDownLatch(1)

            TerminalPasteWriter(executor).submit("hello", { true }, {
                writeThread.set(Thread.currentThread().name)
            }) { done.countDown() }

            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals("zd-paste-test", writeThread.get())
            assertNotEquals(callerThread, writeThread.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `写盘阻塞时 调用线程不被堵住`() {
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "zd-paste-block") }
        try {
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val done = CountDownLatch(1)

            val startedAt = System.nanoTime()
            TerminalPasteWriter(executor).submit("hello", { true }, {
                started.countDown()
                release.await() // 模拟 pty 写把线程堵住
            }) { done.countDown() }
            val submitElapsedMs = (System.nanoTime() - startedAt) / 1_000_000

            assertTrue("submit 不该等写入完成，实际等了 ${submitElapsedMs}ms", submitElapsedMs < 500)
            assertTrue(started.await(5, TimeUnit.SECONDS))
            release.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }
    }
}
