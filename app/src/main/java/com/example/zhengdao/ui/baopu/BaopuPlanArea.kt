// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单第一百四十批 §范围（计划区聚合）＋ 11:50 设计约束（《纸墨黛青》§7.3/§13）
// ＋ 设计 `抱朴设置重构方案-2026-10-22.md` §5「计划区重构」。
//
// 与第一百零六批的差别（106 版是「按轮分组、全量列出」）：
// - **一个会话一个计数**：胶囊显示未完成计划数（「N 个进行中」），不再说「第 x/y 步 ·
//   当前步原文」——后者在 13sp 下装不进 120dp，且一条计划的一句话不等于全部计划；
// - **展开只列未完成的计划**：已完成的计划不占位（胶囊也不为它保留入口）；
// - **未完成计划超过 8 条**时先收起成「还有 N 个」，点一下看全；
// - 胶囊 28dp 视觉 / 48dp 热区 / ≤120dp 宽 / rPill；浮层 rSheet、限高 320dp。
//
// 判据纪律（与 106 同房型）：取数/计数/截断/展开态全是**本文件的纯函数**（无 Compose
// 依赖，JVM 直测）；Composable 只做渲染与点击接线。数据只读
// `controller.messages`（`part(kind='plan')` 已解析进 `BaopuUiMessage.plan`，J16），
// 不动 `PlanController` / `PlanRules` / `PlanStore`，也不新开查询（140 红线）。
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.baopu.PlanRules
import com.example.zhengdao.ui.theme.isZhengdaoDark
import com.example.zhengdao.ui.theme.rPill
import com.example.zhengdao.ui.theme.rSheet
import com.example.zhengdao.util.TimeFormats
import java.util.Calendar

// ── 尺寸常量（唯一真源：门禁按常量读数，改大改小都要过一遍判断） ─────────────

/** 胶囊视觉高度（11:50 约束：28dp；106 版的 32dp 已收）。 */
internal const val PLAN_CAPSULE_VISUAL_DP = 28

/** 胶囊命中盒（设计「触摸热区 ≥48dp」；视觉不放大）。 */
internal const val PLAN_CAPSULE_TOUCH_DP = 48

/** 胶囊最大宽度（11:50 约束「≤120dp」；现状 268dp 必须收）。 */
internal const val PLAN_CAPSULE_MAX_WIDTH_DP = 120

/** 面板最大宽度（沿用 106 版 min(屏宽−32dp, 340dp)）。 */
internal const val PLAN_PANEL_MAX_WIDTH_DP = 340

/** 面板左右合计让位（屏宽 360dp 基准 ⇒ 328dp）。 */
internal const val PLAN_PANEL_MARGIN_DP = 32

/** 面板高度上限（11:50 约束：320dp；106 版的「屏高 60%」已收）。 */
internal const val PLAN_PANEL_MAX_HEIGHT_DP = 320

/** 面板内计划行/「还有 N 个」行的最小高度（设计「每个计划行命中 ≥48dp」）。 */
internal const val PLAN_ROW_MIN_DP = 48

/** 展开列表先显示几条；超过这个数收起成「还有 N 个」（11:50 约束）。 */
internal const val PLAN_PANEL_VISIBLE_MAX = 8

/** 面板头与消息区顶端的距离：胶囊热区 48 ＋ 上边 8 ＋ 间隙 4。 */
private const val PLAN_PANEL_TOP_DP = 60

/** 胶囊离消息区顶端的距离（设计「顶栏下方约 8dp」）。 */
private const val PLAN_CAPSULE_TOP_DP = 8

// ── 纯数据 ────────────────────────────────────────────────────────────────

/**
 * 面板里的一条 = 一轮的最后一份计划（同轮多次 update_plan 取最后一条，106 版口径）。
 *
 * 只装**还没干完**的计划（[planAreaPlans] 过滤掉全 done 的）；[ageIndex] 0 = 最新，
 * [currentStepIndex] 1 起（无未完成项理论上不会出现——全 done 已被过滤）。
 */
