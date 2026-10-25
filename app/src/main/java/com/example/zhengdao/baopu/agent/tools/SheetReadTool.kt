// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第二百一十八批 ① `sheet_read`：读工作区里的表 → 结构化（只读，R0）。
// 判据全在 [SheetReader]/[SheetWorkbook]/[SheetCsv]；本文件只出 spec 与输出渲染。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.agent.ToolGroup
import com.example.zhengdao.baopu.agent.ToolPermission
import com.example.zhengdao.baopu.agent.ToolSpec
import com.example.zhengdao.baopu.agent.ToolTimeout
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.baopu.agent.jsonArrayOf
import com.example.zhengdao.baopu.agent.jsonObjectOf
import com.example.zhengdao.baopu.workspaceBoundaryCheck
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal object SheetReadTool {

    const val ARG_PATH = "path"
    const val ARG_SHEET = "sheet"
    const val ARG_RANGE = "range"
    const val ARG_HAS_HEADER = "hasHeader"
    const val ARG_MAX_ROWS = "maxRows"

    fun spec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = "sheet_read",
        description = "读工作区里的表格文件（xlsx / csv）为结构化行。单元格原样" +
            "（不补 0、不猜类型），空单元格给 null；超过行数上限会标 truncated。" +
            "文件超过 20MB、加密、或不是表格格式会明确报未认领。" +
            "（这个工具在 DATA 组：看不到就先 capabilities 解锁）",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_PATH to jsonObjectOf("type" to "string", "description" to "相对工作区根的表文件路径（.xlsx / .csv）"),
                ARG_SHEET to jsonObjectOf("type" to "string", "description" to "可选：sheet 名或序号（1 起）；缺省第一个"),
                ARG_RANGE to jsonObjectOf("type" to "string", "description" to "可选：A1:C10 这种矩形范围"),
                ARG_HAS_HEADER to jsonObjectOf("type" to "boolean", "description" to "首行是不是表头（缺省 false）"),
                ARG_MAX_ROWS to jsonObjectOf("type" to "integer", "description" to "最多读几行（缺省 200，上限 2000）"),
            ),
            "required" to jsonArrayOf(ARG_PATH),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.FREE,
        riskTier = "R0", // 只读
        readOnly = true,
        parallelSafe = true,
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args -> run(args, workspaceRoot()) }

    fun run(args: JSONObject, root: File): ToolOutcome {
        val rel = args.optString(ARG_PATH).ifBlank { return ToolOutcome.Failed("缺 path（相对工作区根）") }
        val file = File(root, rel)
        workspaceBoundaryCheck(root, file, rel, "路径越出工作区：$rel（sheet_read 只读工作区里的表）")
            ?.let { return ToolOutcome.Rejected(it) }
        if (!file.isFile) {
            return ToolOutcome.Failed("不存在：$rel（先用 list_dir 看结构）")
        }
        val maxRows = if (args.has(ARG_MAX_ROWS)) args.optInt(ARG_MAX_ROWS, SheetLimits.DEFAULT_ROWS) else SheetLimits.DEFAULT_ROWS
        val result = SheetReader.read(
            file = file,
            sheet = args.optString(ARG_SHEET).ifBlank { null },
            range = args.optString(ARG_RANGE).ifBlank { null },
            hasHeader = args.optBoolean(ARG_HAS_HEADER, false),
            maxRows = maxRows,
        )
        val grid = when (val failure = result.exceptionOrNull()) {
            null -> result.getOrThrow()
            is SheetReadFailure.TooBig -> return ToolOutcome.Failed(
                "文件太大（${failure.bytes / 1024 / 1024}MB > 上限 ${SheetLimits.MAX_FILE_BYTES / 1024 / 1024}MB）：$rel",
            )
            SheetReadFailure.Encrypted -> return ToolOutcome.Failed(
                "这个 xlsx 是加密工作簿，未认领（先在电脑上另存为不加密的副本再放进工作区）",
            )
            is SheetReadFailure.Unsupported -> return ToolOutcome.Failed("未认领：${failure.why}")
            else -> return ToolOutcome.Failed("未认领：${failure.message ?: failure.javaClass.simpleName}")
        }
        val out = JSONObject()
            .put("sheets", JSONArray(grid.sheets))
            .put("header", valuesJson(grid.header))
            .put("rows", JSONArray(grid.rows.map { valuesJson(it) }))
            .put("truncated", grid.truncated)
            .put("skipped", JSONObject(grid.skipped))
        return ToolOutcome.Ok(out.toString(2))
    }

    /** 单元格原样进 JSON：空 ⇒ null（**不补 0**），其余按字符串（不猜类型）。 */
    private fun valuesJson(values: List<String?>): JSONArray =
        JSONArray().apply { values.forEach { put(it ?: JSONObject.NULL) } }
}
