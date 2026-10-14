// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据的公开接口：Jetpack Compose（Material 3）官方 API。
//
// P4-1（2026-10-14）：自 TaijiComponents.kt（拆分前 1929 行）按内聚性拆出——
// **只搬不改**，代码逐行原样（含已提交的 P2-10 48dp 触摸目标修复）。
package com.example.zhengdao.ui.taiji

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R

// ── 输入区 ────────────────────────────────────────────────────────────

/**
 * 输入区。
 *
 * ⚠️ **不要给TextField 设 password 类型**——国产 ROM 会弹安全键盘（无 ESC/CTRL、
 * 布局错乱、部分禁粘贴），终端与输入直接不可用。继承 v3 §7 红线。
 * 密码/密钥类输入应另做"普通文本框 + App 内自绘遮蔽"。
 *
 * 视觉（2026-10-07 打磨，用户定稿）：输入框走**胶囊圆角**（24dp）并去掉填充式
 * 输入框的下划线指示器；发送/停止改为 **48dp 圆形按钮**，与胶囊输入框同一套"圆"语言。
 * 箭头与停止方块都是 Canvas 自绘——本工程不使用任何第三方图标素材。
 *
 * @param isStreaming 流式输出中：发送键变为「■ 停止」，不让用户干等。
 *   生成中/可发送由 [TaijiState.isStreaming] 驱动（`prompt` 置真、SSE `session.idle` 置假，
 *   另有轮询「内容稳定即视为结束」兜底，防事件漏接）。
 */
@Composable
fun ComposerBar(
    input: String,
    isStreaming: Boolean,
    enabled: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onAbort: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(
            Modifier.fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()          // 🔺 键盘弹出不得顶掉输入框
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                TextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f).onPreviewKeyEvent { e ->
                        // Enter 提交 / Shift+Enter 换行（v1.1 第三阶段；硬件键盘为主）。
                        // 仅在有可发送内容时拦截，其余情况放行给默认换行，避免误发/吞键。
                        if (e.type == KeyEventType.KeyDown && e.key == Key.Enter &&
                            !e.isShiftPressed && !isStreaming && enabled && input.isNotBlank()
                        ) {
                            onSend(); true
                        } else {
                            false
                        }
                    },
                    // 2026-10-08 走查：流式输出中 placeholder 仍写「描述你的任务…」会让用户
                    // 困惑——「我这时还能发吗？」切到「AI 正在回复…」明示状态；发送键已变停止
                    // (FilledTonalIconButton)，双指示器一致。
                    placeholder = {
                        Text(
                            if (isStreaming) stringResource(R.string.taiji_placeholder_streaming)
                            else stringResource(R.string.taiji_placeholder_idle)
                        )
                    },
                    maxLines = 6,
                    // 圆润：胶囊形。TextField 默认是只有上圆角的 4dp 矩形，与"打开就用"的
                    // 观感不搭；24dp 在单行时是胶囊、多行时仍是柔和的大圆角。
                    shape = RoundedCornerShape(24.dp),
                    // 🔺 普通文本类型——绝不 TYPE_TEXT_VARIATION_PASSWORD
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        autoCorrectEnabled = false,
                        capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None,
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        // 填充式输入框配胶囊形状时，那条下划线指示器是多余的装饰
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                )
                Spacer(Modifier.width(8.dp))
                if (isStreaming) {
                    // 停止＝tonal 圆钮（与实心蓝的发送钮在颜色上区分开，避免误以为还能发）
                    FilledTonalIconButton(
                        onClick = onAbort,
                        modifier = Modifier.size(48.dp),
                    ) { StopGlyph() }
                } else {
                    FilledIconButton(
                        onClick = onSend,
                        enabled = enabled && input.isNotBlank(),
                        modifier = Modifier.size(48.dp),
                    ) { SendGlyph() }
                }
            }
        }
    }
}

/** 发送图标：向上的箭头（自绘，跟随按钮的 LocalContentColor）。 */
@Composable
private fun SendGlyph() {
    val tint = LocalContentColor.current
    // semantics{} 的 lambda 不是 @Composable，Composable 取文本必须先落到局部变量
    val desc = stringResource(R.string.taiji_send_desc)
    // 2026-10-08：无障碍。Canvas 自绘默认**不带任何语义**，读屏用户听到的是空白按钮。
    // contentDescription 写在 Canvas 上会与父 IconButton 的语义合并，播报为「发送，按钮」。
    Canvas(
        Modifier
            .size(20.dp)
            .semantics { contentDescription = desc }
    ) {
        val w = size.width
        val h = size.height
        val sw = 2.2f
        drawLine(tint, Offset(w * 0.50f, h * 0.88f), Offset(w * 0.50f, h * 0.16f), sw, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.50f, h * 0.16f), Offset(w * 0.20f, h * 0.46f), sw, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.50f, h * 0.16f), Offset(w * 0.80f, h * 0.46f), sw, StrokeCap.Round)
    }
}

/** 停止图标：圆角实心方块（■）。 */
@Composable
private fun StopGlyph() {
    val tint = LocalContentColor.current
    // semantics{} 的 lambda 不是 @Composable，取文本先落局部变量（同 SendGlyph）
    val desc = stringResource(R.string.taiji_stop_desc)
    // 2026-10-08：无障碍，同 SendGlyph（Canvas 无语义）。
    Canvas(
        Modifier
            .size(20.dp)
            .semantics { contentDescription = desc }
    ) {
        val s = size.minDimension
        drawRoundRect(
            color = tint,
            topLeft = Offset(s * 0.24f, s * 0.24f),
            size = androidx.compose.ui.geometry.Size(s * 0.52f, s * 0.52f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.10f),
        )
    }
}
