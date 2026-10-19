// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第九批 §4.3（BaopuMessageList：role 分组、文本 part、工具调用 part
// 可见（入参出参摘要）、压缩点可见可展开）+ 设计 §9 施工细节第 8 条。
// 十六批第一件（2026-10-18 · 步骤卡收纳）：按「轮」分组，已完成的历史轮自动收成
// 一行（N 步 · 用时 · 失败 M），点开才展开每一步明细；当前轮（在跑）与带「要人动手」
// 卡的轮永远展开。口径：用户「workbuddy 的工作计划还挺好看的，弹出来打个勾，然后就
// 自己收回去了」「手机上用的时候是默认关闭的」。
// 判据纪律：分组/收起/汇总/用时全部是**本文件里的纯函数**（无 Compose 依赖，
// J1–J7 JVM 直测）；Compose 层只做接线与渲染。
// 第三十批 A 段（2026-10-18 用户「传的图片排的好乱啊，并排不好吗？」）：图片区从
// "一张一行竖着堆"改成**并排网格**——边长与每行个数由 imageGridLayout/imageRows
// 两个纯函数算（G1/G3/G4/G5/G6 都钉在纯函数＋源码级判据上），渲染层只有 Row/Column。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.BaopuWorkspace

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.getSystemService
import com.example.zhengdao.R
import com.example.zhengdao.baopu.ArtifactChipPlan
import com.example.zhengdao.baopu.artifactChipPlan
import com.example.zhengdao.baopu.artifactFileName
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.zhengdao.ui.theme.zhengdaoErrorText

// ── 纯数据与纯判据（JVM 直测：J1–J7） ─────────────────────────────────────

/**
 * run_log 的一行「活动」数据（十六批第一件）。
 *
 * 由 [com.example.zhengdao.ui.baopu.BaopuChatController] 用
 * [com.example.zhengdao.baopu.BaopuSql.RUN_LOG_STEPS_SELECT] 那条只读 SELECT
 * 读出并透出；判据（窗口切片 / 步数 / 失败数 / 用时）全在本文件的纯函数里。
 */
internal data class BaopuStepRow(
    val tool: String,
    val ok: Boolean,
    val latencyMs: Long,
    val err: String,
    val createdAt: Long,
)

/**
 * 一轮 = 一条 user 消息到下一条 user 消息之前的所有消息（user 本条在内）。
 *
 * [endMs] 是下一轮 user 消息的落库时刻（run_log 窗口的右开边界）；
 * 最后一轮没有下一条 user 消息 ⇒ null（窗口开放）。
 */
internal data class BaopuRound(
    val userMessageId: Long,
    val startMs: Long,
    val endMs: Long?,
    val messages: List<BaopuUiMessage>,
    val isCurrent: Boolean,
) {
    /** 轮里的工具卡张数（0 = 纯聊天轮，没有可收的东西，不参与收纳）。 */
    val toolPartCount: Int get() = messages.sumOf { m -> m.parts.count { it.kind == "tool" } }
}

/**
 * J1：按 user 消息把消息流切成轮（**纯函数**；数据源仍是 message/part，
 * 不新增表、不改 schema——派工单 §2.1）。
 *
 * 防御：开头就是 assistant 消息（理论不该有）单独成轮，键取首条消息 id。
 */
internal fun groupRounds(messages: List<BaopuUiMessage>): List<BaopuRound> {
    if (messages.isEmpty()) return emptyList()

    // 先切段：每段记「开轮 user 消息 id / 起始时刻 / 段内消息」
    class Seg(val userId: Long, val startMs: Long, val msgs: MutableList<BaopuUiMessage>)
    val segs = mutableListOf<Seg>()
    var cur: Seg? = null
    for (m in messages) {
        if (m.role == "user") {
            cur?.let { segs += it }
            cur = Seg(m.id, m.createdAt, mutableListOf(m))
        } else {
            val c = cur ?: Seg(m.id, m.createdAt, mutableListOf()).also { cur = it }
            c.msgs += m
        }
    }
    cur?.let { segs += it }

    // 段 → 轮：窗口右边界 = 下一段的起始时刻；最后一段 = 当前轮（窗口开放）
    return segs.mapIndexed { i, s ->
        BaopuRound(
            userMessageId = s.userId,
            startMs = s.startMs,
            endMs = segs.getOrNull(i + 1)?.startMs,
            messages = s.msgs.toList(),
            isCurrent = i == segs.lastIndex,
        )
    }
}

/**
 * J2/J3/J7：一轮该不该收起（**纯函数**）。
 *
 * 三条口径（派工单 §2.2/§2.3/§2.4）：
 * 1. 带「要人动手」卡的轮（确认卡/问人卡/待过目候选卡）**强制展开**——收起来是事故；
 * 2. 正在跑 ⇒ 当前轮保持展开（正在干活的要看得见）；
 * 3. 其余一律默认收起（历史轮收；当前轮收工后也收——「一轮结束时自动收成一行」）。
 * 用户点开的轮（[userExpandedIds] 含其键）保持展开——展开状态只记在组合内
 * （remember），不落库（派工单 §2.3）。
 */
