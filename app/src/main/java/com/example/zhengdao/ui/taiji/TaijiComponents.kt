// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据的公开接口：Jetpack Compose（Material 3）官方 API。
package com.example.zhengdao.ui.taiji

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.zhengdao.oc.OcMessage
import com.example.zhengdao.oc.OcPart
import com.example.zhengdao.oc.OcPermission
import com.example.zhengdao.oc.ConnectionState
import com.example.zhengdao.oc.OcRepository
import com.example.zhengdao.oc.OcTodo
import com.example.zhengdao.oc.TaijiPhase
import com.example.zhengdao.oc.TaijiState
import com.example.zhengdao.oc.ToolState
import kotlinx.coroutines.launch

// ── 顶部栏 ────────────────────────────────────────────────────────────

/**
 * 会话状态栏。
 *
 * [connection]非[ConnectionState.Connected] 时显示状态——**失败必须可见**，
 * 不静默（与项目"M2 内存治理不假装成功"同一原则）。
 */
@Composable
fun SessionBar(
    title: String,
    connection: ConnectionState,
    attempt: Int,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title.ifEmpty { "新会话" }, style = MaterialTheme.typography.titleMedium)
            ConnectionLabel(connection, attempt)
        }
        IconButton(onClick = onStop) { Text("◼", style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun ConnectionLabel(connection: ConnectionState, attempt: Int) {
    val (text, color) = when (connection) {
        ConnectionState.Connected -> "● 已连接" to MaterialTheme.colorScheme.primary
        ConnectionState.Connecting -> "○ 连接中…" to MaterialTheme.colorScheme.onSurfaceVariant
        ConnectionState.Reconnecting ->
            "○ 已断开，正在重连（第 $attempt 次）" to MaterialTheme.colorScheme.error
        ConnectionState.Idle -> "" to MaterialTheme.colorScheme.onSurface
    }
    if (text.isNotEmpty()) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

// ── 连接横幅 ──────────────────────────────────────────────────────────

/** 仅在非Connected 时出现的横幅（信息重复但不打扰阅读流）。 */
@Composable
fun ConnectionBanner(state: TaijiState, onDismiss: () -> Unit) {
    AnimatedVisibility(visible = state.connection != ConnectionState.Connected) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    state.lastError ?: "连接已断开，正在重连…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text("知道了") }
            }
        }
    }
}

// ── 消息列表 ──────────────────────────────────────────────────────────

/**
 * 消息列表。
 *
 * @param onStopScroll 用户上滑查看历史时不抢滚动（见设计文档 §5.2）。
 */
@Composable
fun MessageList(
    messages: List<OcMessage>,
    todos: List<OcTodo>,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val atBottom by remember {
        androidx.compose.runtime.derivedStateOf {
            listState.layoutInfo.visibleItemsInfo.lastOrNull()?.let {
                it.index >= listState.layoutInfo.totalItemsCount - 1
            } ?: true
        }
    }
    LaunchedEffect(messages.size) {
        if (atBottom) listState.animateScrollToItem(messages.lastIndex.coerceAtLeast(0))
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (todos.isNotEmpty()) { item { TodoPanel(todos) } }

        items(messages, key = { it.id }) { msg -> MessageBubble(msg) }

        if (isStreaming) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }
            }
        }
    }
}

// ── 单条消息 ──────────────────────────────────────────────────────────

@Composable
fun MessageBubble(msg: OcMessage) {
    val isUser = msg.role == OcMessage.Role.USER
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(max = 560.dp),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                msg.parts.forEach { PartRow(it) }
            }
        }
    }
}

// ── Part渲染分发 ──────────────────────────────────────────────────────

