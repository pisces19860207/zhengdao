// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百二十八批（派工单 §一）：**决策中心主屏**——待批／已办／可回滚三区，
// 动作只有批/驳/留（可回滚区另有一键回滚，走既有通道）。入口在左侧栏「新会话」之上。
//
// 纪律（判据④）：本文件**只渲染＋转发**——
//   · 三区的内容、排序、每条有哪些钮，全在 `DecisionZoneRules`（纯判据层）算好；
//   · 每个动作都原样交给 `BaopuChatController` 的数据口（`decisionZoneSnapshot`／
//     `onZonePendingAction`／`onZoneRoyalPick`／`onDecisionRecordRollback`），
//     授权闸在通道那一侧，本层不判、不筛、不拼账（源级门禁见 `DecisionZoneScreenGateTest`）。
//
// 承载路子与 `DecisionCenterScreen` 相同（全屏 Dialog：摆对话 Column 里会露两端）。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.zhengdao.R
import com.example.zhengdao.ui.theme.zhengdaoErrorText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 决策中心主屏（三区一屏；与顶栏盾牌的页签屏同源同账——两屏都只吃控制器的数据口）。
 */
@Composable
internal fun DecisionZoneScreen(
    controller: BaopuChatController,
    onDismiss: () -> Unit,
) {
    // 纪律：无条件先读版本号（回滚/裁定后 +1），再决定拿它干什么——不然不订阅、页不刷新。
    val version = controller.decisionRecordVersion
    val emptySnapshot = remember { ZoneSnapshot(emptyList(), emptyList(), emptyList()) }
    // 读账走 IO（264 同一口径）：三区一次读全，不各读各的。
    val snapshot by produceState(initialValue = emptySnapshot, version) {
        value = withContext(Dispatchers.IO) { controller.decisionZoneSnapshot() }
    }
    var evidenceOf by remember { mutableStateOf<ZoneDoneRow?>(null) }
    var confirmRollback by remember { mutableStateOf<ZoneRollbackRow?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                ZoneTopBar(onDismiss)
                controller.decisionNote?.let { note ->
                    Text(
                        text = note.text,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (note.level == BaopuNoteLevel.Error) {
                            zhengdaoErrorText()
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    )
                }
                ZoneList(
                    snapshot = snapshot,
                    onPendingAction = { row, action ->
                        controller.onZonePendingAction(row, action)
                        // 高风险采纳的二次确认层长在聊天页：先收本屏，让确认层露出来
                        // （与 DecisionCenterScreen 对 pendingAdoptConfirm 的同一处理）。
                        if (controller.pendingAdoptConfirm != null) onDismiss()
                    },
                    onRoyalPick = { row, optionId -> controller.onZoneRoyalPick(row, optionId) },
                    onEvidence = { evidenceOf = it },
                    onRollback = { confirmRollback = it },
                )
            }
        }
    }

    ZoneEvidenceDialog(row = evidenceOf, onDismiss = { evidenceOf = null })
    ZoneRollbackDialog(
        row = confirmRollback,
        onDismiss = { confirmRollback = null },
        onConfirm = { controller.onDecisionRecordRollback(it) },
    )
}

/** 顶条：返回＋标题＋一句口径（徽标在页签屏那一侧，主屏不数第二遍）。 */
@Composable
private fun ZoneTopBar(onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = onDismiss,
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text("←") }
        Text(
            text = stringResource(R.string.decision_center_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.dz_subtitle),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 三区列表（顺序即派工单顺序：待批 → 已办 → 可回滚；计数由数据口算好）。 */
@Composable
private fun ZoneList(
    snapshot: ZoneSnapshot,
    onPendingAction: (ZonePendingRow, DecisionZoneAction) -> Unit,
    onRoyalPick: (ZonePendingRow, String) -> Unit,
    onEvidence: (ZoneDoneRow) -> Unit,
    onRollback: (ZoneRollbackRow) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item(key = "zone-pending-head") { ZoneHeader(R.string.dz_zone_pending, snapshot.pending.size) }
        if (snapshot.pending.isEmpty()) {
            item(key = "zone-pending-empty") { ZoneEmpty(R.string.dz_empty_pending) }
        }
        items(snapshot.pending, key = { it.key }) { row ->
            PendingRowView(
                row = row,
                onAction = { action -> onPendingAction(row, action) },
                onRoyalPick = { optionId -> onRoyalPick(row, optionId) },
            )
        }
        item(key = "zone-done-head") { ZoneHeader(R.string.dz_zone_done, snapshot.done.size) }
        if (snapshot.done.isEmpty()) {
            item(key = "zone-done-empty") { ZoneEmpty(R.string.dz_empty_done) }
        }
        items(snapshot.done, key = { "done-" + it.record.id }) { row ->
            RecordRowView(
                record = row.record,
                timeLabel = row.timeLabel,
                trailing = {
                    TextButton(onClick = { onEvidence(row) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.dz_evidence_open))
                    }
                },
            )
        }
        item(key = "zone-rollback-head") { ZoneHeader(R.string.dz_zone_rollback, snapshot.rollbackable.size) }
        if (snapshot.rollbackable.isEmpty()) {
            item(key = "zone-rollback-empty") { ZoneEmpty(R.string.dz_empty_rollback) }
        }
        items(snapshot.rollbackable, key = { "rollback-" + it.record.id }) { row ->
            RecordRowView(
                record = row.record,
                timeLabel = row.timeLabel,
                trailing = {
                    TextButton(onClick = { onRollback(row) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.decision_undo))
                    }
                },
            )
        }
    }
}

