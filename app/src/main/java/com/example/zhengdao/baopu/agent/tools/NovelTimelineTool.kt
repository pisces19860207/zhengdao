// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百零九批 · novel_timeline（读，R0）＋ novel_timeline_append（写，R1 必问，默认关）。
// 格式约定：根是数组，或对象带 events 数组；条目 {id?,name,at,parent?}。
// at 建议用可排序写法（ISO 日期/章节号），区间过滤按字符串序。
package com.example.zhengdao.baopu.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal object NovelTimelineTool {

    const val ARG_PATH = "path"
    const val ARG_FROM = "from"
    const val ARG_TO = "to"

    fun spec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = "novel_timeline",
        description = "读小说时间线（*.tjson5），返回 {id,name,at,parent?,children?} 列表（JSON）。" +
            "约定位置：$NOVEL_ROOT_DIR/<书名>/${NovelAssets.TIMELINE_DIR}/ 或 资料库/${NovelAssets.TIMELINE_DIR}/；" +
            "path 省略＝全收。from/to 按 at 的字符串序做闭区间过滤（at 建议 ISO 日期或章节号）。" +
            "条目缺 id 时按文件内序号补 t1/t2…；只读结构不读正文；坏文件报「第 N 行」。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_PATH to jsonObjectOf("type" to "string", "description" to "时间线文件或目录（围栏内）；省略＝约定位置全收"),
                ARG_FROM to jsonObjectOf("type" to "string", "description" to "区间起点（含），按 at 字符串序；可空"),
                ARG_TO to jsonObjectOf("type" to "string", "description" to "区间终点（含），按 at 字符串序；可空"),
            ),
            "required" to jsonArrayOf(),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.FREE,
        readOnly = true,
        parallelSafe = true,
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args ->
        read(workspaceRoot(), args.optString(ARG_PATH), args.optString(ARG_FROM), args.optString(ARG_TO))
    }

    /** 一条事件（parent 原样保留；children 输出时按 id/名字互指算）。 */
    internal data class Ev(val id: String, val name: String, val at: String, val parent: String?)

    fun read(root: File, path: String, from: String, to: String): ToolOutcome {
        val files = when (val s = NovelAssets.scope(root, path, NovelAssets.TIMELINE_DIR, NovelAssets.TIMELINE_EXT)) {
            is NovelAssets.Scope.Bad -> return s.toOutcome()
            is NovelAssets.Scope.Files -> s.files
        }
        val all = mutableListOf<Ev>()
        for (f in files) {
            val (events, error) = eventsOf(f)
            if (error != null) return ToolOutcome.Failed(error)
            all += events
        }
        val lo = from.trim()
        val hi = to.trim()
        val picked = all.filter { (lo.isEmpty() || it.at >= lo) && (hi.isEmpty() || it.at <= hi) }
        val note = if (lo.isEmpty() && hi.isEmpty()) "" else "（区间过滤后）"
        return ToolOutcome.Ok("时间线 ${picked.size} 条$note（${files.size} 个文件）：\n${outputJson(picked).toString(2)}")
    }

    /** 读一个文件的全部事件；坏文件/缺必填 ⇒ 错误带「文件名 第 N 行」。 */
    private fun eventsOf(f: File): Pair<List<Ev>, String?> {
        val loaded = NovelAssets.load(f)
        if (loaded is NovelAssets.Loaded.Bad) return emptyList<Ev>() to loaded.message
        val ok = loaded as NovelAssets.Loaded.Ok
        val arr = NovelAssets.rootArray(ok.root, "events")
            ?: return emptyList<Ev>() to "${f.name} 第 ${ok.root.line} 行：根要是数组，或对象里带 events 数组"
        val out = mutableListOf<Ev>()
        for ((idx, item) in arr.items.withIndex()) {
            val o = NovelJson5.obj(item)
                ?: return emptyList<Ev>() to "${f.name} 第 ${item.line} 行：事件条目要是对象 {id?,name,at,parent?}"
            val ev = eventOf(o, "t${idx + 1}")
                ?: return emptyList<Ev>() to "${f.name} 第 ${item.line} 行：事件缺必填 name/at（都要非空）"
            out += ev
        }
        return out to null
    }

    /** 条目 → 事件；name/at 缺 ⇒ null。id 缺 ⇒ [fallbackId]；at 收字符串或数。 */
    private fun eventOf(o: NovelJson5.J5Obj, fallbackId: String): Ev? {
        val name = NovelJson5.str(o["name"])?.trim().orEmpty()
        val at = (NovelJson5.str(o["at"]) ?: j5NumText(o["at"]))?.trim().orEmpty()
        if (name.isEmpty() || at.isEmpty()) return null
        val id = (NovelJson5.str(o["id"]) ?: j5NumText(o["id"]))?.trim().orEmpty().ifEmpty { fallbackId }
        val parent = NovelJson5.str(o["parent"])?.trim()?.takeIf { it.isNotEmpty() }
        return Ev(id, name, at, parent)
    }

    /** children：parent 先按 id 认、认不出再按名字认；只计本次选中的事件。 */
    private fun outputJson(events: List<Ev>): JSONArray {
        val kids = mutableMapOf<String, MutableList<String>>()
        for (e in events) {
            val p = e.parent ?: continue
            val target = events.firstOrNull { it.id == p } ?: events.firstOrNull { it.name == p } ?: continue
            kids.getOrPut(target.id) { mutableListOf() } += e.id
        }
        val out = JSONArray()
        for (e in events) {
            val j = JSONObject().put("id", e.id).put("name", e.name).put("at", e.at)
            e.parent?.let { j.put("parent", it) }
            kids[e.id]?.let { j.put("children", JSONArray(it)) }
            out.put(j)
        }
        return out
    }
}

