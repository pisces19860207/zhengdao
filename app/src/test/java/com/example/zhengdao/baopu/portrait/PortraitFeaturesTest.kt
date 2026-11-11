// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百九十七批 · 判据①④的夹具测试：三张逐像素定义的立绘，主色/宽高比/半身全身
// 逐项断言（单 597 判据①）；两图差异清单逐条断言（判据④：只列差异）。
// 纯判据层直测：像素数组进、读数出，零 IO——文件→解码→判据的整链在 PortraitAppearanceToolTest。
// 期望 hex 与夹具源色一致：主色统一色必落同一 4bit 桶，桶内均值＝源色（ImageOps.palette 口径）。
package com.example.zhengdao.baopu.portrait

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortraitFeaturesTest {

    // ── 判据①：三张夹具，主色/宽高比/半身全身 ─────────────────────────────

    @Test
    fun `夹具一_半身像_取色取景宽高比全对`() {
        val r = PortraitFeatures.read(PortraitFixtures.HALF)!!
        assertEquals("256×384 应判 2:3", "2:3", r.aspect)
        assertEquals("头身比 0.354 ⇒ 半身", Extent.HALF, r.extent)
        assertEquals("发区主色=紫", "#963CB4", r.hairColor!!.hex)
        assertEquals("瞳区主色=绿", "#1EB45A", r.eyeColor!!.hex)
        assertEquals("服饰主色=蓝", "#283CA0", r.clothingColor!!.hex)
    }

    @Test
    fun `夹具二_全身像_取色取景宽高比全对`() {
        val r = PortraitFeatures.read(PortraitFixtures.FULL)!!
        assertEquals("256×512 应判 1:2", "1:2", r.aspect)
        assertEquals("头身比 0.113 ⇒ 全身", Extent.FULL, r.extent)
        assertEquals("发区主色=黑", "#141414", r.hairColor!!.hex)
        assertEquals("瞳区主色=红", "#C82828", r.eyeColor!!.hex)
        assertEquals("服饰主色=绿", "#28A046", r.clothingColor!!.hex)
    }

    @Test
    fun `夹具三_不透明底远景_走底边主色一路_判据照对`() {
        val r = PortraitFeatures.read(PortraitFixtures.SCENE)!!
        assertEquals("384×256 应判 3:2", "3:2", r.aspect)
        assertEquals("远景人物头身比 0.137 ⇒ 全身", Extent.FULL, r.extent)
        assertEquals("发区主色=棕", "#6E4628", r.hairColor!!.hex)
        assertEquals("瞳区主色=红（虹膜是该带最大桶）", "#C82828", r.eyeColor!!.hex)
        assertEquals("服饰主色=蓝", "#283CA0", r.clothingColor!!.hex)
    }

    @Test
    fun `全透明图_如实说判不出`() {
        val blank = PortraitPixels(64, 64, IntArray(64 * 64))
        assertNull("纯背景 ⇒ null，不猜", PortraitFeatures.read(blank))
    }

    @Test
    fun `同一张图两次跑_读数逐字节相同_纯函数`() {
        val a = PortraitFeatures.read(PortraitFixtures.HALF)!!
        val b = PortraitFeatures.read(PortraitFixtures.HALF)!!
        assertEquals(a, b)
    }

    // ── 判据④：多图一致性——只列差异 ─────────────────────────────────────

    @Test
    fun `半身vs全身_四条差异_顺序固定`() {
        val a = PortraitFeatures.read(PortraitFixtures.HALF)!!
        val b = PortraitFeatures.read(PortraitFixtures.FULL)!!
        val diffs = PortraitFeatures.compare(a, b)
        assertEquals(
            "发色/瞳色/取景/宽高比 四条，一条不少",
            listOf("发色", "瞳色", "取景", "宽高比"),
            diffs.map { it.kind },
        )
        assertTrue("发色差异带两边的色", diffs[0].detail.contains("#963CB4"))
        assertTrue("取景差异带两档", diffs[2].detail.contains("半身") && diffs[2].detail.contains("全身"))
    }

    @Test
    fun `同一张图对比自己_零差异_不编造`() {
        val a = PortraitFeatures.read(PortraitFixtures.HALF)!!
        assertTrue(PortraitFeatures.compare(a, a).isEmpty())
    }

    // ── 宽高比小工具 ────────────────────────────────────────────────────────

    @Test
    fun `宽高比约分与异常尺寸`() {
        assertEquals("2:3", PortraitFeatures.aspectOf(1024, 1536))
        assertEquals("1:1", PortraitFeatures.aspectOf(512, 512))
        assertEquals("16:9", PortraitFeatures.aspectOf(1920, 1080))
        assertEquals("判不出", PortraitFeatures.aspectOf(0, 100))
    }
}
