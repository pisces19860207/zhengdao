// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：第四百九十一批①「一键导出」②「一键导入：默认不覆盖，冲突列项让人选」。
//
// 本文件只做界面：判定在 `backup/BackupRules`，落盘在 `backup/BackupImporter`，
// 目录与设置的真源在 `backup/BackupShell`。界面里没有一处自己拼路径、自己算 hash。
package com.example.zhengdao.ui

import android.content.Context
import android.content.res.Resources
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.backup.BackupRules
import com.example.zhengdao.backup.BackupShell
import com.example.zhengdao.backup.ImportPreview
import com.example.zhengdao.backup.OpenResult
import com.example.zhengdao.keepalive.CoroutineCrashHandler
import com.example.zhengdao.ui.theme.rDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 系统文件选择器认的 zip MIME（有的文件管理器给 zip 报 octet-stream，所以三个都带）。 */
private val ZIP_MIME = arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")

/** 挑中文件的两种结局（界面只认这两条；异常都在 [openPicked] 里被包成 [Bad]）。 */
private sealed interface Picked {
    data class Bad(val text: String) : Picked
    data class Ready(val preview: ImportPreview) : Picked
}

/**
 * 设置页的「备份与还原」一块（第四百九十一批）。
 *
 * 三行：一键导出 / 含不含聊天原文 / 一键导入。含不含聊天原文是一个**开关行**
 * （默认关）而不是导出时弹窗里问——弹一次窗就多一次打断，而它绝大多数时候都是"不含"。
 */
@Composable
internal fun BackupSection() {
    val ctx = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope { CoroutineCrashHandler }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var includeChat by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<ImportPreview?>(null) }
    var rejectNote by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val picked = openPicked(ctx, res, uri)
            busy = false
            when (picked) {
                is Picked.Bad -> rejectNote = picked.text
                is Picked.Ready -> preview = picked.preview
            }
        }
    }

    BackupCard(
        busy = busy,
        note = note,
        includeChat = includeChat,
        onExport = {
            if (!busy) {
                busy = true
                note = null
                scope.launch {
                    note = exportNote(ctx, res, includeChat)
                    busy = false
                }
            }
        },
        onToggleChat = { if (!busy) includeChat = !includeChat },
        onImport = { if (!busy) picker.launch(ZIP_MIME) },
    )
    rejectNote?.let { why -> SimpleNoteDialog(why) { rejectNote = null } }
    preview?.let { p ->
        ImportConfirmDialog(
            preview = p,
            diff = BackupShell.settingsDiff(ctx, p),
            onDismiss = { p.cleanup(); preview = null },
            onConfirm = { overwrite, withSettings ->
                preview = null
                busy = true
                scope.launch {
                    note = importNote(ctx, res, p, overwrite, withSettings)
                    busy = false
                }
            },
        )
    }
}

/** 一块三行的卡（纯展示：状态与回调都由 [BackupSection] 给）。 */
@Composable
private fun BackupCard(
    busy: Boolean,
    note: String?,
    includeChat: Boolean,
    onExport: () -> Unit,
    onToggleChat: () -> Unit,
    onImport: () -> Unit,
) {
    SectionCard(stringResource(R.string.backup_title)) {
        SettingRow(
            label = stringResource(R.string.backup_export),
            value = when {
                busy -> stringResource(R.string.backup_export_busy)
                note != null -> note
                else -> stringResource(R.string.backup_export_desc)
            },
            valueMaxLines = 2,
            onClick = onExport,
        )
        SettingsDivider()
        SettingRow(
            label = stringResource(R.string.backup_include_chat),
            value = if (includeChat) {
                stringResource(R.string.backup_include_chat_on)
            } else {
                stringResource(R.string.backup_include_chat_off)
            },
            onClick = onToggleChat,
        )
        SettingsDivider()
        SettingRow(
            label = stringResource(R.string.backup_import),
            value = stringResource(R.string.backup_import_desc),
            valueMaxLines = 2,
            onClick = onImport,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.backup_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 一句话弹窗（"这包不能用"专用）。 */
@Composable
private fun SimpleNoteDialog(text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(rDialog),
        title = { Text(stringResource(R.string.backup_import)) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_confirm)) }
        },
    )
}

