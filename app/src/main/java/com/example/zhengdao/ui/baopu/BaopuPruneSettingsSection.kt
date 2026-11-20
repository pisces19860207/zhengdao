// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百五十八批 · 修剪界面（757 图纸 §2.1/§2.2 施工）：设置页「记忆修剪」分区
// （三控件＋`已退役 N 条`入口）＋「已退役」列表页（三列＋三动作）。
//
// 判据层零改动（757 §五.1）：本文件只消费 PruneSettings / PruneRetired / PruneGates，
// 不判任何修剪判据。写口纪律（757 §三映射表，PruneUiGateTest 按行块钉死）：
//   · 总开关只写 PruneSettings.setEnabled；周期只写 setSchedule；阈值只写 setRetireDays；
//   · 第四写口（includeRetired）在检索卡 BaopuPhoneSearchCard 的页面 state——本文件零出现。
// 盘/库 IO 全在 Dispatchers.IO（produceState / withContext，441 纪律：IO 读、主线程写状态）；
// 协程作用域装 CoroutineCrashHandler（P4-4，门禁扫裸作用域）。
// 「不管」＝零数据写入仅收起详情（678「先不管」口径）；「恢复/永久保留」的读数与
// 失败文案全来自 PruneGates 的装配核（如实说不装成功）。
package com.example.zhengdao.ui.baopu

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.example.zhengdao.baopu.BaopuRuntime
import com.example.zhengdao.baopu.memoryprune.PruneLedger
import com.example.zhengdao.baopu.memoryprune.PruneSettings
import com.example.zhengdao.baopu.memoryprune.RetiredRow
import com.example.zhengdao.baopu.memoryprune.keepRetired
import com.example.zhengdao.baopu.memoryprune.retiredCountOf
import com.example.zhengdao.baopu.memoryprune.retiredEntryTextOf
import com.example.zhengdao.baopu.memoryprune.retiredRowsOf
import com.example.zhengdao.baopu.memoryprune.restoreRetired
import com.example.zhengdao.core.RunLog
import com.example.zhengdao.core.TimeFormats
import com.example.zhengdao.keepalive.CoroutineCrashHandler
import com.example.zhengdao.ui.FilterChip2
import com.example.zhengdao.ui.SectionCard
import com.example.zhengdao.ui.SettingsSubPage
import com.example.zhengdao.ui.theme.zhengdaoSwitchColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页「记忆修剪」分区（757 §2.1）：通知句＋总开关＋周期三档＋阈值四档＋
 * `已退役 N 条`入口（N 扫当前库；N=0 ⇒ 入口行不显示）。点入口拉「已退役」列表页。
 */
@Composable
internal fun BaopuPruneSettingsSection() {
    val ctx = LocalContext.current
    val initial = remember { PruneSettings.get(ctx) }
    var enabled by rememberSaveable { mutableStateOf(initial.enabled) }
    var schedule by rememberSaveable { mutableStateOf(initial.schedule) }
    var retireDays by rememberSaveable { mutableIntStateOf(initial.retireAfterDays) }
    var listOpen by rememberSaveable { mutableStateOf(false) }
    // 列表页里恢复/保留过 ⇒ 关页时重扫一次入口计数（IO 读、主线程写）。
    var entryTick by remember { mutableIntStateOf(0) }
    val entryLine by produceState<String?>(initialValue = null, key1 = entryTick) {
        value = withContext(Dispatchers.IO) {
            retiredEntryTextOf(retiredCountOf(BaopuRuntime.memoryStore(ctx).loadAll()))
        }
    }
    Spacer(Modifier.height(12.dp))
    SectionCard(stringResource(R.string.baopu_prune_settings_title)) {
        // 通知句（757 §2.3 渲染位置①：设置页分区必显；资源值整串钉死在门禁）。
        Text(
            text = stringResource(R.string.baopu_prune_retired_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        PruneEnabledRow(ctx, enabled) { want -> enabled = want }
        PruneScheduleRow(ctx, schedule) { want -> schedule = want }
        PruneRetireDaysRow(ctx, retireDays) { want -> retireDays = want }
        // 入口行（数据投影直render；N=0 时投影为 null，行不出现）。
        entryLine?.let { line ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { listOpen = true },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.common_expand_arrow),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (listOpen) {
        BaopuRetiredListDialog(onDismiss = { listOpen = false; entryTick++ })
    }
}

/**
 * 总开关行（757 §三 行1：唯一写口 [PruneSettings.setEnabled]；映射门禁按本行块抽取）。
 * 关 ⇒ 行内显示「记忆修剪已关闭」（757 §2.1.3：与周期关的文案分开）。
 */
@Composable
private fun PruneEnabledRow(ctx: Context, enabled: Boolean, onState: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.baopu_prune_enabled_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.baopu_prune_enabled_desc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!enabled) {
                Text(
                    text = stringResource(R.string.baopu_prune_enabled_off_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = enabled,
            onCheckedChange = { want ->
                PruneSettings.setEnabled(ctx, want)
                onState(want)
                RunLog.log("抱朴设置：记忆修剪总开关 = $want")
            },
            colors = zhengdaoSwitchColors(),
        )
    }
}

/**
 * 周期行（757 §三 行2：唯一写口 [PruneSettings.setSchedule]；值域闭集 关/每天/每周）。
 * 周期是频率门不是定时器（判据在 PruneGates.pruneScheduleDue）；＝关显示「当前周期：关」。
 */
@Composable
private fun PruneScheduleRow(ctx: Context, schedule: String, onState: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.baopu_prune_schedule_title),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.baopu_prune_schedule_desc),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip2(
                label = stringResource(R.string.baopu_prune_schedule_off),
                selected = schedule == PruneSettings.SCHEDULE_OFF,
            ) {
                PruneSettings.setSchedule(ctx, PruneSettings.SCHEDULE_OFF)
                onState(PruneSettings.SCHEDULE_OFF)
                RunLog.log("抱朴设置：记忆修剪周期 = off")
            }
            FilterChip2(
                label = stringResource(R.string.baopu_prune_schedule_daily),
                selected = schedule == PruneSettings.SCHEDULE_DAILY,
            ) {
                PruneSettings.setSchedule(ctx, PruneSettings.SCHEDULE_DAILY)
                onState(PruneSettings.SCHEDULE_DAILY)
                RunLog.log("抱朴设置：记忆修剪周期 = daily")
            }
            FilterChip2(
                label = stringResource(R.string.baopu_prune_schedule_weekly),
                selected = schedule == PruneSettings.SCHEDULE_WEEKLY,
            ) {
                PruneSettings.setSchedule(ctx, PruneSettings.SCHEDULE_WEEKLY)
                onState(PruneSettings.SCHEDULE_WEEKLY)
                RunLog.log("抱朴设置：记忆修剪周期 = weekly")
            }
        }
        if (schedule == PruneSettings.SCHEDULE_OFF) {
            Text(
                text = stringResource(R.string.baopu_prune_schedule_off_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 阈值行（757 §三 行3：唯一写口 [PruneSettings.setRetireDays]；档位只从 RETIRE_DAYS_CHOICES 出）。 */
@Composable
private fun PruneRetireDaysRow(ctx: Context, retireDays: Int, onState: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.baopu_prune_retire_days_title),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.baopu_prune_retire_days_desc),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PruneSettings.RETIRE_DAYS_CHOICES.sorted().forEach { days ->
                FilterChip2(
                    label = days.toString(),
                    selected = retireDays == days,
                ) {
                    PruneSettings.setRetireDays(ctx, days)
                    onState(days)
                    RunLog.log("抱朴设置：退役阈值 = $days 天")
                }
            }
        }
    }
}

