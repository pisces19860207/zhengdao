// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据的公开接口：Jetpack Compose（Material 3）官方 API。
//
// P4-1（2026-10-14）：自 TaijiComponents.kt（拆分前 1929 行）按内聚性拆出——
// **只搬不改**，代码逐行原样（含已提交的 P2-10 48dp 触摸目标修复）。
package com.example.zhengdao.ui.taiji

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.oc.OcMessage
import com.example.zhengdao.oc.OcPart
import com.example.zhengdao.oc.OcTodo
import com.example.zhengdao.ui.AttachmentGlyph
import com.example.zhengdao.ui.util.pressClickable
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState

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
    // K2（切回 Tab 保留滚动位置）：外部 HomeTabs 注入的 listState。
    // 不传时退化到内部新建（保持向后兼容——若日后有调用方未更新）。
    listState: LazyListState = rememberLazyListState(),
    // K2 配套（2026-10-08 审查）：跟随标记与"已经为哪个会话定位过"也必须由外部注入。
    // 只用页面内的 remember，切 Tab（本组件退出 Composition）后两者都会重置成"刚进入会话"，
    // 下面 ④/⑤ 两个 LaunchedEffect 会在重新出现的瞬间把滚动位置拉回底部 ⇒ K2 失效。
    followState: MutableState<Boolean> = remember { mutableStateOf(true) },
    positionedSession: MutableState<String?> = remember { mutableStateOf(null) },
    // 长按某条消息（2026-10-09 用户需求「对话那里能长按复制」）。
    // 默认空实现：老调用方（含仪器/单测里的直接调用）不改也能编译；真正的入口在 TaijiScreen。
    onLongPressMessage: (OcMessage) -> Unit = {},
) {
    // ❌ 原本 `val listState = rememberLazyListState()` 已被 K2 提到入参。
    //    切 Tab 走 Composable 出入 Composition 的路径，state 提到 HomeTabs 顶层才能跨 Tab 保留。
    //    切会话（LaunchedEffect(sessionId) 滚到底）的逻辑不动——那是另一条路径。

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
    //    状态由外部注入（K2 配套，见函数签名）：切 Tab 回来不能重置成 true，
    //    否则 ⑤ 会在重新出现的瞬间贴底，把用户保留的滚动位置吃掉。

    val nestedScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // 只要滚动来自**用户手势**就先停手 —— 无论方向。
                // 刻意不判 available.y 的符号：方向约定易错，且"用户想回到底部时被内容
                // 拽着走"同样是抢。用户停手后若确实在底部，下面的 snapshotFlow 会自动恢复。
                if (source == NestedScrollSource.UserInput) followState.value = false
                return Offset.Zero
            }
        }
    }

    // 用户滚动停下后，若已回到底部 → 恢复跟随（只认稳定态，避免滚动中反复翻转）
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && atBottom) followState.value = true
        }
    }

    // ④ 进入 / 切换会话：直接定位到最后一条（瞬时，不从顶部滚下来）。
    //    ⚠️ 只在「会话真的换了」时定位：positionedSession 由外部持有（K2 配套），
    //    切 Tab 回来时 sessionId 没变，就不能再拉一次 —— 否则保留的位置又被踢到底部。
    LaunchedEffect(sessionId) {
        if (totalItems > 0 && positionedSession.value != sessionId) {
            positionedSession.value = sessionId
            followState.value = true // 新看到的会话从「跟随最新」开始
            listState.scrollToItem(totalItems - 1, Int.MAX_VALUE)
        }
    }

    // ⑤ 新消息 / 流式内容变化：跟随贴底。
    //    follow 也进 key —— 点「回到最新」置 true 后能立刻贴底。
    LaunchedEffect(ordered, isStreaming, totalItems, followState.value) {
        if (followState.value && totalItems > 0) listState.scrollToItem(totalItems - 1, Int.MAX_VALUE)
    }

    Box(modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().nestedScroll(nestedScroll),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (todos.isNotEmpty()) { item { TodoPanel(todos) } }

            items(ordered, key = { it.id }) { msg ->
                MessageBubble(msg, onLongPress = { onLongPressMessage(msg) })
            }

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
            visible = !followState.value,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            JumpToLatestButton(onClick = { followState.value = true })
        }
    }
}

/**
 * 「⬇ 回到最新」浮标：翻历史时出现，点一下回到底部并恢复跟随。
 *
 * 只负责展示与回调 —— 真正的跟随由 [MessageList] 的 `followState` 驱动
 * （点击置 true 后，上方的 LaunchedEffect 立即贴底）。
 */
