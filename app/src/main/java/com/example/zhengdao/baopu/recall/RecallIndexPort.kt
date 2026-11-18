// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 540 模糊唤回 · **533 索引的窄缝**（recall 包只认这三件，不认 533 的类型）。
//
// ## 为什么再包一层
// 533（702 交付）给的三件是 `SessionSearchStore.search/digest/status`。540 的判据层
// 要能 JVM 直测，不能拖 Android 上下文进来；也不想让「533 的 data class」散落到判据层
// （它一改形状，540 一片红）。所以这里只留 540 真正用得到的字段：命中（消息 id、
// 会话 id、摘要）、摘要要点（消息 id、正文）、索引三读数。
//
// ## 唯一实现
// [SessionIndexPort]（`SessionSearchStore` 的薄转接）——**全包只有那一个文件** import
// `baopu.msgsearch`，`RecallSourceGateTest` 扫源码钉死（「复用 533 的索引，不另造」的
// 正面锚）。将来 533 换实现，只改那一个文件。
package com.example.zhengdao.baopu.recall

/** 一条索引命中（530/702 `SearchHit` 的 540 视图；snippet ≤80 字）。 */
internal data class IndexHit(
    val msgId: Long,
    val sessionId: Long,
    val snippet: String,
)

/** 一条会话摘要要点（702 `DigestPoint` 的 540 视图；≤60 字、必带 msgId）。 */
internal data class IndexPoint(
    val msgId: Long,
    val text: String,
)

/** 索引状态三读数（540 只用来出「几行」的读数与「索引是否为空」的判断）。 */
internal data class IndexStatus(
    val messages: Long,
    val tokens: Long,
    val lastIndexedAt: Long,
)

/**
 * 533 索引在 540 侧的接口（一次调用＝一次索引动作；实现必须只读或自维护索引，
 * 不碰用户正文——正文的写面在引擎，不在检索）。
 */
internal interface RecallIndexPort {

    /** 一条查询串（[RecallIndexPlan.queryOf] 拼好的空格分隔词串），返回 ≤[limit] 条命中。 */
    fun search(query: String, limit: Int): List<IndexHit>

    /** 某会话 ≤[max] 条摘要要点（702 的抽取式摘要，不调模型）。 */
    fun digest(sessionId: Long, max: Int): List<IndexPoint>

    /** 三读数（索引行数／倒排行数／最后入库时刻）。 */
    fun status(): IndexStatus
}
