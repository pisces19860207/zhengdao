// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十一批 · 剧本五件的**端到端**判据（临时工作区里真建工程、真写盘、真导出）。
//
// 判据与派工单逐条对上：
//   ① 导出 Fountain 能被读回器按规范读回来（并从盘上那份文件读，不拿内存字符串自证）；
//   ② 一场一次（请求 5 场 ⇒ 拒，且报出 5）；
//   ③ 未批大纲不许写正文；批过又改 ⇒ 批准作废、也要重批；
//   ④ 抽 3 场能点回原文（原文行原样带行号）；
//   ⑤ 反性格冲突必须报（负例：不列冲突就写 ⇒ 红）；
//   ⑥ 档位缺省不许默认「大胆再创作」。
package com.example.zhengdao.baopu.script

import com.example.zhengdao.baopu.FileOpLog
import com.example.zhengdao.baopu.agent.AskUserOption
import com.example.zhengdao.baopu.agent.ScriptApproveTool
import com.example.zhengdao.baopu.agent.ScriptExportTool
import com.example.zhengdao.baopu.agent.ScriptOutlineTool
import com.example.zhengdao.baopu.agent.ScriptTraceTool
import com.example.zhengdao.baopu.agent.ScriptWriteTool
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.core.ZhengdaoDirs
import java.io.File
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

class ScriptToolsTest {

    /** 取 Parsed 的值（坏 ⇒ 断言失败并带上人话）；避免 `as Parsed.Ok` 裸转型。 */
    private fun <T> valueOf(parsed: Parsed<T>): T = when (parsed) {
        is Parsed.Ok -> parsed.value
        is Parsed.Bad -> throw AssertionError(parsed.reason)
    }

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var ws: File

    private val title = "雨夜"

    /** 原文（行号＝这段文本的绝对行号，从 1 数）：
     *  L1 第一章 夜访 / L2 沈砚… / L3 林夏… / L4 空 / L5 第二章 雨后 / L6 沈砚… / L7 林夏… / L8 空 */
    private val novel = listOf(
        "第一章 夜访",
        "沈砚坐在窗边，一句话没说。",
        "林夏推门进来，把伞靠在桌边。",
        "",
        "第二章 雨后",
        "沈砚撑着伞站在巷口，仍旧不说话。",
        "林夏追出来，喊他的名字。",
        "",
    ).joinToString("\n")

    @Before
    fun setUp() {
        ws = tmp.newFolder("ws")
        File(ws, "书.txt").writeText(novel, Charsets.UTF_8)
        FileOpLog.resetForTest()
        FileOpLog.enterSession(621L)
    }

    @After
    fun tearDown() {
        FileOpLog.resetForTest()
    }

    // ── 构造件 ─────────────────────────────────────────────────────────────

    private fun outlineArgs(tier: String? = "rework", scenes: JSONArray = twoScenes()): JSONObject {
        val chars = JSONArray()
            .put(
                JSONObject().put("name", "沈砚").put("traits", JSONArray().put("寡言"))
                    .put(
                        "forbids",
                        JSONArray().put(JSONObject().put("phrase", "滔滔不绝").put("source", "第一章 L2")),
                    ),
            )
            .put(JSONObject().put("name", "林夏").put("traits", JSONArray().put("爽利")))
        return JSONObject()
            .put("project", title)
            .put("mode", "adapt")
            .put("source", "书.txt")
            .put("characters", chars)
            .put("scenes", scenes)
            .also { o -> tier?.let { o.put("tier", it) } }
    }

    private fun twoScenes(): JSONArray = JSONArray()
        .put(
            JSONObject().put("summary", "雨夜等候")
                .put("source", JSONObject().put("chapter", 1).put("from", 2).put("to", 4)),
        )
        .put(
            JSONObject().put("summary", "巷口道别")
                .put("source", JSONObject().put("chapter", 2).put("from", 6).put("to", 7)),
        )

    private fun sceneArgs(id: String, beats: JSONArray, conflicts: JSONArray? = null, rewrite: Boolean = false): JSONObject {
        val no = id.filter { it.isDigit() }.toInt()
        val scene = JSONObject()
            .put("id", id)
            .put("no", no)
            .put("location", if (no == 1) "咖啡馆" else "巷口")
            .put("time", if (no == 1) "夜" else "日")
            .put("interior", no == 1)
            .put("characters", JSONArray().put("沈砚").put("林夏"))
            .put("beats", beats)
            .also { o -> conflicts?.let { o.put("conflicts", it) } }
        return JSONObject().put("project", title).put("scene", scene).put("rewrite", rewrite)
    }

