package com.example.zhengdao.ui.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.example.zhengdao.ui.theme.isZhengdaoDark

/**
 * 玄览（浏览器 Tab）设计令牌 —— 纸墨黛青版（2026-10-22 第一百三十七批）。
 *
 * 设计依据：`抱朴UI设计语言方案-纸墨黛青-2026-10-22.md` §18（11:39 用户裁决
 * 「抱朴、玄览这些都改成这套」）。**只换色，不换形状**：底栏几何、簇状按钮布局、
 * 手势全部保持现状（`BrowserBottomBarGuardTest` 钉的是几何，本批不该让它红）。
 *
 * 13 个槽位逐条对应 §18.1；深色 13 槽按 §16.2 同族取（墨底 #1C1B18 / 卡面 #24231F /
 * 纸字 #EDE9DF / 次字 #A9A399 / 青提亮 #7FA89B / 朱提亮 #D0705A）。
 *
 * 三个「不动」是刻意的，不是漏改：
 * - `clusterSecondaryBg` = `#AAFFFFFF`（65% 半透明白）——半透明属形状语言；
 * - `clusterPrimaryBg` = 实白（深色 = 卡面）——当前页簇本来就是实底；
 * - 两处描边/按压遮罩**只换色相、透明度不动**（12% / 20% / 14%）。
 *
 * 对比度由 `XuanLanPaletteContrastTest` 从色值当场重算（门槛：文字 4.5:1，
 * `focusRing` 作为非文字边界 3:1），本文件不写「已验」结论。
 */
data class XuanLanPalette(
    /** 可用墨：图标/文字前景。 */
    val ink: Color,
    /** 禁用墨。 */
    val inkDisabled: Color,
    /** 主页键填充：整栏唯一填色键。 */
    val homeFill: Color,
    /** 左簇底（历史栈，次要）：浅 = 半透明白；深 = 深卡片。 */
    val clusterSecondaryBg: Color,
    /** 右簇底（当前页，主要）：浅 = 实白；深 = 深卡片。 */
    val clusterPrimaryBg: Color,
    /** 左簇描边（历史栈，次要）：浅 12% 黛青 / 深 发丝线。 */
    val clusterStrokeSecondary: Color,
    /** 右簇描边（当前页，主要）：浅 20% 黛青 / 深 发丝线。 */
    val clusterStrokePrimary: Color,
    /** 中间分隔线 1×20dp（纯装饰）。 */
    val divider: Color,
    /** 加载中停止键红（全站唯一允许的红）。 */
    val stopRed: Color,
    /** 焦点态外框 2px（浅 黛青 / 深 青提亮；非文字边界，3:1 门槛）。 */
    val focusRing: Color,
    /** 按下态底色（14% 黛青 / 深档 14% 纸字）。 */
    val pressedOverlay: Color,
    /** 标签条底（浅 纸 / 深 墨底）。 */
    val stripBg: Color,
    /** 标签条描边（底部发丝线）。 */
    val stripStroke: Color,
)

/** 浅色档＝§18.1 映射表逐行。 */
internal val LightPalette = XuanLanPalette(
    ink = Color(0xFF1F1E1B), // 墨（原 #38566A 玄青：文字走墨，青只留给「可点/进行中」）
    inkDisabled = Color(0xFF57544C), // 次墨（与全站同一档灰）
    homeFill = Color(0xFFE3EDE7), // 浅青（原 #E7EEF0 雾底）
    clusterSecondaryBg = Color(0xAAFFFFFF), // 半透明白：形状语言，不动
    clusterPrimaryBg = Color(0xFFFFFFFF), // 实白：不动
    clusterStrokeSecondary = Color(0x1F2C5F54), // 12% 黛青（只换色相，透明度不动）
    clusterStrokePrimary = Color(0x332C5F54), // 20% 黛青
    divider = Color(0xFFDCD7C9), // 发丝线（原 #E5E9EA）
    stopRed = Color(0xFFA8402B), // 朱砂（原 #C0392B）
    focusRing = Color(0xFF2C5F54), // 黛青（原 #007AFF：本批主目标之一）
    pressedOverlay = Color(0x242C5F54), // 14% 黛青
    stripBg = Color(0xFFF7F4ED), // 纸（原 #F2F2F7 正是 iOS 分组底）
    stripStroke = Color(0xFFDCD7C9), // 发丝线
)

/** 深色档＝§16.2 同族取（不是另一套皮肤）。 */
internal val DarkPalette = XuanLanPalette(
    ink = Color(0xFFEDE9DF), // 纸字
    inkDisabled = Color(0xFFA9A399), // 次字
    homeFill = Color(0xFF2C5F54), // 黛青沉下去当填充（纸字在其上 6.11:1）
    clusterSecondaryBg = Color(0xFF24231F), // 卡面
    clusterPrimaryBg = Color(0xFF24231F),
    clusterStrokeSecondary = Color(0xFF3A372F), // 深发丝线
    clusterStrokePrimary = Color(0xFF3A372F),
    divider = Color(0xFF3A372F),
    stopRed = Color(0xFFD0705A), // 朱提亮
    focusRing = Color(0xFF7FA89B), // 青提亮（黛青在墨底只有 1.9:1，焦点环必须提亮）
    pressedOverlay = Color(0x24EDE9DF), // 14% 纸字
    stripBg = Color(0xFF1C1B18), // 墨底
    stripStroke = Color(0xFF3A372F),
)

/**
 * 取当前主题的玄览令牌。深浅判据与 Theme.kt 同源（`colorScheme.background` 实际亮度，
 * 不取 isSystemInDarkTheme——见 Theme.kt 的注释）。
 */
@Composable
fun xuanLanPalette(): XuanLanPalette {
    val dark = isZhengdaoDark()
    return remember(dark) { if (dark) DarkPalette else LightPalette }
}
