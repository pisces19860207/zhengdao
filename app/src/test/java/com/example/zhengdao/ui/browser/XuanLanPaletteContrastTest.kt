// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.ui.browser

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 玄览底栏配色对比度守卫（第⑨刀 C）——把设计稿的 WCAG 实算值钉进测试。
 *
 * 设计稿数字（任务书收窄定稿，python 复算，②）：
 * 浅色对页底 #F2F2F7：可用墨 #38566A = 6.95:1、主页键雾底 #E7EEF0 上墨 = 6.61:1、
 * 禁用 #6E6E73 = 4.54:1、加载红 #C0392B = 4.87:1；
 * 深色档为镜像配对（③ 设计稿未覆盖）：墨 #E7EEF0 on 深胶囊 #2C2C2E = 11.87:1、
 * on 玄青填充 #38566A = 6.61:1、禁用 #A6A6AB = 5.75:1、停止红 #FF6F61 = 5.11:1。
 *
 * 任何人改 [XuanLanPalette] 的任一个色值导致掉下 WCAG AA（正文 4.5:1），本测试当场红。
 * 亮度用 Compose 自带的 [luminance]（= WCAG 2.x 相对亮度公式）。
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
        assertTrue("$name = ${"%.2f".format(ratio)}:1，低于 AA $min:1", ratio >= min)
    }

    @Test
    fun `浅色档_墨与各底全部过 AA`() {
        assertAA("墨 on 页底（设计稿 6.95）", contrast(LightPalette.ink, LightPalette.stripBg))
        assertAA("墨 on 实白簇（设计稿 7.76）", contrast(LightPalette.ink, LightPalette.clusterPrimaryBg))
        assertAA("墨 on 主页键雾底（设计稿 6.61）", contrast(LightPalette.ink, LightPalette.homeFill))
        assertAA("禁用 on 页底（设计稿 4.54）", contrast(LightPalette.inkDisabled, LightPalette.stripBg))
        assertAA("禁用 on 实白簇（设计稿 5.07）", contrast(LightPalette.inkDisabled, LightPalette.clusterPrimaryBg))
        assertAA("停止红 on 实白簇（设计稿 5.44）", contrast(LightPalette.stopRed, LightPalette.clusterPrimaryBg))
        assertAA("停止红 on 页底（设计稿 4.87）", contrast(LightPalette.stopRed, LightPalette.stripBg))
    }

    @Test
    fun `深色档_墨与各底全部过 AA`() {
        assertAA("墨 on 深胶囊（设计稿 11.87）", contrast(DarkPalette.ink, DarkPalette.clusterPrimaryBg))
        assertAA("墨 on 玄青填充（设计稿 6.61）", contrast(DarkPalette.ink, DarkPalette.homeFill))
        assertAA("禁用 on 深胶囊（设计稿 5.75）", contrast(DarkPalette.inkDisabled, DarkPalette.clusterPrimaryBg))
        assertAA("停止红 on 深胶囊（设计稿 5.11）", contrast(DarkPalette.stopRed, DarkPalette.clusterPrimaryBg))
    }

    @Test
    fun `焦点框深浅同值_007AFF`() {
        assertTrue(
            "焦点框必须是 #007AFF（IOSBlue，任务书指定）",
            LightPalette.focusRing == Color(0xFF007AFF) && DarkPalette.focusRing == Color(0xFF007AFF)
        )
    }

    @Test
    fun `分隔线是任务书指定的 E5E9EA`() {
        assertTrue(
            "浅色分隔线必须是 #E5E9EA（任务书：1×20px #E5E9EA）",
            LightPalette.divider == Color(0xFFE5E9EA)
        )
    }
}