@Composable
private fun JumpToLatestButton(onClick: () -> Unit) {
    // semantics{} 的 lambda 不是 @Composable，取文本先落局部变量
    val desc = stringResource(R.string.taiji_back_to_latest_desc)
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shadowElevation = 4.dp,
        // 同上：胶囊浮标，涟漪要跟着圆形走
        modifier = Modifier.clip(RoundedCornerShape(50)).pressClickable(onClick = onClick),
    ) {
        Text(
            stringResource(R.string.taiji_back_to_latest),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            // 2026-10-08 走查：vertical 10dp → 14dp。原来 10+labelLarge 行高 ≈ 26dp 高，
            // 未达 Google 无障碍建议 48dp。这条按钮出现时机就是用户**远离消息流尾部**，
            // 离主题（点哪个回复）很远；点不中会再去手动往上翻，是高频失手点。
            // 未一步到 48dp：这是浮标，再厚会盖住消息；取 44dp 高（bodyMedium + 14sp）。
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 14.dp)
                // 同上：浮标纯文字 Surface，没 contentDescription 读屏只能读出"⬇ 回到最新"
                // ——能听懂，但没标它是按钮，故加 role 让用户知道可以点。
                .semantics { contentDescription = desc },
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
fun MessageBubble(msg: OcMessage, onLongPress: () -> Unit = {}) {
    // 长按手势（2026-10-09）：移动端没有文本选择，长按是"我想对这条消息做点什么"的通用手势。
    // 这里只负责**触发**（回调给 TaijiScreen 弹操作面板），不做任何复制动作本身——
    // 组件不碰 Context/剪贴板，逻辑全在 [MessageCopy] + TaijiScreen，便于 JVM 单测。
    //
    // 刻意不用 `indication = null`：用户气泡本来就有点击语义（将来可加"引用"），
    // 长按时的涟漪是"手势被识别了"的第一反馈（真机上长按无反馈等于没按）。
    if (msg.role == OcMessage.Role.USER) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .combinedClickable(
                        onLongClick = onLongPress,
                        onLongClickLabel = stringResource(R.string.taiji_copy_message),
                        onClick = {},
                        // 2026-10-10 UIUX 审计 B6 修：单击气泡**没有动作**却带涟漪
                        // ⇒ 用户看到"按下去有反应、结果什么也没发生"，是最典型的手感欺骗。
                        // 与下方助手气泡一致：关掉 indication，只保留长按识别。
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    msg.parts.forEach { PartRow(it, onLongPress = onLongPress) }
                }
            }
        }
        return
    }

    // 助手：整宽文档流。渲染单元已按需把连续 reasoning 合并（见 [groupParts]）。
    val items = remember(msg.parts) { groupParts(msg.parts) }
    Column(
        // 助手消息是无边框文档流，"整块都可点"的涟漪会很怪 ⇒ 关掉 indication，
        // 只保留长按识别本身（卡片内部的展开头另有自己的涟漪，见 ToolCallCard/ReasoningBlock）。
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onLongClick = onLongPress,
                onLongClickLabel = stringResource(R.string.taiji_copy_message),
                onClick = {},
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items.forEach { item ->
            when (item) {
                is RenderItem.TextPart -> FinalAnswerText(item.part.text)
                is RenderItem.ReasoningGroup -> ReasoningBlock(item.parts, onLongPress)
                is RenderItem.ToolPart -> ToolCallCard(item.part, onLongPress)
                is RenderItem.Other -> PartRow(item.part, onLongPress = onLongPress)
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
fun PartRow(part: OcPart, onLongPress: (() -> Unit)? = null) {
    when (part) {
        is OcPart.Text -> if (part.text.isNotEmpty()) {
            Text(part.text, style = MaterialTheme.typography.bodyMedium)
        }
        // ⚠️ 严格按 **part.type** 分派（不靠"是否含 thinking 标签"猜）：
        //    `reasoning` 只进独立的样式化折叠块；`text` 走上一个分支的正文 Text。
        is OcPart.Reasoning -> ReasoningBlock(listOf(part), onLongPress)
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
            ToolCallCard(part, onLongPress)
        }
        is OcPart.File -> Row(verticalAlignment = Alignment.CenterVertically) {
            AttachmentGlyph(tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text(part.filename, style = MaterialTheme.typography.bodySmall)
        }
        // ⚠️ 未知 part 保留原文而非静默丢弃（见 OcDto 注释）
        is OcPart.Unknown -> CollapsibleBlock(
            stringResource(R.string.taiji_unknown_part, part.type),
            onLongPress,
        ) {
            Text(part.raw.take(400), style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace)
        }
    }
}
