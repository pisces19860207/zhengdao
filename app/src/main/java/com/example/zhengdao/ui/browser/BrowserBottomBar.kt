// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.ui.browser

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R

/**
 * 玄览底栏（**一条** 56dp 带子）—— 底栏改版定稿 2026-10-18（`证道设计报告/玄览-底栏改版-定稿与施工单-2026-10-18.md`）。
 *
 * 取代第⑨刀 C 的两条带子（50dp 地址栏带 ＋ 56dp 浮动胶囊栏 ＝ 106dp 定稿）。
 * **106dp 定稿作废**：壳子从 158dp（52＋50＋56）降到 **108dp**（52＋56），直板 374.9×813.7dp
 * 的页面可视从 554dp（68%）涨到 **604dp（74%）**；地址焦点从屏幕 15% 高度挪到 90%（拇指区）。
 *
 * 键位（用户拍板「方案二甲 · 五件全可见」）：
 * `‹〔›〕[地址胶囊] ↻/⨯ ⋯` —— 常态 4 件可见，有前进历史时 5 件。
 * - 「前往」**删除**：与键盘 Go 键完全同义（一条带子两个提交按钮）；
 * - 前进 **改按需出现**（`canForward`，判据 `engine.canGoForward()`）：没有前进历史时不摆一颗灰死的键；
 * - 主页 **收进 ⋯ 菜单第一项**（落点 `HOME_URL` 不变，住在 BrowserScreen）；
 * - ⋯ 菜单**只放代码里已有的三个动作**（主页 / 新标签 / 关闭当前标签）——分享、页内查找、
 *   桌面版站点现在根本没有，写进菜单＝凭空承诺。
 *
 * 几何（全部可照搬；机器数按 374.9dp 直板实算，胶囊是 `weight(1f)` 自适应不是写死宽）：
 * 栏高 **56dp**（布局承载，不靠 padding 撑）、水平内缩 **12dp**、件间距 **4dp**、
 * 键 **48×48dp** 圆角 **12dp**、地址胶囊高 **40dp** 圆角 **20dp**（全圆）；
 * 胶囊宽常态 374.9−24−(48×3＋4×3) = **195dp**，有前进时 374.9−24−(48×4＋4×4) = **143dp**。
 *
 * 地址胶囊两态（定稿 §6）：常态只显示 host（＋次级色 path），点开编辑态才铺完整 URL ——
 * 判据是 [BrowserUrlPolicy.displayHost] / [displayPath] / [isSecure]（纯函数，进 JVM 单测）。
 *
 * 配色沿用 [XuanLanPalette]（WCAG 实算，见 [XuanLanPaletteContrastTest]）：可用墨 #38566A
 * 浅 6.95:1 / 深 11.87:1，禁用 #6E6E73 浅 4.54:1，停止红 #C0392B 浅 4.87:1；
 * 交互：按下 scale(.94)（120ms）、焦点态 2px #007AFF、禁用态描边从 1.9 降到 1.4（不用 opacity，避免塌对比度）。
 *
 * 字号走 [MaterialTheme.typography] 的既有档位（B-补-18 阶梯）：展示态 `bodySmall`（13sp，
 * 设计稿 13px）、编辑态 `bodyMedium`（15sp，打字要能看清）——不新写死字号。
 */
