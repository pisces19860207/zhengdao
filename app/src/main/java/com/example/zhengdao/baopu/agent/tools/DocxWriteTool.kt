// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百三十二批（2026-11-13）· 工具 `docx_write`：把结构化内容写成一份能发给人的 Word 文档。
//
// 与 `write_file` 的分工（也写进描述里给模型看）：要一份**能打开的 .docx** 用本件；
// 改已有文件的一部分仍用 `edit_file` / `write_file`（本件不做增量，每次是一份新文档）。
//
// 三条纪律：
//   1. 输出只落 `产物/`（[OfficeSink]），同名不覆盖；
//   2. 图片**先读先验**（PNG/JPEG、≤2 张、≤20MB）：读不到就明确失败，不生成一份缺图的文档；
//   3. 写成功落一行账（路径/大小/来源），账写不进库不影响这次写（287 口径）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.UserPath
import com.example.zhengdao.baopu.office.DocxBlock
import com.example.zhengdao.baopu.office.DocxSpec
import com.example.zhengdao.baopu.office.DocxSpecParser
import com.example.zhengdao.baopu.office.DocxWriter
import com.example.zhengdao.baopu.office.ImageProbe
import com.example.zhengdao.baopu.office.OfficeImage
import com.example.zhengdao.baopu.office.OfficeLimits
import com.example.zhengdao.baopu.office.Step
import com.example.zhengdao.core.TimeFormats
import java.io.File
import org.json.JSONObject

internal object DocxWriteTool {

    const val NAME = "docx_write"

    /** 文件名兜底（模型没给 name、块里也没有可用的标题时）。 */
    private const val FALLBACK_BASE = "文档"

    fun spec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = NAME,
        description = "把结构化内容写成一个 .docx（Word/WPS 能直接打开，可发给别人）。" +
            "blocks 逐块给：heading(level 1–3) / paragraph / list(ordered 有无序号) / " +
            "table(columns 表头＋rows 数据，≤${OfficeLimits.DOCX_MAX_COLUMNS} 列) / image(path，≤${OfficeLimits.DOCX_MAX_IMAGES} 张)。" +
            "段落与列表项支持 **粗体**、*斜体* 标注；header 可给一行页眉。" +
            "样式是固定的一套（标题 22/17/15pt、正文 15pt、行距 1.5），**不支持**改字体/字号。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                DocxSpecParser.ARG_NAME to jsonObjectOf(
                    "type" to "string",
                    "description" to "文件名基（不含扩展名；缺省用第一个标题/段落）。落点固定为 产物/<名>-<时间>.docx",
                ),
                DocxSpecParser.ARG_HEADER to jsonObjectOf(
                    "type" to "string",
                    "description" to "页眉那一行文字（可选；不给就不建页眉）",
                ),
                DocxSpecParser.ARG_BLOCKS to jsonObjectOf(
                    "type" to "array",
                    "description" to "内容块数组，每块形如 {\"type\":\"paragraph\",\"text\":\"…\"}；" +
                        "表格为 {\"type\":\"table\",\"columns\":[\"列1\"],\"rows\":[[\"值\"]]}",
                    "items" to jsonObjectOf("type" to "object", "description" to "一个内容块"),
                ),
            ),
            "required" to jsonArrayOf(DocxSpecParser.ARG_BLOCKS),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.CONFIRM,
        riskTier = "R1", // 只写工作区 产物/，不动用户既有文件、不联网
        readOnly = false,
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args -> run(args, workspaceRoot()) }

    fun run(args: JSONObject, workspaceRoot: File): ToolOutcome =
        when (val parsed = DocxSpecParser.parse(args)) {
            is Step.Bad -> ToolOutcome.Failed(parsed.reason)
            is Step.Ok -> withImages(parsed.value, workspaceRoot)
        }

    private fun withImages(spec: DocxSpec, root: File): ToolOutcome =
        when (val images = imagesOf(spec.blocks, root)) {
            is Step.Bad -> ToolOutcome.Failed(images.reason)
            is Step.Ok -> write(spec, images.value, root)
        }

    private fun write(spec: DocxSpec, images: Map<String, OfficeImage>, root: File): ToolOutcome {
        val bytes = DocxWriter.build(spec, images)
        val base = OfficeSink.baseOf(spec.fileBase, FALLBACK_BASE)
        return when (val done = OfficeSink.write(root, base, "docx", bytes, TimeFormats.fileStamp())) {
            is Step.Bad -> ToolOutcome.Failed(done.reason)
            is Step.Ok -> {
                val shown = OfficeSink.record(NAME, root, done.value, "生成 docx")
                ToolOutcome.Ok("已写出 $shown（${PathBatch.sizeText(done.value.length())}；${spec.counts()}）")
            }
        }
    }

    /** 逐张读图（**先读先验**：任何一张不成，整次调用失败，不产半份文档）。 */
    private fun imagesOf(blocks: List<DocxBlock>, root: File): Step<Map<String, OfficeImage>> {
        val pictures = blocks.filterIsInstance<DocxBlock.Picture>()
        if (pictures.size > OfficeLimits.DOCX_MAX_IMAGES) {
            return Step.Bad("图片 ${pictures.size} 张，超过上限 ${OfficeLimits.DOCX_MAX_IMAGES} 张（没有静默丢弃）")
        }
        val out = LinkedHashMap<String, OfficeImage>()
        for (pic in pictures) {
            when (val one = readImage(root, pic.path)) {
                is Step.Bad -> return one
                is Step.Ok -> out[pic.path] = one.value
            }
        }
        return Step.Ok(out)
    }

    private fun readImage(root: File, raw: String): Step<OfficeImage> {
        val file = UserPath.resolve(root, raw)
        if (!file.isFile) return Step.Bad("图片读不到：$raw（要看路径对不对；相对路径相对工作区）")
        if (file.length() > OfficeLimits.DOCX_MAX_IMAGE_BYTES) {
            return Step.Bad("图片 $raw 有 ${PathBatch.sizeText(file.length())}，超过 20MB 上限；请先压缩")
        }
        val bytes = runCatching { file.readBytes() }.getOrNull()
            ?: return Step.Bad("图片读不出来：$raw（权限或 IO 错误）")
        val info = ImageProbe.probe(bytes)
            ?: return Step.Bad("只支持 PNG/JPEG 图片（$raw 不是这两种）；webp/heic 请先转成 PNG")
        return Step.Ok(OfficeImage(bytes, info))
    }
}
