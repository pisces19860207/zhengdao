// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 2026-10-18 真机事故的回归门禁：用户连着发了 3.6 MB / 2.7 MB 两张手机照片，
// 抱朴两次都回「这张我看不到」。查下来**没有人关过任何开关**，是这条链路自己丢的：
//
//   VisionFiles.payloadsOf 遇到 > 2 MB 的图 → 直接 continue（跳过）
//   → VisionRules.select 收到空列表 → 连「已省略 N 张」的说明都不生成
//   → 模型只看到「用户附了 1 张图片」→ 老实回「看不见」→ 用户以为视觉被关了
//
// 本套件钉四件事：
// 1. 超限的图**先缩再发**（不再丢）；
// 2. 缩不成/没实现 ⇒ **必须逐张记原因**，并且原因会拼进本轮输入（静默等于骗人）；
// 3. 说明文本本身：0 张空串、超过三张收口；
// 4. 源码级：引擎不许再走 `payloadsOf`（那是丢掉原因的老口径），装配层必须注入缩图实现。
package com.example.zhengdao.baopu

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class VisionShrinkTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = File.createTempFile("zd-vs-root", "").let { f ->
            f.delete()
            f.mkdirs()
            f
        }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    /** 造一张指定字节数的"jpg"（内容不重要，本套件不真解码）。 */
    private fun bigJpg(rel: String, bytes: Int): File = File(root, rel).apply {
        parentFile?.mkdirs()
        writeBytes(ByteArray(bytes) { 7 })
    }

    // ── VisionFit：纯算术 ────────────────────────────────────────────────────

    @Test
    fun `F1_不超过上限时不缩`() {
        assertNull(VisionFit.planFor(VisionRules.MAX_IMAGE_BYTES.toLong()))
        assertNull(VisionFit.planFor(1024L))
    }

    @Test
    fun `F2_超过上限给出计划_上限就是VisionRules那张`() {
        val plan = VisionFit.planFor(VisionRules.MAX_IMAGE_BYTES.toLong() + 1)
        assertTrue("超过 1 字节都必须给计划（不许四舍五入成「差不多就行」）", plan != null)
        assertEquals(VisionRules.MAX_IMAGE_BYTES, plan!!.capBytes)
        assertEquals(VisionFit.MAX_EDGE, plan.maxEdge)
        assertTrue("质量阶梯要从高往低", plan.qualities.first() > plan.qualities.last())
    }

    @Test
    fun `F3_降采样倍数只取二的幂且不把图缩过头`() {
        assertEquals(1, VisionFit.sampleSizeFor(1000, 800))
        assertEquals(1, VisionFit.sampleSizeFor(1568, 1000))
        assertEquals(1, VisionFit.sampleSizeFor(3135, 1000))   // 2 倍才 < 1568 ⇒ 仍取 1
        assertEquals(2, VisionFit.sampleSizeFor(3200, 1000))
        assertEquals(4, VisionFit.sampleSizeFor(6400, 1000))
        assertEquals(1, VisionFit.sampleSizeFor(0, 0))          // 坏尺寸不崩
    }

    @Test
    fun `F4_缩后尺寸按长边等比且不小于1`() {
        assertEquals(1568 to 784, VisionFit.scaledSize(3200, 1600))
        assertEquals(784 to 1568, VisionFit.scaledSize(1600, 3200))
        assertEquals(800 to 600, VisionFit.scaledSize(800, 600))   // 本来够小，原样
        assertEquals(1568 to 1, VisionFit.scaledSize(10000, 1))    // 极扁的图也不许出现 0
    }

    // ── VisionFiles.load：超限先缩 ───────────────────────────────────────────

    @Test
    fun `S1_三点六MB的图先缩再发_不再被丢弃`() {
        bigJpg("附件/big.jpg", 3_600_000)
        var called = 0
        val loaded = VisionFiles.load(
            root = root,
            relPaths = listOf("附件/big.jpg"),
            shrink = { called++; VisionFiles.ShrinkOutcome.Ok(ByteArray(500_000)) },
        )
        assertEquals("必须真的去缩（不许跳过）", 1, called)
        assertEquals(1, loaded.payloads.size)
        assertEquals(0, loaded.skipped.size)
        assertEquals(listOf("附件/big.jpg"), loaded.shrunk)
        assertTrue("dataUrl 要能发", VisionRules.isSendableDataUrl(loaded.payloads[0].dataUrl))
        assertTrue("字节数按缩后算", loaded.payloads[0].bytes <= VisionRules.MAX_IMAGE_BYTES)
    }

    @Test
    fun `S2_缩不成必须逐张记原因_并且原因进得了说明`() {
        bigJpg("附件/huge.jpg", 9_000_000)
        val loaded = VisionFiles.load(
            root = root,
            relPaths = listOf("附件/huge.jpg"),
            shrink = { VisionFiles.ShrinkOutcome.Failed("质量试到底仍超 2 MB") },
        )
        assertEquals(0, loaded.payloads.size)
        assertEquals(1, loaded.skipped.size)
        assertTrue(loaded.skipped[0].reason.contains("2 MB"))
        val note = loaded.note()
        assertTrue("说明里要有文件名", note.contains("huge.jpg"))
        assertTrue("说明里要有原因", note.contains("质量试到底"))
        assertTrue("说明要告诉模型别猜", note.contains("别猜"))
    }

    @Test
    fun `S3_没注入缩图实现也不许静默`() {
        bigJpg("附件/noshink.jpg", 3_000_000)
        val loaded = VisionFiles.load(root = root, relPaths = listOf("附件/noshink.jpg"), shrink = null)
        assertEquals(0, loaded.payloads.size)
        assertEquals(1, loaded.skipped.size)
        assertTrue(loaded.skipped[0].reason.contains("没有缩图实现"))
        assertTrue(loaded.note().isNotEmpty())
    }

    @Test
    fun `S4_缩完还是超限照样记原因`() {
        bigJpg("附件/still.jpg", 3_000_000)
        val loaded = VisionFiles.load(
            root = root,
            relPaths = listOf("附件/still.jpg"),
            shrink = { VisionFiles.ShrinkOutcome.Ok(ByteArray(VisionRules.MAX_IMAGE_BYTES + 1)) },
        )
        assertEquals(0, loaded.payloads.size)
        assertEquals(1, loaded.skipped.size)
        assertTrue(loaded.skipped[0].reason.contains("还是超过"))
    }

    @Test
    fun `S5_正常大小的图不走缩图`() {
        bigJpg("附件/small.jpg", 100_000)
        var called = 0
        val loaded = VisionFiles.load(
            root = root,
            relPaths = listOf("附件/small.jpg"),
            shrink = { called++; VisionFiles.ShrinkOutcome.Ok(ByteArray(1)) },
        )
        assertEquals("没必要缩的一律不缩（重编码是信息损失）", 0, called)
        assertEquals(1, loaded.payloads.size)
    }

    @Test
    fun `S6_越界与坏文件都是记原因而不是抛`() {
        val outside = File.createTempFile("zd-vs-out", ".jpg").apply { writeBytes(ByteArray(10)) }
        try {
            val loaded = VisionFiles.load(
                root = root,
                relPaths = listOf("../${outside.name}", "", "附件/none.jpg"),
            )
            assertEquals(0, loaded.payloads.size)
            assertEquals(3, loaded.skipped.size)
            assertTrue(loaded.note().isNotEmpty())
        } finally {
            outside.delete()
        }
    }

    // ── 说明文本口径 ────────────────────────────────────────────────────────

    @Test
    fun `N1_零张跳过时说明是空串`() {
        assertEquals("", VisionFiles.Loaded().note())
    }

    @Test
    fun `N2_超过三张只点名三张_其余收口`() {
        val loaded = VisionFiles.Loaded(
            skipped = (1..5).map { VisionFiles.Skipped("附件/$it.jpg", "解不开") },
        )
        val note = loaded.note()
        assertTrue(note.contains("有 5 张"))
        assertTrue(note.contains("另外 2 张同理"))
        assertFalse("第四张的名字不该出现", note.contains("附件/4.jpg"))
    }

    // ── 源码级：不许退回老口径 ──────────────────────────────────────────────

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun code(rel: String): String = repoFile(rel).readText()
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    @Test
    fun `G1_引擎必须走load_不许再用丢掉原因的payloadsOf`() {
        val engine = code("src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt")
        assertFalse(
            "RunEngine 里还留着 VisionFiles.payloadsOf（它会丢掉跳过原因）",
            engine.contains("VisionFiles.payloadsOf("),
        )
        assertTrue("引擎要用 VisionFiles.load", engine.contains("VisionFiles.load("))
        assertTrue("引擎要把跳过原因带进本轮输入", engine.contains("turnNotes"))
    }

    @Test
    fun `G2_装配层必须注入缩图实现`() {
        val runtime = code("src/main/java/com/example/zhengdao/baopu/BaopuRuntime.kt")
        assertTrue("BaopuRuntime 没注入 shrinkImage ⇒ 真机上图片照旧全丢", runtime.contains("shrinkImage ="))
        assertTrue("注入的必须是 ImageShrink", runtime.contains("ImageShrink.toJpeg("))
    }

    @Test
    fun `G3_组装层要把额外说明拼进本轮输入`() {
        val assembler = code("src/main/java/com/example/zhengdao/baopu/ContextAssembler.kt")
        assertTrue("ContextAssembler 没有 extraNotes 参数", assembler.contains("extraNotes"))
        assertTrue("额外说明没被拼进 currentText", assembler.contains("notes.joinToString"))
    }
}