    private fun action(text: String, src: Int): JSONObject =
        JSONObject().put("kind", "action").put("text", text).put("src", src)

    private fun dialogue(who: String, text: String, paren: String?, src: Int): JSONObject = JSONObject()
        .put("kind", "dialogue").put("who", who).put("text", text).put("src", src)
        .also { o -> paren?.let { o.put("paren", it) } }

    private fun productFiles(): List<String> =
        File(ws, ZhengdaoDirs.PRODUCTS_DIR).listFiles().orEmpty().map { it.name }

    private fun ok(out: ToolOutcome): String {
        assertTrue("期待成功，实际：$out", out is ToolOutcome.Ok)
        return (out as ToolOutcome.Ok).text
    }

    private fun failed(out: ToolOutcome): String {
        assertTrue("期待被拒，实际：$out", out is ToolOutcome.Failed)
        return (out as ToolOutcome.Failed).error
    }

    private fun approve(verdict: String = ScriptApproveTool.OPT_APPROVE): ToolOutcome =
        ScriptApproveTool.run(
            JSONObject().put("project", title),
            ws,
            { req -> req.options.firstOrNull { it.id == verdict } ?: AskUserOption(verdict, verdict) },
            2_000L,
        )

    // ── ⑥ 档位缺省不许默认 ────────────────────────────────────────────────

    @Test
    fun `改编不给档位_大纲就拒且不默认大胆再创作`() {
        val reason = failed(ScriptOutlineTool.run(outlineArgs(tier = null), ws, 1_000L))
        assertTrue("要说清三档：$reason", reason.contains("大胆再创作"))
        assertTrue("要说明缺省不接受：$reason", reason.contains("必须由人选"))
        assertFalse("拒了就不该建出工程", File(ws, "${ScriptFormat.ROOT_DIR}/$title").isDirectory)
    }

    // ── ③ 未批大纲不许写正文 ─────────────────────────────────────────────

    @Test
    fun `未批大纲_写正文被拒`() {
        ok(ScriptOutlineTool.run(outlineArgs(), ws, 1_000L))
        val reason = failed(
            ScriptWriteTool.run(sceneArgs("S01", JSONArray().put(action("沈砚坐在窗边。", 2))), ws, 1_500L),
        )
        assertTrue("要指路去批大纲：$reason", reason.contains("还没批"))
        assertTrue("要明说一个字都没写：$reason", reason.contains("一个字都没写"))
        assertFalse("场次文件不该被创建", File(ws, "${ScriptFormat.ROOT_DIR}/$title/${ScriptFormat.SCENES_FILE}").exists())
    }

    @Test
    fun `批过之后又改大纲_批准作废`() {
        ok(ScriptOutlineTool.run(outlineArgs(), ws, 1_000L))
        ok(approve())
        // 给大纲加一场 = 换一版 ⇒ 旧批准必须失效
        val more = twoScenes().put(
            JSONObject().put("summary", "三年后")
                .put("source", JSONObject().put("chapter", 2).put("from", 7).put("to", 8)),
        )
        ok(ScriptOutlineTool.run(outlineArgs(scenes = more), ws, 2_500L))
        val reason = failed(
            ScriptWriteTool.run(sceneArgs("S01", JSONArray().put(action("沈砚坐在窗边。", 2))), ws, 3_000L),
        )
        assertTrue("要指出批的是哪一版、现在哪一版：$reason", reason.contains("作废"))
    }

    @Test
    fun `批准只能由用户点_模型身份写不进去`() {
        ok(ScriptOutlineTool.run(outlineArgs(), ws, 1_000L))
        val dir = File(ws, "${ScriptFormat.ROOT_DIR}/$title")
        val bad = ScriptStore.approve(dir, WHO_MODEL, 1_100L)
        assertTrue("MODEL 不能批：$bad", bad is Parsed.Bad)
        assertTrue("大纲仍是未批", ScriptStore.readOutline(dir).let { it is Parsed.Ok && it.value.approved == null })
        // 卡上点「先不改」也不算批
        ok(ScriptOutlineTool.run(outlineArgs(), ws, 1_200L))
        val declined = approve(ScriptApproveTool.OPT_LATER)
        val declinedText = ok(declined)
        assertTrue("点了先不改 ⇒ 必须明说还没批：$declinedText", declinedText.contains("还没批"))
        assertTrue(
            "点了先不改 ⇒ 大纲仍是未批",
            ScriptStore.readOutline(dir).let { it is Parsed.Ok && it.value.approved == null },
        )
    }

