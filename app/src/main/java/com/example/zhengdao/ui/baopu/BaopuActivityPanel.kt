// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第一百零六批 §2（原 108 活动面板）＋ 设计 `抱朴UI设计方案-2026-10-21.md`
// v1.5 §3.12「活动面板挂在当前轮尾部（轮内最后一个元素）」。
//
// 为什么要有它（设计 §3.12 三层分工）：活动面板＝**过程明细**（这一轮在干什么、干了什么）；
// 跑马灯＝环境状态；决策卡＝阻塞问题。三者同屏共存、互不抢占。
//
// 它同时接过了「收起详情」浮标的班：那颗浮标整块删除（用户点名），「入口不随内容滚走」
// 的保证改由轮尾这一行承接——但**不新造第二种浮层**、不新开 WebView、不遮输入框。
//
// 判据纪律：类型映射 / ≤20 条切分 / 目标短描述全是**本文件的纯函数**（无 Compose 依赖，
// JVM 直测）；数据只读现有 run_log 活动行（`RUN_LOG_STEPS_SELECT`，`tool != ''`）＋
// 该轮消息里的 tool part ＋ 引擎状态——**不新造第二套事件源、不改内核 SQL**（108 红线）。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.baopu.agent.RunState
import com.example.zhengdao.ui.theme.zhengdaoErrorText

// ── 尺寸与条数（唯一真源：门禁按常量读数） ─────────────────────────────────

/** 活动流最多摆几条（设计 §3.12「≤20 条」，更多折成「还有 N 条」）。 */
internal const val ACTIVITY_STREAM_MAX = 20

/** 收起行视觉高度（设计「视觉高 40dp」）。 */
internal const val ACTIVITY_HEAD_VISUAL_DP = 40

/** 收起行/条目行的命中高度（设计「热区 ≥48dp」「条目行 ≥48dp」）。 */
internal const val ACTIVITY_ROW_TOUCH_DP = 48

/** 目标短描述的字数上限（超了省略，一行一个意思）。 */
internal const val ACTIVITY_TARGET_CHARS = 40

/**
 * 耗时取不到时的占位（第一百三十一批 UI-D7）。
 *
 * 为什么不是「0s」：`run_log` 里 `latency_ms` 缺省就是 0，而 `formatDuration(0)` 会吐出
 * **「0s」——那是一个假读数**（看着像"这步瞬间完成"）。派工单 §C 判据 J9 点名禁止，
 * 取不到就明说不知道：`—`。
 */
internal const val ACTIVITY_LATENCY_UNKNOWN = "—"

// ── 纯数据 ────────────────────────────────────────────────────────────────

/**
 * 面板行类型（设计 §3.12「活动类型映射」表）：
 * 真工具名 ⇒ [TOOL]；`compression_*` ⇒ [COMPRESSION]；引擎在等确认 ⇒ [AWAIT_CONFIRM]；
 * `ok=0` ⇒ [FAILED]。**思考/完成不进流**（run_log 状态行 `tool=''` 不进 UI SELECT，
 * 由收起行的引擎状态与完成摘要承担）。
 */
internal enum class ActivityKind { TOOL, COMPRESSION, AWAIT_CONFIRM, FAILED }

/** 活动流的一行（纯数据，渲染层只翻译文案）。 */
internal data class ActivityItem(
    val kind: ActivityKind,
    val tool: String,
    /** 目标短描述（取自该轮同名 tool part 的摘要；没有 ⇒ 空串，只显工具名）。 */
    val target: String,
    /** 失败原因（引擎层已人话化；不展示堆栈）。 */
    val err: String,
    val latencyMs: Long,
)

/** 活动流切片结果：[shown] 是最多 [ACTIVITY_STREAM_MAX] 条（最新在上），[more] 是被折掉的条数。 */
internal data class ActivityStream(val shown: List<ActivityItem>, val more: Int)

// ── 纯判据（JVM 直测；门禁在 BaopuActivityPanelTest） ─────────────────────

/**
 * run_log 活动行 ⇒ 面板行（**纯函数**）。
 *
 * 顺序口径：`ok=0` 优先判失败——一条失败的压缩留痕也要先说失败（原因比归类重要）；
 * 其余按 `compression_` 前缀归类。
 * 负例：把 `ok` 判据挪到最后 ⇒ 失败行被画成「工具/压缩」⇒ 门禁红。
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
 * 口径（派工单 §C）：
 * - 数据只取 `run_log` 已有的 `latency_ms`（[BaopuStepRow.latencyMs]），**不新造计时源**；
 * - `<= 0` ⇒ 取不到 ⇒ [ACTIVITY_LATENCY_UNKNOWN]（`—`），**不许写 0s 假装知道**；
 * - `1ms..999ms` ⇒ 保留一位小数（`0.3s`）——亚秒步在 `formatDuration` 里会被截成 `0s`，
 *   那正是 J9 要堵的假值，所以这一档在这里单独处理；
 * - `>= 1s` ⇒ 复用既有 [formatDuration]（`3s` / `1m20s`），口径与轮摘要同一套。
 *
 * 负例：把 `latencyMs <= 0` 也走 formatDuration ⇒ 出现「0s」⇒ 门禁红；
 * 把亚秒直接交给 formatDuration ⇒ 300ms 显示「0s」⇒ 门禁红。
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
 * 它来自**引擎状态**，不是新事件源；这样「等确认」不必给 run_log 加 state 列
 * （108 红线：不改内核 SQL）。
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
 * 目标短描述（**纯函数**）：压成一行、去掉首尾空白、超 [max] 字省略。
 *
 * 取的是**该轮消息里同名 tool part 的摘要**（payload 已有 tool/step/result 的 UI 侧
 * 渲染「工具调用 · 名字 + 摘要」），不新读库、不解析参数 JSON——摘要里那层
 * 「结果：」前缀原样保留（它说的就是"这一步干了什么"）。
 */
