// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.ui.baopu

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.zhengdao.R
import com.example.zhengdao.baopu.ArtifactDeleteRules
import com.example.zhengdao.keepalive.CoroutineCrashHandler
import com.example.zhengdao.rootfs.RunLog
import com.example.zhengdao.ui.FilterChip2
import com.example.zhengdao.ui.theme.zhengdaoErrorText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class PickerScan(val rows: List<ArtifactDeleteRules.PickerRow> = emptyList())

private data class DeleteRun(
    val requested: Int,
    val moved: Int,
    val failed: Int,
    val ledgerOk: Boolean,
    val ledgerMarked: Int,
    val ledgerWhy: String?,
)

/**
 * 第一百七十八批 §0.9 · 自产稿件「清单勾选批量删」。
 *
 * 用户原话（2026-10-24 20:4x）：「列出来那几条，然后用户自己勾选，勾选了它就自己去干」。
 * 所以这一页**不做意图猜测**：只把「产物目录里、台账有记录」的那几条摊开，勾选 → 一句确认 → 执行。
 *
 * 这一页守住的五条（都是判据）：
 * 1. **只列交集**——台账（谁记的账）∩ 产物目录（东西还在不在）；用户自己扔进目录的文件不在这本账上，
 *    一条都不列；别的目录（`资料库/原始/`、记忆、备份）连入口都没有。
 * 2. **一句确认**——第一击只拿到 [ArtifactDeleteRules.BatchGate.NeedsConfirm]（闸在纯函数里，
 *    盘上一个字节都不动），第二击才执行。
 * 3. **移 .trash 不真删**——复用 38 批 [moveArtifactToTrash]（同卷 rename 进回收站 `回收站/`，
 *    名字带时间戳前缀），本文件**没有** delete / deleteRecursively。
 * 4. **台账标 deleted 但行不清**——原行逐字留着，只在下面插一行 🗑 删除标记
 *    （`ArtifactDeleteRules.markDeletedText`）；190 的查重指纹还认原行 ⇒ 删过的不再重复推荐。
 * 5. **审计一行**——每个文件一行删除标记＋运行日志一行批次摘要；失败也要说话，不许静默。
 *
 * 入口在产物面板（[ArtifactShelfDialog]）底部：39 批那一页只管 38 批的库表，本页管 179 的台账，
 * 两本账不合并（合并会把「谁记的账」说不清）。
 */
