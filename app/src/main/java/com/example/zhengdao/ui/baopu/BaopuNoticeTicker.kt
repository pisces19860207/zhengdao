// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：用户 2026-10-18 逐字「这次学到和已压缩放到待命中的下面，在待命中的下面做一个
// 循环播放的那种框，点到哪条，就展开哪条信息。」
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** 轮播框里的一条：标题（短）× 正文（点开才看）。 */
internal data class NoticeItem(val label: String, val body: String)

/** 每条轮播停留时长（毫秒）。3 秒够读完一行标题，又不至于翻得太快。 */
private const val TICKER_INTERVAL_MS = 3_000L

/**
 * 「这次学到 / 已压缩」轮播框（2026-10-18 用户定稿）。
 *
 * 口径：
 * 1. 固定在「待命中」那行下面（**不跟着正文滚**），只占一行高度；
 * 2. 多条时**循环播放**，每条停 [TICKER_INTERVAL_MS]；
 * 3. **点到哪条就展开哪条**：点当前这条 → 展开正文；再点 → 收回并继续轮播；
 * 4. 展开期间**不轮播**（正在读的东西不许被换走）；
 * 5. 只有一条时不轮播（没什么可轮的），但照样可点开。
 */
@Composable
internal fun BaopuNoticeTicker(
    items: List<NoticeItem>,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    var index by remember(items.size) { mutableStateOf(0) }
    var expanded by remember(items.size) { mutableStateOf(false) }
    // 条目减少时把下标拉回合法范围（避免越界闪一下空白）。
    if (index > items.lastIndex) index = items.lastIndex
    val current = items[index]

    // 展开时不轮播；只有一条也不必轮播。
    LaunchedEffect(index, expanded, items.size) {
        if (!expanded && items.size > 1) {
            delay(TICKER_INTERVAL_MS)
            index = (index + 1) % items.size
        }
    }

    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .clickable {
                if (expanded) {
                    expanded = false
                } else {
                    expanded = true
                }
            },
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = current.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!expanded) {
                    Text(
                        text = current.body,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (expanded) {
                // 展开态**限高 + 内部可滚**：便签正文可能很长（压缩摘要有几千字），
                // 而这块是固定区——不限高会把整屏顶飞（2026-10-18 真机实测后补）。
                Text(
                    text = current.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .heightIn(max = 160.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}
