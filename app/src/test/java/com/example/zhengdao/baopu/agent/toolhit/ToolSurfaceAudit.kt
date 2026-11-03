// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 412 单 · 工具面「易混消歧」审计（a 类选错的**结构性**测量）。
//
// 为什么不改语料判定来测 a 类：语料里「模型调了哪个工具」是写死的构造假设，
// 改描述不会让它翻绿——拿它算前后对比就是自证。真正被描述改动**直接决定**、
// 且任何人都能复核的量是这一条：**易混对互指覆盖率**——A 的描述里是否点名了
// 它的易混对手 B（「做 X 用 B」）。修前 0/15，修后必须 15/15，这条门禁钉死不回退。
package com.example.zhengdao.baopu.agent.toolhit

import com.example.zhengdao.baopu.agent.ToolRegistry

/**
 * 已认定的易混对（方向 = 谁的描述里必须点名谁）。
 *
 * 每一条都有出处（412 报告 §Top-10 表）：要么真机里真的发生过（move_path×5、
 * update_plan 措辞改动），要么两个工具的意图空间肉眼重叠（write↔edit、
 * sheet_read↔table_query）。新增易混对 ⇒ 在这里加一行，门禁立刻会要求互指。
 */
internal val CONFUSABLE_PAIRS: List<Pair<String, String>> = listOf(
    "write_file" to "edit_file",       // 新建/整篇 vs 按行改
    "edit_file" to "write_file",       // 反向：文件不存在时别用 edit
    "read_file" to "edit_file",        // 行号口径差一（真机回显为证）
    "search_in_files" to "find_files", // 搜内容 vs 按文件名找
    "list_dir" to "find_files",        // 列一层 vs 深度找
    "move_path" to "bulk_move",        // 单个 vs 一批（真机：逐个 move×5）
    "bulk_move" to "move_path",        // 反向
    "copy_path" to "bulk_copy",        // 单个 vs 一批
    "sheet_read" to "table_query",     // 要原始行 vs 要答案
    "table_query" to "sheet_read",     // 反向
    "image_ops" to "generate_image",   // 改已有图 vs 从零生图
    "generate_image" to "image_ops",   // 反向
    "browser_type" to "form_fill",     // 单框 vs 整表
    "kb_search" to "search_in_files",  // 知识库索引 vs 文件内子串
    "search_web" to "search_in_files", // 联网 vs 本地
)

internal object ToolSurfaceAudit {

    /** 一条互指缺口。 */
    data class Gap(val from: String, val to: String)

    /**
     * 逐方向检查：`from` 的工具描述里必须出现 `to` 的名字。
     *
     * 判据就是字面包含——描述是模型唯一看得见的文字，名字不在里面就等于没指路。
     * 不查「写法是否优美」，只查「指没指」。
     */
    fun audit(registry: ToolRegistry, pairs: List<Pair<String, String>> = CONFUSABLE_PAIRS): List<Gap> =
        pairs.mapNotNull { (from, to) ->
            val spec = registry.find(from)
            when {
                spec == null -> Gap(from, to) // 工具没了 ⇒ 缺口（改名/删除要显式同步本表）
                spec.description.contains(to) -> null
                else -> Gap(from, to)
            }
        }
}
