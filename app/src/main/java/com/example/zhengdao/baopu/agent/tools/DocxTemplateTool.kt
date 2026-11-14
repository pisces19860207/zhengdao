// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百四十二批（2026-11-14）· 工具 `docx_template`：拿用户自己那份 .docx 当模板，
// 把 `{{占位符}}` 换成值 —— 排版/样式/页眉页脚全照模板，一个像素都不动。
//
// 与 `docx_write` 的分工（也写进描述里给模型看）：
//   · 从零写一份 ⇒ `docx_write`；
//   · 用户已有格式（合同、通知书、周报模板）⇒ `docx_template`（只换字，不改版）。
//
// 四条纪律：
//   1. 模板**只读**（不落到 产物/、不改原件），成品落 `产物/`（[OfficeSink]，同名不覆盖）；
//   2. **缺字段 ⇒ 整次失败并点名**（"缺少字段 X"），绝不静默留空；
//   3. 带宏的模板（.docm / vbaProject.bin）当场拒（安全红线）；
//   4. 不做循环、不做条件、不改样式（第一期只换字）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.UserPath
import com.example.zhengdao.baopu.office.DocxTemplate
import com.example.zhengdao.baopu.office.Step
import com.example.zhengdao.core.TimeFormats
import java.io.File
import org.json.JSONObject

internal object DocxTemplateTool {

    const val NAME = "docx_template"

    const val ARG_TEMPLATE = "template"
    const val ARG_FIELDS = "fields"
    const val ARG_NAME = "name"

    /** 文件名兜底（模型没给 name 时）。 */
    private const val FALLBACK_BASE = "填好的文档"

    fun spec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = NAME,
        description = "把用户给的一份 .docx 当模板，替换里面的 {{占位符}}，生成一份新文档。" +
            "template 给模板路径；fields 给每个占位符的值（键就是 {{}} 里的名字，两侧空格不算）。" +
            "模板的排版、字体、页眉页脚、表格全照抄，**只换文字**；" +
            "模板里有占位符没给值 ⇒ 直接报「缺少字段 X」，不会留空。" +
            "带宏的 .docm、公式、循环/条件模板都不做。只落 产物/，同名不覆盖。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_TEMPLATE to jsonObjectOf(
                    "type" to "string",
                    "description" to "模板文件路径（.docx；相对路径相对工作区，也可给绝对路径）",
                ),
                ARG_FIELDS to jsonObjectOf(
                    "type" to "object",
                    "description" to "占位符名 → 值，如 {\"姓名\":\"张三\",\"日期\":\"2026-11-14\"}；" +
                        "模板里出现的每个占位符都必须给值",
                    "additionalProperties" to jsonObjectOf("type" to "string"),
                ),
                ARG_NAME to jsonObjectOf(
                    "type" to "string",
                    "description" to "成品文件名基（不含扩展名；缺省用模板名）。落点固定为 产物/<名>-<时间>.docx",
                ),
            ),
            "required" to jsonArrayOf(ARG_TEMPLATE, ARG_FIELDS),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.CONFIRM,
        riskTier = "R1", // 读模板、只写工作区 产物/，不动用户既有文件、不联网
        readOnly = false,
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args -> run(args, workspaceRoot()) }

    fun run(args: JSONObject, workspaceRoot: File): ToolOutcome {
        val rawPath = args.optString(ARG_TEMPLATE).trim()
        if (rawPath.isEmpty()) return ToolOutcome.Failed("缺少必填参数「$ARG_TEMPLATE」（模板路径）")
        if (rawPath.lowercase().endsWith(".docm")) {
            return ToolOutcome.Failed("不做宏：.docm 是带宏的文档（安全红线，不执行也不嵌入）。请另存为普通 .docx。")
        }
        val file = UserPath.resolve(workspaceRoot, rawPath)
        if (!file.exists()) {
            return ToolOutcome.Failed("模板不存在：$rawPath（先 list_dir 看一眼路径）")
        }
        if (!file.isFile) return ToolOutcome.Failed("不是一个文件：$rawPath（模板要给 .docx 文件）")
        if (file.length() > DocxTemplate.MAX_TEMPLATE_BYTES) {
            return ToolOutcome.Failed(
                "模板有 ${PathBatch.sizeText(file.length())}，超过 " +
                    "${DocxTemplate.MAX_TEMPLATE_BYTES / 1024 / 1024} MB 上限；请换一份小一点的",
            )
        }
        val fields = fieldsOf(args.optJSONObject(ARG_FIELDS))
            ?: return ToolOutcome.Failed("「$ARG_FIELDS」要是对象：{\"占位符名\":\"值\"}")
        if (!args.has(ARG_FIELDS)) {
            return ToolOutcome.Failed(
                "缺少必填参数「$ARG_FIELDS」：模板里每个 {{占位符}} 都要在这里给一个值" +
                    "（模板里没有占位符就传 {}）",
            )
        }
        val bytes = runCatching { file.readBytes() }.getOrNull()
            ?: return ToolOutcome.Failed("模板读不出来：$rawPath（权限或 IO 错误）")
        return when (val done = DocxTemplate.apply(bytes, fields)) {
            is Step.Bad -> ToolOutcome.Failed(done.reason)
            is Step.Ok -> write(done.value, args.optString(ARG_NAME).trim(), file, workspaceRoot)
        }
    }

    private fun write(
        applied: com.example.zhengdao.baopu.office.TemplateApplied,
        rawName: String,
        template: File,
        root: File,
    ): ToolOutcome {
        val base = OfficeSink.baseOf(rawName.ifEmpty { template.nameWithoutExtension }, FALLBACK_BASE)
        return when (val done = OfficeSink.write(root, base, "docx", applied.bytes, TimeFormats.fileStamp())) {
            is Step.Bad -> ToolOutcome.Failed(done.reason)
            is Step.Ok -> {
                val shown = OfficeSink.record(NAME, root, done.value, "按模板生成 docx")
                val fieldText = if (applied.fields.isEmpty()) {
                    "模板里没有 {{占位符}}，成品与模板同文"
                } else {
                    "替换 ${applied.replaced} 处 / ${applied.fields.size} 个字段（${applied.parts} 个部件），" +
                        "字段：${applied.fields.joinToString("、")}"
                }
                ToolOutcome.Ok("已写出 $shown（${PathBatch.sizeText(done.value.length())}；$fieldText）")
            }
        }
    }

    /**
     * `fields` 取值：**只认字符串/数字/布尔**，`null` 当没给（那样才会报"缺少字段"）。
     *
     * 为什么 null 不写成空串：用户漏一个字段，最坏的结果不是报错，而是拿到一份
     * 「甲方：_____」的空合同——他不知道哪里少了东西。
     */
    private fun fieldsOf(obj: JSONObject?): Map<String, String>? {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = obj.opt(key)
            if (value == null || value == JSONObject.NULL) continue
            out[key.trim()] = value.toString()
        }
        return out
    }
}
