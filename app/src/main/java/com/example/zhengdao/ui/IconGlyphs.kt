// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据的公开接口：Jetpack Compose（Material 3）官方 Canvas / Path API。
package com.example.zhengdao.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
 * 的 BaoPuIcon / XiaoYaoIcon / YiRanIcon / BackChevron / SettingsIcon 同一套笔法：
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
