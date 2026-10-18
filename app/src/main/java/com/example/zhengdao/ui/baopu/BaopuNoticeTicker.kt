// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：用户 2026-10-18 逐字「这次学到和已压缩放到待命中和＋那一栏里，开一个滚动循环播放的小口子，
// 就像财经里面那种」「不滚动详情，只滚动标题这几个字，点到哪条就展开那条的详情」。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** 跑马灯里的一条：标题（短）× 详情（点标题才看）。 */
internal data class NoticeItem(val label: String, val body: String)

/** 跑马灯窗口高度（一行文字）。 */
private val TICKER_HEIGHT = 16.dp

/** 每帧滚 0.3px（≈18px/秒），慢到能看清、快过"卡住"的错觉。 */
private const val SCROLL_STEP_PX = 0.3f

/**
 * 「这次学到 / 已压缩」**标题竖向跑马灯**（2026-10-18 用户定稿，第四版）。
 *
 * 口径（用户逐条给的原话）：
 * 1. 位置＝顶栏那一行（「待命中」与「＋」之间），不另占一行；
 * 2. **只滚标题**（「这次学到」「已压缩」这种短标签），**详情不滚**；
 * 3. 标题首尾相接循环（复制两份，滚过半程瞬回起点，肉眼看是无限循环）；
 * 4. **点哪个标题就展开哪条的详情**（详情限高 180dp、内部可滚）；再点同一个收回。
 */
@Composable
internal fun BaopuNoticeTicker(
    items: List<NoticeItem>,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    var selected by remember(items) { mutableStateOf(-1) }
    val scroll = rememberScrollState()

    // 只滚标题：两份首尾相接实现无缝循环。
    val doubled = remember(items) { items + items }

    LaunchedEffect(doubled, selected) {
        if (selected >= 0) return@LaunchedEffect // 详情弹窗开着时不滚（正在读的不许被滚走）
        while (true) {
            val half = scroll.maxValue / 2
            if (half > 0) {
                scroll.scrollBy(SCROLL_STEP_PX)
                if (scroll.value >= half) scroll.scrollTo(0)
            }
            delay(16)
        }
    }

    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
            Column(Modifier.height(TICKER_HEIGHT).verticalScroll(scroll)) {
                doubled.forEachIndexed { i, item ->
                    val idx = i % items.size
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = if (selected == idx) -1 else idx },
                    )
                }
            }
            // 详情改**弹窗**（2026-10-18 用户：「点了过后能弹出来吗？再点就收回去」）：
            // 浮在内容上面，不撑开顶栏、不推正文；点外面空白处即收回。
            androidx.compose.material3.DropdownMenu(
                expanded = selected >= 0 && selected < items.size,
                onDismissRequest = { selected = -1 },
            ) {
                androidx.compose.foundation.layout.Column(
                    Modifier
                        .widthIn(max = 320.dp)
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    if (selected >= 0 && selected < items.size) {
                        Text(
                            text = items[selected].label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        )
                        Text(
                            text = items[selected].body,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
