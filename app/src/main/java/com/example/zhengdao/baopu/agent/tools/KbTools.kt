// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单-2026-10-21-第一百二十八批-知识库v2（工具两件套：建索引 ＋ 检索；
// 红线：不上云、不自动读全盘、不接外部向量库）。设计页 §5/§7/§8。
//
// 硬纪律（逐条对应派工单与设计页）：
// 1. `kb_index` 的目录**必须显式**（缺省＝资料库/投递，一个明确子目录，绝不是工作区
//    根）——负例 N3「全盘自动扫描」钉的就是这条；越界（..／绝对路径／盘符）一律拒；
// 2. `kb_search` **只读**：只读索引文件，一个用户字节都不碰；没有索引/索引损坏时
//    明说"先 kb_index 重建"，不抛异常、不阻塞对话；
// 3. 检索无结果**明说"没找到"**，不塞不相关片段冒充（负例 N2 的行为侧）；
// 4. 输出封顶走 `BrowserTools.MAX_TEXT_CHARS` 同口径，超了明说还有几段；
// 5. `store` 由装配层（`BaopuRuntime.registry`）注入——Context 只能出现在那一层
//    （房型同 `AskUserTool.sink`／`PlanTool.sink`）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.kb.KbAutoInject
import com.example.zhengdao.baopu.kb.KbIndexStore
import com.example.zhengdao.baopu.kb.KbIndexer
import com.example.zhengdao.baopu.kb.KbRetriever
import com.example.zhengdao.baopu.library.LibraryPaths
import com.example.zhengdao.rootfs.PathGuard
import java.io.File

/**
 * 知识库 v2 两件套（第一百二十八批）：`kb_index` 建/增量更新本地索引；
 * `kb_search` 关键词检索带来源引用。
 *
 * 为什么并入一个 object：两个工具共用同一份索引存储（[store]）、同一套目录判据
 * （缺省目录、越界拒）与同一套"没索引"的话术，拆开必然各写一份、各漂一份。
 */
internal object KbTools {

    const val NAME_INDEX = "kb_index"
    const val NAME_SEARCH = "kb_search"

    const val ARG_PATH = "path"
    const val ARG_QUERY = "query"
    const val ARG_TOP_K = "top_k"
    const val ARG_REBUILD = "rebuild"

    /**
     * 缺省索引目录（相对工作区根）＝ `资料库/投递`。
     *
     * **绝不是工作区根**：根上有 `_索引.md`、素材库等一堆非资料文件，扫了必误报
     * （负例 N3：把缺省改成根 ⇒ `KbToolsTest` 红）。要索引别处，模型/用户显式给 path。
     */
    val DEFAULT_DIR_REL: String = LibraryPaths.DIR_LIBRARY + "/" + LibraryPaths.SUB_DELIVER

    /**
     * 索引存储壳（装配层注入；JVM 单测直接赋值）。
     * 没有注入 ⇒ 两个工具都明说"索引没初始化"，不猜、不崩。
     */
    @Volatile
    var store: KbIndexStore? = null

    // ── kb_index（建/增量更新索引；写的是 App 私有缓存，但会读整个目录 ⇒ CONFIRM） ──