internal fun shortTarget(text: String, max: Int = ACTIVITY_TARGET_CHARS): String {
    val oneLine = text.replace(Regex("\\s+"), " ").trim()
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

/**
 * 面板显不显示（**纯函数**）：
 * - 该轮**没有活动**（窗口切片空且没在等确认）⇒ 不出现（纯聊天轮不出，设计「显隐」行）；
 * - 轮**收起**时只给运行中的当前轮那一行（「正在：…」）——收起的完成轮已有摘要行，
 *   再来一行「本轮活动 · 干了 N 步」是重复；展开该轮即可回看活动流（W12c 的「可点开回看」）。
 *
 * 负例：去掉 `running && isCurrent` 前的轮收起判断 ⇒ 每个历史轮都多挂一行 ⇒ 门禁红。
 */
internal fun activityPanelVisible(
    hasActivity: Boolean,
    running: Boolean,
    isCurrent: Boolean,
    collapsed: Boolean,
): Boolean = hasActivity && ((running && isCurrent) || !collapsed)

// ── 渲染 ──────────────────────────────────────────────────────────────────

/**
 * 轮尾活动面板（设计 §3.12）：收起态一行「正在/本轮活动」；点整行展开该轮活动流。
 *
 * 位置由调用方保证（[BaopuMessageList] 的轮尾、轮内最后一个元素）——不弹浮层、
 * 不遮输入框、不抢决策卡槽、不新开 WebView。
 */
@Composable
internal fun BaopuActivityPanel(
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
    val hasActivity = rows.isNotEmpty() || !awaitConfirmTool.isNullOrBlank()
    var open by remember(round.userMessageId) { mutableStateOf(false) }
    if (!hasActivity) return
    // 面板自己的展开态只在轮也展开时有意义（收起轮只有一行，见 activityPanelVisible 的 KDoc）。

    val targets = remember(round.messages) { toolTargets(round.messages) }
    val stream = remember(rows, targets, awaitConfirmTool) {
        activityStream(rows, targets, awaitConfirmTool)
    }
    val fallback = (round.messages.lastOrNull()?.createdAt ?: round.startMs) - round.startMs
    val summary = remember(rows, fallback) { roundSummary(rows, fallback) }
    // 整行点击的读屏语义：收起时「展开活动明细」，展开时「收起活动明细」。
    val toggleDesc = stringResource(
        if (open) R.string.baopu_activity_toggle_close else R.string.baopu_activity_toggle_open,
    )

    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
        // 收起行（视觉 40dp / 命中 48dp）：点整行展开 ⇄ 收起。
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(10.dp),
            tonalElevation = 1.dp,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = ACTIVITY_ROW_TOUCH_DP.dp)
                .clickable { open = !open }
                .semantics { contentDescription = toggleDesc },
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = ACTIVITY_HEAD_VISUAL_DP.dp)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                        maxLines = 2,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Text(
                        text = summary.durationMs?.let {
                            stringResource(R.string.baopu_activity_summary, summary.steps, formatDuration(it))
                        } ?: stringResource(R.string.baopu_activity_summary_nodur, summary.steps),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        modifier = Modifier.weight(1f),
                    )
                    if (summary.fails > 0) {
                        Text(
                            text = stringResource(R.string.baopu_activity_fails, summary.fails),
                            style = MaterialTheme.typography.labelSmall,
                            color = zhengdaoErrorText(),
                        )
                    }
                }
                Text(
                    text = if (open) "▾" else "▸",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (open) {
            // 流展开后没有任何额外按钮：收回就是再点一次上面那行（设计「点击整行展开/收起」）。
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            ) {
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
            }
        }
    }
}

/** 活动流的一行：类型签＋工具名＋耗时（第一行）／目标＋状态（第二行，失败给可读原因）。 */
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
                // 131 批 UI-D7：耗时位**恒在**（取不到显示 —）。原来 `latencyMs > 0` 才画，
                // 于是 run_log 没记时长的行整块消失，真机上「每条都有耗时」读不到。
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
