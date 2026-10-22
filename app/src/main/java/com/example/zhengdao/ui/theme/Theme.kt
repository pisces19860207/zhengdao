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
 * 浅色主题：纸墨黛青（2026-10-22 第一百三十七批换 Token）。
 *
 * 逐槽位映射依据 `抱朴UI设计语言方案-纸墨黛青-2026-10-22.md` §16.1（28 槽），
 * 一条一行、顺序与文档表一致，便于逐条对账。**不再走 iOS 系统色板**——
 * 旧值（systemBlue #007AFF / 分组底 #F2F2F7 / 系统填充 #E9E9EB）已随常量删除。
 *
 * 两个要点（都是 §2.1 定的，不是随手取）：
 * - `onSurfaceVariant` = 次墨 #57544C：全站 110 处次要文字都读它，对纸深底 6.18:1、
 *   对纸 6.88:1，比旧 iOS 的 4.6:1 清楚——这是「不再糊」的最大来源；
 * - `surfaceVariant` = 纸深 #EDE8DC：它只是容器底，**边界由四档容器的形状通道承担**
 *   （四边 2dp / 左边 4dp / 下边 1dp，见 `ui/baopu/BaopuContainers.kt`），
 *   不许再靠「底色深浅」分卡片（§6.1 实测纸深对纸只有 1.11:1，靠它分区等于没分）。
 */
val ZhengdaoLightScheme = lightColorScheme(
    primary = DaiQing,
    onPrimary = Color.White,
    primaryContainer = QingTint,
    onPrimaryContainer = DaiQing,
    secondary = Ink2,
    onSecondary = Color.White,
    secondaryContainer = PaperDeep,
    onSecondaryContainer = Ink1,
    tertiary = HuPo,
    onTertiary = Color.White,
    tertiaryContainer = PaperDeep,
    onTertiaryContainer = HuPo,
    error = ZhuSha,
    onError = Color.White,
    errorContainer = ZhuTint,
    onErrorContainer = ZhuSha,
    background = Paper,
    onBackground = Ink1,
    surface = CardSurface,
    onSurface = Ink1,
    surfaceVariant = PaperDeep,
    onSurfaceVariant = Ink2,
    surfaceContainerLowest = CardSurface,
    surfaceContainerLow = Paper,
    surfaceContainer = CardSurface,
    surfaceContainerHigh = PaperDeep,
    surfaceContainerHighest = PaperDeep,
    outline = Ink3,
    outlineVariant = Hairline,
)

/**
 * 深色主题（§16.2，28 槽）：同一套语言的深位，**不是第二套皮肤**。
 *
 * 与浅色槽位一一对应，业务代码全走 `MaterialTheme.colorScheme.*` ⇒ 切深浅只换颜色、
 * 布局零位移。`onError` 取墨底而不是白：朱提亮 #D0705A 是浅色相的块，
 * 白字在其上只有 2.4:1，墨底 5.04:1 才达 AA（§16.2）。
 */
val ZhengdaoDarkScheme = darkColorScheme(
    primary = DarkDaiQing,
    onPrimary = DarkGround,
    primaryContainer = DaiQing,
    onPrimaryContainer = QingTint,
    secondary = DarkText2,
    onSecondary = DarkGround,
    secondaryContainer = DarkDeep,
    onSecondaryContainer = DarkText,
    tertiary = DarkHuPo,
    onTertiary = DarkGround,
    tertiaryContainer = DarkDeep,
    onTertiaryContainer = DarkHuPo,
    error = DarkZhuSha,
    onError = DarkGround,
    errorContainer = DarkZhuTint,
    onErrorContainer = DarkOnZhuTint,
    background = DarkGround,
    onBackground = DarkText,
    surface = DarkSurface,
    onSurface = DarkText,
    surfaceVariant = DarkDeep,
    onSurfaceVariant = DarkText2,
    surfaceContainerLowest = DarkGround,
    surfaceContainerLow = DarkSurface,
    surfaceContainer = DarkSurface,
    surfaceContainerHigh = DarkDeep,
    surfaceContainerHighest = DarkDeep,
    outline = DarkInk3,
    outlineVariant = DarkHairline,
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
    val tick = AppearancePrefs.changeTick.intValue
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

/**
 * 语义色「就绪」（小字/图形用）：青。
 *
 * 2026-10-22 第一百三十七批：原名 `zhengdaoReadyGreen()`，返回绿色 `#2E7D32`。
 * §2.1「色数收敛」删掉绿色通道——**青＝可行动**，已就绪就是可行动，所以返青
 * （浅 #2C5F54 / 深 #7FA89B）。这不是丢功能，是执行 §1 那句「归不进的，删」。
 */
@Composable
fun zhengdaoReady(): Color = if (isZhengdaoDark()) DarkDaiQing else DaiQing

/**
 * 语义色「链接/强调文字」：黛青。
 *
 * 2026-10-22 第一百三十七批：原名 `zhengdaoBlueText()`，返回系统蓝 `#0058B0`。
 * 现值浅色 #2C5F54（纸底 6.66:1 / 白卡 7.31:1 / 浅青底 6.11:1）、
 * 深色 #7FA89B（卡面 5.97:1 / 墨底 6.54:1），全部达 AA 4.5:1 正文线。
 */
@Composable
fun zhengdaoLinkText(): Color = if (isZhengdaoDark()) DarkDaiQing else DaiQing

/**
 * 语义色「错误正文」（P2-11 / issue #17）：浅色朱砂 #A8402B（纸底 5.57:1）/
 * 深色 #F0BBAE（卡面 9.31:1、墨底 10.19:1）。
 *
 * 为什么不用 `colorScheme.error`：那个槽位是给**色块/图标**用的——浅色朱砂本身
 * 5.57:1 够用，但深色 `error` 提亮成 #D0705A 只有 4.60:1，紧贴门槛且不适合长段正文；
 * 深色错误正文一律取 [#DarkOnZhuTint]（errorContainer 内容色），留足余量。
 * 审计（P2-11）抓到的正是抱朴顶栏「重连中」红字用过 `colorScheme.error`。
 */
@Composable
fun zhengdaoErrorText(): Color = if (isZhengdaoDark()) DarkOnZhuTint else ZhuSha
