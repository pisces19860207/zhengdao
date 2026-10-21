// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 判据：派工单-2026-10-21-第一百二十八批 §3 的索引侧：建索引/增量/重建、
// docx/编码/大文件跳过、索引损坏恢复（与 KbIndexStoreTest 合起来覆盖）。
package com.example.zhengdao.baopu.kb

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KbIndexerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun root(): File = tmp.newFolder()

    private fun write(dir: File, rel: String, text: String): File {
        val f = File(dir, rel)
        f.parentFile?.mkdirs()
        f.writeText(text, Charsets.UTF_8)
        return f
    }

    // ── 建索引：多格式混排、跳过计数 ──────────────────────────────────────

    @Test
    fun `建索引_多格式混排_不认领的跳过并计数`() {
        val ws = root()
        write(ws, "小说.txt", "第一章内容\n\n第二章内容")
        write(ws, "笔记.md", "# 标题\n正文")
        write(ws, "页.html", "<p>网页正文</p>")
        write(ws, "书.docx", "占位") // 假 docx：内容不是 zip ⇒ 抽不出 ⇒ 读失败计数
        write(ws, "图.png", "不是真图") // 内容无 NUL 但扩展名不认领
        write(ws, "无扩展名", "猜不了")

        val r = KbIndexer.build(ws, label = "ws")
        val names = r.index.docs.map { it.relPath }.sorted()
        assertEquals(listOf("小说.txt", "笔记.md", "页.html"), names)
        assertEquals(3, r.index.stats.filesIndexed)
        // 跳过账：假 docx 归"读不出"，png/无扩展名归"不认领"
        assertEquals(1, r.index.stats.skippedBinary)
        assertEquals(2, r.index.stats.skippedUnclaimed)
        assertTrue(r.report.contains("已索引目录：ws"))
    }

    @Test
    fun `大文件跳过_二进制跳过`() {
        val ws = root()
        // 2 MB + 1 字节的文本 ⇒ 超 KbIndexer.MAX_FILE_BYTES
        write(ws, "大.txt", "x".repeat((KbIndexer.MAX_FILE_BYTES + 1).toInt()))
        val bin = File(ws, "带NUL.txt")
        bin.writeBytes(byteArrayOf(65, 0, 66)) // A\0B ⇒ 二进制
        write(ws, "正常.txt", "正常内容")

        val r = KbIndexer.build(ws, label = "ws")
        assertEquals(listOf("正常.txt"), r.index.docs.map { it.relPath })
        assertEquals(1, r.index.stats.skippedBig)
        assertEquals(1, r.index.stats.skippedBinary)
    }

    @Test
    fun `文件数上限_触顶明示不静默`() {
        val ws = root()
        for (i in 1..5) write(ws, "f$i.txt", "内容$i")
        val r = KbIndexer.build(ws, label = "ws", maxFiles = 3)
        assertEquals(3, r.index.docs.size)
        assertTrue(r.index.stats.truncated)
        assertTrue("触顶必须明说", r.report.contains("未索引进全部"))
    }

    // ── 增量：指纹没变整体留用；变了重切；消失的删除 ──────────────────────

    @Test
    fun `增量_没变的复用_变了的重切_消失的删除`() {
        val ws = root()
        val a = write(ws, "a.txt", "旧内容甲")
        val b = write(ws, "b.txt", "内容乙")
        val first = KbIndexer.build(ws, label = "ws").index

        // 只改 a（mtime 会变；内容也变）
        Thread.sleep(1100) // 确保 mtime 跨秒（部分文件系统 mtime 粒度 1s）
        a.writeText("新内容甲改了", Charsets.UTF_8)
        val second = KbIndexer.build(ws, label = "ws", previous = first).index

        assertEquals(2, second.docs.size)
        assertEquals(1, second.stats.reused) // b 复用
        val aDoc = second.docs.first { it.relPath == "a.txt" }
        assertTrue("改过的文件必须重切进新内容", aDoc.chunks.any { it.text.contains("新内容甲改了") })
        assertTrue(aDoc.chunks.none { it.text.contains("旧内容甲") })
        val bDoc = second.docs.first { it.relPath == "b.txt" }
        assertEquals("内容乙", bDoc.chunks.single().text)

        // 删掉 b ⇒ 索引里不再有它
        assertTrue(b.delete())
        val third = KbIndexer.build(ws, label = "ws", previous = second).index
        assertEquals(listOf("a.txt"), third.docs.map { it.relPath })
    }

    @Test
    fun `增量_只改mtime内容不变也重切_复用判据是大小加mtime`() {
        val ws = root()
        val a = write(ws, "a.txt", "内容稳定")
        val first = KbIndexer.build(ws, label = "ws").index
        Thread.sleep(1100)
        a.setLastModified(System.currentTimeMillis() + 60_000) // 只动 mtime
        val second = KbIndexer.build(ws, label = "ws", previous = first).index
        assertEquals("mtime 变了 ⇒ 不留用（宁可重切，不许漏改）", 0, second.stats.reused)
    }

    @Test
    fun `rebuild_忽略指纹全量重切`() {
        val ws = root()
        write(ws, "a.txt", "内容甲")
        val first = KbIndexer.build(ws, label = "ws").index
        val second = KbIndexer.build(ws, label = "ws", previous = first, rebuild = true).index
        assertEquals("rebuild 时一个都不许留用", 0, second.stats.reused)
        assertEquals(1, second.docs.single().chunks.size)
    }

    @Test
    fun `换了目录_旧索引不适用_自动全量`() {
        val ws1 = root()
        val ws2 = root()
        write(ws1, "a.txt", "目录一")
        write(ws2, "a.txt", "目录二")
        val first = KbIndexer.build(ws1, label = "ws1").index
        // 拿着 ws1 的索引去建 ws2：root 不符 ⇒ 不挪用旧账
        val second = KbIndexer.build(ws2, label = "ws2", previous = first).index
        assertEquals(0, second.stats.reused)
        assertEquals("目录二", second.docs.single().chunks.single().text)
    }

    // ── 指纹细节：size 变了也重切 ─────────────────────────────────────────

    @Test
    fun `增量_大小变了重切`() {
        val ws = root()
        write(ws, "a.txt", "短")
        val first = KbIndexer.build(ws, label = "ws").index
        Thread.sleep(1100)
        write(ws, "a.txt", "变长了好几步")
        val second = KbIndexer.build(ws, label = "ws", previous = first).index
        assertEquals(0, second.stats.reused)
        assertEquals("变长了好几步", second.docs.single().chunks.single().text)
    }

    // ── 索引内容抽查：md 的 heading 与行号进索引 ──────────────────────────

    @Test
    fun `索引内容_md标题与行号入库`() {
        val ws = root()
        write(ws, "书.md", "# 第三章\n决战紫禁之巅\n\n# 第四章\n尾声")
        val r = KbIndexer.build(ws, label = "ws")
        val chunks = r.index.docs.single().chunks
        assertEquals(2, chunks.size)
        assertEquals("第三章", chunks[0].heading)
        assertEquals(1, chunks[0].lineStart)
        assertEquals("第四章", chunks[1].heading)
    }
}
