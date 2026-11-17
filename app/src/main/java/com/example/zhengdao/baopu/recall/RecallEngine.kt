// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 540 模糊唤回 · **编排与提炼判据**（纯函数：吃线索组与检索口，出要点卡的数据）。
//
// ## 四条硬规矩（派工单②③④的落法，全部可机检）
// 1. **多线索检索 ≤3 次**：线索组超过 [MAX_SEARCH_CALLS] 组只取前三组；计数器
//    [Outcome.searchCalls] 每真调一次 [RecallSearchPort.search] 加一，测试拿它断言。
// 2. **要点必带消息 id**：命中缺 `messageId`（或缺 `sessionId`）⇒ 丢弃，一个都不许进卡。
// 3. **一次 ≤5 条、每条 ≤60 字**：[MAX_POINTS]／[pointTextOf] 收口，超长折窗不折义。
// 4. **找不到直说**：全部检索完还是空 ⇒ `found=false`，卡面一句固定口径
//    [NOT_FOUND]——引擎没有任何「猜一条」「编一条」的路径，正文只能来自命中的原文。
//
// ## 排序（确定性，同一批输入必得同一张卡）
// 组序在前（本句实词 ＞ 上一条回复 ＞ 近几轮用户原话，越贴这句话越靠前）；
// 组内新话在前（`atMs` 降）、同刻按消息 id 升（写序兜底）。
package com.example.zhengdao.baopu.recall

internal object RecallEngine {

    /** 检索次数硬顶（派工单②「≤3 次」；计数器断言的锚）。 */
    const val MAX_SEARCH_CALLS = 3

    /** 一次召回最多几条要点（派工单④）。 */
    const val MAX_POINTS = 5

    /** 一条要点最多几字（派工单④「每条 ≤60 字」；窗口折断由 [pointTextOf] 收口）。 */
    const val MAX_POINT_CHARS = 60

    /** 找不到时的固定口径（派工单③「找不到就直说，不许编」）。 */
    const val NOT_FOUND = "没找到相关的旧消息"

    /** 窗口折断时，要点正文从命中词往回留几字（上下文看得懂就够了）。 */
    private const val WINDOW_BACK = 12

    /** 一条要点：正文（≤60 字）＋ **必带**的原文钥匙（会话 id ＋ 消息 id，可跳原文）。 */
    data class Point(
        val text: String,
        val sessionId: Long,
        val messageId: Long,
    )

    /**
     * 一次唤回的产出。`found=false` 时 [points] 必空、[notFoundText] 必是 [NOT_FOUND]
     * ——「没找到」三件事（直说、零要点、不编）由这一个类型守住。
     */
    data class Outcome(
        val found: Boolean,
        val points: List<Point>,
        /** 真调了几次检索（计数器；≤[MAX_SEARCH_CALLS]，测试断言用）。 */
        val searchCalls: Int,
        /** 进编排的线索组数（账面读数：几组线索）。 */
        val clueGroupCount: Int,
    ) {
        val notFoundText: String? get() = if (found) null else NOT_FOUND
    }

    /**
     * 线索分组（≤[MAX_SEARCH_CALLS] 组）：①这句话自己的实词（最贴）；②上一条助手
     * 回复的实词（「那个」最常指的是它刚说过的话）；③最近几轮用户原话的实词。
     * 空组不占位；超过三组丢后面的——丢序固定，不许按运气挑。
     */
    fun clueGroups(
        own: List<String>,
        lastReply: List<String>,
        recentUser: List<String>,
    ): List<List<String>> = listOf(own, lastReply, recentUser)
        .filter { it.isNotEmpty() }
        .take(MAX_SEARCH_CALLS)

    /**
     * 召回（纯函数；[port] 是唯一对外动作，且只读）。凑够 [MAX_POINTS] 条即止——
     * 后面的线索组不再花检索次数（「≤3 次」是上限，不是配额）。
     */
    fun recall(groups: List<List<String>>, port: RecallSearchPort): Outcome {
        val seen = LinkedHashSet<Long>()
        val picked = mutableListOf<Picked>()
        var calls = 0
        for ((gi, keywords) in groups.take(MAX_SEARCH_CALLS).withIndex()) {
            if (picked.size >= MAX_POINTS) break
            if (keywords.isEmpty()) continue // 空组不算一次检索
            calls++
            collectGroup(gi, keywords, port, seen, picked)
        }
        val points = picked
            .sortedWith(
                compareBy({ it.group }, { -it.hit.atMs }, { it.hit.messageId }),
            )
            .map { Point(it.text, it.hit.sessionId, it.hit.messageId) }
        return Outcome(
            found = points.isNotEmpty(),
            points = points,
            searchCalls = calls,
            clueGroupCount = groups.size,
        )
    }

    /**
     * 一组线索的收罗（[recall] 的内半）：命中的准入判据全在这一个小函数里——
     * 派工单③「要点必带消息 id」：缺 id（或会话 id 不真）或空正文的命中见一个丢一个。
     */
    private fun collectGroup(
        group: Int,
        keywords: List<String>,
        port: RecallSearchPort,
        seen: MutableSet<Long>,
        picked: MutableList<Picked>,
    ) {
        for (hit in port.search(keywords)) {
            if (!admits(hit)) continue
            if (seen.add(hit.messageId)) {
                picked += Picked(group, hit, pointTextOf(hit.text, keywords))
                if (picked.size >= MAX_POINTS) break
            }
        }
    }

    /** 命中准入：两端 id 都真 ＋ 正文不空（缺消息 id 的命中进不了要点）。 */
    private fun admits(hit: RecallHit): Boolean =
        hit.messageId > 0L && hit.sessionId > 0L && hit.text.isNotBlank()

    /** 编排途中的半成品（组序 ＋ 命中 ＋ 提好的正文；排序完再折叠成 [Point]）。 */
    private data class Picked(val group: Int, val hit: RecallHit, val text: String)

    /**
     * 要点正文（≤[maxChars] 字）：原文折成一行；超长时从**第一个命中词**往回留
     * [WINDOW_BACK] 字起窗（窗不起于行首 ⇒ 头上补一个省略号），窗口吃满 [maxChars]。
     * 关键词一个都没出现（理论到不了——检索就是按词命中的）⇒ 就地掐头。
     */
    fun pointTextOf(raw: String, keywords: List<String>, maxChars: Int = MAX_POINT_CHARS): String {
        val cap = maxChars.coerceAtLeast(1)
        val one = raw.replace(Regex("\\s+"), " ").trim()
        if (one.length <= cap) return one
        val idx = keywords.map { one.indexOf(it) }.filter { it >= 0 }.minOrNull()
        val start = if (idx != null && idx > WINDOW_BACK) idx - WINDOW_BACK else 0
        val budget = if (start > 0) cap - 1 else cap
        val body = one.substring(start).take(budget)
        return (if (start > 0) "…" else "") + body
    }
}
