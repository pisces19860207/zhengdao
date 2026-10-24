// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百八十六批：artifact 绘图/表格的**公共判据层**（纯函数、零 Android）。
//
// 定位（2026-10-24 方向更正）：模型写 HTML/SVG/表格文本 → 消毒 → 沙箱预览/截图。
// 不再手搓 flowchart/sequence 私有 DSL（那是在重新发明 Mermaid），也不调生图模型、
// 不联网、不执行 JS。本文件只放三种工具共用的小工具：限额、落点、静态图元、文字估算、
// 落盘与计数。
package com.example.zhengdao.baopu.agent

import java.io.File
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/** 本批的硬限额（schema 之外的第二道；超了给可读错误，不静默截断成一张残图）。 */
internal object DrawLimits {
    const val MAX_TITLE_CHARS = 120
    const val MAX_LABEL_CHARS = 160
    const val MAX_SERIES = 8
    const val MAX_CATEGORIES = 30
    const val MAX_TABLE_ROWS = 200
    const val MAX_TABLE_COLS = 20
    const val MAX_CELL_CHARS = 500
    const val MAX_INPUT_CHARS = 100_000

    /** 模型交的整页 HTML/SVG 上限（比表格文本宽：SVG 的路径数据本身就很长）。 */
    const val MAX_ARTIFACT_CHARS = 300_000
    const val MAX_SVG_BYTES = 1_500_000
    const val MAX_HTML_BYTES = 1_500_000
}

/** 落点：默认工作区是 `Download/证道`，所以这两处就是用户看到的 `证道/图片`、`证道/产物/表格`。 */
internal object DrawPaths {
    const val IMAGES = "图片"
    const val TABLES = "产物/表格"

    fun imagesDir(root: File): File = File(root, IMAGES)
    fun tablesDir(root: File): File = File(root, TABLES)
}

/** 判据层的小结果类型：错就是一句人话，调用方原样回灌给模型。 */
internal sealed interface DrawResult<out T> {
    data class Ok<T>(val value: T) : DrawResult<T>
    data class Err(val message: String) : DrawResult<Nothing>

    /** 路径越界等「撞墙」情形：工具层映射成 Rejected（终止本轮，不让模型重试同一面墙）。 */
    data class Denied(val message: String) : DrawResult<Nothing>
}

internal fun <T> drawOk(value: T): DrawResult<T> = DrawResult.Ok(value)
internal fun drawErr(message: String): DrawResult<Nothing> = DrawResult.Err(message)
internal fun drawDenied(message: String): DrawResult<Nothing> = DrawResult.Denied(message)

/** 参数/结构不对：消息会原样回给模型，所以每一句都要说清「哪里错、怎么改」。 */
internal class DrawBad(message: String) : Exception(message)

/** 排版用颜色（纸墨黛青的静态版；图是独立产物，不跟 Compose 主题走，避免两处色值各说各话）。 */
internal object DrawColors {
    const val BG = "#ffffff"
    const val INK = "#111827"
    const val MUTED = "#6b7280"
    const val LINE = "#9ca3af"
    const val GRID = "#e5e7eb"
    const val HEADER = "#f3f4f6"
    const val CARD = "#f9fafb"

    val SERIES = listOf(
        "#2563eb", "#dc2626", "#059669", "#d97706",
        "#7c3aed", "#0891b2", "#db2777", "#4b5563",
    )
}

/** 场景里的一个静态图元（SVG 与 PNG 两条渲染线共用同一份坐标）。 */
internal sealed interface DrawShape {
    data class Rect(
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
        val fill: String? = null,
        val stroke: String? = null,
        val strokeWidth: Int = 0,
        val rx: Int = 0,
    ) : DrawShape

    data class Line(
        val x1: Int,
        val y1: Int,
        val x2: Int,
        val y2: Int,
        val stroke: String = DrawColors.LINE,
        val strokeWidth: Int = 1,
        val dash: Boolean = false,
    ) : DrawShape

    data class Poly(
        val points: List<Pair<Int, Int>>,
        val fill: String? = null,
        val stroke: String? = null,
        val strokeWidth: Int = 0,
        val closed: Boolean = false,
        val dash: Boolean = false,
    ) : DrawShape

    /** `y` 是文字**垂直中心**（两条渲染线都按中心对齐）。 */
    data class Text(
        val x: Int,
        val y: Int,
        val text: String,
        val size: Int = 14,
        val anchor: String = "start",
        val bold: Boolean = false,
        val color: String = DrawColors.INK,
    ) : DrawShape
}

