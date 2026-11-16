// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十四批 · 学习板块阶段一 · 单一入口（总则①）：治理中心里的「学习板块」一块——
// 观察开关（默认开，可关）＋数据摘要＋清空两口＋未学会清单。
//
// 提问卡复用 `BaopuAskUserCard`（203 房型原样：问题一行＋选项推荐第一＋必留空白行），
// 本文件不发明卡形状；触发与判据全在 `baopu/learn/LearnTriggers`（纯层），这里只画。
// 「就教这一步」只武装单步补录（ObserveCenter.requestTeachStep），不启示教录制。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.baopu.AskUserCard
import com.example.zhengdao.baopu.AskUserOption
import com.example.zhengdao.baopu.learn.AdjudicationCard
import com.example.zhengdao.baopu.learn.ConflictStepCard
import com.example.zhengdao.baopu.learn.DraftAdjudication
import com.example.zhengdao.baopu.learn.DraftChoice
import com.example.zhengdao.baopu.learn.DraftCheck
import com.example.zhengdao.baopu.learn.LearnCardTexts
import com.example.zhengdao.baopu.learn.LearnToastKind
import com.example.zhengdao.baopu.learn.LearnTriggerType
import com.example.zhengdao.baopu.learn.LocatorCandidate
import com.example.zhengdao.baopu.learn.NotLearnedItem
import com.example.zhengdao.baopu.learn.SkillDraft
import com.example.zhengdao.baopu.learn.SkillDraftRules
import com.example.zhengdao.baopu.learn.SkillDraftStore
import com.example.zhengdao.baopu.learn.ObserveCenter
import com.example.zhengdao.baopu.learn.ObservePrefs
import com.example.zhengdao.core.RunLog
import com.example.zhengdao.core.TimeFormats
import com.example.zhengdao.ui.SectionCard
import com.example.zhengdao.ui.theme.rChip
import com.example.zhengdao.ui.theme.zhengdaoSwitchColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import android.widget.Toast
import android.content.Context

/** 资源文案 → 卡片模板（install 与测试共用；模板里的 {步} 等由 LearnTriggers 替换）。 */
internal fun learnCardTexts(ctx: Context): LearnCardTexts = LearnCardTexts(
    qAmbiguous = ctx.getString(R.string.learn_q_ambiguous),
    qNoEvidence = ctx.getString(R.string.learn_q_no_evidence),
    qParam = ctx.getString(R.string.learn_q_param),
    qIntent = ctx.getString(R.string.learn_q_intent),
    optParam = ctx.getString(R.string.learn_opt_param),
    optFixed = ctx.getString(R.string.learn_opt_fixed),
    optTeach = ctx.getString(R.string.learn_opt_teach),
    optDrop = ctx.getString(R.string.learn_opt_drop),
    freeText = ctx.getString(R.string.learn_free_text),
)