// ── 三个副作用（都在 IO 线程上；界面只管把结果贴出来）───────────────────────────

private suspend fun exportNote(ctx: Context, res: Resources, includeChat: Boolean): String =
    withContext(Dispatchers.IO) {
        val r = BackupShell.export(ctx, includeChat)
        when {
            !r.ok -> res.getString(R.string.backup_export_failed, r.reason.orEmpty())
            // 跳过必须说出来：一个 30 MiB 的日志被悄悄漏掉，用户拿到的"完整还原"就是假的
            r.skipped > 0 ->
                res.getString(R.string.backup_export_done, r.file?.absolutePath.orEmpty()) +
                    res.getString(R.string.backup_export_partial, r.skipped)

            else -> res.getString(R.string.backup_export_done, r.file?.absolutePath.orEmpty())
        }
    }

private suspend fun importNote(
    ctx: Context,
    res: Resources,
    preview: ImportPreview,
    overwrite: Set<String>,
    withSettings: Boolean,
): String = withContext(Dispatchers.IO) {
    val r = BackupShell.importNow(ctx, preview, overwrite, withSettings)
    if (!r.ok) return@withContext res.getString(R.string.backup_import_failed, r.reason.orEmpty())
    val touched = r.applied + if (r.settingsApplied > 0) 1 else 0
    val done = res.getString(R.string.backup_import_done, touched)
    // 冲突没覆盖这件事必须写在结果里，不能只在弹窗里说过就算
    if (r.conflicts.isEmpty()) done else done + res.getString(R.string.backup_import_conflicts, r.conflicts.size)
}

/** 把 `content://` 复制进缓存再校验；任何一步不成就变成一句人话（不抛到界面上）。 */
private suspend fun openPicked(ctx: Context, res: Resources, uri: Uri): Picked = withContext(Dispatchers.IO) {
    val zip = BackupShell.pickToCache(ctx, uri)
    if (zip == null) return@withContext Picked.Bad(res.getString(R.string.backup_pick_failed))
    when (val r = BackupShell.open(ctx, zip)) {
        is OpenResult.Rejected -> Picked.Bad(res.getString(R.string.backup_import_bad, r.reason))
        is OpenResult.Ready -> Picked.Ready(r.preview)
    }
}

/**
 * 导入确认框：**先把会撞的列出来**，勾了才覆盖；一个都不勾＝只补本机没有的。
 *
 * 设置那一条单独一行（它是一个整体，不是逐键勾）——选项就"导/不导"这一件事。
 */
@Composable
private fun ImportConfirmDialog(
    preview: ImportPreview,
    diff: BackupRules.SettingsDiff,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>, Boolean) -> Unit,
) {
    val res = LocalResources.current
    var chosen by remember { mutableStateOf(setOf<String>()) }
    var withSettings by remember { mutableStateOf(diff.changed == 0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(rDialog),
        title = { Text(stringResource(R.string.backup_dialog_title)) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text(
                    res.getString(
                        R.string.backup_dialog_body,
                        preview.news.size,
                        preview.conflicts.size,
                        preview.same.size,
                    ),
                )
                ConflictList(preview, chosen) { path ->
                    chosen = if (path in chosen) chosen - path else chosen + path
                }
                if (!diff.isEmpty) {
                    Spacer(Modifier.height(10.dp))
                    SettingRow(
                        label = res.getString(R.string.backup_dialog_settings, diff.changed, diff.added),
                        value = if (withSettings) "✓" else "",
                        valueMaxLines = 2,
                    ) { withSettings = !withSettings }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(chosen, withSettings) }) {
                Text(stringResource(R.string.backup_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/** 冲突清单（每行一条路径，点一下勾/取消；默认全不勾＝不覆盖）。 */
@Composable
private fun ConflictList(preview: ImportPreview, chosen: Set<String>, onToggle: (String) -> Unit) {
    if (preview.conflicts.isEmpty()) return
    Spacer(Modifier.height(10.dp))
    Text(
        stringResource(R.string.backup_dialog_conflicts),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    for (item in preview.conflicts) {
        SettingRow(
            label = item.entry.path,
            value = if (item.entry.path in chosen) "✓" else "",
            valueMaxLines = 2,
        ) { onToggle(item.entry.path) }
    }
}
