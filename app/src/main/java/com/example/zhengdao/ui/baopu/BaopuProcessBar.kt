// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百三十九批（2026-10-22，派工单 §范围）：
//   **步骤卡 / 工具调用卡整体撤出正文**；每条回答尾巴只挂**一行折叠过程条**
//   （`› 看了 3 个文件 · 跑了 5 步`）：整行可点，点开是**浮层明细**（限高 180dp、内部可滚），
//   再点收回。正文不再因为展开明细而被推长。
//
// 与 106 批的分工：活动数据源不变（run_log 活动行 + 该轮 tool part 的目标摘要 + 引擎状态），
// 不新造 RunState、不改内核 SQL、不碰计划胶囊。计划胶囊＝前瞻「要做什么」，过程条＝回顾
// 「干了什么」（设计 §5）。
//
// 判据纪律：人话摘要的取数（文件数 / 步数 / 失败数 / 是否只有待确认）全部是**纯函数**
// （无 Compose 依赖，JVM 直测）；Compose 层只做文案选择与浮层接线。
//
// 139 批设计约束（11:50）：记录条视觉 44dp、命中热区 48dp（视觉与命中分离），
// 单行 13sp 省略号，展开区圆角 rSheet 24、限高 180dp、内部滚动、不外推正文。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.PopupPositionProvider
import com.example.zhengdao.R
import com.example.zhengdao.baopu.agent.DataFence
import com.example.zhengdao.baopu.agent.RunState
import com.example.zhengdao.ui.theme.rSheet
import com.example.zhengdao.ui.theme.zhengdaoErrorText

// ── 尺寸与条数（唯一真源：门禁按常量读数） ─────────────────────────────────

/** 展开明细浮层的视觉上限（设计约束：限高 180dp，内部滚动）。 */
internal const val PROCESS_DETAIL_MAX_DP = 180

/** 明细流最多摆几条（沿用 106 批口径；更多折成「还有 N 条」）。 */
internal const val ACTIVITY_STREAM_MAX = 20

/** 明细行的最小高度（沿用 106 批口径：条目行 ≥48dp）。 */
internal const val ACTIVITY_ROW_TOUCH_DP = 48

/** 目标短描述的字数上限（超了省略，一行一个意思）。 */
internal const val ACTIVITY_TARGET_CHARS = 40

/**
 * 耗时取不到时的占位（第一百三十一批 UI-D7）。
 *
 * 为什么不是「0s」：`run_log` 里 `latency_ms` 缺省就是 0，而 `formatDuration(0)` 会吐出
 * **「0s」——那是一个假读数**（看着像"这步瞬间完成"）。取不到就明说不知道：`—`。
 */
internal const val ACTIVITY_LATENCY_UNKNOWN = "—"

/**
 * 「看文件」这一档的工具名（**人话摘要的唯一分类表**）。
 *
 * 只用于过程条摘要里的「看了 N 个文件」；明细里仍显示真工具名（明细是给回看用的，
 * 不是给扫一眼用的）。不把写入/删除算作「看了」——那是另一回事。
 */
internal val PROCESS_FILE_TOOLS: Set<String> = setOf(
    "read_file",
    "list_dir",
    "find_files",
    "search_in_files",
    "file_info",
    "image_ops",
    "book_outline",
    "card_inspect",
    "library_list",
)

// ── 纯数据 ────────────────────────────────────────────────────────────────

/**
 * 面板行类型（设计 §3.12「活动类型映射」表）：
 * 真工具名 ⇒ [TOOL]；`compression_*` ⇒ [COMPRESSION]；引擎在等确认 ⇒ [AWAIT_CONFIRM]；
 * `ok=0` ⇒ [FAILED]。
 */
internal enum class ActivityKind { TOOL, COMPRESSION, AWAIT_CONFIRM, FAILED }

/** 明细流的一行（纯数据，渲染层只翻译文案）。 */
internal data class ActivityItem(
    val kind: ActivityKind,
    val tool: String,
    /** 目标短描述（取自该轮同名 tool part 的摘要；没有 ⇒ 空串，只显工具名）。 */
    val target: String,
    /** 失败原因（引擎层已人话化；不展示堆栈）。 */
    val err: String,
    val latencyMs: Long,
)

