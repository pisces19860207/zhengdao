// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百零四单 · **安全台**（安全三件的一页：叫停/解除/回滚/起居注/通知开关；
// zhengdao://safety 深链与关键动作通知点按的落点）。
//
// 页面纪律（照 UnattendedActivity 388 的「页面只是视图」）：本页只读
// HaltGate/RollbackVault/SupervisionDiary 的状态、把按钮按到 SafetyWiring 上，
// 自己不做判定不做时间数学。**解除停机只有本页这一个入口**——这是全系统
// 唯一保留的人工点（派工单 §四）。
package com.example.zhengdao.baopu.safety

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.core.view.WindowCompat
import com.example.zhengdao.R
import com.example.zhengdao.baopu.agent.ActiveSessionGate
import com.example.zhengdao.baopu.agent.DeviationWiring
import com.example.zhengdao.baopu.mcp.server.McpServerController
import com.example.zhengdao.keepalive.CoroutineCrashHandler
import com.example.zhengdao.ui.SectionCard
import com.example.zhengdao.ui.SettingsSubPage
import com.example.zhengdao.ui.theme.ZhengdaoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SafetyActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            ZhengdaoTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SafetyPage(onBack = { finish() })
                }
            }
        }
    }
}

@Composable
private fun SafetyPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    // P4-4 纪律：UI 作用域必须装 CoroutineCrashHandler（门禁扫「没装 handler 的裸作用域」）。
    val scope = rememberCoroutineScope { CoroutineCrashHandler }
    val snackbar = remember { SnackbarHostState() }
    SettingsSubPage(
        title = stringResource(R.string.safety_page_title),
        onBack = onBack,
        snackbar = snackbar,
    ) {
        HaltCard(scope, snackbar)
        RollbackCard(ctx, scope, snackbar)
        DiaryCard(ctx)
        NotifySwitchCard()
        McpServerCard(scope, snackbar)
    }
}

/** 停机卡：状态＋叫停/硬停＋解除（解除是全系统唯一的人工点）。 */
@Composable
private fun HaltCard(
    scope: kotlinx.coroutines.CoroutineScope,
    snackbar: SnackbarHostState,
) {
    val tick = HaltGate.changeTick.intValue
    val level = remember(tick) { HaltGate.level() }
    val record = remember(tick) { HaltGate.record() }
    SectionCard(title = stringResource(R.string.safety_page_title)) {
        Text(
            when (level) {
                HaltLevel.NONE -> "运行中（没有停机）"
                HaltLevel.SOFT -> "软停中：不再发起新动作，当前步跑完即停"
                HaltLevel.HARD -> "硬停中：当前动作已中止，调度/值守/事件一律不启动"
            },
            style = MaterialTheme.typography.titleMedium,
        )
        record?.let {
            Text("谁停的：${it.by}｜何时：${it.atMs}", style = MaterialTheme.typography.bodySmall)
            Text("当时在做什么：${it.where}", style = MaterialTheme.typography.bodySmall)
        }
        if (level == HaltLevel.NONE) {
            Button(onClick = {
                SafetyWiring.softStop(by = "人工（安全台）")
                scope.launch { snackbar.showSnackbar("已叫停（软停）：不再发起新动作") }
            }) { Text(stringResource(R.string.safety_stop_action)) }
            TextButton(onClick = {
                SafetyWiring.hardStop(by = "人工（安全台）")
                scope.launch { snackbar.showSnackbar("已硬停：当前动作中止，在飞写动作将自动回滚") }
            }) { Text(stringResource(R.string.safety_hard_stop_action)) }
        } else {
            // 软停可升硬停（单向加档）；硬停只剩解除。解除只能人做：
            // 模型调解除一律被 HaltGate.lift 拒（判据⑦）。
            if (level == HaltLevel.SOFT) {
                TextButton(onClick = {
                    SafetyWiring.hardStop(by = "人工（安全台）")
                    scope.launch { snackbar.showSnackbar("已升硬停：当前动作中止，在飞写动作将自动回滚") }
                }) { Text(stringResource(R.string.safety_hard_stop_action)) }
            }
            Button(onClick = {
                val ok = SafetyWiring.liftByHuman()
                scope.launch {
                    snackbar.showSnackbar(if (ok) "已人工解除停机" else "解除失败（可能本来就没停）")
                }
            }) { Text(stringResource(R.string.safety_lift_action)) }
        }
    }
}

