// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * LibraryPipeline 单测（刀 2 B 段五步 ＋ D 段导出）。
 *
 * 钉死派工单原话级口径：
 * - **幂等**：同一批跑两遍，第二遍全记 duplicate，索引字节与第一遍相同（判据 §5.6）；
 * - **原件永不删**（除用户确认的待删除清单）；
 * - 产出 `整理/<同名>.md`，`txt`/`md` 直通不加来源头；
 * - 导出撞名不同内容 ⇒ 加 sha 后缀让路，**不覆盖**。
 */
class LibraryPipelineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var raw: File
    private lateinit var done: File
    private lateinit var pipeline: LibraryPipeline

    private fun setup() {
        raw = tmp.newFolder("原始")
        done = tmp.newFolder("整理")
        pipeline = LibraryPipeline(raw, done)
    }

    private fun rawFile(name: String, text: String = "正文内容") {
        val f = File(raw, name)
        f.parentFile?.mkdirs()
        f.writeText(text)
    }

    // ── 五步流水线 ────────────────────────────────────────────────────────

    @Test
    fun `流水线_txt直通不加来源头`() {
        setup()
        rawFile("a.txt", "甲")
        val r = pipeline.run()

        assertEquals(1, r.processed.size)
        assertEquals("a.md", r.processed.single().product)
        val out = File(done, "a.md").readText()
        assertEquals("甲", out)
        assertFalse(out.contains("> 来源："))
        assertTrue(r.indexFile.isFile)
        assertTrue(r.ok)
    }

    @Test
    fun `流水线_非txt_md加一行来源头`() {
        setup()
        rawFile("a.json", "{}")
        val r = pipeline.run()

        val out = File(done, "a.md").readText()
        assertTrue(out.startsWith("> 来源：a.json"))
        assertTrue(out.contains("{}"))
    }

    @Test
    fun `流水线_子目录结构保留`() {
        setup()
        rawFile("sub/b.md", "乙")
        val r = pipeline.run()
        assertEquals("b.md", r.processed.single().product)
        assertTrue(File(done, "b.md").isFile)
    }

    @Test
    fun `流水线_同批两遍第二遍全duplicate且索引字节相同`() {
        setup()
        rawFile("a.txt", "甲")
        rawFile("b.txt", "乙")

        val r1 = pipeline.run()
        val bytes1 = r1.indexFile.readBytes()
        assertEquals(2, r1.processed.size)

        val r2 = pipeline.run()
        assertEquals(0, r2.processed.size)
        assertEquals(2, r2.duplicates.size)
        assertEquals("索引字节必须稳定", bytes1.size, r2.indexFile.readBytes().size)
        assertEquals(String(bytes1), r2.indexFile.readText())
    }

    @Test
    fun `流水线_非UTF8件拒绝并进清单`() {
        setup()
        File(raw, "gbk.txt").writeBytes(byteArrayOf(0xD6.toByte(), 0xD0.toByte(), 0xCE.toByte(), 0xC4.toByte()))
        val r = pipeline.run()

        assertFalse(r.ok)
        assertEquals(1, r.rejected.size)
        assertTrue(r.rejected.single().reason.contains("不是 UTF-8"))
        assertFalse(File(done, "gbk.md").exists())
    }

    @Test
    fun `流水线_撞名不同内容加sha后缀`() {
        setup()
        rawFile("a.txt", "甲")
        pipeline.run()
        // 换内容重跑（原件被改过）
        rawFile("a.txt", "甲乙丙")
        val r = pipeline.run()

        assertTrue(r.duplicates.isEmpty()) // sha 变了 ⇒ 不是 duplicate
        assertEquals(1, r.processed.size)
        // 整理目录里有两个产物（旧 + sha 后缀的新）；索引文件不算产物
        val products = done.listFiles()!!.filter { it.name != FaceIndex.FILE_NAME }.map { it.name }.sorted()
        assertEquals(listOf("a." + FaceIndex.sha256Bytes("甲乙丙".toByteArray()).take(8) + ".md", "a.md"), products)
        // 原件永不删
        assertTrue(File(raw, "a.txt").isFile)
    }

    @Test
    fun `流水线_隔离位由壳层喂_默认真`() {
        setup()
        rawFile("a.txt")
        val r = pipeline.run(isolatedBySha = mapOf(FaceIndex.sha256Bytes("正文内容".toByteArray()) to false))
        assertFalse(r.processed.isEmpty())
        val doc = pipeline.readDoc()
        assertFalse(doc.items.single().isolated)
    }

    @Test
    fun `流水线_onItem回调逐件给新认脸的`() {
        setup()
        rawFile("a.txt", "甲甲") // 内容必须不同——同内容会被去重成一条（认脸的本意）
        rawFile("b.txt", "乙乙")
        val seen = mutableListOf<String>()
        pipeline.run(onItem = { seen += it.name })
        assertEquals(listOf("a.txt", "b.txt"), seen.sorted())
    }

    @Test
    fun `索引_坏文件按空索引重写`() {
        setup()
        File(done, FaceIndex.FILE_NAME).writeText("{ 这不是合法索引")
        val doc = pipeline.readDoc()
        assertTrue(doc.items.isEmpty())
    }

    // ── 导出（D 段） ──────────────────────────────────────────────────────

    @Test
    fun `导出_全部进整理的时间戳目录`() {
        setup()
        rawFile("a.txt", "甲")
        rawFile("b.txt", "乙")
        pipeline.run()

        val r = pipeline.exportAll(now = 1_700_000_000_000L)

        assertEquals(2, r.exported.size)
        assertNotNull(r.dir)
        assertTrue(r.dir!!.name.startsWith(LibraryPipeline.DIR_EXPORT_PREFIX))
        assertTrue(File(r.dir, "a.txt").isFile)
        // 原件永不删
        assertTrue(File(raw, "a.txt").isFile)
    }

    @Test
    fun `导出_只导文本件那一档`() {
        setup()
        rawFile("a.txt", "甲")
        pipeline.run()
        // 手动往索引里塞一条 unclaimed（流水线不会处理它，但索引里可能有旧行）
        val doc = pipeline.readDoc()
        val withUnclaimed = FaceIndex.Doc(
            items = doc.items + FaceIndex.Item(
                sha256 = "sha-x", name = "x.pdf", size = 1, mtime = 1,
                source = LibrarySource.DELIVER, kind = LibraryKind.UNCLAIMED,
            ),
        )
        LibraryPaths.atomicWrite(File(done, FaceIndex.FILE_NAME), FaceIndex.render(withUnclaimed))

        val r = pipeline.exportKind(LibraryKind.TEXT, now = 1L)

        assertEquals(listOf("a.txt"), r.exported)
        assertFalse(File(r.dir, "x.pdf").exists())
    }

    @Test
    fun `导出_空索引明说没有可导出`() {
        setup()
        val r = pipeline.exportAll(now = 1L)
        assertTrue(r.exported.isEmpty())
        assertNull(r.dir)
        assertTrue(r.notes.single().contains("还没有认脸索引"))
    }

    @Test
    fun `导出_撞名不同内容不覆盖加后缀`() {
        setup()
        rawFile("a.txt", "甲")
        pipeline.run()
        // 先手工在导出目录放一个同名不同内容的（模拟上一次导出的旧文件）
        val dir = File(done, LibraryPipeline.DIR_EXPORT_PREFIX + "manual")
        dir.mkdirs()
        File(dir, "a.txt").writeText("旧内容")

        val r = pipeline.exportAll(now = 1_700_000_000_000L)

        assertEquals(1, r.exported.size)
        // 新导出到新目录，不碰旧目录；且新目录里的内容是对的
        assertEquals("甲", File(r.dir, "a.txt").readText())
        assertEquals("旧内容", File(dir, "a.txt").readText())
    }

    @Test
    fun `导出_回写exported_at且onExported逐件回调`() {
        setup()
        rawFile("a.txt")
        pipeline.run()
        val seen = mutableListOf<Pair<String, Long>>()

        pipeline.exportAll(now = 4242L) { sha, at -> seen += sha to at }

        assertEquals(1, seen.size)
        assertEquals(4242L, seen.single().second)
        val doc = pipeline.readDoc()
        assertEquals(4242L, doc.lastExportAt)
        assertEquals(0, doc.unexportedCount)
    }

    @Test
    fun `导出_私有原件不在则跳过并记notes`() {
        setup()
        rawFile("a.txt")
        pipeline.run()
        // 模拟原件丢了（索引还在）
        File(raw, "a.txt").delete()

        val r = pipeline.exportAll(now = 1L)

        assertTrue(r.exported.isEmpty())
        assertTrue(r.notes.any { it.contains("私有原件不在") })
    }

    // ── 防丢判据（纯函数） ────────────────────────────────────────────────

    @Test
    fun `防丢判据_空库不提醒`() {
        assertFalse(BackupRules.isStale(0L, now = 100L, totalCount = 0))
        assertFalse(BackupRules.isStale(50L, now = 100L, totalCount = 0))
    }

    @Test
    fun `防丢判据_从没导出过且库里有件立刻算超期`() {
        assertTrue(BackupRules.isStale(0L, now = 100L, totalCount = 1))
    }

    @Test
    fun `防丢判据_N天可配`() {
        val day = 24L * 60 * 60 * 1000
        val now = 100L * day
        // 正好 N 天不算超（判据是"超过"）
        assertFalse(BackupRules.isStale(now - 30 * day, now = now, totalCount = 1, staleDays = 30))
        assertTrue(BackupRules.isStale(now - 30 * day - 1, now = now, totalCount = 1, staleDays = 30))
        assertTrue(BackupRules.isStale(now - 8 * day, now = now, totalCount = 1, staleDays = 7))
        // lastExportAt = 0（从没导出过）与「N 天前」是两回事：前者立刻算超
        assertTrue(BackupRules.isStale(0L, now = now, totalCount = 1, staleDays = 30))
    }

    @Test
    fun `距今天数_从没导出过是null_否则按天算`() {
        val day = 24L * 60 * 60 * 1000
        assertNull(BackupRules.daysSinceExport(0L, now = 100L))
        val now = 10L * day
        assertEquals(3L, BackupRules.daysSinceExport(now - 3 * day, now))
        assertEquals(0L, BackupRules.daysSinceExport(now, now))
    }
}