/** 治理中心「学习板块」块（单一入口；状态只走 ObserveCenter 这一个真相源）。 */
@Composable
internal fun LearnBoardSection() {
    val context = LocalContext.current
    val state by ObserveCenter.notLearned.collectAsState()
    val active by ObserveCenter.sessionActive.collectAsState()
    var observeOn by rememberSaveable { mutableStateOf(ObservePrefs.on(context)) }
    var dataFiles by remember { mutableIntStateOf(0) }
    var confirmingData by remember { mutableStateOf(false) }
    var confirmingList by remember { mutableStateOf(false) }

    // 摘要行定期刷新（文件数在盘上；顺带让 24h 自清有机会跑——判据④的 UI 半边）
    LaunchedEffect(Unit) {
        while (true) {
            dataFiles = withContext(Dispatchers.IO) { ObserveCenter.dataFileCount() }
            kotlinx.coroutines.delay(30_000L)
        }
    }
    LearnToasts()

    SectionCard(title = stringResource(R.string.learn_section_title)) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.learn_switch_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.learn_switch_desc),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = observeOn,
                onCheckedChange = { want ->
                    ObservePrefs.set(context, want)
                    observeOn = want
                    ObserveCenter.refresh() // 关 ⇒ 立刻收会话＋丢弃在途批次（判据①）
                    RunLog.log("学习板块：观察开关 = $want")
                },
                colors = zhengdaoSwitchColors(),
            )
        }
        Text(
            text = stringResource(
                if (active) R.string.learn_session_active else R.string.learn_data_summary,
                dataFiles,
                state.count,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = { confirmingData = true }) { Text(stringResource(R.string.learn_clear_data)) }
            TextButton(onClick = { confirmingList = true }) { Text(stringResource(R.string.learn_clear_list)) }
        }
        Text(
            text = stringResource(R.string.learn_list_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 12.dp),
        )
        if (state.items.isEmpty()) {
            Text(
                text = stringResource(R.string.learn_list_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            state.items.forEach { item ->
                LearnItemCard(
                    item = item,
                    onAnswer = { text -> ObserveCenter.answer(item.id, text) },
                    onTeach = { ObserveCenter.requestTeachStep(item.id) },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        LearnDraftArea(Modifier.padding(top = 16.dp))
    }

    LearnClearDialogs(
        data = confirmingData,
        list = confirmingList,
        onDismissData = { confirmingData = false },
        onConfirmData = {
            confirmingData = false
            ObserveCenter.clearObservations()
            dataFiles = 0
        },
        onDismissList = { confirmingList = false },
        onConfirmList = {
            confirmingList = false
            ObserveCenter.clearItems()
        },
    )
}

/**
 * 技能草稿区（B/C 产物；单一入口）。要点：
 *   · 「从最近观察建草案」⇒ ObserveCenter.induceDraft(goal)——同意图自动归并（判据③），
 *     结构校验拒收当场说明（判据①②）；冲突草案标「两种走法待人裁」且**不给转正钮**（判据⑤）；
 *   · 「转正为技能」⇒ ObserveCenter.confirmDraft（人批）——经 TeachReplayStore 三闸落
 *     zl-teach/2，之后 643 候选链原样能吃（判据⑥）；未批前**不落技能目录**（不转正）。
 */
@Composable
private fun LearnDraftArea(modifier: Modifier = Modifier) {
    val drafts by ObserveCenter.drafts.collectAsState()
    val adjudications by ObserveCenter.adjudications.collectAsState()
    val scope = rememberCoroutineScope { com.example.zhengdao.keepalive.CoroutineCrashHandler }
    var goal by rememberSaveable { mutableStateOf("") }
    var inducing by remember { mutableStateOf(false) }
    var confirmingId by remember { mutableStateOf<String?>(null) }

    Column(modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.learn_draft_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (drafts.isEmpty()) {
            Text(
                text = stringResource(R.string.learn_draft_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            drafts.forEach { draft ->
                DraftRow(
                    draft = draft,
                    adjudications = adjudications[draft.id].orEmpty(),
                    onConfirm = { confirmingId = draft.id },
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = goal,
                onValueChange = { goal = it },
                placeholder = { Text(stringResource(R.string.learn_draft_goal_hint)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    inducing = true
                    scope.launch {
                        withContext(Dispatchers.IO) { ObserveCenter.induceDraft(goal.trim()) }
                        inducing = false
                        // 成败提示走 ObserveCenter 轻提示通道（LearnToasts 组合期 stringResource，
                        // LocalContext.getString 过不了 lint 硬闸——阶段一同款修正）
                    }
                },
                enabled = !inducing && goal.isNotBlank(),
            ) {
                Text(stringResource(R.string.learn_draft_induce))
            }
        }
    }
    if (confirmingId != null) {
        LearnDraftConfirmDialog(
            draftId = confirmingId.orEmpty(),
            onDismiss = { confirmingId = null },
        )
    }
}

/**
 * 一条草案行：goal＋归并标记＋冲突/偶发/参数标记＋步数，转正与弃两颗钮。
 *
 * 672 起冲突不再只是「不给转正」：下面直接挂裁定卡（[ConflictAdjudicateCard]），
 * **三选只有人能做**——裁完 A／B 才放行转正，裁 none ⇒ 回重新教这一步（判据①②）。
 * 判据本体在 `SkillDraftRules.checkForPromotion`（转正钮的 enabled 就是它的结论）。
 */
@Composable
private fun DraftRow(draft: SkillDraft, adjudications: List<DraftAdjudication>, onConfirm: () -> Unit) {
    val promotable = SkillDraftRules.checkForPromotion(draft, adjudications) is DraftCheck.Ok
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            text = draft.goal,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = buildList {
                if (draft.mergedFrom >= 2) add(stringResource(R.string.learn_draft_merged_mark, draft.mergedFrom))
                add(stringResource(R.string.learn_draft_steps_mark, draft.skeleton.size, draft.skeleton.sumOf { it.evidence.size }))
                if (draft.hasConflict()) add(stringResource(R.string.learn_draft_conflict_mark))
                val optionals = draft.skeleton.count { it.optional }
                if (optionals > 0) add(stringResource(R.string.learn_draft_optional_mark, optionals))
                val params = draft.params.joinToString("、") { it.name }
                if (params.isNotEmpty()) add(stringResource(R.string.learn_draft_param_mark, params))
            }.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = if (draft.hasConflict()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        if (draft.hasConflict()) {
            ConflictAdjudicateCard(draft = draft, adjudications = adjudications)
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            TextButton(onClick = onConfirm, enabled = promotable) {
                Text(stringResource(R.string.learn_draft_confirm))
            }
            TextButton(onClick = { ObserveCenter.discardDraft(draft.id) }) {
                Text(stringResource(R.string.learn_draft_discard))
            }
        }
        if (draft.hasConflict() && !promotable) {
            Text(
                text = stringResource(R.string.learn_draft_promote_blocked),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * 冲突裁定卡（672 判据③④）：并排两套定位（描述 · 命中数）＋一帧证据，三选 A／B／都不对。
 *
 * 位置＝治理中心「学习」区的草案行下面（与 E 卡同区，**不进对话流**）：裁定是人的事，
 * 不能挂在模型能说话的地方。命中数与证据都取「最近一次收口会话」的那份观察面；
 * 取不到就如实说「未聊/无证据」，不编数字、不崩。
 */
@Composable
private fun ConflictAdjudicateCard(draft: SkillDraft, adjudications: List<DraftAdjudication>) {
    val snapshot = remember(draft.id) { ObserveCenter.observation() }
    val cards = remember(draft, adjudications, snapshot) {
        AdjudicationCard.steps(draft, snapshot, adjudications)
    }
    if (cards.isEmpty()) return
    val pendingCount = cards.count { it.resolved == null }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(
            text = stringResource(R.string.learn_adj_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.learn_adj_intro, pendingCount),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        cards.forEach { card -> ConflictStepCardView(draft.id, card) }
    }
}

/** 一个冲突步：两列并排＋证据帧＋三选。 */
@Composable
private fun ConflictStepCardView(draftId: String, card: ConflictStepCard) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope { com.example.zhengdao.keepalive.CoroutineCrashHandler }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            text = stringResource(
                R.string.learn_adj_step,
                card.stepIndex,
                card.intent.ifBlank { card.kind },
            ),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = card.url,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LocatorSideView(card, card.a, Modifier.weight(1f))
            LocatorSideView(card, card.b, Modifier.weight(1f))
        }
        EvidenceFrameChip(
            text = stringResource(R.string.learn_adj_evidence_label, card.evidenceText()),
            missing = card.evidenceRef == null,
        )
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            AdjudicateButton(draftId, card, DraftChoice.A, R.string.learn_adj_pick_a, context, scope)
            AdjudicateButton(draftId, card, DraftChoice.B, R.string.learn_adj_pick_b, context, scope)
            AdjudicateButton(draftId, card, DraftChoice.NONE, R.string.learn_adj_pick_none, context, scope)
        }
        card.resolved?.let { choice ->
            Text(
                text = stringResource(
                    R.string.learn_adj_resolved,
                    choice.label(),
                    card.by ?: "-",
                    stampText(card.atMs),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (card.resolved == DraftChoice.NONE) {
            Text(
                text = stringResource(R.string.learn_adj_resolved_none),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = { ObserveCenter.armReteachStep(card.url, card.kind) }) {
                Text(stringResource(R.string.learn_adj_reteach))
            }
        }
    }
}

/** 并排的一侧：A／B 签＋命中数＋定位描述（描述里带路径，两套同名元素也分得开）。 */
@Composable
private fun LocatorSideView(card: ConflictStepCard, side: LocatorCandidate, modifier: Modifier) {
    Column(modifier) {
        Text(
            text = stringResource(
                if (side.slot == DraftChoice.A) R.string.learn_adj_slot_a else R.string.learn_adj_slot_b,
            ) + " · " + card.hitsText(side),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = side.desc,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** 一帧证据（622 快照引用的形状：文件#序号）；缺证据只显示「无证据」（判据③不崩）。 */
@Composable
private fun EvidenceFrameChip(text: String, missing: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (missing) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(top = 4.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(rChip))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** 三选之一（A／B／都不对）。点完由 ObserveCenter 记一行，提示照实说。 */
@Composable
private fun AdjudicateButton(
    draftId: String,
    card: ConflictStepCard,
    choice: DraftChoice,
    labelRes: Int,
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val picked = card.resolved == choice
    TextButton(
        onClick = {
            scope.launch {
                val msg = withContext(Dispatchers.IO) { ObserveCenter.adjudicate(draftId, card.stepIndex, choice) }
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        },
        enabled = !picked,
    ) {
        Text(
            text = stringResource(labelRes),
            fontWeight = if (picked) FontWeight.SemiBold else FontWeight.Normal,
            color = if (picked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 「已裁」一行里的时刻（盘上留毫秒，卡面照进程默认时区给人读）。
 *
 * 走 `:core` 的 [TimeFormats]（B-补-24 的唯一真源）——主源码里不许再逐次新建
 * `SimpleDateFormat`（`TimeFormatsTest` 的源码门禁会红，本单第一次就撞上了）。
 */
private fun stampText(atMs: Long): String =
    if (atMs <= 0L) "-" else TimeFormats.monthDay(atMs) + " " + TimeFormats.hourMinute(atMs)

/** 转正确认对话框：技能名＋参数默认值（每行「名=值」；空值＝执行端参数链如实处理）。 */
@Composable
private fun LearnDraftConfirmDialog(draftId: String, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope { com.example.zhengdao.keepalive.CoroutineCrashHandler }
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf("") }
    var defaults by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.learn_draft_confirm)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text(stringResource(R.string.learn_draft_confirm_name_hint)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = defaults,
                    onValueChange = { defaults = it },
                    placeholder = { Text(stringResource(R.string.learn_draft_confirm_defaults_hint)) },
                    minLines = 2,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && name.isNotBlank(),
                onClick = {
                    busy = true
                    scope.launch {
                        val msg = withContext(Dispatchers.IO) {
                            ObserveCenter.confirmDraft(draftId, name.trim(), parseDefaults(defaults))
                        }
                        busy = false
                        onDismiss()
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                },
            ) { Text(stringResource(R.string.learn_draft_confirm_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.learn_confirm_cancel)) } },
    )
}

private fun parseDefaults(text: String): Map<String, String> = text.lines()
    .mapNotNull { line ->
        val i = line.indexOf('=')
        if (i <= 0) return@mapNotNull null
        line.substring(0, i).trim() to line.substring(i + 1).trim()
    }.toMap()

/** 轻提示出口（ObserveCenter.lastToast 按 seq 弹 Toast；两个宿主共用这一件）。 */
@Composable
internal fun LearnToasts() {
    val context = LocalContext.current
    val toast = ObserveCenter.lastToast.collectAsState()
    // 文案在组合期用 stringResource 解析（配置感知；LocalContext.getString 过不了 lint 硬闸）
    val text = when (toast.value?.kind) {
        LearnToastKind.ANSWERED -> stringResource(R.string.learn_answered_toast)
        LearnToastKind.CAPTURE_WAITING -> stringResource(R.string.learn_capture_waiting_toast)
        LearnToastKind.TAUGHT -> stringResource(R.string.learn_taught_toast)
        LearnToastKind.KEEP_AMBIGUOUS -> stringResource(R.string.learn_keep_ambiguous_toast)
        LearnToastKind.CAPTURE_TIMEOUT -> stringResource(R.string.learn_capture_timeout_toast)
        LearnToastKind.DRAFT_CREATED -> stringResource(R.string.learn_draft_created_toast)
        LearnToastKind.DRAFT_MERGED -> stringResource(R.string.learn_draft_merged_toast, toast.value?.arg?.toIntOrNull() ?: 0)
        LearnToastKind.DRAFT_REJECTED -> stringResource(R.string.learn_draft_rejected_toast, toast.value?.arg ?: "")
        null -> ""
    }
    LaunchedEffect(toast.value) {
        if (toast.value == null || text.isEmpty()) return@LaunchedEffect
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun LearnClearDialogs(
    data: Boolean,
    list: Boolean,
    onDismissData: () -> Unit,
    onConfirmData: () -> Unit,
    onDismissList: () -> Unit,
    onConfirmList: () -> Unit,
) {
    if (data) {
        AlertDialog(
            onDismissRequest = onDismissData,
            title = { Text(stringResource(R.string.learn_clear_data)) },
            text = { Text(stringResource(R.string.learn_clear_data_confirm)) },
            confirmButton = { TextButton(onClick = onConfirmData) { Text(stringResource(R.string.learn_confirm_ok)) } },
            dismissButton = { TextButton(onClick = onDismissData) { Text(stringResource(R.string.learn_confirm_cancel)) } },
        )
    }
    if (list) {
        AlertDialog(
            onDismissRequest = onDismissList,
            title = { Text(stringResource(R.string.learn_clear_list)) },
            text = { Text(stringResource(R.string.learn_clear_list_confirm)) },
            confirmButton = { TextButton(onClick = onConfirmList) { Text(stringResource(R.string.learn_confirm_ok)) } },
            dismissButton = { TextButton(onClick = onDismissList) { Text(stringResource(R.string.learn_confirm_cancel)) } },
        )
    }
}

/**
 * 清单上的一条＝一张问题卡＋「就教这一步」。
 *
 * 卡面走 `BaopuAskUserCard`（203 房型原样）；类型签与步号是本单的上下文行。
 * 答案（选项或自由文本）交 [onAnswer]；[onTeach] 只武装单步补录。
 */
@Composable
internal fun LearnItemCard(
    item: NotLearnedItem,
    onAnswer: (String) -> Unit,
    onTeach: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(typeLabel(item.type)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.learn_step_label, item.stepSeq, item.pageName.ifBlank { item.url }),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        BaopuAskUserCard(
            card = AskUserCard(
                question = item.question,
                options = item.options.mapIndexed { i, label -> AskUserOption(id = "o$i", label = label, recommended = i == 0) },
                freeTextPrompt = item.freeTextPrompt,
            ),
            onPick = { opt -> onAnswer(opt.label) },
            onFreeText = { text -> onAnswer(text) },
            modifier = Modifier.padding(top = 4.dp),
        )
        Button(
            onClick = onTeach,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) {
            Text(stringResource(R.string.learn_teach_this_step))
        }
    }
}

private fun typeLabel(type: LearnTriggerType): Int = when (type) {
    LearnTriggerType.AMBIGUOUS_LOCATOR -> R.string.learn_type_ambiguous
    LearnTriggerType.NO_EVIDENCE -> R.string.learn_type_no_evidence
    LearnTriggerType.PARAM_UNCERTAIN -> R.string.learn_type_param
    LearnTriggerType.INTENT_UNCERTAIN -> R.string.learn_type_intent
}
