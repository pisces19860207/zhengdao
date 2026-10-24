// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百八十六批（artifact-first）：`render_artifact` 的**判据层**（纯函数、零 IO、零 Android）。
//
// 模型交一整段 HTML 或 SVG；这里只做四件事：
// 1. 认格式（auto 看第一个标签；显式给 html/svg 就按它走）；
// 2. 过 [MarkupSanitizer] 的白名单重建（外链全剥、脚本连内容丢）；
// 3. 把 SVG 包成一页 HTML（预览与 PNG 必须看同一份标记）；
// 4. 尺寸/空内容/超限的**可读错误**——绝不返回一段空白让上层当成成功。
//
// 不在这里碰网络、WebView、文件系统：沙箱渲染在 [DrawPublish] + Android 侧
// [ArtifactRaster]，落盘在工具层，单测可以直接打这里的每一个判据。
package com.example.zhengdao.baopu.agent

internal object DrawArtifact {

    val FORMATS = listOf("auto", "html", "svg")

    data class Rendered(
        val html: String,
        val format: String,
        val title: String?,
        val removed: Int,
        val summary: String,
    )

    fun render(contentRaw: String, formatRaw: String?, titleRaw: String?): DrawResult<Rendered> {
        val content = contentRaw.removePrefix("\uFEFF").trim()
        if (content.isEmpty()) {
            return drawErr("artifact 是空的：给一段 HTML 或 SVG 全文。")
        }
        if (content.length > DrawLimits.MAX_ARTIFACT_CHARS) {
            return drawErr(
                "artifact 太长：${content.length} 字（上限 ${DrawLimits.MAX_ARTIFACT_CHARS}）：请删掉内联大图或拆成多个文件。",
            )
        }
        val fmt = formatRaw?.trim()?.lowercase().orEmpty().ifBlank { "auto" }
        if (fmt !in FORMATS) {
            return drawErr("不认识的 format「$formatRaw」：只认 ${FORMATS.joinToString(" / ")}。")
        }
        val first = firstTag(content)
            ?: return drawErr(
                "不是 HTML/SVG：内容里没有可识别的标签（纯文本请用 write_file；本工具只渲染标记）。",
            )
        if (fmt == "svg" && !Regex("""<\s*svg\b""", RegexOption.IGNORE_CASE).containsMatchIn(content)) {
            return drawErr("format=svg，但内容里没有 <svg> 标签。")
        }
        val isSvg = fmt == "svg" || (fmt == "auto" && first == "svg")
        val cleaner = DrawCleaner()
        val title = titleRaw
            ?.let { cleaner.text(it, "标题", DrawLimits.MAX_TITLE_CHARS) }
            ?.ifBlank { null }
        return if (isSvg) {
            renderSvg(content, title, cleaner.removed)
        } else {
            renderHtml(content, title, cleaner.removed)
        }
    }

    /** SVG 独立文件 → 一页 HTML（居中、留白、不加任何外链）。 */
    fun svgPage(svg: String, title: String?): String = buildString(svg.length + 512) {
        append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
        append("<title>").append(DrawText.escapeXml(title ?: "图")).append("</title>")
        append("<style>html,body{margin:0;padding:16px;background:#ffffff;}")
        append("svg{display:block;margin:0 auto;max-width:100%;height:auto;}</style>")
        append("</head><body>").append(svg).append("</body></html>")
    }

    private fun renderSvg(content: String, title: String?, titleRemoved: Int): DrawResult<Rendered> {
        val body = stripProlog(content)
        val clean = MarkupSanitizer.sanitizeSvg(body)
        if (!clean.ok) {
            return drawErr("SVG 消毒后不可用：${clean.problems.joinToString("；")}")
        }
        val svgBytes = clean.text.toByteArray(Charsets.UTF_8).size
        if (svgBytes > DrawLimits.MAX_SVG_BYTES) {
            return drawErr("SVG 太大：$svgBytes 字节（上限 ${DrawLimits.MAX_SVG_BYTES}）。")
        }
        val html = svgPage(clean.text, title)
        sizeVerdict(html)?.let { return drawErr(it) }
        return drawOk(
            Rendered(
                html = html,
                format = "svg",
                title = title,
                removed = clean.removed + titleRemoved,
                summary = "SVG 画板：${clean.text.length} 字，剥掉 ${clean.removed + titleRemoved} 处",
            ),
        )
    }

    private fun renderHtml(content: String, title: String?, titleRemoved: Int): DrawResult<Rendered> {
        val clean = MarkupSanitizer.sanitizeHtml(content)
        if (!clean.ok) {
            return drawErr("HTML 消毒后不可用：${clean.problems.joinToString("；")}")
        }
        sizeVerdict(clean.text)?.let { return drawErr(it) }
        return drawOk(
            Rendered(
                html = clean.text,
                format = "html",
                title = title,
                removed = clean.removed + titleRemoved,
                summary = "HTML 画板：${clean.text.length} 字，剥掉 ${clean.removed + titleRemoved} 处",
            ),
        )
    }

    private fun sizeVerdict(html: String): String? {
        val bytes = html.toByteArray(Charsets.UTF_8).size
        return if (bytes > DrawLimits.MAX_HTML_BYTES) {
            "artifact 太大：$bytes 字节（上限 ${DrawLimits.MAX_HTML_BYTES}）。"
        } else {
            null
        }
    }

    /**
     * 第一个真标签名（小写；跳过 BOM、XML 声明、DOCTYPE 与注释）。
     *
     * 只认「`<` 后紧跟字母」的形状：纯文本里出现个 `<` 不算标记，`<!--`、`<?xml` 也不算。
     * 注释要先删掉——`<!-- <svg> -->` 不能把一页 HTML 误判成 SVG。
     */
    private fun firstTag(raw: String): String? {
        val noNoise = Regex(
            """<!--.*?-->|<\?.*?\?>|<!DOCTYPE[^>]*>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        ).replace(raw, "")
        val m = Regex("""<\s*([a-zA-Z][a-zA-Z0-9:_-]*)""").find(noNoise) ?: return null
        return m.groupValues[1].lowercase()
    }

    private fun stripProlog(raw: String): String = raw
        .replace(Regex("""^\s*<\?xml[^>]*\?>""", setOf(RegexOption.IGNORE_CASE)), "")
        .replace(Regex("""^\s*<!DOCTYPE[^>]*>""", setOf(RegexOption.IGNORE_CASE)), "")
        .trimStart()
}
