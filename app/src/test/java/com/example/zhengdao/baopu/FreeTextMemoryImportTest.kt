// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 451 批判据③／④：两份自由文本 .md 收编的**幂等、不丢行、不编造**。
 *
 * 与 DB 侧的分工：[BaopuDbAsOfTest] 管 `as_of` 列与复核查询，这里管解析与落盘。
 */
class FreeTextMemoryImportTest {

    private val tmp: MutableList<File> = mutableListOf()

    private fun tempDir(): File = Files.createTempDirectory("zd451").toFile().also { tmp += it }

    @After
    fun clean() {
        tmp.forEach { runCatching { it.deleteRecursively() } }
    }

    private val global = "偏好：中文、简洁\n# 手写的章节标题\n- 规矩：不许批量删除\n乱码行没有键值\n\n事实：模型X支持长文\n"
    private val project = "- 口径：报告落证道设计报告\n普通段落一句话\n"

    @Test
    fun `幂等_连跑两次结果逐字一致`() {
        val a = FreeTextMemoryImport.parse(global, project)
        val b = FreeTextMemoryImport.parse(global, project)
        assertEquals("整份读数", a, b)
        assertEquals("结构化文本", a.rendered, b.rendered)
        assertEquals("回执", a.receipt, b.receipt)
        assertEquals("条目 id 不含时钟／随机数／行号", a.entries.map { it.id }, b.entries.map { it.id })
    }

    @Test
    fun `幂等_落盘两次文件树与字节一致`() {
        val r = FreeTextMemoryImport.parse(global, project)
        val dir = tempDir()
        assertEquals(r.rendered.size + 1, FreeTextMemoryStore.importInto(dir, r))
        val first = snapshot(dir)
        assertEquals(r.rendered.size + 1, FreeTextMemoryStore.importInto(dir, r))
        assertEquals("连跑两次目录内容逐字相同", first, snapshot(dir))
        assertTrue("回执要落盘", File(dir, FreeTextMemoryImport.RECEIPT_NAME).isFile)
    }

    private fun snapshot(dir: File): Map<String, String> =
        dir.listFiles().orEmpty().filter { it.isFile }.associate { it.name to it.readText(Charsets.UTF_8) }

    @Test
    fun `无法解析的行原样保留并计数`() {
        val r = FreeTextMemoryImport.parse(global, project)
        assertEquals(3, r.unresolvedCount)
        assertEquals(r.unresolved.size, r.unresolvedCount)
        for (line in listOf("# 手写的章节标题", "乱码行没有键值", "普通段落一句话")) {
            assertTrue("原文要逐字在清单里：$line", r.unresolved.contains(line))
            assertTrue("回执要逐字列出来：$line", r.receipt.contains(line))
        }
        assertTrue("回执要给计数", r.receipt.contains("共 3 行"))
        assertFalse("解析不了的行不许变成条目", r.entries.any { it.body.contains("手写的章节标题") })
    }

    @Test
    fun `不丢行_总行数等于条目加未解析加空白`() {
        val r = FreeTextMemoryImport.parse(global, project)
        // 按文件实际行算（含末尾换行后的空段）：global 7 ＋ project 3 = 10
        assertEquals(10, r.sourceLineCount)
        assertEquals(
            "一条都不许吞：${r.sourceLineCount} = ${r.entries.size} + ${r.unresolvedCount} + ${r.blankCount}",
            r.sourceLineCount,
            r.entries.size + r.unresolvedCount + r.blankCount,
        )
        assertEquals("4 条 ＋ 3 行未解析 ＋ 3 行空白", listOf(4, 3, 3),
            listOf(r.entries.size, r.unresolvedCount, r.blankCount))
    }

    @Test
    fun `超长行原样进未解析_不静默截断`() {
        val huge = "事实：" + "长".repeat(MemoryRules.BODY_LIMIT + 10)
        val r = FreeTextMemoryImport.parse(huge + "\n", null)
        assertEquals("超长行不许进结构化副本（截断就是丢字）", 0, r.entries.size)
        assertEquals(1, r.unresolvedCount)
        assertEquals("原文要一个字不少地留着", huge, r.unresolved.single())
        assertTrue("回执里也要能看到全文", r.receipt.contains(huge))
    }

    @Test
    fun `同内容重复行不互相覆盖_id只追加序号`() {
        val r = FreeTextMemoryImport.parse("偏好：中文\n偏好：中文\n", null)
        assertEquals(2, r.entries.size)
        assertNotEquals("两行同内容也要各占一条", r.entries[0].id, r.entries[1].id)
        assertTrue("序号是确定性的：${r.entries[1].id}", r.entries[1].id.endsWith("-2"))
    }

