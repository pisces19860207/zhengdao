// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext

/**
 * 浅色主题：iOS 风格（白卡片 + 系统蓝点缀）。
 *
 * 原注释「唯一主题、不随系统深色切换」已于 2026-10-12 作废——用户要求补深色，
 * 现由 [ZhengdaoTheme] 在浅/深之间按偏好选择（见 [AppearancePrefs]）。
 * 终端页仍自成黑底白框，不依赖此主题。
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

/**
 * 深色主题（2026-10-12 新增）：取值 = Apple HIG 的 dark 系统色，
 * 逐色对比度已在 `Color.kt` 里注（对 #1C1C1E 卡片底，均达 WCAG AA 4.5:1）。
 *
 * 槽位与浅色**一一对应**，业务代码全走 `MaterialTheme.colorScheme.*`，
 * 因此无需为深色改任何布局代码。
 */
val ZhengdaoDarkScheme = darkColorScheme(
    primary = IOSDarkBlue,
    onPrimary = Color.Black,
    primaryContainer = IOSDarkTintedBlue,
    onPrimaryContainer = IOSDarkOnTintedBlue,
    secondary = IOSDarkSecondaryLabel,
    onSecondary = Color.Black,
    secondaryContainer = IOSDarkFill,
    onSecondaryContainer = IOSDarkLabel,
    tertiary = IOSDarkGreen,
    errorContainer = IOSDarkTintedRed,
    onErrorContainer = IOSDarkOnTintedRed,
    tertiaryContainer = IOSDarkTintedGreen,
    onTertiaryContainer = IOSDarkOnTintedGreen,
    background = IOSDarkBg,
    onBackground = IOSDarkLabel,
    surface = IOSDarkCard,
    onSurface = IOSDarkLabel,
    surfaceVariant = IOSDarkFill,
    onSurfaceVariant = IOSDarkSecondaryLabel,
    surfaceContainerLowest = IOSDarkBg,
    surfaceContainerLow = Color(0xFF141416),
    surfaceContainer = IOSDarkCard,
    surfaceContainerHigh = Color(0xFF242426),
    surfaceContainerHighest = IOSDarkFill,
    outline = IOSDarkSeparator,
    outlineVariant = Color(0xFF2C2C2E),
    error = IOSDarkRed,
    onError = Color.Black,
)

/**
 * 应用主题：按 [AppearancePrefs] 的偏好在浅/深之间选择。
 *
 * 订阅 `AppearancePrefs.changeTick` ⇒ 用户在设置页切换外观时**当场换主题**
 * （无需 recreate Activity）。偏好本体存在 SharedPreferences，冷启动直接读回。
 * 组合期间只读不写——避免「首帧渲染完再纠正」的闪一下。
 */
@Composable
fun ZhengdaoTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val tick = AppearancePrefs.changeTick.value
    val pref = remember(tick) { AppearancePrefs.get(ctx) }
    val dark = AppearancePrefs.resolveDark(pref, isSystemInDarkTheme())
    MaterialTheme(
        colorScheme = if (dark) ZhengdaoDarkScheme else ZhengdaoLightScheme,
        typography = Typography,
        content = content,
    )
}

/**
 * 当前是否深色主题。
 *
 * 判据取 `colorScheme.background` 的实际亮度，**不取** [isSystemInDarkTheme]——
 * 用户在设置里把外观锁成「浅色」时系统仍是深色，后者会撒谎；
 * 派生自 colorScheme 则永远与真正渲染出来的画面一致。
 */
@Composable
fun isZhengdaoDark(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

/** 语义色「就绪绿」（小字/图形用）：浅色 #2E7D32（5.1:1）/ 深色 #30D158（8.4:1）。 */
@Composable
fun zhengdaoReadyGreen(): Color = if (isZhengdaoDark()) IOSDarkReadyGreen else IOSReadyGreen

/**
 * 语义色「蓝色小字」（链接/强调文字）：浅色 #0058B0 / 深色 #64B5F6。
 *
 * 对比度（2026-10-16 按源码取值、用 WCAG 公式复算；`ContrastGuardTest` 会重算一遍）：
 * 浅色 #0058B0 —— 白底 **6.95:1** / 页面灰底 #F2F2F7 6.23:1 / 选中 chip 底 #E1ECFF 5.84:1；
 * 深色 #64B5F6 —— 卡片底 #1C1C1E 7.68:1。全部达 AA 正文线 4.5:1。
 *
 * ⚠️ 这里曾经写「浅色 5.1:1」，台账 B-补-25 也曾记「在选中 chip 底上 3.38:1」——
 * 两个数字都**没复算过**，实测都不成立（见上）。数字以本注释为准。
 */
@Composable
fun zhengdaoBlueText(): Color = if (isZhengdaoDark()) IOSDarkBlueText else IOSBlueText

/**
 * 语义色「错误正文」（P2-11 / issue #17）：浅色 #C0392B（白底 5.4:1）/
 * 深色 #FFC9C2（对 #1C1C1E 11.7:1）。
 *
 * 为什么不用 `colorScheme.error`：那个槽位是给**色块/图标**用的——浅色值 IOSRed
 * #FF3B30 在白底只有 3.55:1，够不上 WCAG AA 正文的 4.5:1。审计（P2-11）抓到的
 * 正是抱朴顶栏「重连中」红字用了它。深色值直接复用 onErrorContainer 的
 * [IOSDarkOnTintedRed]——同为"深底上的红字"，不必另起变量。
 */
@Composable
fun zhengdaoErrorText(): Color = if (isZhengdaoDark()) IOSDarkOnTintedRed else IOSErrorText
