// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：用户 2026-10-18 逐字「这次学到和已压缩放到待命中和＋那一栏里，开一个滚动循环播放的小口子，
// 就像财经里面那种」「不滚动详情，只滚动标题这几个字，点到哪条就展开那条的详情」；
// 第一百三十八批（2026-10-22）按设计 §5「两态判定图」改成**互斥两态**：
//   ① 运行中（THINKING/ACTING/COMPRESSING/AWAIT_CONFIRM）＝锁定孔位，只换字，永不滚动；
//   ② 空闲（IDLE/DONE/ABORTED）＝逐条轮播，每条停 3 秒；展开任一条 ⇒ **持续暂停**。
// 判据全在 `BaopuTickerRules.kt`（纯函数），本文件只负责画。
//
// 第四百三十四批（2026-11-04，派工单《顶栏对齐设计稿》§跑马灯视觉规范）：**房型从「记录条」升级为「朱胶囊」**。
//   · 整段包进一个容器：朱浅底 errorContainer ＋ 朱砂描边 error 1.5dp ＋ 圆角 rPill 10；
//   · 类目标（「这次学到」）由原来的小浅标签升级为**实心朱砂白字** 52×28 / 圆角 rBadge 6 / 11sp Medium；
//   · 滚动文字换成**正文**（真学到的那句话），色走墨一 onSurface（原线灰 onSurfaceVariant）；
//   · 与左边模型名那颗**同高（44dp）、同圆角（10dp）、都有容器**；没内容时容器整段不画（判据③），
//     但**空位照旧占高**——138 批「三态同高、事件到来顶栏不跳」这条一行没动；
//   · 唯一的边界位置＝四边描边，原来那条下缘发丝线撤掉（`BaopuContainers` 硬规则 1：边界位置四选一，不许叠加）。
//   派工单红线「仅在既有色板内做容器化，不新增任何颜色 token」：朱砂/朱浅都是 `ui/theme/Color.kt`
//   既有常量（`error` / `errorContainer` 槽位），这里一枚新色都没造。
//
// 434 判据⑤：本文件的每个 @Composable 都 ≤59 行（detekt LongMethod 60 阈值同一口径），
// 所以画容器／画条目标／画详情各拆成一小块，主函数只管两态机与游标。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.example.zhengdao.baopu.agent.RunState
import com.example.zhengdao.ui.theme.rBadge
import com.example.zhengdao.ui.theme.rPill
import kotlinx.coroutines.delay

/**
 * 跑马灯：顶栏那一「位」的唯一内容（第一百三十八批起，状态行已从这里撤出）。
 *
 * 两态的唯一开关是 `state`；本函数只管游标与节拍，画出来的东西在 [TickerPill]。
 * 空态（没学到、也没有别的事）＝**容器不画、孔位照占**：判据③要的是「整段不显示」，
 * 不是「顶栏缩一下」（138 批 K1「三态同高」延续）。
 */
@Composable
internal fun BaopuNoticeTicker(
    state: RunState,
    items: List<NoticeItem>,
    modifier: Modifier = Modifier,
) {
    val mode = tickerModeOf(state)
    // 排序（判据 7）：待用户拍板 ＞ 待知晓 ＞ informational，同档保持原顺序。
    val ordered = remember(items) { sortedForTicker(items) }
    // 两个游标都是 Int ⇒ 用 mutableIntStateOf（lint AutoboxingStateCreation：别装箱）。
    var cursor by remember(items) { mutableIntStateOf(0) }
    var expandedIndex by remember(items) { mutableIntStateOf(-1) }
    val expanded = expandedIndex >= 0
    val reduceMotion = rememberTickerReduceMotion()

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
    val entry = if (entryIndex in ordered.indices) ordered[entryIndex] else null
    // 详情只在「展开的就是正在显示的这一条」时打开（展开态由 [TickerPill] 那颗点击切换）。
    val detailIndex = if (expanded && entryIndex in ordered.indices) entryIndex else -1
    TickerPill(
        state = state,
        mode = mode,
        entry = entry,
        ordered = ordered,
        detailIndex = detailIndex,
        onToggle = { expandedIndex = if (expandedIndex == entryIndex) -1 else entryIndex },
        onDismiss = { expandedIndex = -1 },
        modifier = modifier,
    )
}

