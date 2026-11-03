// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
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
    fun `真越界_凭据目录与不可解析形态_报escape`() {
        // 404 起「越界」与写入侧 287 同一把尺：写入侧本来就拒的（系统/凭据目录）与
        // 不可解析形态（空串/scheme）才算 escape。凭据段在哪个测试宿主上都命中。
        val found = ArtifactVerifier.check(root, listOf("/sdcard/.ssh/id_rsa", "https://example.com/a.txt", "  "))
        assertEquals(3, found.size)
        assertTrue(found.all { it.check == ArtifactVerifier.CHECK_ESCAPE })
    }

    @Test
    fun `判据④_文件全部就位的盘面_不判越界`() {
        // 401 验机 M7 的误判现场：产物写在工作区外（287 明文允许的绝对路径落盘），
        // 旧收尾闸把它们全判「路径越界」——文件明明都在。新口径按真实落点机械验收。
        val outside = Files.createTempDirectory("zd-verify-outside").toFile()
        try {
            val f1 = File(outside, "汇总.md")
            val f2 = File(outside, "明细/m7a.txt")
            f2.parentFile.mkdirs()
            f1.writeText("# 汇总\n", StandardCharsets.UTF_8)
            f2.writeText("明细a", StandardCharsets.UTF_8)
            val results = ArtifactVerifier.check(
                root,
                listOf(f1.absolutePath, f2.absolutePath),
            )
            assertTrue("盘面全就位 ⇒ 0 项未过：$results", results.isEmpty())
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun `判据④_相对路径写出工作区_按真实落点验_在就验_不在报missing`() {
        // 287：相对路径含 .. 写出工作区也是写入侧放行的形态——验收按实报，不扣「越界」帽子。
        val escaped = File(root.parentFile, "zd-verify-escape/外面的书.txt")
        escaped.parentFile.mkdirs()
        escaped.writeText("在的", StandardCharsets.UTF_8)
        try {
            assertTrue(ArtifactVerifier.check(root, listOf("../zd-verify-escape/外面的书.txt")).isEmpty())
            val gone = ArtifactVerifier.check(root, listOf("../zd-verify-escape/没写.txt"))
            assertEquals(1, gone.size)
            assertEquals(ArtifactVerifier.CHECK_MISSING, gone[0].check)
        } finally {
            escaped.parentFile.deleteRecursively()
        }
    }

    @Test
    fun `判据④_工作区外真缺了_报missing不是escape`() {
        val outside = Files.createTempDirectory("zd-verify-gone").toFile()
        try {
            val gone = File(outside, "没了.md").absolutePath
            val found = ArtifactVerifier.check(root, listOf(gone))
            assertEquals(1, found.size)
            assertEquals(ArtifactVerifier.CHECK_MISSING, found[0].check)
        } finally {
            outside.deleteRecursively()
        }
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
    fun `resolve_仍只收工作区内的相对化_旧契约不变`() {
        // 404 起 resolve 只服务展示与旧调用方；「工作区外 = 越界」的判定改由 resolveTarget
        // 按 287 口径承担。这条钉 resolve 自己的旧契约不被顺手改掉。
        assertNull(ArtifactVerifier.resolve(root, "/sdcard/.ssh/id_rsa"))
        assertNull(ArtifactVerifier.resolve(root, "  "))
    }

    @Test
    fun `resolveTarget_工作区外合法落点给真实落点_凭据与scheme拒`() {
        val outside = Files.createTempDirectory("zd-verify-target").toFile()
        try {
            val f = File(outside, "a.md")
            f.writeText("x", StandardCharsets.UTF_8)
            assertEquals(f.absoluteFile, ArtifactVerifier.resolveTarget(root, f.absolutePath)?.absoluteFile)
            assertEquals(
                "相对路径按根解",
                File(root, "产物/周报.md").absoluteFile,
                ArtifactVerifier.resolveTarget(root, "产物/周报.md")!!.absoluteFile,
            )
            assertNull("凭据目录 = 真越界", ArtifactVerifier.resolveTarget(root, "/sdcard/.ssh/id_rsa"))
            assertNull("scheme = 不可解析", ArtifactVerifier.resolveTarget(root, "content://x/y"))
            assertNull("空串 = 不可解析", ArtifactVerifier.resolveTarget(root, " "))
        } finally {
            outside.deleteRecursively()
        }
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