/**
 * 「已退役」列表页（757 §2.2）：顶部通知句＋一行一条（标题／退役时间／原因三列），
 * 行展开出三动作（恢复／永久保留／不管）。恢复/保留跑 IO（装配核在 PruneGates），
 * 读数一句话回显；动作后重扫列表（恢复过的条目当场从列表消失）。
 */
@Composable
internal fun BaopuRetiredListDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope { CoroutineCrashHandler }
    var tick by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf<String?>(null) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var acting by remember { mutableStateOf(false) }
    val rows by produceState<List<RetiredRow>?>(initialValue = null, key1 = tick) {
        value = withContext(Dispatchers.IO) {
            val store = BaopuRuntime.memoryStore(ctx)
            retiredRowsOf(store.loadAll(), PruneLedger.read(PruneLedger.fileOf(BaopuRuntime.workspaceRoot(ctx))))
        }
    }
    fun act(id: String, keep: Boolean) {
        if (acting) return // 防连点（752 undoPrune 同口径）
        acting = true
        scope.launch {
            note = withContext(Dispatchers.IO) { if (keep) keepRetired(ctx, id) else restoreRetired(ctx, id) }
            expandedId = null
            acting = false
            tick++
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            SettingsSubPage(title = stringResource(R.string.baopu_prune_retired_list_title), onBack = onDismiss) {
                // 通知句（757 §2.3 渲染位置②：列表页顶部必显）。
                Text(
                    text = stringResource(R.string.baopu_prune_retired_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val current = rows
                when {
                    current == null -> Unit // IO 还没回来，首帧空白
                    current.isEmpty() -> Text(
                        text = stringResource(R.string.baopu_prune_retired_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> current.forEach { row ->
                        RetiredRowLine(
                            row = row,
                            expanded = expandedId == row.id,
                            onToggle = { expandedId = if (expandedId == row.id) null else row.id },
                            onRestore = { act(row.id, keep = false) },
                            onKeep = { act(row.id, keep = true) },
                            onIgnore = { expandedId = null }, // 零数据写入，仅收起详情
                        )
                    }
                }
                note?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 列表一行（三列＋展开详情＋三动作；「不管」只是收起，不写任何东西）。 */
@Composable
private fun RetiredRowLine(
    row: RetiredRow,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRestore: () -> Unit,
    onKeep: () -> Unit,
    onIgnore: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = row.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                )
                Text(
                    text = retiredTimeText(row.retiredAtMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(
                        if (row.merged) R.string.baopu_prune_reason_merged else R.string.baopu_prune_reason_expired,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(if (expanded) R.string.common_collapse_arrow else R.string.common_expand_arrow),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Text(
                text = row.body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onRestore) { Text(stringResource(R.string.baopu_prune_action_restore)) }
                TextButton(onClick = onKeep) { Text(stringResource(R.string.baopu_prune_action_keep)) }
                TextButton(onClick = onIgnore) { Text(stringResource(R.string.baopu_prune_action_ignore)) }
            }
        }
    }
}

/** 列 2 的渲染：账面时刻格式化（[TimeFormats.minuteStamp]＝`yyyy-MM-dd HH:mm`，全仓唯一格式器——
 *  240 批门禁禁 SimpleDateFormat、134 批禁 ConstantLocale getDefault）；找不到（null）⇒「时间未知」，
 *  不许拿当前时间补。 */
@Composable
private fun retiredTimeText(atMs: Long?): String =
    if (atMs == null) {
        stringResource(R.string.baopu_prune_time_unknown)
    } else {
        TimeFormats.minuteStamp(atMs)
    }
