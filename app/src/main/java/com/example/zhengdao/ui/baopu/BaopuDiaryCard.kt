// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单第一百四十七批 checkpoint③（删除）＋《抱朴情景记忆设计方案》§5
// 真删五边界＋§9「设置页删除入口」。房型同 [BaopuPendingMemoryCard]：
// 同一个位置、同一只容器、同一套按钮——**换数据源，不让用户学两套界面**。
// 删除语义全在 [DiaryDeletionRules]（纯函数）与 [DiaryStore.realDelete]（执行），
// 本文件只做展示与确认：确认卡把「删日记，不删文件」说到字面上（边界②），
// 并把该批条目引用的产物路径逐条列出来给人核对（边界⑤）。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.baopu.BaopuRuntime
import com.example.zhengdao.baopu.DiaryDeletionPlan
import com.example.zhengdao.baopu.DiaryDeletionRules
import com.example.zhengdao.baopu.DiaryDeletionScope
import com.example.zhengdao.baopu.DiaryEntry
import com.example.zhengdao.baopu.DiaryRecall
import com.example.zhengdao.baopu.DiaryStore
import com.example.zhengdao.baopu.MemoryLayout
import com.example.zhengdao.rootfs.RunLog

/**
 * 设置页「日记」卡：看近期流水、按条删（真删）。
 *
 * @param entries 近期日记条目（调用方现读盘：日文件＋归档，倒序）
 * @param onDelete 确认后执行（计划已由 [DiaryDeletionRules.plan] 算好；
 *                 返回真删报告供调用方记日志/刷新）
 */
@Composable
internal fun BaopuDiaryCard(
    entries: List<DiaryEntry>,
    onDelete: (DiaryDeletionPlan) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<DiaryEntry?>(null) }
    val visible = visibleItems(entries, expanded = expanded, limit = CARD_VISIBLE_LIMIT)
    val hidden = moreCount(total = entries.size, shown = visible.size)
    Column(modifier.fillMaxWidth()) {
        if (entries.isEmpty()) {
            Text(
                text = stringResource(R.string.baopu_diary_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            return@Column
        }
        visible.forEach { e ->
            BaopuInfoContainer(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            ) {
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        text = e.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = e.date,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = e.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { confirming = e }) {
                        Text(stringResource(R.string.baopu_diary_delete))
                    }
                }
            }
        }
        if (hidden > 0) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(
                    if (expanded) {
                        stringResource(R.string.baopu_diary_collapse)
                    } else {
                        pluralStringResource(R.plurals.baopu_diary_more, hidden, hidden)
                    },
                )
            }
        }
    }
    // 确认卡（边界②⑤）：话头固定、产物路径逐条列
    val target = confirming
    if (target != null) {
        val plan = DiaryDeletionRules.plan(
            scope = DiaryDeletionScope.ENTRY,
            entries = entries,
            ids = listOf(target.id),
        )
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(R.string.baopu_diary_confirm_title)) },
            text = { Text(DiaryDeletionRules.confirmBody(plan)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(plan)
                    confirming = null
                }) {
                    // 边界②：确认键上就写着「删日记，不删文件」——八个字，一个字不改
                    Text(DiaryDeletionRules.CONFIRM_WORDS)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) {
                    Text(stringResource(R.string.baopu_diary_confirm_cancel))
                }
            },
        )
    }
}

/**
 * 设置页日记卡的现读盘＋真删接线（**页面零写盘**的例外只有这一处：
 * 用户本人按了确认——确认制最高一级，同技能归档删除的口径）。
 */
@Composable
internal fun rememberDiarySection(
    ctx: android.content.Context,
    reloadKey: Int,
): Pair<List<DiaryEntry>, (DiaryDeletionPlan) -> Unit> {
    val entries = remember(reloadKey) {
        // 157：DiaryStore 收「记忆根」，日记/归档两处它自己派生（路径真源 MemoryLayout）
        val root = MemoryLayout.memoryDir(BaopuRuntime.workspaceRoot(ctx))
        if (!root.isDirectory) {
            emptyList()
        } else {
            val store = DiaryStore(root, BaopuRuntime.db(ctx))
            (store.loadAll().entries + store.loadArchive())
                .filterNot { DiaryRecall.isDigest(it.id) }
                .sortedByDescending { it.timeToMs }
        }
    }
    val onDelete: (DiaryDeletionPlan) -> Unit = { plan ->
        val store = DiaryStore(
            MemoryLayout.memoryDir(BaopuRuntime.workspaceRoot(ctx)),
            BaopuRuntime.db(ctx),
        )
        val report = runCatching { store.realDelete(plan) }
            .onFailure {
                RunLog.log("抱朴日记：真删失败 ${it.javaClass.simpleName}: ${it.message}")
            }
            .getOrNull()
        if (report != null) {
            RunLog.log(
                "抱朴日记：真删 ${report.deletedEntries} 条（日记文件 ${report.deletedFiles}、" +
                    "归档 ${report.removedArchiveFiles}、摘要重算 ${report.rebuiltDigests}、" +
                    "索引行 ${report.deletedIndexRows}）；产物文件未动",
            )
        }
    }
    return entries to onDelete
}
