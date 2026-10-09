// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据的公开接口：Jetpack Compose（Material 3）官方 Canvas / Path API。
package com.example.zhengdao.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * 内联 Icon Glyph（A12：替换 emoji 为自绘）。
 *
 * 项目自始至终不用 material-icons，全部用 Compose Canvas 自绘——与底部 Tab
 * 的 TaijiIcon / CaveIcon / DingIcon / BackChevron / SettingsIcon 同一套笔法：
 * 圆头圆角、描边 1.5-1.9f、不填实色。Tab 那套在 26dp / 1.9f，这里是「消息流内联
 * 图标」专版：16-18dp 画布配 1.5f 描边，比 Tab 小一号。
 *
 * 用法：`AttachmentGlyph(tint = MaterialTheme.colorScheme.primary)`，直接作为
 * Composable 节点用（不需要 Icon() 包装）。
 *
 * 为什么写 4 个新的而不是用 material-icons：
 * 1. 跟项目既有 5 个自绘 Tab 图标同一套笔法——视觉一致性
 * 2. 不引入 material-icons 依赖（~1MB AAR 增量）
 * 3. 0 第三方代码，符合"独立开发"声明
 *
 * 为什么只替 4 个：状态行 emoji（✅/⚠️/❌/☑/☐）保留——它们是"提示"而非"图标"，
 * 替换为自绘圆点会丢失颜色编码含义。
 */

/** 16dp 画布的统一描边宽度。Tab 是 1.9f/26dp，这里按比例缩。 */
private const val INLINE_STROKE = 1.5f

/** 「附件」——回形针简化形（一个倾斜的椭圆形 + 一条穿过它的对角线）。 */
@Composable
fun AttachmentGlyph(tint: Color) {
    Canvas(Modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(INLINE_STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // 主体：45° 倾的椭圆
        drawOval(
            color = tint,
            topLeft = Offset(w * 0.18f, h * 0.30f),
            size = Size(w * 0.50f, h * 0.55f),
            style = stroke,
        )
        // 穿过椭圆右上的对角线
        drawLine(
            color = tint,
            start = Offset(w * 0.50f, h * 0.10f),
            end = Offset(w * 0.80f, h * 0.95f),
            strokeWidth = INLINE_STROKE,
            cap = StrokeCap.Round,
        )
    }
}

/** 「思考 / 思考中」—— 一个圆角矩形 + 内嵌三个点（打字气泡）。 */
@Composable
fun ThinkGlyph(tint: Color) {
    Canvas(Modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(INLINE_STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val r = Path().apply {
            addRoundRect(
                RoundRect(
                    left = w * 0.05f, top = h * 0.10f,
                    right = w * 0.95f, bottom = h * 0.70f,
                    cornerRadius = CornerRadius(w * 0.20f, h * 0.30f),
                )
            )
        }
        drawPath(r, tint, style = stroke)
        // 三个小点（打字机效果）
        val dotY = h * 0.40f
        for (i in 0..2) {
            drawCircle(
                color = tint,
                radius = w * 0.06f,
                center = Offset(w * (0.28f + 0.22f * i), dotY),
            )
        }
        // 下方小尾巴（指向左下，气泡的"嘴"）
        drawLine(
            color = tint,
            start = Offset(w * 0.28f, h * 0.70f),
            end = Offset(w * 0.20f, h * 0.88f),
            strokeWidth = INLINE_STROKE,
            cap = StrokeCap.Round,
        )
    }
}

/** 「工具 / 工具调用」——扳手简化形（一个圆 + 一根从圆里伸出的斜臂）。 */
@Composable
fun ToolGlyph(tint: Color) {
    Canvas(Modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(INLINE_STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // 头：左下圆
        drawCircle(
            color = tint,
            radius = w * 0.20f,
            center = Offset(w * 0.30f, h * 0.70f),
            style = stroke,
        )
        // 臂：从圆右上沿对角伸到右上角
        drawLine(
            color = tint,
            start = Offset(w * 0.43f, h * 0.57f),
            end = Offset(w * 0.88f, h * 0.12f),
            strokeWidth = INLINE_STROKE,
            cap = StrokeCap.Round,
        )
        // 臂尾：右上小斜线（扳手开口）
        drawLine(
            color = tint,
            start = Offset(w * 0.78f, h * 0.12f),
            end = Offset(w * 0.95f, h * 0.28f),
            strokeWidth = INLINE_STROKE,
            cap = StrokeCap.Round,
        )
    }
}

/** 「文件夹」—— 矩形 + 左上凸起小三角。 */
@Composable
fun FolderGlyph(tint: Color) {
    Canvas(Modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(INLINE_STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val p = Path().apply {
            moveTo(w * 0.08f, h * 0.32f)
            lineTo(w * 0.40f, h * 0.32f)
            lineTo(w * 0.50f, h * 0.20f)
            lineTo(w * 0.92f, h * 0.20f)
            lineTo(w * 0.92f, h * 0.85f)
            lineTo(w * 0.08f, h * 0.85f)
            close()
        }
        drawPath(p, tint, style = stroke)
    }
}

/**
 * 「删除」—— 垃圾桶（桶盖 + 提手 + 桶身）。
 *
 * 2026-10-09 新增（审计 A1）：历史会话行需要一枚**常驻可见**的删除入口。
 * 此前删除只由长按触发、界面毫无提示，新用户不知道会话能删。图标取中性色调用，
 * 与项目「不使用任何第三方图标素材、全部 Canvas 自绘」一致。
 *
 * 18dp 画布（比消息流内联图标 16dp 略大）：它是抽屉列表里的独立点击目标，
 * 需要足够辨识度；描边沿用同一常量，保持一套笔法。
 */
@Composable
fun TrashGlyph(tint: Color) {
    Canvas(Modifier.size(18.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(INLINE_STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // 提手：桶盖上方的小横
        drawLine(tint, Offset(w * 0.36f, h * 0.24f), Offset(w * 0.64f, h * 0.24f),
            strokeWidth = INLINE_STROKE, cap = StrokeCap.Round)
        // 桶盖：贯穿左右的横线
        drawLine(tint, Offset(w * 0.18f, h * 0.34f), Offset(w * 0.82f, h * 0.34f),
            strokeWidth = INLINE_STROKE, cap = StrokeCap.Round)
        // 桶身：左右两条内收的竖线 + 底部横线（上宽下窄的梯形近似）
        drawLine(tint, Offset(w * 0.28f, h * 0.34f), Offset(w * 0.33f, h * 0.86f),
            strokeWidth = INLINE_STROKE, cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.72f, h * 0.34f), Offset(w * 0.67f, h * 0.86f),
            strokeWidth = INLINE_STROKE, cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.33f, h * 0.86f), Offset(w * 0.67f, h * 0.86f),
            strokeWidth = INLINE_STROKE, cap = StrokeCap.Round)
    }
}
