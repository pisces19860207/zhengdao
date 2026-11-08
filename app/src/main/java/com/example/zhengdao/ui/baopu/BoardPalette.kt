// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百四十二批（2026-11-08）· **模型小黑板的板面色**（A 案：深绿底 / 白字）。
//
// 用户口径（2026-11-08）：「样式采用 **A 案：深绿底 #1F3B2F / 白字 #F2F1E6**；字号/圆角/内距/
// 按钮一律用律令既有 token」＋「**不做主题切换开关**；不引第三方字体」。
//
// ## 为什么这三个色不写进 ui/theme/Color.kt
//
// 纸墨黛青只有四类（纸/墨/青/朱），且 `ContrastGuardTest` 有一条硬门禁：
// **Color.kt 里不许再有绿色常量**（§2.1「色数收敛」，语义绿"已就绪"已删）。
// 小黑板的绿不是**语义色**（它不表示"成功/就绪"），是**一块实体板子的物性色**——
// 与玄览底栏另住 `XuanLanPalette` 同一房型：屏级色板自己一个文件，不与主题色板混住，
// 免得下一次有人照抄这个绿去做"成功"色。
//
// ## 三条边界（本文件的存在理由）
//
// 1. **只给小黑板用**：`BoardStyleGateTest` 钉住——除本文件与治理中心那一屏，全仓不许引用它；
// 2. **不随深浅主题变**：一块挂在墙上的板子不会因为手机切了深色模式而变成浅绿
//    （用户原话「不做主题切换开关」）。所以这里没有 `isZhengdaoDark()` 分支；
// 3. **对比度不写"已验"结论**：`BoardPaletteContrastTest` 从这三个色值当场按 WCAG 相对亮度
//    重算（与本仓 `ContrastGuardTest` 同一把尺），有一处不达 AA 就是设计错。
//
// ## 三档各是什么
//
// | 令牌 | 值 | 用在哪 |
// |---|---|---|
// | [BoardPalette.ground] | `#1F3B2F` | 板面底（卡片容器色，唯一填色处） |
// | [BoardPalette.chalk] | `#F2F1E6` | 板面正文（模型写的那几行）与「清空」按钮字 |
// | [BoardPalette.chalkDim] | `#C9C9BE` | 板面小字：标题行「模型小黑板 · 最近更新 HH:mm」、上限、占位 |
package com.example.zhengdao.ui.baopu

import androidx.compose.ui.graphics.Color

/** 小黑板的板面色（A 案三档；口径与边界见文件头）。 */
internal object BoardPalette {

    /** 板面底 `#1F3B2F`（深绿）：卡片容器色，全屏只有这一处填它。 */
    val ground: Color = Color(0xFF1F3B2F)

    /** 板面字 `#F2F1E6`（暖白，不是纯白——纯白在绿上发冷、久看刺眼）。 */
    val chalk: Color = Color(0xFFF2F1E6)

    /** 板面小字 `#C9C9BE`（灰白）：标题行、上限、空板占位。 */
    val chalkDim: Color = Color(0xFFC9C9BE)
}
