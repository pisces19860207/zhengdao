// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.rootfs

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第六十五批 · 解包进度（[InstallPulse]）的纯 JVM 用例。
 *
 * 钉住派工单 §1-② 的口径：「阶段名 + 已解条目数 + 已用秒」，**拿不到字节偏移就不编百分比**；
 * 同时钉住节流与换道（Rust → Java）清零，避免真机上一秒几十帧或数字虚高。
 */
class InstallPulseTest {

    private class FakeClock {
        var now = 0L
        fun advance(ms: Long) {
            now += ms * 1_000_000L
        }
    }

    @After
    fun tearDown() {
        InstallPulse.stop()
        InstallPulse.onTick = null
        InstallPulse.nanoTime = System::nanoTime
    }

    @Test
    fun `进度行给出阶段名 条目数与已用秒 不编百分比`() {
        val line = InstallPulse.line(
            InstallPulse.Snapshot(entries = 12_345L, elapsedMs = 2_400L, currentName = "usr/lib/python3.11/os.py")
        )
        assertTrue("必须有阶段名：$line", line.contains("正在解包系统层"))
        assertTrue("必须有已解条目数：$line", line.contains("已解 12345 条"))
        assertTrue("必须有已用秒（向下取整）：$line", line.contains("已用 2 秒"))
        assertTrue("带上当前条目名（证明在动）：$line", line.contains("os.py"))
        assertFalse("拿不到字节偏移时不许编百分比：$line", line.contains("%"))
    }

    @Test
    fun `节流一秒最多一帧 第一帧立刻回`() {
        val clock = FakeClock()
        InstallPulse.nanoTime = { clock.now }
        val ticks = mutableListOf<InstallPulse.Snapshot>()
        InstallPulse.onTick = { ticks += it }

        InstallPulse.start()
        InstallPulse.fromRust("200", "a")
        assertEquals("第一帧不该被节流吞掉", 1, ticks.size)
        assertEquals(200L, ticks[0].entries)

        clock.advance(200)
        InstallPulse.fromRust("400", "b")
        assertEquals("200ms 内的第二帧必须被节流", 1, ticks.size)

        clock.advance(800)
        InstallPulse.fromRust("600", "c")
        assertEquals("满 1 秒后放行", 2, ticks.size)
        assertEquals("帧里带最新累计条目数", 600L, ticks[1].entries)
    }

    @Test
    fun `Java 回退逐条计数 换道时 reset 不让数字虚高`() {
        val clock = FakeClock()
        InstallPulse.nanoTime = { clock.now }
        var last: InstallPulse.Snapshot? = null
        InstallPulse.onTick = { last = it }

        InstallPulse.start()
        InstallPulse.noteEntry("etc/os-release")
        assertEquals(1L, last?.entries)
        clock.advance(1_000)
        InstallPulse.noteEntry("usr/bin/sh")
        assertEquals(2L, last?.entries)

        // Rust 试错时报了 5000 条后失败 → Java 从 0 重新数
        InstallPulse.reset()
        InstallPulse.noteEntry("etc/passwd")
        assertEquals("换道后必须从 1 开始，不能接在 5000 后面", 1L, last?.entries)
    }

    @Test
    fun `stop 之后回调不再进来`() {
        val clock = FakeClock()
        InstallPulse.nanoTime = { clock.now }
        val ticks = mutableListOf<InstallPulse.Snapshot>()
        InstallPulse.onTick = { ticks += it }
        InstallPulse.start()
        InstallPulse.fromRust("200", "a")
        InstallPulse.stop()
        clock.advance(2_000)
        InstallPulse.fromRust("400", "b")
        InstallPulse.noteEntry("c")
        assertEquals("stop 之后不许再刷 UI", 1, ticks.size)
    }
}