/** 区头（标题＋条数）。 */
@Composable
private fun ZoneHeader(titleRes: Int, count: Int) {
    Text(
        text = stringResource(titleRes) + "（$count）",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
    )
}

/** 区空态一句话（无插画，照决策中心页签的既有口径）。 */
@Composable
private fun ZoneEmpty(textRes: Int) {
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
    )
}

/**
 * 待批一行。
 *
 * 御案行原样交给 [BaopuRoyalCard]（三行＋真假标注＋批/驳/搁置都在卡上，
 * 选项 id 不解释、原样转发）；其余来路渲染三行＋按判据层给的旗子摆钮。
 */
@Composable
private fun PendingRowView(
    row: ZonePendingRow,
    onAction: (DecisionZoneAction) -> Unit,
    onRoyalPick: (String) -> Unit,
) {
    val royal = row.royal
    if (royal != null) {
        BaopuRoyalCard(card = royal.card, onPick = onRoyalPick, modifier = Modifier.padding(vertical = 4.dp))
        return
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            row.lines.forEach { line ->
                Text(text = line, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                text = row.timeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (row.canApprove) {
                    ZoneActionButton(R.string.dz_action_approve) { onAction(DecisionZoneAction.APPROVE) }
                }
                if (row.canReject) {
                    ZoneActionButton(R.string.dz_action_reject) { onAction(DecisionZoneAction.REJECT) }
                }
                if (row.canHold) {
                    ZoneActionButton(R.string.dz_action_hold) { onAction(DecisionZoneAction.HOLD) }
                }
            }
        }
    }
}

/** 一枚动作钮（≥48dp 热区照既有卡；钮有没有由 [ZonePendingRow] 的旗子定，这里不判）。 */
@Composable
private fun ZoneActionButton(labelRes: Int, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(stringResource(labelRes))
    }
}

/** 账行两行半：理由 ＋ 谁·多久前·before→after；尾部动作由调用方摆（已办＝看证据、可回滚＝撤销）。 */
@Composable
private fun RecordRowView(
    record: com.example.zhengdao.baopu.DecisionRecord,
    timeLabel: String,
    trailing: @Composable () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(text = record.reason, style = MaterialTheme.typography.bodySmall)
                Text(
                    text = "${stringResource(decisionActorLabel(record.actor))} · $timeLabel · " +
                        "${record.before}→${record.after}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            trailing()
        }
    }
}

/** 已办点开看证据（判据：kind/actor/理由/before→after/时间之外，证据 id 也在这儿）。 */
@Composable
private fun ZoneEvidenceDialog(row: ZoneDoneRow?, onDismiss: () -> Unit) {
    if (row == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dz_evidence_title)) },
        text = {
            Column {
                EvidenceLine(stringResource(R.string.dz_field_reason), row.record.reason)
                EvidenceLine(
                    stringResource(R.string.dz_field_actor),
                    stringResource(decisionActorLabel(row.record.actor)),
                )
                EvidenceLine(stringResource(R.string.dz_field_kind), row.record.kind.wire)
                EvidenceLine(stringResource(R.string.dz_field_change), "${row.record.before}→${row.record.after}")
                EvidenceLine(stringResource(R.string.dz_field_evidence), row.record.evidence)
                EvidenceLine(stringResource(R.string.dz_field_time), row.timeLabel)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.common_close))
            }
        },
    )
}

/** 回滚二次确认（§2 的既有形制：只多问一次；执行与记账全在控制器与既有回滚通道）。 */
@Composable
private fun ZoneRollbackDialog(
    row: ZoneRollbackRow?,
    onDismiss: () -> Unit,
    onConfirm: (com.example.zhengdao.baopu.DecisionRecord) -> Unit,
) {
    if (row == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.decision_record_rollback_confirm_title)) },
        text = { Text(stringResource(R.string.decision_record_rollback_confirm, row.record.reason)) },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(row.record)
                    onDismiss()
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.decision_undo)) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.decision_authorize_cancel)) }
        },
    )
}

@Composable
private fun EvidenceLine(label: String, value: String) {
    Text(
        text = "$label：$value",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}
