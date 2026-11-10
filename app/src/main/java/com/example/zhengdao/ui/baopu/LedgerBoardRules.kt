// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百四十四批（第二段，2026-11-09）· 治理中心「账本」那一块的**判据层**
// （纯函数，零 Android、零 IO、零 R）。
//
// 四件事在这里定死，屏幕层只许照着画：
//   1. **行里只有一句话标题**（判据③）：[LedgerRowView] 的 `title` 就是渲染出来的那一句，
//      没有正文、没有 note、没有右相理由——不是"没显示"，是这个类型里没有这些字段；
//      状态与归档标记只在里面当**分组依据**，不上行。
//   2. **分组顺序固定**（用户口径「在飞＞待验收＞已收＞冻结」）：[GROUP_ORDER] 是唯一顺序；
//      「撤」排在最后（账本要留"没做啥"的证据，不许从界面上消失）。
//   3. **归档的默认不露**（判据⑤）：[groups] 的 `includeArchived=false` 里没有「已归档」这一组；
//      切到「全部」才多出末尾那一组。账文件一个字都不动（归档在数据层只是多一行 archive）。
//   4. **一次只展开一块**：槽位状态机仍然是 565 的 [TodoBoardRules.toggle]——
//      本块只加一个键 [BLOCK_LEDGER]，**不另写一套**展开逻辑（判据②："别各搞一套"）。
//
// 另外一条：**点单条能跳回来源消息**（用户口径）。能不能跳由 [LedgerRowView.canJump] 说，
// 它的唯一来源是"来源号解析出真消息"——解析不出就不给跳（判据：没有 sourceMsgId 就不给跳）。
// 解析本身要查库，所以放在屏幕层的 IO 里做（[resolve] 是个纯函数，只做格式判定）。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.ledger.LedgerCounts
import com.example.zhengdao.baopu.ledger.LedgerItem
import com.example.zhengdao.baopu.ledger.LedgerRules
import com.example.zhengdao.baopu.ledger.LedgerState

/**
 * 跳到哪一条消息（会话 id ＋ 消息 id）。
 *
 * 只有**解析成功**才会有这个对象：解析不出 ⇒ null ⇒ 那一行不可点（点了也不跳）。
 */
internal data class LedgerJump(val sessionId: Long, val messageId: Long)

/**
 * 账本上的一行：**只有一句话标题**（判据③）。
 *
 * @param id 账本 id（界面上不显示；点跳时不用它，跳靠 [jump]）
 * @param title 那句话（≤40 字由写入侧保证，544 的 `LedgerRules.titleRefusal`）
 * @param state 只用于分组，**不渲染在行上**（状态在组标题上）
 * @param archived 只用于「账面／全部」过滤，**不渲染在行上**
 * @param jump 来源消息（null ＝这条没有可跳的来源号）
 */
internal data class LedgerRowView(
    val id: String,
    val title: String,
    val state: LedgerState,
    val archived: Boolean,
    val jump: LedgerJump?,
) {
    /** 能不能点：**有解析出来的来源消息**才给跳（用户口径原话）。 */
    val canJump: Boolean get() = jump != null
}

/** 一组（组标题由屏幕层按 [key] 查资源，本层不给文案）。 */
internal data class LedgerGroupView(
    /** [LedgerState.wire] 之一，或者 [GROUP_ARCHIVED]。 */
    val key: String,
    val rows: List<LedgerRowView>,
)

/** 账本块的判据（纯函数）。 */
internal object LedgerBoardRules {

    /** 这一块在"展开槽位"里的键（槽位状态机复用 [TodoBoardRules.toggle]）。 */
    const val BLOCK_LEDGER: String = "ledger"

    /** 「已归档」那一组的键（不是状态：归档是"露不露面"，不是第五态之外的新状态）。 */
    const val GROUP_ARCHIVED: String = "archived"

    /** 分组顺序（用户口径「在飞＞待验收＞已收＞冻结」；撤排最后，账本留"没做啥"的证据）。 */
    val GROUP_ORDER: List<LedgerState> = listOf(
        LedgerState.FLYING,
        LedgerState.PENDING,
        LedgerState.ACCEPTED,
        LedgerState.FROZEN,
        LedgerState.WITHDRAWN,
    )

