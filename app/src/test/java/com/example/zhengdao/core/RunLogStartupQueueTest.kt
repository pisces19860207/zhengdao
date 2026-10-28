// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

package com.example.zhengdao.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch

/**
 * 第二百六十四批 · A-P2-5：`RunLog` 启动只做最小初始化，迁移/归档在后台**串行**，
 * 而启动那几行日志**一行都不许丢**。
 *
 * 判据是行为层，不是源码字符串（源码形状另由 `MainThreadDiskReadGateTest` 钉）：
 * 用真临时目录 + [CountDownLatch] 把善后线程按在起点 ⇒
 * 1. 放行前打的日志**在内存队列里**，盘上那份还是上一轮的原文（没被新行污染）；
 * 2. 放行后按**原时间戳顺序**补写，错误行同时进 `errors.log`；
 * 3. 归档发生在补写**之前**（本轮文件里没有"上一轮"的内容，上一轮进了存档）；
 * 4. 善后被按住的这 100ms 里 `init` + `log` 立刻返回（没有 `runBlocking` 那种"等它跑完"）。
 *
 * 为什么不用 `produceState`/Compose 测：这里要证明的是**落盘顺序**，不是界面。
 */
class RunLogStartupQueueTest {

    private lateinit var root: File
    private lateinit var filesDir: File
    private lateinit var publicLogs: File

    @Before
    fun setUp() {
        root = File.createTempFile("zd264-runlog", "").let { it.delete(); it.mkdirs(); it }
        filesDir = File(root, "files").apply { mkdirs() }
        publicLogs = File(root, "public-logs").apply { mkdirs() }
        // 对象是进程级单例：上一例的 prepared/prepareStarted 会挡住这一例的 init
        RunLog.resetForTest()
    }

    @After
    fun tearDown() {
        RunLog.resetForTest()
        root.deleteRecursively()
    }

    @Test
    fun `善后未跑完时启动日志进内存队列_放行后按原序落盘且上一轮已归档`() {
        val logsDir = RunLog.dirFor(filesDir)
        assertTrue("前置：日志目录建不出来", logsDir.mkdirs() || logsDir.isDirectory)
        // 上一轮那份：如果它没被先归档，补写的新行会被当成上一轮一起卷走（顺序事故）
        File(logsDir, "zhengdao-log.txt").writeText("上一轮日志\n", Charsets.UTF_8)
        // 公共区老日志：迁移是"复制"，源文件必须原样留着
        val pubPrev = File(publicLogs, "zhengdao-log.prev.txt")
        pubPrev.writeText("公共区老日志\n", Charsets.UTF_8)

        val gate = CountDownLatch(1)
        val adoptThreads = Collections.synchronizedList(mutableListOf<String>())
        RunLog.initForTest(filesDir, publicLogs, gate) { _: File, legacy: File ->
            adoptThreads += Thread.currentThread().name
            assertTrue("搬家的源是 files/zhengdao-log.txt", legacy.absolutePath.endsWith("files${File.separator}zhengdao-log.txt"))
            true
        }

        RunLog.log("启动自检：第一行")
        RunLog.log("启动自检：第二行")

        val currentPath = File(logsDir, "zhengdao-log.txt")
        assertFalse(
            "善后还按在门口时，新行不许落盘（落了就会在归档时被卷走）",
            currentPath.readText(Charsets.UTF_8).contains("启动自检"),
        )

        gate.countDown()
        assertTrue("善后没在超时内跑完（迁移/归档/补写卡住了？）", RunLog.awaitPrepared())

        val current = currentPath.readText(Charsets.UTF_8)
        assertTrue("第一行在：$current", current.contains("启动自检：第一行"))
        assertTrue("第二行在：$current", current.contains("启动自检：第二行"))
        assertTrue("顺序是原先打日志的顺序", current.indexOf("第一行") < current.indexOf("第二行"))
        assertFalse("上一轮的内容不许混进本轮文件（归档必须先于补写）", current.contains("上一轮日志"))

        val archives = logsDir.listFiles { f -> f.isFile && RunLog.isArchiveName(f.name) }.orEmpty()
        assertEquals("上一轮整份归档了一份", 1, archives.size)
        assertEquals("归档里就是上一轮原文", "上一轮日志\n", archives.single().readText(Charsets.UTF_8))

        assertEquals(
            "公共区老日志复制进私有区（源文件不动）",
            "公共区老日志\n",
            File(logsDir, "zhengdao-log.prev.txt").readText(Charsets.UTF_8),
        )
        assertEquals("公共区那份原样还在", "公共区老日志\n", pubPrev.readText(Charsets.UTF_8))
        assertTrue(
            "搬家发生在后台善后线程（Application.onCreate 主线程不碰盘）",
            adoptThreads.isNotEmpty() && adoptThreads.all { it == "zhengdao-runlog-prepare" },
        )
    }

    @Test
    fun `善后期间的错误行也进错误汇总_不许因为换线程漏记账`() {
        val gate = CountDownLatch(1)
        RunLog.initForTest(filesDir, publicLogs, gate)

        RunLog.log("启动自检：安装包签名读不到（失败）")

        gate.countDown()
        assertTrue("善后没在超时内跑完", RunLog.awaitPrepared())

        val logsDir = RunLog.dirFor(filesDir)
        val current = File(logsDir, "zhengdao-log.txt").readText(Charsets.UTF_8)
        assertTrue("本轮日志有这一行：$current", current.contains("启动自检"))
        val errors = File(logsDir, "errors.log")
        assertTrue("errors.log 建出来了", errors.isFile)
        assertTrue(
            "错误行补写时也走 errors.log（补写与实时写同一条路）",
            errors.readText(Charsets.UTF_8).contains("启动自检"),
        )
    }

    @Test
    fun `善后被按住时_init与log立刻返回_没有runBlocking等它`() {
        val gate = CountDownLatch(1)
        val started = System.nanoTime()
        RunLog.initForTest(filesDir, publicLogs, gate)
        RunLog.log("启动自检：这是首帧之前那一行")
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertTrue(
            "init+log 必须 ≤100ms（按住善后时也不许等；runBlocking 会在这里超时）：实耗 ${elapsedMs}ms",
            elapsedMs <= 100,
        )
        assertFalse("善后没放行 ⇒ 还没落盘", File(RunLog.dirFor(filesDir), "zhengdao-log.txt").exists())

        gate.countDown()
        assertTrue("放行后要能补上", RunLog.awaitPrepared())
        assertTrue(
            "补写后那一行在文件里",
            File(RunLog.dirFor(filesDir), "zhengdao-log.txt").readText(Charsets.UTF_8)
                .contains("这是首帧之前那一行"),
        )
    }
}
