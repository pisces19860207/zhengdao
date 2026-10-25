// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百零三批（批量问卡）：「同作用域同一批问题只问一次」的账本，与 171 的
// [com.example.zhengdao.baopu.ConfirmGrants] 同房型（进程级、按 sessionId 分账、
// 只住内存、endSession 即清）。
//
// 为什么只住内存不落库：与 ConfirmGrants 同一条保守判断——落库会把「问过什么」
// 变成跨重启事实，进程被杀后重新问一遍是**更吵但更对**的那一端。
//
// 与 171 的接法（派工单 §1 末条）：confirm 侧的键是「工具+规范化参数」，这里的键是
// 「同一批问题的规范化形状」（[keyOf]：按 id 排序后走 ConfirmKeys.canonical，同一组
// 问题换顺序仍是同批——本批的立意就是别打扰用户）。同一会话第二次问同批 ⇒ 不弹卡，
// 原样回上次的答案（[answers]），并在审计里留一行回放（负例 6 的判据本体：
// 第二次问不许再弹卡、更不许丢答案）。
package com.example.zhengdao.baopu.agent

import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

internal object AskBatchLedger {

    /** 审计上限（与 ConfirmGrants 同量级：低频事件，手机进程不该无限长）。 */
    private const val AUDIT_CAP = 200

    /** 一条审计：「问过」（asked）或「回放过」（replayed，原样回了上次的答案）。 */
    data class Entry(
        val at: Long,
        val sessionId: Long,
        val key: String,
        val questionCount: Int,
        /** 回放类事件非空：这次没弹卡，直接回的就是这份。 */
        val replayed: Map<String, String>?,
        val useCount: Int,
    )

    private class Batch(
        val answers: Map<String, String>,
        val questionCount: Int,
        val askedAt: Long,
    ) {
        @Volatile var useCount: Int = 0
        @Volatile var lastUsedAt: Long = askedAt
    }

    private class SessionLedger {
        val batches = ConcurrentHashMap<String, Batch>()
        val audit: MutableList<Entry> = Collections.synchronizedList(mutableListOf())
    }

    private val sessions = ConcurrentHashMap<Long, SessionLedger>()

    /**
     * 同一批问题的 key（**纯函数**）：按 id 排序 → 紧凑 JSON → ConfirmKeys.canonical。
     * 排序是故意的：同一组问题换顺序仍是同批；id 集不同才是新的一批。
     * 空批返回 null（没有可去重的东西；调用方按「没 key」处理＝照问）。
     */
    fun keyOf(questions: List<AskQuestion>): String? {
        if (questions.isEmpty()) return null
        val arr = JSONArray()
        questions.sortedBy { it.id }.forEach { q ->
            val o = JSONObject()
                .put("id", q.id)
                .put("title", q.title)
                .put("kind", q.kind.key)
                .put("required", q.required)
                .put("default", q.default)
            if (q.options.isNotEmpty()) {
                val ops = JSONArray()
                q.options.sortedBy { it.id }.forEach { ops.put(JSONObject().put("id", it.id)) }
                o.put("options", ops)
            }
            arr.put(o)
        }
        return "ask_batch|" + com.example.zhengdao.baopu.ConfirmKeys.canonical(
            JSONObject().put("questions", arr),
        )
    }

    /** 命中即回上次答案并记一笔回放审计；没问过返回 null。 */
    fun answers(sessionId: Long, key: String): Map<String, String>? {
        val ledger = sessions[sessionId] ?: return null
        val batch = ledger.batches[key] ?: return null
        batch.useCount += 1
        batch.lastUsedAt = System.currentTimeMillis()
        record(
            ledger,
            Entry(
                at = batch.lastUsedAt,
                sessionId = sessionId,
                key = key,
                questionCount = batch.questionCount,
                replayed = batch.answers,
                useCount = batch.useCount,
            ),
        )
        return batch.answers
    }

    /** 记账（幂等：同 key 重复记只更新时间戳，答案以第一次为准）。 */
    fun record(sessionId: Long, key: String, answers: Map<String, String>) {
        if (answers.isEmpty()) return
        val ledger = sessions.getOrPut(sessionId) { SessionLedger() }
        val now = System.currentTimeMillis()
        ledger.batches.compute(key) { _, existing ->
            if (existing != null) {
                existing.lastUsedAt = now
                existing
            } else {
                Batch(answers.toMap(), answers.size, now)
            }
        }
        record(
            ledger,
            Entry(
                at = now,
                sessionId = sessionId,
                key = key,
                questionCount = answers.size,
                replayed = null,
                useCount = ledger.batches[key]?.useCount ?: 0,
            ),
        )
    }

    /** 会话结束 ⇒ 这个会话的批次全清（换会话不继承，ConfirmGrants 同口径）。 */
    fun endSession(sessionId: Long) {
        sessions.remove(sessionId)
    }

    /** 这个会话当前记着的批次数（测试/读数用）。 */
    fun batchCount(sessionId: Long): Int = sessions[sessionId]?.batches?.size ?: 0

    /** 审计流水（测试读数；返回拷贝，调用方改不动账本）。 */
    fun auditFor(sessionId: Long): List<Entry> =
        sessions[sessionId]?.audit?.toList() ?: emptyList()

    /** 直测收尾：全清（生产不调）。 */
    fun resetForTest() {
        sessions.clear()
    }

    private fun record(ledger: SessionLedger, entry: Entry) {
        ledger.audit.add(entry)
        while (ledger.audit.size > AUDIT_CAP) {
            ledger.audit.removeAt(0)
        }
    }
}
