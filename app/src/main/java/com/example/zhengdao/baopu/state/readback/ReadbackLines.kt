// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百二十二批 · 读回契约：三行文本容器 + 注入接缝。
// 边界：三行只是文本上下文（装配层放进 ContextAssembler.extraNotes），
//       不进任何闸；身体数值一律由 BodyState / 669 词表派生，本文件不写死。
package com.example.zhengdao.baopu.state.readback

/** 读回三行：不变量（身份锚）／状态（当前态）／词档（规范用词）。 */
internal data class ReadbackLines(
    val invariant: String,
    val status: String,
    val vocab: String,
) {
    /**
     * 注入接缝：只产出纯文本三行，供装配层放进本轮文本上下文。
     * 本单零装配——不碰 RunEngine / ContextAssembler / ZhengdaoApp。
     */
    fun asContextNotes(): List<String> = listOf(invariant, status, vocab)

    /** 三行合并成一段（token 预算按这一整段估）。 */
    fun asText(): String = asContextNotes().joinToString("\n")

    companion object {
        /** 判据：每行 ≤90 字（Kotlin String.length，CJK 一字一码位）。 */
        const val MAX_LINE_CHARS = 90

        /** 判据：三行合计 ≤200 token（TokenEstimator 口径）。 */
        const val MAX_TOTAL_TOKENS = 200

        /** 缺失留位标记——不许编数、不许硬编码数值，只标"未接入"。 */
        const val UNWIRED = "未接入"

        /** 超长截断：只整段收，`max` 含省略号。 */
        fun clip(text: String, max: Int = MAX_LINE_CHARS): String =
            if (text.length <= max) text else text.take((max - 1).coerceAtLeast(0)) + "…"
    }
}
