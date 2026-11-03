// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百八十八单 · **离场值守页**（开关＋状态＋环境＋队列＋报告一页看全；
// zhengdao://unattended 深链与四类通知点按的落点）。
//
// 页面纪律：本页只是 [UnattendedStatus]／[UnattendedLedger]／[UnattendedRecorder] 的视图，
// 自己不做判定、不做时间数学（照 ScheduleTasksActivity「页面只是视图」的老规矩）；
// 开关是**纯状态**（388 派工单第一件），圈判定在 judgeUnattended，本页不裁任何事。
package com.example.zhengdao.unattended

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.example.zhengdao.R
import com.example.zhengdao.baopu.BaopuRuntime
import com.example.zhengdao.keepalive.CoroutineCrashHandler
import com.example.zhengdao.ui.SectionCard
import com.example.zhengdao.ui.SettingsSubPage
import com.example.zhengdao.ui.theme.ZhengdaoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UnattendedActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            ZhengdaoTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    UnattendedPage(onBack = { finish() })
                }
            }
        }
    }
}

@Composable
private fun UnattendedPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    // P4-4 纪律：UI 作用域必须装 CoroutineCrashHandler（门禁扫「没装 handler 的裸作用域」）。
    val scope = rememberCoroutineScope { CoroutineCrashHandler }
    val snackbar = remember { SnackbarHostState() }
    // 状态仓的"变了"信号：任何一格写都递增 ⇒ 各卡 remember(tick) 重读，整页即时刷新。
    val tick = UnattendedStatus.changeTick.intValue
    SettingsSubPage(
        title = stringResource(R.string.unattended_page_title),
        onBack = onBack,
        snackbar = snackbar,
    ) {
        ArmCard(ctx, scope, snackbar)
        StateCard()
        EnvCard(ctx)
        QueueCard()
        ReportCard(ctx, scope, snackbar)
    }
}

@Composable
private fun ArmCard(
    ctx: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    snackbar: SnackbarHostState,
) {
    // 读 tick：本卡订阅状态仓的"变了"信号，开/收值守之后 armed 才会即时重读（不然会被跳过）。
    val tick = UnattendedStatus.changeTick.intValue
    var scopeText by remember(tick) { mutableStateOf(UnattendedStatus.scopeNote()) }
    val armed = remember(tick) { UnattendedStatus.armed() }
    SectionCard(title = stringResource(R.string.unattended_page_title)) {
        OutlinedTextField(
            value = scopeText,
            onValueChange = { scopeText = it },
            label = { Text(stringResource(R.string.unattended_scope_hint)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !armed,
            singleLine = true,
        )
        if (armed) {
            TextButton(onClick = {
                scope.launch {
                    val queue = withContext(Dispatchers.IO) {
                        BaopuRuntime.engineIfBuilt()?.awayDeferredHeadlines().orEmpty()
                    }
                    UnattendedRecorder.disarmAndReport(ctx, System.currentTimeMillis(), queue)
                    UnattendedDutyService.stop(ctx)
                    snackbar.showSnackbar("已收值守，离场报告已生成")
                }
            }) { Text(stringResource(R.string.unattended_disarm)) }
        } else {
            Button(onClick = {
                UnattendedRecorder.arm(ctx, scopeText, System.currentTimeMillis())
                UnattendedDutyService.start(ctx)
                scope.launch { snackbar.showSnackbar("值守已开；前台服务与看门狗都已就位") }
            }) { Text(stringResource(R.string.unattended_arm)) }
        }
    }
}

@Composable
private fun StateCard() {
    val tick = UnattendedStatus.changeTick.intValue
    val armedAt = remember(tick) { UnattendedStatus.armedAtMs() }
    val degraded = remember(tick) { UnattendedStatus.degraded() }
    val paused = remember(tick) { UnattendedStatus.pausedReasons() }
    val locked = remember(tick) { UnattendedStatus.lockedNow() }
    val scope = remember(tick) { UnattendedStatus.scopeNote() }
    val lastSummary = remember(tick) { UnattendedStatus.lastSummaryAtMs() }
    SectionCard(title = "状态") {
        val head = if (armedAt == null) "未开值守" else "值守中（已 ${UnattendedReport.formatSpan(System.currentTimeMillis() - armedAt)}）"
        Text(head, style = MaterialTheme.typography.titleMedium)
        Line("托付范围", scope.ifEmpty { "未写" })
        Line("B级汇总", lastSummary?.let { "最近一条 ${UnattendedReport.formatSpan(System.currentTimeMillis() - it)} 前" } ?: "还没出过")
        Line("降级", if (degraded) "已降级停手（自动权收回；复工＝收值守再开）" else "未降级")
        Line("环境暂停", if (paused.isEmpty()) "无" else paused.joinToString("、") { it.cn })
        Line("锁屏", if (locked) "锁着（问卡进队列，不弹）" else "未锁")
    }
}

@Composable
private fun EnvCard(ctx: android.content.Context) {
    val tick = UnattendedStatus.changeTick.intValue
    val sample = remember(tick) { UnattendedEnvSampler.sample(ctx) }
    val battery = sample.batteryPct?.let { "$it%" } ?: "读不到"
    val temp = sample.batteryTempTenths?.let { "${it / 10.0}℃" } ?: "读不到"
    SectionCard(title = "环境（暂停阈：电量 20%／温度 43℃／无网）") {
        Line("电量", battery)
        Line("电池温度", temp)
        Line("网络", if (sample.networkOk) "可用" else "不可用")
    }
}

@Composable
private fun QueueCard() {
    val tick = UnattendedStatus.changeTick.intValue
    val heads = remember(tick) { BaopuRuntime.engineIfBuilt()?.awayDeferredHeadlines().orEmpty() }
    SectionCard(title = "队列（锁屏期间的问卡进这里，解锁回话按序补问）") {
        if (heads.isEmpty()) {
            Text("空（没有压着的卡）")
        } else {
            Text("压着 ${heads.size} 件：")
            heads.forEach { Text("· $it") }
        }
    }
}

@Composable
private fun ReportCard(
    ctx: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    snackbar: SnackbarHostState,
) {
    val tick = UnattendedStatus.changeTick.intValue
    var report by remember { mutableStateOf("") }
    LaunchedEffect(tick) {
        report = withContext(Dispatchers.IO) { UnattendedRecorder.latestReport(ctx).orEmpty() }
    }
    SectionCard(title = "离场报告") {
        TextButton(onClick = {
            scope.launch {
                val queue = withContext(Dispatchers.IO) {
                    BaopuRuntime.engineIfBuilt()?.awayDeferredHeadlines().orEmpty()
                }
                UnattendedRecorder.midtermReport(ctx, System.currentTimeMillis(), queue)
                snackbar.showSnackbar("本期报告已生成")
            }
        }) { Text(stringResource(R.string.unattended_midterm)) }
        if (report.isNotBlank()) {
            Text(
                report,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Text(
        "$label：$value",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}
