// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// `image_ops`（2026-10-18，用户逐字「落」）：让抱朴真能改图，而不是嘴上说「要不要我动手」。
//
// 五条硬纪律：
// 1. 只在工作区内读写（workspaceBoundaryCheck——156 批起开关开＋已授权可越界，先过确认卡）；
// 2. **永不动原图**：输出另存（默认 `<名>-<动作>.<ext>`），目标已存在即拒（不覆盖）；
// 3. 权限 CONFIRM：每次一张确认卡（参数里写清 action/path/out，用户看一眼就懂）；
// 4. 参数缺就报缺哪个（判据在 `ImageOps.missingParam`，不猜默认值蒙混）；
// 5. `palette` 只回文本、不落盘（配色表是给对话看的，不是新文件）。
package com.example.zhengdao.baopu.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.example.zhengdao.baopu.workspaceBoundaryCheck
import java.io.File

/** `image_ops`：缩放 / 裁剪 / 转格式 / 取主色 / 生成图标各档。 */
internal object ImageOpsTool {

    const val ARG_ACTION = "action"
    const val ARG_PATH = "path"
    const val ARG_OUT = "out"
    const val ARG_MAX_EDGE = "max_edge"
    const val ARG_LEFT = "left"
    const val ARG_TOP = "top"
    const val ARG_RIGHT = "right"
    const val ARG_BOTTOM = "bottom"
    const val ARG_FORMAT = "format"
    const val ARG_COLORS = "colors"

    /** 取主色默认几个。 */
    const val DEFAULT_COLORS = 5

