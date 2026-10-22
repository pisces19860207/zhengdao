// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui.theme

import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Switch 配色（M3 [SwitchDefaults] 的四个颜色），全 App 集中一处。
 *
 * 2026-10-22 第一百三十七批换 Token：**选中轨道 → 黛青、未选中轨道 → 发丝线，形状一行不动**
 * （§15.4 与 §17 定论：iOS 的「形状语言」保留——胶囊开关、圆角分组、对勾、底栏几何、
 * 手势长期保持现状，本轮只换色）。旧值（选中绿 #34C759 / 未选中灰 #E9E9EB）随绿通道删除。
 *
 * 四色口径：
 * - 选中轨道 = [DaiQing]（深色 [DarkDaiQing]）：青＝可行动；
 * - 未选中轨道 = [Hairline]（深色 [DarkHairline]）：发丝线，纯装饰位置；
 * - 两态滑块保持纯白：白在黛青上 7.31:1、在发丝线上靠 M3 自带 1dp 投影分辨（形状不动，
 *   不给滑块额外描边——那是改形状）；
 * - 边框全透明：iOS Switch 没有描边，本批不新增。
 *
 * 原名 `iosSwitchColors()` 同步改名（旧名保留＝留后门，与 Color.kt 同一纪律）。
 */
@Composable
fun zhengdaoSwitchColors(): SwitchColors {
    val dark = isZhengdaoDark()
    return SwitchDefaults.colors(
        checkedThumbColor = Color.White,
        checkedTrackColor = if (dark) DarkDaiQing else DaiQing,
        checkedBorderColor = Color.Transparent,
        uncheckedThumbColor = Color.White,
        uncheckedTrackColor = if (dark) DarkHairline else Hairline,
        uncheckedBorderColor = Color.Transparent,
    )
}
