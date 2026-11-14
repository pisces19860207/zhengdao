// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十四批 · 学习板块阶段一 · 单一入口（总则①）：治理中心里的「学习板块」一块——
// 观察开关（默认开，可关）＋数据摘要＋清空两口＋未学会清单。
//
// 提问卡复用 `BaopuAskUserCard`（203 房型原样：问题一行＋选项推荐第一＋必留空白行），
// 本文件不发明卡形状；触发与判据全在 `baopu/learn/LearnTriggers`（纯层），这里只画。
// 「就教这一步」只武装单步补录（ObserveCenter.requestTeachStep），不启示教录制。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.baopu.AskUserCard
import com.example.zhengdao.baopu.AskUserOption
import com.example.zhengdao.baopu.learn.LearnCardTexts
import com.example.zhengdao.baopu.learn.LearnToastKind
import com.example.zhengdao.baopu.learn.LearnTriggerType
import com.example.zhengdao.baopu.learn.NotLearnedItem
import com.example.zhengdao.baopu.learn.ObserveCenter
import com.example.zhengdao.baopu.learn.ObservePrefs
import com.example.zhengdao.core.RunLog
import com.example.zhengdao.ui.SectionCard
import com.example.zhengdao.ui.theme.zhengdaoSwitchColors
import kotlinx.coroutines.Dispatchers
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
