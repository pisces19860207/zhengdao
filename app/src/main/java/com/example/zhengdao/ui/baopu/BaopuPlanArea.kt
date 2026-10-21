// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第一百零六批 §1（原 106 计划区）＋ 设计 `抱朴UI设计方案-2026-10-21.md`
// v1.5 §3.11「计划区＝悬浮胶囊（右上·顶栏下方）＋ 浮层计划面板」。
//
// 为什么要搬家（设计 §3.11 三选一决策表）：计划原来内联在每条轮里（第十七批 C 段），
// 一轮十几张工具卡时它跟着滚走；改成「正文里一个字都不留，全部收进一枚胶囊」——
// 无计划零占位，有计划只占一枚小胶囊，点开才是浮层面板（房型照 W9b 跑马灯详情弹窗：
// 不推正文、有尺寸上限、内部可滚、点外面/返回键收回）。
//
// 判据纪律（与第二十二批同房型）：分组/胶囊取数/组级展开/漏打勾全在**本文件的纯函数**里
// （无 Compose 依赖，JVM 直测）；Composable 只做渲染与点击接线。数据只读
// `controller.messages`（`part(kind='plan')` 已解析进 `BaopuUiMessage.plan`，J16），
// 不动 `PlanController` / `PlanRules` / `PlanStore`，也不新开查询（派工单 106 红线）。
package com.example.zhengdao.ui.baopu

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.baopu.PlanRules
import com.example.zhengdao.util.TimeFormats
import java.util.Calendar

// ── 尺寸常量（唯一真源：门禁按常量读数，改大改小都要过一遍判断） ─────────────

/** 胶囊视觉高度（设计 §3.11 规格表「视觉高 32dp」）。 */
internal const val PLAN_CAPSULE_VISUAL_DP = 32

/** 胶囊命中盒（设计「触摸热区 ≥48dp」；房型照抽屉 ⊕：外层撑高、视觉不放大）。 */
internal const val PLAN_CAPSULE_TOUCH_DP = 48

/** 面板最大宽度（设计「min(屏宽−32dp, 340dp)」）。 */
internal const val PLAN_PANEL_MAX_WIDTH_DP = 340

/** 面板左右合计让位（屏宽 360dp 基准 ⇒ 328dp）。 */
internal const val PLAN_PANEL_MARGIN_DP = 32

/** 面板高度上限 = 屏高 × 0.6（设计「高 ≤ 屏高 60%」）。 */
internal const val PLAN_PANEL_HEIGHT_RATIO = 0.6f

/** 面板内组头/条目行的最小高度（设计「组头行与条目行 ≥48dp」）。 */
internal const val PLAN_ROW_MIN_DP = 48

/** 面板头与消息区顶端的距离：胶囊热区 48 ＋ 上边 8 ＋ 间隙 4。 */
private const val PLAN_PANEL_TOP_DP = 60

/** 胶囊离消息区顶端的距离（设计「顶栏下方约 8dp」）。 */
private const val PLAN_CAPSULE_TOP_DP = 8

// ── 纯数据 ────────────────────────────────────────────────────────────────

/**
 * 面板里的一组 = 一轮的计划（设计 §3.11「按轮列出本会话所有计划」）。
 *
 * [ageIndex] 0 = 最新有计划的轮（置顶）；[roundNumber] 是全轮序号（1 起，组头「第 N 轮」用）。
 * [stale] = 「这轮跑完了，但计划没打完勾」（W11c；不替模型补勾）。
 *
 * `internal`：只在 UI 层内流转，不进 `BaopuUiMessage` 那种公开形状。
 */
internal data class PlanGroup(
    val roundKey: Long,
    val roundNumber: Int,
    val startMs: Long,
    val isCurrent: Boolean,
    val plan: BaopuUiPlan,
    val ageIndex: Int,
    val stale: Boolean,
)

/** 胶囊一行要说的三个数（渲染层只负责按这个结果选文案）。 */
internal data class PlanCapsuleState(
    val roundKey: Long,
    val done: Int,
    val total: Int,
    /** 当前步序号（1 起）；null = 没有未完成项。 */
    val currentStepIndex: Int?,
    /** 当前步短句（超长由渲染层省略）。 */
    val currentText: String,
    val allDone: Boolean,
    /** 已结束的轮但计划没打完勾 ⇒ 胶囊不说「第 n 步」假装在跑（见 KDoc）。 */
    val stale: Boolean,
)

/** 组头三选一（本轮 / 上一轮 / 第 N 轮）。 */
internal enum class PlanGroupTitleKind { CURRENT, PREV, NTH }

