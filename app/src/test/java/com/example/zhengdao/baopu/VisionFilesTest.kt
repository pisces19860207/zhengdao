// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * 第二十四批 C 段（组装半段）· [VisionFiles] 的判据。
 *
 * ## 钉的是什么
 *
 * 「图片能到模型眼前」这条链路的**容易出事的那一半**：把工作区里的文件读成 payload。
 * 出事方式就三种，每条都单独钉：
 * 1. **越界**——`../` 把 `~/.ssh` 之类读进来发出去；
 * 2. **爆内存/爆流量**——把 50 MB 的图读进内存再 base64（base64 还要 ×1.37）；
 * 3. **结构上带路径**——payload 一路走到请求体，任何一处带本机路径都会泄漏（V6）。
 *
 * 第三条是**类型保证**：[VisionRules.ImagePayload] 里没有能装路径的字段，
 * 所以这里只需断言「产出的 dataUrl 里不含工作区路径」即可。
 *
 * 用真实临时目录和真实文件（不是 mock）：这条链路唯一值得测的就是「真文件读出来对不对」。
 */
class VisionFilesTest {

    private lateinit var root: File
    private lateinit var outside: File

    @Before
    fun setUp() {
        root = File.createTempFile("zd-vf-root", "").let { f ->
            f.delete()
            f.mkdirs()
            f
        }
        outside = File.createTempFile("zd-vf-out", "").let { f ->
            f.delete()
            f.mkdirs()
            f
        }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        outside.deleteRecursively()
    }

    private fun img(rel: String, bytes: ByteArray = byteArrayOf(1, 2, 3, 4)): File =
        File(root, rel).apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }

    // ── 正常路径 ─────────────────────────────────────────────────────────────

    @Test
    fun `读得出来_dataUrl 形状对且字节数对`() {
        val payloadBytes = byteArrayOf(9, 8, 7)
        img("附件/a.jpg", payloadBytes)
        val out = VisionFiles.payloadsOf(root, listOf("附件/a.jpg"))
        assertEquals("一张就该回一条", 1, out.size)
        val p = out.first()
        assertTrue("必须是 data URL（V5 只认这个形状）：${p.dataUrl.take(30)}", p.dataUrl.startsWith("data:image/jpeg;base64,"))
        assertEquals("bytes 是原始字节数，不是 base64 长度", payloadBytes.size, p.bytes)
        val b64 = p.dataUrl.substringAfter(";base64,")
        assertTrue("解回来必须与原文逐字节一致", Base64.getDecoder().decode(b64).contentEquals(payloadBytes))
    }

    @Test
    fun `多张保持入参顺序`() {
        img("a.png"); img("b.webp"); img("c.gif")
        val out = VisionFiles.payloadsOf(root, listOf("c.gif", "a.png", "b.webp"))
        assertEquals(
            listOf("image/gif", "image/png", "image/webp"),
            out.map { it.dataUrl.substringAfter("data:").substringBefore(";") },
        )
    }

    @Test
    fun `payload 里不许出现工作区路径_V6 结构性保证`() {
        img("附件/照片.jpg")
        val out = VisionFiles.payloadsOf(root, listOf("附件/照片.jpg"))
        assertTrue("产出里不许含工作区绝对路径", out.none { it.dataUrl.contains(root.absolutePath) })
        assertTrue("也不许含相对路径原文", out.none { it.dataUrl.contains("照片.jpg") })
    }

    // ── 越界 ─────────────────────────────────────────────────────────────────

    @Test
    fun `上跳到工作区外一律不收`() {
        File(outside, "secret.jpg").writeBytes(byteArrayOf(1))
        val out = VisionFiles.payloadsOf(root, listOf("../${outside.name}/secret.jpg"))
        assertTrue("越界必须被挡（canonicalFile 判据，不是字符串前缀）", out.isEmpty())
    }

    @Test
    fun `工作区里绕一圈还是工作区_照收`() {
        img("附件/子目录/深.png")
        val out = VisionFiles.payloadsOf(root, listOf("附件/子目录/../子目录/深.png"))
        assertEquals("归一化后仍在工作区内 ⇒ 该收", 1, out.size)
    }

    // ── 体积闸 ───────────────────────────────────────────────────────────────

    @Test
    fun `超过单图上限的不读也不收`() {
        img("big.jpg", ByteArray(VisionRules.MAX_IMAGE_BYTES + 1) { 0 })
        img("ok.jpg", ByteArray(8) { 0 })
        val out = VisionFiles.payloadsOf(root, listOf("big.jpg", "ok.jpg"))
        assertEquals("超大那张跳过，小的照收", 1, out.size)
        assertEquals(8, out.first().bytes)
    }

    @Test
    fun `空文件不收_省得发一个空图给模型`() {
        img("empty.png", ByteArray(0))
        assertTrue(VisionFiles.payloadsOf(root, listOf("empty.png")).isEmpty())
    }

    // ── 其余边角 ─────────────────────────────────────────────────────────────

    @Test
    fun `认不出扩展名的不收_不猜也不嗅探`() {
        img("notes.txt"); img("noext")
        assertTrue(VisionFiles.payloadsOf(root, listOf("notes.txt", "noext")).isEmpty())
    }

    @Test
    fun `文件不存在或不是文件都跳过_不抛`() {
        File(root, "目录.jpg").mkdirs()
        val out = runCatching { VisionFiles.payloadsOf(root, listOf("没有这张.jpg", "目录.jpg")) }
        assertTrue("跳过而不是抛：${out.exceptionOrNull()}", out.isSuccess)
        assertTrue(out.getOrThrow().isEmpty())
    }

    @Test
    fun `空入参和空白路径都不炸`() {
        assertTrue(VisionFiles.payloadsOf(root, emptyList()).isEmpty())
        assertTrue(VisionFiles.payloadsOf(root, listOf("", "   ")).isEmpty())
    }

    @Test
    fun `mimeOf 的逐项口径`() {
        assertEquals("image/jpeg", VisionFiles.mimeOf("a.jpg"))
        assertEquals("image/jpeg", VisionFiles.mimeOf("A.JPEG"), )
        assertEquals("image/png", VisionFiles.mimeOf("x.PNG"))
        assertEquals("image/webp", VisionFiles.mimeOf("p.webp"))
        assertEquals("image/gif", VisionFiles.mimeOf("g.gif"))
        assertEquals(null, VisionFiles.mimeOf("a.txt"))
        assertEquals(null, VisionFiles.mimeOf("a."))
        assertEquals(null, VisionFiles.mimeOf("a"))
    }
}
