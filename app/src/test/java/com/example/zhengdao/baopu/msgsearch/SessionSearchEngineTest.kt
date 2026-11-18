// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 702 派工单判据（引擎侧，全部纯 Kotlin 直测——JVM 里没有 SQLite，靠的就是算法层零 Android 依赖）：
// ① 关键词检索命中含该词 msgId（3 条夹具）；② filters 三例（时间/会话/角色）生效，越界返回即红；
// ③ BM25 排序：高 tf 分高于低 tf；⑥ 要点 ≤5 条、≤60 字、必带 msgId。
package com.example.zhengdao.baopu.msgsearch

import com.example.zhengdao.baopu.MemoryRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SessionSearchEngineTest {

    /** 一条夹具消息（正文先过仓库既有的分词机，倒排与 len 都从它来——算法层不碰第二套分词）。 */
    private data class Msg(
        val id: Long,
        val ts: Long,
        val text: String,
        val sessionId: Long = 1L,
        val role: String = "user",
    )

    private class Fx(msgs: List<Msg>) {
        val postings = HashMap<String, MutableList<Posting>>()
        val docs = LinkedHashMap<Long, DocMeta>()

        init {
            msgs.forEach { m ->
                val counts = MemoryRules.tokenCountsOf(m.text)
                docs[m.id] = DocMeta(m.id, m.sessionId, m.role, m.ts, counts.values.sum())
                counts.forEach { (t, tf) -> postings.getOrPut(t) { mutableListOf() }.add(Posting(m.id, tf)) }
            }
        }

        fun search(
            query: String,
            filters: SearchFilters = SearchFilters(),
            limit: Int = SessionSearchEngine.DEFAULT_LIMIT,
            excluded: Set<Long> = emptySet(),
        ): List<Scored> = SessionSearchEngine.search(
            postings, docs, docs.size, avgDl(), query, filters, excluded, limit,
        )

        private fun avgDl(): Double =
            if (docs.isEmpty()) 0.0 else docs.values.sumOf { it.len }.toDouble() / docs.size
    }

    private fun msg(vararg pairs: Pair<Long, String>): List<Msg> = pairs.map { Msg(it.first, 1_000L + it.first, it.second) }

    // ── 判据①：关键词检索命中含该词 msgId（3 条夹具） ─────────────────────────

    @Test
    fun `判据1_三条夹具_关键词命中的是含词的msgId_不含词的不回来`() {
        val fx = Fx(
            msg(
                1L to "部署失败了，端口被占用",
                2L to "昨天聊的部署流程还没走完",
                3L to "晚饭吃了火锅，很辣",
            ),
        )
        val hits = fx.search("部署").map { it.doc.msgId }
        assertEquals("含「部署」的 1、2 要命中，3 不许混进来", listOf(1L, 2L), hits)
        assertEquals("换个词命中的是 3", listOf(3L), fx.search("火锅").map { it.doc.msgId })
    }

    @Test
    fun `判据1_命中带msgId与分数_按分数降序`() {
        // 夹具选型有讲究：k1=1.2/b=0.75 下「tf=3 且 dl=5」与「tf=1 且 dl=1」恰好同分（长度归一正好抵掉
        // 词频），所以第二条夹具给成 tf=1 且 dl=3——tf 效果压过长度差，排序才钉得稳
        val fx = Fx(msg(1L to "部署部署部署", 2L to "部署完成"))
        val hits = fx.search("部署")
        assertEquals(2, hits.size)
        assertEquals("tf 高的排前面（判据③同源）", 1L, hits[0].doc.msgId)
        assertTrue("分数降序", hits[0].score > hits[1].score)
    }

    // ── 判据②：filters 三例（时间/会话/角色）生效，越界返回即红 ────────────────

    private val filterFx = Fx(
        listOf(
            Msg(1L, ts = 100L, text = "部署的账号配置", sessionId = 1L, role = "user"),
            Msg(2L, ts = 200L, text = "部署的账号配置", sessionId = 2L, role = "assistant"),
            Msg(3L, ts = 300L, text = "部署的账号配置", sessionId = 2L, role = "user"),
        ),
    )

    @Test
    fun `判据2_时间区间_界外的消息不回来`() {
        assertEquals(listOf(2L, 3L), filterFx.search("部署", SearchFilters(fromTs = 150L)).map { it.doc.msgId })
        assertEquals(listOf(1L, 2L), filterFx.search("部署", SearchFilters(toTs = 250L)).map { it.doc.msgId })
        assertEquals(listOf(2L), filterFx.search("部署", SearchFilters(fromTs = 150L, toTs = 250L)).map { it.doc.msgId })
    }

    @Test
    fun `判据2_会话id_别的会话不回来`() {
        assertEquals(listOf(1L), filterFx.search("部署", SearchFilters(sessionId = 1L)).map { it.doc.msgId })
        assertEquals(listOf(2L, 3L), filterFx.search("部署", SearchFilters(sessionId = 2L)).map { it.doc.msgId })
    }

    @Test
    fun `判据2_角色_别的角色不回来`() {
        assertEquals(listOf(2L), filterFx.search("部署", SearchFilters(role = "assistant")).map { it.doc.msgId })
        assertEquals(listOf(1L, 3L), filterFx.search("部署", SearchFilters(role = "user")).map { it.doc.msgId })
    }

    @Test
    fun `判据2_长度上限_超限的消息不回来`() {
        val len = filterFx.docs.getValue(1L).len
        assertEquals(listOf(1L, 2L, 3L), filterFx.search("部署", SearchFilters(maxLen = len)).map { it.doc.msgId })
        assertEquals("上限压到 len-1 ⇒ 三条全被滤掉（越界返回即红）", emptyList<Scored>(), filterFx.search("部署", SearchFilters(maxLen = len - 1)))
    }

    @Test
    fun `判据2_回收站会话_命中直接滤掉`() {
        assertEquals(emptyList<Scored>(), filterFx.search("部署", excluded = setOf(1L, 2L, 3L)))
        assertEquals(listOf(1L), filterFx.search("部署", excluded = setOf(2L, 3L)).map { it.doc.msgId })
    }

    // ── 判据③：BM25 排序——高 tf 分高于低 tf ─────────────────────────────────

    @Test
    fun `判据3_同df同dl_高tf的分数严格更高`() {
        val base = SessionSearchEngine.bm25TermScore(tf = 1, df = 2, docLen = 7, avgDl = 7.0, docCount = 10)
        val mid = SessionSearchEngine.bm25TermScore(tf = 2, df = 2, docLen = 7, avgDl = 7.0, docCount = 10)
        val high = SessionSearchEngine.bm25TermScore(tf = 3, df = 2, docLen = 7, avgDl = 7.0, docCount = 10)
        assertTrue("tf 1<2<3 ⇒ 分数单调升", base < mid && mid < high)
    }

    @Test
    fun `判据3_端到端_同一批候选里高tf的排第一`() {
        val fx = Fx(msg(1L to "部署部署部署完成", 2L to "部署完成了"))
        assertEquals("tf=3 的排 tf=1 前面", listOf(1L, 2L), fx.search("部署").map { it.doc.msgId })
    }

    @Test
    fun `判据3_稀有词的分高于满地都是的词_idf恒为正`() {
        val fx = Fx(msg(1L to "部署的端口配置", 2L to "部署的端口配置", 3L to "部署的端口配置", 4L to "部署的端口配置"))
        // docCount=4：df=4 的词（满地都是）idf 最小；这里四条同文，两词分同——直接验公式两端的号
        val rare = SessionSearchEngine.bm25TermScore(1, df = 1, docLen = 6, avgDl = 6.0, docCount = 4)
        val common = SessionSearchEngine.bm25TermScore(1, df = 4, docLen = 6, avgDl = 6.0, docCount = 4)
        assertTrue("稀有词 > 满地都是的词", rare > common)
        assertTrue("idf 恒为正（df=N 也不翻负）", common > 0.0)
    }

    // ── 确定性与边界 ──────────────────────────────────────────────────────────

    @Test
    fun `同分按msgId升序_同输入必同序`() {
        val fx = Fx(msg(9L to "部署完成", 2L to "部署完成"))
        assertEquals("分数相同 ⇒ id 小的在前", listOf(2L, 9L), fx.search("部署").map { it.doc.msgId })
        val a = fx.search("部署").map { it.doc.msgId }
        val b = fx.search("部署").map { it.doc.msgId }
        assertEquals("两次调用同序", a, b)
    }

    @Test
    fun `空查询或空库_返回空不炸`() {
        val fx = Fx(msg(1L to "部署完成"))
        assertEquals(emptyList<Scored>(), fx.search("！！！"))
        assertEquals(emptyList<Scored>(), fx.search(""))
        assertEquals(
            emptyList<Scored>(),
            SessionSearchEngine.search(fx.postings, fx.docs, 0, 0.0, "部署"),
        )
    }

    @Test
    fun `limit越界直接断_不许悄悄放行`() {
        val fx = Fx(msg(1L to "部署完成"))
        listOf(0, -1, SessionSearchEngine.MAX_LIMIT + 1).forEach { n ->
            try {
                fx.search("部署", limit = n)
                fail("limit=$n 应当直接断")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("limit"))
            }
        }
    }

    @Test
    fun `查询词超上限_按文本序取前N个`() {
        val terms = SessionSearchEngine.queryTerms("部署端口日志缓存网关队列回放压缩索引快照")
        assertEquals(SessionSearchSql.MAX_QUERY_TOKENS, terms.size)
        assertEquals("靠前的词留下", "部署", terms[0])
    }

    // ── 判据⑥：要点 ≤5 条、≤60 字、必带 msgId ────────────────────────────────

    private fun digestMsg(id: Long, text: String, role: String = "assistant") = DigestMsg(id, role, text)

    @Test
    fun `判据6_要点不超过5条_每条都带msgId`() {
        val msgs = listOf(
            digestMsg(1, "部署失败的根因是端口被占用，把配置改掉之后就恢复了。我们又核对了防火墙规则，确认没有再拦。"),
            digestMsg(2, "好的，明白了"),
            digestMsg(3, "后来把部署脚本也升级了一遍，回滚按钮也补上了。"),
            digestMsg(4, "第二天又跑了一遍全量回归，全部通过。"),
            digestMsg(5, "总结：这次的教训是发布前先查端口占用。"),
            digestMsg(6, "附上当时的部署日志归档，方便以后翻。"),
            digestMsg(7, "下次例会把这个案例讲一遍。"),
        )
        val points = SessionSearchEngine.digest(msgs, max = 5)
        assertTrue("≤5 条，实际 ${points.size}", points.size in 1..5)
        points.forEach { p ->
            assertTrue("每条必带 msgId（缺 id 的话进不来）", p.msgId in 1L..7L)
            assertTrue("每条 ≤60 字，实际 ${p.text.length}", p.text.length <= 60)
            assertTrue("每条非空", p.text.isNotBlank())
        }
    }

    @Test
    fun `判据6_太短的句子不进要点_全部太短就如实返回空`() {
        assertEquals(emptyList<DigestPoint>(), SessionSearchEngine.digest(listOf(digestMsg(1, "好"), digestMsg(2, "知道了"))))
        val points = SessionSearchEngine.digest(listOf(digestMsg(1, "好，但是部署的端口还是被占用的状态呢")))
        assertEquals("长句在、短句丢", 1, points.size)
        assertEquals(1L, points[0].msgId)
    }

    @Test
    fun `判据6_超60字的长句_起窗压缩后仍不超`() {
        // 整句不带任何标点（不会被句切分打断）＝单个候选真的超 60 字，窗口必须出手
        val long = "这一段是一句故意写到超过六十字的长句没有任何逗号句号用来验证窗口逻辑到底会不会把超长的原句压进" +
            "六十个字符以内如果没压进去就是窗口没起作用这一条判据就是红的了"
        assertTrue("夹具本身要超 60，实际 ${long.length}", long.length > 60)
        val points = SessionSearchEngine.digest(listOf(digestMsg(1, long)), max = 1)
        assertEquals(1, points.size)
        assertTrue("压缩后 ≤60，实际 ${points[0].text.length}", points[0].text.length <= 60)
        assertTrue("窗没到句尾 ⇒ 补尾省略号", points[0].text.endsWith("…"))
    }

    @Test
    fun `判据6_近重复的句子只留一条`() {
        val points = SessionSearchEngine.digest(
            listOf(digestMsg(1, "部署失败的根因是端口被占用，部署失败的根因是端口被占用啊。")),
            max = 5,
        )
        assertEquals("两句几乎一样 ⇒ 只留分高的那句", 1, points.size)
    }

    @Test
    fun `判据6_max越界直接断_同输入必同输出`() {
        listOf(0, -1, SessionSearchEngine.DIGEST_MAX + 1).forEach { n ->
            try {
                SessionSearchEngine.digest(listOf(digestMsg(1, "部署的端口占用问题已经解决了")), max = n)
                fail("max=$n 应当直接断")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("max"))
            }
        }
        val msgs = listOf(digestMsg(1, "部署失败的根因是端口被占用，把配置改掉之后就恢复了。"), digestMsg(2, "好的。"))
        assertEquals(SessionSearchEngine.digest(msgs), SessionSearchEngine.digest(msgs))
    }

    // ── snippet：命中词起窗、总长不超、省略号算预算 ─────────────────────────────

    @Test
    fun `snippet_从命中词起窗_总长不超上限_两头按需补省略号`() {
        val text = "前面是一大段铺垫内容用来把命中词往后推，部署的端口占用才是真正要让人一眼看到的重点，后面还有尾巴。" +
            "这一句是特意加长的尾巴内容用来确保窗口真的会截断并补上尾部的省略号，再凑一些字数把总长推过窗口上限才好验证。再加几个字。"
        val anchor = text.indexOf("端口")
        assertTrue("夹具要够长：命中词后身得超过窗宽", text.length > anchor + SessionSearchEngine.SNIPPET_CHARS)
        val s = SessionSearchEngine.snippet(text, setOf("端口"))
        assertTrue("≤80，实际 ${s.length}", s.length <= 80)
        assertTrue("含命中词", s.contains("端口"))
        assertTrue("命中词前面还有内容 ⇒ 补头省略号", s.startsWith("…"))
        assertTrue("后面被截 ⇒ 补尾省略号", s.endsWith("…"))
    }

    @Test
    fun `snippet_没命中词_从句首起窗_短文本原样`() {
        val s = SessionSearchEngine.snippet("很短的一句话", setOf("不存在的词"))
        assertEquals("很短的一句话", s)
        val long = "没有命中词的长文本也要能出一行摘要窗口而不是把整段全塞回去，这一段是特意凑长的填充内容。"
        val s2 = SessionSearchEngine.snippet(long, setOf("不存在"))
        assertTrue(s2.length <= 80)
        assertTrue("没命中 ⇒ 不补头省略号", !s2.startsWith("…"))
    }

    @Test
    fun `windowAround_省略号算进预算_绝不超`() {
        val text = "一二三四五六七八九十".repeat(10)
        listOf(8, 20, 60, 80).forEach { cap ->
            val w = SessionSearchEngine.windowAround(text, 50, cap)
            assertTrue("cap=$cap 时窗长 ${w.length} 超了", w.length <= cap)
        }
    }

    @Test
    fun `折行文本_折叠成一行再起窗`() {
        val s = SessionSearchEngine.snippet("第一行\n第二行有部署\n第三行", setOf("部署"))
        assertTrue("折行变空格", !s.contains("\n"))
        assertTrue(s.contains("部署"))
    }

    // ── tokenCountsOf：分词只有一套（频次视图与去重视图同核） ──────────────────

    @Test
    fun `tokenCountsOf_数的是真词频_与tokensOf同一个切法`() {
        // 「部署部署，部署」＝ 段一（部署部署 → 部署×2＋署部×1）＋ 段二（部署 → ×1）＝ 部署共 3 次
        val counts = MemoryRules.tokenCountsOf("部署部署，部署")
        assertEquals(3, counts["部署"])
        assertEquals(1, counts["署部"])
        assertEquals("去重视图是频次视图的 keys", MemoryRules.tokensOf("部署部署，部署"), counts.keys)
        val text = "Mixed 部署文本 mixed文本 with ABC abc 数字123"
        assertEquals(MemoryRules.tokensOf(text), MemoryRules.tokenCountsOf(text).keys)
    }

    @Test
    fun `tokenCountsOf_跨段落的同一个词各自计数`() {
        assertEquals(2, MemoryRules.tokenCountsOf("部署\n部署")["部署"])
    }
}