/** 明细流切片结果：[shown] 是最多 [ACTIVITY_STREAM_MAX] 条（最新在上），[more] 是被折掉的条数。 */
internal data class ActivityStream(val shown: List<ActivityItem>, val more: Int)

/**
 * 过程条摘要的三个数（**纯数据**）：文件数 / 步数 / 失败数；[awaiting] 表示引擎正在等确认。
 *
 * 可见判据：只要有干活步骤或在等确认就出条；都没有 ⇒ **空过程不显示**（派工单负例）。
 */
internal data class ProcessFacts(
    val files: Int,
    val steps: Int,
    val fails: Int,
    val awaiting: Boolean,
) {
    val visible: Boolean get() = steps > 0 || awaiting
}

// ── 纯判据（JVM 直测；门禁在 BaopuProcessBarTest） ─────────────────────────

/** 一条工具结果是不是失败（tool part 没有 ok 列，只能看引擎写的文案前缀）。 */
private fun toolTextFailed(text: String): Boolean =
    text.startsWith("失败") || text.startsWith("被拒")

/** 该轮消息里的工具 part（139：它们不再进正文，但仍是明细的数据源）。 */
private fun toolPartsOf(messages: List<BaopuUiMessage>): List<BaopuUiPart> =
    messages.flatMap { it.parts }.filter { it.kind == "tool" && it.tool.isNotBlank() }

/**
 * 过程条摘要（**纯函数**）：
 *
 * 1. 优先用 run_log 活动行（有 `ok` 与 `latency_ms` 的权威数据）；`compression_*` 是引擎内务，
 *    不算这一轮干的活（沿用 16 批口径）；
 * 2. run_log 没有行时（老会话 / 只落了消息 part）回落到该轮 tool part——**16 批步骤数据不丢**，
 *    只是失败数改看文案前缀、耗时在明细里显示 `—`；
 * 3. 文件数只数**成功**的「看文件」步骤（失败的那一步进 fails，不假装看成了）；
 * 4. 步数 = 全部活动步骤（含文件步骤）；「跑了 N 步」与「看了 M 个文件」不互斥。
 *
 * 负例：把 compression 行算进 steps ⇒ 压缩内务把过程条撑大 ⇒ 门禁红。
 */
internal fun processFacts(
    rows: List<BaopuStepRow>,
    messages: List<BaopuUiMessage>,
    awaitConfirmTool: String?,
): ProcessFacts {
    val awaiting = !awaitConfirmTool.isNullOrBlank()
    val activity = rows.filter { !it.tool.startsWith("compression_") }
    if (activity.isNotEmpty()) {
        return ProcessFacts(
            files = activity.count { it.ok && it.tool in PROCESS_FILE_TOOLS },
            steps = activity.size,
            fails = activity.count { !it.ok },
            awaiting = awaiting,
        )
    }
    val parts = toolPartsOf(messages)
    return ProcessFacts(
        files = parts.count { !toolTextFailed(it.text) && it.tool in PROCESS_FILE_TOOLS },
        steps = parts.size,
        fails = parts.count { toolTextFailed(it.text) },
        awaiting = awaiting,
    )
}

/**
 * run_log 活动行 ⇒ 明细行（**纯函数**）。
 *
 * 顺序口径：`ok=0` 优先判失败——一条失败的压缩留痕也要先说失败（原因比归类重要）；
 * 其余按 `compression_` 前缀归类。
 */
internal fun activityItemOf(row: BaopuStepRow, target: String): ActivityItem = when {
    !row.ok -> ActivityItem(ActivityKind.FAILED, row.tool, target, row.err, row.latencyMs)
    row.tool.startsWith("compression_") ->
        ActivityItem(ActivityKind.COMPRESSION, row.tool, target, "", row.latencyMs)
    else -> ActivityItem(ActivityKind.TOOL, row.tool, target, "", row.latencyMs)
}

