// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第二十四批 C 段追加（2026-10-18 真机：用户发的手机照片 2.7–3.7 MB，一张都没发出去）。
//
// 为什么要有这个文件：`VisionRules.MAX_IMAGE_BYTES = 2 MB`，而手机相机/截图动辄 3 MB 以上。
// 原来的 `VisionFiles` 遇到超限**直接跳过**，`VisionRules.select` 收到空列表 ⇒ 连「省略了几张」
// 的说明都不会有 ⇒ 模型只看到一句「用户附了 1 张图片」，于是老实回「我看不到」。
// 用户的观感就是「视觉能力关了」，而实际上从头到尾没有人关过任何开关。
//
// 结论：**超限不许丢，先缩再说**。本文件只算「怎么缩」（纯函数，JVM 直测），
// 真解码/重编码在 `ImageShrink`（要 Android Bitmap）。
package com.example.zhengdao.baopu

import kotlin.math.roundToInt

/**
 * 缩图判据：目标边长 ＋ 质量阶梯。**纯函数、零 IO、零 Android**。
 *
 * 一起定的两个数：
 * 1. 长边 [MAX_EDGE] = 1568 —— 主流视觉通道在这个量级上信息量够、token 也够省
 *    （截图里的字段名、下拉值、红字都还读得出来）；
 * 2. 质量阶梯 [QUALITY_LADDER] 从高到低试，第一个落进上限的就用，**不追求压到最小**
 *    （压得过狠会把小字糊掉，比不发更糟：模型会照着糊字瞎猜）。
 */
object VisionFit {

    /** 缩完的目标长边（CSS px）。 */
    const val MAX_EDGE: Int = 1568

    /** JPEG 质量阶梯：从高往低试，第一个满足上限即用。 */
    val QUALITY_LADDER: List<Int> = listOf(85, 75, 65, 55)

    /**
     * 一次缩图的计划。
     * @param maxEdge 目标长边
     * @param qualities 依次尝试的质量
     * @param capBytes 压完必须落进的字节上限
     */
    data class Plan(val maxEdge: Int, val qualities: List<Int>, val capBytes: Int)

    /**
     * 需不需要缩。
     *
     * @return null = 原图就能发（**别缩**：任何重编码都是信息损失）；
     *         非 null = 该怎么缩
     */
    fun planFor(bytes: Long, cap: Int = VisionRules.MAX_IMAGE_BYTES): Plan? =
        if (bytes <= cap) null else Plan(MAX_EDGE, QUALITY_LADDER, cap)

    /**
     * 解码时的降采样倍数（`BitmapFactory.Options.inSampleSize`，必须是 2 的幂）。
     *
     * 先按 2 的幂降到 ≥ 目标，再交给 `createScaledBitmap` 精修到正好 —— 一步降到位的
     * `inSampleSize` 只能取 2 的幂，直接用它会把边长缩到目标的一半以下（小字全糊）。
     */
    fun sampleSizeFor(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Int {
        if (width <= 0 || height <= 0 || maxEdge <= 0) return 1
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= maxEdge) sample *= 2
        return sample
    }

    /** 按长边等比缩放后的尺寸（已经够小 ⇒ 原样返回）。纯算术，便于单测边界。 */
    fun scaledSize(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Pair<Int, Int> {
        if (width <= 0 || height <= 0 || maxEdge <= 0) return width to height
        val longest = maxOf(width, height)
        if (longest <= maxEdge) return width to height
        val ratio = maxEdge.toDouble() / longest
        // 四舍五入而不是截断：截断会让 3200×1600 缩成 1568×783（半个像素的丢失积累成变形）
        val w = (width * ratio).roundToInt().coerceAtLeast(1)
        val h = (height * ratio).roundToInt().coerceAtLeast(1)
        return w to h
    }
}
