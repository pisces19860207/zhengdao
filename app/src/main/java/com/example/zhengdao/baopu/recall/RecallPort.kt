// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 540 模糊唤回 · **检索缝（约定接口）**。
//
// ## 这条缝是谁的
// 派工单②原话「多线索检索 ≤3 次（**复用 533 的索引，不新建索引**）」。533（会话搜索，
// bigram 倒排）已由 702 交付（`feat/session-search`，`SessionSearchStore.search/digest/status`）
// ⇒ 540 的生产主路径是 [RecallIndexSearch]（经 [SessionIndexPort] 接 533 的索引）。
// [RecallDbSearch] 退为**直读兜底**：索引增量入库还没铺到目标（≤500 条/轮）时，
// 用同一批线索再读一遍主库，两条路都空才叫没找到（[RecallFallback]）。
//
// ## 「不新建索引」在本包的落法
// 540 自己零 DDL、零建表：索引的结构与维护全在 533（`search.db` 的 `messages`/`tokens`
// 两张表）；本包只有 [RecallQuery] 一条主键点查（补时刻）和一个只读 LIKE 兜底语句。
// `RecallSourceGateTest` 扫源码钉死「全包零 DDL」与「只有 SessionIndexPort 认得 msgsearch」。
package com.example.zhengdao.baopu.recall

/**
 * 一条检索命中的**原文行**：哪条消息（`messageId` 是跳原文的钥匙）、在哪个会话、
 * 什么时候说的、正文原文（要点只许从它提炼，不许编）。
 *
 * `messageId` ≤ 0 ＝ 没有消息 id——这样的行进不了要点（派工单③「要点必带消息 id」），
 * 引擎见一个丢一个；适配层查不出 id 就不该造行。
 */
internal data class RecallHit(
    val sessionId: Long,
    val messageId: Long,
    val atMs: Long,
    val text: String,
)

/**
 * 一次检索（一组线索词）。**一次调用 ＝ 一次检索**——次数账由调用方记，
 * 硬顶 [RecallEngine.MAX_SEARCH_CALLS]。实现必须只读；失败按「没结果」返回空表
 * （fail-open：唤回是给用户搭把手的，检索坏了不许把聊天拦下来）。
 */
internal fun interface RecallSearchPort {
    fun search(keywords: List<String>): List<RecallHit>
}
