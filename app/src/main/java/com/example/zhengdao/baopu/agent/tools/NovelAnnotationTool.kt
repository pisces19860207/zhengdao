// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百零九批 · novel_annotations（读，R0）＋ novel_annotation_reply（写，R1 必问，默认关）。
// 格式约定：根是数组，或对象带 threads 数组；线程 {id,target?,quote?,status?,comments?}；
// 评论 {author?,at?,text}。status 缺省 open；读只返回批注结构，不读正文。
package com.example.zhengdao.baopu.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal object NovelAnnotationsTool {

    const val ARG_PATH = "path"

    fun spec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = "novel_annotations",
        description = "读小说批注线程（*.ajson5，含状态），返回 {id,target?,quote?,status,comments} 列表（JSON）。" +
            "约定位置：$NOVEL_ROOT_DIR/<书名>/${NovelAssets.ANNOTATIONS_DIR}/ 或 资料库/${NovelAssets.ANNOTATIONS_DIR}/；" +
            "path 省略＝全收，也可指定文件或目录。只读批注结构不读正文；坏文件报「第 N 行」。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_PATH to jsonObjectOf("type" to "string", "description" to "批注文件或目录（围栏内）；省略＝约定位置全收"),
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
        val files = when (val s = NovelAssets.scope(root, path, NovelAssets.ANNOTATIONS_DIR, NovelAssets.ANNOTATIONS_EXT)) {
            is NovelAssets.Scope.Bad -> return s.toOutcome()
            is NovelAssets.Scope.Files -> s.files
        }
        val out = JSONArray()
        for (f in files) {
            val loaded = NovelAssets.load(f)
            if (loaded is NovelAssets.Loaded.Bad) return ToolOutcome.Failed(loaded.message)
            val ok = loaded as NovelAssets.Loaded.Ok
            val arr = NovelAssets.rootArray(ok.root, "threads")
                ?: return ToolOutcome.Failed("${f.name} 第 ${ok.root.line} 行：根要是数组，或对象里带 threads 数组")
            for (item in arr.items) {
                val thread = threadOf(f, item)
                if (thread is ThreadResult.Bad) return ToolOutcome.Failed(thread.message)
                out.put((thread as ThreadResult.Ok).json)
            }
        }
        return ToolOutcome.Ok("批注线程 ${out.length()} 条（${files.size} 个文件）：\n${out.toString(2)}")
    }

    private sealed class ThreadResult {
        class Ok(val json: JSONObject) : ThreadResult()
        class Bad(val message: String) : ThreadResult()
    }

    private fun threadOf(f: File, item: NovelJson5.J5Node): ThreadResult {
        val o = NovelJson5.obj(item)
            ?: return ThreadResult.Bad("${f.name} 第 ${item.line} 行：批注线程要是对象 {id,target?,quote?,status?,comments?}")
        val id = NovelJson5.str(o["id"])?.trim().orEmpty()
        if (id.isEmpty()) return ThreadResult.Bad("${f.name} 第 ${item.line} 行：批注线程缺必填 id（非空字符串）")
        val j = JSONObject().put("id", id).put("status", NovelJson5.str(o["status"])?.trim()?.ifEmpty { "open" } ?: "open")
        NovelJson5.str(o["target"])?.let { j.put("target", it) }
        NovelJson5.str(o["quote"])?.let { j.put("quote", it) }
        val comments = JSONArray()
        val cArr = NovelJson5.arr(o["comments"])
        if (o["comments"] != null && cArr == null) {
            return ThreadResult.Bad("${f.name} 第 ${o["comments"]!!.line} 行：comments 要是数组")
        }
        for (c in cArr?.items.orEmpty()) {
            val co = NovelJson5.obj(c)
                ?: return ThreadResult.Bad("${f.name} 第 ${c.line} 行：评论要是对象 {author?,at?,text}")
            val text = NovelJson5.str(co["text"])?.trim().orEmpty()
            if (text.isEmpty()) return ThreadResult.Bad("${f.name} 第 ${c.line} 行：评论缺必填 text（非空字符串）")
            comments.put(
                JSONObject()
                    .put("author", NovelJson5.str(co["author"])?.trim()?.ifEmpty { "（无名）" } ?: "（无名）")
                    .put("at", NovelJson5.str(co["at"]).orEmpty())
                    .put("text", text),
            )
        }
        j.put("comments", comments)
        return ThreadResult.Ok(j)
    }
}

