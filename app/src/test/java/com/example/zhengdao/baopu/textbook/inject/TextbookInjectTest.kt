// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 798 段 B · 判据②③④⑥⑦：预算上限、正反分块、不编内容、可解释。
// 阈值用派工单硬数（300/60），不引用被测常量自证。
package com.example.zhengdao.baopu.textbook.inject

import com.example.zhengdao.baopu.TokenEstimator
import com.example.zhengdao.baopu.textbook.Domain
import com.example.zhengdao.baopu.textbook.TextbookEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TextbookInjectTest {

    private fun lesson(
        id: String,
        text: String,
        side: TeachSide = TeachSide.POSITIVE,
        scope: String = "叙事连续性",
    ) = TeachingLesson(id, text, "786 评测集 §三", "2026-11-20", side, "scope 命中「$scope」")

    private fun context(keyword: String) = TeachContext(listOf(keyword), emptyList(), LocalDate.of(2026, 11, 22))

    // ── ④ 反例块显式标记 + 不混排 ─────────────────────────────────────────

    @Test
    fun `判据三_反例块显式标这是反例且与正例分块不混排`() {
        val inj = TextbookInject.build(
            listOf(
                lesson("TB-P1", "字段 verdict 只取三档"),
                lesson("TB-P2", "状态行每轮可变"),
                lesson("TB-N1", "这是反例：把 verdict 写成数值档", TeachSide.NEGATIVE),
                lesson("TB-N2", "错误示范：忽略词档行", TeachSide.NEGATIVE),
            ),
        )
        val negLines = inj.lines.filter { it.startsWith(TextbookInject.NEGATIVE_TAG) }
        val posLines = inj.lines.filter { it.startsWith(TextbookInject.POSITIVE_TAG) }
        assertEquals("两个反例都要进反例块", 2, negLines.size)
        assertEquals("两个正例都要进正例块", 2, posLines.size)
        assertTrue("反例块必须显式标『这是反例』", negLines.all { "这是反例" in it })
        assertFalse("不得混排：mixed 须为 false", inj.mixed)
        val firstNeg = inj.lines.indexOfFirst { it.startsWith(TextbookInject.NEGATIVE_TAG) }
        val lastPos = inj.lines.indexOfLast { it.startsWith(TextbookInject.POSITIVE_TAG) }
        assertTrue("所有正例块须在反例块之前", lastPos < firstNeg)
    }

    @Test
    fun `判据三_混排自检咬得住_反例在正例前为真`() {
        val mixed = listOf(
            "${TextbookInject.NEGATIVE_TAG} 这是反例：先给反例",
            "${TextbookInject.POSITIVE_TAG} 再给正例",
        )
        assertTrue("混排输入必须判红", TextbookInject.detectMixed(mixed))
        assertFalse("纯反例块不算混排", TextbookInject.detectMixed(listOf(mixed[0])))
        assertFalse("纯正例块不算混排", TextbookInject.detectMixed(listOf(mixed[1])))
    }

    // ── ② 300 token 硬上限 ────────────────────────────────────────────────

    @Test
    fun `判据二_教材块不超300token且用唯一尺子实算`() {
        val many = (1..40).map { lesson("TB-$it", "可观察描述第${it}条：肩线形态与叙事连续性的写法") }
        val inj = TextbookInject.build(many)
        assertTrue("注入 token ${inj.tokenCost} 须 ≤300", inj.tokenCost <= 300)
        assertEquals("token 须等于唯一尺子实算", TokenEstimator.estimate(inj.lines.joinToString("\n")), inj.tokenCost)
        assertTrue("负例护栏：确实注入了条目", inj.hitCount > 0)
    }

    @Test
    fun `判据二_单条正文不超60字且来源齐`() {
        val long = listOf(lesson("TB-01", "面".repeat(2000)))
        val inj = TextbookInject.build(long)
        val body = inj.lines.single().substringAfter("] ").substringBefore("｜来源:")
        assertTrue("正文 ${body.length} 字须 ≤60", body.length <= 60)
        assertTrue("须带来源", inj.lines.single().contains("｜来源:786 评测集"))
        assertTrue("须带 asOf", inj.lines.single().contains("｜asOf:2026-11-20"))
    }

    // ── ⑤ 可解释 + ⑥ 不编内容 / miss 空块 ────────────────────────────────

    @Test
    fun `判据五_每次注入落teach行含ids_tokens_reason`() {
        val inj = TextbookInject.build(listOf(lesson("TB-01", "字段 verdict 只取三档")))
        assertTrue("explain 须以 [teach] 开头", inj.explain.startsWith("[teach]"))
        assertTrue("须含 ids", "[teach] ids=TB-01 /" in inj.explain || "ids=TB-01" in inj.explain)
        assertTrue("须含 tokens", "tokens=${inj.tokenCost}" in inj.explain)
        assertTrue("须含 reason", "reason=" in inj.explain && "命中" in inj.explain)
    }

    @Test
    fun `判据六_空命中返回miss空块且explain记miss不编内容`() {
        val inj = TextbookInject.build(emptyList())
        assertTrue("须为 miss", inj.miss)
        assertTrue("正文须为空块", inj.lines.isEmpty())
        assertTrue("explain 须记 miss", "miss" in inj.explain)
        assertEquals(0, inj.tokenCost)
    }

    @Test
    fun `判据六_缺源或缺正文的条目被丢弃不编造`() {
        val inj = TextbookInject.build(
            listOf(
                TeachingLesson("TB-NO-SRC", "有正文", "", "2026-11-20", TeachSide.POSITIVE, "r"),
                TeachingLesson("TB-NO-TXT", "", "786", "2026-11-20", TeachSide.POSITIVE, "r"),
                lesson("TB-OK", "正常条目"),
            ),
        )
        assertEquals("只留来源齐且正文非空的 1 条", 1, inj.hitCount)
        assertEquals(listOf("TB-OK"), inj.hitIds)
    }

    // ── 命中层：当前命中 + 过期 + 被取代 + 正反判定 ────────────────────────

    private fun entry(
        id: String,
        scope: String,
        content: String,
        asOf: String = "2026-11-20",
        supersedes: String = "",
        domain: Domain = Domain.规范域,
    ) = TextbookEntry(id, com.example.zhengdao.baopu.textbook.SourceType.文献, "786", asOf, domain, content, "强", scope, true, supersedes)

    @Test
    fun `命中层_按本轮scope命中且反例如实判定`() {
        val entries = listOf(
            entry("TB-P", "叙事连续性", "字段 verdict 只取三档"),
            entry("TB-N", "叙事连续性", "反例：把 verdict 写成数值档"),
            entry("TB-X", "完全无关域", "某条无关内容"),
        )
        val hit = TextbookMatcher.match(entries, context("叙事连续性"))
        assertEquals("只命中 scope 含关键词的两条", 2, hit.size)
        assertEquals(TeachSide.POSITIVE, hit.first { it.id == "TB-P" }.side)
        assertEquals(TeachSide.NEGATIVE, hit.first { it.id == "TB-N" }.side)
    }

    @Test
    fun `命中层_90天过期与被取代旧条均不命中`() {
        val entries = listOf(
            entry("TB-OLD", "叙事连续性", "过期条", asOf = "2026-01-01"),
            entry("TB-SUP", "叙事连续性", "被取代条", supersedes = "TB-SUP"),
            entry("TB-OK", "叙事连续性", "当前条"),
        )
        val hit = TextbookMatcher.match(entries, context("叙事连续性"))
        assertEquals("只剩当前条", listOf("TB-OK"), hit.map { it.id })
    }

    @Test
    fun `命中层_无关键词或域不符均零命中`() {
        val entries = listOf(entry("TB-P", "叙事连续性", "字段 verdict 只取三档"))
        assertTrue("无关键词零命中", TextbookMatcher.match(entries, TeachContext(emptyList())).isEmpty())
        assertTrue("域不符零命中", TextbookMatcher.match(entries, TeachContext(listOf("叙事连续性"), listOf(Domain.事实域))).isEmpty())
    }
}
