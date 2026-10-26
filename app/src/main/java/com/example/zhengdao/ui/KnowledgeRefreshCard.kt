// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-10-24-第一百八十二批 §2/§3/§4（自动刷新可关、进度可取消；
// 整理一次出一张变更清单卡；原件只读；整理产物落 证道/知识库/整理/）。
package com.example.zhengdao.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.example.zhengdao.baopu.BaopuWorkspace
import com.example.zhengdao.baopu.KnowledgeRefresh
import com.example.zhengdao.baopu.knowledge.KnowledgeOrganize
import com.example.zhengdao.baopu.knowledge.KnowledgeOrganizeRules
import com.example.zhengdao.baopu.knowledge.KnowledgeRefreshRules
import com.example.zhengdao.baopu.knowledge.RefreshState
import com.example.zhengdao.keepalive.CoroutineCrashHandler
import com.example.zhengdao.ui.theme.zhengdaoSwitchColors
import com.example.zhengdao.util.TimeFormats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置 → 终端与资料页的「知识库」卡（第一百八十二批）。
 *
 * 两个动作：
 * - **立即刷新**：跑一次增量（可停止）；自动刷新只在前台，关掉后这里仍可手动。
 * - **整理知识库…**：先只读生成**一张**变更清单，用户确认后才写 `整理/` 副本；原件不动。
 */
