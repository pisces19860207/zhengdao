// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第七十二批（§B 80 结构切片 · 纯逻辑抽取）：从 `BaopuChatController.refineOnSettle` 抽出的
// **两段纯判据**——① 提炼游标切片（这一轮该提炼哪几句用户原话、用了哪些工具）；
// ② 待过目候选的合并去重与上限截断。
//
// 为什么抽它：游标是「防同一轮双提炼」的唯一开关，判断错一次就是自省重复刷屏或整轮不提炼；
// 而它原先埋在「读 run_log + 调模型 + 写 SP」中间，只能靠真机观察。搬移**行为零变化**。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.MemoryEntry

/**
 * 一次提炼的输入切片（纯值）。
 *
 * [markCount] 是**本次应写回游标的用户消息条数**（= 会话里用户文本段的累计条数），
 * 不是增量条数——搬移前写的就是 `userTexts.size`，别改成 `fresh.size`（会让游标原地踏步）。
 */
internal data class BaopuRefineSlice(
    val freshUserTexts: List<String>,
    val toolsUsed: List<String>,
    val markCount: Int,
)

internal object BaopuRefineWindow {

    /**
     * 游标切片：返回 null = 没有新用户输入（空跑/纯恢复）⇒ 调用方直接不提炼。
     *
     * 三条判据（搬移前就在）：
     * 1. 游标只在**同一个会话**内生效（换会话 ⇒ 从头算，`markSession != sessionId`）；
     * 2. 只认用户消息里的 `text` part（工具卡/图片不算「用户原话」）；
     * 3. 工具统计只算**本轮**——从第一条新用户消息的 id 起（含它自己）。
     */
    fun slice(
        messages: List<BaopuUiMessage>,
        sessionId: Long,
        markSession: Long,
        markCount: Int,
    ): BaopuRefineSlice? {
        val userTexts = messages
            .filter { it.role == "user" }
            .flatMap { m -> m.parts.filter { it.kind == "text" }.map { it.text } }
        val already = if (markSession == sessionId) markCount.coerceAtLeast(0) else 0
        val fresh = userTexts.drop(already)
        if (fresh.isEmpty()) return null

        val users = messages.filter { it.role == "user" }
        val firstFreshId = if (users.isEmpty()) null else users[users.size - fresh.size].id
        val roundMsgs = messages.filter { it.id >= (firstFreshId ?: Long.MAX_VALUE) }
        val toolsUsed = roundMsgs
            .flatMap { m -> m.parts.filter { it.kind == "tool" }.map { it.tool } }
            .filter { it.isNotBlank() }
            .distinct()

        return BaopuRefineSlice(freshUserTexts = fresh, toolsUsed = toolsUsed, markCount = userTexts.size)
    }

    /** 合并结果：[dropped] = 去重后仍超上限、被截掉的条数（调用方据此决定要不要记日志）。 */
    data class Merge(val kept: List<MemoryEntry>, val dropped: Int)

    /**
     * 追加（不冲掉用户还没过目的老候选）＋ 同正文去重 ＋ 上限截断（先到先得）。
     *
     * 去重键是 `body` 原文，不是 id：候选 id 带时间戳，同一件事每次提炼都会换 id。
     */
    fun mergePending(old: List<MemoryEntry>, incoming: List<MemoryEntry>, max: Int): Merge {
        val merged = (old + incoming).distinctBy { it.body }
        val kept = merged.take(max)
        return Merge(kept = kept, dropped = merged.size - kept.size)
    }
}