internal data class PlanSummary(
    val roundKey: Long,
    val roundNumber: Int,
    val startMs: Long,
    val isCurrent: Boolean,
    val plan: BaopuUiPlan,
    val ageIndex: Int,
    val done: Int,
    val total: Int,
    /** 当前步序号（1 起，第一个 doing；没有 doing 取第一个非 done）。 */
    val currentStepIndex: Int?,
    /** 当前步短句（面板收起行不显示原文；留给无障碍与后续扩展）。 */
    val currentText: String,
    /** 已结束的轮但计划没打完勾（W11c；不替模型补勾）。 */
    val stale: Boolean,
)

/** 胶囊只说一个数：本会话未完成的计划数。null = 无计划（零占位）。 */
internal data class PlanCapsuleState(val activeCount: Int)

/** 展开列表的切片：先显示 [visible]，其余收在「还有 N 个」后面。 */
internal data class PlanPanelSlice(val visible: List<PlanSummary>, val hiddenCount: Int)

/** 组头三选一（本轮 / 上一轮 / 第 N 轮）。 */
internal enum class PlanGroupTitleKind { CURRENT, PREV, NTH }

internal data class PlanGroupTitle(val kind: PlanGroupTitleKind, val nth: Int)

/** 组头时间的三种形态（今天只给时刻、昨天带「昨天」、更早给日期）。 */
internal enum class PlanTimeKind { TODAY, YESTERDAY, DATE }

internal data class PlanTimeLabel(val kind: PlanTimeKind, val clock: String, val date: String)

/**
 * 计划区两处用色的唯一真源（纯数据，JVM 直测）：
 * - 浅色：底 `#E3EDE7` / 字 `#2C5F54`（6.11:1，11:50 约束）；
 * - 深色：同一套语言只换色（`#243A33` / `#B7D8CE`，7.94:1；当前步骤用青提亮 `#7FA89B`）。
 *
 * 为什么不走 `MaterialTheme.colorScheme`：全 App 换纸墨 Token 属 137 全局改版，
 * 本单只动计划区；在全局色板落地前，这里按设计稿给定的两个色值局部落地（见交付报告）。
 */
internal data class PlanPalette(val capsuleBg: Long, val capsuleFg: Long, val accent: Long)

internal fun planPalette(dark: Boolean): PlanPalette = if (dark) {
    PlanPalette(capsuleBg = 0xFF243A33, capsuleFg = 0xFFB7D8CE, accent = 0xFF7FA89B)
} else {
    PlanPalette(capsuleBg = 0xFFE3EDE7, capsuleFg = 0xFF2C5F54, accent = 0xFF2C5F54)
}

// ── 纯判据（JVM 直测；门禁在 BaopuPlanAreaTest） ─────────────────────────

/**
 * 某轮的「计划漏打勾」判据（W11c，106 版原样保留）：
 * - 还在跑的当前轮**不算**漏（在跑是正常的中间态）；
 * - 只要还有一条不是 `done` 且这一轮已经结束 ⇒ 算漏。
 */
internal fun planGroupStale(round: BaopuRound, running: Boolean, items: List<BaopuPlanItem>): Boolean =
    !(running && round.isCurrent) && items.any { it.status != PlanRules.STATUS_DONE }

/**
 * 面板取数（**纯函数**）：本会话所有轮的**最后一份计划**里，只留还没干完的；
 * **最新轮置顶、历史倒序**。
 *
 * 两条负例靶心：
 * - 已完成的计划仍占位 ⇒ 这里必须过滤掉（全 done ⇒ 不进列表）；
 * - 无计划的轮占组 ⇒ 这里没有计划就跳过。
 */
