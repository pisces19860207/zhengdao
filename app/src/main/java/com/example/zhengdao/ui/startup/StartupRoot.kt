// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui.startup

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.zhengdao.BackChevron
import com.example.zhengdao.R
import com.example.zhengdao.ZhengdaoApp
import com.example.zhengdao.startup.StartupFailure
import com.example.zhengdao.startup.StartupGate
import com.example.zhengdao.startup.StartupState
import com.example.zhengdao.ui.SettingsHelpActivity
import com.example.zhengdao.ui.SettingsScreen
import com.example.zhengdao.ui.theme.rCard
import com.example.zhengdao.ui.theme.zhengdaoErrorText
import com.example.zhengdao.ui.util.pressClickable

/**
 * 启动装配的界面分叉（629 判据①③的唯一落点）。
 *
 * 三分支全部由 [StartupState] 决定，**没有第四种走法**：
 *   · [StartupState.Failed] ⇒ 错误页（不是崩溃、不是静默、更不是"照常进主界面"）；
 *   · [StartupState.SafeMode] ⇒ 安全模式壳（只有设置与日志）；
 *   · 其余（Idle/Ready）⇒ 主界面 `ZhengdaoApp`。
 *
 * 为什么把分叉放在**组合的最外层**而不是各页自己判：主界面 `HomeTabs` 里第一屏就是抱朴，
 * 抱朴页一组合就会去取引擎（`BaopuChatController` 的构造参数）——只要组合到那儿，
 * "失败"就已经变成了崩溃。挡在最外层，抱朴页根本没有被组合的机会。
 *
 * @param lastRoute 冷启恢复的上次页面（原样透传给 `ZhengdaoApp`；错误页不看它）
 */
@Composable
internal fun StartupRoot(lastRoute: String?) {
    val ctx = LocalContext.current
    when (val s = StartupGate.state) {
        is StartupState.Failed -> StartupFailureScreen(
            failure = s.failure,
            dumpPath = StartupGate.dumpPath,
            onRetry = { StartupGate.retry(ctx) },
            onExport = { StartupGate.exportDiagnostics(ctx) != null },
            onSafeMode = { StartupGate.enterSafeMode() },
        )

        is StartupState.SafeMode -> SafeModeScreen(
            failure = s.failure,
            dumpPath = StartupGate.dumpPath,
            onOpenLogs = {
                // 复用"帮助"页的运行日志那一节（`EXTRA_FOCUS_LOGS` ⇒ 进页即滚到最后一张卡）。
                // 为什么不再造一个日志页：它不是数据源，只是 RunLog 的一个显示器。
                ctx.startActivity(
                    Intent(ctx, SettingsHelpActivity::class.java)
                        .putExtra(SettingsHelpActivity.EXTRA_FOCUS_LOGS, true),
                )
            },
            onExport = { StartupGate.exportDiagnostics(ctx) != null },
            onExitSafeMode = { StartupGate.retry(ctx) },
        )

        StartupState.Idle, StartupState.Ready -> ZhengdaoApp(lastRoute = lastRoute)
    }
}

/**
 * 装配失败页（判据①）：标题「抱朴没能启动：<异常类型>」＋ 三钮。
 *
 * 三个按钮的顺序不是随意的：重试在最上（最可能让人回到正轨的动作），导出诊断居中
 * （要给人东西带走），以安全模式继续在最下（它是"退而求其次"，且点了就少半个 App）。
 *
 * @param dumpPath 诊断文件的展示路径（自动落盘或用户点过之后才有）
 * @param onExport 真的去写一次；返回 false ⇒ 写不出来（磁盘不可写），界面照实说
 */
@Composable
internal fun StartupFailureScreen(
    failure: StartupFailure,
    dumpPath: String?,
    onRetry: () -> Unit,
    onExport: () -> Boolean,
    onSafeMode: () -> Unit,
) {
    // 导出结果：0＝还没点过；1＝有文件；2＝写不出来（用 Int 而不是 Boolean?：
    // rememberSaveable 的 autoSaver 存不下 null，转屏丢了它就会退成"没点过"）。
    var exported by rememberSaveable { mutableIntStateOf(0) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.startup_fail_title, failure.type),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = zhengdaoErrorText(),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.startup_fail_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(18.dp))
            FailureReasonCard(failure)
            if (dumpPath != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.startup_fail_dump_done, dumpPath),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (exported == EXPORT_FAILED) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.startup_fail_dump_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = zhengdaoErrorText(),
                )
            }
        }
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            Text(
                text = stringResource(R.string.startup_fail_retry_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
            ) { Text(stringResource(R.string.startup_fail_retry)) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { exported = if (onExport()) EXPORT_OK else EXPORT_FAILED },
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
            ) { Text(stringResource(R.string.startup_fail_export)) }
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onSafeMode,
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp),
            ) { Text(stringResource(R.string.startup_fail_safe_mode)) }
        }
    }
}