// ── novel_annotation_reply（写，R1 必问，默认关） ────────────────────────

internal object NovelAnnotationReplyTool {

    const val ARG_PATH = "path"
    const val ARG_THREAD = "thread"
    const val ARG_TEXT = "text"
    const val ARG_AUTHOR = "author"

    fun spec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = "novel_annotation_reply",
        description = "给批注文件（*.ajson5）里某个线程追加一条回复 {author,at,text}：" +
            "只追加，已有字节一个不动；线程不存在报错不猜；文件不存在报错（先建批注文件）。" +
            "path 必填且限 $NOVEL_ROOT_DIR/、资料库/ 内（资料库/原始/ 只读不许写）。" +
            "默认关闭，需在 抱朴设置→抱朴工具 里显式打开；每次回复都要用户确认。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_PATH to jsonObjectOf("type" to "string", "description" to "目标 .ajson5 文件（围栏内相对路径）"),
                ARG_THREAD to jsonObjectOf("type" to "string", "description" to "批注线程 id（必填，须已存在）"),
                ARG_TEXT to jsonObjectOf("type" to "string", "description" to "回复内容（必填非空）"),
                ARG_AUTHOR to jsonObjectOf("type" to "string", "description" to "署名（可空，默认 抱朴）"),
            ),
            "required" to jsonArrayOf(ARG_PATH, ARG_THREAD, ARG_TEXT),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.CONFIRM,
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args -> reply(workspaceRoot(), args) }

    fun reply(root: File, args: JSONObject): ToolOutcome {
        val threadId = args.optString(ARG_THREAD).trim()
        val text = args.optString(ARG_TEXT).trim()
        if (threadId.isEmpty()) return ToolOutcome.Failed("线程 thread 不能为空")
        if (text.isEmpty()) return ToolOutcome.Failed("回复 text 不能为空")
        val author = args.optString(ARG_AUTHOR).trim().ifEmpty { "抱朴" }
        val v = NovelFence.resolve(root, args.optString(ARG_PATH), forWrite = true)
        if (v is NovelFence.Verdict.Denied) return ToolOutcome.Rejected(v.reason)
        val file = (v as NovelFence.Verdict.Ok).file
        if (!file.isFile) return ToolOutcome.Failed("批注文件不存在：${file.name}（reply 只追加到已有线程；请先建批注文件）")
        val loaded = NovelAssets.load(file)
        if (loaded is NovelAssets.Loaded.Bad) return ToolOutcome.Failed(loaded.message)
        val ok = loaded as NovelAssets.Loaded.Ok
        val arr = NovelAssets.rootArray(ok.root, "threads")
            ?: return ToolOutcome.Failed("${file.name} 第 ${ok.root.line} 行：根要是数组，或对象里带 threads 数组")
        val thread = findThread(arr, threadId)
            ?: return ToolOutcome.Failed("${file.name}：没有批注线程「$threadId」（现有：${threadIds(arr)}；不猜不新建）")
        val elem = elemJson(author, text)
        val comments = NovelJson5.arr(thread["comments"])
        if (thread["comments"] != null && comments == null) {
            return ToolOutcome.Failed("${file.name} 第 ${thread["comments"]!!.line} 行：comments 要是数组，先修好再回复")
        }
        val newText = if (comments != null) {
            NovelAssets.appendToArray(ok.text, comments, elem)
        } else {
            NovelAssets.insertKey(ok.text, thread, "comments", "[$elem]")
        }
        if (!NovelAssets.atomicWrite(file, newText)) return ToolOutcome.Failed("写不了 ${file.name}（权限或 IO 错误），未改动")
        return ToolOutcome.Ok("已给线程「$threadId」追加回复（${file.name}，已有内容未动）")
    }

    private fun findThread(arr: NovelJson5.J5Arr, id: String): NovelJson5.J5Obj? =
        arr.items.firstNotNullOfOrNull { item ->
            NovelJson5.obj(item)?.takeIf { NovelJson5.str(it["id"])?.trim() == id }
        }

    private fun threadIds(arr: NovelJson5.J5Arr): String =
        arr.items.mapNotNull { NovelJson5.str(NovelJson5.obj(it)?.get("id")) }.joinToString("、").ifEmpty { "（空）" }

    private fun elemJson(author: String, text: String): String =
        "{\"author\":${NovelJson5.quote(author)},\"at\":${NovelJson5.quote(java.time.Instant.now().toString())}," +
            "\"text\":${NovelJson5.quote(text)}}"
}