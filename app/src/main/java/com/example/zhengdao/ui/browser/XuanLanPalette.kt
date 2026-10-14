package com.example.zhengdao.ui.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.example.zhengdao.ui.theme.isZhengdaoDark

/**
 * 玄览（浏览器 Tab）设计令牌 —— v1.1 主稿 + 探索稿 B 收窄数字的代码化。
 *
 * 设计依据：`证道设计报告/证道-玄览UI设计稿-2026-10-14-v1.1.html`（主稿：双段分组）
 * ＋ `…-v1.1-explore.html`（探索稿 B：浮动胶囊形态）＋ 2026-10-14 用户收窄定稿
 * （台账 FEATURE-LEDGER:490：间距 12→6px、navB 64→56px、地址栏 zone 58→50px、合计 ≤106px）。
 *
 * 对比度全部按 WCAG 2.x 相对亮度公式**实算**（python 复算，非引用）：
 * - 浅色（对页底 #F2F2F7 = IOSBg）：可用墨 #38566A = **6.95:1**；主页键雾底 #E7EEF0 上
 *   墨 = **6.61:1**；禁用 #6E6E73 = **4.54:1**；加载红 #C0392B = **4.87:1**（任务书数字逐项复现）；
 * - 深色档为**镜像配对**（设计稿只覆盖浅色，③ 我的补齐）：墨 #E7EEF0 on 胶囊 #2C2C2E
 *   = 11.87:1；on 玄青填充 #38566A = 6.61:1（与浅色雾底同比例）；禁用 #A6A6AB = 5.75:1；
 *   停止红 #FF6F61 = 5.11:1。全部过 WCAG AA 4.5:1。
 *
 * #38566A / #E7EEF0 / #E5E9EA 不在 App 全局色板（Color.kt）里——它们是玄览自己的
 * 设计令牌（设计稿明言"0 新色"指不引第七个色相，这三个值本稿自管）；
 * #007AFF（焦点框）= IOSBlue、#C0392B（加载红）= IOSErrorText，与全局色板同值。
 */
data class XuanLanPalette(
    /** 可用墨：图标/文字前景（浅 6.95:1 / 深 11.87:1）。 */
    val ink: Color,
    /** 禁用墨（浅 4.54:1 / 深 5.75:1）。 */
    val inkDisabled: Color,
    /** 主页键填充：整栏唯一填色键（浅 玄青雾 / 深 玄青沉）。 */
    val homeFill: Color,
    /** 左簇底（历史栈，次要）：浅 = 半透明白；深 = 深卡片。 */
    val clusterSecondaryBg: Color,
    /** 右簇底（当前页，主要）：浅 = 实白；深 = 深卡片。 */
    val clusterPrimaryBg: Color,
    /** 左簇描边（历史栈，次要）：浅 12% 玄青 / 深 分隔线。 */
    val clusterStrokeSecondary: Color,
    /** 右簇描边（当前页，主要）：浅 20% 玄青 / 深 分隔线。 */
    val clusterStrokePrimary: Color,
    /** 中间分隔线 1×20dp（浅 #E5E9EA / 深 #48484A）。 */
    val divider: Color,
    /** 加载中停止键红（浅 4.87:1 / 深 5.11:1）。 */
    val stopRed: Color,
    /** 焦点态外框 2px（#007AFF，深浅同值）。 */
    val focusRing: Color,
    /** 按下态底色（14% 玄青 / 深档 14% 雾白）。 */
    val pressedOverlay: Color,
    /** 标签条底（浅 页底 / 深 页面黑）。 */
    val stripBg: Color,
    /** 标签条描边（底部发丝线）。 */
    val stripStroke: Color,
)

/** 浅色档 = 设计稿原值（像素级照搬）。 */
internal val LightPalette = XuanLanPalette(
    ink = Color(0xFF38566A),
    inkDisabled = Color(0xFF6E6E73),
    homeFill = Color(0xFFE7EEF0),
    clusterSecondaryBg = Color(0xAAFFFFFF), // rgba(255,255,255,.65) 半透明白（主稿左簇）
    clusterPrimaryBg = Color(0xFFFFFFFF),
    clusterStrokeSecondary = Color(0x1F38566A), // 12% 玄青（0x1F=31/255=12.2%）
    clusterStrokePrimary = Color(0x3338566A), // 20% 玄青（0x33=51/255=20.0%）
    divider = Color(0xFFE5E9EA),
    stopRed = Color(0xFFC0392B),
    focusRing = Color(0xFF007AFF),
    pressedOverlay = Color(0x2438566A), // 14% 玄青（:active 反馈）
    stripBg = Color(0xFFF2F2F7),
    stripStroke = Color(0xFFE5E9EA),
)

/** 深色档 = 镜像配对（③ 设计稿未覆盖，按同比例对比度补齐，见类 KDoc 实算值）。 */
internal val DarkPalette = XuanLanPalette(
    ink = Color(0xFFE7EEF0),
    inkDisabled = Color(0xFFA6A6AB),
    homeFill = Color(0xFF38566A), // 深档让玄青沉下去当填充，雾白墨上来
    clusterSecondaryBg = Color(0xFF2C2C2E),
    clusterPrimaryBg = Color(0xFF2C2C2E),
    clusterStrokeSecondary = Color(0xFF48484A),
    clusterStrokePrimary = Color(0xFF48484A),
    divider = Color(0xFF48484A),
    stopRed = Color(0xFFFF6F61),
    focusRing = Color(0xFF007AFF),
    pressedOverlay = Color(0x24E7EEF0),
    stripBg = Color(0xFF1C1C1E),
    stripStroke = Color(0xFF48484A),
)

/**
 * 取当前主题的玄览令牌。深浅判据与 Theme.kt 同源（`colorScheme.background` 实际亮度，
 * 不取 isSystemInDarkTheme——见 Theme.kt:120 的注释）。
 */
@Composable
fun xuanLanPalette(): XuanLanPalette {
    val dark = isZhengdaoDark()
    return remember(dark) { if (dark) DarkPalette else LightPalette }
}
