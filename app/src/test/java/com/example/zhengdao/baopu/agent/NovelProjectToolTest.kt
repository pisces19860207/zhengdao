// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百五十五批判据测试（派工单 §3）：
// - 新建项目/章节/大纲/字数；续写追加不覆盖；一致性提示；空/坏项目可读；
// - 负例：覆盖正文（二次创建必拒）、绕过确认（novel_create 必须 CONFIRM）、
//   看项目与续写必须 FREE 只读（API 面上就没有写路径）。
// 全部 JVM 直测（org.json 在测试类路径上有真实现，GuardPipelineTest 同款）。
package com.example.zhengdao.baopu.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class NovelProjectToolTest {

    private fun tmp(): File = Files.createTempDirectory("novel-v1").toFile()

    private fun create(root: File, vararg pairs: Pair<String, Any>): ToolOutcome =
        NovelCreateTool.create(root, JSONObject().apply { pairs.forEach { put(it.first, it.second) } })

    private fun writeChapter(root: File, title: String, fileName: String, body: String) {
        val dir = File(File(File(root, NOVEL_ROOT_DIR), title), NOVEL_BODY_DIR)
        dir.mkdirs()
        File(dir, fileName).writeText(body)
    }

    // ── W1 新建项目 ─────────────────────────────────────────────────────

    @Test
    fun `新建项目_骨架齐全_二次创建拒绝不覆盖`() {
        val root = tmp()
        val r = create(root, "title" to "测试书", "outline" to "三卷大纲", "chapter_target_words" to 2500)
        assertTrue("建项目成功", r is ToolOutcome.Ok)
        val dir = File(File(root, NOVEL_ROOT_DIR), "测试书")
        assertTrue("book.json 存在", File(dir, NOVEL_META_FILE).isFile)
        assertTrue("正文目录存在", File(dir, NOVEL_BODY_DIR).isDirectory)
        val meta = File(dir, NOVEL_META_FILE).readText()
        assertTrue("元数据带格式标记", meta.contains("zhengdao-novel/1"))
        assertTrue("大纲落进元数据", meta.contains("三卷大纲"))
        val again = create(root, "title" to "测试书")
        assertTrue("同名项目拒绝（不覆盖）", again is ToolOutcome.Failed)
        assertTrue((again as ToolOutcome.Failed).error.contains("已存在"))
    }

    @Test
    fun `新建项目_书名非法_可读拒绝`() {
        val root = tmp()
        listOf("", "  ", "a/b", "c\\d", "e:f", "..", ".").forEach { bad ->
            val r = create(root, "title" to bad)
            assertTrue("非法书名「$bad」必须拒", r is ToolOutcome.Failed)
        }
    }

    @Test
    fun `新建项目_人物每行一条_全角半角冒号都认`() {
        val root = tmp()
        create(root, "title" to "人物书", "characters" to "张三：主角\n李四:配角\n路人甲")
        val metaText = File(File(File(root, NOVEL_ROOT_DIR), "人物书"), NOVEL_META_FILE).readText()
        val chars = JSONObject(metaText).optJSONArray("characters")!!
        assertEquals(3, chars.length())
        assertEquals("张三", chars.getJSONObject(0).optString("name"))
        assertEquals("主角", chars.getJSONObject(0).optString("desc"))
        assertEquals("李四", chars.getJSONObject(1).optString("name"))
        assertEquals("配角", chars.getJSONObject(1).optString("desc"))
        assertEquals("路人甲", chars.getJSONObject(2).optString("name"))
    }

    @Test
    fun `负例_绕过确认_novel_create必须是CONFIRM`() {
        assertEquals(ToolPermission.CONFIRM, NovelCreateTool.spec({ File(".") }).permission)
    }

    @Test
    fun `负例_看项目与续写必须FREE只读_没有写路径`() {
        val project = NovelProjectTool.spec({ File(".") })
        assertEquals(ToolPermission.FREE, project.permission)
        assertTrue(project.readOnly)
        val cont = NovelContinueTool.spec({ File(".") })
        assertEquals(ToolPermission.FREE, cont.permission)
        assertTrue(cont.readOnly)
    }

    // ── W1 项目总览（目录/字数/大纲可查看；空/坏项目可读提示）────────────

    @Test
    fun `总览_章节字数与目标差_全部可查`() {
        val root = tmp()
        create(root, "title" to "总览书", "chapter_target_words" to 100, "outline" to "全书大纲一卷")
        writeChapter(root, "总览书", "第001章-开端.md", "第一章 开端\n\n他推开门，风灌了进来。")
        writeChapter(root, "总览书", "第002章-相遇.md", "第二章 相遇\n\n她站在桥头。")
        val r = NovelProjectTool.overview(root, "小说/总览书")
        assertTrue(r is ToolOutcome.Ok)
        val text = (r as ToolOutcome.Ok).text
        assertTrue("章文件在目录里", text.contains("第001章-开端.md"))
        assertTrue("实际字数", text.contains("共 ") && text.contains(" 字"))
        assertTrue("对比目标（差值符号）", text.contains("+") || text.contains("-"))
        assertTrue("大纲可查", text.contains("全书大纲一卷"))
    }

    @Test
    fun `总览_空项目_可读提示不是崩溃`() {
        val root = tmp()
        create(root, "title" to "空书")
        val r = NovelProjectTool.overview(root, "小说/空书")
        assertTrue(r is ToolOutcome.Ok)
        assertTrue((r as ToolOutcome.Ok).text.contains("还没有章节"))
    }

    @Test
    fun `总览_路径不对或元数据坏_可读拒绝`() {
        val root = tmp()
        assertTrue(NovelProjectTool.overview(root, "小说/不存在") is ToolOutcome.Failed)
        // 单文件书 ⇒ 指去 book_outline，不另造章节解析
        File(root, "大书.txt").writeText("正文")
        val single = NovelProjectTool.overview(root, "大书.txt")
        assertTrue(single is ToolOutcome.Failed)
        assertTrue((single as ToolOutcome.Failed).error.contains("book_outline"))
        // 坏元数据 ⇒ 可读拒绝，不抛异常
        val dir = File(File(root, NOVEL_ROOT_DIR), "坏书").apply { mkdirs() }
        File(dir, NOVEL_META_FILE).writeText("{ not json")
        val bad = NovelProjectTool.overview(root, "小说/坏书")
        assertTrue("坏文件可读提示（不崩溃）", bad is ToolOutcome.Failed)
        assertTrue((bad as ToolOutcome.Failed).error.contains("JSON"))
        // 缺格式标记同理
        File(dir, NOVEL_META_FILE).writeText("{\"title\":\"x\"}")
        assertTrue(NovelProjectTool.overview(root, "小说/坏书") is ToolOutcome.Failed)
    }

    // ── W2 续写（接上下文/不覆盖/一致性提示）────────────────────────────

    @Test
    fun `续写简报_接哪章哪段_给append与新章两条路`() {
        val root = tmp()
        create(root, "title" to "续写书", "chapter_target_words" to 500)
        writeChapter(root, "续写书", "第001章-开端.md", "第一章\n\n他在山门扫了十年地。\n今天师父叫他进去。")
        val r = NovelContinueTool.brief(root, "小说/续写书", 0)
        assertTrue(r is ToolOutcome.Ok)
        val text = (r as ToolOutcome.Ok).text
        assertTrue("接哪一章", text.contains("第001章-开端.md"))
        assertTrue("接哪一段（末段原文）", text.contains("今天师父叫他进去"))
        assertTrue("追加路径", text.contains("op=append"))
        assertTrue("新章路径", text.contains("write_file"))
        assertFalse("绝不建议覆盖参数", text.contains("overwrite"))
    }

    @Test
    fun `续写简报_一致性提示_没出场的人物点名_出场的闭嘴`() {
        val root = tmp()
        create(root, "title" to "一致书", "characters" to "张三：主角\n王五：缺席者")
        writeChapter(root, "一致书", "第001章.md", "第一章\n\n张三抬头看天。")
        val text = (NovelContinueTool.brief(root, "小说/一致书", 0) as ToolOutcome.Ok).text
        assertTrue("缺席人物被提示", text.contains("王五 在最近一章没有出场"))
        assertFalse("出场的人物不在提示里", text.contains("张三 在最近一章没有出场"))
    }

    @Test
    fun `续写简报_没有正文_给写第一章的提示`() {
        val root = tmp()
        create(root, "title" to "白板书")
        val r = NovelContinueTool.brief(root, "小说/白板书", 0)
        assertTrue(r is ToolOutcome.Failed)
        assertTrue((r as ToolOutcome.Failed).error.contains("第001章"))
    }

    // ── 字数口径 ────────────────────────────────────────────────────────

    @Test
    fun `字数口径_非空白字符数`() {
        assertEquals(4, NovelProjectStore.wordCount("第一 章一\n"))
        assertEquals(0, NovelProjectStore.wordCount(" \n\t"))
    }
}
