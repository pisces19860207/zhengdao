// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 判据：派工单-2026-10-24-第一百八十二批 §0.5（删除靠上轮快照 diff，不靠监听；
// 刷新是发现变化不是全量重扫）。
package com.example.zhengdao.baopu.knowledge

import com.example.zhengdao.baopu.library.LibraryPaths
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KnowledgeSnapshotTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(dir: File, rel: String, text: String): File {
        val f = File(dir, rel)
        f.parentFile?.mkdirs()
        f.writeText(text, Charsets.UTF_8)
        return f
    }

    @Test
    fun `快照_排除索引目录_外部根带外部前缀`() {
        val w = tmp.newFolder("ws")
        val files = tmp.newFolder("files")
        KnowledgeLayout.ensureDirs(w)
        write(KnowledgeLayout.rawDir(w), "a.txt", "a")
        write(KnowledgeLayout.indexDir(w), "知识库索引.json", "{}")
        val deliver = LibraryPaths.deliverDirUnder(w)
        deliver.mkdirs()
        write(deliver, "投.txt", "t")

        val snap = KnowledgeSnapshot.capture(w, files)
        val paths = snap.entries.map { it.relPath }
        assertTrue(paths.contains("原始/a.txt"))
        assertTrue(paths.contains("外部/投递/投.txt"))
        assertTrue("索引文件不许把自己索引进去", paths.none { it.startsWith("索引/") })
    }

    @Test
    fun `diff_新增改动删除`() {
        val prev = KnowledgeSnapshot.Snapshot(
            listOf(
                KnowledgeSnapshot.Entry("a.txt", 10, 1),
                KnowledgeSnapshot.Entry("b.txt", 20, 2),
                KnowledgeSnapshot.Entry("c.txt", 30, 3),
            ),
        )
        val cur = KnowledgeSnapshot.Snapshot(
            listOf(
                KnowledgeSnapshot.Entry("a.txt", 10, 1),
                KnowledgeSnapshot.Entry("b.txt", 21, 2), // size 变
                KnowledgeSnapshot.Entry("d.txt", 40, 4),
            ),
        )
        val d = KnowledgeSnapshot.diff(prev, cur)
        assertEquals(listOf("d.txt"), d.added)
        assertEquals(listOf("b.txt"), d.modified)
        assertEquals(listOf("c.txt"), d.removed)
    }

    @Test
    fun `diff_没有上轮快照_全算新增`() {
        val cur = KnowledgeSnapshot.Snapshot(listOf(KnowledgeSnapshot.Entry("a.txt", 1, 1)))
        assertEquals(listOf("a.txt"), KnowledgeSnapshot.diff(null, cur).added)
    }

    @Test
    fun `编解码往返_坏JSON或版本不符一律null`() {
        val s = KnowledgeSnapshot.Snapshot(
            listOf(KnowledgeSnapshot.Entry("原始/a.txt", 12, 34)),
            truncated = false,
        )
        assertEquals(s, KnowledgeSnapshot.decode(KnowledgeSnapshot.encode(s)))
        assertNull(KnowledgeSnapshot.decode("{ 坏 JSON"))
        val changed = KnowledgeSnapshot.encode(s).replace(KnowledgeSnapshot.FORMAT, "kb-snapshot-v0")
        assertNull(KnowledgeSnapshot.decode(changed))
    }

    @Test
    fun `空快照也能往返`() {
        val s = KnowledgeSnapshot.Snapshot()
        assertNotNull(KnowledgeSnapshot.decode(KnowledgeSnapshot.encode(s)))
        assertEquals(0, KnowledgeSnapshot.decode(KnowledgeSnapshot.encode(s))!!.entries.size)
    }
}
