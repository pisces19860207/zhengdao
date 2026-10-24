// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百八十六批：`draw_chart`——柱状图 / 折线图的**确定性**渲染（bar|line）。
//
// 与 158 `generate_image` 的分工：生图管照片/插画；本工具管结构数据，模型只写 JSON，
// 本地排成 SVG（可选 PNG）——刻度、标签、数值精确，可复算、免费。
//
// 三条硬边界：**不调生图模型**、**不联网**、**不跑 JS**（没有图表库，也没有 WebView
// 执行脚本；PNG 只是把同一段 SVG 放进无脚本沙箱截一张）。判据全在 [DrawChart]。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.util.TimeFormats
import java.io.File
import org.json.JSONObject

internal object DrawChartTool {

    const val NAME = "draw_chart"

    const val ARG_KIND = "kind"
    const val ARG_DATA = "data"
    const val ARG_TITLE = "title"
    const val ARG_PNG = "png"

    const val DESCRIPTION =
        "画柱状图/折线图（本地渲染、免费、不联网、不执行脚本）：kind=bar|line；" +
            "data 给 JSON 对象 {categories:[\"一月\",…], series:[{name:\"系列\",values:[1,2]}], xLabel?}。" +
            "values 必须与 categories 一一对应、且是非负数（坐标轴从 0 起）；最多 " +
            "${DrawLimits.MAX_CATEGORIES} 个类别 × ${DrawLimits.MAX_SERIES} 个系列。" +
            "产物落 `<工作区>/图片/` 的 SVG（png=true 再加一张 PNG），同名不覆盖。" +
            "要流程图/示意图就直接用 render_artifact 写 SVG，不要硬塞成 chart。"

    fun spec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = NAME,
        description = DESCRIPTION,
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_KIND to jsonObjectOf(
                    "type" to "string",
                    "enum" to jsonArrayOf(*DrawChart.KINDS.toTypedArray()),
                    "description" to "图型：bar 柱状图 / line 折线图",
                ),
                ARG_DATA to jsonObjectOf(
                    "type" to "object",
                    "description" to "结构数据（JSON 对象）：" +
                        "{categories:[\"一月\",\"二月\"],series:[{name:\"销量\",values:[3,5]}],xLabel:\"月份\"}；" +
                        "categories 也可写成 labels。",
                ),
                ARG_TITLE to jsonObjectOf(
                    "type" to "string",
                    "description" to "可选：画在图顶上的标题",
                ),
                ARG_PNG to jsonObjectOf(
                    "type" to "boolean",
                    "description" to "true = 另外截一张同样内容的 PNG（默认 false，只出 SVG）",
                ),
            ),
            "required" to jsonArrayOf(ARG_KIND, ARG_DATA),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.CONFIRM,
        timeoutMs = ToolTimeout.DRAW_MS,
    ) { args -> run(workspaceRoot(), args) }

    fun run(root: File, args: JSONObject): ToolOutcome {
        val kind = args.optString(ARG_KIND).trim().lowercase()
        if (kind.isEmpty()) {
            return ToolOutcome.Failed("缺少必填参数「$ARG_KIND」：只认 ${DrawChart.KINDS.joinToString(" / ")}。")
        }
        val data = args.optJSONObject(ARG_DATA)
            ?: return ToolOutcome.Failed(
                "参数「$ARG_DATA」必须是 **JSON 对象**（不是字符串、不是数组）：" +
                    "把 categories/series 放进一个对象里再传。",
            )
        val title = if (args.has(ARG_TITLE)) args.optString(ARG_TITLE) else null
        val withPng = args.optBoolean(ARG_PNG, false)

        val rendered = when (val r = DrawChart.render(kind, data, title)) {
            is DrawResult.Ok -> r.value
            is DrawResult.Err -> return ToolOutcome.Failed(r.message)
            is DrawResult.Denied -> return ToolOutcome.Rejected(r.message)
        }
        val svg = rendered.scene.toSvg()
        val clean = MarkupSanitizer.sanitizeSvg(svg)
        if (!clean.ok) return ToolOutcome.Failed("SVG 消毒失败：${clean.problems.joinToString("；")}")
        if (clean.removed > 0) {
            return ToolOutcome.Failed(
                "SVG 里出现了 ${clean.removed} 处白名单外的标记：已拒绝落盘（这是工具自身的缺陷，请报告）。",
            )
        }
        val dir = DrawPaths.imagesDir(root)
        val base = "图-${TimeFormats.fileStamp()}-" +
            DrawText.slug(title.orEmpty(), if (rendered.kind == "line") "折线图" else "柱状图")
        val written = when (
            val w = DrawPublish.write(root, dir, base, "svg", clean.text, DrawLimits.MAX_SVG_BYTES)
        ) {
            is DrawResult.Ok -> w.value
            is DrawResult.Err -> return ToolOutcome.Failed(w.message)
            is DrawResult.Denied -> return ToolOutcome.Rejected(w.message)
        }

        val pngBase = written.file.nameWithoutExtension
        val pngLine = if (withPng) {
            val page = DrawArtifact.svgPage(clean.text, rendered.summary)
            when (val p = DrawPublish.png(root, page, dir, pngBase)) {
                is DrawResult.Ok -> p.value
                is DrawResult.Denied -> return ToolOutcome.Rejected(p.message)
                is DrawResult.Err -> return ToolOutcome.Failed(
                    "SVG 已经落盘（${DrawFiles.displayPath(root, written.file)}），但 ${p.message}" +
                        " 不要重复画；先看这条报错，再决定是否重试。",
                )
            }
        } else {
            null
        }

        return ToolOutcome.Ok(
            buildString {
                append("画好了（${if (rendered.kind == "line") "折线图" else "柱状图"}）：\n")
                append("· ").append(DrawFiles.displayPath(root, written.file))
                    .append(" · SVG · ${rendered.scene.width}×${rendered.scene.height} · ")
                    .append(written.bytes).append(" 字节\n")
                append("· ").append(rendered.summary).append('\n')
                if (pngLine != null) append("· ").append(pngLine).append('\n')
                rendered.notes.forEach { append("· ").append(it).append('\n') }
                append("（本地渲染：没调生图模型、没联网、没执行任何脚本；同名不覆盖，再画一次是新文件。）")
            },
        )
    }
}
