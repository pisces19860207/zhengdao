// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * MovePathTool 单测（工具补强刀）。
 *
 * 钉住的判据：① 源/目标任一侧越界都 Rejected；② 同盘走 rename（源消失）；
 * ③ 撞名不同内容加后缀让路、**绝不覆盖**；④ 撞名相同内容幂等；
 * ⑤ 跨盘只复制、源保留、写进待删除清单（**不自动删**）；⑥ 不搬目录；⑦ CONFIRM。
 */
class MovePathToolTest {

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

    private fun move(
        from: String,
        to: String,
        createDirs: Boolean = true,
        preferCopy: Boolean = false,
    ) = MovePathTool.move(
        root, from, to,
        createDirs = createDirs,
        stamp = "20261017-170000",
        preferCopy = preferCopy,
    )

    // ── ① 越界 ──────────────────────────────────────────────────────────

    @Test
    fun `越界_源在工作区外被拒`() {
        setup()
        val outside = File(tmp.root, "outside.txt")
        outside.writeText("x", Charsets.UTF_8)
        val r = move(outside.absolutePath, "资料库/整理/x.txt")
        assertTrue("必须 Rejected：$r", r is ToolOutcome.Rejected)
        assertTrue(outside.exists())
    }

    @Test
    fun `越界_目标在工作区外被拒`() {
        setup()
        val src = put("资料库/投递/a.txt", "内容")
        val r = move("资料库/投递/a.txt", "../escape.txt")
        assertTrue(r is ToolOutcome.Rejected)
        assertTrue("源必须原样在：${src.absolutePath}", src.exists())
        assertFalse(File(tmp.root, "escape.txt").exists())
    }

    // ── ② 同盘 rename ───────────────────────────────────────────────────

    @Test
    fun `搬动_同盘rename_源消失目标就位`() {
        setup()
        val src = put("资料库/投递/book.txt", "第一章")
        val r = move("资料库/投递/book.txt", "资料库/整理/武侠/book.txt")
        assertTrue("应成功：$r", r is ToolOutcome.Ok)
        assertFalse("rename 后源应不存在", src.exists())
        val dst = File(root, "资料库/整理/武侠/book.txt")
        assertTrue(dst.exists())
        assertEquals("第一章", dst.readText(Charsets.UTF_8))
        assertTrue((r as ToolOutcome.Ok).text.contains("rename"))
    }

    @Test
    fun `自动建目标目录`() {
        setup()
        put("资料库/投递/a.txt", "x")
        val r = move("资料库/投递/a.txt", "资料库/整理/仙侠/2026/a.txt")
        assertTrue(r is ToolOutcome.Ok)
        assertTrue(File(root, "资料库/整理/仙侠/2026/a.txt").exists())
    }

    @Test
    fun `目标目录不存在且不许建_被拒`() {
        setup()
        val src = put("资料库/投递/a.txt", "x")
        val r = move("资料库/投递/a.txt", "资料库/整理/不存在/a.txt", createDirs = false)
        assertTrue(r is ToolOutcome.Failed)
        assertTrue("源不该动", src.exists())
    }

    // ── ③ 撞名：不同内容 ⇒ 加后缀让路 ──────────────────────────────────

    @Test
    fun `撞名不同内容_加sha后缀两份都在`() {
        setup()
        put("资料库/投递/书.txt", "投递版本")
        val dst = put("资料库/整理/书.txt", "整理版本")
        val r = move("资料库/投递/书.txt", "资料库/整理/书.txt")
        assertTrue("应成功：$r", r is ToolOutcome.Ok)
        assertEquals("整理版本", dst.readText(Charsets.UTF_8))
        val siblings = dst.parentFile!!.listFiles()!!.map { it.name }
        assertTrue("应出现带 sha 后缀的新文件：$siblings", siblings.any { it.startsWith("书.") && it.endsWith(".txt") })
        val kept = dst.parentFile!!.listFiles()!!.first { it.name.startsWith("书.") }
        assertEquals("投递版本", kept.readText(Charsets.UTF_8))
    }

