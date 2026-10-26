// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.schedule

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.zhengdao.R
import com.example.zhengdao.core.RunLog
import com.example.zhengdao.keepalive.CoroutineCrashHandler
import com.example.zhengdao.ui.FilterChip2
import com.example.zhengdao.ui.SectionCard
import com.example.zhengdao.ui.SettingsDivider
import com.example.zhengdao.ui.SettingsSubPage
import com.example.zhengdao.ui.theme.zhengdaoErrorText
import com.example.zhengdao.ui.theme.zhengdaoReady
import com.example.zhengdao.ui.theme.zhengdaoSwitchColors
import com.example.zhengdao.ui.util.pressClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * 定时任务页（第一百六十七批 §1「列表：启停、编辑、删除、下次运行时间、上次结果」）。
 *
 * 为什么这一页放在 `schedule/` 包里而不是 `ui/`：派工单 §0.5 把本批可动的范围钉在
 * 「`schedule/` 新包 ＋ 设置页权限区 ＋ manifest ＋ strings」，页面跟着它服务的判据走，
 * 并表时整包一起搬，不会在 `ui/` 里留一块没人认领的孤儿。
 *
 * 这一页自己**不做任何时间数学**：下一次时刻、槽位、启停迁移全部问 [ScheduleRules]，
 * 读写全部走 [ScheduleStore]，补跑全部交给 [ScheduleRunner] —— 页面只是这三样的一个视图。
 */
class ScheduleTasksActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            com.example.zhengdao.ui.theme.ZhengdaoTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ScheduleTasksPage(onBack = { finish() })
                }
            }
        }
    }
}

/** 任务自己的时区里读时刻（不能用 util/TimeFormats：那套 formatter 绑的是**手机当前时区**，跨时区任务会读错）。 */
private val ZONE_HOUR_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
private val ZONE_DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
private val ZONE_STAMP_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.US)

