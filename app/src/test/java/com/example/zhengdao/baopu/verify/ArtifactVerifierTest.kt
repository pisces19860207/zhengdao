// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 收尾闸验收器的单测：钉死「哪条算失败、哪条算过」，防止以后为了少报红把判据改松。
package com.example.zhengdao.baopu.verify

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ArtifactVerifierTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("zd-verify").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun write(rel: String, text: String): File {
        val f = File(root, rel)
        f.parentFile?.mkdirs()
        f.writeText(text, StandardCharsets.UTF_8)
        return f
    }

    private fun writeBytes(rel: String, bytes: ByteArray): File {
        val f = File(root, rel)
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
        return f
    }

    @Test
    fun `正常文本文件_全过`() {
        write("整理/第一章.txt", "第一章\n正文")
        assertTrue(ArtifactVerifier.check(root, listOf("整理/第一章.txt")).isEmpty())
    }

    @Test
    fun `文件不存在_报 missing`() {
        val found = ArtifactVerifier.check(root, listOf("整理/没写.txt"))
        assertEquals(1, found.size)
        assertEquals(ArtifactVerifier.CHECK_MISSING, found[0].check)
    }

    @Test
    fun `空文件_报 empty`() {
        writeBytes("空.txt", ByteArray(0))
        val found = ArtifactVerifier.check(root, listOf("空.txt"))
        assertEquals(ArtifactVerifier.CHECK_EMPTY, found[0].check)
    }

    @Test
    fun `目录_报 not_a_file`() {
        File(root, "资料库/整理").mkdirs()
        val found = ArtifactVerifier.check(root, listOf("资料库/整理"))
        assertEquals(ArtifactVerifier.CHECK_NOT_FILE, found[0].check)
    }

    @Test
    fun `GBK 字节的 txt_报 bad_encoding`() {
        // 「中文」的 GBK 编码：D6 D0 CE C4（不是合法 UTF-8 序列）
        writeBytes("老书.txt", byteArrayOf(0xD6.toByte(), 0xD0.toByte(), 0xCE.toByte(), 0xC4.toByte()))
        val found = ArtifactVerifier.check(root, listOf("老书.txt"))
        assertEquals(1, found.size)
        assertEquals(ArtifactVerifier.CHECK_BAD_ENCODING, found[0].check)
    }

    @Test
    fun `带 BOM 的 UTF-8_算过`() {
        writeBytes("bom.txt", byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 'a'.code.toByte()))
        assertTrue(ArtifactVerifier.check(root, listOf("bom.txt")).isEmpty())
    }

    @Test
    fun `坏 JSON_报 bad_json`() {
        write("索引.json", "{\"items\": [1, 2")
        val found = ArtifactVerifier.check(root, listOf("索引.json"))
        assertEquals(1, found.size)
        assertEquals(ArtifactVerifier.CHECK_BAD_JSON, found[0].check)
    }

    @Test
    fun `好 JSON 数组_算过`() {
        write("list.json", "[{\"name\":\"a\"}]")
        assertTrue(ArtifactVerifier.check(root, listOf("list.json")).isEmpty())
    }

    @Test
    fun `二进制文件_不做编码检查`() {
        writeBytes("封面.png", byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x00, 0xFF.toByte()))
        assertTrue(ArtifactVerifier.check(root, listOf("封面.png")).isEmpty())
    }

    @Test
    fun `越界路径_报 escape 且不读盘`() {
        val found = ArtifactVerifier.check(root, listOf("../外面的书.txt", "/etc/passwd", "C:/x.txt"))
        assertEquals(3, found.size)
        assertTrue(found.all { it.check == ArtifactVerifier.CHECK_ESCAPE })
    }

    @Test
    fun `重复路径只验一次`() {
        write("a.txt", "x")
        val found = ArtifactVerifier.check(root, listOf("a.txt", "./a.txt", "a.txt"))
        assertTrue(found.isEmpty())
    }

    @Test
    fun `工作区内的绝对路径_按相对路径验_不再误报越界`() {
        // 确认制批准后 GuardPipeline 会把参数写回绝对路径 ⇒ 引擎记账拿到的是绝对路径。
        // 真机实测过的那个坑：写 inbox/rep.md 再搬走，收尾闸报「路径越界」而不是「文件不存在」。
        write("inbox/rep.md", "x")
        val abs = java.io.File(root, "inbox/rep.md").absolutePath
        assertTrue(ArtifactVerifier.check(root, listOf(abs)).isEmpty())

        val goneAbs = java.io.File(root, "inbox/搬走了.md").absolutePath
        val found = ArtifactVerifier.check(root, listOf(goneAbs))
        assertEquals(1, found.size)
        assertEquals(ArtifactVerifier.CHECK_MISSING, found[0].check)
    }

    @Test
    fun `工作区外的绝对路径_仍报越界`() {
        val found = ArtifactVerifier.check(root, listOf("/etc/passwd"))
        assertEquals(1, found.size)
        assertEquals(ArtifactVerifier.CHECK_ESCAPE, found[0].check)
        assertNull(ArtifactVerifier.resolve(root, "/etc/passwd"))
    }

    @Test
    fun `normalize_合法与非法`() {
        assertEquals("整理/书.txt", ArtifactVerifier.normalize("整理/书.txt"))
        assertEquals("整理/书.txt", ArtifactVerifier.normalize(".\\整理\\书.txt"))
        assertNull(ArtifactVerifier.normalize(""))
        assertNull(ArtifactVerifier.normalize("a/../b.txt"))
        assertNull(ArtifactVerifier.normalize("D:/book.txt"))
        assertNull(ArtifactVerifier.normalize("https://example.com/a.txt"))
    }

    @Test
    fun `render_列出每条并对模型说明修完直接回答`() {
        val f = listOf(
            Finding("整理/a.txt", ArtifactVerifier.CHECK_MISSING, "本轮写过，收尾时找不到"),
            Finding("索引.json", ArtifactVerifier.CHECK_BAD_JSON, "JSON 解析失败：x"),
        )
        val text = ArtifactVerifier.render(f)
        assertTrue(text.contains("未通过 2 项"))
        assertTrue(text.contains("整理/a.txt"))
        assertTrue(text.contains("文件不存在"))
        assertTrue(text.contains("修完直接回答"))
    }

    @Test
    fun `summarize_按检查项聚合`() {
        val f = listOf(
            Finding("a.txt", ArtifactVerifier.CHECK_MISSING, ""),
            Finding("b.txt", ArtifactVerifier.CHECK_MISSING, ""),
            Finding("c.json", ArtifactVerifier.CHECK_BAD_JSON, ""),
        )
        val s = ArtifactVerifier.summarize(f)
        assertTrue(s.contains("3 项"))
        assertTrue(s.contains("文件不存在 2"))
        assertTrue(s.contains("JSON 解析不了 1"))
        assertEquals("自检通过", ArtifactVerifier.summarize(emptyList()))
    }

    @Test
    fun `空清单_直接算过`() {
        assertTrue(ArtifactVerifier.check(root, emptyList()).isEmpty())
    }

    // ── VerifyTargets：只认写类工具 ──

    @Test
    fun `写类工具取到路径_读类工具返回空`() {
        val args = JSONObject("{\"path\":\"整理/x.txt\"}")
        assertEquals(listOf("整理/x.txt"), VerifyTargets.pathsOf("write_file", args))
        assertEquals(listOf("整理/x.txt"), VerifyTargets.pathsOf("edit_file", args))
        assertEquals(listOf("整理/x.txt"), VerifyTargets.pathsOf("convert_encoding", args))
        assertTrue(VerifyTargets.pathsOf("read_file", args).isEmpty())
        assertTrue(VerifyTargets.pathsOf("list_dir", args).isEmpty())
    }

    @Test
    fun `搬文件取 to 不取 from`() {
        val args = JSONObject("{\"from\":\"投递/a.txt\",\"to\":\"整理/a.txt\"}")
        assertEquals(listOf("整理/a.txt"), VerifyTargets.pathsOf("move_path", args))
    }

    @Test
    fun `参数缺键或空串_不算产物`() {
        assertTrue(VerifyTargets.pathsOf("write_file", JSONObject("{}")).isEmpty())
        assertTrue(VerifyTargets.pathsOf("write_file", JSONObject("{\"path\":\"  \"}")).isEmpty())
        assertTrue(VerifyTargets.pathsOf("write_file", null).isEmpty())
    }
}