/** 系统「动画缩放＝0 ⇒ 不轮播」的读取位（读一次就够：改它要回系统设置，页内不会变）。 */
@Composable
private fun rememberTickerReduceMotion(): Boolean {
    val ctx = LocalContext.current
    val animatorScale = remember(ctx) {
        android.provider.Settings.Global.getFloat(
            ctx.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
    }
    return reduceMotionFrom(animatorScale)
}

/**
 * 朱胶囊本体：容器 ＋ 一行内容（锁定态＝状态字，空闲态＝类目标＋正文）。
 *
 * 孔位高＝max(44dp, 138 批那一行)：44 是 434 与模型名区对齐的容器高，
 * 里面那一行仍按 [tickerSlotDp] 走 ⇒ 2.0× 字体不撑高、3.0× 只许长高防裁字。
 */
@Composable
private fun TickerPill(
    state: RunState,
    mode: TickerMode,
    entry: NoticeItem?,
    ordered: List<NoticeItem>,
    detailIndex: Int,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier,
) {
    val confirm = state == RunState.AWAIT_CONFIRM
    val pill = marqueePillVisible(mode, ordered.isNotEmpty())
    // 读屏描述先求值：`semantics {}` 的 lambda 不是 @Composable，里面不能调 stringResource。
    val a11yDesc = stringResource(tickerA11yRes(mode, confirm))
    val lockedLabel = if (mode == TickerMode.LOCKED) stringResource(tickerLockedLabel(state)) else ""
    val body = entry?.let { inlineTickerText(it.body) }.orEmpty()
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .pillShellModifier(pill = pill, confirm = confirm)
            .clickable(enabled = entry != null, onClick = onToggle)
            .semantics { contentDescription = a11yDesc },
        contentAlignment = Alignment.CenterStart,
    ) {
        // 443：详情小下拉与模型区同款——宽度取跑马灯胶囊实测宽度，贴在它下沿、右边缘对齐
        val detailWidth = maxWidth
        val detailOffsetY = with(LocalDensity.current) { BAO_TOPBAR_PILL_HEIGHT_DP.dp.roundToPx() }
        if (lockedLabel.isNotEmpty() || body.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(tickerSlotDp(LocalDensity.current.fontScale).dp)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (entry != null) {
                    TickerBadge(text = entry.label)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = if (entry != null) body else lockedLabel,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = TICKER_TEXT_SP.sp),
                    fontWeight = if (confirm) FontWeight.Medium else FontWeight.Normal,
                    // 墨一（434）：朱浅底上比原线灰多一档余量；锁定态本来就是主角，同色。
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    // 443/444：正文独占剩余宽度（fill=true），标签固定宽 ⇒ 两者不许互挤。
                    modifier = Modifier.weight(1f),
                )
            }
        }
        TickerDetail(
            ordered = ordered,
            expandedIndex = detailIndex,
            onDismiss = onDismiss,
            width = detailWidth,
            offsetY = detailOffsetY,
        )
    }
}

/** 容器的三层修饰：44dp 同高 ＋（有内容才）朱浅底/rPill 10/朱砂 1.5dp 描边 ＋（待确认才）琥珀强调。 */
@Composable
private fun Modifier.pillShellModifier(pill: Boolean, confirm: Boolean): Modifier {
    val shell = this.heightIn(min = BAO_TOPBAR_PILL_HEIGHT_DP.dp)
    val zhu = if (!pill) {
        shell
    } else {
        shell
            .clip(RoundedCornerShape(rPill))
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(rPill))
            .border(
                BAO_TOPBAR_PILL_STROKE_DP.dp,
                MaterialTheme.colorScheme.error,
                RoundedCornerShape(rPill),
            )
    }
    // 「待你确认」在朱胶囊之上再加它自己那一档强调（浅底加深一档 ＋ 1dp 琥珀描边）。
    // 描边跟着胶囊走 rPill：第四百三十四批之前这里是 RectangleShape——那会儿跑马灯是**记录条档**
    // （无底无圆角），方框对；现在是朱胶囊，方框压在圆角上会画出四个尖角。
    return if (!confirm) {
        zhu
    } else {
        zhu
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(TICKER_CONFIRM_STROKE_DP.dp, MaterialTheme.colorScheme.tertiary, RoundedCornerShape(rPill))
    }
}

/**
 * 类目标那颗（434：实心朱砂白字 52×28、圆角 rBadge、11sp Medium）。
 *
 * 443/444：**标签固定宽**（`width(TICKER_BADGE_WIDTH_DP)`），正文 `weight(1f)` 独占剩余——
 * 两者不许互挤。四字类目（这次学到／本轮用量）11sp≈44dp ＋ 左右 4dp 内距正好落在 52dp 内；
 * 443 真机复验把固定宽从 52 提到 60：四字类目实测在 52 下会被截成「这次学…」。
 * 万一更长就单行省略，绝不反过来挤正文。
 */
@Composable
private fun TickerBadge(text: String) {
    Box(
        modifier = Modifier
            .height(TICKER_BADGE_HEIGHT_DP.dp)
            .width(TICKER_BADGE_WIDTH_DP.dp)
            .clip(RoundedCornerShape(rBadge))
            .background(MaterialTheme.colorScheme.error, RoundedCornerShape(rBadge))
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = TICKER_BADGE_TEXT_SP.sp),
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onError,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 443：跑马灯详情**与模型区小下拉同款**——贴跑马灯下沿、右边缘对齐、等宽、rPill 圆角
 * （不要方方正正），surface 底 + outlineVariant 描边；仍是浮层，不推正文（K2 判据不变）。
 */
@Composable
private fun TickerDetail(
    ordered: List<NoticeItem>,
    expandedIndex: Int,
    onDismiss: () -> Unit,
    width: Dp,
    offsetY: Int,
) {
    if (expandedIndex !in ordered.indices) return
    val item = ordered[expandedIndex]
    Popup(alignment = Alignment.TopEnd, offset = IntOffset(0, offsetY), onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(width)
                .clip(RoundedCornerShape(rPill))
                .background(MaterialTheme.colorScheme.surface)
                .border(BAO_TOPBAR_PILL_STROKE_DP.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(rPill))
                .heightIn(max = 280.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
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

