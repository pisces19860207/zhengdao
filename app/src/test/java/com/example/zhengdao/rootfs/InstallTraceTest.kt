// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.rootfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第六十五批 · 安装分阶段计时（[InstallTrace]）的纯 JVM 用例。
 *
 * 不碰 Android、不碰真机：只钉四件事——
 * ① 分段耗时按阶段归并，合计等于各段之和；
 * ② 汇总行必须带包大小/条目数/整包哈希次数（派工单 §1-①、§1-④ 的日志断言）；
 * ③ 异常路径能把还开着的阶段收掉，并标出「失败于 X」；
 * ④ 计时器可注入 —— 测试不靠 sleep，读数可复现。
 */
class InstallTraceTest {

    /** 可手动推进的假时钟（纳秒）。 */
    private class FakeClock {
        var now = 0L
        fun advance(ms: Long) {
            now += ms * 1_000_000L
        }
    }

    private fun trace(clock: FakeClock) = InstallTrace(nanoTime = { clock.now })

    @Test
    fun `分阶段计时按阶段归并且合计等于各段之和`() {
        val clock = FakeClock()
        val t = trace(clock)
        t.begin(InstallTrace.Stage.PREPARE)
        clock.advance(5)
        t.end()
        t.begin(InstallTrace.Stage.EXTRACT)
        clock.advance(1_200)
        t.end("Rust，含整包对账")
        t.begin(InstallTrace.Stage.MARKER)
        clock.advance(2)
        t.end()
        t.begin(InstallTrace.Stage.SWAP)
        clock.advance(7)
        t.end()
        t.begin(InstallTrace.Stage.CLEAN_OLD)
        clock.advance(300)
        t.end("前台")

        assertEquals(5L, t.stageMs(InstallTrace.Stage.PREPARE))
        assertEquals(1_200L, t.stageMs(InstallTrace.Stage.EXTRACT))
        assertEquals(2L, t.stageMs(InstallTrace.Stage.MARKER))
        assertEquals(7L, t.stageMs(InstallTrace.Stage.SWAP))
        assertEquals(300L, t.stageMs(InstallTrace.Stage.CLEAN_OLD))
        // 五个阶段之和 = 1514ms；汇总行里的「合计」必须与之一致
        assertTrue(t.summary(ok = true).contains("合计 1514ms"))
    }

    @Test
    fun `汇总行带包大小 条目数与整包哈希次数`() {
        val clock = FakeClock()
        val t = trace(clock)
        t.archiveBytes = 201_632_517L
        t.entries = 12_345L
        t.bytesWritten = 812_000_000L
        t.hashPasses = 1
        t.preHashPasses = 1
        t.extractionPath = "Rust"
        t.begin(InstallTrace.Stage.EXTRACT)
        clock.advance(2_000)
        t.end("Rust，含整包对账")

        val line = t.summary(ok = true)
        assertTrue("必须有可读包大小：$line", line.contains("192.3MB"))
        assertTrue("必须带原始字节数（对账用）：$line", line.contains("201632517B"))
        assertTrue("必须带条目数：$line", line.contains("条目 12345"))
        assertTrue("必须带写出体积：$line", line.contains("写出 774.4MB"))
        assertTrue(
            "必须把安装内/安装前哈希次数分开（派工单 §1-④ 判据）：$line",
            line.contains("整包哈希 安装内 1 次 · 安装前 1 次"),
        )
        assertTrue("必须标出解包路径：$line", line.contains("解包路径 Rust"))
        assertTrue("未发生的阶段写 —，不能编数：$line", line.contains("下载 —"))
    }

    @Test
    fun `异常路径收掉开着的阶段并标出失败于哪一段`() {
        val clock = FakeClock()
        val t = trace(clock)
        t.begin(InstallTrace.Stage.EXTRACT)
        clock.advance(880)
        val failed = t.abortOpen()

        assertEquals(InstallTrace.Stage.EXTRACT, failed)
        assertEquals(880L, t.stageMs(InstallTrace.Stage.EXTRACT))
        assertNull("收掉之后不能还开着", t.abortOpen())
        val line = t.summary(ok = false, failedAt = failed, reason = "SHA256 不匹配")
        assertTrue("必须标失败阶段：$line", line.contains("失败于解包"))
        assertTrue("必须带原因：$line", line.contains("原因 SHA256 不匹配"))
    }

    @Test
    fun `重复 begin 会把上一段收掉 不丢时间也不串台`() {
        val clock = FakeClock()
        val t = trace(clock)
        t.begin(InstallTrace.Stage.EXTRACT)
        clock.advance(100)
        t.begin(InstallTrace.Stage.MARKER)
        clock.advance(3)
        t.end()

        assertEquals(100L, t.stageMs(InstallTrace.Stage.EXTRACT))
        assertEquals(3L, t.stageMs(InstallTrace.Stage.MARKER))
    }

    @Test
    fun `负耗时被压成 0 不出现负数读数`() {
        val clock = FakeClock()
        val t = trace(clock)
        t.record(InstallTrace.Stage.DOWNLOAD, -50, "伪造输入")
        assertEquals(0L, t.stageMs(InstallTrace.Stage.DOWNLOAD))
        assertTrue(t.summary(ok = true).contains("下载 0ms"))
    }
}
