package com.example.zhengdao.ui.browser

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R

/**
 * 玄览浮动胶囊导航条（第⑨刀 C · v1.1 主稿双段分组 ＋ 探索稿 B 浮动胶囊形态）。
 *
 * 设计依据：`证道-玄览UI设计稿-2026-10-14-v1.1.html`（主稿：双段分组、1×20 分隔线、
 * 主页键唯一填充）+ `…-v1.1-explore.html`（探索稿 B：浮动胶囊、圆角 28、强阴影）
 * ＋ 用户收窄定稿（台账 FEATURE-LEDGER:490）。
 *
 * 几何（全部按任务书收窄数，可照搬）：
 * - 命中区 **48×48dp**、键圆角 **12dp**；胶囊栏高 **56dp**（48 键上下各 4dp，
 *   由 Box 高度承载，**不用 padding 撑高**）；
 * - 地址栏带高 50dp、与胶囊间距 6px ⇒ 底栏合计 **106dp**（50+56；6px 住在地址栏带
 *   底部 padding 里，BrowserScreen 负责）；
 * - 簇圆角 14dp（与键 12dp 成内嵌层次）、左右簇间距 8dp（分隔线撑开）。
 *
 * 配色（对比度全部 WCAG 实算，见 [XuanLanPalette]）：可用墨 #38566A（浅 6.95:1）、
 * 禁用 #6E6E73（浅 4.54:1，描边降到 1.4 做副线）、主页键玄青雾 #E7EEF0（6.61:1，
 * 整栏唯一填充键）、加载中刷新键同位变红 ⨯「停止」#C0392B（浅 4.87:1）。
 *
 * 交互：按下 scale(.94) + 底色深一档（120ms）；焦点态 2px #007AFF 外框；
 * 四键 contentDescription = 后退/前进/刷新（加载中→停止）/主页（zh+en 对称）。
 *
 * 分组语义（主稿决策表 3）：左簇 = 历史栈（半透明 + 细描边，「走过去/走过来」），
 * 右簇 = 当前页（实白 + 略粗描边 + 1dp 阴影，「对当前页动手/走人」）。
 */
@Composable
fun BrowserNavCapsule(
    canBack: Boolean,
    canForward: Boolean,
    loading: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onRefreshOrStop: () -> Unit,
    onHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = xuanLanPalette()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp) // 48 键 + 上下各 4dp，布局承载不靠 padding
            .padding(horizontal = 12.dp), // 水平内缩 = 浮动感（探索稿 B）
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        // ── 左簇：历史栈（次要：半透明 + 细描边 1dp @ 12% 玄青） ──
        NavCluster(
            backgroundColor = palette.clusterSecondaryBg,
            strokeColor = palette.clusterStrokeSecondary,
            borderWidth = 1.dp,
            elevation = 0.dp,
        ) {
            CapsuleKey(
                contentDescription = stringResource(R.string.browser_back),
                enabled = canBack,
                palette = palette,
                onClick = onBack,
            ) { tint, stroke ->
                BackGlyph(tint = tint, strokeWidth = stroke)
            }
            CapsuleKey(
                contentDescription = stringResource(R.string.browser_forward),
                enabled = canForward,
                palette = palette,
                onClick = onForward,
            ) { tint, stroke ->
                ForwardGlyph(tint = tint, strokeWidth = stroke)
            }
        }

        // ── 中间分隔线：1×20dp（主稿「视觉锚点」） ──
        Box(
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .width(1.dp)
                .height(20.dp)
                .background(palette.divider),
        )

        // ── 右簇：当前页（主要：实白 + 略粗描边 1.4dp @ 20% 玄青 + 1dp 阴影） ──
        NavCluster(
            backgroundColor = palette.clusterPrimaryBg,
            strokeColor = palette.clusterStrokePrimary,
            borderWidth = 1.4.dp,
            elevation = 1.dp,
        ) {
            CapsuleKey(
                // 加载中：刷新键原位变身「停止」（主稿决策表 6）
                contentDescription = stringResource(
                    if (loading) R.string.browser_stop else R.string.browser_reload
                ),
                enabled = true,
                palette = palette,
                overrideTint = if (loading) palette.stopRed else null,
                onClick = onRefreshOrStop,
            ) { tint, stroke ->
                if (loading) {
                    StopGlyph(tint = tint, strokeWidth = stroke)
                } else {
                    RefreshGlyph(tint = tint, strokeWidth = stroke)
                }
            }
            CapsuleKey(
                contentDescription = stringResource(R.string.browser_home),
                enabled = true,
                palette = palette,
                // 主页键：整栏唯一填充键（玄青雾；深档玄青沉）
                fillColor = palette.homeFill,
                onClick = onHome,
            ) { tint, stroke ->
                HomeGlyph(tint = tint, strokeWidth = stroke)
            }
        }
    }
}

/** 胶囊内的一「段」（簇）：高 48dp、圆角 14dp，容纳两枚 48dp 键（内嵌层次）。
 *
 * @param borderWidth 描边宽度：左簇 1dp（细）/ 右簇 1.4dp（略粗）——主稿「次要/主要」双编码。
 * @param elevation 右簇 1dp 阴影（主稿：「主要」用实底 + 阴影）；0 = 不投影。
 */
@Composable
private fun NavCluster(
    backgroundColor: Color,
    strokeColor: Color,
    borderWidth: Dp,
    elevation: Dp,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .height(48.dp)
            .then(if (elevation > 0.dp) Modifier.shadow(elevation, shape) else Modifier)
            .clip(shape)
            .background(backgroundColor)
            .border(borderWidth, strokeColor, shape),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            content()
        }
    }
}

