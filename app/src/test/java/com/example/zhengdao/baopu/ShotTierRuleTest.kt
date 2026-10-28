// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：262 批 §6 三级截图链路（2026-10-28 追加）的判定纯函数。零 Android，JVM 直测；
// 「哪一档出卡、哪一档免打扰」的矩阵在这里钉死。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShotTierRuleTest {

    // ── T1 判定矩阵：2×2 → 三档，一档不差 ─────────────────────────────────

    @Test
    fun `T1_判定矩阵_三档全覆盖`() {
        // ① 渲染过 ⇒ 直接画（免征询、免切屏——263 实测：前台渲染过的页切后台画不失真）。
        assertEquals(
            ShotTier.PAINT_OFFSCREEN,
            ShotTierRule.decide(pageRenderable = true, appForeground = true),
        )
        assertEquals(
            "渲染过的页就算 App 在后台也直画（成败看「载入时」，不是「截图时」）",
            ShotTier.PAINT_OFFSCREEN,
            ShotTierRule.decide(pageRenderable = true, appForeground = false),
        )
        // ② 没渲染过、App 在前台 ⇒ 无感载入（1dp 挂窗，不出卡）。
        assertEquals(
            ShotTier.LOAD_THEN_PAINT,
            ShotTierRule.decide(pageRenderable = false, appForeground = true),
        )
        // ③ 没渲染过、App 在后台 ⇒ 才出征询卡（唯一的打扰档）。
        assertEquals(
            ShotTier.ASK_TO_FOREGROUND,
            ShotTierRule.decide(pageRenderable = false, appForeground = false),
        )
    }

    // ── T2 载入就绪判据：半成品与拿不到都算没好 ────────────────────────────

    @Test
    fun `T2_就绪判据_只有complete算好`() {
        assertTrue(ShotTierRule.isReadyState("complete"))
        for (notReady in listOf(null, "", "loading", "interactive")) {
            assertFalse(
                "readyState=$notReady 不是「载入完了」——半成品就画与没载入就画是同一类错",
                ShotTierRule.isReadyState(notReady),
            )
        }
    }
}