/** 失败原因卡片：只露类型与一句话消息（栈在诊断文件里，不糊满屏幕）。 */
@Composable
private fun FailureReasonCard(failure: StartupFailure) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(rCard),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = stringResource(R.string.startup_fail_reason_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = failure.message?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.startup_fail_reason_empty),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 安全模式里可去的地方（闭集两处：设置 / 日志）。 */
private enum class SafePage { Menu, Settings }

/** 导出诊断的结果位（0＝还没点过，见 [StartupFailureScreen] 与 [SafeModeScreen] 的 `exported`）。 */
private const val EXPORT_OK = 1
private const val EXPORT_FAILED = 2

/**
 * 安全模式壳（判据③）：**只开设置与日志**，并在最上面一行明示"功能受限"。
 *
 * 为什么不是"照常进主界面但把抱朴藏起来"：那等于悄悄把抱朴卸了又不告诉人。
 * 这里的选择是：明确说明"抱朴没有启动"，只留两个真能用的去处，并给一条退出的路。
 */
@Composable
internal fun SafeModeScreen(
    failure: StartupFailure?,
    dumpPath: String?,
    onOpenLogs: () -> Unit,
    onExport: () -> Boolean,
    onExitSafeMode: () -> Unit,
) {
    var page by rememberSaveable { mutableStateOf(SafePage.Menu) }
    var exported by rememberSaveable { mutableIntStateOf(0) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        SafeModeBanner(failure)
        Box(Modifier.weight(1f)) {
            if (page == SafePage.Settings) {
                Column(Modifier.fillMaxSize()) {
                    TextButton(onClick = { page = SafePage.Menu }, modifier = Modifier.padding(start = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BackChevron(tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.common_back),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    SettingsScreen()
                }
            } else {
                SafeModeMenu(
                    dumpPath = dumpPath,
                    exported = exported,
                    onOpenSettings = { page = SafePage.Settings },
                    onOpenLogs = onOpenLogs,
                    onExport = { exported = if (onExport()) EXPORT_OK else EXPORT_FAILED },
                    onExitSafeMode = onExitSafeMode,
                )
            }
        }
    }
}

/** 顶部那条"功能受限"横幅：一直在，切到设置页也不消失（明示是判据的一部分）。 */
@Composable
private fun SafeModeBanner(failure: StartupFailure?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = stringResource(R.string.startup_safe_banner),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = zhengdaoErrorText(),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.startup_safe_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (failure != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.startup_safe_reason, failure.type),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 安全模式的菜单：设置 / 日志 / 导出诊断 / 退出安全模式。 */
@Composable
private fun SafeModeMenu(
    dumpPath: String?,
    exported: Int,
    onOpenSettings: () -> Unit,
    onOpenLogs: () -> Unit,
    onExport: () -> Unit,
    onExitSafeMode: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(rCard),
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column {
                SafeModeRow(
                    label = stringResource(R.string.common_settings),
                    value = stringResource(R.string.startup_safe_settings_desc),
                    onClick = onOpenSettings,
                )
                SafeModeRow(
                    label = stringResource(R.string.startup_safe_logs),
                    value = stringResource(R.string.startup_safe_logs_desc),
                    onClick = onOpenLogs,
                )
            }
        }
        if (dumpPath != null) {
            Text(
                text = stringResource(R.string.startup_fail_dump_done, dumpPath),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(
            onClick = onExport,
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
        ) {
            Text(
                if (exported == EXPORT_OK) {
                    stringResource(R.string.startup_fail_export_again)
                } else {
                    stringResource(R.string.startup_fail_export)
                },
            )
        }
        TextButton(
            onClick = onExitSafeMode,
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp),
        ) { Text(stringResource(R.string.startup_safe_exit)) }
    }
}

@Composable
private fun SafeModeRow(label: String, value: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pressClickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