/**
 * 该步耗时的显示文本（**纯函数**，第一百三十一批 UI-D7）。
 *
 * `<= 0` ⇒ 取不到 ⇒ `—`，**不许写 0s 假装知道**；亚秒保留一位小数；≥1s 复用 [formatDuration]。
 */
internal fun activityLatencyText(latencyMs: Long): String {
    if (latencyMs <= 0L) return ACTIVITY_LATENCY_UNKNOWN
    if (latencyMs < 1_000L) {
        val tenths = (latencyMs + 50L) / 100L
        return if (tenths >= 10L) "1.0s" else "0.${tenths.coerceAtLeast(1L)}s"
    }
    return formatDuration(latencyMs)
}

/**
 * 该轮的活动流（**纯函数**）：run_log 行**最新在上**，最多 [limit] 条，余下折成「还有 N 条」。
 *
 * [awaitConfirmTool] 非空（引擎正在等这一个工具的确认）⇒ 流首插一条「等确认」行——
 * 它来自**引擎状态**，不是新事件源；这样「等确认」不必给 run_log 加 state 列。
 */
internal fun activityStream(
    rows: List<BaopuStepRow>,
    targets: Map<String, String> = emptyMap(),
    awaitConfirmTool: String? = null,
    limit: Int = ACTIVITY_STREAM_MAX,
): ActivityStream {
    val items = ArrayList<ActivityItem>(rows.size + 1)
    if (!awaitConfirmTool.isNullOrBlank()) {
        items += ActivityItem(
            kind = ActivityKind.AWAIT_CONFIRM,
            tool = awaitConfirmTool,
            target = targets[awaitConfirmTool].orEmpty(),
            err = "",
            latencyMs = 0L,
        )
    }
    rows.asReversed().forEach { items += activityItemOf(it, targets[it.tool].orEmpty()) }
    val shown = items.take(limit.coerceAtLeast(1))
    return ActivityStream(shown = shown, more = (items.size - shown.size).coerceAtLeast(0))
}

/**
 * 这一轮的明细流（**纯函数**）：优先 run_log（权威步数据）；没有时回落到 tool part。
 *
 * 回落不是"另一套事件源"——它就是同一轮消息里已经存着的 part，只是 139 批开始
 * 不再把工具卡画进正文，所以明细必须自己把它接住。
 */
internal fun processStream(
    rows: List<BaopuStepRow>,
    messages: List<BaopuUiMessage>,
    awaitConfirmTool: String?,
    limit: Int = ACTIVITY_STREAM_MAX,
): ActivityStream {
    val targets = toolTargets(messages)
    if (rows.isNotEmpty()) return activityStream(rows, targets, awaitConfirmTool, limit)

    val items = ArrayList<ActivityItem>()
    if (!awaitConfirmTool.isNullOrBlank()) {
        items += ActivityItem(
            kind = ActivityKind.AWAIT_CONFIRM,
            tool = awaitConfirmTool,
            target = targets[awaitConfirmTool].orEmpty(),
            err = "",
            latencyMs = 0L,
        )
    }
    toolPartsOf(messages).asReversed().forEach { part ->
        val failed = toolTextFailed(part.text)
        items += ActivityItem(
            kind = if (failed) ActivityKind.FAILED else ActivityKind.TOOL,
            tool = part.tool,
            target = if (failed) "" else shortTarget(part.text),
            err = if (failed) part.text else "",
            latencyMs = 0L,
        )
    }
    val shown = items.take(limit.coerceAtLeast(1))
    return ActivityStream(shown = shown, more = (items.size - shown.size).coerceAtLeast(0))
}

/**
 * 目标短描述（**纯函数**）：压成一行、去掉首尾空白、超 [max] 字省略。
 *
 * 取的是**该轮消息里同名 tool part 的摘要**（不新读库、不解析参数 JSON）。
 */
