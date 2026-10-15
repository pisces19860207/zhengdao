// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.ui.browser

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.zhengdao.R

/**
 * 玄览起始页（第㊱刀）：空态不再是一行灰字，而是浏览器式起始页。
 *
 * 背景（用户 2026-10-15 原话）：「玄览页一进入可以做成现在那些浏览器的样式吗？
 * 中间一个搜索框，而不是一个空白页，都不知道该点哪里，以为是坏的。」
 *
 * 设计依据：`证道设计报告/证道-玄览UI设计稿-2026-10-14-v1.1.html` 空态起始页 +
 * 本刀派工单（台账 §B 顺序 56）。取色一律走 [xuanLanPalette]（深浅自动镜像，
 * 对比度沿用该文件已实算的 WCAG 数字），不引入新色。
 *
 * 结构（垂直居中一组）：
 * 1. 眼睛图腾 —— Canvas 自绘 52dp、1.6dp 圆头描边（与 WarningGlyph 同一套笔法）；
 * 2. 「玄 览」标识字 —— 17sp、字距 4sp；
 * 3. 一行说明文案；
 * 4. **居中搜索框** —— 44dp 全圆胶囊、实底 + 发丝描边、聚焦换焦点框；
 * 5. 快捷入口 3 枚 chip（必应 / Debian 文档 / 项目仓库），点击等价于在搜索框
 *    提交该 URL。
 *
 * 纪律（派工单②③红线，也是 BrowserStartPageGateTest 的钉点）：
 * - 本组件是**纯 UI + 回调**：不持有引擎、不做 I/O、不碰状态机；
 * - 输入一律经 [onSearch] 回给 BrowserScreen，由它走既有的归一化与导航路径
 *   （tryNavigate + normalizeInput），本文件**不得**出现第二套解析；
 * - 快捷入口只是预填好的字符串，与手输同一条路，无特权。
 */
@Composable
fun BrowserStartPage(
    onSearch: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = xuanLanPalette()
    var query by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 1. 眼睛图腾（设计稿空态：52dp、1.6dp 描边、玄青墨）
        EyeGlyph(tint = palette.ink, side = 52.dp)
        Spacer(Modifier.height(14.dp))

        // 2. 「玄 览」标识字
        Text(
            text = stringResource(R.string.browser_start_title),
            fontSize = 17.sp,
            letterSpacing = 4.sp,
            color = palette.ink,
        )
        Spacer(Modifier.height(6.dp))

        // 3. 说明文案
        Text(
            text = stringResource(R.string.browser_start_hint),
            fontSize = 13.sp,
            color = palette.inkDisabled,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(22.dp))

        // 4. 居中搜索框（44dp 全圆胶囊；实底 + 发丝描边，聚焦换焦点框）
        val borderColor = if (focused) palette.focusRing else palette.stripStroke
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = palette.clusterPrimaryBg,
            border = BorderStroke(1.dp, borderColor),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 360.dp)
                .height(44.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchGlyph(tint = palette.ink, side = 16.dp)
                Spacer(Modifier.width(8.dp))
                Box(modifier = Modifier.weight(1f)) {
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = palette.ink),
                        cursorBrush = SolidColor(palette.ink),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                val input = query
                                if (input.isNotBlank()) onSearch(input)
                            },
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { focused = it.isFocused },
                    )
                    if (query.isEmpty()) {
                        Text(
                            text = stringResource(R.string.browser_start_search_hint),
                            style = MaterialTheme.typography.bodyLarge,
                            color = palette.inkDisabled,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))

        // 5. 快捷入口（2~4 枚 chip；点击 = 提交该 URL，与手输同一条路）
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StartPageChip(
                label = stringResource(R.string.browser_start_shortcut_bing),
                palette = palette,
                onClick = { onSearch(BrowserStartPageLinks.BING) },
            )
            StartPageChip(
                label = stringResource(R.string.browser_start_shortcut_debian),
                palette = palette,
                onClick = { onSearch(BrowserStartPageLinks.DEBIAN_DOC) },
            )
            StartPageChip(
                label = stringResource(R.string.browser_start_shortcut_repo),
                palette = palette,
                onClick = { onSearch(BrowserStartPageLinks.REPO) },
            )
        }
    }
}

/** 快捷入口目标 URL（明文常量：点击 chip 等价于手输这些地址，无特权路径）。 */
internal object BrowserStartPageLinks {
    /** 必应国内版（与 BrowserScreen 主页键同源，免翻墙）。 */
    const val BING = "https://cn.bing.com"

    /** Debian 官方文档（玄览的宿主是 Debian 环境，常用查阅入口）。 */
    const val DEBIAN_DOC = "https://www.debian.org/doc/"

    /** 项目仓库。 */
    const val REPO = "https://github.com/pisces19860207/zhengdao"
}

/** 快捷入口 chip：雾底 + 墨字 + 全圆角（设计稿「当前引擎 chip」同款视觉）。 */
@Composable
private fun StartPageChip(
    label: String,
    palette: XuanLanPalette,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = palette.homeFill,
        border = BorderStroke(1.dp, palette.stripStroke),
        onClick = onClick,
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = palette.ink,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/**
 * 眼睛图腾（设计稿空态）：双弧线眼眶 + 实心瞳。
 * Canvas 自绘、圆头描边——与提示条小图标（WarningGlyph）同一套笔法，零额外依赖。
 */
@Composable
private fun EyeGlyph(tint: androidx.compose.ui.graphics.Color, side: androidx.compose.ui.unit.Dp) {
    Canvas(modifier = Modifier.size(side)) {
        val w = size.width
        val h = size.height
        val stroke = 1.6.dp.toPx()
        // 眼眶：上弧 + 下弧（贝塞尔，与设计稿 M2.5 12 Q12 4.5 21.5 12 同形）
        val upper = Path().apply {
            moveTo(w * 0.10f, h * 0.50f)
            cubicTo(w * 0.28f, h * 0.10f, w * 0.72f, h * 0.10f, w * 0.90f, h * 0.50f)
        }
        val lower = Path().apply {
            moveTo(w * 0.10f, h * 0.50f)
            cubicTo(w * 0.28f, h * 0.90f, w * 0.72f, h * 0.90f, w * 0.90f, h * 0.50f)
        }
        drawPath(upper, tint, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(lower, tint, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        // 瞳：实心圆
        drawCircle(tint, radius = w * 0.125f, center = Offset(w * 0.50f, h * 0.50f))
    }
}

/** 放大镜（搜索框前缀）：圆 + 柄，圆头描边。 */
@Composable
private fun SearchGlyph(tint: androidx.compose.ui.graphics.Color, side: androidx.compose.ui.unit.Dp) {
    Canvas(modifier = Modifier.size(side)) {
        val w = size.width
        val h = size.height
        val stroke = 1.9.dp.toPx()
        drawCircle(tint, radius = w * 0.28f, center = Offset(w * 0.42f, h * 0.42f), style = Stroke(stroke, cap = StrokeCap.Round))
        drawLine(
            tint,
            Offset(w * 0.62f, h * 0.62f),
            Offset(w * 0.88f, h * 0.88f),
            stroke,
            StrokeCap.Round,
        )
    }
}
