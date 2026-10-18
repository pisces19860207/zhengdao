// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第二十批 §2.1（会话库——旧会话有了家）：列表 / 点开继续 / 删除（二次确认）。
// 承载方式照 BaopuSettingsScreen 同一模式（Dialog 全屏浮层，不新建 Activity——清单禁改）。
//
// 判据全在纯函数里（JVM 直测，见 BaopuSessionWorkspaceTest）：
// 排序 updated_at DESC ＋ 徽标（closed_at 非空 ⇒ 已封卷）＝K4；
// workspace 为 NULL ⇒ 「（工作区未记录）」且不许拿当前工作区填 ＝K3；
// 删除只在确认框的 confirmButton 里调 controller.deleteSession ＝K5（源码级断言）。
package com.example.zhengdao.ui.baopu

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.zhengdao.R
import com.example.zhengdao.ui.SettingsTopBar
import com.example.zhengdao.ui.theme.zhengdaoErrorText
import java.util.Locale

/** 会话库一行（纯展示；判据字段的映射见 [buildSessionRows]）。 */
internal data class SessionLibraryRow(
    val id: Long,
    val title: String,
    val timeText: String,
    val roundCount: Int,
    val sealed: Boolean,
    val workspace: String?,
)

/** 会话库一组（组标题 = 工作区路径缩略；workspace 为 null 的组排最后）。 */
internal data class SessionLibraryGroup(
    val header: String,
    val rows: List<SessionLibraryRow>,
)

/** 标题兜底的最后一环：库里没有 ⇒ 「未命名会话」（调 [displayTitle] 前数据层已补过首条用户消息前 20 字）。 */
internal fun displayTitle(rawTitle: String, fallback: String): String =
    rawTitle.trim().ifBlank { fallback }

/**
 * 人话时间（§2.1.2）：今天显示 HH:mm，更早显示 [datePattern]（zh = M月d日 / en = MMM d，
 * 从 strings 取——格式不写死中文）。
 *
 * HH:mm 复用 util/TimeFormats.hourMinute（B-补-24 的唯一真源）；日期分支的图案来自
 * strings（进程内固定），java.time 的 DateTimeFormatter 不可变无线程安全问题，
 * 冷路径逐次构造成本可忽略——主源码不再出现 SimpleDateFormat（TimeFormatsTest 门禁）。
 */
internal fun formatSessionTime(updatedAt: Long, now: Long, datePattern: String): String {
    if (updatedAt <= 0L) return ""
    val zone = java.time.ZoneId.systemDefault()
    val updatedDate = java.time.Instant.ofEpochMilli(updatedAt).atZone(zone).toLocalDate()
    val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return if (updatedDate == today) {
        com.example.zhengdao.util.TimeFormats.hourMinute(updatedAt)
    } else {
        java.time.format.DateTimeFormatter.ofPattern(datePattern, Locale.getDefault())
            .withZone(zone)
            .format(java.time.Instant.ofEpochMilli(updatedAt))
    }
}

/**
 * 会话行模型（K3/K4 判据本体）：按 updated_at DESC（防御性重排——SQL 已排，这里再钉一次）
 * ＋ 徽标（closed_at 非空 ⇒ 已封卷）＋ 标题兜底。
 */
internal fun buildSessionRows(
    entries: List<BaopuSessionEntry>,
    now: Long,
    datePattern: String,
    fallbackTitle: String,
): List<SessionLibraryRow> = entries
    .sortedByDescending { it.updatedAt }
    .map { e ->
        SessionLibraryRow(
            id = e.id,
            title = displayTitle(e.title, fallbackTitle),
            timeText = formatSessionTime(e.updatedAt, now, datePattern),
            roundCount = e.rounds,
            sealed = e.closedAt != null,
            workspace = e.workspace,
        )
    }

/**
 * 组标题缩略（§2.3.4）：长路径中段省略，保留前 12 后 12 字；26 字内原样。
 * （12+1+12 = 25，比 26 大才需要缩——26 含「…」占位刚好不超两行。）
 */
internal fun shortenPath(path: String): String {
    if (path.length <= 26) return path
    return path.take(12) + "…" + path.takeLast(12)
}

/**
 * 按工作区分组（第二十批 §2.3.4；第二十五批追加一 B 段改 NULL 组的归属）：
 * 组内保持 updated_at DESC；组间按「组内最新一条」的位置排（rows 已按 updated_at DESC，
 * 下标越小越新）；**workspace 为 NULL 的组排最前**、标题用 [unknownHeader]
 * （「默认工作区 · …/Download/证道」）——追加一 B 段口径：旧会话别再挂在孤儿组垫底，
 * 归到「默认工作区」名下但**不假装它们当时真在那个目录跑过**（组里每行另标「历史未记录」；
 * 库里 workspace 仍是 NULL，不回填）。放最前的理由：这批「没记工作区」的存量老会话正是
 * 用户「旧会话没了」焦虑的主体，垫底等于继续看不见。
 */
internal fun groupByWorkspace(
    rows: List<SessionLibraryRow>,
    unknownHeader: String,
): List<SessionLibraryGroup> {
    val known = rows.filter { !it.workspace.isNullOrBlank() }
    // 组间序锚：组内最新一行在 rows（updated_at DESC）里的下标——下标越小越新
    val keys = known.map { it.workspace!! }.distinct()
        .sortedBy { ws ->
            val first = known.first { it.workspace == ws }
            rows.indexOfFirst { r -> r.id == first.id }
        }
    val sortedGroups = keys.map { ws ->
        SessionLibraryGroup(shortenPath(ws), known.filter { it.workspace == ws })
    }
    val unknown = rows.filter { it.workspace.isNullOrBlank() }
    return if (unknown.isEmpty()) sortedGroups else listOf(SessionLibraryGroup(unknownHeader, unknown)) + sortedGroups
}

