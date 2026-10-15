// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.oc

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import org.junit.After
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
 * 第㉖刀（顺序 42，E-136）新增：**升级档在 destroyForcibly 之后必须补发
 * `android.os.Process.killProcess(pid)`** —— 取证定案：本机 ART 上 destroyForcibly
 * 对被外部 SIGSTOP 冻成 T 态的 serve 不产生 SIGKILL 效果（pid 正确 / 外部 kill -9
 * 秒杀 / kill -CONT 判别实验，见 E-136 四件证据）。正例用 [FakeFrozenProcess]
 * （destroy/destroyForcibly 都杀不掉的 T 态），负例验证健康路径与 pid 不可得路径
 * 都**不会**发 killProcess。
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

    /**
     * 假「SIGSTOP 冻结进程」（E-136 现实的 JVM 化）：destroy / destroyForcibly 都
     * 杀不掉（T 态，等 CONT 才走），带 `pid()` 方法（反射提取命中，返回 4242）。
     */
    private class FakeFrozenProcess : Process() {
        var destroyCalled = false
        var destroyForciblyCalled = false

        @Suppress("unused") // 供 OcManager.processPidOf 反射调用
        fun pid(): Int = 4242

        override fun destroy() {
            destroyCalled = true // SIGTERM 进共享挂起队列，T 态收不到，不退
        }

        override fun destroyForcibly(): Process {
            destroyForciblyCalled = true // E-136：ART 上不产生 SIGKILL 效果，仍不退
            return this
        }

        override fun waitFor(): Int {
            waitFor(Long.MAX_VALUE, TimeUnit.MILLISECONDS)
            return 0 // 冻结态实际等不到
        }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = false // 冻结态永不退出

        override fun exitValue(): Int = throw IllegalStateException("进程还活着（T 态）")

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

    @After
    fun resetTestHooks() {
        OcManager.killProcessForTest = null
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

    // ── 第㉖刀（顺序 42，E-136）：升级档补发 killProcess ──

    @Test
    fun `升级档 destroyForcibly 后仍存活 —— 必须补发 killProcess 且同见两形态`() {
        val frozen = FakeFrozenProcess()
        val killed = mutableListOf<Int>()
        val logs = mutableListOf<String>()
        OcManager.killProcessForTest = { pid -> killed.add(pid) }

        val escalated = OcManager.shutdownProcess(frozen, waitMs = 500) { logs.add(it) }

        assertTrue("冻结态必须走升级档", escalated)
        assertTrue("destroyForcibly 必须先发出", frozen.destroyForciblyCalled)
        assertEquals(
            "升级档必须补发 android.os.Process.killProcess(pid)（E-136：ART 不给 SIGKILL）",
            listOf(4242), killed,
        )
        assertTrue(
            "形态钉死：同一轮收尾里 destroyForcibly 与 killProcess 必须同时留痕",
            logs.any { it.contains("destroyForcibly") } && logs.any { it.contains("补发 killProcess(pid=4242)") },
        )
        assertTrue("收尾完成应留痕", logs.any { it.contains("强制收尾完成") })
    }

    @Test
    fun `负例_健康退出路径不发 killProcess`() {
        val p = FakeProcess(exitsOnDestroy = true)
        val killed = mutableListOf<Int>()
        OcManager.killProcessForTest = { pid -> killed.add(pid) }

        val escalated = OcManager.shutdownProcess(p, waitMs = 500) { }

        assertFalse(escalated)
        assertFalse("健康路径不许补刀", p.destroyForciblyCalled)
        assertTrue(
            "健康路径绝不发 killProcess（负例自证：钉死补刀只在升级档出现）",
            killed.isEmpty(),
        )
    }

    @Test
    fun `负例_pid 不可得时跳过补刀但不崩`() {
        // FakeProcess 没有 pid() 方法 / pid 字段 ⇒ processPidOf 返回 null
        val p = FakeProcess(exitsOnDestroy = false)
        // 让它模拟「destroyForcibly 也杀不掉」：子类化不了 private 类，改用冻结假进程
        // 但把 pid 提取弄失败 ⇒ 用匿名子类覆盖 pid 反射命中面：
        val frozenNoPid = object : Process() {
            var destroyForciblyCalled = false
            override fun destroy() = Unit
            override fun destroyForcibly(): Process {
                destroyForciblyCalled = true
                return this
            }
            override fun waitFor(): Int {
                waitFor(Long.MAX_VALUE, TimeUnit.MILLISECONDS)
                return 0
            }
            override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = false
            override fun exitValue(): Int = throw IllegalStateException("进程还活着")
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
        val killed = mutableListOf<Int>()
        val logs = mutableListOf<String>()
        OcManager.killProcessForTest = { pid -> killed.add(pid) }

        val escalated = OcManager.shutdownProcess(frozenNoPid, waitMs = 500) { logs.add(it) }

        assertTrue(escalated)
        assertTrue(destroyForciblyCalled(frozenNoPid))
        assertTrue(
            "pid 不可得时跳过补刀（负例自证：不盲发）",
            killed.isEmpty() && logs.any { it.contains("pid 不可得") },
        )
    }

    private fun destroyForciblyCalled(p: Process): Boolean =
        p.javaClass.getDeclaredField("destroyForciblyCalled")
            .apply { isAccessible = true }.get(p) as Boolean
}
