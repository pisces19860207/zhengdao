// 独立开发声明：本文件为本项目原创。
//
// 第四百六十五批 · **接线与红线门禁**（判据④⑤⑥）。
// 纯判据在 OutputCitationTest；这里钉「判据对、但接线上别走样」，并做 6 处变异自证：
// 自检若恒真，改坏任何一处都应当变红——不许拿「文件含某字面量」当判据。
// 第五百五十七批：bodyOf 从「切到下一个 private fun」换成括号配对（GateBodies）——
// 中间夹一层改可见性不再吞并 topicScan（547 实测红过的正是这一格）；marker 不再带可见性。
package com.example.zhengdao.baopu.cards

import com.example.zhengdao.GateBodies
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputCitationWiringTest {

    private fun src(rel: String): String {
        val f = File(rel).let { if (it.exists()) it else File("app/$rel") }
        assertTrue("找不到 $rel：${f.path}", f.isFile)
        return f.readText()
    }

    private val skill by lazy { src("src/main/assets/baopu/skills/微头条流程/SKILL.md") }
    private val kinds by lazy { src("src/main/java/com/example/zhengdao/baopu/cards/OutputCitation.kt") }
    private val sources by lazy { src("src/main/java/com/example/zhengdao/baopu/cards/OutputCitationSources.kt") }
    private val controller by lazy { src("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt") }
    private val containers by lazy { src("src/main/java/com/example/zhengdao/ui/baopu/BaopuContainers.kt") }
    private val resultCard by lazy { src("src/main/java/com/example/zhengdao/ui/baopu/BaopuResultCard.kt") }
    private val bar by lazy { src("src/main/java/com/example/zhengdao/ui/baopu/BaopuCitationBar.kt") }
    private val stringsKt by lazy { src("src/main/java/com/example/zhengdao/ui/baopu/BaopuCardStrings.kt") }
    private val zhXml by lazy { src("src/main/res/values/strings_baopu_cards.xml") }
    private val enXml by lazy { src("src/main/res/values-en/strings_baopu_cards.xml") }
    private val searchTool by lazy { src("src/main/java/com/example/zhengdao/baopu/agent/tools/SearchWebTool.kt") }
    private val verifier by lazy { src("src/main/java/com/example/zhengdao/baopu/verify/ArtifactVerifier.kt") }
    private val topicSuggest by lazy { src("src/main/java/com/example/zhengdao/baopu/TopicSuggest.kt") }
    private val bodyCheck by lazy { src("src/main/java/com/example/zhengdao/baopu/cards/ResultCardBody.kt") }
    private val spec by lazy { src("src/main/java/com/example/zhengdao/baopu/cards/ResultCardTemplate.kt") }

    // ── 可复用的判据（变异自证要拿同一批函数验红）────────────────────────

    private fun numberedSteps(body: String): List<String> =
        Regex("""(?m)^\d+\.\s+(.+)$""").findAll(body).map { it.groupValues[1] }.toList()

    private fun stepNumbers(body: String): List<Int> =
        Regex("""(?m)^(\d+)\.\s+""").findAll(body).map { it.groupValues[1].toInt() }.toList()

    /** 只数行数会放过「把第 7 步改成 6」这种改坏（变异自证抓过一次）：编号必须正好 1..7。 */
    private fun sevenSteps(body: String): Boolean = stepNumbers(body) == (1..7).toList()

    private fun firstStepIsSearch(body: String): Boolean {
        val first = numberedSteps(body).firstOrNull() ?: return false
        return first.contains("联网搜") && first.contains("热点") && !first.contains("台账")
    }

    private fun ledgerOnlyDedup(body: String): Boolean =
        body.contains("只用于查重") && body.contains("台账不当选题来源")

    private fun citationKindsClosed(source: String): Boolean {
        val block = source.substringAfter("enum class CitationKind").substringBefore("\n}")
        return !block.contains("LEDGER") && !block.contains("台账")
    }

    private fun gateBeforeStart(source: String): Boolean {
        val gate = source.indexOf("OutputCitationGate.check(")
        val start = source.indexOf("TopicFlowRules.start(")
        return gate >= 0 && start >= 0 && gate < start
    }

    private fun qualityVerifierUntouched(source: String): Boolean =
        !source.contains("OutputCitationGate") && !source.contains("CitationVerdict")

    /** 函数体＝括号配对定位（GateBodies）；marker 不带可见性，改 internal/private 都不影响切片。 */
    private fun bodyOf(source: String, marker: String): String {
        val body = GateBodies.functionBody(source, marker)
        assertTrue("找不到 $marker", body != null)
        return body!!
    }

    /** 判据五的尺：依据视图里不许出现的台账符号。真实源与变异源用同一把尺。 */
    private fun ledgerLeaks(view: String): List<String> =
        listOf("publishedFromLedger", "pickLedger", "产物/台账.md", "ledger.readText()").filter { view.contains(it) }

    // ── 判据④：SKILL.md 七步、第一步联网搜、台账只查重 ────────────────────

    @Test
    fun `判据四_微头条流程改成七步`() {
        assertTrue("要正好 7 步且编号 1..7，实测 ${stepNumbers(skill)}", sevenSteps(skill))
        assertEquals((1..7).toList(), stepNumbers(skill))
    }

    @Test
    fun `判据四_第一步是联网搜近期热点_且不碰台账`() {
        assertTrue(firstStepIsSearch(skill))
        assertTrue(numberedSteps(skill).first().contains("联网搜"))
    }

    @Test
    fun `判据四_台账只用于查重_不再当选题来源`() {
        assertTrue(ledgerOnlyDedup(skill))
        assertTrue(skill.contains("台账不当选题来源"))
    }

    @Test
    fun `判据四_新正文仍过种子技能内容闸且不超2KB`() {
        assertTrue("标题要带技能名", skill.lineSequence().first { it.isNotBlank() }.contains("微头条流程"))
        assertEquals("正文不许含代码块/外链/权限类词", emptyList<String>(),
            com.example.zhengdao.baopu.SkillWritePolicy.forbiddenFindings(skill))
        val bytes = com.example.zhengdao.baopu.SkillWritePolicy.utf8Bytes(skill)
        assertTrue("正文 ≤2KB（实测 $bytes）", bytes != null && bytes <= SkillWritePolicyMax)
    }

    @Test
    fun `判据四_正文写明依据为空或悬空就不出卡`() {
        assertTrue(skill.contains("依据为空") || skill.contains("依据里每条"))
        assertTrue(skill.contains("就不出卡"))
        assertTrue(skill.contains("卡面顶部一行列出依据"))
    }

    // ── 判据⑤：不存在「引了依据但内容来自旧台账」的路径 ────────────────────

    @Test
    fun `判据五_依据闭集里没有台账`() {
        assertTrue(citationKindsClosed(kinds))
    }

    @Test
    fun `判据五_来源收集器不读台账`() {
        assertEquals("citationView() 里不许出现台账符号",
            emptyList<String>(), ledgerLeaks(bodyOf(controller, "fun citationView()")))
        listOf("publishedFromLedger", "pickLedger", "产物/台账.md", "LedgerEntry").forEach { w ->
            assertFalse("OutputCitationSources.kt 里不许出现 $w", sources.contains(w))
        }
    }

    @Test
    fun `判据五_台账仍然只流向查重`() {
        val scan = bodyOf(controller, "fun topicScan()")
        assertTrue(scan.contains("TopicSuggest.publishedFromLedger"))
        assertTrue(scan.contains("TopicScan(refs"))
        assertTrue(controller.contains("TopicSuggest.nextBatch(scan.refs"))
    }

    @Test
    fun `判据五_搜索结果另走WEB通道_与台账不相交`() {
        assertTrue(searchTool.contains("CitationHitCache.record("))
        assertTrue(bodyOf(controller, "fun citationView()").contains("CitationHitCache.snapshot()"))
    }

    // ── 卡面与接线 ────────────────────────────────────────────────────────

    @Test
    fun `接线_出卡前先过闸_拒时一张卡都不出`() {
        assertTrue(gateBeforeStart(controller))
        val start = bodyOf(controller, "fun topicStart()")
        assertTrue(start.contains("OutputCitationGate.check("))
        assertTrue(start.contains("topicCitationNote = verdict.detail"))
        assertTrue(start.contains("topicFlow = null"))
    }

    @Test
    fun `接线_产出类卡带依据字段`() {
        assertTrue(spec.contains("val citations: List<Citation>"))
        assertTrue(spec.contains("val citationLine: String?"))
        assertTrue(spec.contains("citations = citations"))
    }

    @Test
    fun `接线_两处卡面顶部都渲染依据行`() {
        assertTrue(containers.contains("BaopuCitationBar(citations, citationLine)"))
        assertTrue(resultCard.contains("BaopuCitationBar(card.citations, card.citationLine)"))
        // 依据行在标题条之后、正文之前 ⇒ 物理上就是「卡面顶部一行」
        val at = containers.indexOf("BaopuCitationBar(citations, citationLine)")
        val contentAt = containers.indexOf("Column(Modifier.padding(BAO_ACTION_INSET_DP.dp), content = content)")
        assertTrue(at in 0 until contentAt)
    }

    @Test
    fun `接线_依据行可点开看原文`() {
        assertTrue(bar.contains(".clickable("))
        assertTrue(bar.contains("c.excerpt"))
        assertTrue(bar.contains("R.string.baopu_citation_source"))
    }

    @Test
    fun `接线_九个文案键三处同步`() {
        val keys = listOf(
            "baopu_citation_prefix", "baopu_citation_open", "baopu_citation_close",
            "baopu_citation_open_desc", "baopu_citation_source", "baopu_citation_empty_hint",
            "baopu_citation_ledger_hint", "baopu_citation_bad_ref_hint", "baopu_citation_dangling_hint",
        )
        keys.forEach { k ->
            assertTrue("$k 不在中文 strings", zhXml.contains("name=\"$k\""))
            assertTrue("$k 不在英文 strings", enXml.contains("name=\"$k\""))
            assertTrue("$k 不在文案表", stringsKt.contains("\"$k\" -> res.getString"))
        }
    }

    // ── 判据⑥：不改写作质量判据 ──────────────────────────────────────────

    @Test
    fun `判据六_依据闸没有塞进机械验收器与正文体检`() {
        assertTrue(qualityVerifierUntouched(verifier))
        assertTrue(qualityVerifierUntouched(bodyCheck))
        assertTrue("ArtifactVerifier 的边界声明还在", verifier.contains("不验「写得好不好」"))
    }

    @Test
    fun `判据六_题材与写法的既有判据符号都还在`() {
        listOf("val STYLES", "fun writeInstruction(", "fun clashOf(", "fun nextBatch(").forEach { w ->
            assertTrue("$w 被改动了（写作质量判据不许动）", topicSuggest.contains(w))
        }
        assertTrue(qualityVerifierUntouched(topicSuggest))
    }

    @Test
    fun `判据六_机制不写死具体人格名`() {
        listOf(kinds, sources, bar, spec, stringsKt).forEach { s ->
            assertFalse("机制里不许写死「守书人」", s.contains("守书人"))
        }
    }

    // ── 变异自证：6 处改坏都要被咬住（防恒真）────────────────────────────

    @Test
    fun `变异自证_六处改动各自变红`() {
        assertFalse("少一步要红", sevenSteps(skill.replace("7. 收尾", "6. 收尾")))
        assertFalse("首步改回读台账要红", firstStepIsSearch(skill.replace("先联网搜近期热点", "先读台账")))
        assertFalse("删掉「台账不当选题来源」要红", ledgerOnlyDedup(skill.replace("台账不当选题来源", "")))
        assertFalse("闭集多出 LEDGER 要红", citationKindsClosed(kinds.replace("    WEB,", "    WEB,\n    LEDGER,")))
        assertFalse("去掉依据闸要红", gateBeforeStart(controller.replace("OutputCitationGate.check(", "NO_GATE(")))
        assertFalse("把依据闸塞进质量验收器要红", qualityVerifierUntouched(verifier + "OutputCitationGate"))
    }

    // ── 557 去脆化自证：547 踩过的「中间层改可见性吞并切片」永久钉住 ────────

    @Test
    fun `去脆化自证_中间层改可见性_切片一字不变`() {
        // 547 实测红过的那一格：citationMessages 从 private 改 internal，旧切片器
        // （切到下一个 private fun）会把 topicScan 吞进 citationView ⇒ 判据五误红。
        val mutated = controller.replace("private fun citationMessages(", "internal fun citationMessages(")
        assertTrue("源里应有 private fun citationMessages(", mutated != controller)
        listOf("fun citationView()", "fun topicScan()", "fun topicStart()").forEach { marker ->
            assertEquals("可见性变更不许影响 $marker 的切片",
                bodyOf(controller, marker), bodyOf(mutated, marker))
        }
        assertEquals("切片仍不许吞进台账", emptyList<String>(),
            ledgerLeaks(bodyOf(mutated, "fun citationView()")))
    }

    @Test
    fun `去脆化自证_依据视图塞进台账_同一把尺变红`() {
        val mutated = controller.replace("CitationHitCache.snapshot()", "pickLedger()")
        assertTrue("源里应有 CitationHitCache.snapshot()", mutated != controller)
        assertEquals(listOf("pickLedger"), ledgerLeaks(bodyOf(mutated, "fun citationView()")))
    }

    private companion object {
        const val SkillWritePolicyMax = 2 * 1024
    }
}
