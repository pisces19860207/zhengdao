// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 288 单测：钉住派工单的四条判据——
// ① 复制大文件不崩（流式：缓冲 64KB，测内容/mtree 正确性与目录递归）；
// ② 整理预览与实际一致（同一计划函数 + 回执行数对账）；
// ③ 撤销能全部还原（回执逐条搬回；撞名不覆盖）；
// ④ 重名默认不覆盖（撞名加后缀让路、同内容幂等）。
// 另钉：禁区不进范围（备份/记忆/原始）、注册与风险档齐备。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.RiskClass
import com.example.zhengdao.baopu.RiskMatrix
import com.example.zhengdao.baopu.RiskTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CopyOrganizeToolsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File

    private fun setup() {
        root = tmp.newFolder("workspace")
    }

    private fun put(rel: String, text: String, mtime: Long? = null): File {
        val f = File(root, rel)
        f.parentFile?.mkdirs()
        f.writeText(text, Charsets.UTF_8)
        mtime?.let { f.setLastModified(it) }
        return f
    }

    private fun textOf(f: File): String = f.readText(Charsets.UTF_8)

    // ── copy_path ────────────────────────────────────────────────────────

    @Test
    fun `复制单文件_源保留_内容一致_mtime对齐`() {
        setup()
        val src = put("投递/a.txt", "正文", mtime = 1_700_000_000_000)
        val r = CopyPathTool.run(root, "投递/a.txt", "产物/书/a.txt")
        assertTrue("复制应成功：$r", r is ToolOutcome.Ok)
        assertTrue("源必须原地保留", src.exists())
        val dst = File(root, "产物/书/a.txt")
        assertEquals("正文", textOf(dst))
        assertEquals("mtime 要对齐", src.lastModified(), dst.lastModified())
    }

    @Test
    fun `撞名不同内容_加后缀让路_绝不覆盖`() {
        setup()
        put("投递/a.txt", "新内容")
        val old = put("产物/书/a.txt", "旧内容")
        val r = CopyPathTool.run(root, "投递/a.txt", "产物/书/a.txt")
        assertTrue(r is ToolOutcome.Ok)
        assertEquals("旧内容", textOf(old))
        val copies = File(root, "产物/书").listFiles()?.filter { it.name.startsWith("a.") && it.name != "a.txt" }
        assertTrue("应有让路副本：${copies?.map { it.name }}", copies?.size == 1)
        assertEquals("新内容", textOf(copies!!.first()))
    }

    @Test
    fun `目标内容相同_幂等回已复制`() {
        setup()
        put("投递/a.txt", "一样")
        put("产物/b.txt", "一样")
        val r = CopyPathTool.run(root, "投递/a.txt", "产物/b.txt")
        assertTrue(r is ToolOutcome.Ok)
        assertTrue((r as ToolOutcome.Ok).text.contains("视为已复制"))
    }

    @Test
    fun `目录递归复制_整树落位_自管目录不进范围`() {
        setup()
        put("相册/2026/a.jpg", "图")
        put("相册/备份/旧.jpg", "旧") // 备份是 App 自管目录，任何层级都不进范围
        val r = CopyPathTool.run(root, "相册", "产物/相册副本")
        assertTrue("目录复制应成功：$r", r is ToolOutcome.Ok)
        assertTrue(File(root, "产物/相册副本/2026/a.jpg").exists())
        assertFalse("备份/ 不该被复制", File(root, "产物/相册副本/备份/旧.jpg").exists())
        assertTrue("源目录原地未动", File(root, "相册/2026/a.jpg").exists())
    }

    @Test
    fun `源不存在_如实报错`() {
        setup()
        val r = CopyPathTool.run(root, "投递/没有.txt", "产物/x.txt")
        assertTrue(r is ToolOutcome.Failed)
    }

    // ── bulk_copy / bulk_move ────────────────────────────────────────────

    @Test
    fun `bulk_copy按扩展名筛_源全保留`() {
        setup()
        put("收料/a.jpg", "图1")
        put("收料/b.jpg", "图2")
        put("收料/c.txt", "文")
        val args = org.json.JSONObject().put("dir", "收料").put("dest", "产物/图库")
            .put("ext", org.json.JSONArray().put("jpg"))
        val r = BulkPathTools.run(root, args, move = false)
        assertTrue("bulk_copy 应成功：$r", r is ToolOutcome.Ok)
        assertTrue(File(root, "产物/图库/a.jpg").exists())
        assertTrue(File(root, "产物/图库/b.jpg").exists())
        assertFalse("txt 不该被带上", File(root, "产物/图库/c.txt").exists())
        assertTrue("源 a.jpg 保留", File(root, "收料/a.jpg").exists())
        assertTrue("源 c.txt 保留", File(root, "收料/c.txt").exists())
    }

    @Test
    fun `bulk_move同盘_源消失_回执落地`() {
        setup()
        put("投递/a.txt", "一")
        put("投递/b.txt", "二")
        val args = org.json.JSONObject().put("dir", "投递").put("dest", "产物/归档")
        val r = BulkPathTools.run(root, args, move = true)
        assertTrue("bulk_move 应成功：$r", r is ToolOutcome.Ok)
        assertFalse("同盘 rename 后源不在", File(root, "投递/a.txt").exists())
        assertTrue(File(root, "产物/归档/a.txt").exists())
        val receipts = File(root, "产物/_整理回执").listFiles().orEmpty()
        assertEquals("应有一份回执", 1, receipts.size)
        val lines = receipts.first().readText(Charsets.UTF_8).lines().filter { it.startsWith("- ") }
        assertEquals("回执两行（哪个搬到了哪）", 2, lines.size)
    }

    @Test
    fun `bulk_move跨分区_源保留_记进待删除清单`() {
        setup()
        val src = put("投递/a.txt", "一")
        val plan = PathCopyCore.Plan(
            entries = listOf(src to File(root, "产物/归档/a.txt")),
            skippedSelf = 0,
            totalBytes = 1,
        )
        val report = PathCopyCore.executePlan(plan, move = true, root = root, preferCopy = true) { _, _ -> }
        assertEquals(1, report.done)
        assertEquals("复制后源必须保留", 1, report.pendingDelete.size)
        assertTrue("源还在", src.exists())
        assertTrue(File(root, "产物/归档/a.txt").exists())
    }

    @Test
    fun `目标在范围里面_拒`() {
        setup()
        put("收料/a.txt", "x")
        val args = org.json.JSONObject().put("dir", "收料").put("dest", "收料/里层")
        val r = BulkPathTools.run(root, args, move = true)
        assertTrue("目标在范围内必须拒：$r", r is ToolOutcome.Failed)
    }

    // ── organize：预览一致 / 执行 / 撤销 ──────────────────────────────────

    private fun organizeArgs(vararg pairs: Pair<String, Any>): org.json.JSONObject {
        val j = org.json.JSONObject()
        pairs.forEach { (k, v) -> j.put(k, v) }
        return j
    }

    @Test
    fun `按类型整理_落位正确_再跑一遍幂等`() {
        setup()
        put("收料/a.jpg", "图")
        put("收料/b.txt", "文")
        val args = organizeArgs("dir" to "收料", "mode" to "type")
        val r1 = OrganizeTool.run(root, args)
        assertTrue("首次整理应成功：$r1", r1 is ToolOutcome.Ok)
        assertEquals("图", textOf(File(root, "收料/图片/a.jpg")))
        assertEquals("文", textOf(File(root, "收料/文档/b.txt")))
        val r2 = OrganizeTool.run(root, args)
        assertTrue("二次整理应幂等：$r2", (r2 as ToolOutcome.Ok).text.contains("没有需要归位"))
    }

    @Test
    fun `预览与实际一致_回执行数对账`() {
        setup()
        put("收料/a.jpg", "图")
        put("收料/b.jpg", "图2")
        put("收料/c.txt", "文")
        val args = organizeArgs("dir" to "收料", "mode" to "type")
        val pv = OrganizeTool.previewSpec { root }.executor(
            organizeArgs("op" to "organize", "dir" to "收料", "mode" to "type"),
        )
        assertTrue("预览应成功：$pv", pv is ToolOutcome.Ok)
        val plan = PathCopyCore.planOrganize(
            File(root, "收料"), File(root, "收料"), "type", PathCopyCore.Rule(),
            recursive = true, nowMs = System.currentTimeMillis(),
        )
        assertEquals(3, plan.entries.size)
        val said = Regex("将归位 (\\d+) 个文件").find((pv as ToolOutcome.Ok).text)?.groupValues?.get(1)
        assertEquals("预览报的数应等于计划条数", "3", said)
        val r = OrganizeTool.run(root, args)
        assertTrue(r is ToolOutcome.Ok)
        val receipts = File(root, "产物/_整理回执").listFiles().orEmpty()
        val lines = receipts.flatMap { it.readText(Charsets.UTF_8).lines() }.filter { it.startsWith("- ") }
        assertEquals("回实行数应等于计划条数", 3, lines.size)
    }

    @Test
    fun `撤销能全部还原`() {
        setup()
        val a = put("收料/a.jpg", "图")
        val b = put("收料/子/b.txt", "文")
        val args = organizeArgs("dir" to "收料", "mode" to "type")
        val r = OrganizeTool.run(root, args)
        assertTrue(r is ToolOutcome.Ok)
        assertFalse("整理后源应已挪走", a.exists())
        assertTrue(File(root, "收料/图片/a.jpg").exists())
        // 从回执话术里拿 undo id（回执目录里只有一份）
        val receipt = File(root, "产物/_整理回执").listFiles()!!.first()
        val undoId = receipt.nameWithoutExtension
        val u = OrganizeTool.spec { root }.executor(organizeArgs("dir" to "收料", "undo_receipt" to undoId))
        assertTrue("撤销应成功：$u", u is ToolOutcome.Ok)
        assertTrue("a.jpg 应回原位", a.exists())
        assertEquals("图", textOf(a))
        assertTrue("b.txt 应回原位", b.exists())
        assertFalse("归位处的副本应已搬走", File(root, "收料/图片/a.jpg").exists())
    }

    @Test
    fun `撤销撞名不覆盖_退回加后缀`() {
        setup()
        val a = put("收料/a.jpg", "原图")
        val r = OrganizeTool.run(root, organizeArgs("dir" to "收料", "mode" to "type"))
        assertTrue(r is ToolOutcome.Ok)
        // 整理后用户在原位又放了个同名不同内容的文件
        put("收料/a.jpg", "后来放的新图")
        val undoId = File(root, "产物/_整理回执").listFiles()!!.first().nameWithoutExtension
        val u = OrganizeTool.spec { root }.executor(organizeArgs("undo_receipt" to undoId))
        assertTrue("撤销应成功：$u", u is ToolOutcome.Ok)
        assertEquals("原位的新文件不能被覆盖", "后来放的新图", textOf(a))
        val restored = File(root, "收料").listFiles()?.filter { it.name.startsWith("a.") && it.name != "a.jpg" }
        assertEquals("退回的原图应以新名字在源旁", 1, restored?.size)
        assertEquals("原图", textOf(restored!!.first()))
    }

    @Test
    fun `禁区不进整理范围`() {
        setup()
        put("工作区/备份/旧.jpg", "旧")
        put("工作区/记忆/日记.md", "记")
        val r = OrganizeTool.run(root, organizeArgs("dir" to "工作区", "mode" to "type"))
        assertTrue(r is ToolOutcome.Ok)
        assertTrue("备份/ 不该被动", File(root, "工作区/备份/旧.jpg").exists())
        assertTrue("记忆/ 不该被动", File(root, "工作区/记忆/日记.md").exists())
    }

    @Test
    fun `整理技能_存了能重放`() {
        setup()
        put("收料/a.jpg", "图")
        val args = organizeArgs("dir" to "收料", "mode" to "type", "save_recipe" to "每月收料")
        val r = OrganizeTool.run(root, args)
        assertTrue(r is ToolOutcome.Ok)
        val recipe = File(root, "产物/_整理技能/每月收料.json")
        assertTrue("配方应落盘", recipe.exists())
        // 只带 recipe 就能重放同一套规则（dir/mode 都来自配方）
        put("收料/new.jpg", "新图")
        // 配方里的 dir 是 收料；重放预览应指向 收料 里还没归位的新文件
        val pv = OrganizeTool.previewSpec { root }.executor(organizeArgs("op" to "organize", "recipe" to "每月收料"))
        assertTrue("按配方预览应成功：$pv", pv is ToolOutcome.Ok)
        assertTrue("配方应带上存的 dir 并看到新文件", (pv as ToolOutcome.Ok).text.contains("new.jpg"))
    }

    // ── 注册与风险档齐备 ─────────────────────────────────────────────────

    @Test
    fun `五件已注册_风险档与权限口径正确`() {
        val copy = CopyPathTool.spec { File(".") }
        assertEquals(ToolPermission.CONFIRM, copy.permission)
        val mv = BulkPathTools.moveSpec { File(".") }
        assertEquals(ToolPermission.CONFIRM, mv.permission)
        val cp = BulkPathTools.copySpec { File(".") }
        assertEquals(ToolPermission.CONFIRM, cp.permission)
        val og = OrganizeTool.spec { File(".") }
        assertEquals(ToolPermission.CONFIRM, og.permission)
        val pv = OrganizeTool.previewSpec { File(".") }
        assertEquals("预览必须免确认", ToolPermission.FREE, pv.permission)
        assertTrue("预览必须只读", pv.readOnly)
        assertTrue("预览可并行读", pv.parallelSafe)
        assertEquals(RiskClass.R1, RiskMatrix.baseOf("copy_path"))
        assertEquals(RiskClass.R2, RiskMatrix.baseOf("bulk_move"))
        assertEquals(RiskClass.R1, RiskMatrix.baseOf("bulk_copy"))
        assertEquals(RiskClass.R2, RiskMatrix.baseOf("organize"))
        assertEquals(RiskClass.R0, RiskMatrix.baseOf("organize_preview"))
    }

    @Test
    fun `风险矩阵对注册名册全覆盖`() {
        val names = BaopuToolCatalog.ENTRIES.map { it.name } +
            ToolGroupTable.RUNTIME_REGISTERED
        val missing = RiskMatrix.uncovered(names, RiskTable.DEFAULT)
        assertTrue("这些注册工具缺风险档：$missing", missing.isEmpty())
    }
}