@Composable
fun BrowserBottomBar(
    displayUrl: String,
    editValue: String,
    isEditing: Boolean,
    canBack: Boolean,
    canForward: Boolean,
    loading: Boolean,
    canCloseTab: Boolean,
    onEditStart: () -> Unit,
    onEditEnd: () -> Unit,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onRefreshOrStop: () -> Unit,
    onHome: () -> Unit,
    onNewTab: () -> Unit,
    onCloseTab: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = xuanLanPalette()
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp) // 定稿：48 键 + 上下各 4dp，由栏高承载，不用 padding 撑高
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // ── 后退（常驻） ──
        BottomKey(
            contentDescription = stringResource(R.string.browser_back),
            enabled = canBack,
            palette = palette,
            onClick = onBack,
        ) { tint, stroke ->
            BackGlyph(tint = tint, strokeWidth = stroke)
        }

        // ── 前进：按需出现（定稿：没有前进历史不摆灰死的键） ──
        if (canForward) {
            BottomKey(
                contentDescription = stringResource(R.string.browser_forward),
                enabled = true,
                palette = palette,
                onClick = onForward,
            ) { tint, stroke ->
                ForwardGlyph(tint = tint, strokeWidth = stroke)
            }
        }

        // ── 地址胶囊（唯一自适应件） ──
        AddressPill(
            displayUrl = displayUrl,
            editValue = editValue,
            isEditing = isEditing,
            palette = palette,
            onEditStart = onEditStart,
            onEditEnd = onEditEnd,
            onValueChange = onValueChange,
            onSubmit = onSubmit,
        )

        // ── 刷新 / 停止：同位变身（加载中红色 ⨯） ──
        BottomKey(
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

        // ── ⋯ 菜单（只放已有动作；动作本体在 BrowserScreen，本件只发回调） ──
        Box {
            BottomKey(
                contentDescription = stringResource(R.string.browser_menu),
                enabled = true,
                palette = palette,
                highlight = menuOpen,
                onClick = { menuOpen = !menuOpen },
            ) { tint, stroke ->
                MenuGlyph(tint = tint, strokeWidth = stroke)
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                shape = RoundedCornerShape(16.dp),
                containerColor = palette.clusterPrimaryBg,
                modifier = Modifier.widthIn(min = 180.dp),
            ) {
                MenuEntry(
                    label = stringResource(R.string.browser_home),
                    palette = palette,
                    onClick = {
                        menuOpen = false
                        onHome()
                    },
                )
                MenuEntry(
                    label = stringResource(R.string.browser_new_tab),
                    palette = palette,
                    onClick = {
                        menuOpen = false
                        onNewTab()
                    },
                )
                MenuEntry(
                    label = stringResource(R.string.browser_close_tab),
                    palette = palette,
                    enabled = canCloseTab,
                    onClick = {
                        menuOpen = false
                        onCloseTab()
                    },
                )
            }
        }
    }
}

/**
 * 地址胶囊：高 40dp、圆角 20dp（全圆），`weight(1f)` 吃掉了键与间距之后的剩余宽度。
 *
 * 两态（定稿 §6）——
 * - **展示态**：`[锁] host` ＋ 次级色 path，居中；点它进编辑态；
 * - **编辑态**：完整 URL 左对齐可改，键盘 Go 提交（提交＝既有唯一入口 `onSubmit`，
 *   本件自己**不解析、不导航**，与起始页同一口径）。
 *
 * 空 URL（新标签 / 0 标签空态）展示 `browser_url_hint`，避免出现一个空白胶囊看着像坏了。
 */
