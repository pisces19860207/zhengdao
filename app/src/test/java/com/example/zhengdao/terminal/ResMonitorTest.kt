package com.example.zhengdao.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 资源监控的判定逻辑（v1.2 阶段 3.2）。只测纯函数 [ResMonitor.verdict] ——
 * 采样部分依赖 /proc，留给真机验收。
 *
 * 锁住的关键约定：
 * 1. **无进程可采不算故障**（太极/终端都没开时占用本来就是 0）。报红会违反体检卡
 *    「红 = 修得了」的不变量——内存/CPU 没有一键修复。
 * 2. 超阈值走 **warn（黄色 ⚠）**，不是 ok=false。
 * 3. 阈值取自真机基线（serve 空闲 RSS 375–468 MB / CPU 5.6–7.4%），
 *    须明显高于基线，否则一开太极就告警。
 */
class ResMonitorTest {

    @Test
    fun `无进程可采不算故障`() {
        val v = ResMonitor.verdict(null)
        assertTrue(v.ok)
        assertFalse(v.warn)
        assertTrue(v.detail.contains("未运行"))
    }

    @Test
    fun `正常占用不告警`() {
        val v = ResMonitor.verdict(ResMonitor.Sample(rssMb = 400, cpuPct = 7.0f, procs = 3))
        assertTrue(v.ok)
        assertFalse("基线内的占用不该告警", v.warn)
        assertTrue(v.detail.contains("RSS 400 MB"))
        assertTrue(v.detail.contains("CPU 7.0%"))
    }

    @Test
    fun `内存超阈值告警`() {
        val v = ResMonitor.verdict(ResMonitor.Sample(rssMb = 900, cpuPct = 5f, procs = 4))
        assertTrue(v.ok)
        assertTrue(v.warn)
        assertTrue(v.detail.startsWith("⚠"))
        assertTrue(v.detail.contains("900 MB"))
    }

    @Test
    fun `CPU 超阈值告警`() {
        val v = ResMonitor.verdict(ResMonitor.Sample(rssMb = 400, cpuPct = 30f, procs = 2))
        assertTrue(v.ok)
        assertTrue(v.warn)
        assertTrue(v.detail.startsWith("⚠"))
        assertTrue(v.detail.contains("30.0%"))
    }

    @Test
    fun `阈值边界取等号`() {
        val atRss = ResMonitor.verdict(
            ResMonitor.Sample(rssMb = ResMonitor.RSS_WARN_MB, cpuPct = 0f, procs = 1)
        )
        assertTrue("等于阈值应告警", atRss.warn)

        val belowRss = ResMonitor.verdict(
            ResMonitor.Sample(rssMb = ResMonitor.RSS_WARN_MB - 1, cpuPct = 0f, procs = 1)
        )
        assertFalse("低于阈值不该告警", belowRss.warn)

        val atCpu = ResMonitor.verdict(
            ResMonitor.Sample(rssMb = 0, cpuPct = ResMonitor.CPU_WARN_PCT, procs = 1)
        )
        assertTrue("等于阈值应告警", atCpu.warn)
    }

    @Test
    fun `阈值明显高于真机基线`() {
        // 真机基线（2026-10-07）：serve 空闲 RSS 375–468 MB、CPU 5.6–7.4%
        assertTrue(
            "RSS 阈值须高于基线峰值 468 MB，否则一开太极就告警（狼来了）",
            ResMonitor.RSS_WARN_MB > 468,
        )
        assertTrue(
            "CPU 阈值须高于基线峰值 7.4%",
            ResMonitor.CPU_WARN_PCT > 7.4f,
        )
    }

    @Test
    fun `聚合必须剔除 App 自身进程`() {
        // 真机实测（2026-10-07）：只跑 App 时 top 显示 0.0%，但体检卡稳定报 80–90%
        // —— 采样窗口正好覆盖 inspect() 自己的文件扫描与 UI 重排。不剔除 ⇒ 阈值形同虚设。
        val kept = ResMonitor.excludeSelf(listOf(100, 200, 300), selfPid = 200)
        assertEquals(listOf(100, 300), kept)
    }

    @Test
    fun `只剩 App 自身时不产生采样`() {
        val kept = ResMonitor.excludeSelf(listOf(200), selfPid = 200)
        assertTrue("没有子进程可采时应为空，走「未运行」结论", kept.isEmpty())
    }

    @Test
    fun `内存与 CPU 同时超阈值时优先报内存`() {
        val v = ResMonitor.verdict(
            ResMonitor.Sample(rssMb = ResMonitor.RSS_WARN_MB, cpuPct = ResMonitor.CPU_WARN_PCT, procs = 2)
        )
        assertTrue(v.warn)
        assertTrue("同时超阈值时先说内存（更可能导致 OOM/发热）", v.detail.contains("内存"))
    }
}
