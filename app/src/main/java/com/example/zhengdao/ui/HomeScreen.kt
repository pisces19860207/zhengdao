// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    var statusExpanded by remember { mutableStateOf(false) }
    // 环境体检（P7）：状态卡展开时展示逐项勾叉，红项可定向修复
    var healthChecks by remember { mutableStateOf<List<EnvHealth.Check>?>(null) }
    var healthEpoch by remember { mutableStateOf(0) }
    // 卸载二次确认（P3）：非 null 时弹出确认弹窗
    var uninstallTarget by remember { mutableStateOf<AppState.AgentInfo?>(null) }
    // 卸载（P3/v1.0）：menuOpenFor = 当前展开「更多菜单」的卡片 id；
    // uninstallTarget = 二次确认弹窗的目标 Agent
    var menuOpenFor by remember { mutableStateOf<String?>(null) }


    // 展开状态卡（或修复完成）时跑一遍体检；IO 采集，与 sysInfo 同模式
    LaunchedEffect(statusExpanded, healthEpoch) {
        if (statusExpanded) {
            healthChecks = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                EnvHealth.inspect(context)
            }
        }
    }
    var sysInfo by remember { mutableStateOf<SystemInfoProvider.Info?>(null) }
    // 系统信息采集轮次：进页面采一次，之后每次 ON_RESUME 再采一次。
    // 原先只在 LaunchedEffect(Unit) 里采一次——用户去终端装完环境/Agent 再回来，
    // 卡片里的"内存可用 / 已装 Agent / 发行版"全是切走之前的旧快照。
    var sysTick by remember { mutableStateOf(0) }
    LaunchedEffect(sysTick) {
        sysInfo = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            SystemInfoProvider.collect(context)
        }
    }
    // 每次回到本页（从终端返回）刷新安装状态
    LaunchedEffect(Unit) {
        agents = AppState.agents(context)
        // M3：Agent 清单免发版更新（6h TTL，静默失败；拉到新清单后刷新卡片）
        AgentManifest.refresh(context, force = false) {
            agents = AppState.agents(context)
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
                // 系统信息同步重采：内存/存储占用、已装 Agent 列表都是会变的
                sysTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── 固定顶栏（不随内容滚动）──
        // 与太极页一致：标题常驻、内容在下方自滚。原先把标题塞进 LazyColumn 的第一项，
        // 往下滚标题就滚没了——两个根页同一层级，行为却不一样。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "证道",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            // 设置入口：自绘滑杆图标替代 "⚙" 字符（emoji 在浅色主题里是一块彩色塑料，
            // 与底部三个自绘道家图标不是一套笔）
            TextButton(onClick = onOpenSettings) {
                com.example.zhengdao.SettingsIcon(tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text("设置", color = MaterialTheme.colorScheme.primary)
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
        // ── 系统状态卡（默认收起）──
        item {
            val envReady = AppState.rootfsInstalled(context)
            val installedCount = agents.count { it.installed }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { statusExpanded = !statusExpanded },
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    // 第一行＝状态名 + Agent 计数 + 展开箭头（把"这张卡可以点开"这件事说出来）。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    if (envReady) MaterialTheme.colorScheme.tertiary
                                    else MaterialTheme.colorScheme.error,
                                    CircleShape,
                                ),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (envReady) "环境就绪" else "环境未安装",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "$installedCount 个 Agent",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (statusExpanded) "▴" else "▾",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    // 副行＝系统与环境的原始摘要。原先把含计数的整句挤进两行，
                    // 真机上被折成"… 2 / 个 Agent"（数字和量词分家）；现在计数上提到第一行，
                    // 这里只剩系统信息，一行放得下。
                    Text(
                        text = buildString {
                            append("Android ").append(android.os.Build.VERSION.RELEASE)
                            append(" · ").append(android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "未知")
                            append(" · ").append(if (envReady) "Debian 13.7 已安装" else "Debian 未安装")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AnimatedVisibility(visible = statusExpanded) {
                        Column {
                            // 摘要与体检之间加一条发丝线：展开后内容骤增，需要一条明确的
                            // "以下属于详情"的分界（与卡片、底栏同一套 0.5dp outlineVariant）。
                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                            Spacer(Modifier.height(12.dp))
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
                            Spacer(modifier = Modifier.height(6.dp))
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
                                            .padding(vertical = 6.dp),
                                    ) {
                                        Text(
                                            text = if (c.ok) "✓" else "✗",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = if (c.ok) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.error,
                                            modifier = Modifier.width(22.dp),
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            // 项目名升到 bodyMedium：12sp 的标题 + 12sp 的说明
                                            // 在真机上主次不分，一屏小字看着累。
                                            Text(
                                                text = c.label,
                                                style = MaterialTheme.typography.bodyMedium,
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
                                // 系统信息是一段多行长文本，裸排会和体检项混成一片；
                                // 给它一个浅底信息块（与卡片同圆角语系），明确"这是可复制的原文"。
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            MaterialTheme.colorScheme.surfaceContainerLow,
                                            RoundedCornerShape(10.dp),
                                        )
                                        .padding(10.dp),
                                ) {
                                    Text(
                                        text = info.asText(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
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
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
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
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = { onOpenTerminal(null, null) },
                            shape = RoundedCornerShape(50),
                        ) {
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
                shape = RoundedCornerShape(16.dp),
                // 页面底色是浅灰（systemGroupedBackground）、卡片是纯白，原本只靠色差划界，
                // 边界偏软。补一条 0.5dp 发丝描边——与底部导航栏的分隔线同一套语言。
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val installing = AgentRepository.stateOf(context, agent) == AgentRepository.State.Installing
                    val failedInstall = AgentRepository.stateOf(context, agent) == AgentRepository.State.Failed
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = agent.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        // 卸载入口（P3/v1.0）：已装且有卸载命令的才给「更多」。原先它是描述
                        // 下方另起一行的左对齐 "⋮"，真机上孤零零挂在卡片左下角，像列表装饰；
                        // 挪到主操作旁——它和「启动」同属"对这张卡片的操作"。
                        if (agent.installed && agent.uninstallCmd != null) {
                            Box {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clickable { menuOpenFor = agent.id },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "⋮",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                DropdownMenu(
                                    expanded = menuOpenFor == agent.id,
                                    onDismissRequest = { menuOpenFor = null },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("卸载") },
                                        onClick = {
                                            menuOpenFor = null
                                            uninstallTarget = agent
                                        },
                                    )
                                }
                            }
                        }
                        val envReady = AppState.rootfsInstalled(context)
                        when {
                            agent.installed -> Button(
                                onClick = { onOpenTerminal(agent.launchCmd, agent.id) },
                                modifier = Modifier.width(84.dp),
                                shape = RoundedCornerShape(50),
                            ) { Text("启动") }
                            installing -> OutlinedButton(
                                onClick = { onOpenTerminal(null, agent.id) },
                                modifier = Modifier.width(84.dp),
                                shape = RoundedCornerShape(50),
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
                                shape = RoundedCornerShape(50),
                            ) { Text("安装") }
                            agent.installCmd == null -> OutlinedButton(
                                onClick = { },
                                enabled = false,
                                modifier = Modifier.width(84.dp),
                                shape = RoundedCornerShape(50),
                            ) { Text("即将支持") }
                            // 环境未装：Agent 无法安装，按钮禁用（上方横幅已给一键安装路径）
                            else -> OutlinedButton(
                                onClick = { },
                                enabled = false,
                                modifier = Modifier.width(84.dp),
                                shape = RoundedCornerShape(50),
                            ) { Text("先装环境") }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = agent.desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 状态行：失败 / 安装中用浅底提示条（原先的裸彩字在真机上会被读成
                    // 上一行的溢出，像渲染坏了）；已装版本属常态信息，保持素色小字。
                    if (failedInstall) {
                        StatusNote(
                            text = "上次安装未完成，可点「安装」重试；输出在「终端」可查",
                            tone = MaterialTheme.colorScheme.error,
                        )
                    } else if (installing) {
                        StatusNote(
                            text = "正在安装，输出实时显示在「终端」…",
                            tone = MaterialTheme.colorScheme.primary,
                        )
                    } else if (agent.installed && agent.installedVersion != null) {
                        // 只显示已装版本（本地 package.json 探测）；要不要更新由用户自己决定
                        Text(
                            text = "已装版本：${agent.installedVersion}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                }
            }
        }
        // 空态：manifest 拉不到时列表会空掉，给一句话而不是一片白
        if (agents.none { it.id != "opencode" }) {
            item {
                Text(
                    text = "Agent 清单暂时拉不到，检查网络后回到本页会自动刷新。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                )
            }
        }
        item {
            Text(
                text = "安装与启动均在「终端」内进行；会话由 tmux 保持，断线重进不丢现场。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
        } // LazyColumn 结束
    } // 外层 Column（固定顶栏 + 列表）结束

    // 卸载二次确认（P3/v1.0）：「卸载」删程序、留用户数据；「彻底清除」连用户数据一起删
    // （仅限已知名单内的 Agent——未知 manifest 条目的数据布局不猜测，不提供该选项）。
    // 尺寸用真实 du（从 uninstall 命令解析 rm 目标路径映射宿主后统计），不写死数字。
    uninstallTarget?.let { target ->
        val cmd = target.uninstallCmd ?: return@let
        val wipe = wipeTargets[target.id]
        var sizesText by remember(target.id) { mutableStateOf("尺寸统计中…") }
        LaunchedEffect(target.id) {
            sizesText = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val prog = programSizeMb(context, cmd)
                val cache = runCatching {
                    com.example.zhengdao.terminal.CacheCleaner.measure(context)["安装包缓存"] ?: 0L
                }.getOrDefault(0L)
                val progText = if (prog >= 0) "（约 $prog MB）" else "（以实际占用为准）"
                "· Agent 程序$progText\n· 安装包缓存（全局，约 $cache MB）"
            }
        }
        AlertDialog(
            onDismissRequest = { uninstallTarget = null },
            title = { Text("卸载 ${target.name}？") },
            text = {
                Text(
                    buildString {
                        append("将删除：\n").append(sizesText).append("\n\n")
                        append("将保留：\n· 会话历史与记忆\n· API Key 配置（如适用）")
                        wipe?.let {
                            append("\n\n「彻底清除」将连同用户数据（").append(it.joinToString("、"))
                                .append("）一起删除，不可恢复。")
                        }
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    uninstallTarget = null
                    // agentId 传 null：卸载不走「一键安装」登记，传 id 会被误标「安装中」
                    onOpenTerminal(cmd, null)
                }) { Text("卸载") }
            },
            dismissButton = {
                Row {
                    if (wipe != null) {
                        TextButton(onClick = {
                            uninstallTarget = null
                            onOpenTerminal(cmd + " && rm -rf " + wipe.joinToString(" "), null)
                        }) { Text("彻底清除（含用户数据）", color = MaterialTheme.colorScheme.error) }
                    }
                    TextButton(onClick = { uninstallTarget = null }) { Text("取消") }
                }
            },
        )
    }

}

/**
 * Agent 卡片内的状态提示条：语义色 10% 浅底 + 8dp 圆角。
 * 取代原先的裸彩字——裸红字紧跟在描述下方，真机上会被读成上一行的溢出／渲染错误。
 */
@Composable
private fun StatusNote(text: String, tone: Color) {
    Spacer(Modifier.height(8.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(tone.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = tone,
        )
    }
}

/** 「彻底清除」时随程序一起删除的用户数据目录（guest 内路径；shell 展开 ~）。
 *  仅限已知名单——未知 manifest 条目的数据布局不猜测。 */
private val wipeTargets: Map<String, List<String>> = mapOf(
    "claude-code" to listOf("~/.claude"),
    "hermes" to listOf("~/.hermes"),
)

/** 依据 manifest 的 uninstall 命令估算 Agent 程序体积：解析其中 rm -rf 的目标路径，
 *  映射到宿主真实目录后求 du 和（真实 du，不写死数字）。仅识别 /root/→home 与
 *  /usr/→rootfs/usr 两类映射；含通配符的路径跳过；解析不出已知路径时返回 -1
 *  （弹窗改用"以实际占用为准"措辞，不编造）。 */
private fun programSizeMb(ctx: android.content.Context, uninstallCmd: String): Long {
    val paths = Regex("rm\\s+-rf\\s+([^&\\n]+)").findAll(uninstallCmd)
        .flatMap { it.groupValues[1].trim().split(Regex("\\s+")) }
        .filter { it.startsWith("/") && !it.contains('*') }
        .distinct()
        .mapNotNull { p ->
            when {
                p == "/root" || p.startsWith("/root/") ->
                    java.io.File(ctx.filesDir, "home/" + p.removePrefix("/root/"))
                p == "/usr" || p.startsWith("/usr/") ->
                    java.io.File(ctx.filesDir, "rootfs" + p)
                else -> null
            }
        }
        .toList()
    if (paths.isEmpty()) return -1
    return paths.sumOf { SystemInfoProvider.dirSizeMb(it) }
}
