// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 判据：派工单-2026-10-21-第一百二十八批 §3 的存储侧：存取往返、索引损坏恢复
// （损坏/缺失/版本不符 ⇒ 当没有索引，重建即可，不阻塞对话）、缓存失效。
package com.example.zhengdao.baopu.kb

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KbIndexStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): KbIndexStore = KbIndexStore(tmp.newFolder("files"))

    private fun sampleIndex(): KbIndex = KbIndex(
        root = "/tmp/ws",
        rootLabel = "资料库/投递",
        updatedAt = 123L,
        docs = listOf(
            KbDoc(
                relPath = "书.txt", size = 10, mtime = 20, kind = "text",
                chunks = listOf(
                    KbChunk("书.txt", 1, 3, "标题", "正文内容", KbChunk.SOURCE_FILE),
                    KbChunk("页.html", 1, 2, "", "网页正文", KbChunk.SOURCE_EXTRACTED),
                ),
            ),
        ),
        stats = KbStats(filesIndexed = 1, chunksTotal = 2),
    )

    @Test
    fun `存取往返_内容一致`() {
        val s = store()
        val idx = sampleIndex()
        assertTrue(s.save(idx))
        val back = s.load()
        assertNotNull(back)
        assertEquals(idx, back)
        // 落点在 App 私有 baopu/kb/index.json
        assertEquals("index.json", s.indexPath().name)
        assertTrue(s.indexPath().absolutePath.contains("baopu"))
    }

    @Test
    fun `没有索引文件_返回null不抛`() {
        assertNull(store().load())
    }

    @Test
    fun `索引文件写坏_当没有索引_返回null`() {
        val s = store()
        assertTrue(s.save(sampleIndex()))
        s.indexPath().writeText("{ 这不是合法JSON ……")
        assertNull("坏 JSON 一律当没有索引（重建即可，不阻塞对话）", s.load())
    }

    @Test
    fun `格式版本不符_当没有索引`() {
        val s = store()
        assertTrue(s.save(sampleIndex()))
        val raw = s.indexPath().readText().replace("kb-index-v1", "kb-index-v0-旧格式")
        s.indexPath().writeText(raw)
        assertNull(s.load())
    }

    @Test
    fun `半截索引_也当没有索引_绝不部分解析`() {
        val s = store()
        assertTrue(s.save(sampleIndex()))
        val full = s.indexPath().readText()
        s.indexPath().writeText(full.take(full.length / 2)) // 拦腰截断
        assertNull(s.load())
    }

    @Test
    fun `原子写_不留tmp残file`() {
        val s = store()
        assertTrue(s.save(sampleIndex()))
        val leftovers = s.indexPath().parentFile.listFiles()?.filter { it.name.endsWith(".tmp") } ?: emptyList()
        assertTrue("原子写不许留 .tmp 残file：$leftovers", leftovers.isEmpty())
    }

    @Test
    fun `缓存_按大小mtime失效_改写后读到新的`() {
        val s = store()
        assertTrue(s.save(sampleIndex()))
        val first = s.loadCached()
        assertNotNull(first)
        assertEquals(2, first!!.docs.single().chunks.size)
        // 未改写 ⇒ 缓存命中（同一实例）
        assertTrue(first === s.loadCached())
        // 改写 ⇒ stamp 变 ⇒ 读到新索引
        val changed = sampleIndex().copy(rootLabel = "别的目录")
        assertTrue(s.save(changed))
        val second = s.loadCached()
        assertEquals("别的目录", second!!.rootLabel)
    }

    @Test
    fun `缓存_损坏文件不缓存坏账`() {
        val s = store()
        assertTrue(s.save(sampleIndex()))
        assertNotNull(s.loadCached())
        s.indexPath().writeText("坏")
        assertNull("损坏后缓存也必须失效", s.loadCached())
    }
}
