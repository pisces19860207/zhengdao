// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百八十六批：`render_artifact`——模型交 HTML/SVG，本地沙箱渲染成预览 + PNG。
//
// 与 158 `generate_image` 的分工（两条线互不替代）：
// - generate_image：照片/插画，调 Gemini 图片模型，烧额度、概率性；
// - 本工具：结构图/说明页，模型直接写标记，本地消毒后渲染——文字/箭头/表格精确、可改、免费。
//
// 三条硬边界落在这里：**不调生图模型**（本文件零网络依赖）、**不联网**、**不跑 JS**
// （沙箱在 [ArtifactRaster]：JS 关、网络全拦）。判据（格式识别、消毒、限额）全在
// [DrawArtifact] 与 [MarkupSanitizer]，这里只管落盘、预览、截图与回执。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.util.TimeFormats
import java.io.File
import org.json.JSONObject

internal object RenderArtifactTool {

    const val NAME = "render_artifact"

    const val ARG_CONTENT = "content"
    const val ARG_FORMAT = "format"
    const val ARG_TITLE = "title"
    const val ARG_PNG = "png"

    const val DESCRIPTION =
        "把一段 HTML/SVG 交给本地沙箱渲染（不联网、不执行 JS、不调生图模型）：先消毒" +
            "（script/iframe/on*/外链 CSS 与图片一律剥掉），落 `<工作区>/图片/` 的 .html，" +
            "推到玄览本地预览，并截一张 PNG（png=false 可省）。适合你直接写 SVG 的流程图/" +
            "示意图、带样式的说明页；柱状/折线用 draw_chart，表格用 draw_table。" +
            "空内容、认不出标签、超长（${DrawLimits.MAX_ARTIFACT_CHARS} 字）会直接报错，" +
            "不产出空白图。"

    fun spec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = NAME,
        description = DESCRIPTION,
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_CONTENT to jsonObjectOf(
                    "type" to "string",
                    "description" to "HTML 或 SVG 全文（可以是片段）。只渲染静态标记；" +
                        "外链图片/CSS/字体、脚本、表单都会在落盘前剥掉。",
                ),
                ARG_FORMAT to jsonObjectOf(
                    "type" to "string",
                    "enum" to jsonArrayOf(*DrawArtifact.FORMATS.toTypedArray()),
                    "description" to "内容类型（默认 auto：看第一个标签；也可显式给 html / svg）",
                ),
                ARG_TITLE to jsonObjectOf(
                    "type" to "string",
                    "description" to "可选：标题（用于文件名与 SVG 外包页的 <title>）",
                ),
                ARG_PNG to jsonObjectOf(
                    "type" to "boolean",
                    "description" to "true = 另外截一张 PNG（默认 true；截图失败会如实报错，不会拿空白图冒充成功）",
                ),
            ),
            "required" to jsonArrayOf(ARG_CONTENT),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.CONFIRM,
        timeoutMs = ToolTimeout.DRAW_MS,
    ) { args -> run(workspaceRoot(), args) }

    fun run(root: File, args: JSONObject): ToolOutcome {
        val content = args.optString(ARG_CONTENT)
        val format = if (args.has(ARG_FORMAT)) args.optString(ARG_FORMAT) else null
        val title = if (args.has(ARG_TITLE)) args.optString(ARG_TITLE) else null
        val withPng = args.optBoolean(ARG_PNG, true)

        val rendered = when (val r = DrawArtifact.render(content, format, title)) {
            is DrawResult.Ok -> r.value
            is DrawResult.Err -> return ToolOutcome.Failed(r.message)
            is DrawResult.Denied -> return ToolOutcome.Rejected(r.message)
        }
        val dir = DrawPaths.imagesDir(root)
        val base = "画板-${TimeFormats.fileStamp()}-" + DrawText.slug(rendered.title.orEmpty(), "artifact")
        val written = when (
            val w = DrawPublish.write(root, dir, base, "html", rendered.html, DrawLimits.MAX_HTML_BYTES)
        ) {
            is DrawResult.Ok -> w.value
            is DrawResult.Err -> return ToolOutcome.Failed(w.message)
            is DrawResult.Denied -> return ToolOutcome.Rejected(w.message)
        }

        val preview = DrawPublish.previewLine(root, written.file)
        if (withPng) {
            val pngTargetBase = written.file.nameWithoutExtension
            when (val p = DrawPublish.png(root, rendered.html, dir, pngTargetBase)) {
                is DrawResult.Ok -> return ToolOutcome.Ok(
                    report(root, written, rendered, p.value, preview),
                )
                is DrawResult.Denied -> return ToolOutcome.Rejected(p.message)
                is DrawResult.Err -> return ToolOutcome.Failed(
                    "HTML 已经落盘（${DrawFiles.displayPath(root, written.file)}），" +
                        "但 ${p.message}" +
                        " 不要重复写 HTML；先看这条报错，再决定是否重试。\n预览状态：$preview",
                )
            }
        }
        return ToolOutcome.Ok(report(root, written, rendered, null, preview))
    }

    private fun report(
        root: File,
        written: DrawPublish.Written,
        rendered: DrawArtifact.Rendered,
        pngLine: String?,
        preview: String,
    ): String = buildString {
        append("画板做好了：\n")
        append("· ").append(DrawFiles.displayPath(root, written.file))
            .append(" · HTML · ").append(written.bytes).append(" 字节\n")
        append("· 预览：").append(preview).append('\n')
        if (pngLine != null) append("· ").append(pngLine).append('\n')
        if (rendered.removed > 0) {
            append("· 已剥掉 ").append(rendered.removed)
                .append(" 处可执行/外链标记（只留静态标记，不执行任何脚本）。\n")
        }
        append("（本地沙箱渲染：没调生图模型、没联网、没执行任何脚本；同名不覆盖，再画一次是新文件。）")
    }
}
