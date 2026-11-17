// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 540 模糊唤回 · **检索缝（约定接口）**。
//
// ## 这条缝是谁的
// 派工单②原话「多线索检索 ≤3 次（**复用 533 的索引，不新建索引**）」。533（会话搜索，
// bigram 倒排）尚未开工 ⇒ 本包按同批惯例（678 派工单同款：依赖未合，按约定接口写
// 适配层并在报告标注）立这条**索引缝**：540 的一切检索只许从 [RecallSearchPort] 进，
// 不许自己另开第二条查消息的路。533 合入后由它实现这条接口换底，[RecallEngine] 与
// 卡面一层不动——索引是谁的、长什么样，都不归本包管。
//
// ## 「不新建索引」在本包的落法
// 生产侧今天的适配层（[RecallDbSearch]）**只读**既有 `message`/`part`/`session` 表，
// 零建表、零建索引、零 ALTER（`RecallSourceGateTest` 扫源码钉死）——检索慢就慢，
// 等的是 533 的倒排，不是另造一套索引结构。
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
