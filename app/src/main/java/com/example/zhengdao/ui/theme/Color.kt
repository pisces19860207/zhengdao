// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui.theme

import androidx.compose.ui.graphics.Color

// ── 纸墨黛青（2026-10-22 第一百三十七批：全 App 单一设计语言）───────────────
//
// 设计依据：`_zd-private/docs/抱朴UI设计语言方案-纸墨黛青-2026-10-22.md` §2 / §2.1 / §16。
// 已拍板原话（11:30）：「这是设计语言，我想放弃仿 iOS 了，终端的那三个交通灯不变，
// 全部固定成这一套。」⇒ **旧 iOS 常量名不保留**（留别名＝留后门）。
//
// 语言只有四类，归不进的删：
//   纸＝界面底（暖白，不是纯白）｜墨＝文字（只有两档灰 + 一档线灰）
//   青＝行动与主色（所有「可点 / 进行中 / 待你选」）｜朱＝危险与停止（全站唯一允许的红）
//   ⚠️ 绿色已删除：「已就绪」用青，「待处理」用琥珀，「错误/停止」用朱（§2.1 色数收敛）。
//
// 对比度纪律：本文件**不写「已验」结论**——所有比值由 `ContrastGuardTest` 从色值当场重算，
// 有一处不达标就是设计错，不许在测试里放宽判据（§2.1 末尾原文）。

// ── 浅色（默认）────────────────────────────────────────────────────────────
/** 纸 #F7F4ED：页面底（不是纯白，久读不累）。 */
val Paper = Color(0xFFF7F4ED)

/** 卡面 #FFFFFF：行动卡面。 */
val CardSurface = Color(0xFFFFFFFF)

/** 墨一 #1F1E1B：主文本。文字最深只用两档（墨一 / 次墨）。 */
val Ink1 = Color(0xFF1F1E1B)

/** 次墨 #57544C：次要文本、占位、角标、说明（§2.1 第 1 条：ink-3 退出文字序列）。 */
val Ink2 = Color(0xFF57544C)

/** 线灰 #8A857A：**仅非文字**（图标、需看得见的边界线，对纸 3.34:1 ≥ 3:1）。 */
val Ink3 = Color(0xFF8A857A)

/** 发丝线 #DCD7C9：纯装饰分隔（1.31:1，**不承担识别功能**）。 */
val Hairline = Color(0xFFDCD7C9)

/** 黛青 #2C5F54：主按钮、行动卡描边、进行中、已就绪。 */
val DaiQing = Color(0xFF2C5F54)

/** 浅青 #E3EDE7：标题条、胶囊底、选中底。 */
val QingTint = Color(0xFFE3EDE7)

/** 纸深一档 #EDE8DC：信息卡底、记录条底。 */
val PaperDeep = Color(0xFFEDE8DC)

/** 朱砂 #A8402B：停止、危险、错误（全站唯一允许的红）。 */
val ZhuSha = Color(0xFFA8402B)

/** 朱浅 #F7E5E0：errorContainer 底（朱砂在其上 5.02:1）。 */
val ZhuTint = Color(0xFFF7E5E0)

/** 琥珀 #856018：待处理徽标、警示（§2.1 第 2 条：由 #8F6A1F 加深，纸深底 4.66:1 才达 AA）。 */
val HuPo = Color(0xFF856018)

// ── 深色（同一套语言，不是另一套皮肤）──────────────────────────────────────
//
// 深色只换值不换语义：底仍是「纸」的深位、字仍是「墨」的深位、青/朱仍是青/朱。
// 浅色常量在两处被深色复用（`primaryContainer 深 = DaiQing`、`onPrimaryContainer 深 = QingTint`），
// 这与 §16.2 表格一致，不是笔误。
/** 墨底 #1C1B18：深色 page 底（不是 iOS 的纯黑 #000000）。 */
val DarkGround = Color(0xFF1C1B18)

/** 深卡面 #24231F：深色 surface。 */
val DarkSurface = Color(0xFF24231F)

/** 纸字 #EDE9DF：深色主文本。 */
val DarkText = Color(0xFFEDE9DF)

/** 次字 #A9A399：深色次要文本。 */
val DarkText2 = Color(0xFFA9A399)

/** 深线灰 #807B70：深色 outline（非文字边界；对卡面 3.73:1 ≥ 3:1）。 */
val DarkInk3 = Color(0xFF807B70)

/** 深发丝线 #3A372F：深色纯装饰分隔。 */
val DarkHairline = Color(0xFF3A372F)

/** 青提亮 #7FA89B：深色主色（对卡面 5.97:1，可当小字）。 */
val DarkDaiQing = Color(0xFF7FA89B)

/** 深纸深 #2E2C26：深色 secondaryContainer / surfaceVariant。 */
val DarkDeep = Color(0xFF2E2C26)

/** 朱提亮 #D0705A：深色 danger 色块（对卡面 4.60:1，只给色块/图标，小字走 [DarkOnZhuTint]）。 */
val DarkZhuSha = Color(0xFFD0705A)

/** 深朱浅 #4A2A22：深色 errorContainer 底。 */
val DarkZhuTint = Color(0xFF4A2A22)

/** 深朱浅上的字 #F0BBAE：深色错误正文 / errorContainer 内容（对卡面 9.31:1、墨底 10.19:1）。 */
val DarkOnZhuTint = Color(0xFFF0BBAE)

/** 琥珀提亮 #C4903F：深色 warn（对卡面 5.55:1）。 */
val DarkHuPo = Color(0xFFC4903F)