internal data class PlanGroupTitle(val kind: PlanGroupTitleKind, val nth: Int)

/** 组头时间的三种形态（今天只给时刻、昨天带「昨天」、更早给日期）。 */
internal enum class PlanTimeKind { TODAY, YESTERDAY, DATE }

internal data class PlanTimeLabel(val kind: PlanTimeKind, val clock: String, val date: String)

// ── 纯判据（JVM 直测；门禁在 BaopuPlanAreaTest） ─────────────────────────

/**
 * 某轮的「计划漏打勾」判据（W11c）：
 * - 还在跑的当前轮**不算**漏（在跑是正常的中间态）；
 * - 只要还有一条不是 `done` 且这一轮已经结束 ⇒ 算漏。
 *
 * 负例：去掉 `!(running && round.isCurrent)` ⇒ 跑着的时候就挂「未更新」红章 ⇒ 本判据红。
 */
internal fun planGroupStale(round: BaopuRound, running: Boolean, items: List<BaopuPlanItem>): Boolean =
    !(running && round.isCurrent) && items.any { it.status != PlanRules.STATUS_DONE }

/**
 * 面板分组（**纯函数**）：按轮切、只留有计划的轮、**最新轮置顶、历史倒序**（设计 §3.11）。
 *
 * 同轮多次 `update_plan` 取最后一条 —— 走 [BaopuRound.planOrNull]（J15 原口径）。
 * 不新开查询、不读库：入参就是 `controller.messages`。
 */
internal fun planAreaGroups(messages: List<BaopuUiMessage>, running: Boolean): List<PlanGroup> {
    val rounds = groupRounds(messages)
    val planned = rounds.withIndex().filter { (_, r) -> r.planOrNull()?.items?.isNotEmpty() == true }
    val out = ArrayList<PlanGroup>(planned.size)
    planned.reversed().forEachIndexed { ageIndex, (index, round) ->
        val plan = round.planOrNull() ?: return@forEachIndexed
        out += PlanGroup(
            roundKey = round.userMessageId,
            roundNumber = index + 1,
            startMs = round.startMs,
            isCurrent = round.isCurrent,
            plan = plan,
            ageIndex = ageIndex,
            stale = planGroupStale(round, running, plan.items),
        )
    }
    return out
}

/**
 * 胶囊态（**纯函数**）：无计划 ⇒ null（**零占位**，派工单 §3 负例「胶囊无计划占位」的靶心）。
 *
 * 取数口径（设计 §3.11）：当前轮有计划 ⇒ 当前轮的计划；当前轮还没报计划 ⇒ 最后一个
 * 有计划的轮（`lastOrNull` 一条就同时覆盖这两种情形）。
 *
 * 当前步 = 第一个 `doing`；没有 doing ⇒ 第一个 `todo`（设计「胶囊文案」行）。
 * 已结束的轮还有未完成项 ⇒ [PlanCapsuleState.stale]，胶囊改说「未更新」而不假装在跑
 * （设计决策，见交付报告 §1.3）。
 */
internal fun planCapsuleState(messages: List<BaopuUiMessage>, running: Boolean): PlanCapsuleState? {
    val rounds = groupRounds(messages)
    val round = rounds.lastOrNull { r -> r.planOrNull()?.items?.isNotEmpty() == true } ?: return null
    val items = round.planOrNull()?.items ?: return null
    val done = planDoneCount(items)
    val doing = items.indexOfFirst { it.status == PlanRules.STATUS_DOING }
    val stepIndex = if (doing >= 0) doing else items.indexOfFirst { it.status != PlanRules.STATUS_DONE }
    return PlanCapsuleState(
        roundKey = round.userMessageId,
        done = done,
        total = items.size,
        currentStepIndex = if (stepIndex >= 0) stepIndex + 1 else null,
        currentText = if (stepIndex >= 0) items[stepIndex].text else "",
        allDone = done == items.size,
        stale = planGroupStale(round, running, items),
    )
}

/**
 * 组级展开判据（**J5 迁到组级**，设计 §3.11「展开/收起状态机」）：
 * - 用户手动点过的组 ⇒ **用户选择优先**（展开/收起都算，不落库，只活在组合里）；
 * - 没点过：运行中的当前组展开（打开面板就能看见每条 ✓/●/○）；跑完/历史组收起。
 *
 * 负例：把 `userChoice[...]` 那条挪到后面 ⇒ 跑完的组被「自动收起」盖掉用户刚点的展开 ⇒ 红。
 */