internal fun shortTarget(text: String, max: Int = ACTIVITY_TARGET_CHARS): String {
    // 成功结果在库里带 DataFence 围栏（历史编码口径）：
    // 取围栏里的正文，别把 `<tool_result name=… ts=…>` 这行当目标显示给用户。
    val body = if (text.startsWith(DataFence.FENCE_OPEN)) {
        text.substringAfter('\n', "")
            .substringAfter('\n', "")
            .substringBefore("</tool_result>")
    } else {
        text
    }
    val oneLine = body.replace(Regex("\\s+"), " ").trim()
    return if (oneLine.length <= max) oneLine else oneLine.take(max) + "…"
}

/** 该轮消息里的「工具名 ⇒ 目标短描述」（同名工具取**第一条**非空摘要）。 */
internal fun toolTargets(messages: List<BaopuUiMessage>): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    messages.forEach { m ->
        m.parts.forEach { p ->
            if (p.kind == "tool" && p.tool.isNotBlank() && p.text.isNotBlank() && out[p.tool] == null) {
                out[p.tool] = shortTarget(p.text)
            }
        }
    }
    return out
}

// ── 渲染 ──────────────────────────────────────────────────────────────────

/**
 * 每条回答尾巴的那一行过程条（**整行可点**；`›` 只是视觉提示）。
 *
 * 空过程不渲染。点开是 [ProcessDetailPopup] 浮层：不进入消息列表的布局流，
 * 因此**不推正文**；再点/点外面/返回键收回。
 */
@Composable
internal fun BaopuProcessBar(
    round: BaopuRound,
    steps: List<BaopuStepRow>,
    running: Boolean,
    state: RunState,
    engineStep: Int,
    awaitConfirmTool: String?,
    modifier: Modifier = Modifier,
) {
    val rows = remember(steps, round.startMs, round.endMs) {
        stepsInWindow(steps, round.startMs, round.endMs)
    }
    val facts = remember(rows, round.messages, awaitConfirmTool) {
        processFacts(rows, round.messages, awaitConfirmTool)
    }
    if (!facts.visible) return

    var open by remember(round.userMessageId) { mutableStateOf(false) }
    var barWidthPx by remember { mutableIntStateOf(0) }
    val label = processBarLabel(facts)
    val toggleLabel = stringResource(
        if (open) R.string.baopu_process_toggle_close else R.string.baopu_process_toggle_open,
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .onGloballyPositioned { barWidthPx = it.size.width },
    ) {
        BaopuRecordRow(
            text = label,
            onClick = { open = !open },
            onClickLabel = toggleLabel,
        )
        if (open) {
            val density = LocalDensity.current
            val gapPx = with(density) { 4.dp.roundToPx() }
            val popupWidth: Dp = if (barWidthPx > 0) with(density) { barWidthPx.toDp() } else 320.dp
            ProcessDetailPopup(
                round = round,
                rows = rows,
                summary = label,
                running = running,
                state = state,
                engineStep = engineStep,
                awaitConfirmTool = awaitConfirmTool,
                popupWidth = popupWidth,
                gapPx = gapPx,
                onDismiss = { open = false },
            )
        }
    }
}

/** 过程条的人话摘要（**渲染层唯一拼文案处**；中英都走 strings.xml）。 */
@Composable
private fun processBarLabel(facts: ProcessFacts): String {
    val base = when {
        facts.steps == 0 && facts.awaiting -> stringResource(R.string.baopu_process_waiting)
        facts.files > 0 -> pluralStringResource(
            R.plurals.baopu_process_files_steps, facts.steps, facts.files, facts.steps,
        )
        else -> pluralStringResource(R.plurals.baopu_process_steps, facts.steps, facts.steps)
    }
    return if (facts.steps > 0 && facts.fails > 0) {
        base + " · " + pluralStringResource(R.plurals.baopu_process_fails, facts.fails, facts.fails)
    } else {
        base
    }
}

/**
 * 展开的明细浮层：rSheet 24、限高 180dp、内部滚动，锚在过程条正下方（放不下就翻到上方）。
 *
 * 用 `Popup` 而不是在行内展开：行内展开会把正文往下推，正是本批要消掉的毛病。
 */
