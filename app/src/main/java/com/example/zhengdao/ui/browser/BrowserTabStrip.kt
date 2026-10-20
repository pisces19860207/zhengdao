package com.example.zhengdao.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R

/**
 * 玄览标签条（第⑨刀 B · 阶段 2 §4.2 多标签 UI）。
 *
 * 视觉体系与底栏（v1.1 胶囊）同一套：令牌走 [xuanLanPalette]（玄青墨 / 雾底 / 发丝线），
 * 圆角 12dp 与键圆角同档，图标 Canvas 自绘（与 BackChevron / 底栏 glyph 同笔）。
 *
 * 触摸目标纪律（P2-10 同款）：标签 chip 自身可点（选标签）；关闭钮用 M3 IconButton
 * （库存组件自带 minimumInteractiveComponentSize ⇒ 视觉小、触摸扩到 48dp）；新建钮同理。
 *
 * 超出上限（20）时 [onAdd] 仍会被调用——由调用方决定提示（BrowserScreen 给一条可关消息），
 * 组件本身不吞。
 */
@Composable
fun BrowserTabStrip(
    tabs: List<BrowserTabManager.Tab>,
    activeId: Int?,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = xuanLanPalette()
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .background(palette.stripBg)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(tabs, key = { it.id }) { tab ->
            BrowserTabChip(
                title = tab.state.title.ifEmpty {
                    tab.state.url.ifEmpty { "" }
                },
                loading = tab.state.loading,
                active = tab.id == activeId,
                palette = palette,
                onSelect = { onSelect(tab.id) },
                onClose = { onClose(tab.id) },
            )
        }
        item {
            IconButton(onClick = onAdd) {
                PlusGlyph(tint = palette.ink, contentDescription = stringResource(R.string.browser_new_tab))
            }
        }
    }
}

@Composable
private fun BrowserTabChip(
    title: String,
    loading: Boolean,
    active: Boolean,
    palette: XuanLanPalette,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val bg = if (active) palette.homeFill else palette.clusterPrimaryBg
    val stroke = if (active) palette.clusterStrokePrimary else palette.stripStroke
    Row(
        modifier = Modifier
            .height(40.dp)
            .clip(shape)
            .background(bg)
            .border(1.dp, stroke, shape)
            .clickable(onClick = onSelect)
            .padding(start = 12.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title.ifEmpty { stringResource(R.string.browser_new_tab) },
            color = palette.ink,
            style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 150.dp),
        )
        if (loading) {
            Spacer(Modifier.width(6.dp))
            // 加载中的小转圈（与底栏刷新键同色系；16dp 不抢标题）
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                color = palette.ink,
                strokeWidth = 1.6.dp,
            )
        }
        Spacer(Modifier.width(2.dp))
        IconButton(onClick = onClose) {
            CloseGlyph(
                tint = palette.ink,
                contentDescription = stringResource(R.string.browser_close_tab),
            )
        }
    }
}

/** 「+」：两笔十字（与底栏 glyph 同笔：圆头 1.9f 描边）。 */
@Composable
private fun PlusGlyph(tint: Color, contentDescription: String?, side: androidx.compose.ui.unit.Dp = 22.dp) {
    androidx.compose.foundation.Canvas(modifier = Modifier.size(side)) {
        val w = size.width
        val h = size.height
        val m = w * 0.30f
        drawLine(tint, androidx.compose.ui.geometry.Offset(m, h / 2), androidx.compose.ui.geometry.Offset(w - m, h / 2), 1.9.dp.toPx(), StrokeCap.Round)
        drawLine(tint, androidx.compose.ui.geometry.Offset(w / 2, m), androidx.compose.ui.geometry.Offset(w / 2, h - m), 1.9.dp.toPx(), StrokeCap.Round)
    }
}

/** 「×」：关闭（BrowserScreen 提示条关闭钮同款笔法，这里自包含一份）。 */
@Composable
private fun CloseGlyph(tint: Color, contentDescription: String?, side: androidx.compose.ui.unit.Dp = 18.dp) {
    androidx.compose.foundation.Canvas(modifier = Modifier.size(side)) {
        val w = size.width
        val h = size.height
        val m = w * 0.24f
        drawLine(tint, androidx.compose.ui.geometry.Offset(m, m), androidx.compose.ui.geometry.Offset(w - m, h - m), 1.9.dp.toPx(), StrokeCap.Round)
        drawLine(tint, androidx.compose.ui.geometry.Offset(w - m, m), androidx.compose.ui.geometry.Offset(m, h - m), 1.9.dp.toPx(), StrokeCap.Round)
    }
}
