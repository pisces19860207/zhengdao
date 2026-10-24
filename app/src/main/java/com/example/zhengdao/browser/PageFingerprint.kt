// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：第一百七十七批施工图 §5（S3 操作前后自校验）。零 Android、零 IO、纯函数，
// JVM 直测；脚本与解析放在同一处，避免「页面里拼一套、Kotlin 里解另一套」的漂移。
package com.example.zhengdao.browser

/**
 * 页面指纹：一次 `evaluateJavascript` 取回「URL/标题/页面高度/可交互元素数/正文长度」。
 *
 * ## 为什么是这五个字段
 * 它们覆盖了「操作生效了没有」的四种常见证据，且都便宜（不需要 DOM 序列化）：
 * ① URL 变了 ⇒ 跳转/路由切换；② 标题变了 ⇒ 页面换了一屏；③ 高度变了 ⇒ 内容增减
 * （列表展开、弹层）；④ 可交互元素数变了 ⇒ 表单态/按钮态变了；⑤ 正文长度变了 ⇒
 * 文本区刷新。**任何一项变化都算「有可测反应」**；全都不动才敢说「没测到变化」。
 *
 * ## 为什么用控制符分隔而不是 JSON
 * `org.json` 在 `android.jar` 里是 stub，JVM 单测一碰就抛 `Stub!`（ERRATA E-130 同款坑）。
 * 这里改用 U+0001（SOH）作分隔符（页面几乎不可能在 URL/标题里含它）；万一含了，
 * [parse] 回 null，上层如实说「本次没做成败校验」，不猜。
 *
 * ## 纪律
 * - **不拿指纹当「成功」判据**：页面没变化不等于操作失败（点了但页面本就无响应，
 *   或变化在视口外）；这里只负责「有变化 / 没变化」的**事实陈述**，成败由模型判断。
 * - **不截敏值**：本指纹只取长度类数字与 URL/标题（URL/标题在上层另有脱敏口径）。
 */
internal object PageFingerprint {

    /** 字段分隔符：U+0001。 */
    const val SEP: Char = '\u0001'

    /**
     * 取指纹的页面脚本。
     *
     * 分隔符用 `String.fromCharCode(1)` 在页面侧生成——不在 Kotlin 字符串里塞控制符，
     * 免得被编辑器/换行规范改成别的字节。
     */
    const val SCRIPT: String =
        "(function(){var d=document.documentElement;" +
            "var n=document.querySelectorAll('a,button,input,select,textarea," +
            "[role=button],[role=link],[onclick]').length;" +
            "var t=(document.body&&document.body.innerText||'').length;" +
            "return [location.href,document.title,d.scrollHeight,n,t]" +
            ".join(String.fromCharCode(1))})()"

    /** 一个页面的指纹快照。 */
    data class Fp(
        val url: String,
        val title: String,
        val scrollHeight: Int,
        val interactive: Int,
        val textLen: Int,
    )

    /**
     * 解析页面回传的指纹串。
     *
     * 字段数不对、数字解析不了、或回传为 null（JS 超时/页面未绘制）⇒ **null**：
     * 上层只说「没做成败校验」，绝不用半截指纹编一条结论。
     */
    fun parse(raw: String?): Fp? {
        if (raw.isNullOrEmpty()) return null
        val parts = raw.split(SEP)
        if (parts.size != 5) return null
        val h = parts[2].trim().toIntOrNull() ?: return null
        val n = parts[3].trim().toIntOrNull() ?: return null
        val t = parts[4].trim().toIntOrNull() ?: return null
        return Fp(parts[0], parts[1], h, n, t)
    }

    /** 「目标元素还在不在」的页面脚本（selector 由调用方经 `jsStringLiteral` 带引号嵌入）。 */
    fun targetExistsScript(selectorLiteral: String): String =
        "(function(){return document.querySelector($selectorLiteral)?'YES':'NO'})()"

    /**
     * 前后指纹 → 一句人话（拼进工具回执）。
     *
     * 三种结局都如实：
     * - 任一指纹取不到 ⇒ 「本次没做成败校验」（不假装成功）；
     * - 有字段变了 ⇒ 逐条列变化（URL/标题/高度/元素数/正文长度）；
     * - 全都没变 ⇒ 「页面无可测变化（可能没生效，或页面本就无响应）」。
     *
     * @param targetStillThere 只有给了 selector 的操作才有；null = 没查
     */
    fun diff(before: Fp?, after: Fp?, targetStillThere: Boolean? = null): String {
        if (before == null || after == null) {
            return "校验：本次没做成败校验（取不到页面指纹：页面可能还没绘制完）"
        }
        val lines = ArrayList<String>(5)
        if (before.url != after.url) {
            lines += "URL 变了：${brief(before.url)} → ${brief(after.url)}"
        } else {
            lines += "URL 未变"
        }
        lines += if (before.title != after.title) {
            "标题「${brief(before.title)}」→「${brief(after.title)}」"
        } else {
            "标题未变「${brief(before.title)}」"
        }
        if (before.scrollHeight != after.scrollHeight) {
            lines += "页面高度 ${before.scrollHeight}→${after.scrollHeight}"
        }
        if (before.interactive != after.interactive) {
            lines += "可交互元素 ${before.interactive}→${after.interactive}"
        }
        if (before.textLen != after.textLen) {
            lines += "正文长度 ${before.textLen}→${after.textLen}"
        }
        if (targetStillThere != null) {
            lines += if (targetStillThere) "目标元素仍在" else "目标元素已消失"
        }
        val changed = before != after || targetStillThere == false
        return if (changed) {
            "校验：" + lines.joinToString("；")
        } else {
            "校验：" + lines.joinToString("；") +
                " ⇒ 页面无可测变化（可能没生效，或页面本就无响应；别当成已经成功）"
        }
    }

    /** 长文本压短：URL/标题进回执前截到 60 字符，避免把回执撑爆。 */
    private fun brief(s: String): String =
        if (s.length <= 60) s else s.take(60) + "…"
}
