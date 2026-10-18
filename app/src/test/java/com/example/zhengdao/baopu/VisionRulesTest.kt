// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视觉通道的裁图判据（第二十四批 A 段）——V3/V4/V6 的靶心。
 *
 * ## 钉的是什么
 *
 * 附件能选之后图片只落了工作区，模型根本看不见。本批把图送进去，但**送错比不送更糟**：
 * 把图塞给不认它的通道，模型可能把 base64 当正文瞎编，而用户会以为它看过图。
 * 所以这里钉三件事：
 *
 * - **V3**：没视觉能力 ⇒ 一张不发，且在文本里**明写省了几张**（静默丢图是假成功）；
 * - **V4**：单图 ≤2 MB、最多 4 张、合计 ≤6 MB，超了截断并明说；
 * - **V6**：发出去的东西里**不许有本机绝对路径**——`ImagePayload` 这个类型连
 *   装路径的字段都没有（结构性保证），另外 select 只认 `data:image/…;base64,`。
 */
class VisionRulesTest {

    private fun dataUrl(mime: String = "image/jpeg", pad: Int = 8): String =
        "data:$mime;base64," + "A".repeat(pad)

    private fun payload(bytes: Int, mime: String = "image/jpeg"): VisionRules.ImagePayload =
        VisionRules.ImagePayload(dataUrl(mime), bytes)

    private val mb = 1024 * 1024

    // ── 空输入 ───────────────────────────────────────────────────────────────

    @Test
    fun `没有图片时什么都不做_也不留说明`() {
        val r = VisionRules.select(emptyList(), visionSupported = true)
        assertTrue(r.images.isEmpty())
        assertEquals(0, r.omitted)
        assertEquals("没图就不该多说一句", "", r.note)
    }

    // ── V3：没视觉能力 ⇒ 一张不发 + 明说 ─────────────────────────────────────

    @Test
    fun `V3_没有视觉能力时一张都不发`() {
        val r = VisionRules.select(listOf(payload(1000), payload(2000)), visionSupported = false)
        assertTrue("不许把图塞给不认它的通道", r.images.isEmpty())
        assertEquals(2, r.omittedNoVision)
    }

    @Test
    fun `V3_没有视觉能力时必须明说省了几张`() {
        val r = VisionRules.select(listOf(payload(1000), payload(2000), payload(3000)), false)
        assertTrue("必须留说明——静默丢图＝用户以为模型看过", r.note.isNotEmpty())
        assertTrue("说明里要有张数：${r.note}", r.note.contains("3 张"))
        assertTrue("说明里要说清原因：${r.note}", r.note.contains("不能看图"))
    }

    @Test
    fun `V3_有视觉能力时不加多余的说明`() {
        val r = VisionRules.select(listOf(payload(1000)), visionSupported = true)
        assertEquals(1, r.images.size)
        assertEquals("全发了就不需要解释", "", r.note)
    }

    // ── V4：三道上限 ─────────────────────────────────────────────────────────

    @Test
    fun `V4_单图超过2MB被裁`() {
        val r = VisionRules.select(
            listOf(payload(1000), payload(2 * mb + 1), payload(2000)),
            visionSupported = true,
        )
        assertEquals("2 MB 零 1 字节就该裁（剩两张）", 2, r.images.size)
        assertEquals(1, r.omittedTooLarge)
        assertTrue("裁了要明说：${r.note}", r.note.contains("2 MB"))
    }

    @Test
    fun `V4_恰好2MB不裁`() {
        val r = VisionRules.select(listOf(payload(2 * mb)), visionSupported = true)
        assertEquals("等于上限是允许的（只要不超过）", 1, r.images.size)
        assertEquals(0, r.omittedTooLarge)
    }

