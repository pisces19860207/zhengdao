// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百三十九批（2026-11-09）· 治理中心第 4 块「今天干过什么」的**判据层**（纯函数，零 Android、零 IO）。
//
// 用户定调三条，本层逐条落地：
//   1. **只收已执行且有结果的**：计划未执行 / 被压 / 被拒 **一律不上屏**。
//      这不是"渲染时躲一下"，而是闭集闸——认不出的线值也一律丢（fail-closed：
//      宁可少一条，也不许把"没动过手"写成"干过");
//   2. **四列固定**：时间 · 谁 · 做了什么（一句话）· 结果。四列都在这里拼成"可以画的字"，
//      屏幕层只许照着画（判据⑥：页面不自己算）；
//   3. **时间倒序**：新的在上（同一毫秒按账本给的次序稳定排列）。
//
// 为什么判据要单独一层，而不是写在 `@Composable` 里：Compose 树在 JVM 直测不了
// （同 322 的取舍），而"闭集过滤 / 四列 / 倒序"这三条都能在纯函数上逐条断言。
//
// ⚠️ 本层**不统计**：没有"今天共 N 条"、没有成功率、没有百分比。看板只列发生过的事，
// 一旦这里出现派生数，它就和 527 的读数出口变成了两套口径（322 判据②的同款理由）。
//
// 数据从哪来：见 [ActivityBoardSource]（run_log／自决账／自动维护账三本账的只读口）。
// 本层只认识"归一后的候选行"，不认识任何一本账。
package com.example.zhengdao.ui.baopu

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 这一行是谁干的（闭集四值；文案查资源，本层不碰 `R.string`——JVM 单测里 R 是 0）。 */
internal enum class ActivityWho {
    /** 模型（run_log 里每一次动工具都是模型发起的）。 */
    MODEL,

    /** 调解器（自决账：判了"不打扰"并已按默认走）。 */
    MEDIATOR,

    /** 御医（自动维护账：修复记录）。 */
    REPAIRER,

    /** 进化器（自动维护账：进化提案）。 */
    EVOLVER,
}

/** 结果（闭集四值）。 */
internal enum class ActivityResult {
    /** 成功：账上写着做成了。 */
    DONE,

    /** 失败：账上写着做砸了（有结果，所以上屏——只看成绩单不看错题就是报喜不报忧）。 */
    FAILED,

    /** 跳过：判了"不打扰"并按默认走完，是有结论的一次执行。 */
    SKIPPED,

    /** 已回滚：验不过，账上已退回上一版。 */
    ROLLED_BACK,
}

/**
 * 一条**候选**（各本账的原始行 → 归一后的形状）。
 *
 * @param atMs 发生时刻（账上的 `created_at` / `at`）
 * @param who 谁干的
 * @param what 做了什么（一句话；本层负责压成一行）
 * @param status 账上的线值——**上不上屏由 [ActivityBoardRules.resultOf] 一处判**，
 *   候选这一侧不做任何过滤（谁都能递，能上屏的只有闭集里那四个）
 */
internal data class ActivityCandidate(
    val atMs: Long,
    val who: ActivityWho,
    val what: String,
    val status: String,
)

/**
 * 上屏的一行（四列都已经是"可以画的字"）。
 *
 * @param atMs 排序与水位比较只认它（显示用的 [clock] 不许反过来参与判断）
 * @param clock 时间列（`HH:mm`，本地时区）
 * @param what 做了什么（已压成一行，超长截断带省略号）
 */
internal data class ActivityRow(
    val atMs: Long,
    val clock: String,
    val who: ActivityWho,
    val what: String,
    val result: ActivityResult,
)

internal object ActivityBoardRules {

    /** 一次最多摊几行（屏幕不是账本；超了按新→旧截断，绝不"全量导出"）。 */
    const val MAX_ROWS = 20

    /** 「做了什么」一句最多几个字（超出截断，保证一列一行）。 */
    const val WHAT_CHARS = 48

    // ── 线值（账上的状态 → 本层认的七个字）─────────────────────────────────
    //
    // 前四个上屏，后三个**明确不上屏**（判据①点的就是后三个的名字，所以这里显式列出来，
    // 不靠 `else` 兜底——`else` 只收"认不出的线值"，那一条也要丢）。

