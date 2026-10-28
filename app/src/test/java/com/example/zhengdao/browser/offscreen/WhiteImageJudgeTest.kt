// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.browser.offscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 263 批：白图判定（[WhiteImageJudge]）的纯函数门禁。
 *
 * 白图判定是"这台机行不行"的最后一道闸，判错方向有两种，各会骗出假结论：
 * - 把真页面杀成白图（阈值太松）→ 假的"这台机不行"；
 * - 把白图放行成真页面（阈值太严）→ 假的"兜底可用"。
 *
 * 前半段真判据：喂构造的像素数组，断言三分类与主色。采样上限内的用例 stride=1
 * （逐像素，读数精确）；另给一条超限用例证明大图走跳采样不炸、样本数封顶。
 * 后半段钉牙口：判据三常量逐个 assertEqual 钉死——谁把阈值/亮度/采样上限改了，
 * 本套必红（防"判据写错但永远绿"）。
 */
class WhiteImageJudgeTest {

    private fun solid(argb: Int, w: Int = 16, h: Int = 16) = IntArray(w * h) { argb }

    /** 白底开头贴一块连续实心红：真页面最简形态（连续块，不是散点）。 */
    private fun whiteWithRedBlock(redPx: Int, w: Int, h: Int): IntArray {
        val px = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        repeat(redPx) { px[it] = 0xFFFF0000.toInt() }
        return px
    }

    @Test
    fun `整幅白是白图`() {
        val v = WhiteImageJudge.judge(solid(0xFFFFFFFF.toInt()), 16, 16)
        assertEquals(WhiteImageJudge.Classification.UNIFORM_WHITE, v.classification)
        assertEquals("#FFFFFFFF", v.colorHex())
        assertEquals(1.0, v.dominantRatio, 1e-9)
        assertEquals(256, v.sampled)
    }

    @Test
    fun `整幅透明黑是未渲染不是白图`() {
        val v = WhiteImageJudge.judge(solid(0x00000000), 16, 16)
        assertEquals(WhiteImageJudge.Classification.UNIFORM_BLANK, v.classification)
        assertEquals("#FF000000", v.colorHex())
    }

    @Test
    fun `整幅纯红同色也是未渲染不是白图`() {
        val v = WhiteImageJudge.judge(solid(0xFFFF0000.toInt()), 16, 16)
        assertEquals(WhiteImageJudge.Classification.UNIFORM_BLANK, v.classification)
    }

    @Test
    fun `真页面多色判RICH`() {
        val px = whiteWithRedBlock(redPx = 60, w = 50, h = 20)
        for (x in 0 until 5) px[900 + x] = 0xFF0000FF.toInt()
        val v = WhiteImageJudge.judge(px, 50, 20)
        assertEquals(WhiteImageJudge.Classification.RICH, v.classification)
        assertTrue(v.isRendered)
        assertTrue(v.dominantRatio < WhiteImageJudge.UNIFORM_RATIO)
    }

    @Test
    fun `白占比压线以上判白图`() {
        // 1000 像素里 4 个红（0.4%）→ 白占比 99.6% ≥ 0.995 ⇒ 白图
        val v = WhiteImageJudge.judge(whiteWithRedBlock(redPx = 4, w = 1000, h = 1), 1000, 1)
        assertEquals(WhiteImageJudge.Classification.UNIFORM_WHITE, v.classification)
    }

    @Test
    fun `红块过线判RICH`() {
        // 1000 像素里 10 个红（1%）→ 白占比 99.0% < 0.995 ⇒ RICH
        val v = WhiteImageJudge.judge(whiteWithRedBlock(redPx = 10, w = 1000, h = 1), 1000, 1)
        assertEquals(WhiteImageJudge.Classification.RICH, v.classification)
    }

    @Test
    fun `零尺寸或空像素给EMPTY`() {
        assertEquals(
            WhiteImageJudge.Classification.EMPTY,
            WhiteImageJudge.judge(IntArray(0), 0, 0).classification,
        )
        assertEquals(
            WhiteImageJudge.Classification.EMPTY,
            WhiteImageJudge.judge(IntArray(0), 8, 8).classification,
        )
    }

    @Test
    fun `超大图走跳采样且样本封顶`() {
        // 2,000,000 像素 > 32,768：跳采样后红块 0.5% 占比仍在，分类不变，样本数封顶
        val v = WhiteImageJudge.judge(
            whiteWithRedBlock(redPx = 10_000, w = 2000, h = 1000), 2000, 1000,
        )
        assertTrue(v.sampled in 1..WhiteImageJudge.MAX_SAMPLES)
        assertEquals(WhiteImageJudge.Classification.RICH, v.classification)
    }

    @Test
    fun `判据常量钉死_阈值亮度采样上限不得漂移`() {
        assertEquals(0.995, WhiteImageJudge.UNIFORM_RATIO, 0.0)
        assertEquals(245, WhiteImageJudge.WHITE_LUMINANCE)
        assertEquals(32_768, WhiteImageJudge.MAX_SAMPLES)
    }
}