internal fun roundCollapsed(
    round: BaopuRound,
    running: Boolean,
    pinnedUserIds: Set<Long>,
    userExpandedIds: Set<Long>,
): Boolean = when {
    round.userMessageId in pinnedUserIds -> false
    running && round.isCurrent -> false
    else -> round.userMessageId !in userExpandedIds
}

/** 轮窗口切片（**纯函数**）：run_log 行按 [BaopuRound.startMs, endMs) 左闭右开取。 */
internal fun stepsInWindow(rows: List<BaopuStepRow>, fromMs: Long, toMsExcl: Long?): List<BaopuStepRow> =
    rows.filter { it.createdAt >= fromMs && (toMsExcl == null || it.createdAt < toMsExcl) }

/** 一轮的汇总（收起行文案的三个数）。[durationMs] = null 表示没有可靠用时数据。 */
internal data class RoundSummary(val steps: Int, val fails: Int, val durationMs: Long?)

/**
 * J4/J5/J6：轮汇总（**纯函数**）。
 *
 * - 步数 / 失败数只数**活动行**；压缩留痕行（tool 以 `compression_` 开头）不算
 *   干活步骤——压缩是引擎的内务，不是这轮任务干的事；
 * - 用时 = 活动行首末 created_at 之差（派工单 §2.2）；没有活动行 ⇒ 用「消息时间差」
 *   兜底（[fallbackMs]，调用方按轮内首末消息时刻算）；都没有 ⇒ null（**不显示用时，
 *   不显示 0s 假装知道**——J6）。
 */
internal fun roundSummary(rows: List<BaopuStepRow>, fallbackMs: Long? = null): RoundSummary {
    val activity = rows.filter { !it.tool.startsWith("compression_") }
    val duration = when {
        activity.isNotEmpty() -> activity.last().createdAt - activity.first().createdAt
        fallbackMs != null -> fallbackMs
        else -> null
    }
    return RoundSummary(steps = activity.size, fails = activity.count { !it.ok }, durationMs = duration)
}

/**
 * J5：用时口径（测试写死）：90_000 → "1m30s"；120_000 → "2m"；42_000 → "42s"。
 * 中英两套文案共用这个值（它不是 UI 文案，是数据格式化）。
 */
internal fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    if (totalSec < 60) return "${totalSec}s"
    val min = totalSec / 60
    val sec = totalSec % 60
    return if (sec == 0L) "${min}m" else "${min}m${sec}s"
}

// ── 列表渲染 ──────────────────────────────────────────────────────────────

/**
 * 消息列表。role 分组：user 右对齐浅色、assistant 左对齐 surface；
 * tool part 显示「工具调用 · 名字」＋ 摘要（点开看全文——围栏包裹的原文）。
 *
 * 十六批第一件：先按轮分组——
 * - 纯聊天轮（无工具卡）照旧全显，没有可收的东西；
 * - 有工具卡的轮：在跑的当前轮与被「要人动手」卡钉住的轮展开，其余收成一行摘要；
 * - 点摘要行展开该轮全部消息（工具卡就在其中——工具名/结果/错误原文都在卡上）。
 */
