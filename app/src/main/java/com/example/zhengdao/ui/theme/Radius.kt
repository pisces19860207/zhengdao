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
//
// ── 2026-10-20 第七十批（UI 第六批）口径补充：加了一道门禁，也钉了两套例外 ──
//
// 上面那句「迁移不急」只对了一半：**一次性全仓替换**确实不做（污染 diff、掩盖真实改动），
// 但「新增时随手写数字」不能再放任——实测口径：这 8 个令牌定义到 2026-10-20 **全仓零引用**，
// 而 `ui/` 下裸写圆角 43 处（数字字面量 41 处 ＋ 走常量 2 处）。
//
// 新闸：`python tools/check-radius-tokens.py`（白名单：`tools/radius-whitelist.txt`）
//   · 8 档值裸写      ⇒ 报红，除非白名单登记了理由；
//   · 数字 ≥ 50       ⇒ 胶囊（`RoundedCornerShape(50)` 百分比式与 `999.dp` 都算），自动放行；
//   · 20/22/28 这类   ⇒ 自定义，报红，白名单里必须写清「为什么不是 8 档之一」；
//   · `RoundedCornerShape(rCard)` / `ShapeCard` / 常量参数 ⇒ 放行。
// 本批收编：`ui/SettingsScreen.kt` 3 处（外观/语言两处对话框 → rDialog，SectionCard → rCard）。
//
// 两套**有意不并进来**的圆角体系，别硬塞进 8 档：
//   1）**胶囊**：除了 ≥50，还有「半高圆」写法——40dp 高写 20.dp、44dp 高写 22.dp，
//      视觉是全圆，值却 <50，所以半高圆只能进白名单（8 档里没有「全圆」这一档）。
//   2）**玄览 shell（`ui/browser/**`）**：底栏几何由用户 2026-10-18 定稿，并被
//      `BrowserBottomBarGuardTest` 用**源码字面量**钉死（键 12dp、胶囊 20dp、14dp 簇不许回潮），
//      色板另住 `XuanLanPalette`。把它并进 iOS 语义层要同时改那条守卫 ⇒ 属争议面，等单独拍板。
//
// 白名单就是欠账台账：每行写「待收编（哪一批）」或「语义位不同（差在哪）」；条目一旦没有对应裸值，
// 门禁打「过期」提示 ⇒ 当场删行。`RoundedCornerShape(常量)` 取不到值＝已知盲区，逐条记在交付报告里。

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