@Composable
private fun RowScope.AddressPill(
    displayUrl: String,
    editValue: String,
    isEditing: Boolean,
    palette: XuanLanPalette,
    onEditStart: () -> Unit,
    onEditEnd: () -> Unit,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val host = BrowserUrlPolicy.displayHost(displayUrl)
    val path = BrowserUrlPolicy.displayPath(displayUrl)
    val secure = BrowserUrlPolicy.isSecure(displayUrl)

    // 进编辑态才抢焦点（isEditing 由调用方决定，本件不自己偷偷弹键盘）
    LaunchedEffect(isEditing) {
        if (isEditing) focusRequester.requestFocus()
    }

    Surface(
        modifier = Modifier
            .weight(1f)
            .height(40.dp)
            .clip(shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                enabled = !isEditing,
                onClick = onEditStart,
            ),
        shape = shape,
        color = palette.clusterPrimaryBg,
        // 编辑态描边升到焦点蓝 2dp（与键的焦点框同一语言）；常态发丝线 1dp
        border = if (isEditing || focused) {
            BorderStroke(2.dp, palette.focusRing)
        } else {
            BorderStroke(1.dp, palette.stripStroke)
        },
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp),
            contentAlignment = if (isEditing) Alignment.CenterStart else Alignment.Center,
        ) {
            if (isEditing) {
                BasicTextField(
                    value = editValue,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = palette.ink),
                    cursorBrush = SolidColor(palette.ink),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(
                        onGo = {
                            // 先收键盘与编辑态，再走唯一提交入口（BACK 键收键盘是禁手，见交付报告纪律）
                            focusManager.clearFocus(force = true)
                            onEditEnd()
                            onSubmit()
                        },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
            } else if (displayUrl.isBlank()) {
                Text(
                    text = stringResource(R.string.browser_url_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkDisabled,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    LockGlyph(tint = palette.inkDisabled, crossed = !secure, side = 14.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = host,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (path.isNotEmpty()) {
                        // path 用禁用墨（4.54:1，仍过 AA）：它是次要信息，不许和 host 抢层级
                        Text(
                            text = path,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.inkDisabled,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** 菜单项（ColumnScope 里的一行）。默认墨色换成设计令牌，禁用态走 inkDisabled。 */
@Composable
private fun ColumnScope.MenuEntry(
    label: String,
    palette: XuanLanPalette,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) palette.ink else palette.inkDisabled,
            )
        },
        enabled = enabled,
        onClick = onClick,
    )
}

/**
 * 底栏键：48×48 命中区、圆角 12、按下 scale(.94)+底色深一档（120ms）、焦点 2px #007AFF。
 *
 * @param highlight 常驻高亮底（⋯ 键在菜单展开时用它表明"这层是我打开的"）
 * @param overrideTint 覆盖字形色（加载中停止键的红；null = 按 enabled 取墨色）
 */
@Composable
private fun BottomKey(
    contentDescription: String,
    enabled: Boolean,
    palette: XuanLanPalette,
    onClick: () -> Unit,
    highlight: Boolean = false,
    overrideTint: Color? = null,
    glyph: @Composable (tint: Color, strokeWidth: Dp) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "bottomKeyScale",
    )
    val shape = RoundedCornerShape(12.dp)
    val tint = overrideTint ?: if (enabled) palette.ink else palette.inkDisabled
    // 禁用态：前景 4.54:1 + 描边降到 1.4 做副线（第⑨刀口径，不用 opacity 避免塌对比度）
    val strokeWidth = if (enabled) 1.9.dp else 1.4.dp
    Box(
        modifier = Modifier
            .size(48.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(
                when {
                    pressed -> palette.pressedOverlay
                    highlight -> palette.pressedOverlay
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
// 自绘 glyph（22dp 画布，与 Tab 图标 / BackChevron 同一套笔：圆头描边，宽度可配）。
// 本项目图标一律 Canvas 自绘，不引 material-icons。
// ─────────────────────────────────────────────────────────────────────────────

/** 后退「‹」：与 com.example.zhengdao.BackChevron 同形。 */
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
        drawCircle(color = tint, radius = r, center = Offset(cx, cy), style = stroke)
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
        drawLine(tint, Offset(m, m), Offset(w - m, h - m), strokeWidth.toPx(), StrokeCap.Round)
        drawLine(tint, Offset(w - m, m), Offset(m, h - m), strokeWidth.toPx(), StrokeCap.Round)
    }
}

/** 「⋯」菜单：三个实心点（笔宽只用来对齐禁用态的视觉重量，点在禁用时同样降档）。 */
@Composable
private fun MenuGlyph(tint: Color, strokeWidth: Dp, side: Dp = 22.dp) {
    Canvas(modifier = Modifier.size(side)) {
        val w = size.width
        val h = size.height
        // 点径 = 笔宽的一半：1.9dp 笔 → 0.95dp 半径，与其余 glyph 同一墨量
        val r = strokeWidth.toPx() * 0.5f
        listOf(0.22f, 0.50f, 0.78f).forEach { fx ->
            drawCircle(tint, radius = r, center = Offset(w * fx, h * 0.50f))
        }
    }
}

/**
 * 锁形（地址胶囊用，14dp）：锁体圆角方 + 锁环半圆 + 两条立柱。
 *
 * @param crossed true = 非 https，画一道斜杠（"不加密"要比"没图标"更明确；
 *                这是图形，3:1 即过，所以用 inkDisabled 也够）
 */
@Composable
private fun LockGlyph(tint: Color, crossed: Boolean, side: Dp = 14.dp) {
    Canvas(modifier = Modifier.size(side)) {
        val w = size.width
        val h = size.height
        val pen = 1.4.dp.toPx()
        val stroke = Stroke(width = pen, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val bodyTop = h * 0.44f
        // 锁体
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.20f, bodyTop),
            size = Size(w * 0.60f, h * 0.44f),
            cornerRadius = CornerRadius(w * 0.10f, w * 0.10f),
            style = stroke,
        )
        // 锁环（上半圆）+ 两条立柱接到锁体上沿
        drawArc(
            color = tint,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.30f, h * 0.14f),
            size = Size(w * 0.40f, h * 0.34f),
            style = stroke,
        )
        val legTop = h * 0.31f
        drawLine(tint, Offset(w * 0.30f, legTop), Offset(w * 0.30f, bodyTop), pen, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.70f, legTop), Offset(w * 0.70f, bodyTop), pen, StrokeCap.Round)
        if (crossed) {
            drawLine(tint, Offset(w * 0.16f, h * 0.88f), Offset(w * 0.84f, h * 0.20f), pen, StrokeCap.Round)
        }
    }
}