    @Test
    fun `缺source与asOf一律待补_不许编造`() {
        val r = FreeTextMemoryImport.parse(global, project)
        assertTrue(r.entries.isNotEmpty())
        for (e in r.entries) {
            assertEquals("不许编造核实时刻：${e.id}", 0L, e.asOf)
            assertEquals(FreeTextMemoryImport.PENDING, e.sourceSession)
            assertEquals(FreeTextMemoryImport.PENDING, e.sourceFile)
            assertEquals(listOf("source", "asOf"), r.missingFields[e.id])
            assertTrue("条目文件要看得见待补：${e.id}", r.rendered[e.id + ".md"]!!.contains("source_session: 待补"))
            assertTrue("条目文件要看得见 as_of 没记：${e.id}", r.rendered[e.id + ".md"]!!.contains("as_of: 0"))
        }
        assertTrue("回执要说清没编造", r.receipt.contains("未编造任何时刻或出处"))
    }

    @Test
    fun `回执是txt_不会被记忆扫描当成条目`() {
        assertTrue(FreeTextMemoryImport.RECEIPT_NAME.endsWith(".txt"))
        assertFalse("回执若用 .md 会被 MemoryStore.scan() 当条目解析失败",
            FreeTextMemoryImport.RECEIPT_NAME.endsWith(".md"))
    }

    @Test
    fun `机器收编不自动确认_状态复核且不进上下文`() {
        val r = FreeTextMemoryImport.parse(global, project)
        assertTrue(r.entries.all { it.status == MemoryStatus.PENDING })
        assertTrue(r.entries.none { MemoryTiers.injectable(it) })
        assertTrue(r.entries.none { MemoryTiers.reviewDue(it, System.currentTimeMillis()) })
    }

    @Test
    fun `两份文件分作用域_标题与kind按键表映射`() {
        val r = FreeTextMemoryImport.parse(global, project)
        // global：偏好／规矩／事实 3 条；project：口径 1 条
        assertEquals(3, r.entries.count { it.scope == MemoryScope.GLOBAL })
        assertEquals(1, r.entries.count { it.scope == MemoryScope.PROJECT })
        assertTrue(r.entries.any { it.kind == MemoryKind.PREFERENCE })
        assertTrue(r.entries.any { it.kind == MemoryKind.RULE })
        assertTrue(r.entries.any { it.kind == MemoryKind.FACT })
        assertTrue(r.entries.any { it.kind == MemoryKind.LESSON })
    }

    @Test
    fun `收编只写目标目录_源文件与目标目录外一个字节不动`() {
        val dir = tempDir()
        val g = File(dir, "全局记忆.md")
        val p = File(dir, "项目记忆.md")
        g.writeText(global, Charsets.UTF_8)
        p.writeText(project, Charsets.UTF_8)
        val out = File(dir, "out")
        val r = FreeTextMemoryStore.importFromFiles(g, p, out)
        assertEquals(global, g.readText(Charsets.UTF_8))
        assertEquals(project, p.readText(Charsets.UTF_8))
        assertEquals("目标目录里只有条目 ＋ 回执，没有源文件副本",
            r.rendered.keys + FreeTextMemoryImport.RECEIPT_NAME,
            out.listFiles().orEmpty().map { it.name }.toSet())
    }

    @Test
    fun `空输入给空读数但回执仍在_计数不撒谎`() {
        val r = FreeTextMemoryImport.parse(null, "")
        assertEquals(0, r.sourceLineCount)
        assertEquals(0, r.entries.size)
        assertEquals(0, r.unresolvedCount)
        assertEquals(0, r.rendered.size)
        assertTrue(r.receipt.contains("共 0 行"))
        assertTrue(r.receipt.contains("（无）"))
    }

    @Test
    fun `负例_回执里的每一行都能在输入原文里找到`() {
        val r = FreeTextMemoryImport.parse(global, project)
        val source = global + project
        for (line in r.unresolved) {
            assertTrue("回执／清单里的行必须是输入里的原文：$line", source.contains(line))
        }
        // 反向：输入里那三条不该被悄悄漏掉
        for (expected in listOf("# 手写的章节标题", "乱码行没有键值", "普通段落一句话")) {
            assertTrue("输入里的这行必须在未解析清单里：$expected", r.unresolved.contains(expected))
        }
        assertFalse("回执不许把已收编的条目再报成未解析",
            r.unresolved.any { it.startsWith("偏好：") || it.startsWith("事实：") })
    }
}
