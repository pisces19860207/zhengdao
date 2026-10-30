// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百八十七批（2026-10-30）：律令页「文件操作记录（近 30 天）」卡。
//
// 为什么要有这张卡：287 把工作区降级成"默认落点"，能碰的地方变成全盘；
// 安全的三道下限里，「可审计」必须是**用户看得见的事实**而不是口号——
// 每一笔读/写/删/移/列/搜都落一行（路径＋用途），30 天可回放，过期自动清。
//
// 为什么单开一个文件：设置页已经 1000+ 行，而这张卡自带「读库 → 列表」的小闭环
// （IO 线程 + 三态），塞进去只会更难读。房型照 `BaopuArtifactShelf`。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.baopu.BaopuRuntime
import com.example.zhengdao.baopu.FileOpEntry
import com.example.zhengdao.baopu.FileOpLog
import com.example.zhengdao.core.TimeFormats
import com.example.zhengdao.ui.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 卡上最多列多少行（再多的部分只报总数——这一页是"看得见"，不是"翻账本工具"）。 */
private const val SHOWN_ROWS = 30

/**
 * 文件操作记录卡（287）：最近 [SHOWN_ROWS] 笔 ＋ 总数。
 *
 * 三态：读取中 / 空（一句解释）/ 有行（时间 ＋ 动作 ＋ 路径 ＋ 用途）。
 * 进页面时顺手把账本装到本机库——用户可能先开设置、后跑抱朴（那时引擎还没建）。
 */
@Composable
internal fun FileOpCard(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var rows by remember { mutableStateOf<List<FileOpEntry>>(emptyList()) }
    var total by remember { mutableStateOf(0) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val loadedRows = withContext(Dispatchers.IO) {
            runCatching { BaopuRuntime.installFileOpLog(ctx) }
            FileOpLog.recent(SHOWN_ROWS) to FileOpLog.count()
        }
        rows = loadedRows.first
        total = loadedRows.second
        loaded = true
    }

    SectionCard(title = stringResource(R.string.baopu_file_op_card)) {
        Column(modifier = modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.baopu_file_op_desc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                !loaded -> Text(
                    text = stringResource(R.string.baopu_file_op_loading),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )

                rows.isEmpty() -> Text(
                    text = stringResource(R.string.baopu_file_op_empty),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )

                else -> {
                    rows.forEach { e ->
                        Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                            Text(
                                text = "${TimeFormats.minuteStamp(e.at)} · ${e.kind.label} · ${e.purpose}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = e.path,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (total > rows.size) {
                        Text(
                            text = stringResource(R.string.baopu_file_op_more, rows.size, total),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}
