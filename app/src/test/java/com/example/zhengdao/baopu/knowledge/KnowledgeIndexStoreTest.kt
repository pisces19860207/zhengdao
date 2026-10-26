// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 判据：派工单-2026-10-24-第一百八十二批 §0.5/§2/§4（自动索引、增量、删除同步、
// 索引可重建、原件只读、外部库只索引不搬、全量重扫负例贴红）。
package com.example.zhengdao.baopu.knowledge

import com.example.zhengdao.baopu.library.FaceIndex
import com.example.zhengdao.baopu.library.LibraryPaths
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KnowledgeIndexStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(dir: File, rel: String, text: String): File {
        val f = File(dir, rel)
        f.parentFile?.mkdirs()
        f.writeText(text, Charsets.UTF_8)
        return f
    }

    private fun workspaceWithFiles(files: File): File {
        val w = tmp.newFolder("ws")
        KnowledgeLayout.ensureDirs(w)
        write(KnowledgeLayout.rawDir(w), "a.txt", "旧甲段")
        write(KnowledgeLayout.rawDir(w), "b.txt", "乙内容")
        write(KnowledgeLayout.organizedDir(w), "doc/摘要.md", "# 摘要\n旧产物")
        // 外部根一：投递
        write(LibraryPaths.deliverDirUnder(w), "投.txt", "投递内容")
        // 外部根二：App 私有原件区
        write(LibraryPaths.rawDirUnder(files), "私.txt", "私有内容")
        return w
    }

    @Test
    fun `建索引_覆盖原始整理外部_排除索引自身`() {
        val files = tmp.newFolder("files")
        val w = workspaceWithFiles(files)
        val out = KnowledgeIndexStore.build(w, files, null, null)
        assertFalse(out.cancelled)
        val paths = out.index!!.docs.map { it.relPath }
        assertTrue(paths.contains("原始/a.txt"))
        assertTrue(paths.contains("整理/doc/摘要.md"))
        assertTrue(paths.contains("外部/投递/投.txt"))
        assertTrue(paths.contains("外部/私有原始/私.txt"))
        assertTrue(paths.none { it.startsWith("索引/") })
        // 写盘 + 快照
        assertTrue(KnowledgeIndexStore.save(w, out.index, out.snapshot))
        assertTrue(KnowledgeLayout.indexFile(w).isFile)
        assertTrue(KnowledgeLayout.snapshotFile(w).isFile)
        assertNotNull(KnowledgeIndexStore.load(w))
    }

    @Test
    fun `增量_只重读变了的文件_其余复用`() {
        val files = tmp.newFolder("files")
        val w = workspaceWithFiles(files)
        val first = KnowledgeIndexStore.build(w, files, null, null)
        assertTrue(KnowledgeIndexStore.save(w, first.index!!, first.snapshot))

        Thread.sleep(1_100) // mtime 粒度
        write(KnowledgeLayout.rawDir(w), "a.txt", "新甲段改过")
        val second = KnowledgeIndexStore.build(
            w, files, KnowledgeIndexStore.load(w), KnowledgeIndexStore.loadSnapshot(w),
        )
        val d = second.diff
        assertEquals(listOf("原始/a.txt"), d.modified)
        assertTrue("除 a 外的文件必须复用（全量重扫候选负例）", second.index!!.stats.reused >= 2)
        val a = second.index.docs.first { it.relPath == "原始/a.txt" }
        assertTrue(a.chunks.any { it.text.contains("新甲段改过") })
        assertTrue(a.chunks.none { it.text.contains("旧甲段") })
    }

    @Test
    fun `删除_快照diff发现_索引里消失`() {
        val files = tmp.newFolder("files")
        val w = workspaceWithFiles(files)
        val first = KnowledgeIndexStore.build(w, files, null, null)
        assertTrue(KnowledgeIndexStore.save(w, first.index!!, first.snapshot))

        val b = File(KnowledgeLayout.rawDir(w), "b.txt")
        assertTrue(b.delete())
        val second = KnowledgeIndexStore.build(
            w, files, KnowledgeIndexStore.load(w), KnowledgeIndexStore.loadSnapshot(w),
        )
        assertEquals(listOf("原始/b.txt"), second.diff.removed)
        assertTrue(second.index!!.docs.none { it.relPath == "原始/b.txt" })
    }

    @Test
    fun `索引可重建_删掉索引目录后从文件重建`() {
        val files = tmp.newFolder("files")
        val w = workspaceWithFiles(files)
        val first = KnowledgeIndexStore.build(w, files, null, null)
        assertTrue(KnowledgeIndexStore.save(w, first.index!!, first.snapshot))

        KnowledgeLayout.indexDir(w).deleteRecursively()
        assertNull("索引删掉 ⇒ 当没有索引", KnowledgeIndexStore.load(w))
        assertNull(KnowledgeIndexStore.loadSnapshot(w))

        val rebuilt = KnowledgeIndexStore.build(w, files, KnowledgeIndexStore.load(w), null)
        assertFalse(rebuilt.cancelled)
        assertTrue(rebuilt.index!!.docs.any { it.relPath == "原始/a.txt" })
        assertTrue(KnowledgeIndexStore.save(w, rebuilt.index, rebuilt.snapshot))
        assertNotNull(KnowledgeIndexStore.load(w))
    }

    @Test
    fun `取消_不保存半截索引`() {
        val files = tmp.newFolder("files")
        val w = workspaceWithFiles(files)
        val control = RefreshControl()
        control.cancel()
        val out = KnowledgeIndexStore.build(w, files, null, null, control)
        assertTrue(out.cancelled)
        assertNull("取消 ⇒ 不产出可保存的索引", out.index)
        assertFalse(KnowledgeLayout.indexFile(w).isFile)
    }

    @Test
    fun `原件零丢失_索引前后sha不变_外部库不搬家`() {
        val files = tmp.newFolder("files")
        val w = workspaceWithFiles(files)
        val a = File(KnowledgeLayout.rawDir(w), "a.txt")
        val before = FaceIndex.sha256(a)
        val outside = File(LibraryPaths.deliverDirUnder(w), "投.txt")
        val outSha = FaceIndex.sha256(outside)

        KnowledgeIndexStore.build(w, files, null, null)

        assertEquals("原件必须逐字节不变", before, FaceIndex.sha256(a))
        assertTrue("外部库只索引不搬：原件还在投递", outside.isFile)
        assertEquals(outSha, FaceIndex.sha256(outside))
        assertFalse(
            "外部库不许复制进 知识库/原始",
            File(KnowledgeLayout.rawDir(w), "投.txt").exists(),
        )
    }
}
