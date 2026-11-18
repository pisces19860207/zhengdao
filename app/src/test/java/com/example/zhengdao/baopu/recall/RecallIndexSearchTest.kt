// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.recall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 540 索引适配层判据（假索引口直测，零 Android、零真库）。
 *
 * 钉三件：①search 主路径＝命中原文＋真时刻；②光杆指代（[RecallIndexSearch] 的
 * preferDigest）＝会话摘要，摘要空退回原文；③补不到时刻＝主库已无此消息 ⇒ 丢弃。
 */
class RecallIndexSearchTest {

    private class FakeIndex(
        private val hits: List<IndexHit>,
        private val points: List<IndexPoint> = emptyList(),
        private val status: IndexStatus = IndexStatus(0, 0, 0),
    ) : RecallIndexPort {
        var searched: Pair<String, Int>? = null
        var digested: Pair<Long, Int>? = null

        override fun search(query: String, limit: Int): List<IndexHit> {
            searched = query to limit
            return hits
        }

        override fun digest(sessionId: Long, max: Int): List<IndexPoint> {
            digested = sessionId to max
            return points
        }

        override fun status(): IndexStatus = status
    }

    private fun times(vararg pairs: Pair<Long, Long>): (List<Long>) -> Map<Long, Long> =
        { pairs.toMap() }

    @Test
    fun `有实词_走命中原文_补真时刻_组内新话在前`() {
        val index = FakeIndex(
            hits = listOf(
                IndexHit(msgId = 42L, sessionId = 7L, snippet = "部署用 8080 端口"),
                IndexHit(msgId = 41L, sessionId = 9L, snippet = "端口别占 80"),
            ),
        )
        val port = RecallIndexSearch(index, times(42L to 2_000L, 41L to 1_000L), preferDigest = false)
        val hits = port.search(listOf("端口", "部署"))
        assertEquals("查到一次、查询串空格分隔、上限 20", "端口 部署" to 20, index.searched)
        assertNull("有实词不走摘要", index.digested)
        assertEquals(listOf(42L, 41L), hits.map { it.messageId })
        assertEquals(listOf(7L, 9L), hits.map { it.sessionId })
        assertEquals("时刻来自主库补全", listOf(2_000L, 1_000L), hits.map { it.atMs })
        assertEquals("正文用命中摘要", "部署用 8080 端口", hits.first().text)
    }

    @Test
    fun `光杆指代_走会话摘要_每条带msgid`() {
        val index = FakeIndex(
            hits = listOf(
                IndexHit(42L, 7L, "命中原文"),
                IndexHit(41L, 7L, "另一条命中"),
            ),
            points = listOf(IndexPoint(42L, "摘要一"), IndexPoint(40L, "摘要二")),
        )
        val port = RecallIndexSearch(index, times(42L to 900L, 40L to 800L), preferDigest = true)
        val hits = port.search(listOf("部署"))
        assertEquals("摘要取第一名命中的会话、最多 5 条", 7L to 5, index.digested)
        assertEquals("摘要要点进卡、会话 id 同源", listOf(42L, 40L), hits.map { it.messageId })
        assertEquals(listOf(7L, 7L), hits.map { it.sessionId })
        assertEquals(listOf("摘要一", "摘要二"), hits.map { it.text })
    }

    @Test
    fun `光杆指代但摘要为空_退回命中原文`() {
        val index = FakeIndex(
            hits = listOf(IndexHit(42L, 7L, "命中原文")),
            points = emptyList(),
        )
        val port = RecallIndexSearch(index, times(42L to 900L), preferDigest = true)
        assertEquals("命中原文", port.search(listOf("部署")).single().text)
    }

    @Test
    fun `补不到时刻就丢_命中原文这条已被压缩或删掉`() {
        val index = FakeIndex(
            hits = listOf(
                IndexHit(42L, 7L, "还在的"),
                IndexHit(99L, 7L, "已被压缩/删掉"),
            ),
        )
        val port = RecallIndexSearch(index, times(42L to 900L), preferDigest = false)
        val hits = port.search(listOf("部署"))
        assertEquals("补不到时刻的命中丢掉（跳不过去的不端给用户）", listOf(42L), hits.map { it.messageId })
    }

    @Test
    fun `补不到时刻就丢_摘要要点同样`() {
        val index = FakeIndex(
            hits = listOf(IndexHit(42L, 7L, "命中原文")),
            points = listOf(IndexPoint(42L, "摘要一"), IndexPoint(98L, "摘要二")),
        )
        val port = RecallIndexSearch(index, times(42L to 900L), preferDigest = true)
        assertEquals("摘要里补不到时刻的那条同样丢", listOf(42L), port.search(listOf("部署")).map { it.messageId })
    }

    @Test
    fun `索引零命中_不发摘要_返回空`() {
        val index = FakeIndex(hits = emptyList())
        val port = RecallIndexSearch(index, { emptyMap() }, preferDigest = true)
        assertTrue(port.search(listOf("部署")).isEmpty())
        assertNull("零命中不白调一次摘要", index.digested)
    }

    @Test
    fun `空线索_不发查询`() {
        val index = FakeIndex(hits = listOf(IndexHit(1L, 1L, "x")))
        val port = RecallIndexSearch(index, { emptyMap() }, preferDigest = false)
        assertTrue(port.search(listOf("  ", "")).isEmpty())
        assertNull("空词表不发检索", index.searched)
    }

    @Test
    fun `查询串_去空去重截断`() {
        assertNull(RecallIndexPlan.queryOf(emptyList()))
        assertNull(RecallIndexPlan.queryOf(listOf("  ", "")))
        assertEquals("部署 端口", RecallIndexPlan.queryOf(listOf("部署", " 部署 ", "端口", "")))
        assertEquals(VagueTrigger.MAX_KEYWORDS, RecallIndexPlan.queryOf((1..20).map { "词$it" })!!.split(" ").size)
    }

    @Test
    fun `索引上限20_与533默认同值`() {
        assertEquals(20, RecallIndexPlan.INDEX_LIMIT)
    }
}