internal fun planAreaPlans(messages: List<BaopuUiMessage>, running: Boolean): List<PlanSummary> {
    val rounds = groupRounds(messages)
    class Planned(
        val round: BaopuRound,
        val index: Int,
        val plan: BaopuUiPlan,
        val done: Int,
    )

    val planned = rounds.withIndex().mapNotNull { (index, round) ->
        val plan = round.planOrNull() ?: return@mapNotNull null
        if (plan.items.isEmpty()) return@mapNotNull null
        val done = planDoneCount(plan.items)
        if (done == plan.items.size) return@mapNotNull null // 已完成 ⇒ 不占位、不进列表
        Planned(round, index, plan, done)
    }
    return planned.asReversed().mapIndexed { ageIndex, p ->
        val items = p.plan.items
        val doing = items.indexOfFirst { it.status == PlanRules.STATUS_DOING }
        val stepIndex = if (doing >= 0) doing else items.indexOfFirst { it.status != PlanRules.STATUS_DONE }
        PlanSummary(
            roundKey = p.round.userMessageId,
            roundNumber = p.index + 1,
            startMs = p.round.startMs,
            isCurrent = p.round.isCurrent,
            plan = p.plan,
            ageIndex = ageIndex,
            done = p.done,
            total = items.size,
            currentStepIndex = if (stepIndex >= 0) stepIndex + 1 else null,
            currentText = if (stepIndex >= 0) items[stepIndex].text else "",
            stale = planGroupStale(p.round, running, items),
        )
    }
}

/** 胶囊态（**纯函数**）：没有任何未完成计划 ⇒ null（零占位）。 */
internal fun planCapsuleState(messages: List<BaopuUiMessage>, running: Boolean): PlanCapsuleState? {
    val plans = planAreaPlans(messages, running)
    return if (plans.isEmpty()) null else PlanCapsuleState(plans.size)
}

/**
 * 计划行展开判据（J5 组级展开的 140 版）：
 * - 用户手动点过的计划 ⇒ **用户选择优先**（不落库，只活在组合里）；
 * - 没点过：运行中的当前轮的计划展开（打开面板就能看见每条 ✓/●/○）；其余收起。
 */
internal fun planSummaryExpanded(
    summary: PlanSummary,
    running: Boolean,
    userChoice: Map<Long, Boolean>,
): Boolean = userChoice[summary.roundKey] ?: (running && summary.isCurrent)

/**
 * 展开列表截断（11:50 约束「超过 8 条收起为『还有 N 个』」）。
 * [showAll] = 用户点过「还有 N 个」⇒ 一次看全。
 */
internal fun planPanelSlice(plans: List<PlanSummary>, showAll: Boolean): PlanPanelSlice =
    if (showAll || plans.size <= PLAN_PANEL_VISIBLE_MAX) {
        PlanPanelSlice(plans, 0)
    } else {
        PlanPanelSlice(plans.take(PLAN_PANEL_VISIBLE_MAX), plans.size - PLAN_PANEL_VISIBLE_MAX)
    }

/**
 * 组头文案类型：当前轮 ⇒ 本轮；最新有计划的轮不是当前轮 ⇒ 上一轮（当前轮还没报计划）；
 * 更早 ⇒ 第 N 轮（N = 全轮序号）。
 */
internal fun planGroupTitle(summary: PlanSummary): PlanGroupTitle = when {
    summary.isCurrent -> PlanGroupTitle(PlanGroupTitleKind.CURRENT, 0)
    summary.ageIndex == 0 -> PlanGroupTitle(PlanGroupTitleKind.PREV, 0)
    else -> PlanGroupTitle(PlanGroupTitleKind.NTH, summary.roundNumber)
}

/**
 * 组头时间（**数据格式化**，不是 UI 文案）：今天 ⇒ "10:24"、昨天 ⇒ "22:10"、更早 ⇒ "10-18"。
 * 时刻/日期都走 [TimeFormats]（全仓时间格式化的唯一真源）。
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
 * 无未完成计划 ⇒ **整个组件什么都不渲染**（零占位）；面板默认收起，运行中也不自动弹出
 * （设计「运行中面板不自动弹出，进度看胶囊那一行」）。
 */
