// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 540 模糊唤回 · **533 索引的薄转接**（全包唯一 import `baopu.msgsearch` 的文件）。
//
// 只做类型搬运，不加任何判断：533 的 search 自带增量入库（`catchUp` 在
// `SessionSearchStore.searchInner` 进门第一件事），所以 540 不需要另开预热任务——
// 每次唤回的三组线索各推进一批（≤500 条/轮），索引逐次铺满；没铺到的由
// `RecallFallback` 用同线索直读兜住（见 `RecallIndexSearch` 的注释）。
package com.example.zhengdao.baopu.recall

import android.content.Context
import com.example.zhengdao.baopu.BaopuDb
import com.example.zhengdao.baopu.msgsearch.SessionSearchStore

/** 生产实现：把 702 的三件搬成 540 的窄缝类型。 */
internal class SessionIndexPort(private val store: SessionSearchStore) : RecallIndexPort {

    override fun search(query: String, limit: Int): List<IndexHit> =
        store.search(query, limit = limit).map { IndexHit(it.msgId, it.sessionId, it.snippet) }

    override fun digest(sessionId: Long, max: Int): List<IndexPoint> =
        store.digest(sessionId, max).map { IndexPoint(it.msgId, it.text) }

    override fun status(): IndexStatus =
        store.status().let { IndexStatus(it.messages, it.tokens, it.lastIndexedAt) }
}

/**
 * 懒建入口（控制器只调它，不 import msgsearch）：第一次唤回才开 `search.db` 连接并
 * 让 533 的 DDL 自愈建表——索引是 533 的资产，建表与维护都在它那一侧。
 */
internal fun sessionIndexPort(db: BaopuDb, context: Context): RecallIndexPort =
    SessionIndexPort(SessionSearchStore(db, context))