    /**
     * 收起态那一行（用户点名的字面形状：`账本 · 在飞 N / 待验收 M / 已收 K`）。
     *
     * 为什么不用 `LedgerRules.headerLine`：那一行是**记录态的五行**（在飞/待验收/已收/冻结/撤），
     * 给工具回执与账本自查看；这一行是**屏幕上的一行**，只要用户点名的三个数。
     * 两个都是"唯一渲染口"，只是服务的读者不同——本函数只读 [LedgerCounts] 的字段，不重算。
     */
    fun header(counts: LedgerCounts): String =
        "账本 · 在飞 ${counts.flying} / 待验收 ${counts.pending} / 已收 ${counts.accepted}"

    /**
     * 来源号的**格式**判定（纯函数；查库在屏幕层做）。
     *
     * 认两种写法：纯数字＝消息 id；`<会话id>:<消息id>`＝两段都给。
     * 别的写法（"缺口-544"、提案 id 之类）一律 null ⇒ 那一行不给跳。
     */
    fun parseJump(raw: String): Pair<Long, Long>? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        if (t.matches(Regex("^\\d+$"))) {
            val mid = t.toLongOrNull() ?: return null
            return 0L to mid // 0 ＝会话待查（屏幕层按消息 id 反查会话）
        }
        val m = Regex("^(\\d+):(\\d+)$").find(t) ?: return null
        val sid = m.groupValues[1].toLongOrNull() ?: return null
        val mid = m.groupValues[2].toLongOrNull() ?: return null
        return if (sid <= 0L || mid <= 0L) null else sid to mid
    }

    /** 账本条目 → 行（[resolve] 只在来源号非空时被调；它自己会兜住查库失败）。 */
    fun rows(items: List<LedgerItem>, resolve: (String) -> LedgerJump?): List<LedgerRowView> =
        items.map { item ->
            LedgerRowView(
                id = item.id,
                title = LedgerRules.rowText(item),
                state = item.status,
                archived = item.archivedAtMs > 0L,
                jump = if (item.sourceMsgId.isBlank()) null else resolve(item.sourceMsgId),
            )
        }

    /**
     * 分组（顺序固定；`includeArchived=false` 时归档那一组整个不出现）。
     *
     * 空组**不出现**：一块屏幕上五六个空标题比"没有"更难读（同 539 活动看板"没有就不占一行"的口径）。
     */
    fun groups(rows: List<LedgerRowView>, includeArchived: Boolean): List<LedgerGroupView> {
        val shown = if (includeArchived) rows else rows.filter { !it.archived }
        val out = GROUP_ORDER.mapNotNull { state ->
            val ofState = shown.filter { it.state == state && !it.archived }
            if (ofState.isEmpty()) null else LedgerGroupView(state.wire, ofState)
        }.toMutableList()
        val archived = shown.filter { it.archived }
        if (archived.isNotEmpty()) out += LedgerGroupView(GROUP_ARCHIVED, archived)
        return out
    }

    /**
     * 一条消息在 [BaopuMessageList] 里的 **item 下标**（找不到 ⇒ -1）。
     *
     * 为什么要在本层重算一遍布局：列表把消息按"轮"分组，有过程数据的轮只出一个 item，
     * 纯聊天轮一轮出 N 个 item。要 `scrollToItem` 就得知道"第几个 item"，而那个数只有
     * 走一遍同样的分组才知道。这里用的是**列表自己那两个判据**（`groupRounds` / `roundHasProcess`），
     * 没有第二套口径；判据变化时这里跟着变（[messageItemIndex] 有专门用例钉住这条对应关系）。
     */
    fun messageItemIndex(
        messages: List<BaopuUiMessage>,
        steps: List<BaopuStepRow>,
        awaitConfirmTool: String?,
        messageId: Long,
    ): Int {
        if (messages.isEmpty()) return -1
        var index = 0
        for (round in groupRounds(messages)) {
            if (roundHasProcess(round, steps, awaitConfirmTool)) {
                if (round.messages.any { it.id == messageId }) return index
                index += 1
            } else {
                val at = round.messages.indexOfFirst { it.id == messageId }
                if (at >= 0) return index + at
                index += round.messages.size
            }
        }
        return -1
    }
}
