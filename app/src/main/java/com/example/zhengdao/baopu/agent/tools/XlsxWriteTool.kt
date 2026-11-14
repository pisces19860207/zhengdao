// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百三十二批（2026-11-13）· 工具 `xlsx_write`：把二维数据写成一份能发给人的 Excel 表格。
//
// 与 `sheet_read` 的分工：那边把表读成行（不猜类型）；这边把**已经定好类型与格式**的数据写成表。
// 字符串永远按文本写——要数字/日期就必须显式给 `format`（**不猜**，与 232/218 同一条纪律）。
//
// 判据落点：多 sheet ≤5（超出明确报错）、表头加粗＋冻结首行、列宽自适应、
// 数字/日期格式闭集、合并单元格；输出只落 `产物/`、同名不覆盖、写成功落一行账。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.office.OfficeLimits
import com.example.zhengdao.baopu.office.Step
import com.example.zhengdao.baopu.office.XlsxSpec
import com.example.zhengdao.baopu.office.XlsxSpecParser
import com.example.zhengdao.baopu.office.XlsxWriter
import com.example.zhengdao.core.TimeFormats
import java.io.File
import org.json.JSONObject

internal object XlsxWriteTool {

    const val NAME = "xlsx_write"

    private const val FALLBACK_BASE = "表格"

    fun spec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = NAME,
        description = "把二维数据写成一个 .xlsx（Excel/WPS 能直接打开）。" +
            "sheets 每项：{name(≤31 字), rows(二维数组), header(默认 true), merges([\"A1:C1\"])}。" +
            "格子写法：文本直接给字符串，数字直接给数字，空给 null；" +
            "要数字/日期格式就写 {\"v\":\"2026-11-13\",\"format\":\"yyyy-mm-dd\"}" +
            "（可用 0 / 0.00 / #,##0.00 / 0% / 0.00% / yyyy-mm-dd / yyyy-mm-dd hh:mm / hh:mm）。" +
            "表头自动加粗并冻结首行，列宽自适应。最多 ${OfficeLimits.XLSX_MAX_SHEETS} 个 sheet" +
            "、每表 ${OfficeLimits.XLSX_MAX_ROWS} 行；**只写值，不算公式**。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                XlsxSpecParser.ARG_NAME to jsonObjectOf(
                    "type" to "string",
                    "description" to "文件名基（不含扩展名；缺省用第一个 sheet 名）。落点固定为 产物/<名>-<时间>.xlsx",
                ),
                XlsxSpecParser.ARG_SHEETS to jsonObjectOf(
                    "type" to "array",
                    "description" to "工作表数组，每项形如 {\"name\":\"汇总\",\"rows\":[[\"姓名\",\"分数\"],[\"张三\",90]]}",
                    "items" to jsonObjectOf("type" to "object", "description" to "一个工作表"),
                ),
            ),
            "required" to jsonArrayOf(XlsxSpecParser.ARG_SHEETS),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.CONFIRM,
        riskTier = "R1", // 只写工作区 产物/，不动用户既有文件、不联网
        readOnly = false,
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args -> run(args, workspaceRoot()) }

    fun run(args: JSONObject, workspaceRoot: File): ToolOutcome =
        when (val parsed = XlsxSpecParser.parse(args)) {
            is Step.Bad -> ToolOutcome.Failed(parsed.reason)
            is Step.Ok -> write(parsed.value, workspaceRoot)
        }

    private fun write(spec: XlsxSpec, root: File): ToolOutcome {
        val bytes = XlsxWriter.build(spec)
        val base = OfficeSink.baseOf(spec.fileBase, FALLBACK_BASE)
        return when (val done = OfficeSink.write(root, base, "xlsx", bytes, TimeFormats.fileStamp())) {
            is Step.Bad -> ToolOutcome.Failed(done.reason)
            is Step.Ok -> {
                val rows = spec.sheets.sumOf { it.rows.size }
                val shown = OfficeSink.record(NAME, root, done.value, "生成 xlsx")
                ToolOutcome.Ok(
                    "已写出 $shown（${PathBatch.sizeText(done.value.length())}；" +
                        "${spec.sheets.size} 个 sheet / $rows 行）",
                )
            }
        }
    }
}
