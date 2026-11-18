// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 702 派工单判据⑧：**10 万条消息夹具检索 ≤200ms（放宽到 500ms 也行，但报告写实测）**。
// JVM 里没有 SQLite ⇒ 这条量的是「检索算法」的账（倒排合流＋BM25 打分＋过滤＋排序），
// 夹具按真实形态造：10 万条、高频词 df=N、中频词 df=1_000、查询词全命中最坏情况。
// SQLite I/O 的那一半留真机标③（报告 §「性能实测」写清两边各测了什么）。
package com.example.zhengdao.baopu.msgsearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class SessionSearchPerfTest {

    companion object {
        private const val N = 100_000

        /** 高频词（每条都含，df=N——最坏情况：候选就是全库）。 */
        private lateinit var commonPostings: Map<String, List<Posting>>

        /** 中频词（df=1_000 一档）。 */
        private lateinit var midPostings: Map<String, List<Posting>>

        private lateinit var docs: Map<Long, DocMeta>

        @BeforeClass
        @JvmStatic
        fun buildFixture() {
            val docsMap = HashMap<Long, DocMeta>(N * 2)
            val a = ArrayList<Posting>(N) // 「部署」
            val b = ArrayList<Posting>(N) // 「端口」
            val mids = HashMap<String, MutableList<Posting>>() // 「g0..g99」每组 1_000 条
            for (i in 0 until N) {
                val id = (i + 1).toLong()
                docsMap[id] = DocMeta(msgId = id, sessionId = (i % 50).toLong() + 1, role = "user", ts = 1_700_000_000_000L + i, len = 8)
                a.add(Posting(id, 1 + i % 3))
                b.add(Posting(id, 1 + (i + 1) % 3))
                mids.getOrPut("g${i / 1_000}") { mutableListOf() }.add(Posting(id, 1 + i % 2))
            }
            commonPostings = mapOf("部署" to a, "端口" to b)
            midPostings = mids
            docs = docsMap
        }

        private fun timedSearch(
            postings: Map<String, List<Posting>>,
            query: String,
            filters: SearchFilters = SearchFilters(),
            excluded: Set<Long> = emptySet(),
        ): Pair<Int, Double> {
            val t0 = System.nanoTime()
            val hits = SessionSearchEngine.search(postings, docs, N, 8.0, query, filters, excluded, 20)
            val ms = (System.nanoTime() - t0) / 1_000_000.0
            return hits.size to ms
        }

        /** 三跑取最小（把 JIT 暖机与 GC 抖动从读数里挤出去），断言 500ms 硬闸、实测印进输出给报告抄。 */
        private fun assertWithinBudget(label: String, runs: List<Double>) {
            val best = runs.min()
            val shown = runs.map { "${(it * 10).toLong() / 10.0}ms" }
            println("PERF[$label] 10万条夹具检索实测：min=${(best * 10).toLong() / 10.0}ms runs=$shown")
            assertTrue("判据⑧：$label 实测 ${(best * 10).toLong() / 10.0}ms 超 500ms 硬闸", best <= 500.0)
        }
    }

    @Test
    fun `判据8_10万条_高频词最坏情况_检索在预算内`() {
        val postings = commonPostings
        repeat(2) { timedSearch(postings, "部署 端口") } // 暖机两跑不算数
        val runs = (1..3).map { timedSearch(postings, "部署 端口") }
        runs.forEach { (size, _) -> assertEquals("夹具全库命中，条数打满", 20, size) }
        assertWithinBudget("高频词×2", runs.map { it.second })
    }

    @Test
    fun `判据8_10万条_中频词带filters与排除集_检索在预算内`() {
        val postings = commonPostings + midPostings
        repeat(2) {
            timedSearch(postings, "g0 g1 g2 部署", SearchFilters(fromTs = 1_700_000_000_000L, maxLen = 8), setOf(50L))
        }
        val runs = (1..3).map {
            timedSearch(postings, "g0 g1 g2 部署", SearchFilters(fromTs = 1_700_000_000_000L, maxLen = 8), setOf(50L))
        }
        runs.forEach { (size, _) -> assertEquals("带过滤也要出满条数", 20, size) }
        assertWithinBudget("中频词×3＋filters", runs.map { it.second })
    }
}