// ── novel_timeline_append（写，R1 必问，默认关） ─────────────────────────

/** 数 → 文本：整数值不带小数点（读与冲突判定同一口径，id/at 都可能是数）。 */
private fun j5NumText(n: NovelJson5.J5Node?): String? {
    val d = (n as? NovelJson5.J5Num)?.value ?: return null
    return if (d == kotlin.math.floor(d) && d.isFinite()) d.toLong().toString() else d.toString()
}

internal object NovelTimelineAppendTool {

    const val ARG_PATH = "path"
    const val ARG_NAME = "name"
    const val ARG_AT = "at"
    const val ARG_ID = "id"
    const val ARG_PARENT = "parent"

    fun spec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = "novel_timeline_append",
        description = "往时间线文件（*.tjson5）追加一条事件 {id?,name,at,parent?}：" +
            "只在末尾追加，已有字节一个不动；同名/同 id 事件报冲突不写；文件不存在则新建。" +
            "path 必填且限 $NOVEL_ROOT_DIR/、资料库/ 内（资料库/原始/ 只读不许写）。" +
            "默认关闭，需在 抱朴设置→抱朴工具 里显式打开；每次追加都要用户确认。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_PATH to jsonObjectOf("type" to "string", "description" to "目标 .tjson5 文件（围栏内相对路径）"),
                ARG_NAME to jsonObjectOf("type" to "string", "description" to "事件名（必填；与已有同名 ⇒ 冲突不写）"),
                ARG_AT to jsonObjectOf("type" to "string", "description" to "时间（必填；建议 ISO 日期或章节号，可排序）"),
                ARG_ID to jsonObjectOf("type" to "string", "description" to "事件 id（可空；与已有同 id ⇒ 冲突不写）"),
                ARG_PARENT to jsonObjectOf("type" to "string", "description" to "父事件 id 或名字（可空）"),
            ),
            "required" to jsonArrayOf(ARG_PATH, ARG_NAME, ARG_AT),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.CONFIRM,
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args -> append(workspaceRoot(), args) }

    fun append(root: File, args: JSONObject): ToolOutcome {
        val name = args.optString(ARG_NAME).trim()
        val at = args.optString(ARG_AT).trim()
        if (name.isEmpty()) return ToolOutcome.Failed("事件名 name 不能为空")
        if (at.isEmpty()) return ToolOutcome.Failed("时间 at 不能为空")
        val id = args.optString(ARG_ID).trim()
        val parent = args.optString(ARG_PARENT).trim()
        val v = NovelFence.resolve(root, args.optString(ARG_PATH), forWrite = true)
        if (v is NovelFence.Verdict.Denied) return ToolOutcome.Rejected(v.reason)
        val file = (v as NovelFence.Verdict.Ok).file
        val elem = elemJson(id, name, at, parent)
        if (!file.exists()) return create(file, elem, name)
        val loaded = NovelAssets.load(file)
        if (loaded is NovelAssets.Loaded.Bad) return ToolOutcome.Failed(loaded.message)
        val ok = loaded as NovelAssets.Loaded.Ok
        val arr = NovelAssets.rootArray(ok.root, "events")
            ?: return ToolOutcome.Failed("${file.name} 第 ${ok.root.line} 行：根要是数组，或对象里带 events 数组")
        conflict(file, arr, name, id)?.let { return ToolOutcome.Failed(it) }
        val newText = NovelAssets.appendToArray(ok.text, arr, elem)
        if (!NovelAssets.atomicWrite(file, newText)) return ToolOutcome.Failed("写不了 ${file.name}（权限或 IO 错误），未改动")
        return ToolOutcome.Ok("已追加事件「$name」到 ${file.name}（现 ${arr.items.size + 1} 条，已有内容未动）")
    }

    private fun create(file: File, elem: String, name: String): ToolOutcome {
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            return ToolOutcome.Failed("建不了目录 ${parent.name}（权限或 IO 错误）")
        }
        if (!NovelAssets.atomicWrite(file, "[\n  $elem\n]\n")) {
            return ToolOutcome.Failed("写不了 ${file.name}（权限或 IO 错误）")
        }
        return ToolOutcome.Ok("已新建 ${file.name} 并写入事件「$name」（第 1 条）")
    }

    /** 同名/同 id 冲突 ⇒ 带行号的可读拒绝；已有条目坏 ⇒ 同样报行号（不猜）。 */
    private fun conflict(file: File, arr: NovelJson5.J5Arr, name: String, id: String): String? {
        for (item in arr.items) {
            val o = NovelJson5.obj(item)
                ?: return "${file.name} 第 ${item.line} 行：已有事件条目不是对象，先修好再追加"
            val n = NovelJson5.str(o["name"])?.trim().orEmpty()
            if (n.isEmpty()) return "${file.name} 第 ${item.line} 行：已有事件缺 name，先修好再追加"
            if (n == name) return "${file.name} 第 ${item.line} 行：已有同名事件「$name」（不追加；要改已有请用 edit_file）"
            if (id.isNotEmpty() && (NovelJson5.str(o["id"]) ?: j5NumText(o["id"]))?.trim() == id) {
                return "${file.name} 第 ${item.line} 行：已有同 id 事件「$id」（不追加）"
            }
        }
        return null
    }

    /** 新元素文本（手拼保证字段序稳定；标准 JSON 即合法 JSON5）。 */
    private fun elemJson(id: String, name: String, at: String, parent: String): String = buildString {
        append('{')
        if (id.isNotEmpty()) append("\"id\":").append(NovelJson5.quote(id)).append(',')
        append("\"name\":").append(NovelJson5.quote(name))
        append(",\"at\":").append(NovelJson5.quote(at))
        if (parent.isNotEmpty()) append(",\"parent\":").append(NovelJson5.quote(parent))
        append('}')
    }
}