    fun indexSpec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = NAME_INDEX,
        description = "给一个工作区内目录建本地知识库索引（只写 App 私有索引文件，不碰你的原文件）：" +
            "支持 txt/md/html/docx（pdf/图片/压缩包跳过并计数）。缺省索引 资料库/投递；" +
            "默认增量（按 大小+修改时间 只重切变更的文件），rebuild=true 全量重切。" +
            "建完用 kb_search 检索。执行前会弹一次确认卡（告诉你建的是哪个目录）。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_PATH to jsonObjectOf(
                    "type" to "string",
                    "description" to "要索引的目录（相对工作区根，如 资料库/投递；缺省就是它）",
                ),
                ARG_REBUILD to jsonObjectOf(
                    "type" to "boolean",
                    "description" to "true＝全量重切（默认 false＝增量）",
                ),
            ),
            // 全可选（两个参数都有默认值）⇒ 空 required 数组，原因签字在
            // ToolSchemaRules.REQUIRED_EMPTY_OK（119 批口径：不许缺这段，也不许不登记）
            "required" to jsonArrayOf(),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.CONFIRM,
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args -> index(args, workspaceRoot()) }

    /**
     * 建索引执行器。
     *
     * @param root 工作区根（守卫与缺省目录的基准）
     */
    fun index(args: org.json.JSONObject, root: File): ToolOutcome {
        val store = store ?: return ToolOutcome.Failed("知识库索引没初始化（App 内部状态异常），这轮不建索引")
        val rel = args.optString(ARG_PATH).trim().ifEmpty { DEFAULT_DIR_REL }
        val dir = resolveUnder(root, rel)
            ?: return ToolOutcome.Rejected(
                "路径越界被拒：$rel 形态不合法（不许 ..／绝对路径／盘符）或不在工作区内",
            )
        if (!dir.isDirectory) {
            return ToolOutcome.Failed(
                "目录不存在：$rel（先用 list_dir 看清结构；缺省目录是 $DEFAULT_DIR_REL）",
            )
        }
        val rebuild = args.optBoolean(ARG_REBUILD, false)
        val previous = if (rebuild) null else store.load()
        val result = KbIndexer.build(dir, label = rel, previous = previous, rebuild = rebuild)
        if (!store.save(result.index)) {
            return ToolOutcome.Failed("索引写不进去（App 私有目录 IO 错误）；你的文件一个都没动")
        }
        return ToolOutcome.Ok(result.report)
    }

    // ── kb_search（只读检索；没索引/损坏 ⇒ 明说先重建，不抛不阻塞） ──────────────

    fun searchSpec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = NAME_SEARCH,
        description = "在本地知识库索引里按关键词检索（只读，一个字节都不动）：返回最相关的片段，" +
            "每段带来源（文件＋行号）。没有索引或索引损坏时会告诉你要先 kb_index。" +
            "默认返回 ${KbRetriever.DEFAULT_TOP_K} 段，最多 ${KbRetriever.MAX_TOP_K} 段。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_QUERY to jsonObjectOf(
                    "type" to "string",
                    "description" to "要查的关键词（中文按字词、英文按单词匹配）",
                ),
                ARG_TOP_K to jsonObjectOf(
                    "type" to "integer",
                    "minimum" to 1,
                    "maximum" to KbRetriever.MAX_TOP_K,
                    "description" to "返回段数上限（默认 ${KbRetriever.DEFAULT_TOP_K}）",
                ),
            ),
            "required" to jsonArrayOf(ARG_QUERY),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.FREE,
        readOnly = true,
        parallelSafe = true, // 只读索引文件：无共享可变状态，可与别的读并行
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args -> search(args) }

    /** 检索执行器（不需要工作区根——索引文件在 App 私有目录，与工作区无关）。 */
    fun search(args: org.json.JSONObject): ToolOutcome {
        val store = store ?: return ToolOutcome.Failed("知识库索引没初始化（App 内部状态异常），这轮不检索")
        val query = args.optString(ARG_QUERY).trim()
        if (query.isEmpty()) return ToolOutcome.Failed("缺少必填参数「$ARG_QUERY」（要查的关键词）")
        val index = store.loadCached()
            ?: return ToolOutcome.Failed(
                "本地索引还没有或已损坏：先用 kb_index 给一个工作区内目录建索引" +
                    "（缺省 $DEFAULT_DIR_REL）。索引只存在本机，可随时重建。",
            )
        val topK = args.optInt(ARG_TOP_K, KbRetriever.DEFAULT_TOP_K).coerceIn(1, KbRetriever.MAX_TOP_K)
        val result = KbRetriever.search(index, query, topK)
        if (result.hits.isEmpty()) {
            return ToolOutcome.Ok(
                "（没找到）：索引里没有和「$query」相关的内容——${index.rootLabel} 下 " +
                    "${index.docs.size} 个文件都查过了。换个词，或先 kb_index 索引别的目录。",
            )
        }
        val sb = StringBuilder()
        sb.append("知识库检索「").append(query).append("」（索引目录：").append(index.rootLabel)
            .append("，命中 ").append(result.hits.size).append(" 段，按相关度排序）：\n")
        var shown = 0
        for (h in result.hits) {
            val frag = KbRetriever.renderFragment(h.chunk)
            // 封顶前先量长（同 library_list 口径：封顶外的段一个字节都不拼）
            if (shown > 0 && sb.length + frag.length + 1 > BrowserTools.MAX_TEXT_CHARS) break
            sb.append(frag).append('\n')
            shown++
        }
        val rest = result.hits.size - shown
        if (rest > 0) {
            sb.append("…还有 ").append(rest).append(" 段没列出（输出封顶 ")
                .append(BrowserTools.MAX_TEXT_CHARS).append(" 字符）——换更具体的词，或用 top_k 调段数\n")
        }
        sb.append("（引用请按上面「来源」标注；没列出的内容不要编造来源）")
        return ToolOutcome.Ok(sb.toString().trimEnd())
    }

    // ── 内部工具 ─────────────────────────────────────────────────────────────

    /**
     * 把相对工作区根的路径解析成目录（越界 ⇒ null）。
     * 复用 [LibraryPaths.destinationUnder] 的危险形态检查（前导 `/`、`..`、盘符、
     * 空段）＋ [PathGuard.isInside] 组件级双保险——不另起一套路径判据。
     *
     * ⚠️ **不自己剥前导 `/`**：剥了再送检，等于把"绝对路径即拒"这条判据拆了
     * （`/资料库` 会变成相对的 `资料库` 被收下）——交给 destinationUnder 原样拒。
     */
    internal fun resolveUnder(root: File, rel: String): File? {
        val cleaned = rel.replace('\\', '/').trim()
        if (cleaned.isEmpty()) return null
        if (cleaned.contains(":")) return null
        return LibraryPaths.destinationUnder(root, cleaned)
    }

    /** 自动注入的入口（装配层给引擎用：索引在 ⇒ 一节 note；不在 ⇒ null）。 */
    fun autoInjectNote(userText: String): String? {
        val store = store ?: return null
        return runCatching { KbAutoInject.note(store, userText) }.getOrNull()
    }
}