@Composable
internal fun BaopuMessageList(
    messages: List<BaopuUiMessage>,
    steps: List<BaopuStepRow> = emptyList(),
    running: Boolean = false,
    pinLastRound: Boolean = false,
    // 第十八批-B：滚动状态归 BaopuScreen（贴底跟随＋「回到最新」浮标在屏幕层
    // 接线，列表只管用它渲染）——调用方不传就自持（预览/单测场景）。
    listState: LazyListState = rememberLazyListState(),
    // 朗读（2026-10-18）：null ⇒ 不出「朗读」按钮（单测/预览场景不必造 TTS）。
    onSpeak: ((String) -> Unit)? = null,
    // 第四十批（用户 2026-10-19）：「编辑上一条」不占屏幕 ——
    // 「编辑上一条可以在对应那个用户发送的信息长按出现吗？」「就不这么显化了，有点挡手机的屏幕」
    // ⇒ 长按**那条用户消息**才出菜单；null 时不接这个手势（单测/预览场景）。
    onUserLongPress: ((BaopuUiMessage) -> Unit)? = null,
    // 第三十八批 B 段：本轮**写成功**的文本产物（相对路径，引擎那份只读视图透传下来）。
    // 空 ⇒ 整行不出（大多数纯聊天的轮本来就没产物）。onOpen 只在有片时才会被调。
    artifacts: List<String> = emptyList(),
    onOpenArtifact: (String) -> Unit = {},
    // 2026-10-18 用户：「这次学到和已压缩这类可以放到已完成下面滚动播放吗？不占用正文框，
    // 很影响手机的可视面积」⇒ 两条便签从固定头区搬进列表首项，随内容一起滚走。
    modifier: Modifier = Modifier,
) {
    if (messages.isEmpty()) {
        Column(modifier.fillMaxWidth().padding(24.dp)) {
            Text(
                text = stringResource(R.string.baopu_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val rounds = remember(messages) { groupRounds(messages) }
    // 「要人动手」的卡都挂在本会话最近的活动上 ⇒ 钉住最后一轮（哪个轮是最后，键取
    // 最后一条 user 消息 id）。卡本体渲染在消息流之外，这里保证它背后的明细也不收。
    val lastUserId = messages.lastOrNull { it.role == "user" }?.id
    val pinned = if (pinLastRound && lastUserId != null) setOf(lastUserId) else emptySet<Long>()
    // 2026-10-18 真机第二次反馈：「干了几步点开后没法收回」。真因不是没有收起入口 ——
    // 展开态的摘要行上下各有一条，但**一轮展开后有十几张工具卡、两条都滚出屏幕**，
    // 用户找不到。⇒ 展开状态提到列表层，屏幕上常驻一颗「收起详情」浮标（不随内容滚走）。
    var expandedRoundId by remember { mutableStateOf<Long?>(null) }
    // 产物行只摆前 8 片，剩下的折成「还有 N 个」（判据 B5；切法在纯函数里，JVM 直测）。
    val chipPlan = remember(artifacts) { artifactChipPlan(artifacts) }
    Box(modifier.fillMaxWidth()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            rounds.forEach { round ->
                if (round.toolPartCount == 0) {
                    items(round.messages, key = { it.id }) { msg ->
                        BaopuMessageRow(msg, onSpeak, onUserLongPress)
                    }
                } else {
                    item(key = round.userMessageId) {
                        BaopuRoundItem(
                            round = round,
                            steps = steps,
                            running = running,
                            pinned = pinned,
                            onSpeak = onSpeak,
                            userOpened = expandedRoundId == round.userMessageId,
                            onUserToggle = {
                                expandedRoundId =
                                    if (expandedRoundId == round.userMessageId) null
                                    else round.userMessageId
                            },
                        )
                    }
                }
            }
            // 第三十八批 B 段：本轮产物。挂在**所有轮之后**（= 最后一条回复的下方），
            // 跟着内容一起滚走——2026-10-18 用户嫌「这次学到／已压缩」占固定区那句
            // 「很影响手机的可视面积」的口径还在，新东西一律不进固定区。
            if (chipPlan.shown.isNotEmpty()) {
                item(key = "baopu-artifacts") {
                    BaopuArtifactRow(plan = chipPlan, onOpen = onOpenArtifact)
                }
            }
        }
        if (expandedRoundId != null) {
            // 上移 56dp 让开「⬇ 回到最新」浮标（它在 BottomEnd + 12dp）；两个都可能在。
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = CircleShape,
                tonalElevation = 3.dp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 56.dp)
                    .clickable { expandedRoundId = null },
            ) {
                Text(
                    text = stringResource(R.string.baopu_collapse_detail),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * 产物行（第三十八批 B 段）：一行小片，每片是**文件名的最后一段**，横向可滚。
 *
 * 只有片名没有路径全貌——顶部/查看器里才给相对路径（一行塞不下，用户 2026-10-18
 * 已经说过「很影响手机的可视面积」）。超过 8 个只列前 8 ＋「还有 N 个」（B5）。
 *
 * 点片 ⇒ 把**相对路径**交给调用方（`BaopuScreen` 写 `controller.viewingArtifact`），
 * 查看器不在本文件弹：30 批的门钉着「整文件只应有 1 处 Dialog(」，那是图片全屏层的
 * （`BaopuImageGridTest.G5_点击带的是自己那一格的索引_全屏层只有一份`）。
 */
@Composable
private fun BaopuArtifactRow(plan: ArtifactChipPlan, onOpen: (String) -> Unit) {
    val rowLabel = stringResource(R.string.baopu_artifact_row_label)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = rowLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        plan.shown.forEach { rel ->
            val name = artifactFileName(rel)
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 1.dp,
                modifier = Modifier
                    .clickable { onOpen(rel) }
                    .semantics { contentDescription = "$rowLabel $name" },
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
        if (plan.more > 0) {
            Text(
                text = stringResource(R.string.baopu_artifact_more, plan.more),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 一轮的完整渲染（收起 ⇄ 展开的状态只活在组合里：remember(轮键)，不落库）。 */
@Composable
private fun BaopuRoundItem(
    round: BaopuRound,
    steps: List<BaopuStepRow>,
    running: Boolean,
    pinned: Set<Long>,
    userOpened: Boolean,
    onUserToggle: () -> Unit,
    onSpeak: ((String) -> Unit)? = null,
    onUserLongPress: ((BaopuUiMessage) -> Unit)? = null,
) {
    var planOpened by remember(round.userMessageId) { mutableStateOf(false) }
    val collapsed = roundCollapsed(
        round = round,
        running = running,
        pinnedUserIds = pinned,
        userExpandedIds = if (userOpened) setOf(round.userMessageId) else emptySet(),
    )
    // 工作计划卡（第十七批 C 段）：与轮收起**各自独立**——轮收起也露那一行（J15），
    // 点计划卡只翻计划卡（J5）。计划取自轮里最后一条 plan（多次 update_plan 取最后）。
    val plan = round.planOrNull()
    val planVisible = shouldShowPlanCard(round, plan, collapsed)
    val planExpanded = planCardExpanded(
        round = round,
        running = running,
        userPlanExpandedIds = if (planOpened) setOf(round.userMessageId) else emptySet(),
    )
    Column(Modifier.fillMaxWidth()) {
        if (collapsed) {
            // 收起：user 消息（问题常显）＋ 摘要一行（可点开）＋ **计划卡收起态那一行**
            // （不许被轮收纳吃掉——J15）＋ 最终回答（照旧常显）
            round.messages.filter { it.role == "user" }.forEach { BaopuMessageRow(it, onUserLongPress = onUserLongPress) }
            BaopuRoundSummaryRow(round, steps, onUserToggle)
            if (planVisible) {
                BaopuPlanCard(
                    plan = plan!!,
                    expanded = planExpanded,
                    onToggle = { planOpened = !planOpened },
                )
            }
            round.finalAnswerMessage()?.let { BaopuMessageRow(it, onSpeak) }
        } else {
            // 展开：**先给一条可点的摘要行**（2026-10-18 真机：底那条在长内容里找不到，
            // 用户以为"收不回去"）——点顶部这一行即收回；底部再留一条，两头都能收。
            BaopuRoundSummaryRow(round, steps, onUserToggle)
            // user 消息在最前，计划卡紧随其后（＝该轮顶部、工具卡之上），
            // 其余消息照原样（工具卡＝每一步明细：工具名/结果/错误原文可点开）
            var planMounted = false
            round.messages.forEach { msg ->
                BaopuMessageRow(msg, onSpeak, onUserLongPress)
                if (!planMounted && msg.role == "user") {
                    planMounted = true
                    if (planVisible) {
                        BaopuPlanCard(
                            plan = plan!!,
                            expanded = planExpanded,
                            onToggle = { planOpened = !planOpened },
                        )
                    }
                }
            }
            // 防御：轮里没有 user 消息（assistant 开头，理论不该有）⇒ 计划卡挂最前
            if (!planMounted && planVisible) {
                BaopuPlanCard(
                    plan = plan!!,
                    expanded = planExpanded,
                    onToggle = { planOpened = !planOpened },
                )
            }
            // 2026-10-18 真机反馈：「工具调用我点了，展开后就收不回去了」——
            // 原因是收起态才渲染那行摘要，展开后就没有任何可点的地方。
            // 展开态末尾补一条同样的摘要行，点它即收回（文案/数字与收起态一致）。
            BaopuRoundSummaryRow(round, steps, onUserToggle)
        }
    }
}

/** 轮里要常显的「最终回答」：最后一条含非空文本 part 的 assistant 消息；没有 ⇒ null。 */
private fun BaopuRound.finalAnswerMessage(): BaopuUiMessage? =
    messages.lastOrNull { m ->
        m.role != "user" && m.parts.any { it.kind == "text" && it.text.isNotBlank() }
    }

/** 收起行的三个数（J4/J5/J6 的 UI 落点）：run_log 活动行切窗汇总，消息时间差兜底用时。 */
private fun roundSummaryOf(round: BaopuRound, steps: List<BaopuStepRow>): RoundSummary {
    val rows = stepsInWindow(steps, round.startMs, round.endMs)
    val fallback = (round.messages.lastOrNull()?.createdAt ?: round.startMs) - round.startMs
    return roundSummary(rows, fallback)
}

/**
 * 收起行：`干了 N 步 · 用时 X · 失败 M`（走 strings.xml，不拼中文）；
 * 没有可靠用时数据 ⇒ 用「无用时」变体（J6：不显示 0s 假装知道）。
 */
@Composable
private fun BaopuRoundSummaryRow(
    round: BaopuRound,
    steps: List<BaopuStepRow>,
    onToggle: () -> Unit,
) {
    val s = roundSummaryOf(round, steps)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onToggle),
    ) {
        Text(
            text = s.durationMs?.let {
                stringResource(R.string.baopu_round_summary, s.steps, formatDuration(it), s.fails)
            } ?: stringResource(R.string.baopu_round_summary_nodur, s.steps, s.fails),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun BaopuMessageRow(
    msg: BaopuUiMessage,
    onSpeak: ((String) -> Unit)? = null,
    onUserLongPress: ((BaopuUiMessage) -> Unit)? = null,
) {
    // 只有 plan part 的消息（第十七批 C 段：计划已解析进 msg.plan 由计划卡渲染）⇒
    // 不建空气泡（角色标签下一片空白比不渲染更怪）。
    if (msg.parts.isEmpty()) return
    val isUser = msg.role == "user"
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        // 2026-10-18 用户：「抱朴在左，用户在右才对啊」⇒ 用户气泡靠右、助手靠左；
        // 「长按都没有复制功能哦」⇒ 气泡正文套 SelectionContainer（长按可选、系统菜单可复制）。
        // 第四十批（2026-10-19）：用户气泡的**长按改成长按菜单**（编辑这条／复制）——
        // 那个手势原来被 SelectionContainer 占着，现在由菜单里「复制」接管；
        // 助手气泡不动（仍可长按选字），她改的是自己发出去的话。
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = if (isUser) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                tonalElevation = if (isUser) 0.dp else 1.dp,
                modifier = if (isUser && onUserLongPress != null) {
                    Modifier
                        .fillMaxWidth(0.85f)
                        // 长按与轻点都给同一个菜单：长按是用户点名的手势；轻点也接上，
                        // 一是不让气泡「点了没反应」（以前点它什么都不发生），
                        // 二是长按手势在个别输入法/手势导航下容易被系统吃掉。
                        .combinedClickable(
                            onClick = { onUserLongPress(msg) },
                            onLongClickLabel = stringResource(R.string.baopu_user_msg_longpress),
                            onLongClick = { onUserLongPress(msg) },
                        )
                } else {
                    Modifier.fillMaxWidth(if (isUser) 0.85f else 1f)
                },
            ) {
                Column(Modifier.padding(12.dp)) {
                    // 2026-10-18 用户：「那个『你』字也应该在右啊」⇒ 用户气泡里的角色标签右对齐。
                    Text(
                        text = stringResource(
                            if (isUser) R.string.baopu_role_user else R.string.baopu_role_assistant,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = if (isUser) androidx.compose.ui.text.style.TextAlign.End
                        else androidx.compose.ui.text.style.TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // 文字类 part 走 SelectionContainer（长按可选中复制）。
                    // 图片 part **移出**它：图片要的"长按＝复制路径"与"长按＝选字"
                    // 是同一个手势，套在 SelectionContainer 里必然被抢走。
                    val textParts = msg.parts.filter { it.kind != "image" }
                    val imageParts = msg.parts.filter { it.kind == "image" }
                    if (textParts.isNotEmpty()) {
                        val renderParts: @Composable () -> Unit = {
                            Column {
                                textParts.forEachIndexed { i, part ->
                                    when (part.kind) {
                                        "tool" -> BaopuToolPart(part, key = "${msg.id}-$i")
                                        "compressed" -> BaopuCompressedPart(part, key = "${msg.id}-$i")
                                        else -> BaopuTextPart(part, key = "${msg.id}-$i")
                                    }
                                }
                            }
                        }
                        // 用户气泡：长按归「长按菜单」（编辑/复制），不再套 SelectionContainer；
                        // 助手气泡照旧可长按选字复制。
                        if (isUser) {
                            renderParts()
                        } else {
                            androidx.compose.foundation.text.selection.SelectionContainer {
                                renderParts()
                            }
                        }
                    }
                    // 图片（第三十批 A 段）：**并排**——放得下就一排放满，放不下换行，
                    // 每行同样数量；点开＝该张、长按＝该张的相对路径。
                    // "只有图片、没有文字"的消息（用户只丢一张图不打字）也只有这一段在渲染。
                    if (imageParts.isNotEmpty()) {
                        BaopuImageBlock(imageParts, msgId = msg.id)
                    }
                    // 朗读（2026-10-18）：只用系统 TTS 读**助手的气泡正文**（工具卡/压缩卡不读）。
                    // 放在 SelectionContainer 之外——长按选字的菜单与朗读按钮各管各的。
                    if (!isUser && onSpeak != null) {
                        val speakable = msg.parts
                            .filter { it.kind == "text" }
                            .joinToString("\n") { it.text }
                            .trim()
                        if (speakable.isNotEmpty()) {
                            TextButton(
                                onClick = { onSpeak(speakable) },
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.padding(top = 2.dp).height(28.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.baopu_speak),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BaopuTextPart(part: BaopuUiPart, key: String) {
    if (part.text.isBlank()) return
    Text(
        text = part.text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/**
 * 压缩点 part：**可见可展开**（派工单 §4.3）。
 *
 * 数据来源说明：本刀（刀 1）引擎只落 `text` / `tool` 两种 part，压缩执行与
 * 「压缩标记 part」的落库归后续刀——所以真机上暂时**看不到**这张卡，不是缺陷、
 * 也不是没做：这是渲染口，等 `kind = "compressed"` 的 part 一落库就自动亮。
 * 留这个分支而不是留个死键：库里的 part.kind 是自由文本，未来任何一种新 kind
 * 都不该在列表层被静默吞掉（下面 else 分支同理兜底成文本）。
 */
@Composable
private fun BaopuCompressedPart(part: BaopuUiPart, key: String) {
    var expanded by remember(key) { mutableStateOf(false) }
    Column(Modifier.padding(top = 6.dp).clickable { expanded = !expanded }) {
        Text(
            text = stringResource(R.string.baopu_compressed_marker),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (expanded && part.text.isNotBlank()) {
            Text(
                text = part.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * 工具 part（＝十六批第一件展开后的「每一步明细」）：工具名 ＋ 结果摘要；
 * 点开看全文（围栏原文，含 offset/limit 提示）。摘要截 200 字
 * （派工单 §2.3「错误原文截断 200 字，可点开全文」；原 160 按本单上调）。
 */
@Composable
private fun BaopuToolPart(part: BaopuUiPart, key: String) {
    var expanded by remember(key) { mutableStateOf(false) }
    val failed = part.text.startsWith("失败") || part.text.startsWith("被拒")
    Column(Modifier.padding(top = 6.dp).clickable { expanded = !expanded }) {
        Text(
            text = stringResource(R.string.baopu_tool_label) + " · " + part.tool,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = if (failed) {
                zhengdaoErrorText() // P2-11：error 色 3.55:1 不达正文 AA 4.5:1
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
        Text(
            text = if (expanded) part.text else part.text.take(200) + if (part.text.length > 200) "…" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

// ── 图片区（第二十四批 B 段落地，第三十批 A 段改成并排）────────────────────

/** 缩略图边长（dp）：派工单指定 96dp（单图、以及并排的"装得下"档）。判据 G4。 */
private val IMAGE_THUMB_DP = 96.dp

/**
 * 格子间距（dp）——**唯一真源**（判据 G3）。渲染处一律写 `IMAGE_GAP_DP.dp`，
 * 不许再散落 `4.dp` 字面量（改间距只动这一行，行距与列距必然一致）。
 */
internal const val IMAGE_GAP_DP = 4

/** 图片区与上面那段文字（含附件 hint）之间的距离（派工单指定 6dp）。 */
internal const val IMAGE_TEXT_GAP_DP = 6

/**
 * 并排的两档边长：96（与 [IMAGE_THUMB_DP] 同一个数，判据 G4 钉着）与降级档 84。
 * 96 档排不满 3 列而 84 档排得下 ⇒ 退 84；**不许超过 96**（派工单「别超」）。
 */
internal const val IMAGE_SIDE_FULL_DP = 96
internal const val IMAGE_SIDE_SMALL_DP = 84

/** 极窄窗（分屏/折叠屏小窗）单列时的下限：再窄就看不出是图了。 */
internal const val IMAGE_SIDE_MIN_DP = 64

/**
 * 一格图片（第三十批 A 段）。
 *
 * `present` 是**落点判据**（G6）：文件不在 ⇒ 这一格渲染「图片找不到了」，
 * 位置跟第几张对应（不是整块塌成一个空白方块，也不是把缺的那张悄悄跳过）。
 */
internal data class ImageCell(val relPath: String, val present: Boolean)

/**
 * 文件在不在（B4 的判据本体；**唯一实现**，图片区与缩略图都走它）。
 *
 * `length() > 0` 是必要的：0 字节的同名文件在（拍照中断/复制失败），
 * 当"在"处理就永远转圈，当"不在"处理才会说出「图片找不到了」。
 */
internal fun imageFilePresent(file: File): Boolean = file.isFile && file.length() > 0

/**
 * 「哪张缺」按顺序算出来（**纯函数**，判据 G6）。
 *
 * 顺序必须保住：第 i 个入参对应第 i 格。缺图不能靠"少一项"表达——
 * 那样后面的图会集体往前挪一格，用户点第 2 张打开的是第 3 张。
 */
internal fun imageCells(relPaths: List<String>, rootDir: File): List<ImageCell> =
    relPaths.map { rel ->
        val f = File(rootDir, rel)
        ImageCell(relPath = rel, present = imageFilePresent(f))
    }

/**
 * 索引 → 该格（**纯函数**，判据 G5）。
 *
 * 越界、负数、null 一律给 null（**不许崩**）：全屏层拿 null 就不渲染 Dialog，
 * 而不是抓第一张或抛 IndexOutOfBounds（组合状态在消息重排后可能短暂错位）。
 */
internal fun imageCellAt(cells: List<ImageCell>, index: Int?): ImageCell? =
    index?.let { cells.getOrNull(it) }

/** 一排放几个格子（含间距）。装不下一个 ⇒ 0。 */
internal fun imageColumnsOf(sideDp: Int, availDp: Int): Int =
    if (availDp < sideDp) 0 else (availDp + IMAGE_GAP_DP) / (sideDp + IMAGE_GAP_DP)

/** 图片区几何：格子边长（dp）＋每行几个。 */
internal data class ImageGrid(val sideDp: Int, val perLine: Int)

/**
 * 多图排布（**纯函数**，判据 G1/A1/A4）。
 *
 * 取档顺序（派工单口径「96dp＋4dp 间距，手机上至少挤得下 3 张，不够缩到 84，别超」）：
 * 1. 单图恒 96（A4：不因为改版把单图缩小）；
 * 2. 96 档能排的列数 **≥** 84 档能排的列数时优先 96（同列数取大边长）；
 * 3. 只有当 84 档多出一列（＝多摆一张）才退到 84；
 * 4. 两档都排不下任何一格（极窄窗）⇒ 单列，边长收到可用宽，下限 [IMAGE_SIDE_MIN_DP]。
 *
 * `perLine` 再按张数收口（3 张不许排成每行 6 个——多出来的列数是浪费的换行机会）。
 */
internal fun imageGridLayout(count: Int, availDp: Int): ImageGrid {
    if (count <= 0) return ImageGrid(sideDp = IMAGE_SIDE_FULL_DP, perLine = 1)
    if (count == 1) return ImageGrid(sideDp = IMAGE_SIDE_FULL_DP, perLine = 1)
    val cols96 = imageColumnsOf(IMAGE_SIDE_FULL_DP, availDp)
    val cols84 = imageColumnsOf(IMAGE_SIDE_SMALL_DP, availDp)
    if (cols96 == 0 && cols84 == 0) {
        // 一格都放不下：宽度取可用宽（不超 96），列数 1 —— 宁可单列也不让图溢出气泡
        return ImageGrid(sideDp = availDp.coerceIn(IMAGE_SIDE_MIN_DP, IMAGE_SIDE_FULL_DP), perLine = 1)
    }
    val (side, cols) = if (cols84 > cols96) IMAGE_SIDE_SMALL_DP to cols84 else IMAGE_SIDE_FULL_DP to cols96
    return ImageGrid(sideDp = side, perLine = cols.coerceIn(1, count))
}

/**
 * 按行切片（**纯函数**，判据 A1「每行同样数量」）。
 *
 * 满行优先：前 n−1 行都排满 [perLine]，只有末行可能少几个；少的这几个**留在开头**
 * （行是左对齐的，末行不足不会把唯一那张甩到中间——居中才是派工单点名的反例）。
 */
internal fun imageRows(count: Int, perLine: Int): List<List<Int>> =
    (0 until count).chunked(perLine.coerceAtLeast(1)).map { it.toList() }

/**
 * 缩略图解码的目标像素（按 3x 屏保守估）。解码时按这个值**采样**：一张 4000px 的
 * 原图直接解要 60 MB 内存，而 96dp 缩略图在屏幕上根本用不到那个分辨率。
 */
private const val THUMB_TARGET_PX = 288

/** 全屏看图的目标像素（同样采样，避免大图 OOM）。 */
private const val FULL_TARGET_PX = 2048

/**
 * 图片区 —— 多图**并排**（第三十批 A 段）。
 *
 * ## 四条口径
 *
 * 1. **并排**：先用 [BoxWithConstraints] 量出气泡内容宽（气泡是 85% 宽、内含 12dp 边距，
 *    所以必须量、不能按屏宽硬算），再由 [imageGridLayout] 定边长与每行个数，
 *    最后 [imageRows] 切片渲染成"若干横排 Row"——放得下就一排放满，放不下换行；
 * 2. **每格同尺寸正方形**：[Modifier.aspectRatio] 1f ＋ `ContentScale.Crop`（判据 G2），
 *    竖图横图都裁成方格，不许按原始比例把一行顶得高低不齐；
 * 3. **点第 N 张＝打开第 N 张**（判据 G5）：全屏层归本区块唯一，靠 `openIndex`
 *    经 [imageCellAt] 取**该张**路径；长按复制的是**该张**相对路径；
 * 4. **缺图在原位说话**（判据 G6）：[imageCells] 按顺序算出哪格缺，缺的那格渲染
 *    一格宽的「图片找不到了」，位置跟张数一一对应。
 */
@Composable
private fun BaopuImageBlock(imageParts: List<BaopuUiPart>, msgId: Long) {
    val ctx = LocalContext.current
    val relPaths = remember(imageParts, msgId) { imageParts.map { it.text } }
    val cells = remember(relPaths) { imageCells(relPaths, BaopuWorkspace.hostDir(ctx)) }
    // 全屏打开的是第几格（null＝没开）。只有一份 Dialog ⇒ 必须靠索引取路径（G5）
    var openIndex by remember(msgId) { mutableStateOf<Int?>(null) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = IMAGE_TEXT_GAP_DP.dp),
    ) {
        // maxWidth 已是"气泡内容宽"（外层 Column 的 12dp 边距扣完了）
        val availDp = maxWidth.value.toInt()
        val grid = imageGridLayout(cells.size, availDp)
        // 单图显式用 IMAGE_THUMB_DP（判据 G4 的字面量落点），多图用算出来的档
        val side = if (cells.size == 1) IMAGE_THUMB_DP else grid.sideDp.dp
        Column(verticalArrangement = Arrangement.spacedBy(IMAGE_GAP_DP.dp)) {
            imageRows(cells.size, grid.perLine).forEachIndexed { r, rowIdx ->
                Row(horizontalArrangement = Arrangement.spacedBy(IMAGE_GAP_DP.dp)) {
                    rowIdx.forEach { i ->
                        BaopuImageCell(
                            cell = cells[i],
                            side = side,
                            key = "$msgId-img-$r-$i",
                            onOpen = { openIndex = i },
                        )
                    }
                }
            }
        }
    }

    imageCellAt(cells, openIndex)?.let { opened ->
        BaopuImageFullScreen(relPath = opened.relPath, onDismiss = { openIndex = null })
    }
}

/**
 * 一格图片（边长由图片区统一给 ⇒ 同行必然同尺寸）。
 *
 * 三条纪律没变（第二十四批 B 段）：只读工作区文件不复制、缺失要说、解码在 IO 线程采样。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BaopuImageCell(cell: ImageCell, side: Dp, key: String, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val relPath = cell.relPath
    // 工作区根 + 相对路径 = 真实文件。换工作区后老图找不到 ⇒ 走"缺失"分支，不崩。
    // 第三十一批口径（E-159）：抱朴的根从 `BaopuWorkspace` 取，不再蹭终端那套。
    val file = remember(relPath) { File(BaopuWorkspace.hostDir(ctx), relPath) }
    // 缺不缺由图片区一次算好传进来（G6：哪一格缺，就得在那一格说话）
    val exists = cell.present

    val thumb by produceState<Bitmap?>(initialValue = null, key) {
        value = if (exists) withContext(Dispatchers.IO) { decodeSampledImage(file, THUMB_TARGET_PX) } else null
    }
    val bmp = thumb

    if (bmp == null) {
        if (!exists) {
            // G6：缺图渲染**在这一格**——方格宽、可见文字、底色说得清"这里有一张没了的图"
            Box(
                modifier = Modifier
                    .size(side)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    // 读屏把这格念成带路径的完整一句话（可见文字太短，不足以定位是哪张）
                    .semantics { contentDescription = ctx.getString(R.string.baopu_image_missing, relPath) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.baopu_image_missing_short),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(IMAGE_GAP_DP.dp),
                )
            }
            return
        }
        // 解码中/解码失败：占位但不是"空白方块"——有底色、有格子尺寸，看得出还在处理
        Box(
            Modifier
                .size(side)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        return
    }

    Image(
        bitmap = bmp.asImageBitmap(),
        contentDescription = stringResource(R.string.baopu_image_open),
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(side)
            // G2：强制正方形（不跟随原图比例），Crop 把多出来的部分裁掉
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                // G5：点这一格＝打开**这一格**（索引由图片区拿着）
                onClick = onOpen,
                onLongClick = {
                    val cm = ctx.getSystemService<ClipboardManager>()
                    cm?.setPrimaryClip(ClipData.newPlainText("baopu-image-path", relPath))
                    Toast.makeText(
                        ctx,
                        ctx.getString(R.string.baopu_image_path_copied),
                        Toast.LENGTH_SHORT,
                    ).show()
                },
            ),
    )
}

/**
 * 全屏看**指定那一张**（图片区只有这一份 Dialog；由 openIndex 决定看谁）。
 *
 * 首帧是黑底：大图在 IO 线程采样解码（2048px），解完才画上。
 * 改版前是"先垫缩略图再换大图"，但那份缩略归各格子持有——为了判据 G5
 * （点第 N 张＝打开第 N 张，路径必须经索引→[imageCellAt] 这一条真源）把
 * Dialog 提到区块层，代价就是这一瞬的黑底。列进真机手感项。
 */
@Composable
private fun BaopuImageFullScreen(relPath: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val file = remember(relPath) { File(BaopuWorkspace.hostDir(ctx), relPath) }
    val big by produceState<Bitmap?>(initialValue = null, relPath) {
        value = withContext(Dispatchers.IO) { decodeSampledImage(file, FULL_TARGET_PX) }
    }
    Dialog(
        onDismissRequest = onDismiss,
        // 全屏：关掉 Dialog 的默认宽度上限（否则大图被挤成一个小窗）
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            big?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = stringResource(R.string.baopu_image_open),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * 按目标边长**采样**解码（纯 IO，调用方保证在 IO 线程）。
 *
 * 两遍解码是标准做法：第一遍只读尺寸（`inJustDecodeBounds`，不解像素），算出
 * `inSampleSize` 再真解。这样 4000px 的原图只解出 ~288px 的位图。
 *
 * 文件不在 / 不是图片 / 解码失败 ⇒ null（调用方转成"缺失"文案，不抛）。
 *
 * 第三十批 B 段改 `internal`：输入框的附件缩略图（56dp chip）**复用这一个实现**，
 * 不另写一份解码——两份采样逻辑迟早漂开。
 */
internal fun decodeSampledImage(file: File, targetPx: Int): Bitmap? = runCatching {
    if (!file.isFile || file.length() == 0L) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) {
        sample *= 2
    }
    BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )
}.getOrNull()
