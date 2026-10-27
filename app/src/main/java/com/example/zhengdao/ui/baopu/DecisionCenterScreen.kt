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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.zhengdao.R
import com.example.zhengdao.baopu.DecisionActor
import com.example.zhengdao.baopu.DecisionFilter
import com.example.zhengdao.baopu.DecisionGroup
import com.example.zhengdao.baopu.DecisionGroupCard
import com.example.zhengdao.baopu.DecisionRecord
import com.example.zhengdao.baopu.foldDecisionRecords
import com.example.zhengdao.baopu.agent.FailureAuditLog
import com.example.zhengdao.baopu.agent.FailureAuditRow
import com.example.zhengdao.baopu.inspect.InspectFinding
import com.example.zhengdao.baopu.inspect.InspectLimits
import com.example.zhengdao.baopu.inspect.InspectVerdict
import com.example.zhengdao.baopu.inspect.PatrolEntry
import com.example.zhengdao.baopu.inspect.PatrolLedger
import com.example.zhengdao.baopu.inspect.PatrolRunner
import com.example.zhengdao.baopu.inspect.PatrolScheduler
import com.example.zhengdao.baopu.inspect.PatrolSources
import com.example.zhengdao.keepalive.CoroutineCrashHandler
import com.example.zhengdao.ui.FilterChip2
import com.example.zhengdao.ui.theme.zhengdaoErrorText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    // §2.6 的展开态提到这一层（照上面组卡把 `expanded` 提到 Tab 层同一个理由）：
    // 明细行挂在 LazyColumn 的 item 里，状态留在 item 内部会随滚出可视区被销毁 ⇒
    // 「展开后不记得收起状态」这条负例就落在那儿；离开本页重进 ⇒ 回到默认折叠。
    var auditExpanded by remember { mutableStateOf(false) }
    var auditLimit by remember { mutableIntStateOf(FailureAuditLog.PAGE) }
    // 第二百三十九批 §2.4：巡按栏（勘验的输出落点）。展开态同样提到这一层，理由与上面一样。
    var patrolExpanded by remember { mutableStateOf(false) }
    var patrolLimit by remember { mutableIntStateOf(InspectLimits.PAGE) }
    // 一次巡按的四个界面态：跑没跑（按钮去抖）· 跑完重读台账的水位 · 闸门拒的那一句 · 跑不成的那一句。
    // 后两个是**栏内一行字**，不是弹窗（§2.4「不许弹窗」负例⑥）。
    var patrolBusy by remember { mutableStateOf(false) }
    var patrolTick by remember { mutableIntStateOf(0) }
    var patrolGateWhy by remember { mutableStateOf<String?>(null) }
    var patrolFailWhy by remember { mutableStateOf<String?>(null) }
    val patrolCtx = LocalContext.current
    // P4-4 纪律：UI 作用域必须装 CoroutineCrashHandler
    val patrolScope = rememberCoroutineScope { CoroutineCrashHandler }

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

    // 第二百三十七批 §2.6：工具失败明细也归这一栏 ⇒ 空态判据要算上它
    // （只撞过工具失败、还没做过任何决策的会话，明细不能整页被「暂无记录」盖住）。
    val auditCount = remember(version) { FailureAuditLog.readAll().size }
    // 第二百三十九批 §2.4：巡按栏现在直接顶在记录页第一行，不参与整页空态判定——
    // 原先这里读一次巡按台账只为凑一个 patrolCount 去改空态条件，真机证明那条路走不通：
    // 新机器上一次都没巡过 ⇒ patrolCount == 0 ⇒ 空态成立 ⇒ 唯一的「巡一遍」入口跟着被盖掉。
    // §2.1 的两个入口，这一颗是手动那一个。判据（该不该跑、跑了查出什么）全在 `baopu/inspect/`，
    // 这里只起协程、把这一趟的下场放回本栏那一行字：**不开弹窗、不碰被纠的数据**。
    // 为什么协程起在界面而不是控制器——本批占用范围只有 inspect/ 与这一页，控制器不在范围内；
    // 「界面只渲染＋转发」那条房型让给了批次边界，偏差如实记进交付报告。
    val patrolLauncher: () -> Unit = {
        val gate = PatrolScheduler.manualGate(running = patrolBusy || controller.busy)
        if (gate.run) {
            patrolGateWhy = null
            patrolFailWhy = null
            patrolBusy = true
            patrolScope.launch {
                val result = withContext(Dispatchers.IO) {
                    PatrolRunner.run(patrolCtx, manual = true)
                }
                patrolBusy = false
                // 跑不成也要有字（[PatrolResult.failReason]）；没跑成就不涨水位，栏里留上一次。
                patrolFailWhy = result.failReason
                patrolTick += 1
            }
        } else {
            patrolGateWhy = gate.why
        }
    }

    // 整页空态改成列表首行那句（§2.4）：这一栏的巡按入口不能被它盖住。
    DecisionTabBody(DecisionTab.HISTORY, empty = false) {
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
                // 第二百三十九批 §2.4：巡按栏顶在记录页最上面——一行结论（≤40 字）＋点开明细，
                // 同审计栏交互。它必须在一台「一条记录都没有」的机器上也点得到：那一颗「巡一遍」
                // 是 §2.1 两个入口里唯一真接通的这一个（真机 16:11 实测：原先它跟空态一起被盖掉了）。
                item(key = "patrol") {
                    PatrolSection(
                        version = version,
                        tick = patrolTick,
                        expanded = patrolExpanded,
                        limit = patrolLimit,
                        busy = patrolBusy,
                        gateWhy = patrolGateWhy,
                        failWhy = patrolFailWhy,
                        onToggle = { patrolExpanded = !patrolExpanded },
                        onMore = { patrolLimit += InspectLimits.PAGE },
                        onPatrol = patrolLauncher,
                    )
                }
                // 记录与审计都空时，空态那句改在这里给——不再整页盖住上面那一栏。
                if (records.isEmpty() && auditCount == 0) {
                    item(key = "history-empty") {
                        Text(
                            text = stringResource(decisionEmptyText(DecisionTab.HISTORY)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp),
                        )
                    }
                }
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
                // 第二百三十七批 §2.6：工具失败审计明细（默认折叠的一栏，跟着列表滚）
                item(key = "failure-audit") {
                    FailureAuditSection(
                        version = version,
                        expanded = auditExpanded,
                        limit = auditLimit,
                        onToggle = { auditExpanded = !auditExpanded },
                        onMore = { auditLimit += FailureAuditLog.PAGE },
                    )
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

// ── 第二百三十七批 §2.6：工具失败审计明细（只读、默认折叠的一栏）──────────────

/**
 * 「工具失败审计」栏——折叠交互照上面那张组卡（[DecisionGroupCardView]），不新造组件。
 *
 * 四条纪律都在这段里：默认折叠（初值在 [HistoryTab]，重进本页回到折叠）；点一下展开、
 * 再点收起；一次最多渲染 [FailureAuditLog.PAGE] 行，剩下的点「加载更多」再补一页；
 * **只读**——失败没改过盘，所以行尾既没有回滚钮也没有别的动作口。
 * 明细只活在这本 JSONL 与这一页：模型那边永远只看得见一句聚合（`ToolFallback.auditBrief`），
 * 所以这里读盘渲染**不**回灌任何东西。
 *
 * 展开态与分页水位都由调用方持有：这一栏挂在 LazyColumn 的 item 里，状态自己留着
 * 会随滚出可视区销毁（§2.6 的「展开后不记得收起状态」负例）。
 */
@Composable
private fun FailureAuditSection(
    version: Int,
    expanded: Boolean,
    limit: Int,
    onToggle: () -> Unit,
    onMore: () -> Unit,
) {
    val count = remember(version) { FailureAuditLog.readAll().size }
    val rows = remember(version, expanded, limit) {
        if (expanded) FailureAuditLog.readAll(limit) else emptyList()
    }
    val now = remember(version) { System.currentTimeMillis() }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (count == 0) {
                    stringResource(R.string.decision_audit_fail_title_empty)
                } else {
                    stringResource(R.string.decision_audit_fail_title, count)
                },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.weight(1f))
            if (count > 0) {
                TextButton(
                    onClick = onToggle,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(
                        text = stringResource(
                            if (expanded) R.string.decision_record_collapse else R.string.decision_record_expand,
                        ),
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.decision_audit_fail_readonly),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (expanded) {
            rows.forEach { row -> FailureAuditRowView(row, now) }
            if (rows.size >= limit) {
                TextButton(
                    onClick = onMore,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.decision_audit_fail_more)) }
            }
        }
    }
}

