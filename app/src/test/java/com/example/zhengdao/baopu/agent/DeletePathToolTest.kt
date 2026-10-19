// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 2026-10-18 用户点名「删除工具没加啊？」——真机上抱朴自己回「我没有删除工具」。
// 本套件钉住这条工具的**全部可拒路径**与**默认可反悔**：
//
// 1. 默认进回收站（能捞回来），`permanent=true` 才真删；
// 2. 目录、越界、备份区、回收站本身、`资料库/原始/` 一律拒（说人话，不是异常）；
// 3. 注册表里必须真的在、权限必须是 CONFIRM（源码级，防「写了但没接上」）。
package com.example.zhengdao.baopu.agent

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class DeletePathToolTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = File.createTempFile("zd-del-root", "").let { f ->
            f.delete()
            f.mkdirs()
            f
        }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun file(rel: String, text: String = "x"): File = File(root, rel).apply {
        parentFile?.mkdirs()
        writeText(text, Charsets.UTF_8)
    }

    // ── 默认：进回收站 ──────────────────────────────────────────────────────

    @Test
    fun `D1_默认进回收站_原件不见了但在回收站里`() {
        val f = file("附件/a.txt", "内容")
        val out = DeletePathTool.run("附件/a.txt", root = root, stamp = "20261018-233000")
        assertTrue(out is ToolOutcome.Ok)
        assertFalse("原位置必须没了", f.exists())
        val trashed = File(root, "${DeletePathTool.TRASH_DIR}/20261018-233000-a.txt")
        assertTrue("回收站里必须有", trashed.isFile)
        assertTrue("内容不能变", trashed.readText() == "内容")
        assertTrue("回执要说清去哪儿了", (out as ToolOutcome.Ok).text.contains(DeletePathTool.TRASH_DIR))
    }

    @Test
    fun `D2_同秒删两个同名文件也不覆盖前一个`() {
        file("a.txt", "第一份")
        assertTrue(DeletePathTool.run("a.txt", root = root, stamp = "S") is ToolOutcome.Ok)
        file("a.txt", "第二份")
        assertTrue(DeletePathTool.run("a.txt", root = root, stamp = "S") is ToolOutcome.Ok)
        val trash = File(root, DeletePathTool.TRASH_DIR)
        val names = trash.listFiles()!!.map { it.name }.sorted()
        assertTrue("两份都要在回收站里，且都有内容：$names", names.size == 2)
        val texts = trash.listFiles()!!.map { it.readText() }.toSet()
        assertTrue("内容不能被覆盖：$texts", texts.contains("第一份") && texts.contains("第二份"))
    }

    // ── permanent：真删 ─────────────────────────────────────────────────────

    @Test
    fun `D3_permanent才真删_不进回收站`() {
        val f = file("旧稿.txt")
        val out = DeletePathTool.run("旧稿.txt", permanent = true, root = root)
        assertTrue(out is ToolOutcome.Ok)
        assertFalse(f.exists())
        val trash = File(root, DeletePathTool.TRASH_DIR)
        assertFalse("真删不该留回收站痕迹", trash.exists() && (trash.listFiles()?.isNotEmpty() == true))
        assertTrue("回执要明说不可恢复", (out as ToolOutcome.Ok).text.contains("不可恢复"))
    }

    // ── 拒绝路径 ────────────────────────────────────────────────────────────

    @Test
    fun `D4_目录一律拒_并说清怎么办`() {
        File(root, "小说").mkdirs()
        val out = DeletePathTool.run("小说", root = root)
        assertTrue(out is ToolOutcome.Failed)
        assertTrue((out as ToolOutcome.Failed).error.contains("目录"))
        assertTrue("目录还在", File(root, "小说").isDirectory)
    }

    @Test
    fun `D5_越界拒绝_文件原样`() {
        val outside = File(root.parentFile, "zd-del-outside-${System.nanoTime()}.txt").apply {
            writeText("外面", Charsets.UTF_8)
        }
        try {
            val out = DeletePathTool.run("../${outside.name}", root = root)
            assertTrue("越界必须是 Rejected", out is ToolOutcome.Rejected)
            assertTrue("外面的文件不许动", outside.isFile)
        } finally {
            outside.delete()
        }
    }

    @Test
    fun `D6_资料库原始是红线_一个字节都不碰`() {
        file("${DeletePathTool.RAW_GUARD_REL}/原件.txt", "原件")
        val out = DeletePathTool.run("${DeletePathTool.RAW_GUARD_REL}/原件.txt", root = root)
        assertTrue(out is ToolOutcome.Rejected)
        assertTrue("原件必须还在", File(root, "${DeletePathTool.RAW_GUARD_REL}/原件.txt").isFile)
    }

    @Test
    fun `D7_备份区与回收站本身都不许删`() {
        file("${DeletePathTool.BACKUP_DIR_NAME}/旧版.txt", "备份")
        val a = DeletePathTool.run("${DeletePathTool.BACKUP_DIR_NAME}/旧版.txt", root = root)
        assertTrue(a is ToolOutcome.Failed)
        assertTrue(File(root, "${DeletePathTool.BACKUP_DIR_NAME}/旧版.txt").isFile)

        // 回收站：**默认就彻底清**（用户两次反馈「清不掉」，对回收站再进回收站是原地打转）
        file("${DeletePathTool.TRASH_DIR}/已删.txt", "回收站里的")
        val b = DeletePathTool.run("${DeletePathTool.TRASH_DIR}/已删.txt", root = root)
        assertTrue("回收站里的东西应当直接清掉：$b", b is ToolOutcome.Ok)
        assertTrue((b as ToolOutcome.Ok).text.contains("从回收站彻底删除"))
        assertFalse(File(root, "${DeletePathTool.TRASH_DIR}/已删.txt").exists())
    }

    @Test
    fun `D8_不存在的文件说话不抛`() {
        val out = DeletePathTool.run("没有这个.txt", root = root)
        assertTrue(out is ToolOutcome.Failed)
        assertTrue((out as ToolOutcome.Failed).error.contains("不存在"))
    }

    @Test
    fun `D9_空路径直接拒`() {
        assertTrue(DeletePathTool.run("   ", root = root) is ToolOutcome.Failed)
    }

    // ── 源码级：接上了才算数 ────────────────────────────────────────────────

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun code(rel: String): String = repoFile(rel).readText()
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    @Test
    fun `G1_注册表里有delete_path且默认启用`() {
        val reg = code("src/main/java/com/example/zhengdao/baopu/agent/ToolRegistry.kt")
        assertTrue("ENTRIES 里没注册 delete_path", reg.contains("Entry(\"delete_path\")"))
        assertTrue("DEFAULT_ENABLED 里没有 delete_path", reg.contains("\"delete_path\","))
    }

    @Test
    fun `G2_权限必须是CONFIRM且源码写死`() {
        val src = code("src/main/java/com/example/zhengdao/baopu/agent/tools/DeletePathTool.kt")
        assertTrue("删除必须有确认卡（permission = CONFIRM）", src.contains("permission = ToolPermission.CONFIRM"))
        assertFalse("删除工具不许出现 FREE 权限", src.contains("permission = ToolPermission.FREE"))
    }

    @Test
    fun `G3_设置页两个语言都有标签与副标题`() {
        val screen = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuSettingsScreen.kt")
        assertTrue("baopuToolLabel 漏了 delete_path", screen.contains("\"delete_path\" -> R.string.baopu_tool_delete_path"))
        assertTrue("baopuToolDesc 漏了 delete_path", screen.contains("\"delete_path\" -> R.string.baopu_tool_delete_path_desc"))
        val zh = repoFile("src/main/res/values/strings.xml").readText()
        val en = repoFile("src/main/res/values-en/strings.xml").readText()
        listOf("baopu_tool_delete_path", "baopu_tool_delete_path_desc").forEach { key ->
            assertTrue("中文缺 $key", zh.contains("name=\"$key\""))
            assertTrue("英文缺 $key", en.contains("name=\"$key\""))
        }
    }
    @Test
    fun `D10_清空回收站一张卡干完_子目录留着`() {
        // 2026-10-19 用户真机：回收站 14 个文件逐个删 = 14 步 14 张卡，撞步数上限
        file("${DeletePathTool.TRASH_DIR}/a.txt", "a")
        file("${DeletePathTool.TRASH_DIR}/b.txt", "bb")
        File(root, "${DeletePathTool.TRASH_DIR}/子目录").mkdirs()
        file("${DeletePathTool.TRASH_DIR}/子目录/keep.txt", "keep")
        val out = DeletePathTool.emptyTrash(root)
        assertTrue("应当成功：$out", out is ToolOutcome.Ok)
        val text = (out as ToolOutcome.Ok).text
        assertTrue("回执要说删了几个：$text", text.contains("删掉 2 个"))
        assertFalse(File(root, "${DeletePathTool.TRASH_DIR}/a.txt").exists())
        assertFalse(File(root, "${DeletePathTool.TRASH_DIR}/b.txt").exists())
        assertTrue("子目录里的东西要留着（逐层删才有反悔余地）", File(root, "${DeletePathTool.TRASH_DIR}/子目录/keep.txt").isFile)
    }

    @Test
    fun `D11_回收站本来就空时说话不抛`() {
        val out = DeletePathTool.emptyTrash(root)
        assertTrue(out is ToolOutcome.Ok)
        assertTrue((out as ToolOutcome.Ok).text.contains("空的"))
    }

    @Test
    fun `D12_给模型看的说明里_回收站路径就是盘上那个`() {
        // E-163（真机 2026-10-19 走查发现）：回收站早就从 `.回收站/` 改成可见的 `回收站/`，
        // 但工具说明（模型读的那段）和设置页文案还写着「.回收站/」——模型会照着它说错路径。
        val desc = DeletePathTool.spec { root }.description
        assertTrue("说明要写准落点：工作区/回收站/时间戳-原名 ⇒ 实际是 $desc", desc.contains("工作区/回收站/时间戳-原名"))
        assertFalse("不许再说点开头的旧路径（盘上没有这个目录）", desc.contains(".回收站"))
    }
}