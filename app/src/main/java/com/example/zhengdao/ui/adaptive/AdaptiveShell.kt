// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.ui.adaptive

import android.app.Activity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.zhengdao.ui.adaptive.AdaptiveLayout.decide

/**
 * 746 宿主适配壳：把尺寸档与折叠姿态接进真实 UI。
 *
 * 接线纪律（判据②④）：
 *   - 本壳不改变子树里的任何状态持有者（tab / listState / 输入框 / controller 全在子树内，
 *     壳只在它们外面加一层 padding，合成树中原有 composable 的身份/顺序/key 全部不变）
 *     ⇒ 配置变更（旋转/折叠/分屏）后 rememberSaveable 照常恢复。
 *   - 唯一新增状态是 foldState（系统派生态）：翻转后由系统重新推，不作为用户输入保存，
 *     丢一帧不影响受保护的四项。
 *
 * 宽度来源用 LocalWindowInfo.containerSize（当前窗口容器尺寸）——分屏/折叠下才是真正可见的
 * 那块区域；不用 Configuration.screenWidthDp（lint ConfigurationScreenWidthHeight，且分屏下
 * 会给出整屏宽度导致档位判错）。
 */
val LocalLayoutConfig = staticCompositionLocalOf {
    LayoutConfig(
        widthClass = WidthSizeClass.COMPACT,
        paneCount = 1,
        navigation = NavigationStyle.BOTTOM,
        editorMaxWidthDp = WindowSizeClasses.EDITOR_MAX_COMPACT_DP,
        hingeTopPaddingDp = 0,
        hingeBottomPaddingDp = 0,
    )
}

@Composable
fun AdaptiveShell(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context as? Activity }
    val containerSize = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current.density

    // 折叠姿态：无 Activity / 无折叠能力 ⇒ 平展，绝不阻断主界面。
    val foldFlow = remember(activity) {
        activity?.let { runCatching { FoldStateAdapter.foldStates(it) }.getOrNull() }
    }
    val foldState by foldFlow?.collectAsStateWithLifecycle(initialValue = FoldState.FLAT)
        ?: remember { androidx.compose.runtime.mutableStateOf(FoldState.FLAT) }

    // px → dp：用真实 density（分屏下 window container 是 px，档位阈值是 dp）。
    val widthDp = if (density > 0f) containerSize.width / density else 0f
    val window = IntRect(0, 0, containerSize.width, containerSize.height)
    val config = remember(containerSize.width, containerSize.height, density, foldState) {
        decide(widthDp, foldState, window)
    }

    CompositionLocalProvider(LocalLayoutConfig provides config) {
        Box(Modifier.fillMaxSize().hingePadding(config)) { content() }
    }
}

/** 铰链避让 padding：只在半折时于内容区上下留白，让内容不落在铰链带。 */
private fun Modifier.hingePadding(config: LayoutConfig): Modifier = if (
    config.hingeTopPaddingDp > 0 || config.hingeBottomPaddingDp > 0
) {
    this.padding(top = config.hingeTopPaddingDp.dp, bottom = config.hingeBottomPaddingDp.dp)
} else this
