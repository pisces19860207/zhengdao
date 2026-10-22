// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：用户 2026-10-18 逐字「这次学到和已压缩放到待命中和＋那一栏里，开一个滚动循环播放的小口子，
// 就像财经里面那种」「不滚动详情，只滚动标题这几个字，点到哪条就展开那条的详情」；
// 第一百三十八批（2026-10-22）按设计 §5「两态判定图」改成**互斥两态**：
//   ① 运行中（THINKING/ACTING/COMPRESSING/AWAIT_CONFIRM）＝锁定孔位，只换字，永不滚动；
//   ② 空闲（IDLE/DONE/ABORTED）＝逐条轮播，每条停 3 秒；展开任一条 ⇒ **持续暂停**。
// 判据全在 `BaopuTickerRules.kt`（纯函数），本文件只负责画。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.zhengdao.baopu.agent.RunState
import kotlinx.coroutines.delay

/**
 * 跑马灯：顶栏那一「位」的唯一内容（第一百三十八批起，状态行已从这里撤出）。
 *
 * 房型（《纸墨黛青》§6.1 记录条档 ＋ 派工单 §设计约束）：
 * - **恒定占位 40dp**：空态、空闲轮播态、运行锁定态三者同高，事件到来时顶栏不跳；
 * - **无左右边框、无底色、无圆角**，只有下边 1dp 发丝线（`outlineVariant` = `#DCD7C9`）；
 * - 正文字号 13sp（脚注档）；
 * - **只有「待你确认」带视觉区分**：浅琥珀底 ＋ 1dp 琥珀描边 ＋ 字重 500（靠对比，不靠换色相）；
 * - 详情仍是**浮层**（`DropdownMenu`），不占布局高度、不推正文（K2 判据延续）。
 */
@Composable
internal fun BaopuNoticeTicker(
    state: RunState,
    items: List<NoticeItem>,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val fontScale = LocalDensity.current.fontScale
    // 系统动画缩放读一次就够（改它要回系统设置，页内不会变）。默认值 1f＝动画开着。
    val animatorScale = remember(ctx) {
        android.provider.Settings.Global.getFloat(
            ctx.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
    }
    val reduceMotion = reduceMotionFrom(animatorScale)
    val mode = tickerModeOf(state)
    // 排序（判据 7）：待用户拍板 ＞ 待知晓 ＞ informational，同档保持原顺序。
    val ordered = remember(items) { sortedForTicker(items) }
    // 两个游标都是 Int ⇒ 用 mutableIntStateOf（lint AutoboxingStateCreation：别装箱）。
    var cursor by remember(items) { mutableIntStateOf(0) }
    var expandedIndex by remember(items) { mutableIntStateOf(-1) }
    val expanded = expandedIndex >= 0

    // 切进运行态：收起详情、游标归零（锁定孔位只换字，不许留旧条目的展开态）。
    LaunchedEffect(mode) {
        if (mode == TickerMode.LOCKED) {
            expandedIndex = -1
            cursor = 0
        }
    }
    // 条目池变化后把游标/展开项收紧到合法范围（否则点开的那条被移除 ⇒ 详情取不到）。
    LaunchedEffect(ordered.size) {
        if (cursor > ordered.lastIndex) cursor = 0
        if (expandedIndex > ordered.lastIndex) expandedIndex = -1
    }
    // 轮播（判据 5/6）：key 含 expanded ⇒ 一展开就取消协程（**持续暂停**），收起才重新计时。
    // 这不是"停一拍"：旧写法是 16ms 无休循环 + 循环体里判断 selected，展开期间协程仍每帧醒着，
    // 收起瞬间还会接着跳到下一条 —— 读屏用户点到的那条就被换掉了（无障碍硬错误）。
    LaunchedEffect(mode, ordered.size, expanded, reduceMotion) {
        if (!tickerShouldCycle(mode, ordered.size, expanded, reduceMotion)) return@LaunchedEffect
        while (true) {
            delay(TICKER_CYCLE_MS)
            cursor = nextTickerIndex(cursor, ordered.size, expanded)
        }
    }

    val entryIndex = if (mode == TickerMode.LOCKED) -1 else tickerEntryIndex(ordered.size, cursor, reduceMotion)
    val confirm = state == RunState.AWAIT_CONFIRM
    val hairline = MaterialTheme.colorScheme.outlineVariant
    // 读屏描述先求值：`semantics {}` 的 lambda 不是 @Composable，里面不能调 stringResource。
    val a11yDesc = stringResource(tickerA11yRes(mode, confirm))

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(tickerSlotDp(fontScale).dp)
            // 记录条档：只下边一条发丝线，左右无框、不填底、不画圆角。
            .drawBehind {
                val y = size.height - (1.dp.toPx() / 2f)
                drawLine(
                    color = hairline,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            // 「待你确认」唯一的视觉区分：同色系加深一档填充 ＋ 1dp 琥珀描边（无圆角）。
            .then(
                if (confirm) {
                    Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(
                            TICKER_CONFIRM_STROKE_DP.dp,
                            MaterialTheme.colorScheme.tertiary,
                            RectangleShape,
                        )
                } else {
                    Modifier
                },
            )
            .clickable(enabled = mode == TickerMode.IDLE && entryIndex >= 0) {
                expandedIndex = if (expandedIndex == entryIndex) -1 else entryIndex
            }
            .semantics { contentDescription = a11yDesc },
        contentAlignment = Alignment.CenterStart,
    ) {
        // 空态（判据 4）：标签是空串，但**孔位照旧 40dp** —— 不 return、不零高、不推挤顶栏。
        val label = when {
            mode == TickerMode.LOCKED -> stringResource(tickerLockedLabel(state))
            entryIndex >= 0 -> ordered[entryIndex].label
            else -> ""
        }
        if (label.isNotEmpty()) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = TICKER_TEXT_SP.sp),
                fontWeight = if (confirm) FontWeight.Medium else FontWeight.Normal,
                color = if (mode == TickerMode.LOCKED) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
        // 详情仍是浮层：不占布局高度、不推正文；点外面空白处即收回。
        DropdownMenu(
            expanded = expanded && entryIndex >= 0 && entryIndex < ordered.size,
            onDismissRequest = { expandedIndex = -1 },
        ) {
            Column(
                Modifier
                    .widthIn(max = 320.dp)
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                if (expandedIndex >= 0 && expandedIndex < ordered.size) {
                    val item = ordered[expandedIndex]
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = item.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}