    // ── ② 一场一次 ───────────────────────────────────────────────────────

    @Test
    fun `请求五场_拒并报出五场`() {
        ok(ScriptOutlineTool.run(outlineArgs(), ws, 1_000L))
        ok(approve())
        val args = JSONObject().put("project", title).put(
            "scenes",
            JSONArray().also { a ->
                repeat(5) { a.put(JSONObject().put("summary", "第${it + 1}场")) }
            },
        )
        val reason = failed(ScriptWriteTool.run(args, ws, 2_000L))
        assertTrue("要报出 5 场：$reason", reason.contains("5 场"))
    }

    // ── ⑤ 反性格冲突必须报 ───────────────────────────────────────────────

    @Test
    fun `人物做禁区里的事_不列冲突就拒_列了就过`() {
        ok(ScriptOutlineTool.run(outlineArgs(), ws, 1_000L))
        ok(approve())
        val beats = JSONArray()
            .put(action("沈砚抬起头。", 2))
            .put(dialogue("沈砚", "他把这三年的事滔滔不绝地讲了一遍。", null, 3))
        val reason = failed(ScriptWriteTool.run(sceneArgs("S01", beats), ws, 2_000L))
        assertTrue("要点名人、点禁区词、点原文依据：$reason", reason.contains("滔滔不绝") && reason.contains("第一章 L2"))
        assertTrue("要给出路（改写或列冲突）：$reason", reason.contains("conflicts"))

        val listed = JSONArray().put(
            JSONObject().put("who", "沈砚")
                .put("source", "第一章 L2（他在窗边一句话没说）")
                .put("here", "他把这三年的事滔滔不绝地讲了一遍")
                .put("why", "把三章的沉默压成一场告白，便于收尾"),
        )
        val text = ok(ScriptWriteTool.run(sceneArgs("S01", beats, listed), ws, 2_100L))
        assertTrue("冲突项必须逐条回给模型（不许静默吸收）：$text", text.contains("冲突项 1 条"))
        assertTrue(text.contains("第一章 L2"))
    }

    // ── ① 导出能读回 ＋ ④ 抽场点回原文 ──────────────────────────────────

    @Test
    fun `导出两份都能落盘_fountain能从盘上读回`() {
        ok(ScriptOutlineTool.run(outlineArgs(), ws, 1_000L))
        ok(approve())
        ok(
            ScriptWriteTool.run(
                sceneArgs(
                    "S01",
                    JSONArray().put(action("沈砚坐在窗边，杯子已经凉了。", 2))
                        .put(dialogue("林夏", "你来晚了。", "低声", 3)),
                ),
                ws,
                2_000L,
            ),
        )
        ok(
            ScriptWriteTool.run(
                sceneArgs("S02", JSONArray().put(action("雨还没停。", 6))).also { it.put("rewrite", false) },
                ws,
                2_100L,
            ),
        )
        val text = ok(ScriptExportTool.run(JSONObject().put("project", title), ws, 3_000L))
        assertTrue("要报出两份产物：$text", text.contains(".fountain") && text.contains(".md"))

        val fountain = File(ws, ZhengdaoDirs.PRODUCTS_DIR).listFiles().orEmpty()
            .firstOrNull { it.name.endsWith(".fountain") }
        assertTrue("产物目录里要有 .fountain：${productFiles()}", fountain != null)
        // 从**盘上那份文件**读回来（不拿内存字符串自证）
        val back = FountainIn.parse(fountain!!.readText(Charsets.UTF_8))
        assertTrue("盘上那份要能读回：$back", back is Parsed.Ok)
        val parsed = valueOf(back)
        assertEquals(2, parsed.size)
        assertEquals("INT. 咖啡馆 - 夜", parsed[0].heading)
        assertEquals("EXT. 巷口 - 日", parsed[1].heading)
        assertEquals(BeatKind.DIALOGUE, parsed[0].beats[1].kind)
        assertEquals("低声", parsed[0].beats[1].paren)
        assertTrue("改编要另出溯源表：${productFiles()}", productFiles().any { it.contains("溯源") })
    }

