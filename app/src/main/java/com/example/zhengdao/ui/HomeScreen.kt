// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 首页：顶部可折叠系统状态卡 + Agent 卡片列表；底部 Tab 的「终端」页由
 * 外层 MainActivity 处理（导航到 TerminalActivity）。
 */
@Composable
fun HomeScreen(
    onOpenTerminal: (autocmd: String?) -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var agents by remember { mutableStateOf(AppState.agents(context)) }
    var summary by remember { mutableStateOf(AppState.summaryLine(context)) }
    var statusExpanded by remember { mutableStateOf(false) }
    var sysInfo by remember { mutableStateOf<SystemInfoProvider.Info?>(null) }
    // 每次回到本页（从终端返回）刷新安装状态
    LaunchedEffect(Unit) {
        agents = AppState.agents(context)
        summary = AppState.summaryLine(context)
        sysInfo = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            SystemInfoProvider.collect(context)
        }
    }
    // 设置页/终端返回后刷新（API Key / 工作区 / 修复环境可能已变更）
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                agents = AppState.agents(context)
                summary = AppState.summaryLine(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ── 顶栏：标题 + 设置入口 ──
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "证道",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpenSettings) { Text("⚙ 设置") }
            }
        }
        // ── 系统状态卡（默认收起）──
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { statusExpanded = !statusExpanded },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AnimatedVisibility(visible = statusExpanded) {
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            sysInfo?.let { info ->
                                Text(
                                    text = info.asText(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                TextButton(onClick = {
                                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                        as android.content.ClipboardManager
                                    cm.setPrimaryClip(
                                        android.content.ClipData.newPlainText("zhengdao-sysinfo", info.asText())
                                    )
                                    android.widget.Toast.makeText(
                                        context, "已复制全部系统信息", android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }) { Text("一键复制全部信息") }
                            } ?: Text("加载中…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        // ── Agent 卡片 ──
        items(agents, key = { it.id }) { agent ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = agent.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        when {
                            agent.installed -> Button(
                                onClick = { onOpenTerminal(agent.launchCmd) },
                                modifier = Modifier.width(84.dp),
                            ) { Text("启动") }
                            agent.installCmd != null -> OutlinedButton(
                                onClick = { onOpenTerminal(agent.installCmd) },
                                modifier = Modifier.width(84.dp),
                            ) { Text("安装") }
                            else -> OutlinedButton(
                                onClick = { },
                                enabled = false,
                                modifier = Modifier.width(84.dp),
                            ) { Text("即将支持") }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = agent.desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Text(
                text = "安装与启动均在「终端」内进行，会话由 tmux 兜底（M2 接入）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(6.dp),
            )
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}