internal fun planGroupExpanded(group: PlanGroup, running: Boolean, userChoice: Map<Long, Boolean>): Boolean =
    userChoice[group.roundKey] ?: (running && group.isCurrent)

/**
 * 组头文案类型：当前轮 ⇒ 本轮；最新有计划的轮不是当前轮 ⇒ 上一轮（当前轮还没报计划）；
 * 更早 ⇒ 第 N 轮（N = 全轮序号）。
 */
internal fun planGroupTitle(group: PlanGroup): PlanGroupTitle = when {
    group.isCurrent -> PlanGroupTitle(PlanGroupTitleKind.CURRENT, 0)
    group.ageIndex == 0 -> PlanGroupTitle(PlanGroupTitleKind.PREV, 0)
    else -> PlanGroupTitle(PlanGroupTitleKind.NTH, group.roundNumber)
}

/**
 * 组头时间（**数据格式化**，不是 UI 文案）：今天 ⇒ "10:24"、昨天 ⇒ "22:10"、更早 ⇒ "10-18"。
 *
 * 时刻/日期都走 [TimeFormats]（全仓时间格式化的唯一真源，B-补-24 的门禁钉着主源码
 * 不许再出现 `SimpleDateFormat(`）；「同一天」按**年月日**判，不按 24 小时差值硬算。
 */
internal fun planTimeLabel(atMs: Long, nowMs: Long): PlanTimeLabel {
    val clock = TimeFormats.hourMinute(atMs)
    val date = TimeFormats.monthDay(atMs)
    val dayOf = { ms: Long ->
        Calendar.getInstance().apply { timeInMillis = ms }
            .let { it.get(Calendar.YEAR) * 10_000 + it.get(Calendar.MONTH + 1) * 100 + it.get(Calendar.DAY_OF_MONTH) }
    }
    val kind = when (dayOf(atMs)) {
        dayOf(nowMs) -> PlanTimeKind.TODAY
        dayOf(nowMs - 86_400_000L) -> PlanTimeKind.YESTERDAY
        else -> PlanTimeKind.DATE
    }
    return PlanTimeLabel(kind, clock, date)
}

// ── 渲染 ──────────────────────────────────────────────────────────────────

/**
 * 计划区：一枚悬浮胶囊（消息区右上、顶栏下方 8dp）＋ 点开的浮层面板。
 *
 * 无计划 ⇒ **整个组件什么都不渲染**（零占位）；面板默认收起，运行中也不自动弹出
 * （设计「运行中面板不自动弹出，进度看胶囊那一行」）。
 *
 * 浮层不推正文、不遮输入框：它挂在消息列表那个 Box 里（`BaopuScreen` 的 `weight(1f)`），
 * 物理上就到不了输入框；高度上限 60% 屏高，内部可滚。
 */
@Composable
internal fun BaopuPlanArea(
    messages: List<BaopuUiMessage>,
    running: Boolean,
    modifier: Modifier = Modifier,
) {
    val groups = remember(messages, running) { planAreaGroups(messages, running) }
    val capsule = remember(messages, running) { planCapsuleState(messages, running) }
    if (capsule == null || groups.isEmpty()) return

    var open by remember { mutableStateOf(false) }
    // 用户手动展开/收起过的组（键 = 轮键）；跑完自动收起不许盖掉用户刚做的选择。
    var userChoice by remember { mutableStateOf<Map<Long, Boolean>>(emptyMap()) }

    // 返回键收回（房型照 W9b；DropdownMenu 自带，这里是自己画的浮层，得自己接）。
    BackHandler(enabled = open) { open = false }
    val capsuleDesc = stringResource(R.string.baopu_plan_capsule_desc)

    BoxWithConstraints(modifier.fillMaxSize()) {
        val panelWidth = (maxWidth - PLAN_PANEL_MARGIN_DP.dp)
            .coerceAtMost(PLAN_PANEL_MAX_WIDTH_DP.dp)
        val panelMaxHeight = maxHeight * PLAN_PANEL_HEIGHT_RATIO
        val nowMs = remember(open) { System.currentTimeMillis() }

        if (open) {
            // 轻遮罩：点外面收回（不盖顶栏/输入框——本组件只在消息区内）。
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.28f))
                    .clickable { open = false },
            )
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 3.dp,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = PLAN_PANEL_TOP_DP.dp, end = 8.dp)
                    .width(panelWidth)
                    .heightIn(max = panelMaxHeight),
            ) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    // 面板头：「计划」＋「已完成 3/6」
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = PLAN_ROW_MIN_DP.dp)
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.baopu_plan_panel_title),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(
                                R.string.baopu_plan_panel_progress,
                                planDoneCount(groups.first().plan.items),
                                groups.first().plan.items.size,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
                        groups.forEach { group ->
                            val expanded = planGroupExpanded(group, running, userChoice)
                            PlanGroupBlock(
                                group = group,
                                expanded = expanded,
                                nowMs = nowMs,
                                onToggle = {
                                    userChoice = userChoice + (group.roundKey to !expanded)
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        // 胶囊：视觉 32dp、命中盒 48dp（外层撑高）；无 doing/todo 且已完成 ⇒ ok 色文案。
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = PLAN_CAPSULE_TOP_DP.dp, end = 12.dp)
                .defaultMinSize(minHeight = PLAN_CAPSULE_TOUCH_DP.dp)
                .clickable { open = true }
                .semantics { contentDescription = capsuleDesc },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                color = if (capsule.allDone) {
                    // 全完成：绿底（tertiaryContainer）——与「运行中」灰底一眼可辨。
                    MaterialTheme.colorScheme.tertiaryContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
                shape = RoundedCornerShape(PLAN_CAPSULE_VISUAL_DP.dp / 2),
                tonalElevation = 3.dp,
                modifier = Modifier.defaultMinSize(minHeight = PLAN_CAPSULE_VISUAL_DP.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = capsuleText(capsule),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (capsule.allDone) {
                            MaterialTheme.colorScheme.onTertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(PLAN_PANEL_MAX_WIDTH_DP.dp - 72.dp),
                    )
                }
            }
        }
    }
}

