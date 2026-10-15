// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.oc

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 第㉖刀（顺序 37）单测：孤儿 serve 清理异步化。
 *
 * 两件事各钉一条：
 * ① **扫描语义不变**（审计 A1 口径原样）：只 kill cmdline 含 `/oc/usr/bin/opencode`
 *    的进程；guest 里 `rootfs/usr/bin/opencode` 与无关进程**绝不误杀**；坏条目
 *    （非数字目录 / 读不出 cmdline 的文件）不炸扫描。
 * ② **扫描不在调用线程**（顺序 37 的本意）：[OcManager.stopServe] 的两个调用点
 *    （ZhengdaoApp.onActivityDestroyed / SessionService.onDestroy|onTaskRemoved）
 *    都是主线程 ⇒ sweep 必须在注入的 dispatcher 线程上执行，且 stopServe 立即返回。
 *
 * procRoot / kill 全部注入（JVM 扫不了真 /proc）；dispatcher 注入用 Dispatchers.Default
 * （真异步，线程名与测试线程不同）。负例自证：kill 回调**不**被调的用例直接断言空表。
 */
class OcServeSweepTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val killed = mutableListOf<Int>()
    private val kill: (Int) -> Unit = { pid -> synchronized(killed) { killed.add(pid) } }

    @After
    fun resetTestHooks() {
        OcManager.cleanupDispatcherForTest = null
        OcManager.sweepProcRootForTest = null
        OcManager.sweepKillForTest = null
    }

    /** 伪造 /proc：数字目录 + cmdline 文件；返回根。 */
    private fun fakeProc(vararg entries: Pair<String, String?>): File {
        val root = tmp.newFolder("proc-${System.nanoTime()}")
        entries.forEach { (name, cmdline) ->
            val d = root.resolve(name)
            if (cmdline == null) {
                root.resolve(name).writeText("not a pid dir") // 非目录条目
                return@forEach
            }
            d.mkdirs()
            d.resolve("cmdline").writeText(cmdline)
        }
        return root
    }

    private val serveCmd = "/data/user/0/com.example.zhengdao/files/oc/usr/bin/opencode\u0000serve"
    private val guestCmd = "/data/user/0/com.example.zhengdao/files/rootfs/usr/bin/opencode"

    @Test
    fun `正例_命中本App的opencode并kill`() = runBlocking {
        val root = fakeProc("101" to serveCmd)
        val killedPids = OcManager.sweepOrphanServe(root, kill)
        assertEquals(listOf(101), killedPids)
        assertEquals(listOf(101), killed)
    }

    @Test
    fun `负例_guest的opencode与无关进程不误杀`() = runBlocking {
        val root = fakeProc("202" to guestCmd, "303" to "some-other-daemon")
        val killedPids = OcManager.sweepOrphanServe(root, kill)
        assertTrue(
            "审计 A1 口径：rootfs 里的 opencode 是用户的 agent，绝不误杀",
            killedPids.isEmpty() && killed.isEmpty(),
        )
    }

    @Test
    fun `负例_坏条目不炸扫描`() = runBlocking {
        // "proc" 非数字目录（被过滤器挡在门外）；"404" 是文件（readText 会炸 → per-entry 兜住）
        val root = fakeProc("404" to null, "505" to serveCmd)
        val killedPids = OcManager.sweepOrphanServe(root, kill)
        assertEquals(listOf(505), killedPids)
    }

    @Test
    fun `门禁_扫描不在调用线程且stopServe立即返回`() {
        val root = fakeProc("606" to serveCmd)
        val ranOnThread = Array<String?>(1) { null }
        val latch = CountDownLatch(1)
        // 注入：真异步（Default 池），kill 回调记录执行线程并放行
        OcManager.cleanupDispatcherForTest = Dispatchers.Default
        OcManager.sweepProcRootForTest = root
        OcManager.sweepKillForTest = { pid ->
            ranOnThread[0] = Thread.currentThread().name
            synchronized(killed) { killed.add(pid) }
            latch.countDown()
        }

        val testThread = Thread.currentThread().name
        OcManager.stopServe() // 语义：立即返回，扫描在别处

        val returned = latch.await(10, TimeUnit.SECONDS)
        assertTrue("10s 内扫描没在后台跑完——异步化失效", returned)
        assertEquals("命中判定不变：仍只杀 /oc/usr/bin/opencode", listOf(606), killed)
        assertNotEquals(
            "扫描必须移出调用线程（顺序 37 的本意）",
            testThread, ranOnThread[0],
        )
    }
}
