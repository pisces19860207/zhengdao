// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.oc

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-17（第⑯刀 #3）单测：serve 进程收尾四步 —— **「进程已启动但不退出」必须走到
 * destroyForcibly**，且每步留痕。
 *
 * 用注入的假进程伪造两条路径（OcManager.shutdownProcess 的进程参数是注入的）：
 * ① destroy() 后照常退出（健康进程）⇒ 不升级；
 * ② destroy() 后仍不退（卡死的 serve）⇒ 必须 destroyForcibly()（且再等一轮）。
 *
 * 「句柄在 finally 里置空」这一半在 startServe 的超时路径（代码原文
 * `serveProcess = null` 在 shutdownProcess 调用之前），但 startServe 需要 Android
 * Context（getString/filesDir），纯 JVM 单测驱动不了 ⇒ 该半条标「未验（真机/代码审读）」。
 */
class OcProcessShutdownTest {

    /** 假进程：destroy 后按 [exitsOnDestroy] 决定退不退；destroyForcibly 一定杀得掉。 */
    private class FakeProcess(private val exitsOnDestroy: Boolean) : Process() {
        var destroyCalled = false
        var destroyForciblyCalled = false
        var exited = false
        private val alive: Boolean get() = !exited

        override fun destroy() {
            destroyCalled = true
            if (exitsOnDestroy) exited = true
        }

        override fun destroyForcibly(): Process {
            destroyForciblyCalled = true
            exited = true
            return this
        }

        override fun waitFor(): Int {
            while (alive) Thread.sleep(1)
            return 0
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            // 假进程没有真实时钟：destroy 后若该退就当场退；否则视为超时未退
            return !alive
        }

        override fun exitValue(): Int {
            check(!alive) { "进程还活着" }
            return 0
        }

        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(b: Int) = Unit
        }
        override fun getInputStream(): InputStream = object : InputStream() {
            override fun read(): Int = -1
        }
        override fun getErrorStream(): InputStream = object : InputStream() {
            override fun read(): Int = -1
        }
    }

    @Test
    fun `健康进程 destroy 后退出 —— 不升级 Forcibly`() {
        val p = FakeProcess(exitsOnDestroy = true)
        val logs = mutableListOf<String>()
        val escalated = OcManager.shutdownProcess(p, waitMs = 500) { logs.add(it) }

        assertFalse("正常退出的进程不应走 destroyForcibly", escalated)
        assertTrue("destroy() 必须先发出", p.destroyCalled)
        assertFalse("不该走到 destroyForcibly", p.destroyForciblyCalled)
        assertTrue("收尾完成应留痕", logs.any { it.contains("收尾完成") })
    }

    @Test
    fun `卡死进程 destroy 不退 —— 必须升级 destroyForcibly`() {
        val p = FakeProcess(exitsOnDestroy = false)
        val logs = mutableListOf<String>()
        val escalated = OcManager.shutdownProcess(p, waitMs = 500) { logs.add(it) }

        assertTrue("不退的进程必须升级到 destroyForcibly", escalated)
        assertTrue("destroy() 必须先于 destroyForcibly", p.destroyCalled)
        assertTrue("必须调用 destroyForcibly", p.destroyForciblyCalled)
        assertTrue("升级动作应留痕", logs.any { it.contains("destroyForcibly") })
        assertTrue("强制收尾完成应留痕", logs.any { it.contains("强制收尾完成") })
    }
}
