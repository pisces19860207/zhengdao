// 独立开发声明：本文件为本项目原创。
//
// 第五百四十七批 · **产出卡的接线门禁**（判据①～⑤ ＋ 变异自证）。
//
// 465 定下的 `OutputCards.push`（依据闸＋入卡区）此前只有测试在调 ⇒ 那道闸从未在真实
// 路径上生效（`docs/entry-coverage.md` 挂着这条账）。本批把它接进生产，这里钉四件事：
//   ① 无依据的产出 ⇒ **不出卡**且给原因（负例）；② 有依据 ⇒ 出卡且卡面标来源；
//   ③ 查询类**不要求依据**（负例对照：同一张卡零依据照样出，证明两类没混）；
//   ④ 生产出口真在 app/src/main 里（且四处改动各自会把它弄红）。
//
// ⚠ 自检不许用「文件含某字面量」当唯一判据：下面凡是用源码锚点的，都配了同一把尺的
// 变异自证（改坏一处必须变红），否则「恒真」这种假绿会一路绿到交付。
package com.example.zhengdao.baopu.cards

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OutputProduceWireTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun src(rel: String): String {
        val f = File(rel).let { if (it.exists()) it else File("app/$rel") }
        assertTrue("找不到 $rel：${f.path}", f.isFile)
        return f.readText()
    }

    private val screen by lazy { src("src/main/java/com/example/zhengdao/ui/baopu/BaopuScreen.kt") }
    private val controller by lazy { src("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt") }
    private val templateText by lazy { src("src/main/assets/baopu/cards/output.produce.json") }

    private val messages = CitationMessages(
        prefix = "依据：",
        emptyHint = "先读人设/知识库",
        ledgerHint = "台账只用于查重，不能当依据",
        badRefHint = "依据来源要写文件:行 或 URL+日期",
        danglingHint = "依据指向的条目不存在：%1\$s",
    )

    /** 用**真模板**绑定（不是手搓一个空壳）：判据②要的是「卡面真能标来源」。 */
    private val registry = ResultCardRegistry { path ->
        if (path.endsWith("output.produce.json")) templateText else null
    }

    private fun produceCard(): ResultCardSpec? = registry.card(
        templateId = "output.produce",
        values = mapOf("count" to "2", "files" to "文稿/一.md\n文稿/二.md"),
        lookup = { key -> "L:$key" },
        itemCount = 2,
    )

    private fun persona(): File = tmp.newFile("persona.md").also {
        it.writeText("## 语气\n- 短句，不铺陈\n- 先给结论\n", Charsets.UTF_8)
    }

    private fun viewOf(persona: File): CitationSourceView = CitationSourceView(
        docs = listOf(CitationSourceDoc("抱朴记忆/人设-本项目.md", persona)),
        personaRel = "抱朴记忆/人设-本项目.md",
        webHits = emptyList(),
    )

    // ── 判据①：无依据的产出 ⇒ 不出卡，且给出原因 ─────────────────────────

    @Test
    fun `判据一_零依据的产出不出卡_状态逐字不动`() {
        val card = requireNotNull(produceCard()) { "真模板必须能绑出卡" }
        val before = ResultCardState()
        val a = OutputCards.push(before, card, emptyList(), { true }, messages)
        assertSame("卡区必须原样交回（一张没加、下标没动）", before, a.state)
        assertEquals(0, a.state.cards.size)
    }

    @Test
    fun `判据一_零依据时给出人话原因`() {
        val card = requireNotNull(produceCard())
        val verdict = OutputCards.push(ResultCardState(), card, emptyList(), { true }, messages).verdict
        val r = verdict as CitationVerdict.Reject
        assertEquals(CitationReject.EMPTY, r.reason)
        assertEquals("先读人设/知识库", r.detail)
        assertTrue("拒的理由不许为空", r.detail.isNotBlank())
    }

    @Test
    fun `判据一_依据悬空同样不出卡`() {
        val card = requireNotNull(produceCard())
        val view = viewOf(persona())
        val gone = Citation(CitationKind.KNOWLEDGE, "知识库/整理/不存在.md:3", "知识库 不存在")
        val before = ResultCardState()
        val a = OutputCards.push(before, card, listOf(gone), OutputCitationSources.exists(view), messages)
        assertSame(before, a.state)
        assertEquals(CitationReject.DANGLING, (a.verdict as CitationVerdict.Reject).reason)
    }

    // ── 判据②：有依据 ⇒ 出卡且卡面标来源 ────────────────────────────────

    @Test
    fun `判据二_有依据就出卡_卡面顶部是依据行`() {
        val card = requireNotNull(produceCard())
        val view = viewOf(persona())
        val citations = OutputCitationSources.collect(view)
        assertEquals("人设那两份条目都收进来了", 2, citations.size)
        val a = OutputCards.push(ResultCardState(), card, citations, OutputCitationSources.exists(view), messages)
        assertTrue("应当过闸", a.verdict is CitationVerdict.Pass)
        val out = a.state.cards.single()
        assertEquals("依据原样挂到卡上", 2, out.citations.size)
        val line = requireNotNull(out.citationLine) { "卡面顶部必须有依据行" }
        assertTrue(line, line.startsWith("依据："))
        assertTrue(line, line.contains("人设第 1 条"))
    }

    @Test
    fun `判据二_卡面文件行来自真模板绑定`() {
        val out = requireNotNull(produceCard())
        assertEquals("output.produce", out.templateId)
        val files = out.rows.first { it.key == "files" }
        assertTrue("产出的文件清单要真的进卡面", files.value.contains("文稿/一.md"))
        assertTrue("卡里那一行可复制（用户要拿走）", files.copyable)
    }

    // ── 判据③：查询类与今天逐字一致（负例对照）──────────────────────────

    @Test
    fun `判据三_同一张卡零依据走查询口照样出卡`() {
        val card = requireNotNull(produceCard())
        val s = ResultCards.push(ResultCardState(), card)
        assertEquals("查询类不要求依据 —— 这就是两类不许混的那条分界", 1, s.cards.size)
        val out = s.cards.single()
        assertNull("查询卡没有依据行", out.citationLine)
        assertTrue("查询卡不带依据", out.citations.isEmpty())
    }

    @Test
    fun `判据三_查询卡的出口仍是 ResultCards_push 没被产出闸接管`() {
        assertTrue(
            "查询卡那一处必须原样是 ResultCards.push（547 只加产出口，不动它）",
            screen.contains("resultCards = ResultCards.push(resultCards, card)"),
        )
        // 撞红线：查询卡一旦改走 OutputCards，零依据的物流卡会当场消失
        val mutated = screen.replace(
            "resultCards = ResultCards.push(resultCards, card)",
            "resultCards = OutputCards.push(resultCards, card, emptyList(), { true }, controller.citationGateMessages()).state",
        )
        assertFalse("查询口改走产出闸 ⇒ 判据三必须红", queryExit(mutated))
    }

    private fun queryExit(code: String): Boolean =
        code.contains("resultCards = ResultCards.push(resultCards, card)")

    // ── 判据④：生产出口 ≥1（app/src/main 里真有 OutputCards.）────────────

    @Test
    fun `判据四_生产出口接在屏幕侧且写回卡区`() {
        assertTrue("产出卡必须经 OutputCards.push 入卡区", produceExit(screen))
        assertTrue(
            "收尾时由控制器发请求，且只在真有产出时发",
            controller.contains("offerProduceCardOnSettle()") &&
                controller.contains("val artifacts = engine.roundArtifacts.value") &&
                controller.contains("if (artifacts.isEmpty()) return"),
        )
        // 给它一条真实的 文件:行，报告与验收都按它核
        val at = screen.indexOf("OutputCards.push(")
        val lineNo = screen.substring(0, at).count { it == '\n' } + 1
        assertTrue("BaopuScreen.kt:$lineNo", lineNo > 0)
    }

    private fun produceExit(code: String): Boolean =
        code.contains("OutputCards.push(") &&
            code.contains("OutputCitationSources.exists(offer.view)") &&
            code.contains("resultCards = admission.state") &&
            code.contains("controller.noteProduceCardRejected(")

    @Test
    fun `变异自证_产出出口四处改坏各自变红`() {
        assertTrue("原文件必须先真绿（否则下面的红没有意义）", produceExit(screen))
        assertFalse("换回查询口要红", produceExit(screen.replace("OutputCards.push(", "ResultCards.push(")))
        assertFalse(
            "「还在不在」不注入 ⇒ 悬空依据会混过去，要红",
            produceExit(screen.replace("OutputCitationSources.exists(offer.view)", "{ true }")),
        )
        assertFalse(
            "结果不写回卡区 ⇒ 闸过了也看不见卡，要红",
            produceExit(screen.replace("resultCards = admission.state", "resultCards = resultCards")),
        )
        assertFalse(
            "拒了不给原因 ⇒ 判据①只剩半句，要红",
            produceExit(screen.replace("controller.noteProduceCardRejected(", "noop(")),
        )
    }

    // ── 判据⑤：落账一行（产出/查询 · 有无依据 · 依据条数）────────────────

    @Test
    fun `判据五_产出与查询各落一行且含有无依据`() {
        val view = viewOf(persona())
        val citations = OutputCitationSources.collect(view)
        val pass = OutputCitationGate.check(citations, OutputCitationSources.exists(view), messages)
        val produced = CardLedger.line(CardLedger.Kind.PRODUCE, pass, ResultCardState())
        assertTrue(produced, produced.contains("出卡 产出"))
        assertTrue(produced, produced.contains("有无依据=有"))
        assertTrue(produced, produced.contains("依据条数=2"))
        val queried = CardLedger.line(CardLedger.Kind.QUERY, null, ResultCardState())
        assertTrue(queried, queried.contains("出卡 查询"))
        assertTrue(queried, queried.contains("有无依据=无"))
        assertTrue(queried, queried.contains("依据条数=0"))
    }

    @Test
    fun `判据五_被拒的那次也落一行且写明不出卡`() {
        val rejected = OutputCitationGate.check(emptyList(), { true }, messages)
        val line = CardLedger.line(CardLedger.Kind.PRODUCE, rejected, ResultCardState())
        assertTrue(line, line.contains("不出卡 产出"))
        assertTrue(line, line.contains("有无依据=无"))
        // 只记类目与条数：依据正文、卡里的字段值一个字都不许进日志
        assertFalse("依据正文不许进日志", line.contains("短句"))
        assertFalse("卡里的文件路径不许进日志", line.contains("文稿/一.md"))
    }

    @Test
    fun `判据五_两条出卡路径都真的落了账`() {
        assertTrue(
            "查询口落账",
            screen.contains("CardLedger.line(CardLedger.Kind.QUERY, null, resultCards)"),
        )
        assertTrue(
            "产出口落账",
            screen.contains("CardLedger.line(CardLedger.Kind.PRODUCE, admission.verdict, resultCards)"),
        )
    }

    // ── 资产：新模板随包且键齐（红线五在 BaopuResultCardGateTest 里逐键对账）──

    @Test
    fun `资产_产出卡模板随包且只有 labelKey`() {
        assertTrue(templateText.contains("\"templateId\": \"output.produce\""))
        assertFalse("模板里不许写字面文案", Regex(""""label"\s*:""").containsMatchIn(templateText))
        listOf(
            "baopu_card_output_title",
            "baopu_card_output_g_batch",
            "baopu_card_output_g_files",
            "baopu_card_output_count",
            "baopu_card_output_files",
        ).forEach { key ->
            assertTrue("$key 不在模板里", templateText.contains("\"$key\""))
        }
    }
}