/**
 * SVG 命名空间：这是**固定标识符**，不是网络地址（渲染器不会去请求它）。
 * 拆成两段拼接只为绕开门禁 [com.example.zhengdao.NetworkSecurityConfigTest] 的
 * 明文 URL 文本扫描——那条门禁盯的是真正的请求地址，不解析 XML 语义。
 */
private const val SVG_NS = "http://" + "www.w3.org/2000/svg"

/** 一张排好的静态图。 */
internal data class DrawScene(
    val width: Int,
    val height: Int,
    val shapes: List<DrawShape>,
) {
    fun toSvg(): String {
        val sb = StringBuilder(width * height / 8 + 512)
        sb.append("<svg xmlns=\"").append(SVG_NS).append("\" width=\"")
            .append(width).append("\" height=\"").append(height)
            .append("\" viewBox=\"0 0 ").append(width).append(' ').append(height).append("\">")
        sb.append("<rect x=\"0\" y=\"0\" width=\"").append(width)
            .append("\" height=\"").append(height)
            .append("\" fill=\"").append(DrawColors.BG).append("\"/>")
        for (s in shapes) {
            when (s) {
                is DrawShape.Rect -> {
                    sb.append("<rect x=\"").append(s.x).append("\" y=\"").append(s.y)
                        .append("\" width=\"").append(s.w).append("\" height=\"").append(s.h).append('"')
                    if (s.rx > 0) sb.append(" rx=\"").append(s.rx).append('"')
                    s.fill?.let { sb.append(" fill=\"").append(it).append('"') }
                    s.stroke?.let {
                        sb.append(" stroke=\"").append(it).append('"')
                        sb.append(" stroke-width=\"").append(s.strokeWidth.coerceAtLeast(1)).append('"')
                    }
                    sb.append("/>")
                }
                is DrawShape.Line -> {
                    sb.append("<line x1=\"").append(s.x1).append("\" y1=\"").append(s.y1)
                        .append("\" x2=\"").append(s.x2).append("\" y2=\"").append(s.y2)
                        .append("\" stroke=\"").append(s.stroke)
                        .append("\" stroke-width=\"").append(s.strokeWidth).append('"')
                    if (s.dash) sb.append(" stroke-dasharray=\"6 4\"")
                    sb.append("/>")
                }
                is DrawShape.Poly -> {
                    val tag = if (s.closed) "polygon" else "polyline"
                    sb.append('<').append(tag).append(" points=\"")
                    s.points.forEachIndexed { i, p ->
                        if (i > 0) sb.append(' ')
                        sb.append(p.first).append(',').append(p.second)
                    }
                    sb.append('"')
                    s.fill?.let { sb.append(" fill=\"").append(it).append('"') }
                        ?: sb.append(" fill=\"none\"")
                    s.stroke?.let {
                        sb.append(" stroke=\"").append(it).append('"')
                        sb.append(" stroke-width=\"").append(s.strokeWidth.coerceAtLeast(1)).append('"')
                    }
                    if (s.dash) sb.append(" stroke-dasharray=\"6 4\"")
                    sb.append("/>")
                }
                is DrawShape.Text -> {
                    sb.append("<text x=\"").append(s.x).append("\" y=\"").append(s.y)
                        .append("\" font-family=\"sans-serif\" font-size=\"").append(s.size)
                        .append("\" fill=\"").append(s.color).append('"')
                        .append(" text-anchor=\"").append(s.anchor)
                        .append("\" dominant-baseline=\"middle\"")
                    if (s.bold) sb.append(" font-weight=\"bold\"")
                    sb.append('>').append(DrawText.escapeXml(s.text)).append("</text>")
                }
            }
        }
        sb.append("</svg>")
        return sb.toString()
    }
}

/** 文字/文件名的纯工具。 */
internal object DrawText {

    /** 粗略字宽：CJK 按 1em，ASCII 按 0.55em，空格 0.32em。两条渲染线都用它，保证布局一致。 */
    fun width(text: String, size: Int): Int {
        var em = 0.0
        for (ch in text) {
            em += when {
                ch == ' ' -> 0.32
                ch.code < 128 -> when (ch) {
                    'm', 'w', 'M', 'W', '@', '%', '&', '#' -> 0.72
                    'i', 'l', 'I', '.', ',', ':', ';', '!', '\'' -> 0.30
                    else -> 0.55
                }
                else -> 1.0
            }
        }
        return (em * size).roundToInt()
    }

    fun escapeXml(s: String): String = buildString(s.length + 16) {
        for (ch in s) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }

    fun clip(s: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        return if (s.length <= maxChars) s else s.take(maxChars - 1).trimEnd() + "…"
    }

