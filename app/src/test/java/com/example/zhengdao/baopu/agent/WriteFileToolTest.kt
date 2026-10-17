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
 * WriteFileTool 单测（刀 2 追加包；判据追加①：四种拒绝都验）。
 *
 * 四种拒绝：① 越界（Rejected）② 非 CONFIRM 直写（permission 断言）③ 超 200 行
 * ④ 无删除能力（对象无删除 API + Schema additionalProperties=false）。
 * 另钉：覆盖前先备份（备份失败不写）、原子写不留 .write-tmp、写不进 .baopu-bak/。
 */
class WriteFileToolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File

    private fun setup() {
        root = tmp.newFolder("workspace")
    }

    private fun write(
        path: String,
        content: String = "内容",
        overwrite: Boolean = false,
    ) = WriteFileTool.write(root, path, content, overwrite, stamp = "20261017-120000")

    // ── 判据①：越界即拒 ──────────────────────────────────────────────────

    @Test
    fun `越界_相对路径逃逸被拒`() {
        setup()
        val r = write("../outside.txt")
        assertTrue("越界必须 Rejected：$r", r is ToolOutcome.Rejected)
        assertFalse(File(tmp.root, "outside.txt").exists())
    }

    @Test
    fun `越界_绝对路径在工作区外被拒`() {
        setup()
        val outside = File(tmp.root, "outside.txt").absolutePath
        val r = write(outside)
        assertTrue(r is ToolOutcome.Rejected)
        assertFalse(File(outside).exists())
    }

    @Test
    fun `越界_绝对路径在工作区内放行`() {
        setup()
        val inside = File(root, "inside.txt").absolutePath
        val r = write(inside)
        assertTrue("工作区内的绝对路径不该被拒：$r", r is ToolOutcome.Ok)
        assertTrue(File(inside).isFile)
    }

    @Test
    fun `空路径与空内容被拒`() {
        setup()
        assertTrue(write("   ") is ToolOutcome.Failed)
        assertTrue(write("a.txt", content = "") is ToolOutcome.Failed)
    }

    // ── 判据②：权限是 CONFIRM（每次写都要用户确认） ───────────────────────

    @Test
    fun `权限_CONFIRM且Schema钉死三参`() {
        val spec = WriteFileTool.spec { root }
        assertEquals("write_file", spec.name)
        assertEquals(ToolPermission.CONFIRM, spec.permission)
        val props = spec.inputSchema.getJSONObject("properties")
        assertEquals(3, props.length())
        assertTrue(props.has(WriteFileTool.ARG_PATH))
        assertTrue(props.has(WriteFileTool.ARG_CONTENT))
        assertTrue(props.has(WriteFileTool.ARG_OVERWRITE))
        assertFalse("additionalProperties 必须 false", spec.inputSchema.optBoolean("additionalProperties"))
        // required = path + content（overwrite 有默认值，不强制）
        val required = spec.inputSchema.getJSONArray("required")
        assertEquals(2, required.length())
    }

    // ── 判据③：单次 200 行上限 ───────────────────────────────────────────

    @Test
    fun `超200行_拒绝并提示分次`() {
        setup()
        val content = (1..201).joinToString("\n") { "第 $it 行" }
        val r = write("big.txt", content = content)

        assertTrue("超行数必须 Failed（可重试）：$r", r is ToolOutcome.Failed)
        assertTrue((r as ToolOutcome.Failed).error.contains("200"))
        assertFalse(File(root, "big.txt").exists())
    }

    @Test
    fun `正好200行_放行`() {
        setup()
        val content = (1..200).joinToString("\n") { "第 $it 行" }
        val r = write("ok.txt", content = content)
        assertTrue(r is ToolOutcome.Ok)
        assertEquals(200, File(root, "ok.txt").readLines().size)
    }

    // ── 判据④：无删除能力 ────────────────────────────────────────────────

    @Test
    fun `无删除能力_API面与Schema都没有`() {
        setup()
        // Schema 层：没有任何 delete/remove 参数（幻觉出的参数过不了 SchemaCheck）
        val props = WriteFileTool.spec { root }.inputSchema.getJSONObject("properties")
        assertFalse(props.keys().asSequence().any { it.contains("delete") || it.contains("remove") })
        // 类面：WriteFileTool 的公开方法只有 spec/write（反射核一遍，防将来偷偷加删除）
        val names = WriteFileTool::class.java.declaredMethods.map { it.name }.toSet()
        assertFalse("不许有删除能力：$names", names.any { it.contains("delete") || it.contains("remove") })
    }

    // ── 覆盖前先备份 ─────────────────────────────────────────────────────

    @Test
    fun `覆盖_旧版本先进备份再写`() {
        setup()
        File(root, "a.txt").writeText("旧内容")

        val r = write("a.txt", content = "新内容", overwrite = true)

        assertTrue(r is ToolOutcome.Ok)
        assertEquals("新内容", File(root, "a.txt").readText())
        val bak = File(root, WriteFileTool.BACKUP_DIR_NAME).listFiles()!!.single()
        assertEquals("旧内容", bak.readText())
        assertTrue(bak.name.contains("20261017-120000"))
        assertTrue((r as ToolOutcome.Ok).text.contains(".baopu-bak/"))
    }

    @Test
    fun `已存在未overwrite_拒绝且旧文件不动`() {
        setup()
        File(root, "a.txt").writeText("旧内容")
        val r = write("a.txt", content = "新内容", overwrite = false)

        assertTrue(r is ToolOutcome.Failed)
        assertEquals("旧内容", File(root, "a.txt").readText())
        assertFalse(File(root, WriteFileTool.BACKUP_DIR_NAME).exists())
    }

    @Test
    fun `备份区不许写_双保险`() {
        setup()
        val r = write(".baopu-bak/x.txt", content = "y", overwrite = true)
        assertTrue("备份区必须拒写：$r", r is ToolOutcome.Failed)
        assertFalse(File(root, ".baopu-bak/x.txt").exists())
    }

    @Test
    fun `目标恰为备份目录本身也拒`() {
        setup()
        val r = write(".baopu-bak", content = "y", overwrite = true)
        assertTrue(r is ToolOutcome.Failed)
    }

    @Test
    fun `目标是目录_拒绝`() {
        setup()
        File(root, "adir").mkdirs()
        val r = write("adir", content = "y", overwrite = true)
        assertTrue(r is ToolOutcome.Failed)
    }

    // ── 正常写 ───────────────────────────────────────────────────────────

    @Test
    fun `新建_建父目录且内容逐字落盘`() {
        setup()
        val r = write("资料库/整理/新文件.md", content = "第一行\n第二行")
        assertTrue(r is ToolOutcome.Ok)
        assertEquals("第一行\n第二行", File(root, "资料库/整理/新文件.md").readText())
        assertTrue((r as ToolOutcome.Ok).text.startsWith("已新建"))
        assertTrue((r as ToolOutcome.Ok).text.contains("2 行"))
    }

    @Test
    fun `原子写_不留临时文件`() {
        setup()
        write("a.txt")
        val names = root.listFiles()!!.map { it.name }
        assertEquals(listOf("a.txt"), names)
    }

    @Test
    fun `同内容覆盖_也照备份不静默`() {
        setup()
        File(root, "a.txt").writeText("同内容")
        val r = write("a.txt", content = "同内容", overwrite = true)
        assertTrue(r is ToolOutcome.Ok)
        assertTrue(File(root, WriteFileTool.BACKUP_DIR_NAME).listFiles()!!.single().isFile)
    }

    @Test
    fun `executor_走spec的执行器`() {
        setup()
        val spec = WriteFileTool.spec { root }
        val args = org.json.JSONObject()
            .put("path", "via-exec.txt")
            .put("content", "执行器内容")
        val r = spec.executor(args)
        assertTrue(r is ToolOutcome.Ok)
        assertEquals("执行器内容", File(root, "via-exec.txt").readText())
    }
}