    @Test
    fun `V4_超过4张截断`() {
        val r = VisionRules.select(
            (1..6).map { payload(1000) },
            visionSupported = true,
        )
        assertEquals("最多 4 张", VisionRules.MAX_IMAGES, r.images.size)
        assertEquals(2, r.omittedOverLimit)
        assertTrue("截断要明说：${r.note}", r.note.contains("4 张"))
    }

    @Test
    fun `V4_合计超过6MB截断`() {
        // 4 张 1.9 MB = 7.6 MB > 6 MB：第 4 张会被总量闸挡住
        val r = VisionRules.select(
            (1..4).map { payload((1.9 * mb).toInt()) },
            visionSupported = true,
        )
        assertEquals("总量闸必须独立于张数闸", 3, r.images.size)
        assertEquals(1, r.omittedOverLimit)
    }

    @Test
    fun `V4_数量与过大同时命中时两条原因都写`() {
        val r = VisionRules.select(
            listOf(payload(3 * mb)) + (1..5).map { payload(1000) },
            visionSupported = true,
        )
        assertTrue(r.note.contains("2 MB"))
        assertTrue(r.note.contains("超出数量上限"))
    }

    // ── V6：路径一步都别想进来 ───────────────────────────────────────────────

    @Test
    fun `V6_本机绝对路径不会当成图片发出去`() {
        val r = VisionRules.select(
            listOf(
                VisionRules.ImagePayload("/data/user/0/com.example.zhengdao/files/a.jpg", 1000),
                VisionRules.ImagePayload("file:///storage/emulated/0/DCIM/b.jpg", 1000),
                VisionRules.ImagePayload("AABBCC", 1000), // 裸 base64 也不收（不统一就没有防线）
            ),
            visionSupported = true,
        )
        assertTrue("路径绝不能进请求体", r.images.isEmpty())
        assertEquals(3, r.omittedInvalid)
        assertTrue("省了要说：${r.note}", r.note.isNotEmpty())
    }

    @Test
    fun `V6_只认data-image-base64形态`() {
        assertTrue(VisionRules.isSendableDataUrl("data:image/png;base64,AAA"))
        assertTrue(VisionRules.isSendableDataUrl("data:image/jpeg;base64,AAA"))
        assertFalse("没有 base64 标记的不是这个形态", VisionRules.isSendableDataUrl("data:image/png,AAA"))
        assertFalse("不是 image 的不行", VisionRules.isSendableDataUrl("data:text/plain;base64,AAA"))
        assertFalse("文件路径不行", VisionRules.isSendableDataUrl("/sdcard/a.jpg"))
        assertFalse(VisionRules.isSendableDataUrl(""))
    }

    @Test
    fun `V6_字节数不可用的也跳过`() {
        val r = VisionRules.select(listOf(VisionRules.ImagePayload(dataUrl(), 0)), true)
        assertTrue(r.images.isEmpty())
        assertEquals(1, r.omittedInvalid)
    }

    // ── data URL 拆分（Anthropic 形状要用）────────────────────────────────────

    @Test
    fun `拆分dataUrl_拿media_type与base64`() {
        val (mime, data) = VisionRules.splitDataUrl("data:image/jpeg;base64,QUJD")!!
        assertEquals("image/jpeg", mime)
        assertEquals("QUJD", data)
    }

    @Test
    fun `拆分dataUrl_坏形态返回null`() {
        assertNull(VisionRules.splitDataUrl("/sdcard/a.jpg"))
        assertNull(VisionRules.splitDataUrl("data:image/png;base64,")) // 逗号后没内容
        assertNull(VisionRules.splitDataUrl("data:;base64,AAA"))       // 没 media type
    }

    @Test
    fun `顺序保持_先来的先发`() {
        val a = "data:image/png;base64,AAA"
        val b = "data:image/png;base64,BBB"
        val r = VisionRules.select(
            listOf(VisionRules.ImagePayload(a, 100), VisionRules.ImagePayload(b, 100)),
            true,
        )
        assertEquals(listOf(a, b), r.images)
    }
}