@Composable
private fun ProcessDetailPopup(
    round: BaopuRound,
    rows: List<BaopuStepRow>,
    summary: String,
    running: Boolean,
    state: RunState,
    engineStep: Int,
    awaitConfirmTool: String?,
    popupWidth: Dp,
    gapPx: Int,
    onDismiss: () -> Unit,
) {
    val stream = remember(rows, round.messages, awaitConfirmTool) {
        processStream(rows, round.messages, awaitConfirmTool)
    }
    val positionProvider = remember(gapPx) { BelowAnchorPopupPositionProvider(gapPx) }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnClickOutside = true,
            dismissOnBackPress = true,
        ),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(rSheet),
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
            modifier = Modifier
                .width(popupWidth)
                .heightIn(max = PROCESS_DETAIL_MAX_DP.dp),
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (running && round.isCurrent) {
                        Text(
                            text = stringResource(
                                R.string.baopu_activity_running,
                                stringResource(stateLabel(state)),
                                engineStep,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.baopu_process_detail_title),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                stream.shown.forEach { item ->
                    ActivityRow(item)
                }
                if (stream.more > 0) {
                    Text(
                        text = stringResource(R.string.baopu_activity_more, stream.more),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp, top = 4.dp, bottom = 2.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

/** 过程条明细的一行：类型签＋工具名＋耗时（第一行）／目标＋状态（第二行，失败给可读原因）。 */
@Composable
private fun ActivityRow(item: ActivityItem) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = ACTIVITY_ROW_TOUCH_DP.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        ActivityKindChip(item.kind)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.tool,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // 131 批 UI-D7：耗时位**恒在**（取不到显示 —）。
                Text(
                    text = activityLatencyText(item.latencyMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.kind == ActivityKind.FAILED) {
                Text(
                    text = stringResource(
                        R.string.baopu_activity_reason,
                        item.err.ifBlank { item.target },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = zhengdaoErrorText(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            } else if (item.target.isNotBlank()) {
                Text(
                    text = item.target,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** 类型签（工具/压缩/等确认/失败）：四种都有明确底色，不靠颜色单独表意（带字）。 */
@Composable
private fun ActivityKindChip(kind: ActivityKind) {
    val (bg, fg) = when (kind) {
        ActivityKind.TOOL -> MaterialTheme.colorScheme.primaryContainer to
            MaterialTheme.colorScheme.onPrimaryContainer
        ActivityKind.COMPRESSION -> MaterialTheme.colorScheme.surfaceVariant to
            MaterialTheme.colorScheme.onSurfaceVariant
        ActivityKind.AWAIT_CONFIRM -> MaterialTheme.colorScheme.tertiaryContainer to
            MaterialTheme.colorScheme.onTertiaryContainer
        ActivityKind.FAILED -> MaterialTheme.colorScheme.errorContainer to
            MaterialTheme.colorScheme.onErrorContainer
    }
    val label = when (kind) {
        ActivityKind.TOOL -> R.string.baopu_activity_kind_tool
        ActivityKind.COMPRESSION -> R.string.baopu_activity_kind_compression
        ActivityKind.AWAIT_CONFIRM -> R.string.baopu_activity_kind_await
        ActivityKind.FAILED -> R.string.baopu_activity_kind_failed
    }
    Surface(color = bg, shape = RoundedCornerShape(6.dp)) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * 浮层定位：锚点正下方 [gapPx]；下方放不下就翻到上方；两边都放不下就贴屏幕底。
 * 水平方向与锚点左缘对齐，并夹在窗口内。
 */
private class BelowAnchorPopupPositionProvider(private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = anchorBounds.left.coerceIn(
            0,
            (windowSize.width - popupContentSize.width).coerceAtLeast(0),
        )
        val below = anchorBounds.bottom + gapPx
        val above = anchorBounds.top - gapPx - popupContentSize.height
        val y = when {
            below + popupContentSize.height <= windowSize.height -> below
            above >= 0 -> above
            else -> (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        }
        return IntOffset(x, y)
    }
}
