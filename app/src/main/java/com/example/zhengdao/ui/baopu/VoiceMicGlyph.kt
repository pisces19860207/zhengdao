// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp

/** 自绘麦克风（不引图标库、不加 emoji，与回形针/朗读同一条自绘房型）。 */
@Composable
internal fun MicGlyph(tint: Color, side: Dp) {
    Canvas(Modifier.size(side)) {
        val w = size.width
        val h = size.height
        val stroke = w / 12f
        // 头：上方圆角胶囊
        drawRoundRect(
            color = tint,
            topLeft = androidx.compose.ui.geometry.Offset(w * 0.36f, h * 0.10f),
            size = androidx.compose.ui.geometry.Size(w * 0.28f, h * 0.48f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.14f, w * 0.14f),
            style = Stroke(width = stroke),
        )
        // 托架：下方半弧
        drawArc(
            color = tint,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(w * 0.22f, h * 0.36f),
            size = androidx.compose.ui.geometry.Size(w * 0.56f, h * 0.50f),
            style = Stroke(width = stroke),
        )
        // 杆与底座
        drawLine(
            color = tint,
            start = androidx.compose.ui.geometry.Offset(w * 0.50f, h * 0.86f),
            end = androidx.compose.ui.geometry.Offset(w * 0.50f, h * 0.95f),
            strokeWidth = stroke,
        )
        drawLine(
            color = tint,
            start = androidx.compose.ui.geometry.Offset(w * 0.34f, h * 0.95f),
            end = androidx.compose.ui.geometry.Offset(w * 0.66f, h * 0.95f),
            strokeWidth = stroke,
        )
    }
}
