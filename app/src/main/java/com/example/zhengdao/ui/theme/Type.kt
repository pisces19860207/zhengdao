// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 字号阶梯（B-补-18，2026-10-16）。
 *
 * 台账 §B-补 ④ 原文：
 * `- **B-补-18**（美化第 6 条 / 统一美化方案批次 3）字号没补：ui/theme/Type.kt 仍只 3 档
 *  （bodyLarge 16sp / titleLarge 22sp / labelSmall 11sp），iOS 目标 34 / 17 / 15 / 13 / 10sp
 *  一个都没落`。
 *
 * 病根不是「字号偏小」，是**没有阶梯**：原先只显式覆盖了 `bodyLarge = 16.sp`，
 * `titleLarge` / `labelSmall` 躺在注释块里，其余槽位全是 Material 默认值 ——
 * 于是同一个界面里 12 / 14 / 16 / 22 各来一遍，层级靠「碰巧」而不是靠设计。
 *
 * 本表把 app **实际用到的每一个槽位**写成显式档位（用 `typography.<slot>` 逐文件 grep 过，
 * 2026-10-16 实测用到 bodySmall 98 / bodyMedium 44 / labelSmall 33 / titleMedium 21 /
 * bodyLarge 17 / labelLarge 13 / titleSmall 6 / titleLarge 5 / labelMedium 5 /
 * headlineSmall 2 / displayLarge 1 / displaySmall 1 处）。没用到的槽位（displayMedium、
 * headlineMedium/Large、displaySmall）**保持 Material 默认** —— 不替将来的用法瞎定尺寸。
 *
 * | 用途 | 槽位 | 本刀 | Material 默认 | iOS 对应 |
 * |---|---|---|---|---|
 * | 品牌/首屏大标题 | `displayLarge` | **34sp** | 57sp | largeTitle 34 |
 * | 区块标题 | `headlineSmall` | **22sp** | 24sp | title2 22 |
 * | 区块标题（抽屉/调试页） | `titleLarge` | **22sp** | 22sp | title2 22 |
 * | 列表主标题、收起后的大标题 | `titleMedium` | **17sp** | 16sp | headline 17 |
 * | 正文 | `bodyLarge` | **17sp** | 16sp | body 17 |
 * | 次级正文 | `bodyMedium` | **15sp** | 14sp | subhead 15 |
 * | 小节标题 | `titleSmall` | **15sp** | 14sp | subhead 15 |
 * | 按钮文字 | `labelLarge` | **15sp** | 14sp | subhead 15 |
 * | 脚注、说明 | `bodySmall` | **13sp** | 12sp | footnote 13 |
 * | 小标签 | `labelMedium` | **13sp** | 12sp | footnote 13 |
 * | 最小标记（角标、单位） | `labelSmall` | **10sp** | 11sp | caption 10 |
 *
 * 两条设计取舍：
 * 1. **中文不用 iOS 的原字号，只借它的层级间距** —— iOS 的 17/15/13 是给拉丁字母算的，
 *    汉字同字号视觉更大，所以正文停在 17sp（不再往上加），靠 22 与 34 拉出两级大标题。
 * 2. **`displayLarge` 从 57sp 收到 34sp** —— 首屏品牌标题原先用的是 Material 的展示级字号，
 *    比同一个屏幕里任何文字都大一倍多，平板上像报头；收到 34sp 后与「大标题」这一档对齐，
 *    也给 B-补-17 的「大标题 → 紧凑标题」留出了明确落差（34 → 17）。
 *
 * `lineHeight` 统一按 iOS 的约 1.2× 给（34/41、22/28、17/22、15/20、13/18、10/13），
 * 保证多行中文不挤在一起。
 */
val Typography = Typography(
    // ── 34sp：品牌/首屏大标题（iOS largeTitle）──
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 34.sp,
        lineHeight = 41.sp,
        letterSpacing = 0.sp
    ),
    // ── 22sp：区块标题（iOS title2）──
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    // ── 17sp：正文与列表主标题（iOS headline/body）──
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.2.sp
    ),
    // ── 15sp：次级正文、小节标题、按钮文字（iOS subhead）──
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.2.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    // ── 13sp：脚注、说明、小标签（iOS footnote）──
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp
    ),
    // ── 10sp：最小标记（iOS caption，中文不再往下压）──
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        letterSpacing = 0.2.sp
    )
    // 其余槽位（displayMedium / displaySmall / headlineMedium / headlineLarge）留 Material 默认：
    // 本 app 未使用；将来真要用时再按上表补一档，别现在替将来的用法瞎定尺寸。
)
