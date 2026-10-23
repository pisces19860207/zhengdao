// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百三十八批（2026-10-22）：跑马灯**两态**的唯一判据源（设计 §5「两态判定图」）。
//
// 为什么要有这个文件：改前跑马灯只会「连续滚标题」，而会话头另有一套状态人话
// （`stateLabel(RunState)` 在顶栏第二处 `Text` 显示）——同一时刻两个地方说两种话。
// 本批把状态收敛成**一个出口**：
//
//   RunState（唯一真源）
//     ├ THINKING / ACTING / COMPRESSING / AWAIT_CONFIRM ─► ① LOCKED（锁定显示，永不滚动）
//     └ IDLE / DONE / ABORTED ──────────────────────────► ② IDLE（逐条轮播，每条 3 秒）
//
// 三条铁律（纯函数表达，JVM 直测，不依赖 Compose/Android）：
//   1. 两态互斥且穷尽（任何 RunState 必落其一）；
//   2. ① 的文案池与 ② 的条目池**零交集**（杜绝两处说两种话）；
//   3. 展开态为真 ⇒ 轮播**持续暂停**（不是"停一拍"）——读屏用户点的条目若在 3 秒后被换掉，
//      就是无障碍硬错误。
//
// 纪律：本文件没有状态、没有 UI、没有 Android 类型（除 R 常量），只放判据与常量。
// 容器归属见 `BaopuContainers.kt`（跑马灯属「记录条」档：只下边 1dp 发丝线）。

package com.example.zhengdao.ui.baopu

import com.example.zhengdao.R
import com.example.zhengdao.baopu.agent.RunState

/** 跑马灯两态（互斥、穷尽）。 */
internal enum class TickerMode {
    /** ① 运行中：锁定孔位，只换字，永不滚动。 */
    LOCKED,

    /** ② 空闲：逐条轮播（每条 3 秒），展开即持续暂停。 */
    IDLE,
}

/**
 * 态判定（判据 1/2）：引擎在不在干活，是两态唯一的切换开关。
 *
 * 注意 `AWAIT_CONFIRM` 属**运行中**：等用户拍板时模型这一轮还没结束，轮播起来会把
 * 「待你确认」滚走 —— 那是最需要被看见的一条。
 */
internal fun tickerModeOf(state: RunState): TickerMode = when (state) {
    RunState.THINKING,
    RunState.ACTING,
    RunState.COMPRESSING,
    RunState.AWAIT_CONFIRM,
    -> TickerMode.LOCKED

    RunState.IDLE,
    RunState.DONE,
    RunState.ABORTED,
    -> TickerMode.IDLE
}

/** ① 的运行态集合（判据 2 的输入；也是「两池零交集」的一半）。 */
internal val TICKER_LOCKED_STATES: Set<RunState> = setOf(
    RunState.THINKING,
    RunState.ACTING,
    RunState.COMPRESSING,
    RunState.AWAIT_CONFIRM,
)

/** ② 的空闲态集合（另一半）。 */
internal val TICKER_IDLE_STATES: Set<RunState> = setOf(
    RunState.IDLE,
    RunState.DONE,
    RunState.ABORTED,
)

/**
 * ① 锁定文案（判据 3）：**复用 [stateLabel]**，不再另写一套。
 *
 * 这就是「状态单一出口」的代码形态 —— 文案只有一份，跑马灯只是它唯一的显示位置。
 */
internal fun tickerLockedLabel(state: RunState): Int = stateLabel(state)

/**
 * ① 的文案资源集合（判据 9 的一半）。四个运行态各自一条，不重复。
 */
internal val TICKER_LOCKED_LABELS: Set<Int> = TICKER_LOCKED_STATES.map { tickerLockedLabel(it) }.toSet()

/**
 * ② 的条目文案资源集合（判据 9 的另一半）。
 *
 * 条目按类别分三档（排序用），这里列的是**会出现在轮播里的所有标题**：
 * 当前型号 / 这次学到 / 已压缩 / 待办（临时文件剩余）/ 玄览在干什么 / 本轮用量。
 * `TICKER_LOCKED_LABELS ∩ TICKER_IDLE_LABELS` 必须为空 —— 见 `BaopuTickerRulesTest`。
 */
internal val TICKER_IDLE_LABELS: Set<Int> = setOf(
    R.string.baopu_ticker_model_label,
    R.string.baopu_introspection_label,
    R.string.baopu_notice_compressed_label,
    R.string.baopu_scratch_left_label,
    R.string.baopu_browser_notice_label,
    R.string.baopu_usage_label,
)

/**
 * ② 条目的紧急度（判据 7 的排序权重）。
 *
 * - [NEEDS_YOU] 待用户拍板（临时文件没清、需要她决定的事）
 * - [NEEDS_KNOWING] 待知晓（这次学到、已压缩）
 * - [INFO] informational（当前型号、玄览在干什么、本轮用量）
 */
internal enum class NoticeKind {
    NEEDS_YOU,
    NEEDS_KNOWING,
    INFO,
}