/**
 * 会话库全屏浮层（第二十批 §2.1）。
 *
 * 能开（点行接着聊；busy 拒切）、能删（行尾「删除」→ 二次确认框；busy 拒删）。
 * 空库一行「还没有聊过」。
 */
@Composable
internal fun BaopuSessionLibraryScreen(
    controller: BaopuChatController,
    onDismiss: () -> Unit,
) {
    var tick by remember { mutableStateOf(0) }
    val ctx = LocalContext.current
    val entries = remember(tick) { controller.loadSessionLibrary() }
    val busy = controller.busy
    val now = System.currentTimeMillis()
    val datePattern = stringResource(R.string.baopu_session_date_fmt)
    val fallbackTitle = stringResource(R.string.baopu_session_unnamed)
    // 追加一 B 段：NULL 工作区组归「默认工作区」（不回填库，只改展示层标题），
    // 组标题里的路径取 Workspace.defaultDir 的缩略；组里每行另标「历史未记录」小字。
    val unknownHeader = stringResource(
        R.string.baopu_session_ws_unknown_header,
        shortenPath(com.example.zhengdao.terminal.Workspace.defaultDir(ctx).absolutePath),
    )
    val unknownWs = stringResource(R.string.baopu_session_ws_unknown)
    // 二次确认（K5/D1）：删除动作只能从确认框的确认按钮进 controller.deleteSession——
    // 行尾按钮只置 askDelete，不直接删。
    var askDelete by remember { mutableStateOf<SessionLibraryRow?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                SettingsTopBar(title = stringResource(R.string.baopu_session_library_title), onBack = onDismiss)
                if (entries.isEmpty()) {
                    Text(
                        text = stringResource(R.string.baopu_session_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 32.dp),
                    )
                } else {
                    val groups = remember(tick, datePattern, fallbackTitle, unknownHeader) {
                        groupByWorkspace(
                            buildSessionRows(entries, now, datePattern, fallbackTitle),
                            unknownHeader,
                        )
                    }
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        groups.forEach { group ->
                            item(key = "h-${group.header}") {
                                Text(
                                    text = group.header,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
                                )
                            }
                            items(group.rows, key = { "r-${it.id}" }) { row ->
                                SessionLibraryRowItem(
                                    row = row,
                                    enabled = !busy,
                                    showUnknownMark = row.workspace.isNullOrBlank(),
                                    onOpen = {
                                        controller.openSession(row.id)
                                        onDismiss()
                                    },
                                    onDelete = { askDelete = row },
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 24.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                )
                            }
                        }
                        item { Spacer(Modifier.height(24.dp)) }
                    }
                }

                // ⚠️ 确认框必须组合在**浮层 Dialog 的 content 内**（追加一 A 段真因）：
                // 之前放在本函数体顶层（与 Dialog 平级），它挂在主窗口的组合树里，
                // 弹出的窗口 z 序在全屏浮层**之下**——真机上确认框被浮层整个盖住，
                // 表现就是「点删除没反应」。挪进来才能叠在浮层之上。
                askDelete?.let { row ->
                    AlertDialog(
                        onDismissRequest = { askDelete = null },
                        title = { Text(stringResource(R.string.baopu_session_delete_title)) },
                        text = {
                            Text(
                                stringResource(
                                    R.string.baopu_session_delete_desc,
                                    row.title,
                                    row.roundCount,
                                ),
                            )
                        },
                        confirmButton = {
                            TextButton(
                                enabled = !busy,
                                onClick = {
                                    // 第二十五批 C 段：删的是当前正开着的会话 ⇒ 主界面回空态，
                                    // 这里说一声（Toast 飘在浮层之上）；**不许**自动跳到别的会话。
                                    val wasCurrent = row.id == controller.sessionId
                                    askDelete = null
                                    val ok = controller.deleteSession(row.id)
                                    if (!ok) {
                                        // 追加一 D4：删除失败必须说出来，不许静默
                                        Toast.makeText(
                                            ctx,
                                            ctx.getString(R.string.baopu_session_delete_failed),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    } else if (wasCurrent) {
                                        Toast.makeText(
                                            ctx,
                                            ctx.getString(R.string.baopu_session_deleted_current),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                    tick++
                                },
                            ) {
                                Text(
                                    stringResource(R.string.baopu_session_delete_confirm),
                                    // 派工单 §2.1.4 要红色删除键；仓库对比度守卫（ErrorTextContrastGuardTest）
                                    // 规定文字不许用 colorScheme.error（3.55:1 不达 AA）⇒ 走语义红 zhengdaoErrorText()
                                    color = zhengdaoErrorText(),
                                )
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { askDelete = null }) {
                                Text(stringResource(R.string.baopu_session_delete_cancel))
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionLibraryRowItem(
    row: SessionLibraryRow,
    enabled: Boolean,
    // 追加一 B 段：旧会话（workspace 没记）在这行补一枚「历史未记录」小字——
    // 组标题归了「默认工作区」，行内这句是把「没假装它当时真在那儿跑」落到行上。
    showUnknownMark: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(start = 24.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.baopu_session_rounds, row.roundCount) +
                        " · " + row.timeText + " · " + stringResource(
                        if (row.sealed) R.string.baopu_session_sealed else R.string.baopu_session_continuable,
                    ) +
                        if (showUnknownMark) {
                            " · " + stringResource(R.string.baopu_session_ws_unknown)
                        } else {
                            ""
                        },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (row.sealed) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        }
        TextButton(enabled = enabled, onClick = onDelete) {
            Text(stringResource(R.string.baopu_session_delete))
        }
    }
}