/** 一行明细：工具·级别·结果 ／ 谁定的·多久前·档位 ／ 证据原文（§2.6 的那几列）。 */
@Composable
private fun FailureAuditRowView(row: FailureAuditRow, nowMs: Long) {
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 6.dp)) {
        Text(
            text = "${row.tool} · ${row.stage} · ${row.outcome}",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = "${stringResource(decisionActorLabel(DecisionActor.ofWire(row.actor)))}" +
                " · ${failureAgeText(row.atMs, nowMs)} · ${row.risk}（${row.reasonCode}）· 组 ${row.groupKey}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (row.evidence.isNotBlank()) {
            Text(
                text = row.evidence,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 「多久前」那半句（与组头/记录行同一套档，不另造时间格式）。 */
@Composable
private fun failureAgeText(atMs: Long, nowMs: Long): String {
    val age = decisionAgeOf(atMs, nowMs)
    return when (age.bucket) {
        DecisionTimeBucket.NOW -> stringResource(R.string.decision_record_time_now)
        DecisionTimeBucket.MINUTES -> stringResource(R.string.decision_record_time_min, age.n)
        DecisionTimeBucket.HOURS -> stringResource(R.string.decision_record_time_hour, age.n)
        DecisionTimeBucket.DAYS -> stringResource(R.string.decision_record_time_day, age.n)
        DecisionTimeBucket.OLDER -> stringResource(R.string.decision_record_time_older)
    }
}

/**
 * 第二百三十九批 §2.4 的「巡按」栏（勘验的输出落点）。
 *
 * 形制就是派工单那三行：折叠态只给一行结论（≤40 字，钳形在 `InspectText.line`，
 * 不在这里截）；点开才给正文两行＋六项明细＋更早的巡按。四条纪律在这段里都能看见：
 * - **默认折叠**、展开态由调用方持有（与 [FailureAuditSection] 同一个理由）；
 * - **零弹窗**：跑没跑成、闸门为什么拒，全是栏内的一行字，不另开 Dialog/AlertDialog；
 * - **只读**：这一栏唯一的写是 `PatrolRunner` 追加自己的巡按台账，被纠的技能正文与账本
 *   一个字节都不动（§5①由 `InspectReadOnlyGateTest` 从源码层钉，不靠这里自觉）；
 * - 明细只在这里渲染：它们同时只写在 `巡按记录.jsonl` 里，**没有**第二个出口通向模型
 *   （模型侧永远只拿一句 `PatrolReport.modelLine`＝标题那一句）。
 *
 * 读盘：一次巡按只写一行，台账一天最多长一行，`PatrolLedger.read` 又带水位上限 ⇒
 * 与 237 的审计栏同一口径，在组合里现读不会卡 UI 线程。
 */
@Composable
private fun PatrolSection(
    version: Int,
    tick: Int,
    expanded: Boolean,
    limit: Int,
    busy: Boolean,
    gateWhy: String?,
    failWhy: String?,
    onToggle: () -> Unit,
    onMore: () -> Unit,
    onPatrol: () -> Unit,
) {
    val entries = remember(version, tick, limit) {
        PatrolSources.memoryRoot()?.let { PatrolLedger.read(it, limit) } ?: emptyList()
    }
    val latest = entries.firstOrNull()
    val now = remember(version, tick) { System.currentTimeMillis() }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = latest?.headline ?: stringResource(R.string.decision_patrol_title_empty),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            if (latest != null) {
                TextButton(
                    onClick = onToggle,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(
                        text = stringResource(
                            if (expanded) R.string.decision_record_collapse else R.string.decision_record_expand,
                        ),
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.decision_patrol_readonly),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = onPatrol,
                enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(if (busy) R.string.decision_patrol_running else R.string.decision_patrol_run))
            }
            if (latest != null) {
                Text(
                    text = stringResource(
                        R.string.decision_patrol_meta,
                        failureAgeText(latest.atMs, now),
                        stringResource(
                            if (latest.manual) R.string.decision_patrol_mode_manual
                            else R.string.decision_patrol_mode_night,
                        ),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // 两句"没跑成"的话：闸门拒的（还没开跑）与跑坏了的（开跑了没结果）。都进不了弹窗。
        gateWhy?.let { why ->
            Text(
                text = stringResource(R.string.decision_patrol_gate, why),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        failWhy?.let { why ->
            Text(
                text = stringResource(R.string.decision_patrol_failed, why),
                style = MaterialTheme.typography.labelSmall,
                color = zhengdaoErrorText(),
            )
        }
        if (expanded && latest != null) {
            latest.lines.forEach { line ->
                Text(text = line, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                text = stringResource(R.string.decision_patrol_elapsed, latest.elapsedMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (latest.allowanceLine.isNotBlank()) {
                Text(
                    text = latest.allowanceLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            latest.findings.forEach { PatrolFindingRow(it) }
            if (entries.size > 1) {
                Text(
                    text = stringResource(R.string.decision_patrol_history_title),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 6.dp),
                )
                entries.drop(1).forEach { entry -> PatrolHistoryRow(entry, now) }
            }
            if (entries.size >= limit) {
                TextButton(
                    onClick = onMore,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.decision_audit_fail_more)) }
            }
        }
    }
}

/** 一项勘验的明细（检查名·认定·那一行 ／ 建议 ／ 证据行）。命中项上色，清白与查不了不上。 */
@Composable
private fun PatrolFindingRow(finding: InspectFinding) {
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 6.dp)) {
        Text(
            text = "${finding.check.cn} · ${patrolVerdictLabel(finding.verdict)} · ${finding.oneLine}",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = if (finding.verdict == InspectVerdict.HIT) {
                zhengdaoErrorText()
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        if (finding.suggestion.isNotBlank()) {
            Text(
                text = finding.suggestion,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        finding.evidence.forEach { row ->
            Text(
                text = row,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 更早的巡按：只一行结论（点开明细是"最近这一次"的事，历史不摊开，不然又是长篇报告）。 */
@Composable
private fun PatrolHistoryRow(entry: PatrolEntry, nowMs: Long) {
    Text(
        text = "${failureAgeText(entry.atMs, nowMs)} · " +
            "${stringResource(if (entry.manual) R.string.decision_patrol_mode_manual else R.string.decision_patrol_mode_night)}" +
            " · ${entry.headline}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 认定三态的人话（「查不了」必须与「查过没问题」是两个词——混了就是本批要纠的弊）。 */
@Composable
private fun patrolVerdictLabel(verdict: InspectVerdict): String = when (verdict) {
    InspectVerdict.HIT -> stringResource(R.string.decision_patrol_verdict_hit)
    InspectVerdict.CLEAR -> stringResource(R.string.decision_patrol_verdict_clear)
    InspectVerdict.NO_DATA -> stringResource(R.string.decision_patrol_verdict_nodata)
}
