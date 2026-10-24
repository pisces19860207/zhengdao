// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百八十六批：三个 artifact 工具共用的**落盘 / 预览 / 截图**编排（零 Android）。
//
// 为什么单独一层：
// - 落点、限长、不覆盖、越界拒绝、预览话术、截图失败话术只有一份口径；
// - 截图器是**可注入接口**（[ArtifactRasterizer]）：Android 侧实现是
//   [ArtifactRaster]（XML 无 JS 的沙箱 WebView），JVM 单测注入假件就能验
//   「PNG 失败时如实报错，不把空白当成功」这条负例，而不必造 WebView。
package com.example.zhengdao.baopu.agent

import java.io.File

/** 一张截图的读数；[clipped] = 页面比高度上限更高，只截到上限（要如实告诉用户）。 */
internal data class RasterResult(
    val bytes: Int,
    val width: Int,
    val height: Int,
    val clipped: Boolean = false,
)

/** 渲染失败的人话原因（会原样进工具回执）。 */
internal class RasterException(message: String) : Exception(message)

/** 截图器接口（Android 实现在 [ArtifactRaster]）。 */
internal interface ArtifactRasterizer {
    /** 把一整页 HTML 截成 PNG 写到 [target]；失败抛 [RasterException]。 */
    fun capture(html: String, target: File): RasterResult
}

internal object DrawPublish {

    /** 进程级截图器（装配层注入；JVM 单测可替换）。 */
    @Volatile
    var rasterizer: ArtifactRasterizer? = null

    data class Written(val file: File, val bytes: Int)

    /** 写 UTF-8 文件：限长、工作区内、同名不覆盖（`-2`、`-3`…）。 */
    fun write(
        root: File,
        dir: File,
        base: String,
        ext: String,
        body: String,
        maxBytes: Int,
    ): DrawResult<Written> {
        val bytes = body.toByteArray(Charsets.UTF_8).size
        if (bytes > maxBytes) {
            return drawErr("产物太大：$bytes 字节（上限 $maxBytes）。")
        }
        val target = DrawFiles.uniqueTarget(dir, base, ext)
        if (!DrawFiles.inside(root, target)) {
            return drawDenied("落点越界被拒：${target.absolutePath} 不在工作区 ${root.absolutePath} 内。")
        }
        val write = runCatching { DrawFiles.writeUtf8(target, body) }
        return if (write.isFailure) {
            drawErr("写不进 ${DrawFiles.displayPath(root, target)}：${write.exceptionOrNull()?.javaClass?.simpleName}。")
        } else {
            drawOk(Written(target, bytes))
        }
    }

    /**
     * 推到玄览「本地预览」页签。**文件已经写好**，所以这里只返回一句状态，
     * 不把「玄览没开」升级成整次失败（那会让模型以为文件也没写成）。
     */
    fun previewLine(root: File, file: File): String = when (val r = PreviewTool.preview(root, file)) {
        is ToolOutcome.Ok -> "已送到玄览「本地预览」页签（只显示：默认断网、不跑脚本）"
        is ToolOutcome.Failed -> "预览没推出去：${r.error}（文件已写好，可在玄览本地预览里手动打开）"
        is ToolOutcome.Rejected -> "预览没推出去：${r.reason}（文件已写好，可在玄览本地预览里手动打开）"
    }

    /**
     * 截一张 PNG（与预览同一份 HTML）。
     *
     * 失败一律返回 [DrawResult.Err]——调用方据此把整次工具调用判成失败，并在话术里
     * 带上「HTML 已写好」。**空白位图也会走到这里**（[ArtifactRaster] 先做空白检测），
     * 所以不会出现「回执说 PNG 好了、图却是白的」。
     */
    fun png(root: File, html: String, dir: File, base: String): DrawResult<String> {
        val raster = rasterizer
            ?: return drawErr("PNG 没画出来：截图器没接线（App 未装配）。")
        val target = DrawFiles.uniqueTarget(dir, base, "png")
        if (!DrawFiles.inside(root, target)) {
            return drawDenied("PNG 落点越界被拒：${target.absolutePath} 不在工作区 ${root.absolutePath} 内。")
        }
        return try {
            val r = raster.capture(html, target)
            val clip = if (r.clipped) " · 只截到高度上限 ${r.height}px（更长的部分请看 HTML）" else ""
            drawOk("${DrawFiles.displayPath(root, target)} · PNG · ${r.width}×${r.height} · ${r.bytes} 字节$clip")
        } catch (e: RasterException) {
            drawErr(e.message ?: "PNG 没画出来（原因未知）。")
        } catch (t: Throwable) {
            drawErr("PNG 没画出来：${t.javaClass.simpleName}。")
        }
    }
}
