// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.readthrough.Checkpoint
import com.example.zhengdao.baopu.readthrough.CheckpointStore
import com.example.zhengdao.baopu.readthrough.ExtractionPort
import com.example.zhengdao.baopu.readthrough.FactStore
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 通读工具两件单测（610）。
 *
 * 钉住的判据：③ 略读漏章必须列出；⑤ 预算超限**不启动**（run 侧再闸，假件零调用）；
 * ⑦ 重跑只补缺章（账本丢了按已有事实文件补种子）；装配层没接模型出口 ⇒ 如实说跑不了。
 */
class NovelReadthroughToolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File

    /** 与 Runner 同款假件：按提示词头产事实；归并提示词回摘要。抽取与归并分开计数。 */
    private class FakePort(private val alwaysFail: Boolean = false) : ExtractionPort {
        val calls = AtomicInteger(0)
        val sliceCalls = AtomicInteger(0)
        override fun call(prompt: String): String {
            calls.incrementAndGet()
            if (alwaysFail) throw RuntimeException("模拟网络故障")
            if (prompt.contains("归并器")) {
                return JSONObject().put("summary", "摘要").toString()
            }
            sliceCalls.incrementAndGet()
            val m = Regex("""第 (\d+) 章[^】\n]*第 (\d+)/(\d+) 片（行 (\d+)–(\d+)""").find(prompt)!!
            val chapter = m.groupValues[1].toInt()
            val a = m.groupValues[4].toInt()
            val b = m.groupValues[5].toInt()
            val body = Regex("""^(\d+)\t(.*)$""", RegexOption.MULTILINE).find(prompt)!!
            return JSONArray().put(
                JSONObject()
                    .put("chapter", chapter)
                    .put("start_line", a.coerceAtMost(b)).put("end_line", b)
                    .put("subject", "张小凡").put("subject_id", "张小凡#1")
                    .put("predicate", "路过").put("object", "山道")
                    .put("quote", body.groupValues[2].take(10)),
            ).toString()
        }
    }

    @Before
    fun setup() {
        root = tmp.newFolder("workspace")
        NovelReadthroughTool.env = null
    }

    @After
    fun teardown() {
        NovelReadthroughTool.env = null
    }

    private fun put(rel: String, text: String, charset: Charset = Charsets.UTF_8): File {
        val f = File(root, rel)
        f.parentFile?.mkdirs()
        f.writeBytes(text.toByteArray(charset))
        return f
    }

    private fun planArgs(path: String, mode: String = "deep", query: String = "", vararg extra: Pair<String, Any>): JSONObject {
        val o = JSONObject().put("path", path)
        if (mode != "deep") o.put("mode", mode)
        if (query.isNotEmpty()) o.put("query", query)
        extra.forEach { (k, v) -> o.put(k, v) }
        return o
    }

    private fun text(r: ToolOutcome): String = when (r) {
        is ToolOutcome.Ok -> r.text
        is ToolOutcome.Failed -> r.error
        is ToolOutcome.Rejected -> r.reason
    }

    private fun plan(path: String, vararg extra: Pair<String, Any>): ToolOutcome =
        NovelReadthroughTool.planSpec { root }.executor(planArgs(path, extra = extra))

    private fun run(path: String, vararg extra: Pair<String, Any>): ToolOutcome =
        NovelReadthroughTool.runSpec { root }.executor(planArgs(path, extra = extra))

    private val book12 = (1..12).joinToString("") { "第${it}章 章$it\n张小凡道：「第$it 回的话。」\n叙述一小段。\n" }

    // ── plan ────────────────────────────────────────────────────────────────

    @Test
    fun `plan_报编码_预算允许_归并层数_输出目录`() {
        put("投递/书.txt", book12)
        val t = text(plan("投递/书.txt"))
        assertTrue("编码段：$t", t.contains("编码"))
        assertTrue("预算允许：$t", t.contains("允许开跑"))
        assertTrue("层数段：$t", t.contains("4 层"))
        assertTrue("输出目录按 157 收口落产物：$t", t.contains("产物/通读/"))
        assertTrue("片数：$t", t.contains("12 片"))
    }

    @Test
    fun `plan_略读_漏章逐段列出`() {
        put("投递/书.txt", book12)
        val t = text(plan("投递/书.txt", "mode" to "skim", "query" to "章3 章5"))
        assertTrue("要报选中数：$t", t.contains("选中 2 章"))
        assertTrue("漏章要列出（不只给个数）：$t", t.contains("漏 10 章"))
        // 章号全书 0 起（与 chapter-NNNN.jsonl、read_file 行号同口径）
        assertTrue("漏章区间：$t", t.contains("0–1、3、5–11"))
    }

    @Test
    fun `plan_预算超限_拒绝且明说不启动`() {
        put("投递/书.txt", book12)
        val t = text(plan("投递/书.txt", "max_calls" to 2, "max_tokens" to 500))
        assertTrue("要拒绝：$t", t.contains("拒绝"))
        assertTrue("要说不启动：$t", t.contains("不启动"))
        assertTrue("原因要点名调用数：$t", t.contains("调用数"))
    }

    @Test
    fun `plan_缺query的略读_拒`() {
        put("投递/书.txt", book12)
        assertTrue(text(plan("投递/书.txt", "mode" to "skim")).contains("query"))
    }

    @Test
    fun `plan_文件不存在_拒`() {
        assertTrue(text(plan("没有/这书.txt")).contains("不存在"))
    }

    // ── run ─────────────────────────────────────────────────────────────────

    @Test
    fun `run_全量跑通_事实与账本落盘_章覆盖100`() {
        val f = put("投递/书.txt", book12)
        NovelReadthroughTool.env = NovelReadthroughTool.Env(FakePort())
        val t = text(run("投递/书.txt"))
        assertTrue("要报完成：$t", t.contains("通读完成"))
        assertTrue("章覆盖 100%：$t", t.contains("12/12"))
        // 落盘：产物/通读/书/chapter-0000.jsonl + .progress.json
        val out = File(root, "产物/通读/书")
        assertTrue(File(out, FactStore.chapterFileName(0)).exists())
        assertTrue(File(out, FactStore.PROGRESS_FILE).exists())
        val cp = CheckpointStore.fromJson(File(out, FactStore.PROGRESS_FILE).readText())!!
        assertEquals(12, cp.doneChapters.size)
        assertEquals(12, cp.doneSlices.size)
        assertTrue(File(out, FactStore.BOOK_FILE).exists())
        assertTrue("f 存在以确保书是这套路径读的", f.exists())
    }

    @Test
    fun `run_预算超限_不启动_假件零调用`() {
        put("投递/书.txt", book12)
        val fake = FakePort()
        NovelReadthroughTool.env = NovelReadthroughTool.Env(fake)
        val r = run("投递/书.txt", "max_calls" to 2, "max_tokens" to 500)
        assertTrue(text(r).contains("预算超限"))
        assertTrue(text(r).contains("不启动"))
        assertEquals("超额不启动 ⇒ 一次都不许调", 0, fake.calls.get())
    }

    @Test
    fun `run_重跑只补缺章_账本丢了按已有文件补种子`() {
        put("投递/书.txt", book12)
        NovelReadthroughTool.env = NovelReadthroughTool.Env(FakePort())
        run("投递/书.txt")
        // 第一轮：12 章全落了
        val out = File(root, "产物/通读/书")
        assertEquals(12, FactStore.existingChapters(out).size)
        // 账本删掉（模拟「checkpoint 丢了」），再跑一轮
        File(out, FactStore.PROGRESS_FILE).delete()
        val fake2 = FakePort()
        NovelReadthroughTool.env = NovelReadthroughTool.Env(fake2)
        val t = text(run("投递/书.txt"))
        assertEquals("已落章不重抽（补缺章口径；归并照走）", 0, fake2.sliceCalls.get())
        assertTrue("第二轮应跳过全部片：$t", t.contains("跳过已完成的 12 片"))
    }

    @Test
    fun `run_网络全挂_暂停报告_账本可续`() {
        put("投递/书.txt", book12)
        NovelReadthroughTool.env = NovelReadthroughTool.Env(FakePort(alwaysFail = true))
        val t = text(run("投递/书.txt"))
        assertTrue("要如实报暂停：$t", t.contains("已暂停"))
        assertTrue("要指路续跑：$t", t.contains("续跑"))
        // 账本在（空账），异常逐条落账
        val out = File(root, "产物/通读/书")
        assertTrue(File(out, FactStore.PROGRESS_FILE).exists())
        assertTrue(File(out, FactStore.ANOMALY_FILE).exists())
    }

    @Test
    fun `run_没接模型出口_如实说跑不了`() {
        put("投递/书.txt", book12)
        NovelReadthroughTool.env = null
        val r = run("投递/书.txt")
        assertTrue(text(r).contains("模型出口没接上"))
    }

    @Test
    fun `run_略读零命中_拒并指路`() {
        put("投递/书.txt", book12)
        NovelReadthroughTool.env = NovelReadthroughTool.Env(FakePort())
        val r = run("投递/书.txt", "mode" to "skim", "query" to "灵潮复苏")
        assertTrue(text(r).contains("0 章命中"))
    }

    @Test
    fun `run_out_dir越界_拒`() {
        put("投递/书.txt", book12)
        NovelReadthroughTool.env = NovelReadthroughTool.Env(FakePort())
        val r = run("投递/书.txt", "out_dir" to "../外面")
        assertTrue(text(r).contains("越出工作区"))
    }

    @Test
    fun `run_不续跑_resume_false_清账重跑`() {
        put("投递/书.txt", book12)
        NovelReadthroughTool.env = NovelReadthroughTool.Env(FakePort())
        run("投递/书.txt")
        val fake2 = FakePort()
        NovelReadthroughTool.env = NovelReadthroughTool.Env(fake2)
        val t = text(run("投递/书.txt", "resume" to false))
        assertTrue("清账重跑：全部重抽", t.contains("跳过已完成的 0 片"))
        assertEquals("12 片抽取 + 块/书归并 2 次", 14, fake2.calls.get())
        // 已落 jsonl 不删（append-only）：章 0 又追加 1 条事实 ⇒ 事实共 2 条
        //（本地章摘要也算一行账，单独数）
        val out = File(root, "产物/通读/书")
        val factLines = FactStore.readLines(File(out, FactStore.chapterFileName(0)))
            .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            .count { it.optString("type") == "fact" }
        assertEquals(2, factLines)
    }
}
