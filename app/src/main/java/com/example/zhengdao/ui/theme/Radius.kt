// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ── 圆角令牌（iOS HIG 语义分层）──
//
// 2026-10-11 加。**不改变任何现有视觉**——把已在代码里裸写的 8 个圆角值
// 收成有名字的令牌，让"这个圆角为什么是这个值"可查、后续新增控件不再随手取值。
//
// ⚠️ 为什么保留 8 档而不是合并成 4 档：
// 审计时我一度以为"8 种圆角＝没收敛＝要压到 4 档"，逐处核对后发现它们
// **各自对应一个明确层级**，强行合并会把卡片和按钮做成同一个弧度，反而
// 丢掉 iOS 那套"层级靠圆角也要分层"的语言。已核对的语义（2026-10-11）：
//
//   | 令牌 | 值 | 语义位 | iOS 依据 |
//   |---|---|---|---|
//   | rSwatch | 4dp | 配色预览小色块 | iOS 极小控件 |
//   | rBadge  | 6dp | 小徽标/计数胶囊 | — |
//   | rChip   | 8dp | 状态条 / 可点列表行 | secondarySystemFill 容器 |
//   | rPill   | 10dp | 选中 chip / 模型标签 | ≈ systemFill 容器 |
//   | rButton | 12dp | 按钮 / 输入框 | iOS 按钮圆角 10~12 |
//   | rDialog | 14dp | 对话框 | iOS HIG alert 圆角 14 |
//   | rCard   | 16dp | 卡片 / SectionCard | secondarySystemGroupedBackground |
//   | rSheet  | 24dp | 大浮层 / 抽屉卡片 | iOS sheet 圆角 24 |
//
// 用法：`shape = RoundedCornerShape(rCard)` 或直接用下面的 shape 便捷 val。
// 迁移不急——只在**新增或改动**某处圆角时顺手换成令牌，不必一次性全仓替换
// （全仓替换会污染 diff、掩盖真实改动）。

/** 4dp：配色预览等极小色块。 */
val rSwatch: Dp = 4.dp

/** 6dp：小徽标 / 计数胶囊。 */
val rBadge: Dp = 6.dp

/** 8dp：状态条、可点列表行。 */
val rChip: Dp = 8.dp

/** 10dp：选中 chip、模型标签。 */
val rPill: Dp = 10.dp

/** 12dp：按钮、输入框。 */
val rButton: Dp = 12.dp

/** 14dp：对话框（Apple HIG alert 圆角）。 */
val rDialog: Dp = 14.dp

/** 16dp：卡片、SectionCard。 */
val rCard: Dp = 16.dp

/** 24dp：大浮层 / 抽屉卡片（iOS sheet）。 */
val rSheet: Dp = 24.dp

// ── shape 便捷别名（需要 RoundedCornerShape 实例时直接用）──

val ShapeSwatch = RoundedCornerShape(rSwatch)
val ShapeBadge = RoundedCornerShape(rBadge)
val ShapeChip = RoundedCornerShape(rChip)
val ShapePill = RoundedCornerShape(rPill)
val ShapeButton = RoundedCornerShape(rButton)
val ShapeDialog = RoundedCornerShape(rDialog)
val ShapeCard = RoundedCornerShape(rCard)
val ShapeSheet = RoundedCornerShape(rSheet)