/** 回滚卡：会话/任务两粒度一键回滚＋自校验结果（判据④）。 */
@Composable
private fun RollbackCard(
    ctx: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    snackbar: SnackbarHostState,
) {
    val tick = HaltGate.changeTick.intValue
    val outcome = remember { mutableStateOf<String?>(null) }
    val session = remember(tick) { ActiveSessionGate.current().orEmpty() }
    val run = remember(tick) {
        // 任务/轮次粒度标签：有任务契约 id 用它（任务可跨多轮），没有落到本轮 runId。
        DeviationWiring.currentTaskId().ifBlank { DeviationWiring.currentRunId() }
    }
    SectionCard(title = stringResource(R.string.safety_rollback_card)) {
        Text("会话粒度：$session", style = MaterialTheme.typography.bodySmall)
        Text("任务粒度：${run.ifBlank { "无（这轮还没起 runId）" }}", style = MaterialTheme.typography.bodySmall)
        Button(onClick = {
            scope.launch {
                if (session.isEmpty()) {
                    snackbar.showSnackbar("没有活跃会话可回滚")
                } else {
                    val o = withContext(Dispatchers.IO) {
                        RollbackVault.rollback(RollbackScope.BySession(session), System.currentTimeMillis())
                    }
                    outcome.value = o.summaryLine
                    SupervisionDiary.append(
                        ctx,
                        DiaryEvent(DiaryKind.ROLLBACK, System.currentTimeMillis(), "人工一键回滚（会话粒度）", session, o.summaryLine),
                    )
                    snackbar.showSnackbar(o.summaryLine)
                }
            }
        }) { Text(stringResource(R.string.safety_rollback_session)) }
        Button(onClick = {
            scope.launch {
                if (run.isBlank()) {
                    snackbar.showSnackbar("当前没有任务粒度（runId）可回滚")
                } else {
                    val o = withContext(Dispatchers.IO) {
                        RollbackVault.rollback(RollbackScope.ByTask(run), System.currentTimeMillis())
                    }
                    outcome.value = o.summaryLine
                    SupervisionDiary.append(
                        ctx,
                        DiaryEvent(DiaryKind.ROLLBACK, System.currentTimeMillis(), "人工一键回滚（任务粒度）", run, o.summaryLine),
                    )
                    snackbar.showSnackbar(o.summaryLine)
                }
            }
        }) { Text(stringResource(R.string.safety_rollback_task)) }
        outcome.value?.let {
            Text("自校验：$it", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** 起居注卡：最近 30 行（何时/依据/做了什么/结果）。 */
@Composable
private fun DiaryCard(ctx: android.content.Context) {
    val tick = HaltGate.changeTick.intValue
    val lines = remember(tick) { SupervisionDiary.readAll(ctx).takeLast(30) }
    SectionCard(title = stringResource(R.string.safety_diary_card)) {
        if (lines.isEmpty()) {
            Text("还没有记录（每次自动动作都会落一行）")
        } else {
            Text(
                lines.joinToString("\n") { it.line },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}

/** 通知开关卡：关键动作通知可关、缺省开（469②）。 */
@Composable
private fun NotifySwitchCard() {
    val tick = SafetyPrefs.changeTick.intValue
    val on = remember(tick) { SafetyPrefs.notifyEnabled() }
    SectionCard(title = stringResource(R.string.safety_notify_card)) {
        Text(
            "不可逆/批量/连续失败≥3 的动作会发一条通知——只是让你知道，不需要你处理",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 2.dp),
        )
        Switch(
            checked = on,
            onCheckedChange = { SafetyPrefs.setNotifyEnabled(it) },
        )
    }
}

/**
 * MCP 服务端卡（第五百八十四单）：开关/绑定档/令牌显抄/白名单读数。
 *
 * 本卡只读 [McpServerController.status()]、把开关按到控制器上，自己不做任何判定——
 * 鉴权/白名单/租约/审计/停机五闸全在服务端核心里。**停机闸联动是结构性的**：
 * 上面 HaltCard 一键停机（软/硬）落下 ⇒ 看门狗 1 秒内关端口，本卡状态行随之翻停。
 * 令牌默认掩码：显示/复制/换钥匙都只出现在人工点按这一条路上（token 永不进日志/账/报告）。
 */
@Composable
private fun McpServerCard(
    scope: kotlinx.coroutines.CoroutineScope,
    snackbar: SnackbarHostState,
) {
    val clipboard = LocalClipboardManager.current
    // 1s 轮询一次状态（服务起停是后台线程的事，Compose 侧只看读数；与 HaltGate.changeTick 同一房型）。
    val tick = remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            tick.intValue += 1
        }
    }
    val st = remember(tick.intValue) { McpServerController.status() }
    val showToken = remember { mutableStateOf(false) }
    SectionCard(title = "MCP 服务端（外部工具口）") {
        Text(
            when {
                st.halted -> "停机闸在位：端口已关（471）——解除停机后自动复活"
                st.running -> "运行中 @${st.endpoint ?: "?"}"
                st.enabled -> "开关开着但没在监听（看门狗正在对齐；端口被占会记进账）"
                else -> "已停（开关关）"
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "绑定：${if (st.bindAll) "0.0.0.0（局域网可选档）" else "127.0.0.1（默认：只许本机其他 App 连）"}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "只暴露 R0 只读工具 ${st.whitelistCount} 件；token／白名单／租约／审计／停机 五闸全过才到执行件",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = st.enabled,
                onCheckedChange = { McpServerController.setEnabled(it) },
            )
            Text(
                if (st.enabled) "服务开着" else "服务关着",
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = st.bindAll,
                onCheckedChange = { McpServerController.setBindAll(it) },
            )
            Text(
                "绑 0.0.0.0（局域网电脑可连；公网档不存在）",
                modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            "访问令牌：" + if (showToken.value) {
                (McpServerController.revealToken() ?: "（还没生成——开启服务时自动生成）")
            } else {
                "••••••••（指纹 ${st.tokenFingerprint ?: "-"}）"
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Row {
            TextButton(onClick = { showToken.value = !showToken.value }) {
                Text(if (showToken.value) "隐藏令牌" else "显示令牌")
            }
            TextButton(onClick = {
                McpServerController.revealToken()?.let { clipboard.setText(AnnotatedString(it)) }
                scope.launch { snackbar.showSnackbar("令牌已复制到剪贴板（用完记得清）") }
            }) { Text("复制令牌") }
            TextButton(onClick = {
                McpServerController.rotateToken()
                scope.launch { snackbar.showSnackbar("已换新钥匙：旧令牌立即失效") }
            }) { Text("换钥匙") }
        }
    }
}