@Composable
internal fun BaopuPlanArea(
    messages: List<BaopuUiMessage>,
    running: Boolean,
    modifier: Modifier = Modifier,
) {
    val plans = remember(messages, running) { planAreaPlans(messages, running) }
    val capsule = remember(messages, running) { planCapsuleState(messages, running) }
    if (capsule == null || plans.isEmpty()) return

    var open by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }
    // 用户手动展开/收起过的计划（键 = 轮键）；跑完自动收起不许盖掉用户刚做的选择。
    var userChoice by remember { mutableStateOf<Map<Long, Boolean>>(emptyMap()) }

    // 返回键收回（房型照 W9b；DropdownMenu 自带，这里是自己画的浮层，得自己接）。
    BackHandler(enabled = open) { open = false }
    val capsuleDesc = stringResource(R.string.baopu_plan_capsule_desc)
    val palette = planPalette(isZhengdaoDark())

    BoxWithConstraints(modifier.fillMaxSize()) {
        val panelWidth = (maxWidth - PLAN_PANEL_MARGIN_DP.dp)
            .coerceAtMost(PLAN_PANEL_MAX_WIDTH_DP.dp)
        // 限高 320dp；屏幕矮时给消息区留出顶栏与胶囊的位置（不压到输入框）。
        val panelMaxHeight = minOf(
            PLAN_PANEL_MAX_HEIGHT_DP.dp,
            (maxHeight - PLAN_PANEL_TOP_DP.dp).coerceAtLeast(120.dp),
        )
        val nowMs = remember(open) { System.currentTimeMillis() }
        val slice = remember(plans, showAll) { planPanelSlice(plans, showAll) }

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
                shape = RoundedCornerShape(rSheet),
                tonalElevation = 3.dp,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = PLAN_PANEL_TOP_DP.dp, end = 8.dp)
                    .width(panelWidth)
                    .heightIn(max = panelMaxHeight),
            ) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    // 面板头：「计划」＋「N 个进行中」
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
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.baopu_plan_capsule_active, capsule.activeCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
                        slice.visible.forEach { summary ->
                            PlanSummaryBlock(
                                summary = summary,
                                expanded = planSummaryExpanded(summary, running, userChoice),
                                nowMs = nowMs,
                                accent = Color(palette.accent),
                                onToggle = {
                                    userChoice = userChoice + (summary.roundKey to !planSummaryExpanded(summary, running, userChoice))
                                },
                            )
                        }
                        if (slice.hiddenCount > 0) {
                            // 「还有 N 个」：整行可点（≥48dp），点一次看全。
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = PLAN_ROW_MIN_DP.dp)
                                    .clickable { showAll = true }
                                    .padding(horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = pluralStringResource(R.plurals.baopu_plan_more, slice.hiddenCount, slice.hiddenCount),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        // 胶囊：视觉 28dp、命中盒 48dp（外层撑高）、宽 ≤120dp。
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
                color = Color(palette.capsuleBg),
                shape = RoundedCornerShape(rPill),
                modifier = Modifier
                    .height(PLAN_CAPSULE_VISUAL_DP.dp)
                    .widthIn(max = PLAN_CAPSULE_MAX_WIDTH_DP.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.baopu_plan_capsule_active, capsule.activeCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(palette.capsuleFg),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 一条计划的渲染：组头可点折叠；展开复用 [BaopuPlanCard]（当前步骤用黛青高亮）。 */
@Composable
private fun PlanSummaryBlock(
    summary: PlanSummary,
    expanded: Boolean,
    nowMs: Long,
    accent: Color,
    onToggle: () -> Unit,
) {
    val title = planGroupTitle(summary)
    val titleText = when (title.kind) {
        PlanGroupTitleKind.CURRENT -> stringResource(R.string.baopu_plan_group_current)
        PlanGroupTitleKind.PREV -> stringResource(R.string.baopu_plan_group_prev)
        PlanGroupTitleKind.NTH -> stringResource(R.string.baopu_plan_group_nth, title.nth)
    }
    val timeText = planTimeText(planTimeLabel(summary.startMs, nowMs))
    val progress = "${summary.done}/${summary.total}"
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (summary.stale) {
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
            // 106 版「BaopuPlanCard 组件不删——搬进面板当组渲染复用」的口径延续；
            // 140 只多传一个 highlightIndex（当前步骤用黛青，不靠整行加粗）。
            BaopuPlanCard(
                plan = summary.plan,
                expanded = true,
                onToggle = {},
                showHeader = false,
                rowMinHeightDp = PLAN_ROW_MIN_DP,
                highlightIndex = summary.currentStepIndex?.minus(1),
                accentColor = accent,
            )
        }
        if (summary.stale) {
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
