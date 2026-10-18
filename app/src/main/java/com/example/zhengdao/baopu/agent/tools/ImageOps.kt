// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：用户 2026-10-18 逐字「还能拿图片干这些啊，可是没工具吧？」⇒「落」。
// 抱朴原先只会「看图」和「动文件名」，真让它改图就卡在没工具。本文件是 `image_ops` 的**判据层**：
// 纯函数、零 IO、零 Android，JVM 直测；真解码/重编码在 `ImageOpsTool` 里（要 Bitmap）。
//
// 四条规矩：
// 1. **原图永不覆盖**：输出默认另存 `<名>-<动作>.<ext>`，指定 out 时若已存在就拒（不覆盖）；
// 2. 只认能编码的三种格式（png/jpg/webp）；jpg 没有透明通道 ⇒ 透明区压白底（不偷偷丢掉 alpha 信息而不说）；
// 3. 参数缺了就如实说缺哪个（不猜默认值蒙混）；
// 4. icon 是「一档多写」：一次生成 5 档 PNG，文件名固定 `<base>-<n>.png`。
package com.example.zhengdao.baopu.agent

/** 图片矩形（左上右下，单位=原图像素）。 */
internal data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height <= 0
}

/** 一个主色（hex 不含 alpha；share = 占该图非透明像素的比例）。 */
internal data class PaletteColor(val hex: String, val share: Double)

internal object ImageOps {

    // ── 动作枚举（与 spec 的 enum 必须逐字一致，门禁会比对）──────────────────
    const val ACT_RESIZE = "resize"
    const val ACT_CROP = "crop"
    const val ACT_CONVERT = "convert"
    const val ACT_PALETTE = "palette"
    const val ACT_ICON = "icon"

    val ACTIONS: List<String> = listOf(ACT_RESIZE, ACT_CROP, ACT_CONVERT, ACT_PALETTE, ACT_ICON)

    /** 认的图片格式（与 Android 那三种编码器一一对应）。 */
    val FORMATS: List<String> = listOf("png", "jpg", "webp")

    /** jpg 无 alpha：透明区压白底。 */
    fun supportsAlpha(format: String): Boolean = format.lowercase() != "jpg"

    /** 扩展名 ⇒ 格式；认不出回 null（不是图片就别进这个工具）。 */
    fun formatOf(fileName: String): String? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "png" -> "png"
            "jpg", "jpeg" -> "jpg"
            "webp" -> "webp"
            else -> null
        }
    }

    /**
     * 输出文件名：`<基名>-<动作>.<ext>`。
     *
     * 为什么带动作名：同一张图可能既要缩又要转，带动作名两份都在，用户一眼看得懂谁是谁。
     */
    fun outName(srcName: String, action: String, format: String): String {
        val base = srcName.substringBeforeLast('.', srcName)
        return "$base-$action.${if (format == "jpeg") "jpg" else format}"
    }

    /** icon 的五档文件基名与边长（与常见 launcher 密度档对应）。 */
    val ICON_SIZES: List<Int> = listOf(48, 72, 96, 144, 192)

    fun iconName(srcName: String, edge: Int): String {
        val base = srcName.substringBeforeLast('.', srcName)
        return "$base-$edge.png"
    }

    /** 等比缩到长边不超过 [maxEdge]（已经够小 ⇒ 原样；宽高非正 ⇒ 原样）。 */
    fun fitSize(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0 || maxEdge <= 0) return width to height
        val longest = maxOf(width, height)
        if (longest <= maxEdge) return width to height
        val ratio = maxEdge.toDouble() / longest
        val w = (width * ratio).toInt().coerceAtLeast(1)
        val h = (height * ratio).toInt().coerceAtLeast(1)
        return w to h
    }

    /**
     * 裁剪矩形：**夹到图像内**，但不替用户改方向（left>right 视为参数写反 ⇒ 返回 null，让调用方报错）。
     *
     * 夹而不是拒：模型常写出「右下角多 1-2 像素」的矩形，直接拒会让它反复试；
     * 夹完之后仍要求非空（夹成空的就说明这个矩形整个在图外，那是真错）。
     */
    fun clampCrop(width: Int, height: Int, rect: PixelRect): PixelRect? {
        if (width <= 0 || height <= 0) return null
        if (rect.right <= rect.left || rect.bottom <= rect.top) return null
        // 与图像**没有交集**（整块在某一侧之外）⇒ 这不是"越界一点点"，是真错，直接 null。
        // 有交集才做夹取：模型常写出「右下角多 1-2 像素」，夹一下就对了，不该反复试。
        if (rect.left >= width || rect.top >= height || rect.right <= 0 || rect.bottom <= 0) return null
        val l = rect.left.coerceIn(0, width - 1)
        val t = rect.top.coerceIn(0, height - 1)
        val r = rect.right.coerceIn(l + 1, width)
        val b = rect.bottom.coerceIn(t + 1, height)
        val out = PixelRect(l, t, r, b)
        return if (out.isEmpty) null else out
    }

    /**
     * 主色提取（**纯函数**：喂 ARGB 像素数组，出前 N 个主色）。
     *
     * 量化到每通道 4 bit（16 级）后计数，再按桶内均值回色——比 k-means 简单一个数量级，
     * 对「抠个配色」这个用途够用，而且结果是确定的（同一张图两次跑出一样的色，可断言）。
     * 完全透明的像素不参与统计（否则透明区会把白色刷成主色）。
     */
    fun palette(pixels: IntArray, count: Int = 5): List<PaletteColor> {
        if (pixels.isEmpty() || count <= 0) return emptyList()
        val sums = HashMap<Int, LongArray>(64)
        var total = 0L
        for (px in pixels) {
            val a = (px ushr 24) and 0xFF
            if (a == 0) continue
            val r = (px ushr 16) and 0xFF
            val g = (px ushr 8) and 0xFF
            val b = px and 0xFF
            val key = ((r ushr 4) shl 8) or ((g ushr 4) shl 4) or (b ushr 4)
            val acc = sums.getOrPut(key) { LongArray(4) }
            acc[0] += r.toLong()
            acc[1] += g.toLong()
            acc[2] += b.toLong()
            acc[3] += 1
            total++
        }
        if (total == 0L) return emptyList()
        return sums.values
            .sortedByDescending { it[3] }
            .take(count)
            .map { acc ->
                val n = acc[3]
                val r = (acc[0] / n).toInt().coerceIn(0, 255)
                val g = (acc[1] / n).toInt().coerceIn(0, 255)
                val b = (acc[2] / n).toInt().coerceIn(0, 255)
                PaletteColor(
                    hex = "#%02X%02X%02X".format(r, g, b),
                    share = n.toDouble() / total.toDouble(),
                )
            }
    }

    /** 每个动作缺哪个参数（返回 null = 参数齐了）。**缺就报缺哪个，不猜默认值。** */
    fun missingParam(
        action: String,
        maxEdge: Int?,
        rect: PixelRect?,
        format: String?,
        usesOut: Boolean,
    ): String? = when (action) {
        ACT_RESIZE -> if (maxEdge == null || maxEdge <= 0) "resize 需要 max_edge（目标长边，正整数）" else null
        ACT_CROP -> if (rect == null) "crop 需要 left/top/right/bottom（原图像素，左上右下）" else null
        ACT_CONVERT -> if (format.isNullOrBlank()) "convert 需要 format（png/jpg/webp）" else null
        ACT_ICON -> null
        ACT_PALETTE -> null
        else -> "不认识的动作：$action（只认 ${ACTIONS.joinToString("/")}）"
    }
}
