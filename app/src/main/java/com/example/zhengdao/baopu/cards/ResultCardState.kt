// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百一十二批 · **三态机**（派工单 §1：弹出 → 收起成小条 → 再开；§3 负例①②⑦）。
//
// ## 三态为什么是「一个下标 ＋ 一个布尔」而不是三份列表
// 派工单要的三件事——「点空白收成小条」「点小条再弹」「× 收起整组留一句已收起 N 条」——
// 说的是**同一批结果**的三种呈现。数据只有一份，呈现三个选择：
//   · `activeIndex >= 0`  ⇒ 展开（那张卡浮起来）
//   · `activeIndex < 0`   ⇒ 收起（正文里留一行小条）
//   · `collapsedAll`      ⇒ 整组收起（只剩「已收起 N 条结果」，点开回来）
// 拆成三份列表就会出现「收起时把卡删了」这种实现，而负例②恰恰钉的「收起后数据丢失」。
//
// ## 「再开不重查」是**结构**保证，不是纪律
// 这里存的是 [ResultCardSpec]（已经带值的卡）本身：`expand` 只改下标，手上没有任何
// 「查一次」的入口可调。所以「点小条又打了一次库」在类型层面就写不出来——
// 这也是本批不接受「收起时先记 query 参数、再开时重跑」那种设计的原因：
// 那等于把「有没有重查」交给调用方的自觉。
//
// ## 负例⑦（同一批结果弹两张一样的卡）
// [push] 先按 [sameResult] 找同一件事：找到就**原地换值**、不追加。这比「加个 id 去重」可靠，
// 因为 producer 不必生成 id，也不会出现「同一件事两个 id」的两张卡。
package com.example.zhengdao.baopu.cards

/** 卡区当前的呈现（界面按它决定画什么）。 */
enum class CardPhase {
    /** 展开：浮卡在屏上。 */
    EXPANDED,

    /** 收起：正文里留一行小条。 */
    COLLAPSED,

    /** 整组收起：只剩「已收起 N 条结果」这一句。 */
    HIDDEN,
}

/**
 * 卡区的全部状态（不可变；所有操作都返回新的一份）。
 *
 * @param cards 内存里那一份结果（**收起不清空**——负例②）
 * @param activeIndex 正在展开的那张；-1 ＝ 全部收起
 * @param collapsedAll 用户点过 ×（整组收起）；与 [activeIndex] 互斥，[expand] 会清掉它
 */
data class ResultCardState(
    val cards: List<ResultCardSpec> = emptyList(),
    val activeIndex: Int = -1,
    val collapsedAll: Boolean = false,
) {
    val active: ResultCardSpec? get() = cards.getOrNull(activeIndex)

    /** 除展开那张之外、需要折成小条的张数。 */
    val foldedCount: Int get() = if (activeIndex < 0) cards.size else cards.size - 1

    /** 整组收起时那句「已收起 N 条结果」里的 N。 */
    val hiddenCount: Int get() = if (collapsedAll) cards.size else 0

    val phase: CardPhase
        get() = when {
            collapsedAll -> CardPhase.HIDDEN
            activeIndex in cards.indices -> CardPhase.EXPANDED
            else -> CardPhase.COLLAPSED
        }
}

object ResultCards {

    /**
     * 一条结果到了：同一件事就原地换值，否则追加并**自动展开**（派工单 §1「结果到达 ⇒ 浮卡升起」）。
     *
     * 故意不判断「是不是同一批」：批次的边界在 producer 那边（一次提问一次结果），
     * 本层只保证「内容一样的两张不会同时存在」。
     */
    fun push(state: ResultCardState, card: ResultCardSpec): ResultCardState {
        val at = state.cards.indexOfFirst { sameResult(it, card) }
        if (at >= 0) {
            val next = state.cards.toMutableList()
            next[at] = card
            return state.copy(cards = next, activeIndex = at, collapsedAll = false)
        }
        val next = state.cards + card
        return state.copy(cards = next, activeIndex = next.lastIndex, collapsedAll = false)
    }

    /** 点卡外空白／下滑手柄：收起成小条（**下标保留**，小条指向同一张）。 */
    fun collapse(state: ResultCardState): ResultCardState =
        if (state.cards.isEmpty()) state else state.copy(activeIndex = -1)

    /** 点小条再开：只改下标，不碰 [ResultCardState.cards]。 */
    fun expand(state: ResultCardState, index: Int = -1): ResultCardState {
        if (state.cards.isEmpty()) return state
        val target = when {
            index in state.cards.indices -> index
            state.activeIndex in state.cards.indices -> state.activeIndex
            else -> state.cards.lastIndex
        }
        return state.copy(activeIndex = target, collapsedAll = false)
    }

    /** × 整组收起：卡**不删**，只把呈现改成一句计数（点开还能回来）。 */
    fun collapseAll(state: ResultCardState): ResultCardState =
        state.copy(activeIndex = -1, collapsedAll = true)

    /** 「已收起 N 条结果」点一下：回到收起态的小条（不直接弹卡，免得像误触）。 */
    fun restore(state: ResultCardState): ResultCardState = state.copy(collapsedAll = false)

    /** 换会话／清屏：整组清掉（唯一会丢数据的入口，且必须是「换会话」这种显式动作）。 */
    fun clear(): ResultCardState = ResultCardState()
}
