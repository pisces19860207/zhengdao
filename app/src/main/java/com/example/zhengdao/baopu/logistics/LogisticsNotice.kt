// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十五批 · 未发播报（派工单 §4：v1 **不做定时**，只在「打开抱朴时」给一句话提示）。
//
// ## 为什么每天只提示一次
// 这条挂在主屏的提示列表里。每次回前台都弹一遍＝同一个消息催两遍，第二遍起就成了噪音，
// 而她真正会做的是「关掉它再继续」——那样连第一遍也丢了。所以闸门是**按天**的：
// 今天提示过就不再提示，哪怕她把 App 杀掉重开。
// 记的是「提示过哪天」而不是「提示过几次」，所以跨天自然复位，不需要定时任务来清。
//
// ## 计数用「单」不用「车」
// 车数有三档口径（PLATE／ORDER／TRIP，见 [LogisticsBasis]），主屏这一行没有切换口径的地方，
// 写「还有 3 车没发」等于替她选了一档口径还不告诉她。单号没有口径问题：一单就是一单。
//
// ## 只报「待发」档
// 状态写着「缺件」和状态没填的单子都不算进「没发」里（同 [LogisticsAnswer.detailLine] 的口径），
// 但它们各自要点名一句——「另有 2 单标了缺件」是把事实交回去，「应该还没发」是替她下判断。
package com.example.zhengdao.baopu.logistics

import android.content.Context
import androidx.core.content.edit
import java.util.TimeZone

/** 打开抱朴时的那一句（没有可播的内容 ⇒ `null`，主屏那一行直接不出现）。 */
object LogisticsNotice {

    /** 本批自己的 SharedPreferences 文件（不复用 `zhengdao-ui`：那里面的键归主页路由管）。 */
    const val PREFS_NAME = "baopu-logistics"

    /** 已经提示过的那一天（`20261025`）。一天一次，跨天自然复位。 */
    const val KEY_SHOWN_DAY = "notice_shown_day"

    /** 明细点名上限（与答话纪律同一条数，两处两个上限迟早会漂移）。 */
    const val MAX_DETAIL = LogisticsAnswer.MAX_DETAIL

    /**
     * 该不该提示（**纯函数**，负例靶子：把 `lastShownDay != day` 删了 ⇒ 每次回前台弹一遍，本条红）。
     *
     * @param lastShownDay 上次提示的那一天；`null` = 从来没提示过
     * @param pendingCount 待发单数；0 ⇒ 没什么可播（没有比「今天都发完了」更该沉默的一句）
     */
    fun shouldShow(lastShownDay: String?, day: String, pendingCount: Int): Boolean =
        pendingCount > 0 && lastShownDay != day

    /** 待发名单（生效状态 = 待发；人勾过的盖过表里的，与查询、台账同一口径）。 */
    fun pendingRows(rows: List<StoredRow>): List<StoredRow> =
        rows.filter { it.effectiveStatus() == LogisticsStatus.PENDING }

    /**
     * 一条点名：`10234（北京朝阳，12 件）`。
     *
     * 范围与件数缺任何一个都写成「未填」而不是省略——省略会让「（北京朝阳）」和「（12 件）」
     * 看起来像两种不同的东西，而她要核的是「这单到底有没有件数」。
     */
    fun itemOf(r: StoredRow): String {
        val scope = LogisticsAggregate.scopeOf(r).ifEmpty { "未填" }
        val pieces = if (r.pieces != null) "${r.pieces} 件" else "件数未填"
        return "${r.waybill}（$scope，$pieces）"
    }

    /**
     * 播报正文（纯函数）。
     *
     * 形状就一句：`今天还有 3 单没发：…；另有 2 单标了缺件（数据 09:12 导出的配货单）`。
     * 数据时间必须带（派工单 §1 明示 + 负例⑤「不带数据时间」要红），取不到就写「取不到」，
     * 不许拿墙上时钟顶——那正是「把昨天的表当成今天的」这类错的源头。
     */
    fun textOf(rows: List<StoredRow>, at: ExportedAt, zone: TimeZone): String? {
        val pending = pendingRows(rows)
        if (pending.isEmpty()) return null
        val names = pending.map { itemOf(it) }
        val head = names.take(MAX_DETAIL).joinToString("、")
        val tail = if (names.size > MAX_DETAIL) "，等 ${names.size} 单，展开看" else ""
        val short = rows.count { it.effectiveStatus() == LogisticsStatus.SHORT }
        val unknown = rows.count { it.effectiveStatus() == LogisticsStatus.UNKNOWN }
        val extra = ArrayList<String>(2)
        if (short > 0) extra += "另有 $short 单标了缺件"
        if (unknown > 0) extra += "另有 $unknown 单状态未填，没法算进没发"
        val time = when {
            at.millis == null -> "数据时间取不到"
            !at.hasClock -> "数据 ${LogisticsExportedAt.dayKey(at.millis, zone)} 那天导出"
            else -> "数据 ${LogisticsExportedAt.clock(at.millis, zone)} 导出"
        }
        val extras = if (extra.isEmpty()) "" else "；" + extra.joinToString("；")
        return "今天还有 ${pending.size} 单没发：$head$tail$extras（$time）"
    }

    /** 现读：上次提示过哪天（读不到 ⇒ `null`，按「从来没提示过」处理）。 */
    fun shownDay(ctx: Context): String? = runCatching {
        ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SHOWN_DAY, null)
    }.getOrNull()

    /**
     * 记一笔「这一天提示过了」。
     *
     * 由调用方在**这一行真的渲染出来之后**调（`LaunchedEffect` 里），不在 [forOpen] 里顺手写：
     * 组合期间写偏好等于把「也许没显示」当成「已经说过」，那样今天她就再也没机会看到这条。
     */
    fun markShown(ctx: Context, day: String) {
        runCatching {
            ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit { putString(KEY_SHOWN_DAY, day) }
        }
    }

    /** 主屏入口：有表、有待发、今天还没提示过 ⇒ 一句话；否则 `null`。 */
    fun forOpen(
        ctx: Context,
        store: LogisticsStore,
        day: String,
        zone: TimeZone = TimeZone.getDefault(),
    ): String? {
        val rows = store.rowsFor(day)
        if (rows.isEmpty()) return null
        if (!shouldShow(shownDay(ctx), day, pendingRows(rows).size)) return null
        return textOf(rows, LogisticsAsk.exportedAtOf(rows), zone)
    }
}
