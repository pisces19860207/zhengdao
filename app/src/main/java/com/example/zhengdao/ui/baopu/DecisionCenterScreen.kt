// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百零四批（2026-10-21，派工单 §1③）：**决策中心**——四页签
// （待授权 / 建议 / 提案 / 记录），承载排队中的卡与审计撤销入口。
//
// 设计依据（v1.4 §0 决策 #5 / §3 W6/W7）：
//   - 盾牌是顶栏第五件；徽标＝前三页签待处理总数，记录页不计；
//   - 「建议」页签双类型（99 建议＋100 效果同驻，类型签区分，效果行排前）；
//   - 记录页承载审计与撤销（**仅可回退操作**，不制造新待处理）；
//   - 四页签各有空态文案，无插画。
//
// 纪律：本文件只渲染＋转发，判据与数据全在控制器（`decisionCenter*` 系列）
// 与 `DecisionCardRules`；点行打开同款决策卡（设计稿 W7）。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.example.zhengdao.baopu.DecisionFilter
import com.example.zhengdao.baopu.DecisionGroup
import com.example.zhengdao.baopu.DecisionGroupCard
import com.example.zhengdao.baopu.DecisionRecord
import com.example.zhengdao.baopu.foldDecisionRecords
import com.example.zhengdao.ui.FilterChip2

/**
 * 决策中心（全屏 Dialog；与抱朴设置同一条承载路子——摆对话 Column 里会露两端）。
 *
 * @param onOpenSettings 「去设置页处理」（RETIRE 效果卡的指路钮）与抽屉同入口
 */
