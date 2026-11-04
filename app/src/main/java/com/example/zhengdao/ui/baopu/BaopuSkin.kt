// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百零五批（2026-10-21，派工单 §0）：**抱朴外壳皮肤**——抽屉 / 顶栏 / 跑马灯 / 设置页
// 四个壳件共用的常量与派生形状，一处定义。
//
// 为什么要有这个文件（不是「又一层封装」）：
//   v1.4 §1 的圆角与触控口径原先散在各处——104 决策卡把「卡 20 / 钮 14 / 警示带 12」
//   写成 `DecisionCard.kt` 里的三个 private val，外壳又要同样的值 ⇒ 数值相同却各写一份，
//   改一处漏三处。本批把**值**收到这里，卡与壳件都从这里取；卡自己的排版与判据一行不动。
//
// 与 v1.4 token 的对应（不新增第二套视觉语言，全部落到既有 MaterialTheme 槽位）：
//   v1.4 纸墨底 paper/surface  → colorScheme.background / surface
//   v1.4 主文本 ink-1          → colorScheme.onSurface
//   v1.4 次文本 ink-2          → colorScheme.onSurfaceVariant
//   v1.4 主色 primary（黛青）  → colorScheme.primary（App 现值是 iOS 系统蓝，语义位相同）
//   v1.4 tint 主色浅底         → colorScheme.primaryContainer
//   v1.4 line 发丝线           → colorScheme.outlineVariant
//   v1.4 高风险                → colorScheme.errorContainer / onErrorContainer
//   ⚠️ 设计稿给的是深色黛青十六进制值，104 已按「走既有 token、不新增第二套视觉语言」落地；
//      本批沿用同一取舍——换整仓色板是全局改版，不在外壳单里做（见交付报告「未做项」）。
//
// 纪律：本文件**只有常量与形状**，没有判据、没有状态、没有 @Composable 副作用。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

// 2026-10-22 第一百三十七批（设计语言 §4「显式收编」）：
//   **卡圆角 20 → rCard 16**（`ui/theme/Radius.kt` 的八档令牌）。
//   20dp 在圆角门禁里属「自定义值」，本就欠一个理由；四档容器统一走 `RoundedCornerShape(rCard)` 后，
//   `BAO_CARD_RADIUS_DP` / `BaoCardShape` 已零引用 ⇒ 本条删除（留常量＝留后门）。
//   按钮 14 / chip 12 本轮不动（属于已拍板保留的「形状语言」，§17 定论）。

/** 按钮圆角（按钮层仍用既有值；设计语言 §4 的对照是 rButton 12，换形要另开单）。 */
internal const val BAO_BUTTON_RADIUS_DP = 14

/** 小容器/警示带/列表高亮块圆角（v1.4 §1：列表容器 16、chip 12——壳件取 12 这一档）。 */
internal const val BAO_CHIP_RADIUS_DP = 12

/** 顶栏图标位边长（v1.4 §4：☰／🛡／＋ 各 48dp，不缩不裁）。 */
internal const val BAO_ICON_SLOT_DP = 48

/** 列表行最低可点高度（v1.4 §4：条目行 ≥48dp）。 */
internal const val BAO_ROW_MIN_HEIGHT_DP = 48

/** 按钮最低高度（v1.4 §4：按钮 ≥48dp；与行高同值但语义不同，分开写便于将来单独调）。 */
internal const val BAO_BUTTON_MIN_HEIGHT_DP = 48

/** 抽屉条目的左右内边距（分组标签、条目共用，保证标签与文字左缘对齐）。 */
internal const val BAO_LIST_INSET_DP = 16

/** 会话行在文件夹行下面的缩进（视觉从属关系，不是命中区）。 */
internal const val BAO_SESSION_INDENT_DP = 40

// 2026-11-04 第四百三十四批（顶栏对齐设计稿）：顶栏那四件的尺寸全部落在这里，
// **派工单里的坐标（368/384/884/1172dp）一条都不许进代码**——那些是参考线，
// 实际位置由「权重 + 间距」推出来，换设备/换字号都不破版式。

/** 顶栏两段的容器高（设计稿：模型区与跑马灯同高 44dp；命中层另按 BAO_ROW_MIN_HEIGHT_DP 48dp 走）。 */
internal const val BAO_TOPBAR_PILL_HEIGHT_DP = 44

/** 顶栏容器描边宽（设计稿：朱砂 1.5dp；只在既有 token 之上加线宽，不加颜色）。 */
internal const val BAO_TOPBAR_PILL_STROKE_DP = 1.5f

/** 模型区内两个命中区之间的间隔（派工单硬规矩：≥8dp，防误触）。 */
internal const val BAO_TOPBAR_ZONE_GAP_DP = 8

/** 模型区整块与跑马灯之间的间隔（派工单参考线：≈16dp，走 4/8/12/16/24/32 尺度）。 */
internal const val BAO_TOPBAR_SECTION_GAP_DP = 16

/** 字母徽标那颗的边长（自绘徽标替代公司全名，省横向空间；高随 44dp 容器收成 28）。 */
internal const val BAO_LETTER_TILE_DP = 28

internal val BaoButtonShape = RoundedCornerShape(BAO_BUTTON_RADIUS_DP.dp)

internal val BaoChipShape = RoundedCornerShape(BAO_CHIP_RADIUS_DP.dp)
