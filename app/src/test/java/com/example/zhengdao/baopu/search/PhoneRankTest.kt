// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十八批（派工单 §1③ ＋ §2）：**分词与打分的算式层直测**（JVM 表驱动，零 Android、零 SQL）。
// 派工单 §2 原话「分词/打分/指纹的 JVM 表驱动测……这是选 B 方案的全部理由，不许只写真机验」
// 里最难的一条就是这里：真机上「搜得到搜不到」只给一个二值读数，而口径对不对**必须**能在一张表上
// 逐个打分对账——`search.db` 没有 FTS5，分数是我们自己算的，算错了没有任何东西会响。
//
// ## 三条主判据
// 1. **切词一把尺子**：`PhoneRank.split` 只许调 `MemoryRules.tokensOf`（Memory.kt:164）。
//    建索引与查询用不同切词 ⇒ 词表对不上、搜不到还查不出原因。行为上打不出这种错，
//    所以 [结构_切词不重算] 用剥掉注释的源码文本钉住。
// 2. **打分与 `KbRetriever.score` 行为等价**（[行为等价_词是整词时两边同分]）：
//    同一个 `KbChunk` 现跑两边比对，不是「两边都写了 ×3／×1 所以自称一样」。
// 3. **差别只在「半个词」，方向是少召回**（[表驱动_半个词的差别_只少不多]）：
//    倒排命中整 token，`KbRetriever` 命中子串。这条不许被说成「完全等价」，
//    也不许反过来出现「倒排多给分」——那意味着同一份内容换个入口换个名次。
package com.example.zhengdao.baopu.search

