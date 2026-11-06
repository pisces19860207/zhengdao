// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百九十四批判据：造 5 个文件（正常 .md／无 asOf 的 .md／非 UTF-8／超大／无权限）
// ⇒ 各归其位；`项目记忆.md` 不进坏文件计数；四类分别计数能列名；每条可查原因；
// 收编的幂等与「不覆盖」语义不变。
//
// 关于「无权限」：Windows 上 `File.setReadable(false)` 对 JVM 常常不作数（ACL/管理员），
// 拿它当判据会得到一条随机红的测试。本批把读盘口注入 MemoryStore（生产仍走 readBytes），
// 用「读这个文件时抛 IOException」稳定复现权限/IO 拒绝——**证据等级是模拟 IO 失败**，
// 不冒充真机权限；真机权限的取证归验收方（报告 §风险）。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

class MemoryScanClassifyTest {

    @Rule
    @JvmField
    val tmp: TemporaryFolder = TemporaryFolder()

    /** 一条带完整 front-matter（含 as_of）的合法记忆 .md。 */
    private fun good(id: String = "1-a") = MemoryEntry(
        id = id,
        scope = MemoryScope.PROJECT,
        kind = MemoryKind.PREFERENCE,
        title = "称呼偏好",
        body = "用户希望被称呼为老师",
        sourceSession = "s7",
        sourceFile = "资料库/原始/设定.md",
        status = MemoryStatus.CONFIRMED,
        createdAt = 1_729_000_000_000L,
        updatedAt = 1_729_000_000_000L,
        asOf = 1_729_000_000_000L,
    )

    /** 造一棵真实目录：[reader] 可注入（模拟权限拒绝）。 */
    private fun store(reader: (File) -> ByteArray = { it.readBytes() }): Pair<MemoryStore, File> {
        val s = MemoryStore(tmp.newFolder("app"), tmp.newFolder("ws"), reader)
        val dir = s.projectDir().apply { mkdirs() }
        return s to dir
    }

    private fun write(dir: File, name: String, text: String): File =
        File(dir, name).apply { writeText(text, Charsets.UTF_8) }

    private fun kindsOf(r: MemoryStore.ReindexResult): Set<ScanSkipKind> =
        r.sheet.rows.map { it.kind }.toSet()

    // ── 判据①：5 个文件各归其位（5 条测试） ─────────────────────────────

    @Test
    fun `正常md_照常收编_四类里一个都不进`() {
        val (s, dir) = store()
        write(dir, "正常.md", EntryCodec.render(good()))
        val r = s.reindex()
        assertEquals("正常 .md 该收的还收", 1, r.entries.size)
        assertTrue("正常 .md 不进坏", r.brokenFiles.isEmpty())
        assertTrue("正常 .md 也不标未结构化", r.unstructuredFiles.isEmpty())
        assertTrue(r.sheet.rows.isEmpty())
    }

    @Test
    fun `无asOf的自由文本md_只标未结构化_不进坏`() {
        val (s, dir) = store()
        write(dir, "项目记忆.md", "# 项目记忆\n\n术语：调解＝黑盒\n")
        val r = s.reindex()
        assertTrue("合法 markdown 永不进坏", r.brokenFiles.isEmpty())
        assertEquals(listOf("项目记忆.md"), r.unstructuredFiles)
        assertEquals(ScanSkipKind.FORMAT_MISMATCH, r.sheet.rows.single().kind)
        assertTrue("formatMismatch 的人读原因要写明「原样保留」", r.sheet.rows.single().reason.contains("原样保留"))
    }