@Composable
fun PartRow(part: OcPart) {
    when (part) {
        is OcPart.Text -> if (part.text.isNotEmpty()) {
            Text(part.text, style = MaterialTheme.typography.bodyMedium)
        }
        // ⚠️ 严格按 **part.type** 分派（不靠"是否含 thinking 标签"猜）：
        //    `reasoning` 只进独立的样式化折叠块；`text` 走上一个分支的正文 Text。
        is OcPart.Reasoning -> ReasoningBlock(part.text)
        is OcPart.Tool -> {
            // 🔍 诊断（用户第 1 步）：dump 工具卡**实际收到**的字段，确认 name / 入参 / 结果是否都在。
            //    用 LaunchedEffect(part) 保证「每个 part 只打一次」，避免轮询重组时刷屏。
            LaunchedEffect(part) {
                com.example.zhengdao.rootfs.RunLog.log(
                    "工具卡: name='${part.toolName}' state=${part.state} " +
                        "inputLen=${part.input?.length ?: -1} outputLen=${part.output?.length ?: -1} " +
                        "input=${part.input?.take(120)}"
                )
            }
            ToolCallCard(part)
        }
        is OcPart.File -> Text("📎 ${part.filename}", style = MaterialTheme.typography.bodySmall)
        // ⚠️ 未知 part 保留原文而非静默丢弃（见 OcDto 注释）
        is OcPart.Unknown -> CollapsibleBlock("未知内容（${part.type}）") {
            Text(part.raw.take(400), style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace)
        }
    }
}

// ── 工具调用卡片 ──────────────────────────────────────────────────────

/**
 * 工具调用卡片（可折叠）。
 *
 * 结构化事件带来的红利：TUI 只能画终端画面，这里可以分状态、分区展示。
 * [ToolState] 三态视觉区分，让用户一眼看出成功/失败。
 */