@Composable
internal fun ArtifactPickerDialog(
    workspaceRoot: File,
    onChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val res = LocalResources.current
    // 落点与台账选择**原样复用 179**（默认工作区根，外置根同构；不另找路径）
    val vaultRoot = remember(workspaceRoot) { BaopuLongformStore.vaultRoot(ctx) }
    val productDir = remember(vaultRoot) { BaopuLongformStore.productDirOf(vaultRoot) }
    val ledger = remember(vaultRoot, productDir) { BaopuLongformPolicy.pickLedger(vaultRoot, productDir) }

    var tick by remember { mutableIntStateOf(0) }
    var publishedOnly by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirming by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val scan by produceState(initialValue = PickerScan(), key1 = ledger, key2 = tick) {
        value = withContext(Dispatchers.IO) {
            val text = runCatching { if (ledger.isFile) ledger.readText() else null }.getOrNull()
            val files = runCatching { productDir.listFiles() }.getOrNull()
                ?.filter { it.isFile && !com.example.zhengdao.baopu.BaopuGuardCore.isSymlink(it) }
                ?.associate { it.name to runCatching { it.length() }.getOrDefault(0L) }
                ?: emptyMap()
            PickerScan(ArtifactDeleteRules.pickerRows(text, files, includePublishedOnly = false))
        }
    }

    val visible = scan.rows.filter { !publishedOnly || it.item.published }
    val visibleNames = visible.map { it.item.name }.toSet()
    // 筛「已发」、或被别处删掉之后，选中集合里不许留下**列表外**的名字（负例 N2）
    LaunchedEffect(visibleNames) {
        val pruned = selected.filter { it in visibleNames }.toSet()
        if (pruned != selected) {
            selected = pruned
            confirming = false
        }
    }
    // P4-4 纪律：UI 作用域必须装 CoroutineCrashHandler（门禁扫「没装 handler 的裸作用域」）
    val scope = rememberCoroutineScope { CoroutineCrashHandler }

    fun toggle(name: String) {
        selected = if (name in selected) selected - name else selected + name
        confirming = false
    }

    fun execute(names: List<String>) {
        if (names.isEmpty() || busy) return
        busy = true
        scope.launch {
            try {
                val nowMs = System.currentTimeMillis()
                val run = withContext(Dispatchers.IO) {
                    val moved = ArrayList<String>()
                    val failed = ArrayList<String>()
                    names.forEach { name ->
                        val rel = "${BaopuLongformPolicy.PRODUCT_DIR}/$name"
                        // 名字必须仍落在产物目录这一层（越界/带分隔符的一律不动）
                        val safe = ArtifactDeleteRules.fileIn(productDir, name) != null
                        val outcome = if (safe) {
                            moveArtifactToTrash(vaultRoot, rel, nowMs, confirmed = true)
                        } else {
                            null
                        }
                        when (outcome) {
                            // 文件已不在原地（Moved）或盘上本来就没有（LedgerOnly）⇒ 都算删成
                            is ArtifactMoveOutcome.Moved, is ArtifactMoveOutcome.LedgerOnly -> moved += name
                            else -> failed += name
                        }
                    }
                    val ledgerResult = if (moved.isEmpty()) {
                        ArtifactDeleteRules.LedgerWriteResult(true, emptyList(), emptyList())
                    } else {
                        ArtifactDeleteRules.markDeletedInLedger(ledger, moved, nowMs)
                    }
                    DeleteRun(
                        requested = names.size,
                        moved = moved.size,
                        failed = failed.size,
                        ledgerOk = ledgerResult.ok,
                        ledgerMarked = ledgerResult.marked.size,
                        ledgerWhy = ledgerResult.why,
                    )
                }
                // 审计一行（批次摘要；每个文件那一行在台账里）
                RunLog.log(
                    "产物批量删：请求 ${run.requested} 条，移入回收站 ${run.moved} 条，失败 ${run.failed} 条，" +
                        "台账删除标记 ${run.ledgerMarked} 条${if (run.ledgerOk) "" else "（未写成：${run.ledgerWhy}）"}",
                )
                val msg = when {
                    run.failed > 0 -> res.getString(
                        R.string.baopu_artifact_picker_failed_partial,
                        run.failed,
                        run.moved,
                    )
                    !run.ledgerOk -> res.getString(R.string.baopu_artifact_picker_ledger_failed)
                    else -> res.getString(R.string.baopu_artifact_picker_done, run.moved, run.ledgerMarked)
                }
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            } finally {
                // 下一次一定重新问（判据：确认位不许记住上次的许可）
                selected = emptySet()
                confirming = false
                busy = false
                tick++
                onChanged()
            }
        }
    }

    fun onBigButton() {
        when (val gate = ArtifactDeleteRules.batchGate(selected, visibleNames, confirmed = confirming)) {
            is ArtifactDeleteRules.BatchGate.NeedsConfirm -> confirming = true
            is ArtifactDeleteRules.BatchGate.Ready -> execute(gate.names)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(18.dp)) {
                Text(
                    text = stringResource(R.string.baopu_artifact_picker_title, visible.size),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.baopu_artifact_picker_desc),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Row(Modifier.padding(top = 8.dp)) {
                    FilterChip2(
                        label = stringResource(R.string.baopu_artifact_picker_filter_all),
                        selected = !publishedOnly,
                        onClick = {
                            publishedOnly = false
                            confirming = false
                        },
                    )
                    Spacer(Modifier.width(6.dp))
                    FilterChip2(
                        label = stringResource(R.string.baopu_artifact_picker_filter_sent),
                        selected = publishedOnly,
                        onClick = {
                            publishedOnly = true
                            confirming = false
                        },
                    )
                }
                Spacer(Modifier.height(6.dp))
                if (visible.isEmpty()) {
                    Text(
                        text = stringResource(
                            if (publishedOnly) R.string.baopu_artifact_picker_empty_sent
                            else R.string.baopu_artifact_picker_empty,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        visible.forEach { row ->
                            val name = row.item.name
                            val checked = name in selected
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .toggleable(
                                        value = checked,
                                        role = Role.Checkbox,
                                        onValueChange = { toggle(name) },
                                    )
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                // 整行 toggleable ⇒ 勾选框只显示（不重复接收点击，读屏也念一次）
                                Checkbox(checked = checked, onCheckedChange = null)
                                Column(Modifier.padding(start = 6.dp, top = 6.dp)) {
                                    val status = stringResource(
                                        if (row.item.published) R.string.baopu_artifact_picker_sent
                                        else R.string.baopu_artifact_picker_draft,
                                    )
                                    Text(
                                        text = row.item.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.baopu_artifact_picker_row_meta,
                                            row.item.date.ifBlank { "—" },
                                            row.item.track.ifBlank { "—" },
                                            row.item.chars,
                                            status,
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                        }
                    }
                }
                if (confirming && selected.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.baopu_artifact_picker_confirm_body, selected.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = zhengdaoErrorText(),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Button(
                    onClick = { onBigButton() },
                    enabled = selected.isNotEmpty() && !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 6.dp),
                ) {
                    Text(
                        text = stringResource(
                            if (confirming) R.string.baopu_artifact_picker_confirm_action
                            else R.string.baopu_artifact_picker_delete_n,
                            selected.size,
                        ),
                    )
                }
                Row(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.baopu_artifact_picker_close))
                    }
                }
            }
        }
    }
}
