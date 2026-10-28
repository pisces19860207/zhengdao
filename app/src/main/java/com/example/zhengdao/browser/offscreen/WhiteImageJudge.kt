// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.browser.offscreen

import java.util.Locale

/**
 * 263 批实验件：白图判定（纯函数，JVM 单测可直接跑）。
 *
 * 输入是位图采样的像素数组，输出三分类：
 * - [Classification.RICH]：颜色分布有实质内容（最大同色占比 < [UNIFORM_RATIO]）⇒ 渲染出了真东西；
 * - [Classification.UNIFORM_WHITE]：几乎整幅同一色且亮度 ≥ [WHITE_LUMINANCE] ⇒ 经典"白图"；
 * - [Classification.UNIFORM_BLANK]：几乎整幅同一色但不是白（全透明黑 / 纯黑 / 纯灰）⇒ 同属"没画出来"。
 *
 * "几乎"是 99.5%：给反锯齿、边角图标留零头，避免把真页面误杀成白图。alpha 不参与分色
 * （透明底画不出来和纯黑画不出来是一回事，统一按 RGB 归类，主色补不透明位报告）。
 * 大图跳采样封顶 [MAX_SAMPLES] 个样本——采样是按线性下标等距取的，对"连续色块"的比例
 * 无偏；极端稀疏的散点可能漏采，这是本判据的已知边界，写进报告不藏。
 */
object WhiteImageJudge {

    /** 最大同色占比达到该阈值即判"整幅同色"。 */
    const val UNIFORM_RATIO = 0.995

    /** 白图判白的亮度下限（0.299R + 0.587G + 0.114B）。 */
    const val WHITE_LUMINANCE = 245

    /** 采样上限：位图再大也只取这么多样本，避免全屏位图把主线程拖死。 */
    const val MAX_SAMPLES = 32_768

    enum class Classification { RICH, UNIFORM_WHITE, UNIFORM_BLANK, EMPTY }

    data class Verdict(
        val classification: Classification,
        /** UNIFORM_* 时是整幅同色；RICH 时是占比最大的颜色（均补不透明位）。 */
        val dominantColor: Int,
        /** 最大同色占比（0..1）。 */
        val dominantRatio: Double,
        /** 实际采样数。 */
        val sampled: Int,
    ) {
        fun colorHex(): String = String.format(Locale.US, "#%08X", dominantColor)

        fun ratioText(): String = String.format(Locale.US, "%.3f", dominantRatio)

        fun describe(): String =
            "主色 ${colorHex()} 占比 ${ratioText()}（采样 $sampled）"

        val isRendered: Boolean get() = classification == Classification.RICH
    }

    fun judge(pixels: IntArray, width: Int, height: Int): Verdict {
        if (width <= 0 || height <= 0 || pixels.isEmpty()) {
            return Verdict(Classification.EMPTY, 0, 0.0, 0)
        }
        val total = width * height
        require(pixels.size >= total) { "pixels.size=${pixels.size} < w*h=$total" }
        val stride = maxOf(1, (total + MAX_SAMPLES - 1) / MAX_SAMPLES)
        val counts = HashMap<Int, Int>()
        var sampledCount = 0
        var i = 0
        while (i < total) {
            val rgb = pixels[i] and 0x00FFFFFF
            counts[rgb] = (counts[rgb] ?: 0) + 1
            sampledCount++
            i += stride
        }
        var dominant = -1
        var dominantCount = 0
        for ((c, n) in counts) {
            if (n > dominantCount) {
                dominant = c
                dominantCount = n
            }
        }
        val ratio = dominantCount.toDouble() / sampledCount
        val opaque = dominant or 0xFF000000.toInt()
        return if (ratio >= UNIFORM_RATIO) {
            val lum = 0.299 * (dominant shr 16 and 0xFF) +
                0.587 * (dominant shr 8 and 0xFF) +
                0.114 * (dominant and 0xFF)
            if (lum >= WHITE_LUMINANCE) {
                Verdict(Classification.UNIFORM_WHITE, opaque, ratio, sampledCount)
            } else {
                Verdict(Classification.UNIFORM_BLANK, opaque, ratio, sampledCount)
            }
        } else {
            Verdict(Classification.RICH, opaque, ratio, sampledCount)
        }
    }
}
