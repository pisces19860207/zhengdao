// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.terminal

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * §B 顺序 16：`KnowledgeBaseHints` 的行缓冲**真的**会被多个线程同时碰。
 *
 * 证据链：`onUserCodePoint` 从 Termux 引擎／IME 线程进来（本类文档明写「允许在任意线程调用」），
 * 而 `TerminalActivity.onSessionFinished` 调 `reset()` 时**没有**切主线程
 * （`app/src/main/java/com/example/zhengdao/TerminalActivity.kt:886-889`，
 * 直到 `:890` 才 `runOnUiThread` 去弹 Toast）⇒ 两个线程确实会同时进同一个 `StringBuilder`。
 *
 * 这条测试是**护栏**，钉两件事：① 高并发下不抛异常；② 行缓冲始终不超过上限。
 * ⚠️ 说清它的边界：它**不能**证明"修复前一定会炸"（`StringBuilder` 的竞争多数时候只是写坏内容，
 * 不一定抛）。所以它的价值是"锁被谁拆掉就会有人知道"，不是"缺陷复现"。
 */
class KnowledgeBaseHintsBufferTest {

    @Test
    fun `多线程同时喂缓冲 不抛异常 且始终不超上限`() {
        val threads = 8
        val perThread = 3_000
        val errors = ConcurrentLinkedQueue<Throwable>()
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        repeat(threads) { t ->
            pool.execute {
                start.await()
                repeat(perThread) { i ->
                    try {
                        when ((i + t) % 5) {
                            0 -> KnowledgeBaseHints.onUserText("用户粘贴的一段话")
                            1 -> KnowledgeBaseHints.onUserBackspace()
                            2 -> KnowledgeBaseHints.reset()
                            3 -> KnowledgeBaseHints.onUserText("x".repeat(120))
                            else -> KnowledgeBaseHints.debugLineBuffer()
                        }
                    } catch (e: Throwable) {
                        errors.add(e)
                    }
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue("并发喂缓冲超时未结束", pool.awaitTermination(60, TimeUnit.SECONDS))
        assertTrue("并发访问行缓冲抛了异常：${errors.firstOrNull()}", errors.isEmpty())
        val len = KnowledgeBaseHints.debugLineBuffer().length
        assertTrue("行缓冲超上限（应 ≤ 400）：$len", len <= 400)
    }
}
