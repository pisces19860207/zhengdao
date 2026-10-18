// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * FindFilesTool 单测（工具补强第二刀）。
 *
 * 钉住的判据：① 只按文件名找、大小写不敏感；② 递归到 max_depth 就停；
 * ③ 隐藏目录/点文件不找；④ 结果按相对路径排（两次调用同序）；⑤ 命中为空时明说「扫了几个」；
 * ⑥ 越界 Rejected；⑦ 传文件而不是目录 ⇒ Failed；⑧ FREE（只读）。
 */
class FindFilesToolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File

    private fun setup() {
        root = tmp.newFolder("workspace")
    }

    private fun put(rel: String, text: String = "x"): File {
        val f = File(root, rel)
        f.parentFile?.mkdirs()
        f.writeText(text, Charsets.UTF_8)
        return f
    }

    private fun find(
        pattern: String,
        dir: File? = null,
        limit: Int = FindFilesTool.DEFAULT_LIMIT,
        maxDepth: Int = FindFilesTool.DEFAULT_DEPTH,
    ) = FindFilesTool.find(root, dir ?: root, pattern, limit, maxDepth)

    private fun text(r: ToolOutcome): String = when (r) {
        is ToolOutcome.Ok -> r.text
        is ToolOutcome.Failed -> r.error
        is ToolOutcome.Rejected -> r.reason
    }

    // ── ① 基本命中 ──────────────────────────────────────────────────────

    @Test
    fun `按文件名片段命中_大小写不敏感`() {
        setup()
        put("资料库/原始/斗罗大陆.txt")
        put("资料库/原始/凡人修仙传.TXT")
        val r = find("txt")
        assertTrue("应 Ok：$r", r is ToolOutcome.Ok)
        val t = text(r)
        assertTrue(t, t.contains("斗罗大陆.txt"))
        assertTrue(t, t.contains("凡人修仙传.TXT"))
        assertTrue("要报扫了几个文件：$t", t.contains("扫了 2 个"))
    }

    @Test
    fun `命中结果按相对路径排序`() {
        setup()
        put("b/同一本.txt")
        put("a/同一本.txt")
        val t = text(find("同一本"))
        val ia = t.indexOf("a/同一本.txt")
        val ib = t.indexOf("b/同一本.txt")
        assertTrue("a 应在 b 前：$t", ia in 0 until ib)
    }

    // ── ② 深度 ──────────────────────────────────────────────────────────

    @Test
    fun `深度上限之外的不找`() {
        setup()
        put("l1/l2/l3/深处.txt")
        assertTrue("默认 6 层应能找到", text(find("深处")).contains("深处.txt"))
        val shallow = text(find("深处", maxDepth = 1))
        assertTrue("1 层应找不到：$shallow", shallow.contains("没找到"))
    }

    // ── ③ 隐藏不找 ──────────────────────────────────────────────────────

    @Test
    fun `隐藏目录与点文件不找`() {
        setup()
        put(".baopu-bak/备份.txt")
        put(".隐藏.txt")
        put("正常.txt")
        val t = text(find("txt"))
        assertTrue(t, t.contains("正常.txt"))
        assertTrue("点文件不该出现：$t", !t.contains(".隐藏.txt"))
        assertTrue("隐藏目录里的不该出现：$t", !t.contains("备份.txt"))
    }

    // ── ④ 空结果 ────────────────────────────────────────────────────────

    @Test
    fun `没命中要明说扫了几个并给下一步`() {
        setup()
        put("a.txt")
        val t = text(find("不存在的名字"))
        assertTrue(t, t.contains("没找到"))
        assertTrue("要报扫描数：$t", t.contains("扫了 1 个"))
        assertTrue("要给下一步：$t", t.contains("list_dir"))
    }

    // ── ⑤ limit ─────────────────────────────────────────────────────────

    @Test
    fun `达到 limit 要明说还有更多`() {
        setup()
        (1..5).forEach { put("书$it.txt") }
        val t = text(find("书", limit = 2))
        assertTrue(t, t.contains("已到 2 个上限"))
    }

    // ── ⑥ 越界 / 非法入参 ───────────────────────────────────────────────

    @Test
    fun `起点目录在工作区外被拒`() {
        setup()
        val outside = tmp.newFolder("outside")
        val r = find("x", dir = outside)
        assertTrue("必须 Rejected：$r", r is ToolOutcome.Rejected)
    }

    @Test
    fun `给文件而不是目录要 Failed 并指路`() {
        setup()
        val f = put("a.txt")
        val t = text(find("a", dir = f))
        assertTrue(t, t.contains("不是一个目录"))
        assertTrue(t, t.contains("read_file"))
    }

    @Test
    fun `空片段要 Failed`() {
        setup()
        val t = text(find("   "))
        assertTrue(t, t.contains("为空"))
    }

    @Test
    fun `目录不存在要 Failed 并指路`() {
        setup()
        val t = text(find("x", dir = File(root, "没有这个目录")))
        assertTrue(t, t.contains("不存在"))
        assertTrue(t, t.contains("list_dir"))
    }

    // ── ⑦ spec 形状 ─────────────────────────────────────────────────────

    @Test
    fun `spec_只读免确认且只有pattern必填`() {
        val spec = FindFilesTool.spec { tmp.root }
        assertEquals("find_files", spec.name)
        assertEquals(ToolPermission.FREE, spec.permission)
        val schema = spec.inputSchema
        val required = schema.getJSONArray("required")
        assertEquals(1, required.length())
        assertEquals("pattern", required.getString(0))
        assertEquals(false, schema.getBoolean("additionalProperties"))
    }
}
