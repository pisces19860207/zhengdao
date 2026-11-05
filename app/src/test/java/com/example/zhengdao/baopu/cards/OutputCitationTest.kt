// 独立开发声明：本文件为本项目原创。
//
// 第四百六十五批 · **产出引依据的判据层**（465 判据①②⑤ + 正常产出）。
// 纯 JVM：临时目录造人设/知识库文件，过闸、删文件、再过闸做「被删」双跑对照。
package com.example.zhengdao.baopu.cards

import com.example.zhengdao.core.TimeFormats
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OutputCitationTest {

    @get:Rule val tmp = TemporaryFolder()

    private val nowMs = 1_700_000_000_000L

    private val messages = CitationMessages(
        prefix = "依据：",
        emptyHint = "先读人设/知识库",
        ledgerHint = "台账只用于查重，不能当依据",
        badRefHint = "依据来源要写文件:行 或 URL+日期",
        danglingHint = "依据指向的条目不存在：%1\$s",
    )

    private val always: (Citation) -> Boolean = { true }

    private fun persona(): File = tmp.newFile("persona.md").also {
        it.writeText(
            "## 语气\n- 短句，不铺陈\n- 少用感叹号\n## 习惯\n- 先给结论\n",
            Charsets.UTF_8,
        )
    }

    private fun knowledge(name: String = "kb.md"): File = tmp.newFile(name).also {
        it.writeText("# 城市笔记\n菜市场清晨最热闹。\n", Charsets.UTF_8)
    }

    /** 与生产同一口径（:core TimeFormats 是唯一真源），不另造一份日期算法。 */
    private fun dateOf(ms: Long): String = TimeFormats.dayStamp(ms)

    private fun view(
        persona: File?,
        knowledge: List<File> = emptyList(),
        hits: List<CitationWebHit> = emptyList(),
    ): CitationSourceView {
        val docs = ArrayList<CitationSourceDoc>(knowledge.size + 1)
        if (persona != null) docs += CitationSourceDoc(PERSONA_REL, persona)
        knowledge.forEachIndexed { i, f ->
            docs += CitationSourceDoc("知识库/整理/笔记$i.md", f)
        }
        return CitationSourceView(
            docs = docs,
            personaRel = if (persona != null) PERSONA_REL else null,
            webHits = hits,
        )
    }

    private fun card(): ResultCardSpec =
        ResultCardSpec("write.topic", "选题方案", emptyList(), emptyList())

    private fun hit(url: String = "https://news.example/hot"): CitationWebHit =
        CitationWebHit(url, "近日热点", "街口新开了一家早点铺", nowMs)

    private fun reject(verdict: CitationVerdict): CitationVerdict.Reject {
        assertTrue("应当是拒：$verdict", verdict is CitationVerdict.Reject)
        return verdict as CitationVerdict.Reject
    }

    private fun pass(verdict: CitationVerdict): CitationVerdict.Pass {
        assertTrue("应当是通过：$verdict", verdict is CitationVerdict.Pass)
        return verdict as CitationVerdict.Pass
    }

    // ── 判据①：依据为空 ⇒ 不许出卡 ────────────────────────────────────────

    @Test
    fun `判据一_依据为空_闸拒且提示先读人设知识库`() {
        val r = reject(OutputCitationGate.check(emptyList(), always, messages))
        assertEquals(CitationReject.EMPTY, r.reason)
        assertEquals("先读人设/知识库", r.detail)
    }

    @Test
    fun `判据一_依据为空_出卡口状态原样且一张卡都不加`() {
        val before = ResultCardState()
        val a = OutputCards.push(before, card(), emptyList(), always, messages)
        assertEquals(before, a.state)
        assertTrue(a.verdict is CitationVerdict.Reject)
    }

    @Test
    fun `判据一_卡区已有卡_依据为空也不许并排再出一张`() {
        val before = ResultCards.push(ResultCardState(), card().copy(title = "旧卡"))
        val a = OutputCards.push(before, card().copy(title = "新卡"), emptyList(), always, messages)
        assertEquals(before, a.state)
        assertEquals(1, a.state.cards.size)
    }

    // ── 判据②：指向的条目不存在 ⇒ 拒并说明 ────────────────────────────────

    @Test
    fun `判据二_指向不存在条目_拒且说明是哪一条`() {
        val c = Citation(CitationKind.PERSONA, "$PERSONA_REL:7", "人设第 7 条")
        val r = reject(OutputCitationGate.check(listOf(c), { false }, messages))
        assertEquals(CitationReject.DANGLING, r.reason)
        assertTrue(r.detail.contains("$PERSONA_REL:7"))
    }

    @Test
    fun `判据二_条目被删_同一组依据先绿后红`() {
        val f = persona()
        val v = view(f)
        val citations = OutputCitationSources.collect(v)
        pass(OutputCitationGate.check(citations, OutputCitationSources.exists(v), messages))
        assertTrue("测试要真的把文件删掉", f.delete())
        val r = reject(OutputCitationGate.check(citations, OutputCitationSources.exists(v), messages))
        assertEquals(CitationReject.DANGLING, r.reason)
    }

    @Test
    fun `判据二_行号越界_打不开`() {
        val v = view(persona())
        assertFalse(OutputCitationSources.fileLineExists("$PERSONA_REL:999", v.docs))
    }

    @Test
    fun `判据二_没登记过的文件_打不开`() {
        val v = view(persona())
        assertFalse(OutputCitationSources.fileLineExists("别的路径/文件.md:1", v.docs))
    }

    @Test
    fun `判据二_空文档不产依据`() {
        val blank = tmp.newFile("blank.md").also { it.writeText("\n\n", Charsets.UTF_8) }
        val citations = OutputCitationSources.collect(view(persona(), listOf(blank)))
        assertFalse(citations.any { it.kind == CitationKind.KNOWLEDGE })
    }

    @Test
    fun `判据二_搜索URL不在本轮结果里_打不开`() {
        val v = view(null, hits = listOf(hit()))
        assertFalse(
            OutputCitationSources.webExists(
                "https://news.example/other ${dateOf(nowMs)}", v.webHits,
            ),
        )
    }

    @Test
    fun `判据二_搜索日期对不上抓到那天_打不开`() {
        val v = view(null, hits = listOf(hit()))
        assertTrue(
            OutputCitationSources.webExists("https://news.example/hot ${dateOf(nowMs)}", v.webHits),
        )
        assertFalse(
            OutputCitationSources.webExists("https://news.example/hot 1999-01-01", v.webHits),
        )
    }

    // ── 判据⑤：不存在「引了依据但内容来自旧台账」的路径 ────────────────────

    @Test
    fun `判据五_台账当依据_拒且说明只用于查重`() {
        val c = Citation(CitationKind.PERSONA, "产物/台账.md:3", "台账 第 3 行")
        val r = reject(OutputCitationGate.check(listOf(c), always, messages))
        assertEquals(CitationReject.LEDGER_SOURCE, r.reason)
        assertTrue(r.detail.contains("台账只用于查重"))
    }

    @Test
    fun `判据五_换个kind把台账写进display_仍拒`() {
        val c = Citation(CitationKind.KNOWLEDGE, "知识库/整理/a.md:1", "知识库 台账摘录")
        assertEquals(
            CitationReject.LEDGER_SOURCE,
            reject(OutputCitationGate.check(listOf(c), always, messages)).reason,
        )
    }

    @Test
    fun `判据五_台账引用自称存在也拒_形状闸先于存在性`() {
        val c = Citation(CitationKind.WEB, "https://news.example/hot ${dateOf(nowMs)}", "台账热点")
        assertEquals(
            CitationReject.LEDGER_SOURCE,
            reject(OutputCitationGate.check(listOf(c), always, messages)).reason,
        )
    }

    @Test
    fun `判据五_依据闭集只有三类_没有台账这一种`() {
        assertEquals(
            setOf("PERSONA", "KNOWLEDGE", "WEB"),
            CitationKind.values().map { it.name }.toSet(),
        )
    }

    @Test
    fun `判据五_收集器只认人设知识库搜索_收不出台账`() {
        val citations = OutputCitationSources.collect(view(persona(), listOf(knowledge())))
        assertTrue(citations.isNotEmpty())
        assertTrue(citations.all { it.kind != CitationKind.WEB })
        assertFalse(citations.any { CitationRefs.isLedger(it) })
    }

    @Test
    fun `判据五_台账依据走到出卡口_状态原样`() {
        val before = ResultCardState()
        val c = Citation(CitationKind.PERSONA, "产物/台账.md:1", "台账 第 1 行")
        val a = OutputCards.push(before, card(), listOf(c), always, messages)
        assertEquals(before, a.state)
        assertEquals(CitationReject.LEDGER_SOURCE, reject(a.verdict).reason)
    }

    // ── 正常产出 ──────────────────────────────────────────────────────────

    @Test
    fun `正常产出_三类依据齐全_过闸且卡面出现依据行`() {
        val v = view(persona(), listOf(knowledge()), listOf(hit()))
        val citations = OutputCitationSources.collect(v)
        assertEquals(3, citations.map { it.kind }.toSet().size)
        val p = pass(OutputCitationGate.check(citations, OutputCitationSources.exists(v), messages))
        assertTrue(p.line.startsWith("依据："))
        assertTrue(p.line.contains("人设第 1 条"))
        assertTrue(p.line.contains("知识库"))
        assertTrue(p.line.contains("搜索"))
    }

    @Test
    fun `正常产出_卡面行前缀在最前_多条用顿号连接`() {
        val a = Citation(CitationKind.PERSONA, "a.md:1", "甲")
        val b = Citation(CitationKind.KNOWLEDGE, "b.md:1", "乙")
        assertEquals("依据：甲、乙", OutputCitationGate.line(listOf(a, b), "依据："))
    }

    @Test
    fun `正常产出_出卡口把依据与那一行贴到卡上`() {
        val v = view(persona(), listOf(knowledge()))
        val citations = OutputCitationSources.collect(v)
        val a = OutputCards.push(ResultCardState(), card(), citations, OutputCitationSources.exists(v), messages)
        assertEquals(1, a.state.cards.size)
        assertEquals(citations, a.state.cards[0].citations)
        assertEquals(pass(a.verdict).line, a.state.cards[0].citationLine)
        assertTrue(a.state.cards[0].citationLine!!.startsWith("依据："))
    }

    @Test
    fun `正常产出_点开看的原文就是文件里那一行`() {
        val citations = OutputCitationSources.collect(view(persona()))
        val first = citations.first { it.kind == CitationKind.PERSONA }
        assertEquals("短句，不铺陈", first.excerpt)
        assertEquals("$PERSONA_REL:2", first.ref)
    }

    @Test
    fun `正常产出_人设括号里的小节名从文件读_机制不写死人格`() {
        val first = OutputCitationSources.collect(view(persona())).first()
        assertTrue("小节名应当来自文件的 ## 语气：${first.display}", first.display.contains("语气"))
    }

    @Test
    fun `正常产出_搜索依据的日期由抓取时刻换算`() {
        val citations = OutputCitationSources.collect(view(null, hits = listOf(hit())))
        val web = citations.single { it.kind == CitationKind.WEB }
        assertEquals("https://news.example/hot ${dateOf(nowMs)}", web.ref)
    }

    @Test
    fun `正常产出_收集有上限_不刷屏`() {
        val many = tmp.newFile("many.md").also {
            it.writeText((1..12).joinToString("\n") { "- 第 $it 条" }, Charsets.UTF_8)
        }
        val v = view(many, (1..5).map { knowledge("kb$it.md") }, (1..5).map { hit("https://n.example/$it") })
        val citations = OutputCitationSources.collect(v)
        assertEquals(OutputCitationSources.MAX_PERSONA, citations.count { it.kind == CitationKind.PERSONA })
        assertEquals(OutputCitationSources.MAX_KNOWLEDGE, citations.count { it.kind == CitationKind.KNOWLEDGE })
        assertEquals(OutputCitationSources.MAX_WEB, citations.count { it.kind == CitationKind.WEB })
    }

    // ── 引用形状 ──────────────────────────────────────────────────────────

    @Test
    fun `形状_人设没写行号_BAD_REF`() {
        val c = Citation(CitationKind.PERSONA, "人设.md", "甲")
        assertEquals(CitationReject.BAD_REF, CitationRefs.shapeProblem(c)?.first)
    }

    @Test
    fun `形状_搜索缺日期_BAD_REF`() {
        val c = Citation(CitationKind.WEB, "https://news.example/hot", "甲")
        assertEquals(CitationReject.BAD_REF, CitationRefs.shapeProblem(c)?.first)
    }

    @Test
    fun `形状_行号写0_不合法`() {
        assertEquals(null, CitationRefs.fileLine("a.md:0"))
    }

    @Test
    fun `形状_文件行_解析出路径与行号`() {
        assertEquals("知识库/整理/a.md" to 12, CitationRefs.fileLine("知识库/整理/a.md:12"))
    }

    @Test
    fun `形状_URL加日期_解析出两段`() {
        assertEquals("https://news.example/hot" to "2026-11-05",
            CitationRefs.webRef("https://news.example/hot 2026-11-05"))
    }

    @Test
    fun `形状_百度式相对路径不含URL的WEB_拒`() {
        val c = Citation(CitationKind.WEB, "news.example/hot 2026-11-05", "甲")
        assertEquals(CitationReject.BAD_REF, CitationRefs.shapeProblem(c)?.first)
    }

    @Test
    fun `查询类结果_不带依据照旧走ResultCards_不受产出闸影响`() {
        val c = card()
        val state = ResultCards.push(ResultCardState(), c)
        assertEquals(1, state.cards.size)
        assertEquals(emptyList<Citation>(), state.cards[0].citations)
        assertEquals(null, state.cards[0].citationLine)
    }

    private companion object {
        const val PERSONA_REL = "抱朴记忆/人设-本项目.md"
    }
}