    /** 已执行，成了。 */
    const val WIRE_ACTED = "acted"

    /** 已执行，砸了。 */
    const val WIRE_FAILED = "failed"

    /** 判了"不打扰"，按默认走完（自决账）。 */
    const val WIRE_SKIPPED = "skipped"

    /** 验不过已退回上一版（进化提案 `rolled_back`）。 */
    const val WIRE_ROLLED_BACK = "rolled_back"

    /** **不上屏**：计划未执行（等确认／提案没走到验证／修复只落了 intent）。 */
    const val WIRE_PLANNED = "planned"

    /** **不上屏**：被压（卡没摆出来）。落点在本屏不读的那本账上，这条线值只给判据与负例用。 */
    const val WIRE_SUPPRESSED = "suppressed"

    /** **不上屏**：被拒（准入硬拒／退回）。 */
    const val WIRE_REFUSED = "refused"

    /** 时间列的形状（只到分：这是"今天干过什么"，不是审计时间线）。 */
    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    /**
     * 线值 → 结果；**返回 null ＝这一行不上屏**。
     *
     * 判据①：计划未执行 / 被压 / 被拒 三支都返回 null（显式列出，不靠 else）。
     * `else` 收"认不出的线值"——同样丢：新的账本状态加进来时，屏幕**少一条**是可接受的，
     * 把"没执行"当成"执行过"是不可接受的。
     */
    fun resultOf(status: String): ActivityResult? = when (status) {
        WIRE_ACTED -> ActivityResult.DONE
        WIRE_FAILED -> ActivityResult.FAILED
        WIRE_SKIPPED -> ActivityResult.SKIPPED
        WIRE_ROLLED_BACK -> ActivityResult.ROLLED_BACK
        WIRE_PLANNED, WIRE_SUPPRESSED, WIRE_REFUSED -> null
        else -> null
    }

    /**
     * 这一屏认的起点（毫秒）：**今天零点**与**看板水位**取大者。
     *
     * @param clearedAtMs 上次「清空」的时刻（0 ＝没清过）。水位是"这道线以下的都当没看见"，
     *   所以等于水位的行也算清掉了（同一毫秒落两条时一起走——宁可多清一条，不许清不掉）
     */
    fun fromMs(nowMs: Long, clearedAtMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val dayStart = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            .atStartOfDay(zone).toInstant().toEpochMilli()
        val afterClear = if (clearedAtMs > 0L) clearedAtMs + 1L else Long.MIN_VALUE
        return maxOf(dayStart, afterClear)
    }

    /**
     * 候选 → 上屏行（**唯一入口**：过滤、四列、倒序、上限都在这一处）。
     *
     * @param candidates 各本账归一的候选（本层不看它来自哪本账）
     * @param nowMs 现在（定"今天"的那一刀）
     * @param clearedAtMs 上次清空的时刻（0 ＝没清过）
     * @param zone 时区（测试传固定时区，生产传系统时区）
     */
    fun rows(
        candidates: List<ActivityCandidate>,
        nowMs: Long,
        clearedAtMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<ActivityRow> {
        val from = fromMs(nowMs, clearedAtMs, zone)
        return candidates
            .mapNotNull { toRow(it, zone) }
            .filter { it.atMs >= from }
            .sortedByDescending { it.atMs }
            .take(MAX_ROWS)
    }

    /** 「做了什么」压成一行：折空白 ＋ 超长截断（`…` 也算一个字）。 */
    fun oneLine(text: String): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        if (flat.length <= WHAT_CHARS) return flat
        return flat.take(WHAT_CHARS - 1) + "…"
    }

    private fun toRow(candidate: ActivityCandidate, zone: ZoneId): ActivityRow? {
        val result = resultOf(candidate.status) ?: return null
        return ActivityRow(
            atMs = candidate.atMs,
            clock = CLOCK.format(Instant.ofEpochMilli(candidate.atMs).atZone(zone)),
            who = candidate.who,
            what = oneLine(candidate.what),
            result = result,
        )
    }
}