    @Test
    fun `撞名相同内容_幂等不动任何文件`() {
        setup()
        val src = put("资料库/投递/同.txt", "一样的内容")
        val dst = put("资料库/整理/同.txt", "一样的内容")
        val r = move("资料库/投递/同.txt", "资料库/整理/同.txt")
        assertTrue(r is ToolOutcome.Ok)
        assertTrue("应说明视为已搬：$r", (r as ToolOutcome.Ok).text.contains("视为已搬"))
        assertTrue("源应保留（没有多余动作）", src.exists())
        assertEquals("一样的内容", dst.readText(Charsets.UTF_8))
        assertEquals("不许造后缀文件", 2, dst.parentFile!!.parentFile!!.walkTopDown().count { it.isFile })
    }

    // ── ⑤ 跨盘：复制 ＋ 待删除清单，不删源 ────────────────────────────

    @Test
    fun `跨盘_只复制_源保留_写进待删除清单`() {
        setup()
        val src = put("资料库/投递/big.txt", "很长的内容")
        val r = MovePathTool.move(
            root, "资料库/投递/big.txt", "资料库/整理/big.txt",
            createDirs = true, stamp = "20261017-170000", preferCopy = true,
        )
        assertTrue("应成功：$r", r is ToolOutcome.Ok)
        val text = (r as ToolOutcome.Ok).text
        assertTrue("应说明源未删：$text", text.contains("保留未删"))
        assertTrue("源必须还在", src.exists())
        assertEquals("很长的内容", File(root, "资料库/整理/big.txt").readText(Charsets.UTF_8))
        val list = File(root, MovePathTool.PENDING_LIST_REL)
        assertTrue("待删除清单应被写出：${list.absolutePath}", list.exists())
        val body = list.readText(Charsets.UTF_8)
        assertTrue("清单里应有源路径：$body", body.contains(src.absolutePath))
        assertTrue("清单里应有时间戳", body.contains("20261017-170000"))
    }

    // ── ⑥ 其它拒绝 ──────────────────────────────────────────────────────

    @Test
    fun `源不存在_被拒`() {
        setup()
        val r = move("资料库/投递/没有.txt", "资料库/整理/没有.txt")
        assertTrue(r is ToolOutcome.Failed)
    }

    @Test
    fun `源是目录_被拒`() {
        setup()
        File(root, "资料库/投递/一整个目录").mkdirs()
        val r = move("资料库/投递/一整个目录", "资料库/整理/一整个目录")
        assertTrue("目录不搬：$r", r is ToolOutcome.Failed)
    }

    @Test
    fun `源目标同一路径_被拒`() {
        setup()
        put("资料库/投递/a.txt", "x")
        val r = move("资料库/投递/a.txt", "资料库/投递/a.txt")
        assertTrue(r is ToolOutcome.Failed)
    }

    @Test
    fun `不能拿备份区当源或目标`() {
        setup()
        put("${MovePathTool.BACKUP_DIR_NAME}/old.bak", "备份")
        val r1 = move("${MovePathTool.BACKUP_DIR_NAME}/old.bak", "资料库/整理/old.bak")
        assertTrue(r1 is ToolOutcome.Failed)
        put("资料库/投递/a.txt", "x")
        val r2 = move("资料库/投递/a.txt", "${MovePathTool.BACKUP_DIR_NAME}/a.txt")
        assertTrue(r2 is ToolOutcome.Failed)
    }

    // ── ⑦ 声明层 ────────────────────────────────────────────────────────

    @Test
    fun `权限必须是CONFIRM`() {
        setup()
        val spec = MovePathTool.spec { root }
        assertEquals(ToolPermission.CONFIRM, spec.permission)
        assertEquals("move_path", spec.name)
    }

    @Test
    fun `Schema_只有三个参数且禁止额外参数`() {
        setup()
        val spec = MovePathTool.spec { root }
        val props = spec.inputSchema.getJSONObject("properties")
        assertEquals(3, props.length())
        assertFalse(spec.inputSchema.getBoolean("additionalProperties"))
        assertTrue(spec.description.length <= ToolSpec.MAX_DESC)
    }
}