@Composable
private fun ScheduleTasksPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val res = LocalResources.current
    // P4-4 纪律：UI 作用域必须装 CoroutineCrashHandler（门禁扫「没装 handler 的裸作用域」）
    val scope = rememberCoroutineScope { CoroutineCrashHandler }
    val snackbar = remember { SnackbarHostState() }

    var tick by remember { mutableIntStateOf(0) }
    var tasks by remember { mutableStateOf<List<ScheduleTask>>(emptyList()) }
    var corrupt by remember { mutableStateOf(false) }
    var exactOk by remember { mutableStateOf(SchedulePermissions.canScheduleExactAlarms(ctx)) }
    var editing by remember { mutableStateOf<ScheduleTask?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ScheduleTask?>(null) }

    // 补跑只做一次（同一个页面实例）：派工单 §1 的原话是「下次打开补跑一次」。
    // remember 而不是 LaunchedEffect(Unit)：ON_RESUME 会反复把 tick 推一格，
    // 用 Unit 当键根本轮不到它，用 tick 当键又每次都补——那是"每次回到前台都灌一条"。
    val catchUpDone = remember { mutableStateOf(false) }

    // 从系统「精确闹钟」页回来时重读：那页没有 ActivityResult 回执，只能靠 ON_RESUME。
    // 这一页只读这一项——通知/电池两项的状态在设置页那张卡里（一处一套控件）。
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                exactOk = SchedulePermissions.canScheduleExactAlarms(ctx)
                tick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(tick) {
        val loaded = withContext(Dispatchers.IO) {
            if (!catchUpDone.value) {
                catchUpDone.value = true
                runCatching { ScheduleRunner.catchUpOnOpen(ctx) }
                    .onFailure { RunLog.log("定时任务：打开时补跑没跑成 ${it.javaClass.simpleName}: ${it.message}") }
                // 开机后闹钟会被系统清掉（官方口径：闹钟默认不跨重启；WorkManager 的作业由系统自己恢复）。
                // 打开这一页时顺手把闹钟补登记回去——同一槽位重排是 REPLACE，幂等，不会多响一次。
                runCatching { ScheduleRunner.rescheduleAll(ctx) }
                    .onFailure { RunLog.log("定时任务：打开时重排没成 ${it.javaClass.simpleName}: ${it.message}") }
            }
            ScheduleStore.of(ctx).load()
        }
        tasks = loaded.tasks
        corrupt = loaded.status == LoadStatus.CORRUPT_RECOVERED
    }

    /** 写表 ＋ 重排（都在 IO：save 是文件写，rescheduleAll 里还要读一次表）。 */
    fun persist(rows: List<ScheduleTask>, cancel: List<ScheduleTask> = emptyList()) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                cancel.forEach { runCatching { ScheduleScheduler.cancelSlot(ctx, it) } }
                val saved = ScheduleStore.of(ctx).save(rows)
                if (saved) runCatching { ScheduleRunner.rescheduleAll(ctx) }
                saved
            }
            if (ok) {
                tasks = rows
            } else {
                // 派工单 §0.5「失败不静默当成功」：没落盘就说出来，不改内存里的列表骗人。
                snackbar.showSnackbar(res.getString(R.string.sched_err_save))
            }
        }
    }

    fun saveTask(task: ScheduleTask, isNew: Boolean) {
        creating = false
        editing = null
        val rows = if (isNew) tasks + task else tasks.map { if (it.id == task.id) task else it }
        persist(rows)
    }

    fun toggle(task: ScheduleTask, on: Boolean) {
        val next = if (on) ScheduleRules.afterEnable(task, System.currentTimeMillis())
        else ScheduleRules.afterDisable(task)
        persist(
            tasks.map { if (it.id == task.id) next else it },
            cancel = if (on) emptyList() else listOf(task),
        )
    }

    /** 删除确认后写表 ＋ 重排（见 [persist]）。 */
    fun deleteConfirmed(task: ScheduleTask) {
        deleting = null
        persist(tasks.filter { it.id != task.id }, cancel = listOf(task))
    }

    SettingsSubPage(title = stringResource(R.string.sched_page_title), onBack = onBack, snackbar = snackbar) {
        if (corrupt) {
            SectionCard(stringResource(R.string.sched_card_tasks)) {
                Text(
                    text = stringResource(R.string.sched_corrupt_note, ScheduleStore.corruptFileName()),
                    style = MaterialTheme.typography.bodySmall,
                    color = zhengdaoErrorText(),
                )
            }
        }

        SectionCard(stringResource(R.string.sched_card_tasks)) {
            // 「走的是哪条路」在这一页顶部先说出来（派工单 §5 ①）：判据与调度器同一处取。
            Text(
                text = stringResource(
                    if (SchedulePermissions.route(ctx) == ScheduleRules.Route.EXACT_ALARM_PLUS_WORK) {
                        R.string.sched_route_exact
                    } else {
                        R.string.sched_route_work
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (exactOk) zhengdaoReady() else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 走的是降级路径时给一条**出路**，不只报状态。第二百一十批（入口与权限归位）：
            // 出路从「按钮直发系统精确闹钟页」改成一行指路文案——直接拉起系统授权页是
            // 散落的申请入口，全 App 只许「设置 → 申请权限」卡那一处发起（源码门禁
            // PermissionEntryGateTest）。本行的路线文字仍由 SchedulePermissions.route() 决定，
            // 判定一格没动。
            if (!exactOk) {
                Text(
                    text = stringResource(R.string.sched_exact_goto),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (tasks.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.sched_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            tasks.forEachIndexed { i, task ->
                if (i > 0) SettingsDivider()
                TaskRow(
                    task = task,
                    onToggle = { on -> toggle(task, on) },
                    onEdit = { editing = task },
                    onDelete = { deleting = task },
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { creating = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.sched_btn_new)) }
        }

        // ── 开始前提醒（第二百二十九批 §2）────────────────────────────────────
        // 「定时任务开始前提醒」开关：**默认开**；关掉则到点只把 prompt 发进会话，
        // 不先响那一声（判据在 SchedulePreNotifyRules，开关在 SchedulePreNotify）。
        // 权限横幅：通知权限没给 ⇒ 提醒发不出**不许静默**——RunLog 由
        // ScheduleNotifier.postStarting 落，这里把后果说在明处；申请入口仍在
        // 设置页「申请权限」集中卡（一处一套，PermissionEntryGateTest 盯着）。
        SectionCard(stringResource(R.string.sched_pre_notify_title)) {
            var preOn by remember { mutableStateOf(SchedulePreNotify.enabled(ctx)) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.sched_pre_notify_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = preOn,
                    onCheckedChange = { want ->
                        SchedulePreNotify.set(ctx, want)
                        preOn = want
                    },
                    colors = zhengdaoSwitchColors(),
                )
            }
            // 与 exactOk 同一刷新时机：从系统权限页回来（ON_RESUME）重读一次。
            val notifOk = remember(tick) { SchedulePermissions.notificationsGranted(ctx) }
            if (!notifOk) {
                Text(
                    text = stringResource(R.string.sched_perm_missing_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = zhengdaoErrorText(),
                )
            }
        }

        // 三行权限**只在设置页有**（见 ui/SettingsDataActivity 的「定时任务用到的权限」卡）。
        // 这里不重复一份开关：两处各一套控件迟早给出两个结论（本仓 E2「消双判」的教训），
        // 而这一页顶部那一行已经用同一个 SchedulePermissions.route() 把当前走的路说清楚了。

        if (creating) {
            ScheduleEditorDialog(
                existing = null,
                onDismiss = { creating = false },
                onSave = { saveTask(it, isNew = true) },
            )
        }
        editing?.let { current ->
            ScheduleEditorDialog(
                existing = current,
                onDismiss = { editing = null },
                onSave = { saveTask(it, isNew = false) },
            )
        }
        deleting?.let { target ->
            AlertDialog(
                onDismissRequest = { deleting = null },
                title = { Text(stringResource(R.string.sched_delete_title)) },
                text = { Text(stringResource(R.string.sched_delete_desc, target.title)) },
                confirmButton = {
                    TextButton(onClick = { deleteConfirmed(target) }) {
                        Text(stringResource(R.string.sched_delete_action))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.common_cancel)) }
                },
            )
        }
    }
}

/** 一行的样子：标题＋开关在上，「下次」在中，「上次结果」在下，整行点开编辑。 */
@Composable
private fun TaskRow(
    task: ScheduleTask,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pressClickable(onClickLabel = stringResource(R.string.sched_edit_title), onClick = onEdit)
            .padding(vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = task.title.ifBlank { task.prompt },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = task.enabled,
                onCheckedChange = onToggle,
                colors = zhengdaoSwitchColors(),
            )
        }
        Spacer(Modifier.height(4.dp))
        // 「每天 08:00」这样一行是这条任务的身份：列表里同时有五六条时，光看标题分不清哪条是哪条。
        Text(
            text = stringResource(
                R.string.sched_sum_line,
                stringResource(freqRes(task.freq)),
                ZONE_HOUR_FMT.format(ScheduleRules.localTimeOf(task.minuteOfDay)),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = nextRunText(task),
            style = MaterialTheme.typography.bodySmall,
            color = if (task.enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = lastRunText(task),
            style = MaterialTheme.typography.labelSmall,
            // 失败/没排进去要用红色说出来（派工单 §1「不静默」）；其余是中性信息。
            color = if (task.lastOutcome.needsNotice) zhengdaoErrorText()
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        task.lastDetail.takeIf { task.lastOutcome.needsNotice }?.let {
            Text(
                text = detailText(it),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onEdit) { Text(stringResource(R.string.sched_edit_title)) }
            TextButton(onClick = onDelete) { Text(stringResource(R.string.sched_delete_action)) }
        }
    }
}

/** 「下次：今天 08:00（Asia/Tokyo）」；暂停／没有下次各有各的说法。 */
@Composable
private fun nextRunText(task: ScheduleTask): String {
    if (!task.enabled) return stringResource(R.string.sched_state_paused)
    if (task.nextRunAtMs == ScheduleRules.NO_RUN) return stringResource(R.string.sched_state_no_next)
    val local = ScheduleRules.localOf(task.nextRunAtMs, task.zone)
        ?: return stringResource(R.string.sched_state_no_next)
    val clock = ZONE_HOUR_FMT.format(local)
    val day = when (ScheduleRules.dayOffsetOf(task.nextRunAtMs, task.zone, System.currentTimeMillis())) {
        // 锚点算不出本地时刻（理论上只在时区串坏掉时发生）：与上面 localOf 的 null 同一说法。
        ScheduleRules.DayOffset.NONE -> return stringResource(R.string.sched_state_no_next)
        ScheduleRules.DayOffset.TODAY -> stringResource(R.string.sched_next_today, clock)
        ScheduleRules.DayOffset.TOMORROW -> stringResource(R.string.sched_next_tomorrow, clock)
        ScheduleRules.DayOffset.OTHER -> stringResource(
            R.string.sched_next_date,
            ZONE_DATE_FMT.format(local),
            clock,
        )
    }
    val zoneSuffix = if (ScheduleRules.isDeviceZone(task.zone)) ""
    else stringResource(R.string.sched_zone_suffix, task.zone)
    return stringResource(R.string.sched_next_at, day + zoneSuffix)
}

@Composable
private fun lastRunText(task: ScheduleTask): String {
    val outcome = stringResource(outcomeRes(task.lastOutcome))
    if (task.lastRunAtMs <= 0L) return stringResource(R.string.sched_last_never)
    val local = ScheduleRules.localOf(task.lastRunAtMs, task.zone)
    val stamp = local?.let { ZONE_STAMP_FMT.format(it) } ?: "-"
    return stringResource(R.string.sched_last_line, outcome, stamp)
}

/** 结局 → 文案键（枚举本身不产字，见 ScheduleOutcome 上方那段说明）。 */
@androidx.annotation.StringRes
private fun outcomeRes(o: ScheduleOutcome): Int = when (o) {
    ScheduleOutcome.NONE -> R.string.sched_out_none
    ScheduleOutcome.FIRED -> R.string.sched_out_fired
    ScheduleOutcome.FIRED_LATE -> R.string.sched_out_fired_late
    ScheduleOutcome.QUEUED -> R.string.sched_out_queued
    ScheduleOutcome.MISSED -> R.string.sched_out_missed
    ScheduleOutcome.REFUSED_BUSY -> R.string.sched_out_refused
    ScheduleOutcome.FAILED -> R.string.sched_out_failed
}

/**
 * 原因那一行：已知的几个落库词翻成人话，其余（异常类名原文）照显。
 *
 * 为什么不全部翻译：`lastDetail` 里也可能是 `SecurityException:...` 这类原文，
 * 硬翻成「出错了」会把用户唯一能拿去对日志的线索抹掉。
 */
@Composable
private fun detailText(detail: String): String = when (detail) {
    "other_session_busy" -> stringResource(R.string.sched_detail_other_busy)
    "queue_full" -> stringResource(R.string.sched_detail_queue_full)
    "empty_prompt" -> stringResource(R.string.sched_detail_empty)
    "retry_limit" -> stringResource(R.string.sched_detail_retry_limit)
    else -> detail
}

/**
 * 新建/编辑表单（派工单 §1 那五项：标题、prompt、频率、时间、时区）。
 *
 * 校验只问一处 [ScheduleTask.validate]——页面不自己再判一遍日期格式，
 * 否则「存进去解得开、界面上判不过」这种分裂迟早出现。
 */
@Composable
private fun ScheduleEditorDialog(
    existing: ScheduleTask?,
    onDismiss: () -> Unit,
    onSave: (ScheduleTask) -> Unit,
) {
    val res = LocalResources.current
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var prompt by remember { mutableStateOf(existing?.prompt ?: "") }
    var freq by remember { mutableStateOf(existing?.freq ?: ScheduleFreq.DAILY) }
    val initMinute = existing?.minuteOfDay ?: (9 * 60)
    var hourText by remember { mutableStateOf((initMinute / 60).toString()) }
    var minuteText by remember { mutableStateOf((initMinute % 60).toString().padStart(2, '0')) }
    var dayOfWeek by remember { mutableIntStateOf(existing?.dayOfWeek?.takeIf { it in 1..7 } ?: LocalDate.now().dayOfWeek.value) }
    var dateText by remember {
        mutableStateOf(existing?.onceDate?.takeIf { it.isNotBlank() } ?: LocalDate.now().toString())
    }
    var zoneText by remember { mutableStateOf(existing?.zone ?: "") }
    var formError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (existing == null) R.string.sched_edit_new_title else R.string.sched_edit_title,
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text(stringResource(R.string.sched_field_prompt)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.sched_field_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.sched_field_freq),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScheduleFreq.entries.forEach { f ->
                        FilterChip2(
                            label = stringResource(freqRes(f)),
                            selected = freq == f,
                            onClick = { freq = f },
                        )
                    }
                }
                if (freq == ScheduleFreq.WEEKLY) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        (1..7).forEach { d ->
                            FilterChip2(
                                label = stringResource(dayRes(d)),
                                selected = dayOfWeek == d,
                                onClick = { dayOfWeek = d },
                            )
                        }
                    }
                }
                if (freq == ScheduleFreq.ONCE) {
                    OutlinedTextField(
                        value = dateText,
                        onValueChange = { dateText = it },
                        label = { Text(stringResource(R.string.sched_field_date)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    stringResource(R.string.sched_field_time),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(
                        value = hourText,
                        onChange = { hourText = it },
                        label = stringResource(R.string.sched_field_hour),
                        modifier = Modifier.width(88.dp),
                    )
                    NumberField(
                        value = minuteText,
                        onChange = { minuteText = it },
                        label = stringResource(R.string.sched_field_minute),
                        modifier = Modifier.width(88.dp),
                    )
                }
                OutlinedTextField(
                    value = zoneText,
                    onValueChange = { zoneText = it },
                    label = { Text(stringResource(R.string.sched_field_zone)) },
                    supportingText = {
                        Text(stringResource(R.string.sched_field_zone_hint), style = MaterialTheme.typography.labelSmall)
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                formError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = zhengdaoErrorText())
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val trimmedPrompt = prompt.trim()
                if (trimmedPrompt.isEmpty()) {
                    formError = res.getString(R.string.sched_err_prompt)
                    return@TextButton
                }
                val hour = hourText.toIntOrNull() ?: -1
                val minute = minuteText.toIntOrNull() ?: -1
                val minuteOfDay = hour * 60 + minute
                val zone = zoneText.trim().ifBlank { ZoneId.systemDefault().id }
                val onceDate = if (freq == ScheduleFreq.ONCE) dateText.trim() else ""
                val dow = if (freq == ScheduleFreq.WEEKLY) dayOfWeek else 0
                val fieldError = ScheduleTask.validate(freq, minuteOfDay, dow, onceDate, zone)
                if (fieldError != null) {
                    formError = res.getString(R.string.sched_err_fields, fieldError)
                    return@TextButton
                }
                val now = System.currentTimeMillis()
                val next = ScheduleRules.nextRunAfter(freq, minuteOfDay, dow, onceDate, zone, now)
                if (next == ScheduleRules.NO_RUN) {
                    formError = res.getString(R.string.sched_err_past)
                    return@TextButton
                }
                val finalTitle = title.trim().ifEmpty { trimmedPrompt.take(24) }
                val task = existing?.let {
                    ScheduleTask.withEdit(
                        old = it,
                        title = finalTitle,
                        prompt = trimmedPrompt,
                        freq = freq,
                        minuteOfDay = minuteOfDay,
                        dayOfWeek = dow,
                        onceDate = onceDate,
                        nextRunAtMs = next,
                    )
                } ?: ScheduleTask.draft(
                    id = "t" + UUID.randomUUID().toString().take(8),
                    title = finalTitle,
                    prompt = trimmedPrompt,
                    freq = freq,
                    minuteOfDay = minuteOfDay,
                    dayOfWeek = dow,
                    onceDate = onceDate,
                    zone = zone,
                    nextRunAtMs = next,
                    nowMs = now,
                )
                onSave(task)
            }) { Text(stringResource(R.string.sched_btn_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/** 时/分两个小格：只收数字，越界交给 [ScheduleTask.validate] 统一说（页面不自己判第二遍）。 */
@Composable
private fun NumberField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(2)) },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = modifier.heightIn(min = 56.dp),
    )
}

@androidx.annotation.StringRes
private fun freqRes(f: ScheduleFreq): Int = when (f) {
    ScheduleFreq.ONCE -> R.string.sched_freq_once
    ScheduleFreq.DAILY -> R.string.sched_freq_daily
    ScheduleFreq.WEEKLY -> R.string.sched_freq_weekly
}

@androidx.annotation.StringRes
private fun dayRes(iso: Int): Int = when (iso) {
    1 -> R.string.sched_day_1
    2 -> R.string.sched_day_2
    3 -> R.string.sched_day_3
    4 -> R.string.sched_day_4
    5 -> R.string.sched_day_5
    6 -> R.string.sched_day_6
    else -> R.string.sched_day_7
}