import com.example.zhengdao.baopu.MemoryRules
import com.example.zhengdao.baopu.kb.KbChunk
import com.example.zhengdao.baopu.kb.KbRetriever
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneRankTest {

    // ── 1. 切词：唯一入口（§1③「分词复用 MemoryRules.tokensOf」） ────────────────

    @Test
    fun `表驱动_切词口径与记忆检索同一个答案`() {
        // 这一条不是「重跑 MemoryRules 的单测」，是钉住**本包没换尺子**：
        // 同一串文本，PhoneRank 给的词集必须与 MemoryRules.tokensOf 逐字相同。
        listOf(
            "抱朴存真",
            "Kotlin Guide 中文混排 abc123",
            "第 1 章：范围（含）与 预算",
            "ALLCAPS 大小写不敏感",
            "，。！？、；：「」『』（）…—·",
            "",
            "   \n\t ",
        ).forEach { text -> assertEquals("切词换了尺子：'$text'", MemoryRules.tokensOf(text), PhoneRank.split(text)) }
    }

    @Test
    fun `表驱动_CJK二字元与ASCII连续段`() {
        // 单字 CJK 不成词（噪声）；ASCII 连续段整体一个词并小写——这两条决定了「半个词搜不到」。
        assertEquals(setOf("抱朴", "朴存", "存真"), PhoneRank.split("抱朴存真"))
        assertEquals(setOf("kotlin"), PhoneRank.split("Kotlin"))
        assertEquals(setOf("抱朴", "kotlin"), PhoneRank.split("抱朴 Kotlin"))
        // 标点切开：跨标点的二字元不存在
        assertFalse(PhoneRank.split("抱，朴").contains("抱朴"))
        assertEquals(setOf("抱", "朴"), PhoneRank.split("抱，朴"))
    }

    @Test
    fun `结构_切词不重算`() {
        val src = code(sourceOf("PhoneRank.kt"))
        assertTrue("split 必须直接调 MemoryRules.tokensOf", src.contains("MemoryRules.tokensOf("))
        assertTrue("查询侧必须走同一个 tokensOf（经 tokensOfQuery）", src.contains("MemoryRules.tokensOfQuery("))
        // 本包不许自己长出 bigram／小写段逻辑：长出一套就有第二把尺子（这三段是 tokensOf 的算式本尊）。
        listOf("substring(i, i + 2)", "isWhitespace", "code < 128").forEach { fragment ->
            assertFalse("PhoneRank 里出现了重新实现切词的片段：$fragment", src.contains(fragment))
        }
    }

    // ── 2. 查询词：上限与截断可见（§1③ ＋ §2「静默截断」） ───────────────────────

    @Test
    fun `表驱动_查询词截到上限并且说明截了`() {
        val cap = PhoneRank.MAX_TERMS
        assertEquals("绑参上限与 selectSearch 的 require 同一个数", SearchSql.MAX_TOKENS, cap)
        // 20 个不同汉字 ⇒ 19 个二字元，超过 16 ⇒ 留**靠前**的 16 个（用户打的那几个字通常就在前面）
        val text = (0 until 20).map { String(Character.toChars(0x4E00 + it)) }.joinToString("")
        val all = PhoneRank.split(text).toList()
        assertTrue("19 个词才够触发截断，实得 ${all.size}", all.size > cap)
        val terms = PhoneRank.termsOf(text)
        assertEquals(cap, terms.tokens.size)
        assertEquals("留下的是文本顺序的前 $cap 个", all.take(cap), terms.tokens)
        assertTrue("截断必须报出来（界面要显示「查询词已截断」）", terms.truncated)
    }

    @Test
    fun `表驱动_短查询不截断`() {
        val terms = PhoneRank.termsOf("抱朴")
        assertFalse(terms.truncated)
        assertEquals(listOf("抱朴"), terms.tokens)
        assertFalse(terms.isEmpty)
    }

    @Test
    fun `表驱动_空查询与全标点给空表`() {
        // 空表 ⇒ 薄壳根本不查库（也不记日志）：「列出所有文档」不属于本批的检索面。
        listOf("" to "空串", "   " to "全空白", "，。！？" to "全标点").forEach { (text, label) ->
            val terms = PhoneRank.termsOf(text)
            assertTrue("$label 必须给空表", terms.isEmpty)
            assertEquals(0, terms.tokens.size)
            assertFalse("$label 不许报截断（根本没词）", terms.truncated)
        }
    }

    // ── 3. 打分：与 KbRetriever 口径行为等价（§1③「打分复用 KbRetriever 口径」） ──

    @Test
    fun `行为等价_词是整词时两边同分`() {
        val cases = listOf(
            Triple("抱朴存真", "去伪存真，抱朴守一", "抱朴"),
            Triple("Kotlin Guide", "kotlin 与 guide 都在正文", "kotlin"),
            Triple("范围与预算", "范围口径写在正文里", "范围"),
            Triple("倒排索引设计", "设计倒排索引的四个上限", "排索"),
            Triple("标题", "正文正文", "正文"),
            Triple("抱朴存真", "一句没有", "抱朴"),
            Triple("证道中心 使用手册", "第 3 章 证道中心 的 使用 方法", "证道"),
        )
        cases.forEach { (title, body, query) ->
            val terms = PhoneRank.termsOf(query).tokens.toSet()
            val mine = PhoneRank.scoreOf(title, body, terms)
            val theirs = KbRetriever.score(
                KbChunk(relPath = "a.md", lineStart = 1, lineEnd = 2, heading = title, text = body), terms,
            )
            assertTrue("$query 在标题 '$title' 里没有整词命中，比对无意义", mine > 0 && theirs > 0)
            assertEquals("title=$title body=$body query=$query 两边分数必须同一个", theirs, mine)
        }
    }

    @Test
    fun `表驱动_权重就是标题3正文1`() {
        // 单独把两个权重钉死：与 `KbRetriever.score` 里的 `s += 3`／`s += 1` 同数。
        assertEquals(3, PhoneRank.TITLE_WEIGHT)
        assertEquals(1, PhoneRank.BODY_WEIGHT)
        assertEquals(3, PhoneRank.scoreOf("抱朴", "别的字", setOf("抱朴")))
        assertEquals(1, PhoneRank.scoreOf("别的字", "抱朴在这里", setOf("抱朴")))
        assertEquals(4, PhoneRank.scoreOf("抱朴", "抱朴也在正文", setOf("抱朴")))
        // 一个词最多计两次（标题＋正文），**不随出现次数累加**——这是 KbRetriever 的口径，不是 tf
        assertEquals(4, PhoneRank.scoreOf("抱朴 抱朴 抱朴", "抱朴抱朴抱朴", setOf("抱朴")))
        assertEquals(0, PhoneRank.scoreOf("抱朴", "抱朴", emptySet()))
        assertEquals(0, PhoneRank.scoreOf("", "", setOf("")))
    }

    @Test
    fun `表驱动_多词查询逐个累加`() {
        val title = "范围 与 预算"
        val body = "范围的口径 预算的口径"
        assertEquals(4, PhoneRank.scoreOf(title, body, setOf("范围")))
        assertEquals(8, PhoneRank.scoreOf(title, body, setOf("范围", "预算")))
        // Set 语义：同一个词写两遍不多算（查询词表本来就是集合）
        assertEquals(4, PhoneRank.scoreOf(title, body, setOf("范围", "范围")))
        // 多一个词只增不减：分数单调，否则「够一屏就停」会因词序换名次
        assertTrue(PhoneRank.scoreOf(title, body, setOf("范围", "预算")) >= PhoneRank.scoreOf(title, body, setOf("范围")))
    }

    @Test
    fun `表驱动_半个词的差别_只少不多`() {
        // 派工单说「复用 KbRetriever 口径」，这条是**必须承认的差别**：
        // 倒排命中整 token，KbRetriever 命中子串。`kot` 想搜 `kotlin`：KbRetriever 给 3 分，
        // 倒排给 0 行（词表里根本没有 "kot"）。方向只能是这样——反过来出现「倒排多给分」
        // 就是同一份内容换个入口换个名次。
        val cases = listOf(
            Triple("kotlin 指南", "kotlin 正文", "kot"),
            Triple("抱朴存真", "正文", "抱"), // 单字：子串命中，词表里没有单字
            Triple("倒排索引", "正文", "倒排索"), // 跨在 bigram 之间的半个词
        )
        cases.forEach { (title, body, query) ->
            val terms = setOf(query)
            val mine = PhoneRank.scoreOf(title, body, terms)
            val theirs = KbRetriever.score(
                KbChunk(relPath = "a.md", lineStart = 1, lineEnd = 1, heading = title, text = body), terms,
            )
            assertTrue("词=$query 倒排给 $mine、KbRetriever 给 $theirs：差别必须是**少召回**", mine <= theirs)
        }
        // 三条确实都差（否则本用例是空跑，钉不住任何东西）
        assertEquals(0, PhoneRank.scoreOf("kotlin 指南", "kotlin 正文", setOf("kot")))
        assertEquals(
            "KbRetriever 那侧：半个词也算命中 ⇒ 标题 3 ＋ 正文 1",
            4,
            KbRetriever.score(KbChunk(relPath = "a", lineStart = 1, lineEnd = 1, heading = "kotlin 指南", text = "kotlin 正文"), setOf("kot")),
        )
    }

    // ── 4. 入库时的 cnt：×3／×1 折进倒排行数（§1③ 的实现方式） ────────────────────

    @Test
    fun `表驱动_cnt等于标题三加正文一`() {
        val c = PhoneRank.tokenCounts("抱朴存真", "去伪存真，抱朴守一")
        assertFalse(c.truncated)
        assertEquals("标题＋正文 ⇒ 3+1", 4, c.counts.getValue("抱朴"))
        assertEquals("只在标题 ⇒ 3", 3, c.counts.getValue("朴存"))
        assertEquals("存真：标题里有、正文的 bigram 里也有 ⇒ 3+1", 4, c.counts.getValue("存真"))
        assertEquals("只在正文 ⇒ 1", 1, c.counts.getValue("伪存"))
        // SUM(cnt)（`SearchSql.selectSearch`，本批一字未改）算出来的就是这个分数 ⇒ 查询侧不再加权
        assertEquals(4, PhoneRank.termsOf("抱朴").tokens.sumOf { c.counts[it] ?: 0 })
    }

    @Test
    fun `表驱动_一篇最多四千零九十六个词`() {
        val cap = PhoneRank.MAX_DOC_TOKENS
        assertEquals(4096, cap)
        // 标题先切 ⇒ 标题的词一定活下来；正文按文本顺序取前 cap 个
        val distinct = (0 until (cap + 500)).map { String(Character.toChars(0x4E00 + it)) }.joinToString("")
        val c = PhoneRank.tokenCounts("标题词", distinct)
        assertTrue("超上限必须报截断（派工单 §2「静默截断」红在这里）", c.truncated)
        assertEquals(cap, c.counts.size)
        assertTrue("标题的词不许被截掉（截的是正文靠后的部分）", c.counts.containsKey("标题"))
        assertEquals("标题独占的词只有 ×3：正文里根本没有这两个字", 3, c.counts.getValue("标题"))
    }

    @Test
    fun `表驱动_正文只切前十二万字符`() {
        val clip = PhoneRank.BODY_INDEX_MAX_CHARS
        assertEquals(120_000, clip)
        val body = "字".repeat(clip) + "麒麟"
        val c = PhoneRank.tokenCounts("", body)
        assertTrue("按字符数截过 ⇒ 必须报截断", c.truncated)
        assertFalse("12 万字之外的词不许进倒排（否则冷扫峰值内存就是它）", c.counts.containsKey("麒麟"))
        // 正好卡在边界不截
        val exact = PhoneRank.tokenCounts("", "字".repeat(clip))
        assertFalse(exact.truncated)
        assertTrue(exact.counts.containsKey("字字"))
    }

    @Test
    fun `表驱动_空文本切不出词`() {
        listOf("" to "空串", "，。" to "全标点", "   " to "空白").forEach { (text, label) ->
            val c = PhoneRank.tokenCounts(text, text)
            assertEquals("$label 不许有词", 0, c.counts.size)
            assertFalse("$label 不许报截断", c.truncated)
        }
    }

    // ── 5. 排序口径：与 SQL 的 ORDER BY 逐字同一个（§1③） ─────────────────────────

    @Test
    fun `表驱动_同分按路径升序`() {
        val hits = listOf(
            TermHit(1L, "b.md", 5),
            TermHit(1L, "a.md", 5),
            TermHit(2L, "z.md", 9),
            TermHit(1L, "c.md", 5),
        )
        val ranked = PhoneRank.rank(hits)
        assertEquals("分数降序、同分按 rel_path 升序（不靠 rowid 的偶然顺序）", listOf("z.md", "a.md", "b.md", "c.md"), ranked.map { it.relPath })
        assertEquals(9, ranked.first().score)
        // root_id 不参与排序 ⇒ 跨根的同分命中一定按路径定序（两处入口给出同一个第 1 名的保证）
        assertEquals(listOf(2L, 1L, 1L, 1L), ranked.map { it.rootId })
    }

    @Test
    fun `表驱动_limit截的是名次不是随机`() {
        val hits = (1..50).map { TermHit(1L, "f%02d.md".format(it), 100 - it) }
        assertEquals(10, PhoneRank.rank(hits, 10).size)
        assertEquals("f01.md", PhoneRank.rank(hits, 10).first().relPath)
        assertEquals(1, PhoneRank.rank(hits, 1).size)
        assertEquals(50, PhoneRank.rank(hits, SearchSql.MAX_LIMIT).size)
        assertThrows(IllegalArgumentException::class.java) { PhoneRank.rank(hits, 0) }
        assertThrows(IllegalArgumentException::class.java) { PhoneRank.rank(hits, -1) }
    }

    @Test
    fun `结构_排序口径两处同序`() {
        val sql = sourceOf("SearchSql.kt")
        assertTrue("SQL 侧必须按分数降序、同分按 rel_path 升序", sql.contains("ORDER BY score DESC, d.rel_path LIMIT"))
        val rank = code(sourceOf("PhoneRank.kt"))
        assertTrue("内存侧必须同一个序", rank.contains("compareByDescending<TermHit> { it.score }"))
        assertTrue("内存侧同分必须按路径升序", rank.contains(".thenBy { it.relPath }"))
    }

    @Test
    fun `结构_权重不进口径外的地方`() {
        // 分数折进 `tok.cnt` ⇒ selectSearch **没有**权重参数；这条把「没改那条语句的形状」钉住。
        val sql = SearchSql.selectSearch(3, 40)
        assertEquals(3, Regex("\\?").findAll(sql).count())
        assertFalse("SQL 文本里不许出现乘法（权重只在建索引时算）", sql.contains("*"))
        assertTrue(sql.contains("AND d.status = 'OK'"))
        assertThrows(IllegalArgumentException::class.java) { SearchSql.selectSearch(SearchSql.MAX_TOKENS + 1) }
        assertEquals(SearchSql.MAX_TOKENS, PhoneRank.MAX_TERMS)
    }

    /** 本文件的源码文本门禁不吃注释：注释里会出现被判的片段本身（房型同 `SearchAdmissionTest`）。 */
    private fun sourceOf(name: String): String = repoFile("src/main/java/com/example/zhengdao/baopu/search/$name").readText()

    private fun code(text: String): String =
        text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    /** 从工作目录逐级向上找仓库里的相对路径（同 `SearchAdmissionTest` 的房型）。 */
    private fun repoFile(rel: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            listOf(File(dir, "app/$rel"), File(dir, rel)).firstOrNull { it.exists() }?.let { return it }
            dir = dir.parentFile
        }
        throw AssertionError("找不到 $rel（工作目录 ${File(".").absolutePath}）")
    }
}
