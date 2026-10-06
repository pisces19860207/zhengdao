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
import androidx.compose.material3.AlertDialog
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
    onOpenTerminal: (autocmd: String?, agentId: String?) -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var agents by remember { mutableStateOf(AppState.agents(context)) }
    var summary by remember { mutableStateOf(AppState.summaryLine(context)) }
    var statusExpanded by remember { mutableStateOf(false) }
    // 环境体检（P7）：状态卡展开时展示逐项勾叉，红项可定向修复
    var healthChecks by remember { mutableStateOf<List<EnvHealth.Check>?>(null) }
    var healthEpoch by remember { mutableStateOf(0) }
    // 卸载二次确认（P3）：非 null 时弹出确认弹窗
    var uninstallTarget by remember { mutableStateOf<AppState.AgentInfo?>(null) }

    // 展开状态卡（或修复完成）时跑一遍体检；IO 采集，与 sysInfo 同模式
    LaunchedEffect(statusExpanded, healthEpoch) {
        if (statusExpanded) {
            healthChecks = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                EnvHealth.inspect(context)
            }
        }
    }
    var sysInfo by remember { mutableStateOf<SystemInfoProvider.Info?>(null) }
    // 每次回到本页（从终端返回）刷新安装状态
    LaunchedEffect(Unit) {
        agents = AppState.agents(context)
        summary = AppState.summaryLine(context)
        sysInfo = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            SystemInfoProvider.collect(context)
        }
        // M3：Agent 清单免发版更新（6h TTL，静默失败；拉到新清单后刷新卡片）
        AgentManifest.refresh(context, force = false) {
            agents = AppState.agents(context)
            summary = AppState.summaryLine(context)
        }
    }

    // M3：一键安装轮询——有「安装中」的 Agent 时每 5 秒重查（文件出现 → [启动]）
    androidx.compose.runtime.LaunchedEffect(agents) {
        if (agents.any { AgentRepository.stateOf(context, it) == AgentRepository.State.Installing }) {
            kotlinx.coroutines.delay(3000)
            agents = AppState.agents(context)
        }
    }

    // 设置页/终端返回后刷新（工作区 / 修复环境可能已变更）
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
                            // 环境体检（P7）：逐项勾叉 + 定向修复；先于系统信息展示
                            val checks = healthChecks
                            Text(
                                text = if (checks == null) "环境体检中…" else {
                                    val pass = checks.count { it.ok }
                                    "环境体检 $pass/${checks.size} 通过"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            if (checks == null) {
                                Text(
                                    text = "正在检查环境状态…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                checks.forEach { c ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp),
                                    ) {
                                        Text(
                                            text = if (c.ok) "✓" else "✗",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (c.ok) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.error,
                                            modifier = Modifier.width(20.dp),
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = c.label,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Medium,
                                            )
                                            Text(
                                                text = c.detail,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        // 可修复项：行尾定向修复；RootFS/proot 损坏：引导到设置页重解压
                                        if (!c.ok) {
                                            if (c.fixId != null) {
                                                TextButton(onClick = {
                                                    val fid = c.fixId
                                                    Thread {
                                                        EnvHealth.fix(context, fid)
                                                        android.os.Handler(context.mainLooper).post {
                                                            healthEpoch++
                                                        }
                                                    }.start()
                                                }) { Text("修复") }
                                            } else {
                                                TextButton(onClick = onOpenSettings) {
                                                    Text("去处理")
                                                }
                                            }
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                            }
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
        // ── 环境未装引导横幅（第三批）：环境没装时 Agent 装不了，先给一条一键安装路径 ──
        if (!AppState.rootfsInstalled(context)) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "运行环境尚未安装",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "安装 Agent 前需要先装好 Debian 环境（约 3 分钟，只需一次）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { onOpenTerminal(null, null) }) {
                            Text("安装运行环境")
                        }
                    }
                }
            }
        }
        // ── Agent 卡片（OpenCode 已内置为太极，不在丹房展示——用户定稿）──
        items(agents.filter { it.id != "opencode" }, key = { it.id }) { agent ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    val installing = AgentRepository.stateOf(context, agent) == AgentRepository.State.Installing
                    val failedInstall = AgentRepository.stateOf(context, agent) == AgentRepository.State.Failed
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = agent.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        val envReady = AppState.rootfsInstalled(context)
                        when {
                            agent.installed -> Button(
                                onClick = { onOpenTerminal(agent.launchCmd, agent.id) },
                                modifier = Modifier.width(84.dp),
                            ) { Text("启动") }
                            installing -> OutlinedButton(
                                onClick = { onOpenTerminal(null, agent.id) },
                                modifier = Modifier.width(84.dp),
                                enabled = false,
                            ) { Text("安装中") }
                            agent.installCmd != null && envReady -> OutlinedButton(
                                onClick = {
                                    // 一键到底（M3 骨架 §3）：脚本本地化 + 清锁，装完自动启动
                                    AgentInstaller.prepareInstall(context, agent) { cmd ->
                                        onOpenTerminal(cmd, agent.id)
                                    }
                                },
                                modifier = Modifier.width(84.dp),
                            ) { Text("安装") }
                            agent.installCmd == null -> OutlinedButton(
                                onClick = { },
                                enabled = false,
                                modifier = Modifier.width(84.dp),
                            ) { Text("即将支持") }
                            // 环境未装：Agent 无法安装，按钮禁用（上方横幅已给一键安装路径）
                            else -> OutlinedButton(
                                onClick = { },
                                enabled = false,
                                modifier = Modifier.width(84.dp),
                            ) { Text("先装环境") }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = agent.desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 版本行：已装版本 + 可更新提示（npm 包可探测时才有）
                    if (failedInstall) {
                        Text(
                            text = "上次安装未完成，可点「安装」重试；输出在「终端」可查",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else if (installing) {
                        Text(
                            text = "正在安装，输出实时显示在「终端」…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else if (agent.installed && agent.installedVersion != null) {
                        // 只显示已装版本（本地 package.json 探测）；要不要更新由用户自己决定
                        Text(
                            text = "已装版本：${agent.installedVersion}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // 卸载入口（P3）：仅已装且有卸载命令的 Agent 显示；确认弹窗在 LazyColumn 之后
                    if (agent.installed && agent.uninstallCmd != null) {
                        TextButton(onClick = { uninstallTarget = agent }) {
                            Text("卸载", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
        item {
            Text(
                text = "安装与启动均在「终端」内进行；会话由 tmux 保持，断线重进不丢现场。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(6.dp),
            )
        }
    }

    // 卸载二次确认（P3）：「卸载」删程序、留用户数据；「彻底清除」连用户数据一起删
    // （仅限已知名单内的 Agent——未知 manifest 条目的数据布局不猜测，不提供该选项）
    uninstallTarget?.let { target ->
        val cmd = target.uninstallCmd ?: return@let
        val wipe = wipeTargets[target.id]
        AlertDialog(
            onDismissRequest = { uninstallTarget = null },
            title = { Text("卸载 ${target.name}") },
            text = {
                Text(
                    if (wipe == null) {
                        "删除程序本体；用户数据（配置、会话）保留，可随时重装。"
                    } else {
                        "「卸载」删除程序本体，用户数据（${wipe.joinToString("、")}）保留，可随时重装；\n" +
                            "「彻底清除」连同用户数据一起删除，不可恢复。"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    uninstallTarget = null
                    onOpenTerminal(cmd, target.id)
                }) { Text("卸载", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                if (wipe == null) {
                    TextButton(onClick = { uninstallTarget = null }) { Text("取消") }
                } else {
                    TextButton(onClick = {
                        uninstallTarget = null
                        onOpenTerminal(cmd + " && rm -rf " + wipe.joinToString(" "), target.id)
                    }) { Text("彻底清除", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { uninstallTarget = null }) { Text("取消") }
                }
            },
        )
    }
}

/** 「彻底清除」时随程序一起删除的用户数据目录（guest 内路径；shell 展开 ~）。 */
private val wipeTargets: Map<String, List<String>> = mapOf(
    "claude-code" to listOf("~/.claude"),
    "hermes" to listOf("~/.hermes"),
)

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
