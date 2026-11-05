// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百六十五批 · **卡面顶部「依据：…」那一行**（行动卡与答案卡共用同一份组件）。
//
// 为什么共用一个组件：两处都要「一行摘要 ＋ 点开看原文」，写两份迟早措辞、热区、
// 展开态各走各的。这里只读 [Citation] 数据，不查盘、不认业务字段——壳的纪律不破。
// 没有依据时一行都不画：查询类卡（物流单等）与改造前逐像素一致。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.baopu.cards.Citation

/** 卡面顶部一行依据；点一下展开每条的来源与原文。依据为空 ⇒ 一行都不画。 */
@Composable
internal fun BaopuCitationBar(
    citations: List<Citation>,
    line: String?,
    modifier: Modifier = Modifier,
) {
    if (citations.isEmpty() || line.isNullOrBlank()) return
    var open by remember(citations) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clickable(onClickLabel = stringResource(R.string.baopu_citation_open_desc)) {
                    open = !open
                }
                .padding(horizontal = BAO_ACTION_INSET_DP.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = line,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(
                    if (open) R.string.baopu_citation_close else R.string.baopu_citation_open,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (open) {
            citations.forEach { CitationDetail(it) }
        }
    }
}

/** 展开后的一条：标签 → 来源 → 原文（原文是点开这一下要看到的东西）。 */
@Composable
private fun CitationDetail(c: Citation) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = BAO_ACTION_INSET_DP.dp, vertical = 4.dp),
    ) {
        Text(
            text = c.display,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.baopu_citation_source, c.ref),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (c.excerpt.isNotBlank()) {
            Text(
                text = c.excerpt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