/** 排序权重：数值小的排前面（待用户拍板 ＞ 待知晓 ＞ informational）。 */
internal fun noticeRank(kind: NoticeKind): Int = when (kind) {
    NoticeKind.NEEDS_YOU -> 0
    NoticeKind.NEEDS_KNOWING -> 1
    NoticeKind.INFO -> 2
}

/** 跑马灯里的一条：标题（短）× 详情（点标题才看）× 紧急度（决定轮播顺序）。 */
internal data class NoticeItem(
    val label: String,
    val body: String,
    val kind: NoticeKind = NoticeKind.INFO,
)

/**
 * 轮播顺序（判据 7）：按紧急度分档，**同档保持原顺序**（`sortedBy` 是稳定排序 ——
 * 「这次学到」和「已压缩」谁先来谁先播，不因为排序被打乱）。
 */
internal fun sortedForTicker(items: List<NoticeItem>): List<NoticeItem> =
    items.sortedBy { noticeRank(it.kind) }

/**
 * 当前该显示哪一条（判据 5）：减少动画时固定最新一条；否则取游标（越界收紧）。
 * 空列表返回 -1（调用方据此渲染**空白占位**，不是 return 掉整个组件 —— 判据 4）。
 */
internal fun tickerEntryIndex(size: Int, cursor: Int, reduceMotion: Boolean): Int = when {
    size <= 0 -> -1
    reduceMotion -> size - 1
    else -> cursor.coerceIn(0, size - 1)
}

/**
 * 读屏描述（走 strings.xml，**英文环境不会念中文**）。
 *
 * 为什么不是把 label 直接当描述：锁定态要让人知道「这是当前状态、不会换」，
 * 空闲态要让人知道「这条可以点开」。纯函数化便于 JVM 断言（见 `BaopuTickerRulesTest`）。
 */
internal fun tickerA11yRes(mode: TickerMode, confirm: Boolean): Int = when {
    mode == TickerMode.LOCKED && confirm -> R.string.baopu_ticker_a11y_confirm
    mode == TickerMode.LOCKED -> R.string.baopu_ticker_a11y_locked
    else -> R.string.baopu_ticker_a11y_idle
}

/** 孔位高度（§6.1 记录条之外的**专用**占位）：40dp，空态与运行态同高。 */internal const val TICKER_SLOT_DP = 40

/** 正文字号：13sp（《纸墨黛青》§3 脚注档，不许 10sp 也不许 15sp）。 */
internal const val TICKER_TEXT_SP = 13

/** 13sp 的行高（`ui/theme/Type.kt`：bodySmall 13sp / 18sp 行高）。 */
internal const val TICKER_LINE_DP = 18

/** 每条停留 3 秒（§5：业界轮播 3–5 秒取中下值，短文案 3 秒够读完）。 */
internal const val TICKER_CYCLE_MS = 3_000L

/** 「待你确认」的琥珀描边宽度（§5：同色系加深一档 ＋ 加粗描边 ＋ 字重 500）。 */
internal const val TICKER_CONFIRM_STROKE_DP = 1

/**
 * 孔位高度 = max(40dp, 一行行高 × fontScale)。
 *
 * **与态无关**是刻意的：两态同高（判据 4），切换只换字、顶栏不跳。
 * 大字体下只保证"不被裁"，不改 40dp 的定稿值（2.0× 时一行 36dp 仍在 40 里）。
 */
internal fun tickerSlotDp(fontScale: Float): Float {
    val line = TICKER_LINE_DP * fontScale
    return if (line > TICKER_SLOT_DP) line else TICKER_SLOT_DP.toFloat()
}

/**
 * 下一条的索引（判据 5/6）。
 *
 * - `expanded == true` ⇒ **原地不动**（持续暂停；不是"这一拍不换、下一拍接着换"）；
 * - 条目 ≤1 ⇒ 恒 0（没有轮播可言，但孔位仍占着）；
 * - 否则环形前进一格。
 */
internal fun nextTickerIndex(current: Int, size: Int, expanded: Boolean): Int {
    if (size <= 1) return 0
    val safe = current.coerceIn(0, size - 1)
    if (expanded) return safe
    return (safe + 1) % size
}

/**
 * 轮播是否该跑（判据 5/6 的前置）：锁动态、展开态、减少动画、条目不足都**不跑**。
 *
 * 展开态在这里被否掉，才是「持续暂停」——`LaunchedEffect` 的 key 含 `expanded`，
 * 展开即取消协程、收起才重新计时。
 */
internal fun tickerShouldCycle(
    mode: TickerMode,
    size: Int,
    expanded: Boolean,
    reduceMotion: Boolean,
): Boolean = mode == TickerMode.IDLE && !expanded && !reduceMotion && size > 1

/**
 * 系统「动画时长缩放」＝0 ⇒ 用户关了动画（无障碍/省电设置，**不是** App 的开关）。
 * 此时轮到降级为「只显最新一条、不轮播」（v1.4 §3.9 末条；不新增设置项）。
 */
internal fun reduceMotionFrom(animatorScale: Float): Boolean = animatorScale == 0f
