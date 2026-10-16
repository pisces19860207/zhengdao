// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `RssThresholds` 的单测（§B 顺序 19）。
 *
 * 这条待做的原始描述是「两处阈值不一致（3.84×），收敛成同一个常量」。复核后**没有合并**：
 * 两者口径不同（体检卡量本 UID 聚合、通知栏只量会话 shell 一个进程），合并任一边都会坏 ——
 * 体检卡用大值 ⇒ 永不告警；通知栏用小值 ⇒ 每 30 秒一次假警报。
 * ⇒ 改成「两个数、一个家」，并用本测试把「它们必须不同、且各自同源」钉住。
 */
class RssThresholdsTest {

    @Test
    fun `两个阈值都必须留 且通知栏那个必须更大`() {
        assertEquals(800, RssThresholds.HEALTH_CARD_MB)
        assertEquals(3072L, RssThresholds.NOTIFY_MB)
        assertTrue(
            "体检卡口径（本 UID 聚合）必须明显低于通知栏口径（单个 shell 进程）—— " +
                "合并成一个数就会有一边失效，见 RssThresholds 类注释",
            RssThresholds.HEALTH_CARD_MB.toLong() < RssThresholds.NOTIFY_MB,
        )
    }

    @Test
    fun `ResMonitor 引用的是登记处那个常量 不是自己另抄一份`() {
        assertEquals(RssThresholds.HEALTH_CARD_MB, ResMonitor.RSS_WARN_MB)
    }

    @Test
    fun `体检卡阈值必须高于抱朴空闲基线 否则一开抱朴就告警`() {
        // 真机基线（2026-10-07）：抱朴 serve 空闲 RSS 375–468 MB。
        assertTrue(ResMonitor.RSS_WARN_MB > 468)
    }
}
