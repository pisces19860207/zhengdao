// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第十八批-B（对话区归位）§2.1/§2.2 ＋ 第十八批主单 §2.4.1（贴底跟随与
// 「⬇ 回到最新」）。判据纪律与第十六批第一件同房型：底部卡片区的可见/折叠、跟随、
// 浮标全是**本文件的纯函数**（无 Compose 依赖，J8–J11 JVM 直测）；Composable 只接线。
package com.example.zhengdao.ui.baopu

/**
 * 底部操作卡种类（§2.1 四种「要用户动手」的卡）。
 *
 * enum 声明序＝展示优先级：确认卡堵着引擎运行，最急；问人卡同急；
 * 待过目卡是「不点就不落盘」的显式动作；候选卡最缓。
 */
internal enum class BottomCardKind { CONFIRM, ASK, PENDING, CANDIDATE }

/**
 * 底部卡片区的输入（[com.example.zhengdao.ui.baopu.BaopuChatController] 状态的纯投影）。
 *
 * 为什么是布尔而不是卡本体：判据要 JVM 直测（卡本体带 Compose/Context），
 * 投影成布尔后「有哪几张卡、折叠成什么样」就是纯数据题。
 */
internal data class BottomCardsInput(
    val confirmCard: Boolean,
    val askCard: Boolean,
    val pendingMemory: Boolean,
    val memoryCandidate: Boolean,
)

/**
 * 底部卡片区的可见形态（§2.1 第 3 条：手机上不许一屏堆三张卡）。
 *
 * @param visible 要渲染的卡（折叠时只有最上面一张）
 * @param hiddenCount 被折起来的张数（0 ⇒ 没有「还有 N 张」行）
 */
internal data class BottomCardsView(
    val visible: List<BottomCardKind>,
    val hiddenCount: Int,
) {
    val hasAny: Boolean get() = visible.isNotEmpty()
}

/**
 * J9：底部卡片折叠判据（**纯函数**）。
 *
 * 有卡 ⇒ 只展开最上面一张，其余折成「还有 N 张」一行，点开才全部展开
 * （§2.1 第 3 条；「展开 ▼」复用旧键 `common_expand_arrow`，数字角标是数据
 * 不是文案——本单 strings 零新增）。一张卡都没有 ⇒ 整个区不渲染。
 *
 * @param expandAll 用户点过「还有 N 张」⇒ true（全展开，隐藏行消失）
 */
internal fun bottomCards(input: BottomCardsInput, expandAll: Boolean = false): BottomCardsView {
    val present = buildList {
        if (input.confirmCard) add(BottomCardKind.CONFIRM)
        if (input.askCard) add(BottomCardKind.ASK)
        if (input.pendingMemory) add(BottomCardKind.PENDING)
        if (input.memoryCandidate) add(BottomCardKind.CANDIDATE)
    }
    if (present.isEmpty()) return BottomCardsView(visible = emptyList(), hiddenCount = 0)
    return if (expandAll) {
        BottomCardsView(visible = present, hiddenCount = 0)
    } else {
        BottomCardsView(visible = listOf(present.first()), hiddenCount = present.size - 1)
    }
}

/**
 * J11：贴底跟随判据（**纯函数**；与第十六批 `roundCollapsed` 同风格）。
 *
 * 只有**已贴在底部**才跟随新内容；用户正往上读历史 ⇒ 不抢（§2.2 第 8 条）。
 * 负例 2 的靶子：把这里改成恒 true（永远跟随）⇒ 上翻被抢 ⇒ J11 必红。
 */
internal fun shouldFollow(atBottom: Boolean): Boolean = atBottom

/**
 * J11：「⬇ 回到最新」浮标显不显示（**纯函数**）。
 *
 * 口径（主单 §2.4.1 第 7 条）：**不在底部就出现**——用户往上读历史时得有一条
 * 回底的路（回到最新这个「贴心小设计」接回来的就是这个行为）。有没有新内容
 * 只决定浮标上的小圆点（见 [jumpHasNewDot]），不决定浮标本身显不显示；
 * 回到底部 ⇒ 浮标消失并清标记。负例 2 的靶子在 [shouldFollow]，这里同时
 * 钉「在底部绝不出现」。
 */
internal fun shouldShowJump(atBottom: Boolean, hasNew: Boolean): Boolean = !atBottom

/**
 * 浮标上的小圆点（**纯函数**）：不在底部**且**有新内容才点亮。
 *
 * 与 [shouldShowJump] 分成两个函数：浮标 ＝ 回底的路（恒在），圆点 ＝ 新内容
 * 提醒（有才亮）——两者混一个返回值就没法分别测了。
 */
internal fun jumpHasNewDot(atBottom: Boolean, hasNew: Boolean): Boolean = !atBottom && hasNew