    fun spec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = "image_ops",
        description = "改图（每次执行前弹确认卡）：resize＝等比缩到长边 max_edge；" +
            "crop＝按 left/top/right/bottom（原图像素，左上右下）裁一块；" +
            "convert＝转成 png/jpg/webp（jpg 无透明通道，透明区压白底）；" +
            "palette＝回这张图的主色（hex ＋ 占比，只回文本不落盘）；" +
            "icon＝一次生成 48/72/96/144/192 五档方图标 PNG。" +
            "**原图永不覆盖**：默认另存 <名>-<动作>.<ext>，目标已存在就拒绝。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_ACTION to jsonObjectOf(
                    "type" to "string",
                    "enum" to jsonArrayOf(*ImageOps.ACTIONS.toTypedArray()),
                    "description" to "resize / crop / convert / palette / icon",
                ),
                ARG_PATH to jsonObjectOf(
                    "type" to "string",
                    "description" to "源图（相对工作区根，如 附件/20261018-233000-x.png）",
                ),
                ARG_OUT to jsonObjectOf(
                    "type" to "string",
                    "description" to "输出路径（相对工作区根；省略则默认 <原目录>/<名>-<动作>.<ext>）",
                ),
                ARG_MAX_EDGE to jsonObjectOf(
                    "type" to "integer",
                    "description" to "resize 用：目标长边像素（如 512）",
                ),
                ARG_LEFT to jsonObjectOf("type" to "integer", "description" to "crop 用：左边界像素"),
                ARG_TOP to jsonObjectOf("type" to "integer", "description" to "crop 用：上边界像素"),
                ARG_RIGHT to jsonObjectOf("type" to "integer", "description" to "crop 用：右边界像素（不含）"),
                ARG_BOTTOM to jsonObjectOf("type" to "integer", "description" to "crop 用：下边界像素（不含）"),
                ARG_FORMAT to jsonObjectOf(
                    "type" to "string",
                    "enum" to jsonArrayOf(*ImageOps.FORMATS.toTypedArray()),
                    "description" to "convert 用：目标格式",
                ),
                ARG_COLORS to jsonObjectOf(
                    "type" to "integer",
                    "description" to "palette 用：要几个主色（默认 $DEFAULT_COLORS，最多 8）",
                ),
            ),
            "required" to jsonArrayOf(ARG_ACTION, ARG_PATH),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.CONFIRM,
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args ->
        run(
            root = workspaceRoot(),
            action = args.optString(ARG_ACTION).trim(),
            path = args.optString(ARG_PATH).trim(),
            out = args.optString(ARG_OUT).trim().ifBlank { null },
            maxEdge = if (args.has(ARG_MAX_EDGE)) args.optInt(ARG_MAX_EDGE) else null,
            rect = if (args.has(ARG_LEFT) && args.has(ARG_TOP) && args.has(ARG_RIGHT) && args.has(ARG_BOTTOM)) {
                PixelRect(
                    left = args.optInt(ARG_LEFT),
                    top = args.optInt(ARG_TOP),
                    right = args.optInt(ARG_RIGHT),
                    bottom = args.optInt(ARG_BOTTOM),
                )
            } else {
                null
            },
            format = args.optString(ARG_FORMAT).trim().ifBlank { null }?.lowercase(),
            colors = if (args.has(ARG_COLORS)) args.optInt(ARG_COLORS) else DEFAULT_COLORS,
        )
    }

    /**
     * 执行（Android 侧：真解码/编码）。
     *
     * @param root 工作区根（越界判据基准）
     */
    fun run(
        root: File,
        action: String,
        path: String,
        out: String?,
        maxEdge: Int?,
        rect: PixelRect?,
        format: String?,
        colors: Int = DEFAULT_COLORS,
    ): ToolOutcome {
        if (path.isBlank()) return ToolOutcome.Failed("缺少必填参数「$ARG_PATH」")
        val src = resolve(root, path)
        // 第一百五十六批：越界改走统一判据（开关开＋已授权 ⇒ 放行；关 ⇒ 原文拒绝）
        workspaceBoundaryCheck(
            root,
            src,
            path,
            "路径越界被拒：$path 不在工作区（${root.absolutePath}）内。",
        )?.let { return ToolOutcome.Rejected(it) }
        if (!src.isFile) return ToolOutcome.Failed("源图不存在：$path（先用 list_dir 看清有什么）")
        val srcFormat = ImageOps.formatOf(src.name)
            ?: return ToolOutcome.Failed("这不是认识的图片格式：${src.name}（只认 png/jpg/webp）")
        val missing = ImageOps.missingParam(action, maxEdge, rect, format, out != null)
        if (missing != null) return ToolOutcome.Failed(missing)

        val decoded = decode(src)
            ?: return ToolOutcome.Failed("解不开这张图（可能已损坏，或格式伪装了扩展名）：$path")

        try {
            return when (action) {
                ImageOps.ACT_PALETTE -> palette(decoded, colors)
                ImageOps.ACT_ICON -> icon(root, src, decoded, out)
                ImageOps.ACT_RESIZE -> {
                    val (w, h) = ImageOps.fitSize(decoded.width, decoded.height, maxEdge!!)
                    writeOne(root, src, "resize", out, srcFormat, scaleTo(decoded, w, h))
                }
                ImageOps.ACT_CROP -> {
                    val r = ImageOps.clampCrop(decoded.width, decoded.height, rect!!)
                        ?: return ToolOutcome.Failed(
                            "裁剪矩形不成立：left/top/right/bottom＝${rect.left}/${rect.top}/${rect.right}/${rect.bottom}，" +
                                "原图 ${decoded.width}×${decoded.height}",
                        )
                    val cropped = runCatching {
                        Bitmap.createBitmap(decoded, r.left, r.top, r.width, r.height)
                    }.getOrNull() ?: return ToolOutcome.Failed("裁剪失败（矩形超出图像？）")
                    try {
                        writeOne(root, src, "crop", out, srcFormat, cropped)
                    } finally {
                        runCatching { cropped.recycle() }
                    }
                }
                ImageOps.ACT_CONVERT -> writeOne(root, src, "convert", out, format!!, decoded)
                else -> ToolOutcome.Failed("不认识的动作：$action（只认 ${ImageOps.ACTIONS.joinToString("/")}）")
            }
        } finally {
            runCatching { decoded.recycle() }
        }
    }

    // ── 各动作 ──────────────────────────────────────────────────────────────

    /** 取主色：纯文本回执，不落盘。 */
    private fun palette(bmp: Bitmap, colors: Int): ToolOutcome {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return ToolOutcome.Failed("图像尺寸为 0，取不了色")
        val pixels = IntArray(w * h)
        runCatching { bmp.getPixels(pixels, 0, w, 0, 0, w, h) }
            .onFailure { return ToolOutcome.Failed("读像素失败：${it.javaClass.simpleName}") }
        val n = colors.coerceIn(1, 8)
        val list = ImageOps.palette(pixels, n)
        if (list.isEmpty()) return ToolOutcome.Failed("这张图没有非透明像素（全透明），取不了主色")
        val sb = StringBuilder()
        sb.append("主色 ${list.size} 个（$w×$h，按占比排序）：\n")
        list.forEachIndexed { i, c ->
            sb.append("${i + 1}. ${c.hex}\t${"%.1f".format(c.share * 100)}%\n")
        }
        sb.append("（只回了文本，没有写任何文件）")
        return ToolOutcome.Ok(sb.toString().trimEnd())
    }

    /** 生成五档方图标：等比缩 + 居中放透明画布（不留拉伸变形）。 */
    private fun icon(root: File, src: File, bmp: Bitmap, out: String?): ToolOutcome {
        val dir = out?.let { resolve(root, it).parentFile } ?: src.parentFile
        if (dir == null) return ToolOutcome.Failed("定位不到输出目录")
        // 第一百五十六批：越界改走统一判据（开关开＋已授权 ⇒ 放行；关 ⇒ 原文拒绝）
        workspaceBoundaryCheck(
            root,
            dir,
            dir.absolutePath,
            "输出目录越界被拒：${dir.absolutePath}",
        )?.let { return ToolOutcome.Rejected(it) }
        if (!dir.exists() && !dir.mkdirs()) return ToolOutcome.Failed("建不了输出目录：${dir.absolutePath}")
        val written = mutableListOf<String>()
        for (edge in ImageOps.ICON_SIZES) {
            val name = ImageOps.iconName(src.name, edge)
            val target = File(dir, name)
            if (target.exists()) return ToolOutcome.Failed("目标已存在，不做覆盖：${relOf(root, target)}")
            val canvas = createBitmap(edge, edge)
            try {
                val (w, h) = ImageOps.fitSize(bmp.width, bmp.height, edge)
                val scaled = scaleTo(bmp, w, h)
                try {
                    Canvas(canvas).drawBitmap(
                        scaled,
                        ((edge - w) / 2).toFloat(),
                        ((edge - h) / 2).toFloat(),
                        null,
                    )
                    val bytes = encode(canvas, "png")
                        ?: return ToolOutcome.Failed("编码失败：$name")
                    if (!writeBytes(target, bytes)) return ToolOutcome.Failed("写不进去：${relOf(root, target)}")
                    written += "${relOf(root, target)}（${bytes.size} B）"
                } finally {
                    runCatching { if (scaled !== bmp) scaled.recycle() }
                }
            } finally {
                runCatching { canvas.recycle() }
            }
        }
        return ToolOutcome.Ok("已生成 ${written.size} 档图标（原图未动）：\n" + written.joinToString("\n"))
    }

    /** 写单张输出（不覆盖、不越界、原子写）。 */
    private fun writeOne(
        root: File,
        src: File,
        action: String,
        out: String?,
        format: String,
        bmp: Bitmap,
    ): ToolOutcome {
        val target = out?.let { resolve(root, it) }
            ?: File(src.parentFile, ImageOps.outName(src.name, action, format))
        // 第一百五十六批：越界改走统一判据（开关开＋已授权 ⇒ 放行；关 ⇒ 原文拒绝）
        workspaceBoundaryCheck(
            root,
            target,
            target.absolutePath,
            "输出路径越界被拒：${target.absolutePath}",
        )?.let { return ToolOutcome.Rejected(it) }
        if (target.absolutePath == src.absolutePath) {
            return ToolOutcome.Failed("输出路径和原图相同——image_ops 永不覆盖原图，请换一个 out")
        }
        if (target.exists()) {
            return ToolOutcome.Failed("目标已存在，不做覆盖：${relOf(root, target)}（换一个 out，或先删掉旧的）")
        }
        val fmt = ImageOps.formatOf(target.name)
            ?: return ToolOutcome.Failed("输出文件名要带 png/jpg/webp 扩展名：${target.name}")
        target.parentFile?.let { p -> if (!p.exists() && !p.mkdirs()) return ToolOutcome.Failed("建不了输出目录：$p") }
        // jpg 无 alpha：判据在 ImageOps.supportsAlpha，这里按它决定是否压白底
        val toEncode = if (!ImageOps.supportsAlpha(fmt)) flattenOnWhite(bmp) else bmp
        try {
            val bytes = encode(toEncode, fmt) ?: return ToolOutcome.Failed("编码失败（$fmt）")
            if (!writeBytes(target, bytes)) return ToolOutcome.Failed("写不进去：${relOf(root, target)}")
            val alphaNote = if (!ImageOps.supportsAlpha(fmt)) "；jpg 无透明通道，透明区已压白底" else ""
            return ToolOutcome.Ok(
                "已生成 ${relOf(root, target)}（${bmp.width}×${bmp.height} → ${toEncode.width}×${toEncode.height}，" +
                    "${bytes.size} B$alphaNote）。原图未动。",
            )
        } finally {
            if (toEncode !== bmp) runCatching { toEncode.recycle() }
        }
    }

    // ── Bitmap 小工具 ────────────────────────────────────────────────────────

    private fun decode(file: File): Bitmap? =
        runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()

    private fun scaleTo(bmp: Bitmap, w: Int, h: Int): Bitmap =
        if (bmp.width == w && bmp.height == h) {
            bmp
        } else {
            runCatching { bmp.scale(w, h) }.getOrDefault(bmp)
        }

    private fun flattenOnWhite(bmp: Bitmap): Bitmap {
        val out = createBitmap(bmp.width, bmp.height)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        c.drawBitmap(bmp, 0f, 0f, null)
        return out
    }

    private fun encode(bmp: Bitmap, format: String): ByteArray? = runCatching {
        val fmt = when (format.lowercase()) {
            "png" -> Bitmap.CompressFormat.PNG
            "webp" -> Bitmap.CompressFormat.WEBP_LOSSLESS
            else -> Bitmap.CompressFormat.JPEG
        }
        val out = java.io.ByteArrayOutputStream()
        if (!bmp.compress(fmt, 92, out)) return@runCatching null
        out.toByteArray()
    }.getOrNull()

    /** 原子写：先写 `.tmp` 再 rename（与 `LibraryPaths.atomicWrite` 同口径）。 */
    private fun writeBytes(target: File, bytes: ByteArray): Boolean = runCatching {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (tmp.renameTo(target)) {
            true
        } else {
            target.writeBytes(bytes)
            runCatching { tmp.delete() }
            target.isFile
        }
    }.getOrDefault(false)

    private fun resolve(root: File, path: String): File =
        if (File(path).isAbsolute) File(path) else File(root, path)

    private fun relOf(root: File, f: File): String = runCatching {
        f.absoluteFile.toRelativeString(root.absoluteFile).replace('\\', '/')
    }.getOrDefault(f.name)
}
