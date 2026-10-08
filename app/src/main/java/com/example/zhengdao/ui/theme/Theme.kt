// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 唯一主题：iOS 风格浅色（固定不随系统深色切换——证道面向"打开就用"的普通用户，
 * 两套主题等于两倍的走查成本；终端页自成黑底白框，不依赖此主题）。
 */
val ZhengdaoLightScheme = lightColorScheme(
    primary = IOSBlue,
    onPrimary = Color.White,
    primaryContainer = IOSTintedBlue,
    onPrimaryContainer = Color(0xFF003E7E),
    secondary = IOSSecondaryLabel,
    onSecondary = Color.White,
    secondaryContainer = IOSFill,
    onSecondaryContainer = IOSLabel,
    tertiary = IOSGreen,
    // 2026-10-08 走查补齐：lightColorScheme 原本缺 errorContainer / onErrorContainer /
    // tertiaryContainer / onTertiaryContainer——A4 修 ConnectionBanner 引用前两个，
    // 缺了会拿到 Color.Unspecified，运行时渲染异常。配色按 iOS 色板延伸
    // (IOSTintedRed / IOSTintedGreen 与 IOSTintedBlue 同源：12% 透明叠加)。
    // onTertiaryContainer 用 IOSLabel 主文字黑：在 IOSTintedGreen 上对比度 ~13:1，远超
    // WCAG AA 的 4.5:1；不另起「深绿」变量避免扩散（该色只在此一处用）。
    errorContainer = IOSTintedRed,
    onErrorContainer = IOSErrorText,
    tertiaryContainer = IOSTintedGreen,
    onTertiaryContainer = IOSLabel,
    background = IOSBg,
    onBackground = IOSLabel,
    surface = IOSCard,
    onSurface = IOSLabel,
    surfaceVariant = IOSFill,
    onSurfaceVariant = IOSSecondaryLabel,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F7FA),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF7F7FA),
    surfaceContainerHighest = IOSFill,
    outline = IOSSeparator,
    outlineVariant = Color(0xFFE5E5EA),
    error = IOSRed,
    onError = Color.White,
)

@Composable
fun ZhengdaoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ZhengdaoLightScheme,
        typography = Typography,
        content = content,
    )
}