/**
 * 胶囊键：48×48 命中区、圆角 12、按下 scale(.94)+深一档（120ms）、焦点 2px #007AFF。
 *
 * @param fillColor 常态填充（主页键的玄青雾；null = 透明）
 * @param overrideTint 覆盖字形色（加载中停止键的红；null = 按 enabled 取墨色）
 */
@Composable
private fun CapsuleKey(
    contentDescription: String,
    enabled: Boolean,
    palette: XuanLanPalette,
    onClick: () -> Unit,
    fillColor: Color? = null,
    overrideTint: Color? = null,
    glyph: @Composable (tint: Color, strokeWidth: Dp) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "capsuleKeyScale",
    )
    val shape = RoundedCornerShape(12.dp)
    val tint = overrideTint ?: if (enabled) palette.ink else palette.inkDisabled
    // 禁用态：前景 4.54:1 + 描边降到 1.4 做副线（主稿决策表 2；不用 opacity 避免塌对比度）
    val strokeWidth = if (enabled) 1.9.dp else 1.4.dp
    Box(
        modifier = Modifier
            .size(48.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(
                when {
                    pressed -> palette.pressedOverlay
                    fillColor != null -> fillColor
                    else -> Color.Transparent
                }
            )
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) palette.focusRing else Color.Transparent,
                shape = shape,
            )
            .clickable(
                interactionSource = interaction,
                indication = null, // 按下反馈自己做（scale + 底色），不要涟漪盖住设计
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        glyph(tint, strokeWidth)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 自绘 glyph（22dp 画布，与 Tab 图标 / BackChevron 同一套笔：圆头描边）。
// 描边宽度可配：常态 1.9dp / 禁用 1.4dp（主稿决策表 2）。
// ─────────────────────────────────────────────────────────────────────────────

/** 后退「‹」：与 com.example.zhengdao.BackChevron 同形（本文件自包含，stroke 可配）。 */
@Composable
private fun BackGlyph(tint: Color, strokeWidth: Dp, side: Dp = 22.dp) {
    Canvas(modifier = Modifier.size(side)) {
        val w = size.width
        val h = size.height
        val p = Path().apply {
            moveTo(w * 0.66f, h * 0.12f)
            lineTo(w * 0.28f, h * 0.50f)
            lineTo(w * 0.66f, h * 0.88f)
        }
        drawPath(p, tint, style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** 前进「›」：后退的镜像。 */
@Composable
private fun ForwardGlyph(tint: Color, strokeWidth: Dp, side: Dp = 22.dp) {
    Canvas(modifier = Modifier.size(side)) {
        val w = size.width
        val h = size.height
        val p = Path().apply {
            moveTo(w * 0.34f, h * 0.12f)
            lineTo(w * 0.72f, h * 0.50f)
            lineTo(w * 0.34f, h * 0.88f)
        }
        drawPath(p, tint, style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** 刷新「↻」：圆环 + 环右上折角箭头。 */
@Composable
private fun RefreshGlyph(tint: Color, strokeWidth: Dp, side: Dp = 22.dp) {
    Canvas(modifier = Modifier.size(side)) {
        val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
        val w = size.width
        val h = size.height
        val r = w * 0.32f
        val cx = w * 0.5f
        val cy = h * 0.5f
        drawCircle(color = tint, radius = r, center = androidx.compose.ui.geometry.Offset(cx, cy), style = stroke)
        val p = Path().apply {
            moveTo(cx + r * 0.55f, cy - r * 1.15f)
            lineTo(cx + r * 1.05f, cy - r * 0.70f)
            lineTo(cx + r * 0.50f, cy - r * 0.25f)
        }
        drawPath(p, tint, style = stroke)
    }
}

/** 停止「⨯」：两笔对角（加载中刷新键的第二形态）。 */
@Composable
private fun StopGlyph(tint: Color, strokeWidth: Dp, side: Dp = 22.dp) {
    Canvas(modifier = Modifier.size(side)) {
        val w = size.width
        val h = size.height
        val m = w * 0.26f
        drawLine(tint, androidx.compose.ui.geometry.Offset(m, m), androidx.compose.ui.geometry.Offset(w - m, h - m), strokeWidth.toPx(), StrokeCap.Round)
        drawLine(tint, androidx.compose.ui.geometry.Offset(w - m, m), androidx.compose.ui.geometry.Offset(m, h - m), strokeWidth.toPx(), StrokeCap.Round)
    }
}

/** 主页「⌂」：屋顶 + 屋身 + 门（描边笔法，与其余 glyph 同套）。 */
@Composable
private fun HomeGlyph(tint: Color, strokeWidth: Dp, side: Dp = 22.dp) {
    Canvas(modifier = Modifier.size(side)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val house = Path().apply {
            moveTo(w * 0.50f, h * 0.12f)
            lineTo(w * 0.88f, h * 0.44f)
            lineTo(w * 0.88f, h * 0.86f)
            lineTo(w * 0.12f, h * 0.86f)
            lineTo(w * 0.12f, h * 0.44f)
            close()
        }
        drawPath(house, tint, style = stroke)
        // 门
        val door = Path().apply {
            moveTo(w * 0.42f, h * 0.86f)
            lineTo(w * 0.42f, h * 0.58f)
            lineTo(w * 0.58f, h * 0.58f)
            lineTo(w * 0.58f, h * 0.86f)
        }
        drawPath(door, tint, style = stroke)
    }
}
