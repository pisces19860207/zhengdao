// 独立开发声明：本文件为本项目原创。
//
// 616 · **引据缓存的来源域**（判据①②③④：夹具、对照、跨域断言、只读测试）。
// 现场（570/569）：debug 预览页 previewAdmission 往进程级 CitationHitCache 灌示例数据
// （news.example/hot，2023-11-15），之后正式出卡带上这条假依据，而且**过了闸**——
// 不崩、不红、不报错，只让产物带假证据。本文件把真序列钉成夹具：先跑真预览准入
// （CitationPreviewAdmission＝Activity 实际在跑的那段，debug 源集），再走正式出卡口，
// 卡上不许出现示例引据；对照夹具证明生产路径照常出依据。
package com.example.zhengdao.baopu.cards

import com.example.zhengdao.core.TimeFormats
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OutputCitationCacheDomainTest {

    @get:Rule val tmp = TemporaryFolder()

    private val nowMs = 1_700_000_000_000L

    private val messages = CitationMessages(
        prefix = "依据：",
        emptyHint = "先读人设/知识库",
        ledgerHint = "台账只用于查重，不能当依据",
        badRefHint = "依据来源要写文件:行 或 URL+日期",
        danglingHint = "依据指向的条目不存在：%1\$s",
    )

    /** 预览页灌的示例引据（缺陷现场那条）。 */
    private val sampleUrl = "https://news.example/hot"

    /** 生产检索才会有的真引据（对照夹具用）。 */
    private val realUrl = "https://zh.wikipedia.org/wiki/早点铺"

    // ── 判据④：只读测试——预览写入后，生产读不到 ─────────────────────────

    @Test
    fun `只读_预览写入后生产读不到`() {
        CitationHitCache.record(listOf(previewHit()), CitationDomain.PREVIEW)
        assertFalse(
            "生产快照不许看到示例数据（570/569 缺陷现场）",
            productionUrls().any { it.startsWith(sampleUrl) },
        )
        // 预览自己的域还在：预览页不是黑洞，它自己仍要显示示例引据
        assertTrue(
            CitationHitCache.snapshot(CitationDomain.PREVIEW).any { it.url == sampleUrl },
        )
    }

    @Test
    fun `只读_双向_生产写入预览也读不到`() {
        CitationHitCache.record(listOf(productionHit()), CitationDomain.PRODUCTION)
        assertTrue(CitationHitCache.snapshot(CitationDomain.PRODUCTION).any { it.url == realUrl })
        assertFalse(
            CitationHitCache.snapshot(CitationDomain.PREVIEW).any { it.url == realUrl },
        )
    }

    @Test
    fun `键带来源域_各域各一份_互不清互不可见`() {
        CitationHitCache.record(listOf(previewHit()), CitationDomain.PREVIEW)
        CitationHitCache.record(listOf(productionHit()), CitationDomain.PRODUCTION)
        assertTrue(CitationHitCache.snapshot(CitationDomain.PREVIEW).any { it.url == sampleUrl })
        assertTrue(CitationHitCache.snapshot(CitationDomain.PRODUCTION).any { it.url == realUrl })
        assertFalse(CitationHitCache.snapshot(CitationDomain.PRODUCTION).any { it.url == sampleUrl })
        assertFalse(CitationHitCache.snapshot(CitationDomain.PREVIEW).any { it.url == realUrl })
    }

    @Test
    fun `来源域默认生产_预览必须显式点名`() {
        assertEquals(CitationDomain.PRODUCTION, CitationWebHit(realUrl, "t", "s", 1L).source)
        assertEquals(CitationDomain.PREVIEW, previewHit().source)
    }

    // ── 判据③：出卡时校验依据来源——示例/预览引据一律拒（负例）────────────

    @Test
    fun `跨来源不可命中_出卡存在性只认本视图的域`() {
        // 污染形态：示例 hit 被塞进生产视图（哪怕只是手滑拼出一个混域列表）
        val v = view(null, hits = listOf(previewHit(), productionHit()))
        val exists = OutputCitationSources.exists(v)
        assertTrue("本域（生产）引据照常验得上", exists(Citation(CitationKind.WEB, "$realUrl ${dateOf(nowMs)}", "搜索")))
        assertFalse("示例引据过不了存在性", exists(Citation(CitationKind.WEB, "$sampleUrl ${dateOf(nowMs)}", "搜索")))
    }

    @Test
    fun `出卡负例_示例引据不进卡面_塞进出卡口也被拒`() {
        val v = view(persona(), hits = listOf(previewHit(), productionHit()))
        // 收集层：示例引据根本不进卡面；同批的生产引据照常
        val citations = OutputCitationSources.collect(v)
        assertFalse("示例引据不进卡面", citations.any { it.ref.startsWith(sampleUrl) })
        assertTrue("生产引据照常", citations.any { it.ref.startsWith(realUrl) })
        // 出卡口：哪怕被手工塞进来，存在性也过不去 ⇒ 拒、点名、一张卡都不出
        val fake = Citation(CitationKind.WEB, "$sampleUrl ${dateOf(nowMs)}", "搜索 近日热点", "街口新开早点铺")
        val a = OutputCards.push(ResultCardState(), card(), listOf(fake), OutputCitationSources.exists(v), messages)
        assertEquals("拒时一张卡都不出", ResultCardState(), a.state)
        val r = reject(a.verdict)
        assertEquals(CitationReject.DANGLING, r.reason)
        assertTrue("拒要指名道姓：${r.detail}", r.detail.contains(sampleUrl))
    }

    // ── 判据①：夹具——先开 debug 预览、再走正式出卡 ⇒ 卡上无示例引据 ──────

    @Test
    fun `夹具_先开预览再正式出卡_卡上无示例引据`() {
        // 第一幕：开一次 debug 预览（真准入路径，就是 Activity 跑的那段逻辑）
        CitationPreviewAdmission.run(tmp.newFolder("preview"), messages, nowMs)
        // 第二幕：正式出卡（生产域快照 + 生产文档；对照夹具有人设与知识库）
        val v = view(persona(), listOf(knowledge()))
        val citations = OutputCitationSources.collect(v)
        val a = OutputCards.push(ResultCardState(), card(), citations, OutputCitationSources.exists(v), messages)
        val p = pass(a.verdict)
        assertFalse("卡上不许有示例引据", p.citations.any { it.ref.contains("news.example") })
        assertTrue("对照依据还在：人设", p.citations.any { it.kind == CitationKind.PERSONA })
        assertTrue("对照依据还在：知识库", p.citations.any { it.kind == CitationKind.KNOWLEDGE })
    }

    // ── 判据②：对照夹具——正式路径不受影响，依据仍能正常出现 ──────────────

    @Test
    fun `对照_正式路径不受影响_检索出的依据照常上卡`() {
        CitationHitCache.record(listOf(productionHit()), CitationDomain.PRODUCTION)
        val v = view(persona(), listOf(knowledge()), hits = CitationHitCache.snapshot())
        val citations = OutputCitationSources.collect(v)
        val a = OutputCards.push(ResultCardState(), card(), citations, OutputCitationSources.exists(v), messages)
        val p = pass(a.verdict)
        assertTrue(
            "WEB 依据照常上卡：${p.citations.map { it.ref }}",
            p.citations.any { it.ref == "$realUrl ${dateOf(nowMs)}" },
        )
        assertTrue("卡面那行带搜索依据", p.line.contains("搜索"))
    }

    @Test
    fun `预览页自身_示例引据仍在_独立域没被误杀`() {
        val (_, verdict) = CitationPreviewAdmission.run(tmp.newFolder("preview"), messages, nowMs)
        val p = pass(verdict)
        assertTrue(
            "预览卡上还得有示例引据（预览的职责没被拆）：${p.citations.map { it.ref }}",
            p.citations.any { it.ref.startsWith(sampleUrl) },
        )
        assertEquals("人设+知识库+搜索 三类齐全", 3, p.citations.map { it.kind }.toSet().size)
    }

    // ── 夹具的零件（与 OutputCitationTest 同口径）─────────────────────────

    private fun previewHit(): CitationWebHit =
        CitationWebHit(sampleUrl, "近日热点", "街口新开早点铺", nowMs, CitationDomain.PREVIEW)

    private fun productionHit(): CitationWebHit =
        CitationWebHit(realUrl, "真结果", "早点铺开张", nowMs, CitationDomain.PRODUCTION)

    /** 不点名域的快照＝生产域（默认参数那条路，正式出卡 citationView 走的同一形态）。 */
    private fun productionUrls(): List<String> = CitationHitCache.snapshot().map { it.url }

    /** 与生产同一口径（:core TimeFormats 是唯一真源），不另造一份日期算法。 */
    private fun dateOf(ms: Long): String = TimeFormats.dayStamp(ms)

    private fun persona(): File = tmp.newFile("persona.md").also {
        it.writeText("## 语气\n- 短句，不铺陈\n- 少用感叹号\n## 习惯\n- 先给结论\n", Charsets.UTF_8)
    }

    private fun knowledge(): File = tmp.newFile("kb.md").also {
        it.writeText("# 城市笔记\n菜市场清晨最热闹。\n", Charsets.UTF_8)
    }

    private fun view(
        persona: File?,
        knowledge: List<File> = emptyList(),
        hits: List<CitationWebHit> = emptyList(),
    ): CitationSourceView {
        val docs = ArrayList<CitationSourceDoc>(knowledge.size + 1)
        if (persona != null) docs += CitationSourceDoc(PERSONA_REL, persona)
        knowledge.forEachIndexed { i, f -> docs += CitationSourceDoc("知识库/整理/笔记$i.md", f) }
        return CitationSourceView(
            docs = docs,
            personaRel = if (persona != null) PERSONA_REL else null,
            webHits = hits,
        )
    }

    private fun card(): ResultCardSpec =
        ResultCardSpec("write.topic", "选题方案", emptyList(), emptyList())

    private fun reject(verdict: CitationVerdict): CitationVerdict.Reject {
        assertTrue("应当是拒：$verdict", verdict is CitationVerdict.Reject)
        return verdict as CitationVerdict.Reject
    }

    private fun pass(verdict: CitationVerdict): CitationVerdict.Pass {
        assertTrue("应当是通过：$verdict", verdict is CitationVerdict.Pass)
        return verdict as CitationVerdict.Pass
    }

    private companion object {
        const val PERSONA_REL = "抱朴记忆/人设-本项目.md"
    }
}