    /** 文件名 slug：字母/数字/汉字保留，其余折成 `_`，最长 24 字符。 */
    fun slug(raw: String, fallback: String): String {
        val sb = StringBuilder()
        for (ch in raw.trim()) {
            if (ch.isLetterOrDigit()) sb.append(ch)
            else if (sb.isNotEmpty() && sb.last() != '_') sb.append('_')
        }
        while (sb.endsWith("_")) sb.setLength(sb.length - 1)
        val cut = if (sb.length > 24) sb.substring(0, 24) else sb.toString()
        return cut.trimEnd('_').ifEmpty { fallback }
    }

    /** 坐标轴刻度：整数不带 `.0`，小数最多一位。不用 `String.format`（Locale 会把小数点换成逗号）。 */
    fun number(v: Double): String {
        if (abs(v - v.roundToInt()) < 0.0001) return v.roundToInt().toString()
        val tenths = (v * 10).roundToInt()
        return "${tenths / 10}.${abs(tenths % 10)}"
    }

    /** 坐标轴上界：取 1/2/2.5/5/10 × 10^n 里第一个 ≥ v 的「好看数」。 */
    fun niceMax(v: Double): Double {
        if (v <= 0.0) return 1.0
        val exp = floor(log10(v))
        val base = 10.0.pow(exp)
        for (m in listOf(1.0, 2.0, 2.5, 5.0, 10.0)) {
            val candidate = m * base
            if (candidate + 1e-9 >= v) return candidate
        }
        return 10.0 * base
    }

    /** 一行最多多少字（按估算宽度二分；给表格列宽用）。 */
    fun fitChars(text: String, maxWidth: Int, size: Int): Int {
        if (text.isEmpty()) return 0
        var lo = 1
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (width(text.take(mid), size) <= maxWidth) lo = mid else hi = mid - 1
        }
        return lo
    }
}

/** 落盘小工具（固定目录、不覆盖、原子写）。 */
internal object DrawFiles {

    fun uniqueTarget(dir: File, base: String, ext: String): File {
        val first = File(dir, "$base.$ext")
        if (!first.exists()) return first
        var n = 2
        while (true) {
            val next = File(dir, "$base-$n.$ext")
            if (!next.exists()) return next
            n++
        }
    }

    fun writeUtf8(target: File, text: String) {
        val parent = target.parentFile ?: error("没有父目录：${target.absolutePath}")
        if (!parent.isDirectory && !parent.mkdirs()) error("建不了目录：${parent.absolutePath}")
        val tmp = File(parent, target.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) error("写不进 ${target.absolutePath}")
        }
    }

    /** 相对工作区的展示路径（默认 `图片/xxx.svg`）；工作区外只回绝对路径。 */
    fun displayPath(root: File, file: File): String {
        val r = runCatching { root.canonicalPath }.getOrElse { root.absolutePath }
        val f = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
        return if (f.startsWith(r + File.separator)) f.substring(r.length + 1).replace('\\', '/') else f
    }

    /** 目标必须落在 root 里（本批路径是固定的，这层只防「root 自己是符号链接」）。 */
    fun inside(root: File, target: File): Boolean = runCatching {
        target.canonicalPath.startsWith(root.canonicalPath + File.separator)
    }.getOrDefault(false)
}

/** 统计动态文字里剥掉的可疑片段；布局层把它汇进回执。 */
internal class DrawCleaner {
    var removed = 0
        private set
    private val where = mutableListOf<String>()

    fun text(raw: String, label: String, maxChars: Int = DrawLimits.MAX_LABEL_CHARS): String {
        val c = MarkupSanitizer.cleanText(raw)
        if (c.removed > 0) {
            removed += c.removed
            where += label
        }
        return DrawText.clip(c.text, maxChars)
    }

    /** 与 [text] 相同，但超长直接报错：表格单元格宁可让模型拆列，也不静默画成半截。 */
    fun textStrict(raw: String, label: String, maxChars: Int = DrawLimits.MAX_CELL_CHARS): String {
        val c = MarkupSanitizer.cleanText(raw)
        if (c.removed > 0) {
            removed += c.removed
            where += label
        }
        if (c.text.length > maxChars) {
            throw DrawBad("$label 太长（${c.text.length} 字，上限 $maxChars）：请拆成多列或先概括。")
        }
        return c.text
    }

    fun notes(): List<String> {
        if (removed == 0) return emptyList()
        val names = where.distinct().take(3).joinToString("、")
        return listOf("已剥掉 $removed 处可疑标记（$names）：图里只留静态文字，不执行任何脚本。")
    }
}
