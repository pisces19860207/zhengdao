// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据的公开接口：Jetpack Compose（Material 3）官方 API。
package com.example.zhengdao.ui.taiji

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
// Markdown 渲染（v1.1 第四阶段）：仅最终回答使用
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
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
 * 消息列表（v1.1 体验修复：**正序显示 + 智能跟随滚动**）。
 *
 * ## 顺序
 * 老消息在上、新消息在下 —— 与微信一致。数据层顺序就是 API 顺序，这里在**渲染层**
 * 用 [orderChronologically] 收敛一次，**不动数据层**。
 *
 * ## 跟随滚动
 * 进入会话先定位到最新一条；发消息 / 流式回复中持续贴底跟随。
 * **用户手动上滑看历史时立刻停手**（关键：不能跟用户抢），并浮出「⬇ 回到最新」；
 * 点它、或用户自己滑回底部 → 恢复跟随。切换会话则重新定位到该会话底部。
 *
 * 判定「用户上滑」只认**用户手势**（nested-scroll 的 `UserInput` 来源），
 * 程序自身的贴底滚动不参与判定 —— 否则「贴底 → isScrollInProgress → 误判为上滑」
 * 会自锁成"再也不跟随"。
 *
 * @param sessionId 值变化即视为"换了会话"，重新定位到底部。
 */
@Composable
fun MessageList(
    messages: List<OcMessage>,
    todos: List<OcTodo>,
    isStreaming: Boolean,
    sessionId: String? = null,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // ① 正序：老在上、新在下（渲染层收敛，数据层不动）
    val ordered = remember(messages) { orderChronologically(messages) }

    // 列表总项数（todos 面板 + 消息 + 流式指示器）—— "最后一项"的 index 由它决定
    val totalItems = (if (todos.isNotEmpty()) 1 else 0) + ordered.size + (if (isStreaming) 1 else 0)

    // ② 是否贴底：最后一项可见，且其底部已落在视口内
    val atBottom by remember {
        androidx.compose.runtime.derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.index >= info.totalItemsCount - 1 &&
                last.offset + last.size <= info.viewportEndOffset + 8
        }
    }

    // ③ 是否跟随最新。用户主动上滑看历史 → false（不抢用户的滚动）
    var follow by remember { mutableStateOf(true) }

    val nestedScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // 只要滚动来自**用户手势**就先停手 —— 无论方向。
                // 刻意不判 available.y 的符号：方向约定易错，且"用户想回到底部时被内容
                // 拽着走"同样是抢。用户停手后若确实在底部，下面的 snapshotFlow 会自动恢复。
                if (source == NestedScrollSource.UserInput) follow = false
                return Offset.Zero
            }
        }
    }

    // 用户滚动停下后，若已回到底部 → 恢复跟随（只认稳定态，避免滚动中反复翻转）
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && atBottom) follow = true
        }
    }

    // ④ 进入 / 切换会话：直接定位到最后一条（瞬时，不从顶部滚下来）
    LaunchedEffect(sessionId) {
        if (totalItems > 0) listState.scrollToItem(totalItems - 1, Int.MAX_VALUE)
    }

    // ⑤ 新消息 / 流式内容变化：跟随贴底。
    //    follow 也进 key —— 点「回到最新」置 true 后能立刻贴底。
    LaunchedEffect(ordered, isStreaming, totalItems, follow) {
        if (follow && totalItems > 0) listState.scrollToItem(totalItems - 1, Int.MAX_VALUE)
    }

    Box(modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().nestedScroll(nestedScroll),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (todos.isNotEmpty()) { item { TodoPanel(todos) } }

            items(ordered, key = { it.id }) { msg -> MessageBubble(msg) }

            if (isStreaming) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(8.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }
                }
            }
        }

        // ⑥ 正在翻历史时浮出「⬇ 回到最新」
        AnimatedVisibility(
            visible = !follow,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            JumpToLatestButton(onClick = { follow = true })
        }
    }
}

