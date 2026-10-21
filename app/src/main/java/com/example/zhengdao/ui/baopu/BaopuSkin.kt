// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
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

/** 卡圆角（v1.4 §1：卡 20）。决策卡与设置页卡片共用。 */
internal const val BAO_CARD_RADIUS_DP = 20

/** 按钮圆角（v1.4 §1：按钮 14）。 */
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

internal val BaoCardShape = RoundedCornerShape(BAO_CARD_RADIUS_DP.dp)

internal val BaoButtonShape = RoundedCornerShape(BAO_BUTTON_RADIUS_DP.dp)

internal val BaoChipShape = RoundedCornerShape(BAO_CHIP_RADIUS_DP.dp)
