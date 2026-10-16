// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui.theme

import androidx.compose.ui.graphics.Color

// ── iOS 系统色板（Apple HIG 公开常量）——整体浅色、白卡片、系统蓝点缀 ──
//
// 可读性说明（2026-10-08 走查）：系统色板里的"浅"色是给大色块用的，直接拿去写小字
// 会不达 WCAG AA 的 4.5:1。下面对每一个用作小字的色都给了加深变体，规则是
// 「大色块/图标用原色，小字用 Text 变体」。
val IOSBlue = Color(0xFF007AFF)           // systemBlue：主色（按钮/链接/选中态）
// 2026-10-10 加：主蓝小字变体，给"小字 + 蓝"场景用。
// 原 #007AFF 在 14sp bodyMedium 白底仅 4.02:1，略低于 WCAG AA 4.5:1。
// 灰底/低对比场景下用此值：白底 6.95:1 / 页面灰底 #F2F2F7 6.23:1 / 状态卡填充 #E9E9EB 5.73:1 /
// 选中 chip 底 #E1ECFF 5.84:1，全部达 AA。配套 onPrimaryContainer (#003E7E) 用在
// primaryContainer (IOSTintedBlue) 底上的"再深一层"——那一处不靠本常量。
// ⚠️ 2026-10-16（B-补-25）：上一版这里写的是"5.07:1"——那是 #6E6E73 白底的比值，张冠李戴；
// 复算后的四个底见上一行。判据钉在 ui/theme/ContrastGuardTest.kt（从本文件取值当场复算，改色会红）。
val IOSBlueText = Color(0xFF0058B0)
// 2026-10-08 删除主蓝的小字变体（原 #0A63C9）：全库 grep 零引用 ⇒ 死常量。当时加它是
// 给主蓝的小字准备的，实际没有一处小字用主蓝；将来真需要，再按上面的规则补加深值。
val IOSBg = Color(0xFFF2F2F7)             // systemGroupedBackground：页面底色
val IOSCard = Color(0xFFFFFFFF)           // secondarySystemGroupedBackground：卡片
val IOSLabel = Color(0xFF1C1C1E)          // label：主文字
val IOSSecondaryLabel = Color(0xFF6E6E73) // secondaryLabel：次要文字。
                                           // 2026-10-08 由 #8E8E93 加深：原值白底仅 3.26:1、
                                           // 页面灰底 2.92:1，均低于 4.5:1；现值白底 4.6:1。
val IOSSeparator = Color(0xFFC6C6C8)      // separator：描边/分隔
val IOSFill = Color(0xFFE9E9EB)           // systemFill：状态卡/非选中填充
val IOSTintedBlue = Color(0xFFE1ECFF)     // systemBlue 12% 透明叠加：选中 chip 底
val IOSTintedRed = Color(0xFFFFE1DE)      // systemRed 12% 透明叠加：errorContainer 底
val IOSTintedGreen = Color(0xFFDFF5E1)    // systemGreen 12% 透明叠加：tertiaryContainer 底
val IOSRed = Color(0xFFFF3B30)            // systemRed：破坏性操作
val IOSErrorText = Color(0xFFC0392B)      // 错误正文：#FF3B30 白底仅 3.55:1，此值 5.4:1
val IOSGreen = Color(0xFF34C759)          // systemGreen：就绪/成功
val IOSReadyGreen = Color(0xFF2E7D32)     // 就绪绿的小字/图形变体：#34C759 白底仅 2.22:1，此值 5.13:1

// ── 深色变体（2026-10-12 接入深色主题）────────────────────────────────────────
//
// 取值 = Apple HIG 的 dark 系统色；每个「用作小字」的色都验过对比度（对 #1C1C1E 卡片底）。
// 规则同浅色：大色块/图标用系统原色，小字用加深/加亮变体。
val IOSDarkBg = Color(0xFF000000)             // systemGroupedBackground (dark)：纯黑页面底
val IOSDarkCard = Color(0xFF1C1C1E)           // secondarySystemGroupedBackground (dark)
val IOSDarkLabel = Color(0xFFFFFFFF)          // label (dark)：主文字
val IOSDarkSecondaryLabel = Color(0xFF98989F) // secondaryLabel (dark) ≈ #EBEBF5 @60% 叠在 #1C1C1E 上的合成色
                                              // 对比度 5.9:1（对卡片底），达 WCAG AA
val IOSDarkSeparator = Color(0xFF38383A)      // separator (dark)
val IOSDarkFill = Color(0xFF2C2C2E)           // systemFill (dark)
val IOSDarkBlue = Color(0xFF0A84FF)           // systemBlue (dark)：卡片底 4.7:1 / 纯黑底 5.8:1
val IOSDarkBlueText = Color(0xFF64B5F6)       // 蓝色小字（深底）：#0A84FF 在卡片底 4.7:1 偏紧，此值 6.9:1
val IOSDarkRed = Color(0xFFFF453A)            // systemRed (dark)
val IOSDarkErrorText = Color(0xFFFF6B5E)      // 错误正文（深底）：#FF453A 卡片底 4.0:1 不达标，此值 6.1:1
val IOSDarkGreen = Color(0xFF30D158)          // systemGreen (dark)
val IOSDarkReadyGreen = Color(0xFF30D158)     // 就绪绿（深底）：对比 8.4:1
val IOSDarkTintedBlue = Color(0xFF1A3A5C)     // 蓝 18% 叠加于深底：选中 chip / primaryContainer
val IOSDarkTintedRed = Color(0xFF4A1F1C)      // 红 18% 叠加于深底：errorContainer
val IOSDarkTintedGreen = Color(0xFF1B3A22)    // 绿 18% 叠加于深底：tertiaryContainer
val IOSDarkOnTintedBlue = Color(0xFF9FC7FF)   // 深底 primaryContainer 上的文字（6.7:1）
val IOSDarkOnTintedGreen = Color(0xFFB8E6C2)  // 深底 tertiaryContainer 上的文字
val IOSDarkOnTintedRed = Color(0xFFFFC9C2)    // 深底 errorContainer 上的文字
