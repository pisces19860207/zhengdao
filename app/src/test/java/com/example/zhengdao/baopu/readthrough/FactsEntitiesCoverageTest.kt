// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.readthrough

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.json.JSONObject

/**
 * 事实落盘 / 实体护栏 / 人物覆盖 单测（610 判据②③④⑦）。
 *
 * 钉住的判据：② 每条事实自带章＋行（0 起含端点）——「抽 5 条能点回章＋行」的字段根基；
 * ③ 人物覆盖抽样 ≥95%，不达标逐章列出；④ **同名不同人不许自动合并**——撞名只报不并；
 * ⑦ 每章一个 jsonl，append-only，重跑只补缺章。
 */
class FactsEntitiesCoverageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun fact(
        chapter: Int, start: Int, end: Int,
        subject: String = "张小凡", subjectId: String = "张小凡#1",
        predicate: String = "遇见", obj: String = "张小凡#2",
        quote: String = "原文一句",
    ) = FactLine(chapter, start, end, subject, subjectId, predicate, obj, quote, "c${chapter}t0", 100L)

    // ── ⑤ 落盘 ──────────────────────────────────────────────────────────────

    @Test
    fun `事实行_json往返_字段齐全`() {
        val f = fact(3, 10, 12)
        val back = FactLine.fromJson(f.toJson())!!
        assertEquals(f, back)
    }

    @Test
    fun `事实行_缺字段或行号非法_解析为null`() {
        assertNull(FactLine.fromJson(JSONObject().put("chapter", 1)))
        assertNull(
            FactLine.fromJson(
                JSONObject()
                    .put("chapter", 1).put("start_line", 5).put("end_line", 2)
                    .put("subject", "甲").put("subject_id", "甲#1").put("predicate", "见"),
            ),
        )
    }

    @Test
    fun `每章一个jsonl_文件名与解析互逆`() {
        assertEquals("chapter-0007.jsonl", FactStore.chapterFileName(7))
        assertEquals(7, FactStore.chapterIndexOf("chapter-0007.jsonl"))
        assertEquals("block-012.jsonl", FactStore.blockFileName(12))
        assertEquals(12, FactStore.blockIndexOf("block-012.jsonl"))
        assertNull(FactStore.chapterIndexOf("book.jsonl"))
    }

    @Test
    fun `append_only_追加不重写_两次都在`() {
        val dir = tmp.newFolder("out")
        FactStore.append(dir, FactStore.chapterFileName(0), listOf(fact(0, 0, 1).toJson().toString()))
        FactStore.append(dir, FactStore.chapterFileName(0), listOf(fact(0, 2, 3, "林惊羽", "林惊羽#1").toJson().toString()))
        val file = File(dir, FactStore.chapterFileName(0))
        assertEquals(2, FactStore.readLines(file).size)
        val existing = FactStore.existingChapters(dir)
        assertEquals(setOf(0), existing)
        // 缺章 = 计划 − 已有
        assertEquals(listOf(1, 2), CheckpointStore.missingChapters(listOf(0, 1, 2), existing))
    }

    @Test
    fun `空追加_不建文件_不建目录`() {
        val dir = tmp.newFolder("out2")
        FactStore.append(dir, "chapter-0000.jsonl", emptyList())
        assertEquals(0, dir.listFiles()!!.size)
    }

    // ── ④ 实体护栏：合并只认 id，同名不同人只报不并 ─────────────────────────

    @Test
    fun `同名不同id_两个实体_撞名进人审清单_不许自动合并`() {
        val facts = listOf(
            fact(0, 0, 1, "张小凡", "张小凡#1"), // 主角
            fact(1, 5, 6, "张小凡", "张小凡#2"), // 同名的另一个人（不许并进 #1）
        )
        val audit = EntityMerge.audit(facts)
        assertEquals("两个 id 是两个实体，谁也没吃掉谁", 2, audit.entities.size)
        assertEquals(1, audit.clashCount)
        assertEquals("张小凡", audit.clashes[0].name)
        assertEquals(listOf("张小凡#1", "张小凡#2"), audit.clashes[0].ids)
    }

    @Test
    fun `同一id多个称呼_一个实体_称呼全列不拍正名`() {
        val facts = listOf(
            fact(0, 0, 1, "张小凡", "张小凡#1"),
            fact(1, 0, 1, "小凡", "张小凡#1"),
        )
        val audit = EntityMerge.audit(facts)
        assertEquals(1, audit.entities.size)
        assertEquals(setOf("张小凡", "小凡"), audit.entities[0].names)
        assertEquals("同 id 不同称呼不算撞名", 0, audit.clashCount)
        assertEquals(2, audit.entities[0].factCount)
    }

    @Test
    fun `无撞名_人审清单为空`() {
        val audit = EntityMerge.audit(listOf(fact(0, 0, 1), fact(0, 2, 3, "林惊羽", "林惊羽#1")))
        assertEquals(2, audit.entities.size)
        assertEquals(0, audit.clashCount)
    }

    // ── ③ 人物覆盖抽样 ──────────────────────────────────────────────────────

    @Test
    fun `说话人抓取与覆盖_全部对上_达标`() {
        val text = "张小凡道：「好。」\n林惊羽说：「走。」\n田灵儿喊：「等等。」\n"
        val facts = listOf(fact(0, 0, 0, "张小凡", "张小凡#1"), fact(0, 1, 1, "林惊羽", "林惊羽#1"), fact(0, 2, 2, "田灵儿", "田灵儿#1"))
        assertEquals(1.0, CharacterCoverage.coverageOf(text, facts), 0.0001)
        assertTrue(CharacterCoverage.speakers(text).contains("张小凡"))
    }

    @Test
    fun `漏了一个说话人_单章覆盖率如实降_不达标章逐条报`() {
        val text = "张小凡道：「好。」\n林惊羽说：「走。」\n"
        val facts = listOf(fact(0, 0, 0, "张小凡", "张小凡#1"))
        val cov = CharacterCoverage.coverageOf(text, facts)
        assertEquals(0.5, cov, 0.0001)
        val report = CharacterCoverage.sample(listOf(CharacterCoverage.Sample(3, text, facts)))
        assertTrue(!report.pass)
        assertEquals(listOf(3 to 0.5), report.failing)
    }

    @Test
    fun `无说话人候选_不冤枉该章`() {
        val text = "山风过处，落叶纷纷。\n"
        assertEquals(1.0, CharacterCoverage.coverageOf(text, emptyList()), 0.0001)
    }

    @Test
    fun `抽样_每N章抽一_整体均值过阈值即达标`() {
        val good = CharacterCoverage.Sample(0, "张小凡道：「好。」", listOf(fact(0, 0, 0, "张小凡", "张小凡#1")))
        val report = CharacterCoverage.sample(listOf(good), threshold = 0.95)
        assertTrue(report.pass)
        assertEquals(1, report.sampled)
    }

    // ── ② 点回章＋行：5 条抽样的字段根基 ────────────────────────────────────

    @Test
    fun `五条事实_行号都在章内且能对回原文行`() {
        val book = ReadthroughFixtures.standardBook(3, bodyLines = 4)
        val lines = book.lines()
        val slices = ReadthroughSlicer.sliceBook(lines, com.example.zhengdao.baopu.library.ChapterSplitter.split(book)).slices
        // 假抽取：每片产 2 条事实，行号取片内真实行
        val facts = slices.flatMap { s ->
            (0 until 2).map { k ->
                val ln = (s.startLine + k).coerceAtMost(s.endLine)
                fact(s.chapterIndex, ln, ln, quote = lines[ln])
            }
        }
        assertTrue("抽样池要有 5 条以上", facts.size >= 5)
        facts.take(5).forEach { f ->
            assertTrue("章号合法", f.chapter in 0..2)
            assertTrue("行号含端点且在章内：$f", f.startLine in 0..f.endLine)
            val slice = slices.first { it.chapterIndex == f.chapter }
            assertTrue("行号落在该片范围内：$f", f.startLine >= slice.startLine && f.endLine <= slice.endLine)
            assertEquals("quote 能点回原文行：$f", lines[f.startLine], f.quote)
        }
    }
}
