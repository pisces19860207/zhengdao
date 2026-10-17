// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * FaceIndex 纯函数单测（刀 2 B 段第 1/2/5 步）。
 *
 * 钉死：sha 稳定（同内容同值）、去重两条规则、**渲染字节稳定且显式排序**
 * （同一批内容两次生成必须字节相同——判据 §5.6）、解析宽容空白严控字段。
 */
class FaceIndexTest {

    private fun item(
        sha: String,
        name: String,
        size: Long = 1L,
        mtime: Long = 1L,
        source: String = LibrarySource.DELIVER,
        exportedAt: Long = 0L,
        isolated: Boolean = true,
        kind: String = LibraryKind.TEXT,
    ) = FaceIndex.Item(sha, name, size, mtime, source, exportedAt, isolated, kind)

    // ── 认脸 ──────────────────────────────────────────────────────────────

    @Test
    fun `sha_同内容同值且文件与字节版一致`() {
        val bytes = "同一份内容".toByteArray()
        val f = File.createTempFile("face", ".txt").apply { writeBytes(bytes); deleteOnExit() }
        assertEquals(FaceIndex.sha256Bytes(bytes), FaceIndex.sha256(f))
        assertEquals(64, FaceIndex.sha256Bytes(bytes).length)
        assertTrue(FaceIndex.sha256Bytes(bytes).all { it in "0123456789abcdef" })
    }

    @Test
    fun `sha_不同内容不同值`() {
        assertTrue(FaceIndex.sha256Bytes("甲".toByteArray()) != FaceIndex.sha256Bytes("乙".toByteArray()))
    }

    // ── 去重 ──────────────────────────────────────────────────────────────

    @Test
    fun `去重_与索引同sha记duplicate`() {
        val existing = listOf(item("sha1", "a.txt", mtime = 5L))
        val r = FaceIndex.dedup(listOf(item("sha1", "a.txt")), existing)
        assertTrue(r.kept.isEmpty())
        assertEquals(1, r.duplicates.size)
        // 保留来源路径与时间的是索引里那条（firstSeen 的 mtime = 5）
        assertEquals(5L, r.duplicates.single().second.mtime)
    }

    @Test
    fun `去重_候选之间同sha只留排序后第一个`() {
        val r = FaceIndex.dedup(
            listOf(item("sha1", "b.txt"), item("sha1", "a.txt")),
            emptyList(),
        )
        assertEquals(listOf("a.txt"), r.kept.map { it.name })
        assertEquals(1, r.duplicates.size)
    }

    @Test
    fun `去重_不同sha全留`() {
        val r = FaceIndex.dedup(listOf(item("sha1", "a.txt"), item("sha2", "b.txt")), emptyList())
        assertEquals(listOf("a.txt", "b.txt"), r.kept.map { it.name })
        assertTrue(r.duplicates.isEmpty())
    }

    // ── 渲染（字节稳定 + 显式排序） ────────────────────────────────────────

    @Test
    fun `渲染_同内容两次字节相同`() {
        val doc = FaceIndex.Doc(items = listOf(item("sha2", "b.txt"), item("sha1", "a.txt")))
        assertEquals(FaceIndex.render(doc), FaceIndex.render(doc))
    }

    @Test
    fun `渲染_按name再sha排序且无时间戳`() {
        val text = FaceIndex.render(
            FaceIndex.Doc(items = listOf(item("sha2", "b.txt"), item("sha1", "a.txt"))),
        )
        val aIdx = text.indexOf("\"a.txt\"")
        val bIdx = text.indexOf("\"b.txt\"")
        assertTrue("a 必须排在 b 前", aIdx in 0 until bIdx)
        // 无生成时间戳（否则两次生成字节不同）
        assertTrue(!text.contains("generated") && !text.contains("时间"))
    }

    @Test
    fun `渲染_汇总字段由items派生`() {
        val doc = FaceIndex.Doc(
            items = listOf(
                item("sha1", "a.txt", exportedAt = 100L),
                item("sha2", "b.txt", exportedAt = 0L),
            ),
        )
        assertEquals(100L, doc.lastExportAt)
        assertEquals(1, doc.unexportedCount)
        val text = FaceIndex.render(doc)
        assertTrue(text.contains("\"last_export_at\": 100"))
        assertTrue(text.contains("\"unexported_count\": 1"))
    }

    @Test
    fun `渲染_来源渲染成中文且布尔小写`() {
        val text = FaceIndex.render(
            FaceIndex.Doc(items = listOf(item("sha1", "a.txt", source = LibrarySource.IMPORT, isolated = false))),
        )
        assertTrue(text.contains("\"source\": \"导入\""))
        assertTrue(text.contains("\"isolated\": false"))
    }

    @Test
    fun `渲染_空索引也是合法JSON`() {
        val text = FaceIndex.render(FaceIndex.Doc())
        assertTrue(text.contains("\"items\": []"))
        val back = FaceIndex.parse(text)
        assertNotNull(back)
        assertTrue(back!!.items.isEmpty())
    }

    // ── 解析 ──────────────────────────────────────────────────────────────

    @Test
    fun `解析_渲染后可 roundtrip`() {
        val doc = FaceIndex.Doc(items = listOf(item("sha1", "a.txt", size = 3, mtime = 4)))
        val back = FaceIndex.parse(FaceIndex.render(doc))
        assertNotNull(back)
        assertEquals(doc.items, back!!.items)
    }

    @Test
    fun `解析_字段缺一条即null`() {
        val broken = "{\n  \"version\": 1,\n  \"items\": [\n" +
            "    {\"sha256\": \"x\", \"name\": \"a.txt\", \"size\": 1, \"mtime\": 1, " +
            "\"source\": \"投递\", \"exported_at\": 0, \"isolated\": true}\n  ]\n}\n" // 缺 kind
        assertNull(FaceIndex.parse(broken))
    }

    @Test
    fun `解析_宽容空白`() {
        val messy = "{ \"version\" : 1 ,\n \"items\" : [ { \"sha256\":\"x\",\"name\":\"a.txt\"," +
            "\"size\":1,\"mtime\":1,\"source\":\"投递\",\"exported_at\":0,\"isolated\":true,\"kind\":\"text\" } ] }"
        assertNotNull(FaceIndex.parse(messy))
    }

    @Test
    fun `转义_引号反斜杠换行`() {
        assertEquals("a\\\"b", FaceIndex.jsonEscape("a\"b"))
        assertEquals("a\\\\b", FaceIndex.jsonEscape("a\\b"))
        assertEquals("a\\nb", FaceIndex.jsonEscape("a\nb"))
    }
}
