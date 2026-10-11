// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui.theme

import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 2026-10-10 改：iOS 风 Switch 配色（M3 SwitchDefaults 的颜色叠加）。
 *
 * 改动理由：M3 Switch 默认走 primary 色（项目色板 = IOSBlue #007AFF），iOS 是
 * systemGreen #34C759。底栏/卡片已经全面 iOS 化，Switch 是最后一块 M3 默认色。
 *
 * 改动范围：4 处 Switch（PluginsScreen × 2 + SettingsScreen × 2），
 * 通过本 helper 集中定义颜色，调用处加 `colors = iosSwitchColors()` 一行即可。
 *
 * 配色细节：
 * - 选中轨道 = IOSGreen（iOS systemGreen 原色，与项目 StatusCard "就绪" 同源）
 * - 选中/未选中滑块 = 纯白（iOS 是白，M3 默认是 onPrimary 本项目 = 白，刚好一致）
 * - 边框全透明：iOS Switch 没有描边
 * - 未选中轨道 = IOSFill（iOS 系统填充灰 #E9E9EB）
 */
@Composable
fun iosSwitchColors(): SwitchColors = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = IOSGreen,
    checkedBorderColor = Color.Transparent,
    uncheckedThumbColor = Color.White,
    uncheckedTrackColor = IOSFill,
    uncheckedBorderColor = Color.Transparent,
)
