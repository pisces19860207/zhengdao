// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百四十三批 · 纯函数边界测试（第 2 组）。
//
// 依据实现：`TokenEstimator.estimate` 的口径是「非 ASCII 按字符数 ×1.0、ASCII 按
// 字节 ÷4，取大者，再 ×1.15 后向上取整」。本文件只钉**边界与取整行为**，不改实现：
// 空串、ASCII 3/4 字符跨档、CJK、混合取大、emoji（UTF-16 代理对）、非 ASCII 拉丁字母、
// 超长文本、安全系数常量。
//
// 注意一条按当前实现钉住的差异：文档说「一个字符一个 token」，但 Kotlin `Char`
// 是 UTF-16 码元，BMP 之外的 emoji 会算 **2 个非 ASCII 字符**（详见交付报告）。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Test

class TokenEstimatorTest {

    @Test
    fun `estimate_空串_返回0`() {
        assertEquals(0, TokenEstimator.estimate(""))
    }

    @Test
    fun `estimate_单个ASCII_向上取整到1`() {
        // 0.25 × 1.15 = 0.2875 ⇒ ceil = 1
        assertEquals(1, TokenEstimator.estimate("a"))
    }

    @Test
    fun `estimate_三个ASCII_仍是1_四个ASCII跨到2`() {
        // 3/4 × 1.15 = 0.8625 ⇒ 1；4/4 × 1.15 = 1.15 ⇒ 2
        assertEquals(1, TokenEstimator.estimate("abc"))
        assertEquals(2, TokenEstimator.estimate("abcd"))
    }

    @Test
    fun `estimate_一百ASCII_28点75取整到29`() {
        assertEquals(29, TokenEstimator.estimate("a".repeat(100)))
    }

    @Test
    fun `estimate_单个中文_2`() {
        // 1 × 1.15 = 1.15 ⇒ 2
        assertEquals(2, TokenEstimator.estimate("中"))
    }

    @Test
    fun `estimate_两个中文_3`() {
        // 2 × 1.15 = 2.3 ⇒ 3
        assertEquals(3, TokenEstimator.estimate("中文"))
    }

    @Test
    fun `estimate_中文夹ASCII_按非ASCII字符数取大`() {
        // 非 ASCII 2、ASCII 2 ⇒ ASCII 口径 0.5，取大仍是 2 ⇒ 3
        assertEquals(3, TokenEstimator.estimate("ab中文"))
        // ASCII 400（100）压过非 ASCII 10 ⇒ 100 × 1.15 ⇒ 115
        assertEquals(115, TokenEstimator.estimate("a".repeat(400) + "中".repeat(10)))
    }

    @Test
    fun `estimate_纯空格也算ASCII_四个空格算2`() {
        // 空格 code=32 < 128 ⇒ 走 ASCII 口径；4/4 × 1.15 ⇒ 2
        assertEquals(2, TokenEstimator.estimate("    "))
    }

    @Test
    fun `estimate_emoji按UTF16码元计_一个笑脸算2个非ASCII`() {
        // 按当前实现钉住：U+1F600 在 Kotlin String 里是两个 Char（代理对），
        // 两个码元都 ≥128 ⇒ 2 × 1.15 ⇒ 3（不是文档字面的「一个字符一个 token」）。
        assertEquals(3, TokenEstimator.estimate("\uD83D\uDE00"))
        assertEquals(5, TokenEstimator.estimate("\uD83D\uDE00\uD83D\uDE00"))
    }

    @Test
    fun `estimate_非ASCII拉丁字母按一个字符计_单个é也得2`() {
        // é = U+00E9 ≥ 128 ⇒ 非 ASCII 1 个 ⇒ 2；与 CJK 同口径
        assertEquals(2, TokenEstimator.estimate("é"))
    }

    @Test
    fun `estimate_十万中文_不溢出且等于115000`() {
        assertEquals(115_000, TokenEstimator.estimate("甲".repeat(100_000)))
    }

    @Test
    fun `estimate_四十万ASCII_同样等于115000`() {
        // 400_000/4 = 100_000；与十万中文同量 ⇒ 同一读数（两把尺子取大后的等价点）
        assertEquals(115_000, TokenEstimator.estimate("a".repeat(400_000)))
    }

    @Test
    fun `estimate_安全系数钉住1点15`() {
        // 系数是「全系统唯一一把尺子」的可调旋钮；被谁顺手改掉必须在这里红
        assertEquals(1.15, TokenEstimator.SAFETY_FACTOR, 0.0)
    }

    @Test
    fun `estimate_单调不减_前缀越长读数不降`() {
        val text = "甲乙ab中文😀"
        var last = 0
        for (n in 0..text.length) {
            val now = TokenEstimator.estimate(text.take(n))
            org.junit.Assert.assertTrue("前 $n 个码元读数 $now 反而小于上一档 $last", now >= last)
            last = now
        }
    }
}