    @Test
    fun `非UTF8_归编码错_不伪装成格式不符`() {
        val (s, dir) = store()
        val f = File(dir, "非UTF8.md").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x41))
        }
        val r = s.reindex()
        assertEquals(ScanSkipKind.BAD_ENCODING, r.sheet.rows.single().kind)
        assertEquals(f.name, r.sheet.rows.single().name)
        assertEquals(listOf(f.name), r.brokenFiles)
        assertTrue("编码错是真坏，但也不能算未结构化", r.unstructuredFiles.isEmpty())
    }

    @Test
    fun `超大_归超限_而且不硬读`() {
        val (s, dir) = store()
        val f = File(dir, "超大.md")
        RandomAccessFile(f, "rw").use { it.setLength(MemoryScanRules.MAX_FILE_BYTES + 1) }
        val r = s.reindex()
        assertEquals(ScanSkipKind.TOO_BIG, r.sheet.rows.single().kind)
        assertEquals(listOf(f.name), r.brokenFiles)
        assertTrue("超限原因里要给出上限与实读字节数", r.sheet.rows.single().reason.contains("字节上限"))
    }

    @Test
    fun `无权限_归读不了_不伪装成格式不符`() {
        val denied: (File) -> ByteArray = { throw IOException("Access denied (模拟权限拒绝)") }
        val (s, dir) = store(denied)
        val f = write(dir, "无权限.md", EntryCodec.render(good()))
        val r = s.reindex()
        assertEquals(ScanSkipKind.UNREADABLE, r.sheet.rows.single().kind)
        assertEquals(listOf(f.name), r.brokenFiles)
        assertTrue("读不了的原因里要写出异常种类", r.sheet.rows.single().reason.contains("IOException"))
        assertTrue("源文件一个字节没动", f.readText(Charsets.UTF_8).startsWith("---"))
    }

    // ── 判据②：项目记忆.md 不出现在坏文件计数里 ─────────────────────────

    @Test
    fun `项目记忆md_不出现在坏文件计数里`() {
        val (s, dir) = store()
        write(dir, "项目记忆.md", "# 项目记忆\n这批留在哪：工作区\n取证要点：看日志\n")
        val r = s.reindex()
        assertFalse("坏文件清单不许出现 项目记忆.md", r.brokenFiles.contains("项目记忆.md"))
        assertFalse("坏行集合不许出现 项目记忆.md", r.sheet.badNames.contains("项目记忆.md"))
        assertEquals("坏文件计数必须是 0", 0, r.sheet.badRows.size)
        assertEquals("它只能落在未结构化那一格", listOf("项目记忆.md"), r.sheet.unstructuredNames)
        assertEquals("坏 0 · 未结构化 1", "坏 0 · 未结构化 1", r.sheet.line())
    }

    // ── 判据③：四类分别计数、能列名 ─────────────────────────────────────

    @Test
    fun `四类分别计数_而且各能列出文件名`() {
        val reader: (File) -> ByteArray = { f ->
            if (f.name == "无权限.md") throw IOException("denied") else f.readBytes()
        }
        val (s, dir) = store(reader)
        write(dir, "正常.md", EntryCodec.render(good()))
        write(dir, "无asOf.md", "# 自由文本\n口径：黑盒\n")
        File(dir, "非UTF8.md").writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
        RandomAccessFile(File(dir, "超大.md"), "rw").use { it.setLength(MemoryScanRules.MAX_FILE_BYTES + 1) }
        write(dir, "无权限.md", EntryCodec.render(good("2-b")))

        val r = s.reindex()
        assertEquals("正常那个仍收编", 1, r.entries.size)
        assertEquals("四类齐了", setOf(
            ScanSkipKind.UNREADABLE, ScanSkipKind.BAD_ENCODING,
            ScanSkipKind.TOO_BIG, ScanSkipKind.FORMAT_MISMATCH,
        ), kindsOf(r))
        assertEquals(1, r.sheet.count(ScanSkipKind.UNREADABLE))
        assertEquals(1, r.sheet.count(ScanSkipKind.BAD_ENCODING))
        assertEquals(1, r.sheet.count(ScanSkipKind.TOO_BIG))
        assertEquals(1, r.sheet.count(ScanSkipKind.FORMAT_MISMATCH))
        assertEquals(setOf("非UTF8.md", "超大.md", "无权限.md"), r.sheet.badNames.toSet())
        assertEquals(listOf("无asOf.md"), r.sheet.unstructuredNames)
        assertEquals("体检单：坏 3（1 读不了 / 1 编码错 / 1 超限）· 未结构化 1",
            "坏 3（1 读不了 / 1 编码错 / 1 超限）· 未结构化 1", r.sheet.line())
    }

    @Test
    fun `汇总行_工单示例逐字对得上`() {
        val rows = listOf(
            ScanSkipRow("读不了.md", ScanSkipKind.UNREADABLE, "权限"),
            ScanSkipRow("编码错.md", ScanSkipKind.BAD_ENCODING, "非 UTF-8"),
        ) + (1..5).map { ScanSkipRow("自由$it.md", ScanSkipKind.FORMAT_MISMATCH, "没 front-matter") }
        assertEquals("坏 2（1 读不了 / 1 编码错）· 未结构化 5", ScanSheet(rows).line())
        assertEquals("坏 0 · 未结构化 0", ScanSheet.EMPTY.line())
    }

    // ── 判据④：每条被判的文件带 reason，可查 ───────────────────────────

    @Test
    fun `每条被判的文件都带wire名与原因`() {
        val (s, dir) = store()
        write(dir, "a.md", "# 自由文本\n")
        File(dir, "b.md").writeBytes(byteArrayOf(0xC3.toByte(), 0x28.toByte()))
        val r = s.reindex()
        assertEquals(2, r.sheet.rows.size)
        r.sheet.rows.forEach { row ->
            assertTrue("${row.name} 的原因不能是空的", row.reason.isNotBlank())
            assertTrue("${row.name} 的类别要有稳定键", row.kind.wire.isNotBlank())
        }
        val lines = r.sheet.detailLines()
        assertEquals(r.sheet.rows.size, lines.size)
        assertTrue("明细行要含文件名", lines.any { it.contains("a.md") })
        assertTrue("明细行要含类别键", lines.any { it.contains("badEncoding") })
        assertTrue("明细行要含原因", lines.any { it.contains("UTF-8") })
    }

    // ── 判据⑤：收编策略不变（该收的还收、幂等、不覆盖、只读） ───────────

    @Test
    fun `带frontMatter但缺asOf_仍照常收编_不进坏`() {
        val (s, dir) = store()
        // 451 既定口径：老格式缺 as_of ⇒ 读回来 asOf=0（标待补），照样进条目。494 不动它。
        write(dir, "老条目.md", EntryCodec.render(good().copy(asOf = 0L)))
        val r = s.reindex()
        assertEquals("该收的还收", 1, r.entries.size)
        assertEquals("asOf 保持 0，不编造", 0L, r.entries.single().asOf)
        assertTrue(r.brokenFiles.isEmpty())
        assertTrue(r.unstructuredFiles.isEmpty())
    }

    @Test
    fun `连跑两次_读数逐字相同_文件字节不变`() {
        val (s, dir) = store()
        val goodFile = write(dir, "正常.md", EntryCodec.render(good()))
        val freeFile = write(dir, "项目记忆.md", "# 项目记忆\n口径：黑盒\n")
        val before = listOf(goodFile, freeFile).map { it.readBytes().toList() }
        val first = s.reindex()
        val second = s.reindex()
        assertEquals("条目同源同序", first.entries, second.entries)
        assertEquals("坏清单幂等", first.brokenFiles, second.brokenFiles)
        assertEquals("未结构化清单幂等", first.unstructuredFiles, second.unstructuredFiles)
        assertEquals("体检单逐字相同（含 reason）", first.sheet, second.sheet)
        assertEquals("读路径一个字节都不写盘", before, listOf(goodFile, freeFile).map { it.readBytes().toList() })
    }
}
