// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百八十九批 · 接线判据（判据③④的**源码那一半** ＋ ack 注入的行为那一半）。
//
// 为什么要有源码断言：`update_plan` 的 ack 在引擎里才拼得出来（RunEngine 要 Android SQLite，
// JVM 起不来），所以「摘要真的接在 ack 尾巴上」与「670 的 summaryLine 真的有生产调用点」
// 这两件事照本仓门禁范式钉成结构性事实（同款范式见 ReflowWiringGateTest／LearnContextNoteGateTest）。
package com.example.zhengdao.baopu.state.summary

import com.example.zhengdao.baopu.PlanRules
import com.example.zhengdao.baopu.PlanRules.PlanItem
import com.example.zhengdao.baopu.PlanStore
import com.example.zhengdao.baopu.agent.PlanController
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.baopu.safety.DiaryEvent
import com.example.zhengdao.baopu.safety.DiaryKind
import com.example.zhengdao.baopu.state.track.CharacterTrack
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StateSummaryWiringTest {

    private val summaryDir = "src/main/java/com/example/zhengdao/baopu/state/summary"

    private fun repoFile(rel: String): File = File(rel).let { if (it.exists()) it else File("app/$rel") }

    private fun read(rel: String): String = repoFile(rel).readText()

    /** 去注释的源码（免得门禁被改注释绕过）。 */
    private fun codeOnly(src: String): String =
        src.split("\n").joinToString("\n") { it.substringBefore("//") }
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")

    private fun plan(total: Int, done: Int = 0): List<PlanItem> = (1..total).map { i ->
        PlanItem(
            text = "第${i}步：整理材料",
            status = if (i <= done) PlanRules.STATUS_DONE else PlanRules.STATUS_TODO,
        )
    }

    private fun action(what: String): DiaryEvent =
        DiaryEvent(DiaryKind.ACTION, 1_700_000_000_000L, "值守自动动作", what, "成功")

    @After
    fun tearDown() {
        StateSummaryWiring.resetForTest()
    }

    // ── 判据④：670 的 summaryLine 被调用（源码断言） ─────────────────────

    @Test
    fun `判据4源码断言_摘要包里真的有summaryLine调用点`() {
        val assembler = codeOnly(read("$summaryDir/RunStateAssembler.kt"))
        assertTrue(
            "670 的 summaryLine() 必须被生产代码调用（否则 670 仍是死代码）",
            assembler.contains("summaryLine()"),
        )
        assertTrue("要经由神轨实例调它", assembler.contains("track.summaryLine()"))
        assertTrue("入参必须是 670 的 CharacterTrack", assembler.contains("CharacterTrack"))
    }

    @Test
    fun `判据4源码断言_摘要接在update_plan的ack尾巴上`() {
        val controller = codeOnly(
            read("src/main/java/com/example/zhengdao/baopu/agent/PlanController.kt"),
        )
        assertTrue(
            "PlanController 的 ack 必须带状态摘要（653 的消费入口）",
            controller.contains("StateSummaryWiring.ackNote("),
        )
        // 只许一处：多一处就有第二份摘要真相
        assertEquals(
            "摘要注入点只许一处",
            1,
            Regex("""StateSummaryWiring\.ackNote\(""").findAll(controller).count(),
        )
    }

    @Test
    fun `判据2源码断言_摘要包不碰对话正文`() {
        val files = repoFile(summaryDir).listFiles()?.filter { it.extension == "kt" }.orEmpty()
        assertTrue("摘要包的文件没找到：$summaryDir", files.isNotEmpty())
        val forbidden = listOf("HistoryMessage", "userText", "MessagePart", "messages", "prompt")
        files.forEach { f ->
            val code = codeOnly(f.readText())
            forbidden.forEach { token ->
                assertFalse(
                    "${f.name} 不许读对话正文（判据②：摘要不含用户正文）：命中 $token",
                    code.contains(token),
                )
            }
        }
    }

    // ── 行为：ack 尾巴那一段 ──────────────────────────────────────────────

    @Test
    fun `没接线时ack一个字不加`() {
        StateSummaryWiring.resetForTest()
        assertEquals("", StateSummaryWiring.ackNote(plan(3, done = 1), "整理报表"))
    }

    @Test
    fun `接线后有账就出摘要且带670那一行`() {
        val track = CharacterTrack()
        track.tick()
        val line = track.summaryLine()
        StateSummaryWiring.attach(
            diary = { listOf(action("写入汇总表第一页")) },
            ledger = { emptyList() },
            unlearned = { listOf("这份表按含税还是不含税统计") },
            track = { track },
        )
        val note = StateSummaryWiring.ackNote(plan(3, done = 1), "整理报表")
        assertTrue("必须另起一行注入（不粘在上一句后面）：$note", note.startsWith("\n"))
        assertTrue("要有前缀：$note", note.contains(StateSummaryWiring.PREFIX))
        assertTrue("要有目标：$note", note.contains("目标：整理报表"))
        assertTrue("要有阶段：$note", note.contains("（1/3）"))
        assertTrue("要有刚完成与账行号：$note", note.contains("账行#1"))
        assertTrue("要有未学会：$note", note.contains("未学会#1"))
        assertTrue("要带 670 那一行：$note", note.contains(line))
        assertTrue("整段（含神轨行）不许超 400 字：${note.length}", note.length <= StateSummary.MAX_CHARS + 32)
    }

    @Test
    fun `空状态不往模型上下文里塞无`() {
        StateSummaryWiring.attach(
            diary = { emptyList() },
            ledger = { emptyList() },
            unlearned = { emptyList() },
            track = { null },
        )
        assertEquals("空状态 ⇒ 摘要本体是「无」，注入口不塞噪声", "", StateSummaryWiring.ackNote(emptyList(), null))
    }

    @Test
    fun `读账抛异常只丢那一段不炸`() {
        StateSummaryWiring.attach(
            diary = { listOf(action("写入汇总表第一页")) },
            ledger = { throw IllegalStateException("账坏了") },
            unlearned = { throw IllegalStateException("清单坏了") },
            track = { throw IllegalStateException("神轨坏了") },
        )
        val note = StateSummaryWiring.ackNote(plan(2, done = 1), "整理报表")
        assertTrue("起居注那一段要活下来：$note", note.contains("账行#1"))
        assertFalse("坏账不许出现在摘要里", note.contains("卡住"))
    }

    @Test
    fun `重复接线以最后一次为准`() {
        StateSummaryWiring.attach(
            diary = { listOf(action("旧的一行")) },
            ledger = { emptyList() },
            unlearned = { emptyList() },
        )
        StateSummaryWiring.attach(
            diary = { listOf(action("新的一行")) },
            ledger = { emptyList() },
            unlearned = { emptyList() },
        )
        val note = StateSummaryWiring.ackNote(plan(1), null)
        assertTrue("要以最后一次接线为准：$note", note.contains("新的一行"))
        assertFalse("旧的那次接线必须被替换：$note", note.contains("旧的一行"))
    }

    // ── 端到端：摘要真的出现在 `update_plan` 的 ack 里（不止源码断言） ──────

    @Test
    fun `端到端_update_plan的ack里真的带摘要与神轨行`() {
        val track = CharacterTrack()
        track.tick()
        val line = track.summaryLine()
        StateSummaryWiring.attach(
            diary = { listOf(action("写入汇总表第一页")) },
            ledger = { emptyList() },
            unlearned = { emptyList() },
            track = { track },
        )
        val stored = StringBuilder()
        val controller = PlanController(
            sessionId = { 7L },
            store = object : PlanStore {
                override fun insert(sessionId: Long, payload: String) {
                    stored.append(payload)
                }

                override fun latest(sessionId: Long): String? = stored.toString().ifBlank { null }
            },
            log = {},
        )
        val args = JSONObject().put(
            "items",
            JSONArray()
                .put(JSONObject().put("text", "第一步").put("status", "done"))
                .put(JSONObject().put("text", "第二步").put("status", "doing")),
        )
        val out = controller.submit(args)
        assertTrue("必须走成功回执：$out", out is ToolOutcome.Ok)
        val text = (out as ToolOutcome.Ok).text
        println("[689] update_plan ack 尾部：${text.takeLast(160)}")
        assertTrue("ack 里必须带摘要前缀：$text", text.contains(StateSummaryWiring.PREFIX))
        assertTrue("摘要的阶段数要对上（1 完成 / 2 步）：$text", text.contains("（1/2）"))
        assertTrue("神轨那一行也要在：$text", text.contains(line))
        assertTrue("落库的那一版计划还在（摘要没顶掉正文）", stored.toString().contains("第一步"))
    }
}