@Composable
fun ToolCallCard(part: OcPart.Tool) {
    var expanded by remember { mutableStateOf(false) }
    val (icon, tint) = when (part.state) {
        ToolState.Running -> "⟳" to MaterialTheme.colorScheme.primary
        ToolState.Success -> "✓" to MaterialTheme.colorScheme.primary
        ToolState.Error -> "✗" to MaterialTheme.colorScheme.error
        ToolState.Unknown -> "•" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(icon, color = tint, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    part.toolName.ifEmpty { "工具" },
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    when (part.state) {
                        ToolState.Running -> "执行中"
                        ToolState.Success -> "完成"
                        ToolState.Error -> "失败"
                        ToolState.Unknown -> ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = tint,
                )
            }
            // 折叠态也显示一行入参摘要（命令 / 路径），让"用了哪个工具、干了啥"一眼可见
            part.input?.takeIf { it.isNotBlank() }?.let { inp ->
                Spacer(Modifier.height(4.dp))
                Text(
                    inp.lineSequence().first().take(140),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                HorizontalDivider()
                Spacer(Modifier.height(6.dp))
                part.input?.let {
                    Text("入参", style = MaterialTheme.typography.labelSmall)
                    Text(it.take(600), style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace, maxLines = 8, overflow = TextOverflow.Ellipsis)
                }
                part.output?.let {
                    Spacer(Modifier.height(6.dp))
                    Text("结果", style = MaterialTheme.typography.labelSmall)
                    Text(it.take(600), style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace, maxLines = 8, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ── 思考过程（reasoning）独立样式化块 ─────────────────────────────────

/**
 * reasoning 的**独立样式化块**（借鉴 PR #29028 思路）：
 * 头部独立成行（含首行摘要 + 展开指示），展开后的原文作为**块内正文**渲染在头部之下。
 * 目的是把「思考过程」与紧随其后的**最终正文**明确分开
 * —— 修「正文看起来属于思考过程」的标签错位（原先所有 part 挤在同一个无间距 Column 里）。
 */
@Composable
private fun ReasoningBlock(text: String) {
    var expanded by remember { mutableStateOf(false) }
    // 头部摘要：取首个非空行
    val summary = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "💭 思考过程",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (!expanded && summary.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Text(
                if (expanded) "收起 ▲" else "展开 ▼",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Spacer(Modifier.height(4.dp))
            HorizontalDivider()
            Spacer(Modifier.height(6.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── 可折叠块（未知内容用）─────────────────────────────────────────────

@Composable
private fun CollapsibleBlock(title: String, content: @Composable () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { expanded = !expanded }.padding(vertical = 2.dp),
        )
        if (expanded) { HorizontalDivider(); content() }
    }
}

// ── 待办面板 ──────────────────────────────────────────────────────────

@Composable
fun TodoPanel(todos: List<OcTodo>) {
    if (todos.isEmpty()) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("任务清单（${todos.count { it.status == OcTodo.TodoStatus.Completed }}/${todos.size}）",
                style = MaterialTheme.typography.labelLarge)
            todos.forEach { t ->
                Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (t.status) {
                            OcTodo.TodoStatus.Completed -> "☑"
                            OcTodo.TodoStatus.Running -> "⟳"
                            OcTodo.TodoStatus.Cancelled -> "✗"
                            OcTodo.TodoStatus.Pending -> "☐"
                        },
                        Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        t.content,
                        style = MaterialTheme.typography.bodySmall,
                        textDecoration = if (t.status == OcTodo.TodoStatus.Completed)
                            androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                    )
                }
            }
        }
    }
}

// ── 输入区 ────────────────────────────────────────────────────────────

/**
 * 输入区。
 *
 * ⚠️ **不要给TextField 设 password 类型**——国产 ROM 会弹安全键盘（无 ESC/CTRL、
 * 布局错乱、部分禁粘贴），终端与输入直接不可用。继承 v3 §7 红线。
 * 密码/密钥类输入应另做"普通文本框 + App 内自绘遮蔽"。
 *
 * @param isStreaming 流式输出中：发送键变为「中止」，不让用户干等。
 */
@Composable
fun ComposerBar(
    input: String,
    isStreaming: Boolean,
    enabled: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onAbort: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(
            Modifier.fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()          // 🔺 键盘弹出不得顶掉输入框
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                TextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("描述你的任务…") },
                    maxLines = 6,
                    // 🔺 普通文本类型——绝不 TYPE_TEXT_VARIATION_PASSWORD
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        autoCorrectEnabled = false,
                        capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None,
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                )
                Spacer(Modifier.width(8.dp))
                if (isStreaming) {
                    OutlinedButton(onClick = onAbort) { Text("中止") }
                } else {
                    Button(onClick = onSend, enabled = enabled && input.isNotBlank()) { Text("发送") }
                }
            }
        }
    }
}

// ── 权限批准底部抽屉（★ 里程碑门禁）─────────────────────────────────

/**
 * 权限批准。
 *
 * ⚠️ **不可省**：Agent 要改文件/执行命令时发 `permission.asked`，不响应就**卡死**，
 * 用户看到的是"卡住了"。
 * ⚠️ **不能盲批**：必须显示工具名 + 具体目标（文件路径 / 命令）。
 */
// ModalBottomSheet 在 Material3 里仍是 @ExperimentalMaterial3Api，必须显式 OptIn。
// 不用非实验性替代品：权限抽屉要求"点外面不能关、必须显式批准"，
// 这正是 ModalBottomSheet 的语义；换成普通 Dialog 会丢掉底部抽屉手势与层级。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionSheet(
    permission: OcPermission,
    onRespond: (allow: Boolean, remember: Boolean) -> Unit,
) {
    var remember by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = { /* 不允许点外面关掉——必须显式选择 */ }) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text("工具请求授权", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            // 工具名（action）：bash / edit / write …
            Text("工具：${permission.title}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            // 具体目标（resources[]）：命令 / 文件路径 —— 用户判断的唯一依据，必须显示
            permission.detail?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    "目标",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.Checkbox(remember, onCheckedChange = { remember = it })
            Text("记住这个选择", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { onRespond(false, false) },
                    modifier = Modifier.weight(1f),
                ) { Text("拒绝") }
                Button(
                    onClick = { onRespond(true, remember) },
                    modifier = Modifier.weight(1f),
                ) { Text("允许") }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "不处理会让 Agent 卡住等待，任务无法继续。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}