    @Test
    fun `手改坏一行_jsonl_导出拒且不出文件`() {
        ok(ScriptOutlineTool.run(outlineArgs(), ws, 1_000L))
        ok(approve())
        val dir = File(ws, "${ScriptFormat.ROOT_DIR}/$title")
        // 绕过 script_write 的校验，直接往真源里塞一条"对白以 > 开头"的场：
        // 写出去会被 Fountain 读成转场 ⇒ 读回自检必须拦住，且**不许**落一份坏文件。
        val bad = Scene(
            id = "S01",
            no = 1,
            location = "咖啡馆",
            interior = true,
            time = "夜",
            characters = listOf("林夏"),
            beats = listOf(Beat(BeatKind.DIALOGUE, "> 切至巷口", "林夏", null, null)),
            conflicts = emptyList(),
            source = SourceRef(1, 2, 4),
            tier = ScriptTier.REWORK,
            rewrite = false,
            atMs = 0L,
        )
        assertTrue(ScriptStore.appendScene(dir, bad))
        val reason = failed(ScriptExportTool.run(JSONObject().put("project", title), ws, 3_000L))
        assertTrue("要如实说读不回：$reason", reason.contains("读回"))
        assertFalse("读不回来就不许出文件：${productFiles()}", productFiles().any { it.endsWith(".fountain") })
    }

    @Test
    fun `抽三场_把原文行原样贴出来`() {
        ok(ScriptOutlineTool.run(outlineArgs(), ws, 1_000L))
        ok(approve())
        ok(ScriptWriteTool.run(sceneArgs("S01", JSONArray().put(action("沈砚坐在窗边。", 2))), ws, 2_000L))
        val text = ok(ScriptTraceTool.run(JSONObject().put("project", title), ws))
        assertTrue("要给行号与原话：$text", text.contains("L2｜沈砚坐在窗边，一句话没说。"))
        assertTrue("第二场也要能点回去：$text", text.contains("L6｜沈砚撑着伞站在巷口，仍旧不说话。"))
        assertTrue("没写的场要如实标记：$text", text.contains("还没写"))
    }

    // ── 原创链路（不碰原文，也不需要档位） ───────────────────────────────

    @Test
    fun `原创_建大纲批过写一场导出`() {
        val args = JSONObject().put("project", "原创片").put("mode", "original")
            .put(
                "scenes",
                JSONArray().put(JSONObject().put("summary", "开场：雨夜等人"))
                    .put(JSONObject().put("summary", "结尾：她走了")),
            )
        val outline = ok(ScriptOutlineTool.run(args, ws, 1_000L))
        assertTrue("原创要说没有档位：$outline", outline.contains("原创"))
        val approved = ScriptApproveTool.run(
            JSONObject().put("project", "原创片"),
            ws,
            { req -> req.options.first() },
            2_000L,
        )
        ok(approved)
        ok(
            ScriptWriteTool.run(
                JSONObject().put("project", "原创片").put(
                    "scene",
                    JSONObject().put("id", "S01").put("location", "咖啡馆").put("time", "夜")
                        .put("characters", JSONArray().put("她"))
                        .put(
                            "beats",
                            // 原创没有原文，一拍都不许标 src 行号（见 ScriptDomainTest 的形状判据）
                            JSONArray().put(JSONObject().put("kind", "action").put("text", "她把伞收起来。"))
                                .put(
                                    JSONObject().put("kind", "dialogue").put("who", "她").put("text", "我来晚了。"),
                                ),
                        ),
                ),
                ws,
                3_000L,
            ),
        )
        val exported = ok(ScriptExportTool.run(JSONObject().put("project", "原创片"), ws, 4_000L))
        assertTrue(exported.contains("已导出 1 场"))
        assertTrue("原创没有溯源表", !productFiles().any { it.contains("原创片-溯源") })
        val trace = ScriptTraceTool.run(JSONObject().put("project", "原创片"), ws)
        assertTrue("原创点不回原文（如实说）", trace is ToolOutcome.Failed)
    }
}
