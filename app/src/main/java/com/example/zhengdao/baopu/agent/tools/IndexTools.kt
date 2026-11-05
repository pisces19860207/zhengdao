// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：492 工单 B②「新增 index_browse(路径)；index_search 复用既有搜索」。
// 两件都是**索引的视图**（`search.db` 的账行，[PhoneSearch]／[IndexBrowse] 门面）：
// 不读盘建索引、不做第二把搜索算式（打分/切词全在 [PhoneRank]，这里只渲染）。
// 隐藏文件**不进索引**是既有判据（[SearchAdmission] 的 DOT 排除），所以两件都没有
// includeHidden 参数——要搜/读隐藏文件，用带 include_hidden 的 search_in_files/find_files
// 与 read_file（那是「搜盘/读盘」，不是「读索引」）。
//
// Context 纪律：工具层不认识 Context——运行环境由 [ctx] 在 BaopuRuntime 装配一次
// （房型同 DeviceTools.host／KbTools.store）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.FilePaths
import com.example.zhengdao.baopu.search.DocSummaryRules
import com.example.zhengdao.baopu.search.IndexBrowse
import com.example.zhengdao.baopu.search.PhoneSearch
import com.example.zhengdao.baopu.search.SearchSql
import java.io.File

internal object IndexTools {

    const val ARG_PATH = "path"
    const val ARG_QUERY = "query"
    const val ARG_LIMIT = "limit"

    /** 目录浏览默认/最大条数（[IndexBrowse] 的账行翻页口；上限对齐 list_dir 的尺度）。 */
    const val DEFAULT_DIR_ROWS = 100
    const val MAX_DIR_ROWS = 500

    /** 命中默认条数（既有检索的 SEARCH_LIMIT=40 就是天花板，不另立预算）。 */
    const val DEFAULT_HITS = 20

    /** Context 注入口（BaopuRuntime 唯一装配点；JVM 单测不碰它）。 */
    @Volatile
    var ctx: (() -> android.content.Context)? = null

    // ── index_browse：索引的目录/单篇卡 ─────────────────────────────────────

    fun browseSpec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = "index_browse",
        description = "看索引摘要（先有索引才有内容：搜索卡「扫描本机」建索引）。" +
            "给文件路径 ⇒ 摘要卡：标题、前 ${DocSummaryRules.FIRST_LINES} 行、结构块（文件:行）；" +
            "给目录路径（可省略＝工作区根）⇒ 子目录与「文件 —— 标题」清单。" +
            "路径相对工作区根或绝对路径；同名条目在多个根里都存在时会报错并列出候选。" +
            "要读全文用 read_file；按文件名找用 find_files。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_PATH to jsonObjectOf(
                    "type" to "string",
                    "description" to "文件或目录路径：相对工作区根，或全盘绝对路径（省略＝工作区根）",
                ),
                ARG_LIMIT to jsonObjectOf(
                    "type" to "integer",
                    "description" to "目录浏览最多列几行（默认 $DEFAULT_DIR_ROWS，最大 $MAX_DIR_ROWS）",
                    "minimum" to 1,
                    "maximum" to MAX_DIR_ROWS,
                ),
            ),
            "required" to jsonArrayOf(),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.FREE,
        readOnly = true,
        parallelSafe = true, // 纯库读：无共享可变状态，可与别的读并行
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args -> runBrowse(args, workspaceRoot) }

    // ── index_search：复用既有检索 ──────────────────────────────────────────

    fun searchSpec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = "index_search",
        description = "在索引里按词找文件（复用搜索卡那套倒排；先有索引才有结果）。" +
            "回「路径 + 分数 + 标题」，最多 40 条；词走分词（同 PhoneRank）。" +
            "索引只覆盖扫过的文件（隐藏文件不进索引）；要逐字搜盘上的原文用 search_in_files。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_QUERY to jsonObjectOf(
                    "type" to "string",
                    "description" to "要找的词（可给几个；走既有分词与打分）",
                ),
                ARG_LIMIT to jsonObjectOf(
                    "type" to "integer",
                    "description" to "最多回几条（默认 $DEFAULT_HITS，最大 ${SearchSql.SEARCH_LIMIT}）",
                    "minimum" to 1,
                    "maximum" to SearchSql.SEARCH_LIMIT,
                ),
            ),
            "required" to jsonArrayOf(ARG_QUERY),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.FREE,
        readOnly = true,
        parallelSafe = true, // 纯库读
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args -> runSearch(args, workspaceRoot) }

    // ── executor ────────────────────────────────────────────────────────────

    private fun env(workspaceRoot: () -> File): Pair<android.content.Context, File>? {
        val c = ctx?.invoke() ?: return null
        return c to workspaceRoot()
    }

    private fun runBrowse(args: org.json.JSONObject, workspaceRoot: () -> File): ToolOutcome {
        val e = env(workspaceRoot) ?: return ToolOutcome.Failed("索引环境没装配（这是装配层的事，不是参数错）")
        val (context, root) = e
        val raw = args.optString(ARG_PATH).trim()
        val target = if (raw.isEmpty()) root else File(raw)
        if (!target.exists()) {
            return ToolOutcome.Failed(
                "不存在：${target.name}（索引只收录扫过的文件；先用 list_dir 看真实结构）",
            )
        }
        val status = IndexBrowse.status(context)
        return if (target.isDirectory) {
            val cap = args.optInt(ARG_LIMIT, DEFAULT_DIR_ROWS).coerceIn(1, MAX_DIR_ROWS)
            val browse = IndexBrowse.dirBrowse(context, target, cap)
                ?: return ToolOutcome.Failed(
                    "「${FilePaths.display(root, target)}」不在任何已入账的根里" +
                        "（索引只覆盖多根工作区清单里的根与手机全盘档）",
                )
            ToolOutcome.Ok(IndexBrowse.renderDir(browse, status.line(), cap))
        } else {
            ToolOutcome.Ok(IndexBrowse.renderFile(IndexBrowse.fileCard(context, target), status.line()))
        }
    }

    private fun runSearch(args: org.json.JSONObject, workspaceRoot: () -> File): ToolOutcome {
        val e = env(workspaceRoot) ?: return ToolOutcome.Failed("索引环境没装配（这是装配层的事，不是参数错）")
        val (context, root) = e
        val query = args.optString(ARG_QUERY).trim()
        if (query.isEmpty()) return ToolOutcome.Failed("参数「$ARG_QUERY」为空（要给一个词）")
        val limit = args.optInt(ARG_LIMIT, DEFAULT_HITS).coerceIn(1, SearchSql.SEARCH_LIMIT)
        val result = PhoneSearch.query(context, query, limit)
        val status = IndexBrowse.status(context)
        val body = buildString {
            if (result.hits.isEmpty()) {
                append("（索引里没有含这些词的文件）")
                if (!status.dbExists) append("——索引还没建：搜索卡「扫描本机」之后再用 index_search。")
            } else {
                result.hits.forEach { h ->
                    val f = File(h.absRoot, h.relPath)
                    append(FilePaths.display(root, f))
                    append("（").append(h.score).append(" 分）")
                    if (h.title.isNotEmpty()) append(h.title)
                    append('\n')
                }
                append("命中 ${result.hits.size} 条（${result.termCount} 个词")
                if (result.termsTruncated) append("，只按前几个词查的")
                append("）")
            }
            append('\n').append(status.line())
            append("；索引只覆盖扫过的文件（隐藏文件不进索引）——逐字搜盘用 search_in_files。")
        }
        return ToolOutcome.Ok(body)
    }
}
