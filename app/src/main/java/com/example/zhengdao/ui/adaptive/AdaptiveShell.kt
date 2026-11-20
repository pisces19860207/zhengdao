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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.zhengdao.ui.adaptive.AdaptiveLayout.decide

/**
 * 746 宿主适配壳：把尺寸档与折叠姿态接进真实 UI。
 *
 * 接线纪律（判据②④）：
 *   - 本壳**不改变**子树里的任何状态持有者（tab / listState / 输入框 / controller 全在子树内，
 *     壳只在它们外面加一层 padding，合成树中原有 composable 的身份/顺序/key 全部不变）
 *     ⇒ 配置变更（旋转/折叠/分屏）后 rememberSaveable 照常恢复。
 *   - 唯一新增状态是 foldState（系统派生态）：翻转后由系统重新推，不作为用户输入保存，
 *     丢一帧不影响受保护的四项。
 *
 * 档位不用 material3-window-size-class：该 artifact 本机未缓存、要联网；
 * LocalConfiguration.screenWidthDp 给出等价宽度（dp），交给纯函数判档，可脱离 Android 单测。
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
    val configuration = LocalConfiguration.current

    // 折叠姿态：无 Activity / 无折叠能力 ⇒ 平展，绝不阻断主界面。
    val foldFlow = remember(activity) {
        activity?.let { runCatching { FoldStateAdapter.foldStates(it) }.getOrNull() }
    }
    val foldState by foldFlow?.collectAsStateWithLifecycle(initialValue = FoldState.FLAT)
        ?: remember { androidx.compose.runtime.mutableStateOf(FoldState.FLAT) }

    val widthDp = configuration.screenWidthDp.toFloat()
    val window = IntRect(0, 0, configuration.screenWidthDp, configuration.screenHeightDp)
    val config = remember(widthDp, foldState, configuration.screenHeightDp) {
        decide(widthDp, foldState, window)
    }

    CompositionLocalProvider(LocalLayoutConfig provides config) {
        Box(Modifier.fillMaxSize().then(config.adaptivePadding())) { content() }
    }
}

/** 铰链避让 padding：只在半折时于内容区上下留白，让内容不落在铰链带。 */
private fun LayoutConfig.adaptivePadding(): Modifier = if (hingeTopPaddingDp > 0 || hingeBottomPaddingDp > 0) {
    Modifier.padding(top = hingeTopPaddingDp.dp, bottom = hingeBottomPaddingDp.dp)
} else Modifier
