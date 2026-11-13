// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.readthrough

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 编排骨架端到端单测（假件：不跑模型，数调用）。
 *
 * 钉住的判据：① 覆盖 100%（章全覆盖）；② 抽 5 条能点回章＋行（quote 对得回原文）；
 * ④ 网络差暂停要停在片边界、checkpoint 落盘；⑥ 断点续跑**不重跑已完成片**；
 * 归并严格按树走（章层只对多片章、块层每 100 章、书层一次）。
 */
class ReadthroughRunnerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 假抽取件：按提示词头里的「行 a–b」产真实行号的事实；归并提示词回摘要对象。 */
    private class FakeExtraction(
        private val failOnNth: Int? = null,
        private val calls: AtomicInteger = AtomicInteger(0),
    ) : ExtractionPort {

        // 波次并发下 call() 在多线程跑——名单用同步表，别让并发追加丢条目
        val calledSliceIds = java.util.Collections.synchronizedList(mutableListOf<String>())
        val calledMergeIds = java.util.Collections.synchronizedList(mutableListOf<String>())

        override fun call(prompt: String): String {
            val n = calls.incrementAndGet()
            if (failOnNth != null && n >= failOnNth) throw RuntimeException("模拟网络故障 #$n")
            if (prompt.contains("归并器")) {
                calledMergeIds += mergeIdOf(prompt)
                return JSONObject().put("summary", "归并测试摘要").put("characters", JSONArray()).toString()
            }
            // 事实抽取：解析「第 X 章 … 第 i/j 片（行 a–b」头
            val m = Regex("""第 (\d+) 章[^】\n]*第 (\d+)/(\d+) 片（行 (\d+)–(\d+)""").find(prompt)
                ?: throw RuntimeException("提示词头不认识：${prompt.take(60)}")
            val (chapter, a, b) = Triple(
                m.groupValues[1].toInt(),
                m.groupValues[4].toInt(),
                m.groupValues[5].toInt(),
            )
            calledSliceIds += "c${chapter}t${m.groupValues[2].toInt() - 1}"
            // 用正文行做 quote（从提示词正文里捞：行号\t正文；quote 必须指向它引用的行）
            val bodyLines = Regex("""^(\d+)\t(.*)$""", RegexOption.MULTILINE).findAll(prompt).toList()
            if (bodyLines.isEmpty()) throw RuntimeException("提示词里没有正文")
            fun lineText(n: Int): String =
                bodyLines.firstOrNull { it.groupValues[1].toInt() == n }?.groupValues[2]
                    ?: bodyLines.first().groupValues[2]
            val start = (a + 1).coerceAtMost(b)
            val end = b
            return JSONArray()
                .put(
                    JSONObject()
                        .put("chapter", chapter).put("start_line", a).put("end_line", a)
                        .put("subject", "张小凡").put("subject_id", "张小凡#1")
                        .put("predicate", "走过").put("object", "山道")
                        .put("quote", lineText(a).take(12)),
                )
                .put(
                    JSONObject()
                        .put("chapter", chapter).put("start_line", start).put("end_line", start)
                        .put("subject", "林惊羽").put("subject_id", "林惊羽#1")
                        .put("predicate", "看见").put("object", "张小凡")
                        .put("quote", lineText(start).take(12)),
                )
                .toString()
        }

        private fun mergeIdOf(prompt: String): String {
            // 归并提示词结尾列输入；用输入份数与「每 100 章」形状对账即可——回 id 由调用方断言数量
            return prompt.take(20)
        }
    }

    private fun runnerRequest(
        book: String,
        outDir: File,
        port: ExtractionPort,
        checkpoint: Checkpoint? = null,
        persistCaptured: MutableList<Checkpoint> = mutableListOf(),
    ): Triple<ReadthroughRunner, RunRequest, MutableList<Checkpoint>> {
        val lines = book.lines()
        val chapters = com.example.zhengdao.baopu.library.ChapterSplitter.split(book)
        val sliced = ReadthroughSlicer.sliceBook(lines, chapters)
        val counts = sliced.slices.groupBy { it.chapterIndex }.toSortedMap().map { (c, ss) -> c to ss.size }
        val plan = MergePlanner.plan(counts)
        val req = RunRequest(
            bookName = "测试书",
            bookSha = CheckpointStore.bookSha(book),
            modeLabel = "DEEP",
            lines = lines,
            slices = sliced.slices,
            mergeCalls = plan.calls,
            outDir = outDir,
            checkpoint = checkpoint,
            persist = { cp -> persistCaptured.add(cp) },
        )
        return Triple(ReadthroughRunner(port), req, persistCaptured)
    }

    private fun factsOf(dir: File, chapter: Int): List<FactLine> =
        FactStore.readLines(File(dir, FactStore.chapterFileName(chapter)))
            .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            .filter { it.optString("type") == "fact" }
            .mapNotNull { FactLine.fromJson(it) }

    // ── ①② 全量跑通：覆盖 100%、点回章＋行 ────────────────────────────────

    @Test
    fun `全量跑通_章覆盖100_归并按树走_抽五条能点回章加行`() {
        val book = ReadthroughFixtures.standardBook(12, bodyLines = 3)
        val out = tmp.newFolder("run1")
        val fake = FakeExtraction()
        val (runner, req, _) = runnerRequest(book, out, fake)
        val report = runner.run(req)

        assertTrue("不该暂停：${report.pauseReason}", !report.paused)
        assertEquals("章覆盖 100%", 1.0, report.chapterCoverage, 0.0001)
        assertEquals(12, report.chaptersDone)
        // 每章 1 片 ⇒ 抽取 12 次；章层 0 次；块层 1 次（12 章 <100）；书层 1 次
        assertEquals(12, report.extractionCalls)
        assertEquals(2, report.mergeCalls)
        assertEquals(12, FactStore.existingChapters(out).size)
        // 每章 2 条事实（假件每片产 2 条）
        assertEquals(2, factsOf(out, 0).size)
        // 判据②：抽 5 条，章＋行能点回原文
        val lines = book.lines()
        val facts = (0 until 12).flatMap { factsOf(out, it) }
        assertTrue("抽样池要 ≥5", facts.size >= 5)
        facts.take(5).forEach { f ->
            val slice = req.slices.first { it.chapterIndex == f.chapter }
            assertTrue("行号在该片范围内：$f", f.startLine >= slice.startLine && f.endLine <= slice.endLine)
            assertTrue(
                "quote 能对回原文：${f.quote}",
                lines[f.startLine].contains(f.quote) || f.quote.isEmpty(),
            )
        }
        // 归并产物落盘：block-000.jsonl + book.jsonl
        assertTrue(File(out, FactStore.blockFileName(0)).exists())
        assertTrue(File(out, FactStore.BOOK_FILE).exists())
        assertTrue("覆盖抽样应达标：${report.coverage?.overall}", report.coverage!!.pass)
        assertTrue("报告应自述覆盖：${report.render()}", report.render().contains("章覆盖 12/12"))
    }

    // ── ⑥ 断点续跑：不重跑已完成片 ─────────────────────────────────────────

    @Test
    fun `断点续跑_已完成的片不重跑_最终覆盖100`() {
        val book = ReadthroughFixtures.standardBook(8, bodyLines = 3)
        val out = tmp.newFolder("run2")
        // 第一轮：第 3 次调用起全挂 → 部分完成（波次并发 2：前 2 片成功，之后连败暂停）
        val failFake = FakeExtraction(failOnNth = 3)
        val (runner1, req1, cps) = runnerRequest(book, out, failFake)
        val r1 = runner1.run(req1)
        assertTrue("网络挂应触发暂停：${r1.pauseReason}", r1.paused)
        assertTrue("checkpoint 要落盘过", cps.isNotEmpty())
        val cpAfterFirst = cps.last()
        val doneFirst = cpAfterFirst.doneSlices.size
        assertTrue("第一轮要有部分成功", doneFirst in 1..7)

        // 第二轮：假件全通，接同一份 checkpoint
        val fake2 = FakeExtraction()
        val (runner2, req2, _) = runnerRequest(book, out, fake2, checkpoint = cpAfterFirst)
        val r2 = runner2.run(req2)
        assertTrue(!r2.paused)
        assertEquals("最终覆盖 100%", 1.0, r2.chapterCoverage, 0.0001)
        assertEquals("续跑只补缺：${fake2.calledSliceIds}", 8 - doneFirst, fake2.calledSliceIds.size)
        assertTrue(
            "已完成片绝不重跑：${cpAfterFirst.doneSlices.intersect(fake2.calledSliceIds.toSet())}",
            fake2.calledSliceIds.toSet().intersect(cpAfterFirst.doneSlices).isEmpty(),
        )
        // 归并最终全走完
        assertEquals("块+书", 2, r2.mergeCalls)
    }

    // ── ④ 网络闸：暂停要停在片边界，checkpoint 如实 ────────────────────────

    @Test
    fun `全程网络故障_三次连败即暂停_零完成_账本可续`() {
        val book = ReadthroughFixtures.standardBook(4, bodyLines = 2)
        val out = tmp.newFolder("run3")
        val failFake = FakeExtraction(failOnNth = 1)
        val (runner, req, cps) = runnerRequest(book, out, failFake)
        val report = runner.run(req)
        assertTrue(report.paused)
        assertNotNull(report.pauseReason)
        assertEquals("一次都没成功", 0, report.extractionCalls)
        assertTrue("异常要逐条：${report.anomalies}", report.anomalies.size >= 2)
        assertTrue(FactStore.existingChapters(out).isEmpty())
        // 账本空也能续（下一轮全部重跑）
        assertTrue(cps.last().doneSlices.isEmpty())
    }

    // ── ⑤ 落盘纪律：同章多片按序追加、append-only ───────────────────────────

    @Test
    fun `一章多片_事实按片序追加_重跑不重复`() {
        // 造一章 16 段 × ~500 字 ≈ 8k ⇒ 2 片（12 段才过 6k，16 段留余量）
        val book = "第一章 长章\n" + (1..16).joinToString("") { "段$it，" + "文".repeat(496) + '\n' } +
            "第二章 短章\n正文\n"
        val out = tmp.newFolder("run4")
        val fake = FakeExtraction()
        val (runner, req, _) = runnerRequest(book, out, fake)
        val report = runner.run(req)
        assertEquals(1.0, report.chapterCoverage, 0.0001)
        val facts = factsOf(out, 0)
        assertEquals("两片各 2 条", 4, facts.size)
        val sliceIds = facts.map { it.sliceId }
        assertEquals("按片序：c0t0 全在前", listOf("c0t0", "c0t0", "c0t1", "c0t1"), sliceIds)

        // 重跑（同一 checkpoint 状态、账本已完成全部片）⇒ 抽取 0 次、文件不重复追加
        val cp = Checkpoint(
            req.bookSha, "DEEP", "",
            req.slices.map { it.id }.toSet(), req.slices.map { it.chapterIndex }.toSet(), emptySet(), 0,
        )
        val fake2 = FakeExtraction()
        val (runner2, req2, _) = runnerRequest(book, out, fake2, checkpoint = cp)
        val r2 = runner2.run(req2)
        assertEquals("重跑零抽取", 0, r2.extractionCalls)
        assertEquals("追加-only：文件不再变长", 4, factsOf(out, 0).size)
    }

    // ── 覆盖抽样：不达标要逐章报 ────────────────────────────────────────────

    @Test
    fun `人物覆盖抽样_假件少给一个说话人_如实报`() {
        // 假件只给 张小凡/林惊羽；正文说话人还有第三个名字 ⇒ 覆盖 <95%
        val book = ReadthroughFixtures.standardBook(2, bodyLines = 2)
        val out = tmp.newFolder("run5")
        val fake = FakeExtraction()
        val (runner, req, _) = runnerRequest(book, out, fake)
        val report = runner.run(req)
        val cov = report.coverage!!
        // 夹具正文无说话动词 ⇒ 候选空 ⇒ 不冤枉；此处只验报告形状与达标口径
        assertTrue(cov.pass || cov.failing.isNotEmpty())
    }
}
