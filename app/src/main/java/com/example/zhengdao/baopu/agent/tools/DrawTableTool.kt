// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百八十六批：`draw_table`——把 Markdown 表格 / CSV / JSON 变成**静态 HTML 表格**。
//
// 为什么主产物是 HTML 而不是图：模型写 Markdown 表格几乎不花力气，用户要的也是「能读、
// 能复制」；HTML 又正好能在玄览的本地预览页签里看（断网、禁 JS 的那一侧）。
// PNG 只是附赠的一眼预览，由沙箱截同一份 HTML 得到——不另写一套栅格排版。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.util.TimeFormats
import java.io.File
import org.json.JSONObject

internal object DrawTableTool {

    const val NAME = "draw_table"

    const val ARG_SOURCE = "source"
    const val ARG_FORMAT = "format"
    const val ARG_TITLE = "title"
    const val ARG_PNG = "png"

    const val DESCRIPTION =
        "把表格做成 HTML（可读、可复制、能在玄览「本地预览」里看）：source 直接给 Markdown 表格" +
            "（| 表头 | … | 加一行 |---|）、CSV，或 JSON（对象数组 / 数组行 / {headers,rows}）；" +
            "format 省略＝自动认。产物落 `<工作区>/产物/表格/` 的 .html（png=true 再截一张 PNG），" +
            "并推到玄览本地预览；同名不覆盖。单元格里的 HTML 一律当文字（不执行、不联网）；超过 " +
            "${DrawLimits.MAX_TABLE_ROWS} 行 / ${DrawLimits.MAX_TABLE_COLS} 列 / 单元格 " +
            "${DrawLimits.MAX_CELL_CHARS} 字会直接报错，不静默截断。"

    fun spec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = NAME,
        description = DESCRIPTION,
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_SOURCE to jsonObjectOf(
                    "type" to "string",
                    "description" to "表格内容原文：Markdown 表格 / CSV / JSON 文本（JSON 对象数组的字段名就是表头）",
                ),
                ARG_FORMAT to jsonObjectOf(
                    "type" to "string",
                    "enum" to jsonArrayOf(*DrawTable.FORMATS.toTypedArray()),
                    "description" to "输入格式（默认 auto：按内容自动认）",
                ),
                ARG_TITLE to jsonObjectOf(
                    "type" to "string",
                    "description" to "可选：表格标题（HTML 的 caption）",
                ),
                ARG_PNG to jsonObjectOf(
                    "type" to "boolean",
                    "description" to "true = 另外截一张表格的 PNG（默认 false；截图失败会如实报错）",
                ),
            ),
            "required" to jsonArrayOf(ARG_SOURCE),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.CONFIRM,
        timeoutMs = ToolTimeout.DRAW_MS,
    ) { args -> run(workspaceRoot(), args) }

    fun run(root: File, args: JSONObject): ToolOutcome {
        val source = args.optString(ARG_SOURCE)
        val format = if (args.has(ARG_FORMAT)) args.optString(ARG_FORMAT) else null
        val title = if (args.has(ARG_TITLE)) args.optString(ARG_TITLE) else null
        val withPng = args.optBoolean(ARG_PNG, false)

        val rendered = when (val r = DrawTable.render(source, format, title)) {
            is DrawResult.Ok -> r.value
            is DrawResult.Err -> return ToolOutcome.Failed(r.message)
            is DrawResult.Denied -> return ToolOutcome.Rejected(r.message)
        }
        val dir = DrawPaths.tablesDir(root)
        val base = "表-${TimeFormats.fileStamp()}-" + DrawText.slug(title.orEmpty(), "表格")
        val written = when (
            val w = DrawPublish.write(root, dir, base, "html", rendered.html, DrawLimits.MAX_HTML_BYTES)
        ) {
            is DrawResult.Ok -> w.value
            is DrawResult.Err -> return ToolOutcome.Failed(w.message)
            is DrawResult.Denied -> return ToolOutcome.Rejected(w.message)
        }
        val preview = DrawPublish.previewLine(root, written.file)

        val pngLine = if (withPng) {
            when (val p = DrawPublish.png(root, rendered.html, dir, written.file.nameWithoutExtension)) {
                is DrawResult.Ok -> p.value
                is DrawResult.Denied -> return ToolOutcome.Rejected(p.message)
                is DrawResult.Err -> return ToolOutcome.Failed(
                    "表格 HTML 已经落盘（${DrawFiles.displayPath(root, written.file)}），但 ${p.message}" +
                        " 不要重复写表；先看这条报错，再决定是否重试。\n预览状态：$preview",
                )
            }
        } else {
            null
        }

        return ToolOutcome.Ok(
            buildString {
                append("表格做好了：\n")
                append("· ").append(DrawFiles.displayPath(root, written.file))
                    .append(" · HTML · ").append(rendered.summary).append(" · ")
                    .append(written.bytes).append(" 字节\n")
                append("· 预览：").append(preview).append('\n')
                if (pngLine != null) append("· ").append(pngLine).append('\n')
                rendered.notes.forEach { append("· ").append(it).append('\n') }
                append("（单元格里的标记一律当文字；同名不覆盖，再做一次是新文件。")
                append("想看就直接用 preview_local_file 打开这个 .html。）")
            },
        )
    }
}
