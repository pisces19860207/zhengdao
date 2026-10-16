// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：抱朴-Agent设计-程枢版-2026-10-16.md §7「token 估算器（2026-10-16 补录，
// 否则 0.85 落不了地）」定版估算式；派工单第八批追加二第 2 条（一把尺子：
// Memory 的 ≤ 2k 裁剪与 ContextAssembler 的窗口判定共用，run_log 的
// tokens_in_est / tokens_out_est 也用它写）。
package com.example.zhengdao.baopu

/**
 * token 估算器（刀 0b · 2026-10-16）——**全系统唯一一把尺子**。
 *
 * 定版估算式（程枢版 §7，保守侧）：
 * **中文按字符数 × 1.0，ASCII 按字节 ÷ 4，取大者，再 × 1.15 安全系数**。
 *
 * 为什么需要它：Kotlin 侧没有 tokenizer，provider 的 `usage` 只对**已发生**的
 * 请求有效 ⇒ 组装前算不出 token 数，压缩阈值（窗口 × 0.85）会变成「等它爆了再说」。
 *
 * 校准路径：估算值与实际 `usage` 记进 `run_log` 的 `tokens_in_est` /
 * `tokens_out_est` 两列（与 `tokens_in` / `tokens_out` 对拍），跑约 20 次即可
 * 校准 1.15 这个系数。**系数要改，改这里一处**，别处不许私算。
 */
internal object TokenEstimator {

    /** 安全系数（程枢版 §7 定版值；对拍校准前不动）。 */
    const val SAFETY_FACTOR = 1.15

    /**
     * 估算一段文本的 token 数（纯函数，零依赖，可 JVM 单测）。
     *
     * 口径：CJK 及一切非 ASCII 字符按「一个字符一个 token」估；ASCII 按
     * 「4 字节一个 token」估；两者取大（一段纯中文不会因为夹了几个空格就被
     * ASCII 口径拉低）；最后 × 1.15。
     */
    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        var nonAscii = 0
        var ascii = 0
        for (ch in text) {
            if (ch.code < 128) ascii++ else nonAscii++
        }
        val base = maxOf(nonAscii.toDouble(), ascii / 4.0)
        return Math.ceil(base * SAFETY_FACTOR).toInt()
    }
}
