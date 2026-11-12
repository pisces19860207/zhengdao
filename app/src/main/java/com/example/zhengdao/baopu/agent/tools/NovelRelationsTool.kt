// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百零九批 · novel_relations（读，R0）：读 *.rjson5 关系表，只返回结构。
// 格式约定：根是数组，或对象带 relations 数组；条目 {from,to,kind,note?}。
package com.example.zhengdao.baopu.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal object NovelRelationsTool {

    const val ARG_PATH = "path"

    fun spec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = "novel_relations",
        description = "读小说关系表（*.rjson5），返回 {from,to,kind,note} 列表（JSON）。" +
            "约定位置：$NOVEL_ROOT_DIR/<书名>/${NovelAssets.RELATIONS_DIR}/ 或 " +
            "资料库/${NovelAssets.RELATIONS_DIR}/；path 省略＝全收，也可指定文件或目录（目录＝直接子文件）。" +
            "只读结构不读正文；坏文件报「第 N 行」，不猜。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_PATH to jsonObjectOf(
                    "type" to "string",
                    "description" to "关系文件或目录（围栏内：$NOVEL_ROOT_DIR/、资料库/）；省略＝约定位置全收",
                ),
            ),
            "required" to jsonArrayOf(),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.FREE,
        readOnly = true,
        parallelSafe = true,
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args -> read(workspaceRoot(), args.optString(ARG_PATH)) }

    fun read(root: File, path: String): ToolOutcome {
        val files = when (val s = NovelAssets.scope(root, path, NovelAssets.RELATIONS_DIR, NovelAssets.RELATIONS_EXT)) {
            is NovelAssets.Scope.Bad -> return s.toOutcome()
            is NovelAssets.Scope.Files -> s.files
        }
        val out = JSONArray()
        for (f in files) {
            val loaded = NovelAssets.load(f)
            if (loaded is NovelAssets.Loaded.Bad) return ToolOutcome.Failed(loaded.message)
            val ok = loaded as NovelAssets.Loaded.Ok
            val arr = NovelAssets.rootArray(ok.root, "relations")
                ?: return ToolOutcome.Failed("${f.name} 第 ${ok.root.line} 行：根要是数组，或对象里带 relations 数组")
            for (item in arr.items) {
                val o = NovelJson5.obj(item)
                    ?: return ToolOutcome.Failed("${f.name} 第 ${item.line} 行：关系条目要是对象 {from,to,kind,note?}")
                val rel = relationOf(o)
                    ?: return ToolOutcome.Failed("${f.name} 第 ${item.line} 行：关系缺必填 from/to/kind（都要非空字符串）")
                out.put(rel)
            }
        }
        return ToolOutcome.Ok("关系 ${out.length()} 条（${files.size} 个文件）：\n${out.toString(2)}")
    }

    /** 条目 → JSON；缺必填 ⇒ null（行号由调用方报）。note 可空。 */
    private fun relationOf(o: NovelJson5.J5Obj): JSONObject? {
        val from = NovelJson5.str(o["from"])?.trim().orEmpty()
        val to = NovelJson5.str(o["to"])?.trim().orEmpty()
        val kind = NovelJson5.str(o["kind"])?.trim().orEmpty()
        if (from.isEmpty() || to.isEmpty() || kind.isEmpty()) return null
        val j = JSONObject().put("from", from).put("to", to).put("kind", kind)
        NovelJson5.str(o["note"])?.let { j.put("note", it) }
        return j
    }
}

/** 范围拒绝 → 工具结果：围栏拒绝走 Rejected（管线自动记账），其余 Failed（可重试）。 */
internal fun NovelAssets.Scope.Bad.toOutcome(): ToolOutcome =
    if (denied) ToolOutcome.Rejected(message) else ToolOutcome.Failed(message)