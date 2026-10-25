// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百零三批：同会话「同批问题只问一次」的账本（AskBatchLedger，与 171 的
// ConfirmGrants 同房型）。负例 6 的判据本体在这里：同一批问题第二次问，
// 账本原样回上次的答案（不弹卡、不丢答案）；换会话不继承；键按 id 排序——
// 同一组问题换顺序仍是同批。
package com.example.zhengdao.baopu.agent

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AskBatchLedgerTest {

    @After
    fun tearDown() {
        AskBatchLedger.resetForTest()
    }

    private fun batch(vararg ids: String) = ids.map { AskQuestion(it, "问 $it") }

    @Test
    fun `L1_同批换顺序仍是同批_id不同才是新批`() {
        val a = AskBatchLedger.keyOf(batch("q1", "q2", "q3"))
        val b = AskBatchLedger.keyOf(batch("q3", "q1", "q2"))
        assertEquals("同一组问题换顺序＝同批（本批立意：别打扰用户）", a, b)
        val c = AskBatchLedger.keyOf(batch("q1", "q2", "q9"))
        assertTrue("换了一个 id ＝ 新的一批", a != c)
        assertNull("空批没有 key", AskBatchLedger.keyOf(emptyList()))
    }

    @Test
    fun `N6_同一会话第二次问同批_原样回上次的答案不再弹`() {
        val key = AskBatchLedger.keyOf(batch("q1", "q2"))!!
        assertNull("没问过＝没答案（要弹卡）", AskBatchLedger.answers(1L, key))
        AskBatchLedger.record(1L, key, mapOf("q1" to "甲", "q2" to "乙"))
        val replay = AskBatchLedger.answers(1L, key)
        assertEquals("第二次问拿到一模一样的答案，一个不缺", mapOf("q1" to "甲", "q2" to "乙"), replay)
        val audit = AskBatchLedger.auditFor(1L)
        assertEquals("一次记账＋一次回放＝两条审计", 2, audit.size)
        assertNull(audit[0].replayed)
        assertEquals("回放那条要带上原答案（可对账）", mapOf("q1" to "甲", "q2" to "乙"), audit[1].replayed)
        assertEquals(1, audit[1].useCount)
        assertEquals("记的批次数＝1", 1, AskBatchLedger.batchCount(1L))
    }

    @Test
    fun `L2_换会话不继承_会话结束全清`() {
        val key = AskBatchLedger.keyOf(batch("q1"))!!
        AskBatchLedger.record(1L, key, mapOf("q1" to "甲"))
        assertNull("会话 2 白捡会话 1 的答案是串味", AskBatchLedger.answers(2L, key))
        AskBatchLedger.endSession(1L)
        assertNull("endSession 后连会话 1 自己也不继承", AskBatchLedger.answers(1L, key))
        assertEquals(0, AskBatchLedger.batchCount(1L))
    }

    @Test
    fun `L3_答案以第一次为准_空答案不入账`() {
        val key = AskBatchLedger.keyOf(batch("q1"))!!
        AskBatchLedger.record(1L, key, mapOf("q1" to "甲"))
        AskBatchLedger.record(1L, key, mapOf("q1" to "被改掉的"))
        assertEquals(
            "同 key 重复记不许覆盖第一次的答案",
            mapOf("q1" to "甲"),
            AskBatchLedger.answers(1L, key),
        )
        AskBatchLedger.record(2L, key, emptyMap())
        assertEquals("空答案（没答）不入账", 0, AskBatchLedger.batchCount(2L))
    }
}