/**
 * 「⬇ 回到最新」浮标：翻历史时出现，点一下回到底部并恢复跟随。
 *
 * 只负责展示与回调 —— 真正的跟随由 [MessageList] 的 `follow` 驱动
 * （点击置 true 后，上方的 LaunchedEffect 立即贴底）。
 */
@Composable
private fun JumpToLatestButton(onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shadowElevation = 4.dp,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            "⬇ 回到最新",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

/**
 * 渲染层正序保证：老消息在上、新消息在下（与微信一致）。
 *
 * 数据层顺序就是 API 返回顺序，本项目对消息端点**未做排序约定**，故在此统一收敛：
 *
 * - **全部消息都带 [OcMessage.timeCreated]** → 按时间升序；已经正序时**原样返回**
 *   （同一实例，避免无谓新建列表触发下游重组）。
 * - **有任一缺失时间戳** → 原样返回。此时排序会把消息打乱成"看起来随机"，比不排更糟。
 *
 * 纯展示层行为，[com.example.zhengdao.oc.OcRepository] 不受影响。
 * `internal` 供 JVM 单测（`TaijiMessageOrderTest`）验证。
 */
internal fun orderChronologically(messages: List<OcMessage>): List<OcMessage> {
    if (messages.size < 2) return messages
    val stamps = messages.map { it.timeCreated ?: return messages }
    if (stamps.zipWithNext().all { (a, b) -> a <= b }) return messages   // 已正序，零改动
    return messages.sortedBy { it.timeCreated ?: Long.MAX_VALUE }
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

/**
 * 最终回答正文：主角。大字号、高对比、无折叠。
 *
 * ## v1.1 第四阶段：升级为 Markdown 渲染
 *
 * 由纯 [Text] 升级为 Markdown（标题 / 列表 / 表格 / 引用 / 链接 / 代码块）。三个关键约束：
 *
 * 1. **显式 `Modifier.fillMaxWidth()`** —— 库默认 modifier 是 `fillMaxSize()`，直接用在
 *    消息流里会撑破布局（报告 R3）。
 * 2. **`retainState = true`** —— 流式追加时不重置内部状态、不闪 loading（报告 R1）。
 * 3. **`markdownColor` / `markdownTypography` 显式对齐第二阶段视觉**（`onSurface` + `bodyLarge`），
 *    避免库默认字号/颜色造成视觉回归（报告 R2）。
 *
 * 代码块走 code 模块：独立背景 + 等宽 + 横向滚动 + 语法高亮 + 顶部语言标签与**复制按钮**
 * （`showHeader = true`，计划第 12 条里复制按钮优先级最高）。
 *
 * 渲染范围（报告 §5.3）：**仅最终回答** Markdown 化。[ReasoningBlock] 与 [ToolCallCard]
 * 保持纯文本 —— 它们是日志性质，Markdown 化只增噪音与解析开销。
 *
 * 注：流式重解析是上游 **长期** 限制（mikepenz Issue #315，作者明确拒绝增量解析），
 * 非临时问题；节流为长期策略，详见报告 §6 R1。
 */
@Composable
private fun FinalAnswerText(text: String) {
    if (text.isEmpty()) return
    val markdownState = rememberMarkdownState(text, retainState = true)
    Markdown(
        markdownState = markdownState,
        modifier = Modifier.fillMaxWidth(),
        colors = markdownColor(text = MaterialTheme.colorScheme.onSurface),
        typography = markdownTypography(
            text = MaterialTheme.typography.bodyLarge,
            code = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        ),
        // 代码块：独立背景 + 等宽 + 横向滚动 + 语法高亮 + 顶部语言标签与复制按钮（showHeader = true）。
        // 注：markdownComponents 的 slot 是普通参数（非 receiver），故用 it 取 content/node/typography。
        components = markdownComponents(
            codeFence = {
                MarkdownHighlightedCodeFence(it.content, it.node, it.typography.code, showHeader = true)
            },
            codeBlock = {
                MarkdownHighlightedCodeBlock(it.content, it.node, it.typography.code, showHeader = true)
            },
        ),
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