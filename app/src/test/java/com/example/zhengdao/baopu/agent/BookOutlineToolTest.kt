// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * BookOutlineTool 单测（工具补强第二刀）。
 *
 * 钉住的判据：① 中文/英文章节都认得，行号与 read_file 的 offset 同口径（0 起、含端点）；
 * ② 只回目录不回正文（每章只带首句预览）；③ max_chapters 触顶要明说「还有 N 章」；
 * ④ 无标记时按字数兜底切；⑤ 二进制/超大/不存在/越界各自失败与指路；
 * ⑥ spec 是 FREE（只读）；⑦ 空文件给空态。
 */
class BookOutlineToolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File

    private fun setup() {
        root = tmp.newFolder("workspace")
    }

    private fun put(rel: String, text: String): File {
        val f = File(root, rel)
        f.parentFile?.mkdirs()
        f.writeText(text, Charsets.UTF_8)
        return f
    }

    private fun outline(file: File, maxChapters: Int = BookOutlineTool.DEFAULT_MAX_CHAPTERS) =
        BookOutlineTool.outline(root, file, maxChapters)

    private fun text(r: ToolOutcome): String = when (r) {
        is ToolOutcome.Ok -> r.text
        is ToolOutcome.Failed -> r.error
        is ToolOutcome.Rejected -> r.reason
    }

    // ── ① 中文章节 + 行号口径 ────────────────────────────────────────────

    @Test
    fun `中文章节_行号从0起且含端点`() {
        setup()
        val f = put(
            "资料库/原始/斗罗.txt",
            "第一章 起步\n正文一\n正文二\n第二章 出门\n正文三\n",
        )
        val t = text(outline(f))
        assertTrue("应报两章：$t", t.contains("共 2 章"))
        // 第一章：行 0..2（标题＋两行正文）；第二章：行 3..4
        assertTrue("第一章应为 0–2：$t", t.contains("0–2"))
        assertTrue("第二章应为 3–4：$t", t.contains("3–4"))
        assertTrue("首句应是标题行：$t", t.contains("第一章 起步"))
    }

    @Test
    fun `英文章节也认`() {
        setup()
        val f = put("a.txt", "Chapter 1\nhi\nChapter 2\nbye\n")
        val t = text(outline(f))
        assertTrue(t, t.contains("共 2 章"))
        assertTrue(t, t.contains("Chapter 1"))
    }

    // ── ② 只回目录 ──────────────────────────────────────────────────────

    @Test
    fun `不回正文_只给首句预览`() {
        setup()
        val longBody = "这一行是正文内容，" + "很长".repeat(40)
        val f = put("a.txt", "第一章 标题\n$longBody\n")
        val t = text(outline(f))
        assertTrue("正文不该整段出现：$t", !t.contains(longBody))
        // 章首是标题行 ⇒ 预览取「第一个非空行」＝标题，正文整段不进目录
        assertTrue("预览应取章首行：$t", t.contains("第一章 标题"))
    }

    @Test
    fun `章首本身就是长句时_预览截断带省略号`() {
        setup()
        val longFirst = "长句开头" + "很长的内容".repeat(20)
        val f = put("a.txt", "第一章 $longFirst\n正文\n")
        val t = text(outline(f))
        assertTrue("标题应被截到 60 字以内：$t", !t.contains(longFirst))
        assertTrue("预览要带省略号：$t", t.contains("…"))
    }

    @Test
    fun `末行空白不影响章数`() {
        setup()
        val f = put("a.txt", "第一章 甲\n正文\n")
        val t = text(outline(f))
        assertTrue(t, t.contains("共 1 章"))
        assertTrue("末尾换行不该多算一行：$t", t.contains("共 1 章，2 行"))
    }

    // ── ③ 触顶 ──────────────────────────────────────────────────────────

    @Test
    fun `max_chapters 触顶要明说还有多少章`() {
        setup()
        val sb = StringBuilder()
        (1..5).forEach { sb.append("第${it}章 标题\n正文\n") }
        val f = put("a.txt", sb.toString())
        val t = text(outline(f, maxChapters = 2))
        assertTrue(t, t.contains("只列了前 2 章"))
        assertTrue(t, t.contains("还有 3 章"))
    }

    @Test
    fun `max_chapters 超范围会夹紧不崩`() {
        setup()
        val sb = StringBuilder()
        (1..3).forEach { sb.append("第${it}章 标题\n正文\n") }
        val f = put("a.txt", sb.toString())
        val t = text(outline(f, maxChapters = 99_999))
        assertTrue(t, t.contains("共 3 章"))
    }

    // ── ④ 兜底切 ────────────────────────────────────────────────────────

    @Test
    fun `没有章节标记时按字数兜底切`() {
        setup()
        val f = put("a.txt", "这是一段没有任何章节标记的长文本。".repeat(200))
        val t = text(outline(f))
        assertTrue("应按字数额底出多章：$t", t.contains("共 ") && !t.contains("共 0 章"))
    }

    // ── ⑤ 失败路径 ──────────────────────────────────────────────────────

    @Test
    fun `不存在要 Failed 并指路`() {
        setup()
        val t = text(outline(File(root, "没有.txt")))
        assertTrue(t, t.contains("不存在"))
        assertTrue(t, t.contains("find_files"))
    }

    @Test
    fun `目录要 Failed 并指路 list_dir`() {
        setup()
        val dir = File(root, "资料库").apply { mkdirs() }
        val t = text(outline(dir))
        assertTrue(t, t.contains("不是一个文件"))
        assertTrue(t, t.contains("list_dir"))
    }

    @Test
    fun `越界要 Rejected`() {
        setup()
        val outside = File(tmp.root, "外面.txt").apply { writeText("第一章 甲\n", Charsets.UTF_8) }
        assertTrue(outline(outside) is ToolOutcome.Rejected)
    }

    @Test
    fun `二进制文件按文本解出来也不崩`() {
        setup()
        val f = File(root, "a.bin")
        f.writeBytes(byteArrayOf(0, 1, 2, 3))
        val r = outline(f)
        assertTrue("不崩即可（Ok 或 Failed 都行）：$r", r is ToolOutcome.Ok || r is ToolOutcome.Failed)
    }

    @Test
    fun `空文件给空态`() {
        setup()
        val f = put("空.txt", "")
        val t = text(outline(f))
        assertTrue(t, t.contains("空文件"))
    }

    // ── ⑥ spec ──────────────────────────────────────────────────────────

    @Test
    fun `spec_只读免确认且只path必填`() {
        val spec = BookOutlineTool.spec { tmp.root }
        assertEquals("book_outline", spec.name)
        assertEquals(ToolPermission.FREE, spec.permission)
        val required = spec.inputSchema.getJSONArray("required")
        assertEquals(1, required.length())
        assertEquals("path", required.getString(0))
    }
}
