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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.zhengdao.oc.OcMessage
import com.example.zhengdao.oc.OcPart
import com.example.zhengdao.oc.OcPermission
import com.example.zhengdao.oc.OcSessionSummary
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
 *
 * v1.1 第一阶段新增左侧「☰ 历史」与右侧「＋ 新会话」入口（会话完整化）。
 * 两个回调都给默认空实现，避免影响既有调用点。
 */
@Composable
fun SessionBar(
    title: String,
    connection: ConnectionState,
    attempt: Int,
    onHistory: () -> Unit = {},
    onNew: () -> Unit = {},
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onHistory) {
            Text("☰", style = MaterialTheme.typography.titleMedium)
        }
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(
                title.ifEmpty { "新会话" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ConnectionLabel(connection, attempt)
        }
        IconButton(onClick = onNew) {
            Text("＋", style = MaterialTheme.typography.titleMedium)
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

/**
 * 单条消息。
 *
 * ## v1.1 第二阶段：视觉层级（「最终回答是主角」）
 *
 * - **用户消息**：右侧彩色气泡（`primaryContainer`），一眼可辨。
 * - **助手消息**：**不套气泡**，整宽文档流渲染——正文（最终回答）用
 *   [MaterialTheme.typography.bodyLarge] + `onSurface`（**大字号、高对比、无折叠**）；
 *   思考过程与工具调用作为**辅助**，分别以折叠块 / 低调卡片呈现。
 *
 * 之所以给助手消息去掉气泡：气泡会把长回答压进一个浅色窄容器，主次不分、可读性差；
 * 整宽文档流才能让"最终回答"真正成为主角（这也是主流对话客户端的做法）。
 */
@Composable
fun MessageBubble(msg: OcMessage) {
    if (msg.role == OcMessage.Role.USER) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.widthIn(max = 560.dp),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    msg.parts.forEach { PartRow(it) }
                }
            }
        }
        return
    }

    // 助手：整宽文档流。渲染单元已按需把连续 reasoning 合并（见 [groupParts]）。
    val items = remember(msg.parts) { groupParts(msg.parts) }
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items.forEach { item ->
            when (item) {
                is RenderItem.TextPart -> FinalAnswerText(item.part.text)
                is RenderItem.ReasoningGroup -> ReasoningBlock(item.parts)
                is RenderItem.ToolPart -> ToolCallCard(item.part)
                is RenderItem.Other -> PartRow(item.part)
            }
        }
    }
}