@Composable
internal fun DecisionCenterScreen(
    controller: BaopuChatController,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    /** 记录页「跳回原始上下文」用：产物类跳回收站（不传＝只能去设置页）。 */
    onOpenTrash: () -> Unit = {},
) {
    // 点行打开的「同款决策卡」（null = 只看列表）
    var openCardKey by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(DecisionTab.TO_AUTHORIZE) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                // 顶条：返回＋标题＋（待处理数）
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
                    Spacer(Modifier.weight(1f))
                    val badge = controller.decisionBadge
                    if (badge > 0) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                            Text(
                                text = badge.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                    }
                }
                Text(
                    text = stringResource(R.string.decision_center_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
                TabRow(selectedTabIndex = tab.ordinal) {
                    DecisionTab.entries.forEach { t ->
                        Tab(
                            selected = tab == t,
                            onClick = { tab = t },
                            text = { Text(stringResource(decisionTabTitle(t))) },
                        )
                    }
                }
                when (tab) {
                    DecisionTab.TO_AUTHORIZE -> AuthorizeTab(controller)
                    DecisionTab.SUGGESTIONS -> SuggestionsTab(controller, openCardKey) { openCardKey = it }
                    DecisionTab.PROPOSALS -> ProposalsTab(controller)
                    DecisionTab.HISTORY -> HistoryTab(
                        controller = controller,
                        onJump = { target ->
                            when (target) {
                                DecisionJump.SETTINGS -> {
                                    onDismiss()
                                    onOpenSettings()
                                }
                                DecisionJump.ARTIFACT_TRASH -> {
                                    onDismiss()
                                    onOpenTrash()
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    // 点行打开的「同款决策卡」（W7）；动作经控制器按 key 分发
    openCardKey?.let { key ->
        val spec = controller.decisionCardSpecFor(key)
        if (spec == null) {
            openCardKey = null // 行已经没了（被处理/被撤销）⇒ 收对话框
        } else {
            AlertDialog(
                onDismissRequest = { openCardKey = null },
                title = { Text(spec.title) },
                text = {
                    DecisionCard(
                        spec = spec,
                        onAction = { buttonId ->
                            controller.onDecisionCardAction(key, buttonId)
                            // 高风险采纳要二次确认 ⇒ 先收决策中心，让确认层露出来
                            if (controller.pendingAdoptConfirm != null) {
                                openCardKey = null
                                onDismiss()
                            }
                        },
                    )
                },
                confirmButton = {},
            )
        }
    }
}

/** 页签标题（四句；空态见各 Tab）。 */
private fun decisionTabTitle(t: DecisionTab): Int = when (t) {
    DecisionTab.TO_AUTHORIZE -> R.string.decision_tab_authorize
    DecisionTab.SUGGESTIONS -> R.string.decision_tab_suggestions
    DecisionTab.PROPOSALS -> R.string.decision_tab_proposals
    DecisionTab.HISTORY -> R.string.decision_tab_history
}

/** 页签空态（四句各一句，无插画）。 */
private fun decisionEmptyText(t: DecisionTab): Int = when (t) {
    DecisionTab.TO_AUTHORIZE -> R.string.decision_empty_authorize
    DecisionTab.SUGGESTIONS -> R.string.decision_empty_suggestions
    DecisionTab.PROPOSALS -> R.string.decision_empty_proposals
    DecisionTab.HISTORY -> R.string.decision_empty_history
}

/** 一个页签的公共外壳：空态一句话 / 有内容走 [content]。 */
@Composable
private fun DecisionTabBody(tab: DecisionTab, empty: Boolean, content: @Composable () -> Unit) {
    if (empty) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(decisionEmptyText(tab)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        content()
    }
}

@Composable
private fun AuthorizeTab(controller: BaopuChatController) {
    val spec = controller.pendingAuthorizationSpec()
    DecisionTabBody(DecisionTab.TO_AUTHORIZE, empty = spec == null) {
        LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
            item {
                spec?.let {
                    DecisionCard(
                        spec = it,
                        onAction = { id -> controller.onDecisionAction(id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SuggestionsTab(
    controller: BaopuChatController,
    openCardKey: String?,
    onOpenCard: (String?) -> Unit,
) {
    val rows = controller.decisionCenterSuggestionRows()
    DecisionTabBody(DecisionTab.SUGGESTIONS, empty = rows.isEmpty()) {
        LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
            items(rows, key = { it.id }) { row ->
                DecisionRowCard(row, onClick = { onOpenCard(row.id) })
            }
        }
    }
}

@Composable
private fun ProposalsTab(controller: BaopuChatController) {
    val spec = controller.pendingProposalSpec()
    DecisionTabBody(DecisionTab.PROPOSALS, empty = spec == null) {
        LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
            item {
                spec?.let {
                    DecisionCard(
                        spec = it,
                        onAction = { id -> controller.onDecisionAction(id) },
                    )
                }
            }
        }
    }
}

/**
 * 记录页（第二百零八批 §2／§2.5／§2.7）——**按对象分组的折叠卡**，不是一长条。
 *
 * 纪律：
 * - 折叠态只渲染组卡头（组内行只在展开后出现）；
 * - 组内行最多 [PAGE_LIMIT] 行（组间共用预算），超出进「组内还有 N 条」；
 * - 待抽检组排最前，每条两个按钮（确认无误／判错了）；
 * - 有 `reversible` 的行给「回滚」＋一次二次确认；回滚本身由控制器再留一条。
 */
@Composable
private fun HistoryTab(
    controller: BaopuChatController,
    onJump: (DecisionJump) -> Unit,
) {
    // 纪律：Compose 里必须**无条件先读** State，再决定拿它干什么（不然不订阅、页不刷新）
    val version = controller.decisionRecordVersion
    var filter by remember { mutableStateOf(DecisionFilter.ALL) }
    var expanded by remember { mutableStateOf(setOf<DecisionGroup>()) }
    var confirmRollback by remember { mutableStateOf<DecisionRecord?>(null) }

    // 现读账本（版本号一变就重算：回滚/抽检后组头计数即时更新）
    val now = remember(version) { System.currentTimeMillis() }
    val records = remember(version) { controller.decisionRecordList() }
    val pendingIds = remember(version, records) { controller.decisionRecordPendingIds(records) }
    val fold = remember(version, filter, expanded, records, pendingIds) {
        foldDecisionRecords(
            records = records,
            filter = filter,
            nowMs = now,
            sessionId = controller.sessionId ?: -1L,
            pendingIds = pendingIds,
            expanded = expanded,
        )
    }

    DecisionTabBody(DecisionTab.HISTORY, empty = records.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            // 筛选 chips（§2.5 规则④：先筛再分组）
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DecisionFilter.entries.forEach { f ->
                    FilterChip2(
                        label = stringResource(decisionFilterTitle(f)),
                        selected = filter == f,
                        onClick = { filter = f },
                    )
                }
            }
            controller.decisionNote?.let { note ->
                Text(
                    text = note.text,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (note.level == BaopuNoteLevel.Error) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp)) {
                items(fold.cards, key = { it.group.wire }) { card ->
                    DecisionGroupCardView(
                        card = card,
                        nowMs = now,
                        isSampling = card.group == DecisionGroup.SAMPLING,
                        expanded = card.group in expanded,
                        onToggle = {
                            expanded = if (card.group in expanded) {
                                expanded - card.group
                            } else {
                                expanded + card.group
                            }
                        },
                        onJump = { onJump(decisionJumpOf(it.kind)) },
                        onRollback = { confirmRollback = it },
                        onJudge = { rec, wrong -> controller.onDecisionRecordJudge(rec, wrong) },
                    )
                }
                if (fold.hasOverflow) {
                    item(key = "overflow") {
                        Text(
                            text = stringResource(R.string.decision_group_more, fold.overflowGroups.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }

    // 回滚二次确认（§2：只多问一次）
    confirmRollback?.let { rec ->
        AlertDialog(
            onDismissRequest = { confirmRollback = null },
            title = { Text(stringResource(R.string.decision_record_rollback_confirm_title)) },
            text = { Text(stringResource(R.string.decision_record_rollback_confirm, rec.reason)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        controller.onDecisionRecordRollback(rec)
                        confirmRollback = null
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.decision_undo)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmRollback = null },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.decision_authorize_cancel)) }
            },
        )
    }
}

/** 一张组卡：组头（组名·条数·最近时间·可撤销数）＋展开后才有组内行。 */
@Composable
private fun DecisionGroupCardView(
    card: DecisionGroupCard,
    nowMs: Long,
    isSampling: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onJump: (DecisionRecord) -> Unit,
    onRollback: (DecisionRecord) -> Unit,
    onJudge: (DecisionRecord, Boolean) -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = groupHeadText(card, nowMs),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    if (isSampling) {
                        Text(
                            text = stringResource(R.string.decision_record_hint_sampling),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = onToggle, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(
                        stringResource(
                            if (expanded) R.string.decision_record_collapse else R.string.decision_record_expand,
                        ),
                    )
                }
            }
            if (expanded) {
                card.rows.forEach { rec ->
                    DecisionRecordRow(
                        rec = rec,
                        nowMs = nowMs,
                        isSampling = isSampling,
                        onJump = { onJump(rec) },
                        onRollback = { onRollback(rec) },
                        onJudge = { wrong -> onJudge(rec, wrong) },
                    )
                }
                if (card.hiddenByPage > 0) {
                    Text(
                        text = stringResource(R.string.decision_group_more_rows, card.hiddenByPage),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

/** 组头那句（有可撤销的组多说一句「N 条可撤销」）。 */
@Composable
private fun groupHeadText(card: DecisionGroupCard, nowMs: Long): String {
    val title = stringResource(decisionGroupTitle(card.group))
    val age = decisionAgeOf(card.latestMs, nowMs)
    val ageText = when (age.bucket) {
        DecisionTimeBucket.NOW -> stringResource(R.string.decision_record_time_now)
        DecisionTimeBucket.MINUTES -> stringResource(R.string.decision_record_time_min, age.n)
        DecisionTimeBucket.HOURS -> stringResource(R.string.decision_record_time_hour, age.n)
        DecisionTimeBucket.DAYS -> stringResource(R.string.decision_record_time_day, age.n)
        DecisionTimeBucket.OLDER -> stringResource(R.string.decision_record_time_older)
    }
    return if (card.reversibleCount > 0) {
        stringResource(R.string.decision_group_head_undoable, title, card.count, ageText, card.reversibleCount)
    } else {
        stringResource(R.string.decision_group_head, title, card.count, ageText)
    }
}

/** 组内一行：谁＋做了什么＋时间；可跳原上下文、可回滚、抽检条目多两个按钮。 */
@Composable
private fun DecisionRecordRow(
    rec: DecisionRecord,
    nowMs: Long,
    isSampling: Boolean,
    onJump: () -> Unit,
    onRollback: () -> Unit,
    onJudge: (Boolean) -> Unit,
) {
    val age = decisionAgeOf(rec.atMs, nowMs)
    val ageText = when (age.bucket) {
        DecisionTimeBucket.NOW -> stringResource(R.string.decision_record_time_now)
        DecisionTimeBucket.MINUTES -> stringResource(R.string.decision_record_time_min, age.n)
        DecisionTimeBucket.HOURS -> stringResource(R.string.decision_record_time_hour, age.n)
        DecisionTimeBucket.DAYS -> stringResource(R.string.decision_record_time_day, age.n)
        DecisionTimeBucket.OLDER -> stringResource(R.string.decision_record_time_older)
    }
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 6.dp)) {
        Text(
            text = rec.reason,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = "${stringResource(decisionActorLabel(rec.actor))} · $ageText · ${rec.before}→${rec.after}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isSampling) {
                TextButton(onClick = { onJudge(false) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.decision_record_judge_ok))
                }
                TextButton(onClick = { onJudge(true) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.decision_record_judge_wrong))
                }
            } else {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onJump, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.common_expand_arrow))
                }
                if (rec.reversible) {
                    TextButton(onClick = onRollback, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.decision_undo))
                    }
                }
            }
        }
    }
}

/** 列表里的一行（标题＋副标题＋徽标/类型签；可点开同款卡，可带撤销钮）。 */
@Composable
private fun DecisionRowCard(
    row: DecisionCenterRow,
    onClick: (() -> Unit)?,
    trailing: (@Composable () -> Unit)? = null,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (row.badge != null) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                            Text(
                                text = row.badge,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = row.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
                val sub = listOfNotNull(row.subtitle.ifBlank { null }, row.typeLabel).joinToString(" · ")
                if (sub.isNotBlank()) {
                    Text(
                        text = sub,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onClick != null) {
                TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.common_expand_arrow))
                }
            }
            trailing?.invoke()
        }
    }
}
