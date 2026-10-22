// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.ui.browser

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 玄览底栏配色对比度守卫 —— 把设计稿的 WCAG 实算值钉进测试。
 *
 * 2026-10-22 第一百三十七批（设计语言 §18）：玄览纳入纸墨黛青，**只换色不换形状**。
 * 本测随之改判据（先改判据再换色）：
 * - 页底 #F2F2F7 → 纸 #F7F4ED；墨 #38566A → 墨一 #1F1E1B；禁用 #6E6E73 → 次墨 #57544C；
 * - 焦点框 #007AFF → 浅黛青 #2C5F54 / 深青提亮 #7FA89B（深色**必须**提亮：黛青在墨底只有 1.9:1，
 *   焦点环就消失了）；分隔线 #E5E9EA → 发丝线 #DCD7C9。
 *
 * §18.2 要求的四组（`ink` / `inkDisabled` / `stopRed` / `focusRing`）全部覆盖；
 * `focusRing` 是非文字边界，门槛 3:1，其余 4.5:1。任何人改 [XuanLanPalette] 的任一个色值
 * 导致掉下门槛，本测试当场红。
 */
class XuanLanPaletteContrastTest {

    private fun contrast(fg: Color, bg: Color): Double {
        val a = fg.luminance()
        val b = bg.luminance()
        val hi = maxOf(a, b)
        val lo = minOf(a, b)
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun assertAA(name: String, ratio: Double, min: Double = 4.5) {
        assertTrue("$name = ${"%.2f".format(ratio)}:1，低于门槛 $min:1", ratio >= min)
    }

    @Test
    fun `浅色档_墨与各底全部过AA`() {
        assertAA("墨 on 纸（页底）", contrast(LightPalette.ink, LightPalette.stripBg))
        assertAA("墨 on 实白簇", contrast(LightPalette.ink, LightPalette.clusterPrimaryBg))
        assertAA("墨 on 主页键浅青底", contrast(LightPalette.ink, LightPalette.homeFill))
        assertAA("禁用 on 纸", contrast(LightPalette.inkDisabled, LightPalette.stripBg))
        assertAA("禁用 on 实白簇", contrast(LightPalette.inkDisabled, LightPalette.clusterPrimaryBg))
        assertAA("停止红 on 实白簇", contrast(LightPalette.stopRed, LightPalette.clusterPrimaryBg))
        assertAA("停止红 on 纸", contrast(LightPalette.stopRed, LightPalette.stripBg))
    }

    @Test
    fun `深色档_墨与各底全部过AA`() {
        assertAA("墨 on 深胶囊", contrast(DarkPalette.ink, DarkPalette.clusterPrimaryBg))
        assertAA("墨 on 黛青填充", contrast(DarkPalette.ink, DarkPalette.homeFill))
        assertAA("禁用 on 深胶囊", contrast(DarkPalette.inkDisabled, DarkPalette.clusterPrimaryBg))
        assertAA("停止红 on 深胶囊", contrast(DarkPalette.stopRed, DarkPalette.clusterPrimaryBg))
    }

    @Test
    fun `焦点框_浅黛青_深青提亮_都过非文字边界三比一`() {
        assertTrue(
            "浅色焦点框必须是黛青 #2C5F54（原 #007AFF 是 iOS 系统蓝）",
            LightPalette.focusRing == Color(0xFF2C5F54),
        )
        assertTrue(
            "深色焦点框必须是青提亮 #7FA89B（黛青在墨底只有 1.9:1，环会看不见）",
            DarkPalette.focusRing == Color(0xFF7FA89B),
        )
        assertAA("浅色焦点环 on 纸（非文字 3:1）", contrast(LightPalette.focusRing, LightPalette.stripBg), 3.0)
        assertAA("深色焦点环 on 墨底（非文字 3:1）", contrast(DarkPalette.focusRing, DarkPalette.stripBg), 3.0)
    }

    @Test
    fun `分隔线是发丝线`() {
        assertTrue(
            "浅色分隔线必须是发丝线 #DCD7C9（纯装饰，不承担识别）",
            LightPalette.divider == Color(0xFFDCD7C9),
        )
        assertTrue(
            "深色分隔线必须是深发丝线 #3A372F",
            DarkPalette.divider == Color(0xFF3A372F),
        )
    }

    @Test
    fun `没有一处仍是 iOS 蓝或分组底`() {
        val iosBlue = Color(0xFF007AFF)
        val iosGrouped = Color(0xFFF2F2F7)
        listOf("浅色" to LightPalette, "深色" to DarkPalette).forEach { (tag, p) ->
            listOf(
                "ink" to p.ink, "inkDisabled" to p.inkDisabled, "homeFill" to p.homeFill,
                "clusterPrimaryBg" to p.clusterPrimaryBg, "clusterSecondaryBg" to p.clusterSecondaryBg,
                "divider" to p.divider, "stopRed" to p.stopRed, "focusRing" to p.focusRing,
                "stripBg" to p.stripBg, "stripStroke" to p.stripStroke,
            ).forEach { (name, c) ->
                assertTrue("$tag $name 仍是 iOS 系统蓝 #007AFF", c != iosBlue)
                assertTrue("$tag $name 仍是 iOS 分组底 #F2F2F7", c != iosGrouped)
            }
        }
    }
}
