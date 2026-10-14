package com.example.zhengdao.rootfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P2-14（issue #17）：下载重试无退避的回归。
 *
 * 改动前（台账原文）：`rootfs/RootfsDownloader.kt:32 MAX_ATTEMPTS_PER_URL=3`、
 * `:112`（现行 :173 的重试循环）无退避——同一 URL 失败后立即重试，3 连击在几百毫秒内
 * 打完，对端正在抖时等于帮着砸，且第 2、3 次几乎必然同样失败。
 *
 * 本测试钉：① 退避曲线（纯函数）；② 睡眠真的接进了重试循环（门禁）。
 */
class RootfsDownloaderBackoffTest {

    @Test
    fun `退避曲线_首次重试等基数`() {
        assertEquals(800L, RootfsDownloader.backoffDelayMs(0))
    }

    @Test
    fun `退避曲线_逐次翻倍`() {
        assertEquals(800L, RootfsDownloader.backoffDelayMs(0))
        assertEquals(1_600L, RootfsDownloader.backoffDelayMs(1))
        assertEquals(3_200L, RootfsDownloader.backoffDelayMs(2))
    }

    @Test
    fun `退避曲线_翻到封顶 8 秒`() {
        // 800 → 1600 → 3200 → 6400 → 12800 被 8000 帽截断
        assertEquals(6_400L, RootfsDownloader.backoffDelayMs(3))
        assertEquals(8_000L, RootfsDownloader.backoffDelayMs(4))
        assertEquals(8_000L, RootfsDownloader.backoffDelayMs(10))
        assertEquals(8_000L, RootfsDownloader.backoffDelayMs(100))
    }

    @Test
    fun `负索引不睡`() {
        assertEquals(0L, RootfsDownloader.backoffDelayMs(-1))
    }

    @Test
    fun `门禁_重试循环接了退避睡眠`() {
        val src = File("src/main/java/com/example/zhengdao/rootfs/RootfsDownloader.kt").readText()
        val loop = src.substringAfter("for (attempt in 0 until MAX_ATTEMPTS_PER_URL)")
            .substringBefore("dest.delete()")
        assertTrue(
            "同一 URL 的重试必须退避（P2-14）",
            loop.contains("backoffDelayMs(attempt - 1)") && loop.contains("Thread.sleep(waitMs)"),
        )
        assertTrue(
            "首次尝试不能睡（attempt > 0 才退避）",
            loop.contains("if (attempt > 0)"),
        )
    }
}