/** 最终回答正文：主角。大字号、高对比、无折叠。 */
@Composable
private fun FinalAnswerText(text: String) {
    if (text.isEmpty()) return
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

// ── 渲染分组（v1.1 第二阶段）──────────────────────────────────────────

/** 一条消息内的渲染单元。连续 reasoning 合并为一组，其余按 part 原样透传。 */
internal sealed interface RenderItem {
    data class TextPart(val part: OcPart.Text) : RenderItem
    data class ReasoningGroup(val parts: List<OcPart.Reasoning>) : RenderItem
    data class ToolPart(val part: OcPart.Tool) : RenderItem
    data class Other(val part: OcPart) : RenderItem
}

/**
 * 把消息的 parts 归并为渲染单元。
 *
 * **为什么合并连续 reasoning**：一条助手消息里，Agent 常在多次工具调用之间产生
 * 多段 reasoning（part 被拆碎）。若每段各画一个折叠块，头部会刷屏、层级被拉平；
 * 合并成一组后以「💭 思考过程 · N 步」呈现，N = 非空段数（数据直接可得，不猜测）。
 *
 * 只合并**相邻**的 reasoning —— 它们被文本/工具分隔时属于不同的思考阶段，不应跨段合并。
 *
 * `internal` 而非 `private`：供 JVM 单测（TaijiRenderGroupingTest）验证合并边界。
 */
internal fun groupParts(parts: List<OcPart>): List<RenderItem> {
    val out = mutableListOf<RenderItem>()
    var buf = mutableListOf<OcPart.Reasoning>()
    fun flush() {
        if (buf.isNotEmpty()) {
            out += RenderItem.ReasoningGroup(buf)
            buf = mutableListOf()
        }
    }
    parts.forEach { p ->
        when (p) {
            is OcPart.Reasoning -> buf += p
            else -> {
                flush()
                out += when (p) {
                    is OcPart.Text -> RenderItem.TextPart(p)
                    is OcPart.Tool -> RenderItem.ToolPart(p)
                    else -> RenderItem.Other(p)
                }
            }
        }
    }
    flush()
    return out
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
        is OcPart.Reasoning -> ReasoningBlock(listOf(part))
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
    // v1.1 第二阶段：统一为「<状态图标> 🔧 <工具名> · <状态>」。
    // 状态图标：运行中 ● / 完成 ✓ / 失败 ✗（计划第 8 条）。
    val (icon, tint, stateText) = when (part.state) {
        ToolState.Running -> Triple("●", MaterialTheme.colorScheme.primary, "执行中")
        ToolState.Success -> Triple("✓", MaterialTheme.colorScheme.primary, "已完成")
        ToolState.Error -> Triple("✗", MaterialTheme.colorScheme.error, "失败")
        ToolState.Unknown -> Triple("•", MaterialTheme.colorScheme.onSurfaceVariant, "")
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
                    "🔧 ${part.toolName.ifEmpty { "工具" }}",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (stateText.isNotEmpty()) {
                    Text(
                        "· $stateText",
                        style = MaterialTheme.typography.labelSmall,
                        color = tint,
                    )
                }
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
 * 思考过程的**折叠块**（v1.1 第二阶段）。
 *
 * 把一条消息内**相邻的多段 reasoning** 合并为一个折叠块：
 * - 头部：`💭 思考过程 · N 步　展开 ▼`（N = 非空段数；仅 1 段时省去「· N 步」）；
 * - 默认**折叠**（思考是辅助，不该抢正文的注意力）；
 * - 展开后以**灰色小字**渲染全部思考原文（辅助层级）。
 *
 * 与「最终回答」（大字号、高对比、无折叠）形成明确主次对比——这正是第二阶段要修的
 * 「正文看起来属于思考过程」的层级错位。
 */
@Composable
private fun ReasoningBlock(parts: List<OcPart.Reasoning>) {
    var expanded by remember { mutableStateOf(false) }
    val text = parts.joinToString("\n\n") { it.text }.trim()
    if (text.isEmpty()) return

    // 步数 = 非空段数（直接来自数据，不猜测）；<2 段时不显示「· N 步」，避免「· 1 步」的怪读法。
    val steps = parts.count { it.text.isNotBlank() }
    val head = if (steps > 1) "💭 思考过程 · $steps 步" else "💭 思考过程"
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
                head,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
 * @param isStreaming 流式输出中：发送键变为「■ 停止」，不让用户干等。
 *   生成中/可发送由 [TaijiState.isStreaming] 驱动（`prompt` 置真、SSE `session.idle` 置假，
 *   另有轮询「内容稳定即视为结束」兜底，防事件漏接）。
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
                    modifier = Modifier.weight(1f).onPreviewKeyEvent { e ->
                        // Enter 提交 / Shift+Enter 换行（v1.1 第三阶段；硬件键盘为主）。
                        // 仅在有可发送内容时拦截，其余情况放行给默认换行，避免误发/吞键。
                        if (e.type == KeyEventType.KeyDown && e.key == Key.Enter &&
                            !e.isShiftPressed && !isStreaming && enabled && input.isNotBlank()
                        ) {
                            onSend(); true
                        } else {
                            false
                        }
                    },
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
                    OutlinedButton(onClick = onAbort) { Text("■ 停止") }
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

// ── 历史会话抽屉（v1.1 第一阶段「会话完整化」）─────────────────────────

/**
 * 历史会话列表抽屉。
 *
 * 按 **今天 / 昨天 / 更早** 分组；每条显示标题 + 时间 + 消息条数。
 * 点击任一条即恢复该会话（[onPick]），当前会话高亮。
 *
 * [loading] 为 true 时显示进度（首次拉取元数据）——**不让用户对着空白发呆**；
 * 拉取失败不阻塞：调用方传空列表 + 由 [HistorySheet] 给出"还没有会话"文案。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySheet(
    sessions: List<OcSessionSummary>,
    loading: Boolean,
    currentId: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("历史会话", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            Spacer(Modifier.height(8.dp))

            when {
                sessions.isEmpty() && loading -> Text(
                    "正在加载…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                sessions.isEmpty() -> Text(
                    "还没有历史会话。点右上角「＋」开始第一段对话。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    groupSessionsByDay(sessions).forEach { (label, items) ->
                        item(key = "header-$label") { DayHeader(label) }
                        items(items, key = { it.id }) { s ->
                            SessionRow(
                                summary = s,
                                current = s.id == currentId,
                                onClick = { onPick(s.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayHeader(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun SessionRow(summary: OcSessionSummary, current: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (current) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    sessionTitle(summary),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        summary.updatedAt?.let { append(formatClock(it)) }
                        summary.messageCount?.let {
                            if (isNotEmpty()) append(" · ")
                            append("$it 条消息")
                        }
                    }.ifEmpty { summary.id.take(12) },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (current) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "当前",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * 会话标题：服务端 `title` 优先；为空回退「会话 + id 尾缀」。
 * **不调模型生成标题**（计划 P1-4 明确）。
 */
private fun sessionTitle(s: OcSessionSummary): String =
    s.title?.takeIf { it.isNotBlank() } ?: "会话 ${s.id.takeLast(6)}"

/**
 * 按天分组：今天 / 昨天 / 更早。保持输入顺序（服务端已按时间倒序）。时间缺失归「更早」。
 *
 * `internal` 而非 `private`：供 JVM 单元测试（TaijiSessionGroupingTest）直接验证分组
 * —— 这是 v1.1 第一阶段唯一可脱离真机自动验证的核心逻辑。同模块可见，不扩大外部 API。
 */
internal fun groupSessionsByDay(list: List<OcSessionSummary>): List<Pair<String, List<OcSessionSummary>>> {
    val out = LinkedHashMap<String, MutableList<OcSessionSummary>>()
    list.forEach { out.getOrPut(dayLabel(it.updatedAt)) { mutableListOf() }.add(it) }
    return out.map { it.key to it.value }
}

/** 「今天 / 昨天 / 更早」判据。`internal` 供单测（见 [groupSessionsByDay]）。 */
internal fun dayLabel(ts: Long?): String {
    ts ?: return "更早"
    val startOfToday = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    return when {
        ts >= startOfToday -> "今天"
        ts >= startOfToday - 86_400_000L -> "昨天"
        else -> "更早"
    }
}

private fun formatClock(ts: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ts))