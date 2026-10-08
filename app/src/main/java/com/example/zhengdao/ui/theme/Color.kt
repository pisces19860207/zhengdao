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