@Composable
internal fun KnowledgeRefreshCard() {
    val ctx = LocalContext.current
    // 配置感知取资源（lint LocalContextGetResourceValueCall：协程里不能用 ctx.getString）。
    val res = LocalResources.current
    val scope = rememberCoroutineScope { CoroutineCrashHandler }
    var summary by remember { mutableStateOf<KnowledgeRefresh.Summary?>(null) }
    var busy by remember { mutableStateOf(false) }
    var organizing by remember { mutableStateOf(false) }
    var plan by remember { mutableStateOf<KnowledgeOrganizeRules.Plan?>(null) }
    var note by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        summary = withContext(Dispatchers.IO) { KnowledgeRefresh.summary(ctx) }
    }

    LaunchedEffect(Unit) { reload() }
    LaunchedEffect(busy) {
        while (busy) {
            summary = withContext(Dispatchers.IO) { KnowledgeRefresh.summary(ctx) }
            delay(400)
        }
    }

    fun refresh() {
        if (busy || organizing) return
        busy = true
        note = null
        scope.launch {
            val st = withContext(Dispatchers.IO) { KnowledgeRefresh.refreshNow(ctx) }
            busy = false
            note = when (st.state) {
                RefreshState.DONE -> res.getString(R.string.kb_refresh_done, st.added, st.modified, st.removed)
                RefreshState.CANCELLED -> res.getString(R.string.kb_refresh_cancelled)
                RefreshState.FAILED -> res.getString(R.string.kb_refresh_failed, st.message)
                else -> null
            }
            reload()
        }
    }

    fun makePlan() {
        if (busy || organizing) return
        organizing = true
        note = res.getString(R.string.kb_organize_scanning)
        scope.launch {
            val p = withContext(Dispatchers.IO) {
                runCatching { KnowledgeOrganize.propose(BaopuWorkspace.hostDir(ctx)) }.getOrNull()
            }
            organizing = false
            when {
                p == null -> note = res.getString(
                    R.string.kb_organize_failed,
                    res.getString(R.string.kb_organize_error),
                )
                p.isEmpty -> note = res.getString(R.string.kb_organize_empty)
                else -> {
                    plan = p
                    note = null
                }
            }
        }
    }

    fun executePlan(p: KnowledgeOrganizeRules.Plan) {
        plan = null
        organizing = true
        note = null
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { KnowledgeOrganize.execute(BaopuWorkspace.hostDir(ctx), p) }.getOrNull()
            }
            organizing = false
            note = if (r == null) {
                res.getString(R.string.kb_organize_failed, res.getString(R.string.kb_organize_error))
            } else {
                res.getString(R.string.kb_organize_result, r.created, r.skipped, r.failed)
            }
            KnowledgeRefresh.request(ctx, KnowledgeRefreshRules.Trigger.ORGANIZE_DONE)
            reload()
        }
    }

    val s = summary
    val running = busy || s?.status?.state == RefreshState.RUNNING
    SectionCard(stringResource(R.string.kb_refresh_card_title)) {
        val stateText = when {
            running -> {
                val done = s?.status?.done ?: 0
                val total = s?.status?.total ?: 0
                if (total > 0) stringResource(R.string.kb_refresh_running, done, total)
                else stringResource(R.string.kb_refresh_running_unknown)
            }
            s == null || !s.hasIndex -> stringResource(R.string.kb_refresh_state_empty)
            else -> stringResource(R.string.kb_refresh_state, s.files, TimeFormats.minuteStamp(s.updatedAt))
        }
        InfoRow(stringResource(R.string.kb_refresh_state_label), stateText)
        s?.indexPath?.takeIf { it.isNotBlank() }?.let { path ->
            Text(
                text = stringResource(R.string.kb_refresh_path, path),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        s?.status?.takeIf { it.at > 0 }?.let { st ->
            Text(
                text = stringResource(
                    R.string.kb_refresh_last,
                    TimeFormats.minuteStamp(st.at),
                    reasonLabel(st.reason),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        note?.let {
            Spacer(Modifier.height(4.dp))
            Text(text = it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedButton(enabled = !busy && !organizing, onClick = { refresh() }) {
                Text(stringResource(R.string.kb_refresh_now))
            }
            if (busy) {
                TextButton(onClick = { KnowledgeRefresh.cancel() }) {
                    Text(stringResource(R.string.kb_refresh_cancel))
                }
            }
            Spacer(Modifier.width(8.dp))
            TextButton(enabled = !busy && !organizing, onClick = { makePlan() }) {
                Text(stringResource(R.string.kb_organize_action))
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.kb_refresh_auto),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = s?.autoEnabled ?: true,
                onCheckedChange = { on ->
                    KnowledgeRefresh.setAutoEnabled(ctx, on)
                    if (on) KnowledgeRefresh.request(ctx, KnowledgeRefreshRules.Trigger.FOREGROUND)
                    scope.launch { reload() }
                },
                colors = zhengdaoSwitchColors(),
            )
        }
        Text(
            text = stringResource(R.string.kb_refresh_desc),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    plan?.let { p ->
        AlertDialog(
            onDismissRequest = { plan = null },
            title = { Text(stringResource(R.string.kb_organize_card_title, p.adds.size)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(stringResource(R.string.kb_organize_card_desc), style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(6.dp))
                    p.adds.take(8).forEach { item ->
                        Text(
                            text = stringResource(
                                R.string.kb_organize_item,
                                item.sourceRel,
                                item.targetRel,
                                catLabel(item.category),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    val rest = p.adds.size - 8
                    if (rest > 0) {
                        Text(stringResource(R.string.kb_organize_more, rest), style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { executePlan(p) }) { Text(stringResource(R.string.kb_organize_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { plan = null }) { Text(stringResource(R.string.kb_organize_cancel)) }
            },
        )
    }
}

@Composable
private fun reasonLabel(reason: String): String = stringResource(
    when (reason) {
        KnowledgeRefreshRules.Trigger.START.key -> R.string.kb_refresh_reason_start
        KnowledgeRefreshRules.Trigger.FOREGROUND.key -> R.string.kb_refresh_reason_foreground
        KnowledgeRefreshRules.Trigger.IMPORT_DONE.key -> R.string.kb_refresh_reason_import
        KnowledgeRefreshRules.Trigger.MEDIASTORE.key -> R.string.kb_refresh_reason_mediastore
        KnowledgeRefreshRules.Trigger.PERIODIC.key -> R.string.kb_refresh_reason_periodic
        KnowledgeRefreshRules.Trigger.ORGANIZE_DONE.key -> R.string.kb_refresh_reason_organize
        else -> R.string.kb_refresh_reason_manual
    },
)

@Composable
private fun catLabel(category: KnowledgeOrganizeRules.Category): String = stringResource(
    when (category) {
        KnowledgeOrganizeRules.Category.DOC -> R.string.kb_organize_cat_doc
        KnowledgeOrganizeRules.Category.CODE -> R.string.kb_organize_cat_code
        KnowledgeOrganizeRules.Category.SHEET -> R.string.kb_organize_cat_sheet
        KnowledgeOrganizeRules.Category.WEB -> R.string.kb_organize_cat_web
        KnowledgeOrganizeRules.Category.OTHER -> R.string.kb_organize_cat_other
    },
)