/** 胶囊文案三选一：全完成 / 漏打勾的结束轮 / 运行中（含未跑但计划还在）。 */
@Composable
private fun capsuleText(capsule: PlanCapsuleState): String = when {
    capsule.allDone -> stringResource(R.string.baopu_plan_capsule_done, capsule.done, capsule.total)
    capsule.stale -> stringResource(R.string.baopu_plan_capsule_stale, capsule.done, capsule.total)
    else -> stringResource(
        R.string.baopu_plan_capsule_step,
        capsule.currentStepIndex ?: 1,
        capsule.total,
        capsule.currentText,
    )
}

/** 一组（= 一轮）的渲染：组头可点折叠；展开态复用 [BaopuPlanCard] 的条目渲染。 */
@Composable
private fun PlanGroupBlock(
    group: PlanGroup,
    expanded: Boolean,
    nowMs: Long,
    onToggle: () -> Unit,
) {
    val title = planGroupTitle(group)
    val titleText = when (title.kind) {
        PlanGroupTitleKind.CURRENT -> stringResource(R.string.baopu_plan_group_current)
        PlanGroupTitleKind.PREV -> stringResource(R.string.baopu_plan_group_prev)
        PlanGroupTitleKind.NTH -> stringResource(R.string.baopu_plan_group_nth, title.nth)
    }
    val timeText = planTimeText(planTimeLabel(group.startMs, nowMs))
    val progress = "${planDoneCount(group.plan.items)}/${group.plan.items.size}"
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = PLAN_ROW_MIN_DP.dp)
                .clickable(onClick = onToggle)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (expanded) "▾" else "▸",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.baopu_plan_group_meta, titleText, timeText, progress),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (group.isCurrent) FontWeight.Medium else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (group.stale) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.baopu_plan_stale_chip),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
        if (expanded) {
            // 复用计划卡（设计「BaopuPlanCard 组件不删——搬进面板当组渲染复用」）：
            // 面板已经有组头，卡头不再画；条目行抬到 48dp 命中高度。
            BaopuPlanCard(
                plan = group.plan,
                expanded = true,
                onToggle = {},
                showHeader = false,
                rowMinHeightDp = PLAN_ROW_MIN_DP,
            )
        }
        if (group.stale) {
            Text(
                text = stringResource(R.string.baopu_plan_stale_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 18.dp, end = 12.dp, bottom = 6.dp),
            )
        }
    }
}

/** 组头时间文案（三种形态各一个 string 键；「昨天/今天」是文案不是格式）。 */
@Composable
private fun planTimeText(label: PlanTimeLabel): String = when (label.kind) {
    PlanTimeKind.TODAY -> stringResource(R.string.baopu_plan_time_today, label.clock)
    PlanTimeKind.YESTERDAY -> stringResource(R.string.baopu_plan_time_yesterday, label.clock)
    PlanTimeKind.DATE -> label.date
}
