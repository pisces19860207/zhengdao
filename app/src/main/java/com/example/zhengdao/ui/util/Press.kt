// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui.util

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * 2026-10-10 加：iOS 风按压反馈 + 触感 = [Modifier.pressClickable]。
 *
 * 行为：
 * - 按下缩到 0.97（spring 弹性回弹 ~150ms，比涟漪早一拍触发视觉反馈）
 * - 松开恢复 1.0
 * - 触发 onClick 时调用 LocalHapticFeedback（短震动，M3 Switch 自带同等反馈）
 *
 * 用法：把 `Modifier.clickable { ... }` 换成 `Modifier.pressClickable { ... }`，
 * import 路径：`com.example.zhengdao.ui.util.pressClickable`。
 *
 * ⚠️ 必须放在 `clip(...)` 之后（缩放围绕外接矩形轴，clip 后圆角不变形）。
 * ⚠️ 当前实现走 `composed`（M3 标 deprecated 但 BOM 2026.02 仍可用），
 *    待 Compose 内核升级后改 Node API。
 *
 * 全仓现状（2026-10-10）：clickable 共 9+ 处，本 helper 首批示范 3 处
 * （HomeScreen 状态卡 / HomeScreen Agent 菜单 / SettingsScreen SettingRow）；
 * 其余位置由后续支线按需替换，不在本次范围内。
 */
fun Modifier.pressClickable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "press-scale",
    )
    val haptic = LocalHapticFeedback.current
    this
        .scale(scale)
        .clickable(
            interactionSource = interactionSource,
            indication = ripple(),
            enabled = enabled,
            onClickLabel = onClickLabel,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
        )
}
