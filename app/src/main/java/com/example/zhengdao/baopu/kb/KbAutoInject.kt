// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单-2026-10-21-第一百二十八批-知识库v2 §2（注入：按上下文预算注入片段，
// 不超限；无结果明确说"没找到"，不编造来源；索引损坏/缺失可重建，不阻塞对话）。
package com.example.zhengdao.baopu.kb

/**
 * 自动注入（第一百二十八批）——每轮用**本轮用户输入**过一遍本地索引，命中的片段
 * 按预算裁成一节拼进本轮输入末尾（引擎侧走 `extraNotes` 既有房型）。
 *
 * ## 三条边界（与工具 `kb_search` 的分工）
 * - **索引缺失/损坏 ⇒ 返回 null**（静默跳过）：不阻塞对话；模型真要用时
 *   `kb_search` 会告诉它"先 kb_index 建一个"；
 * - **无命中 ⇒ 返回 null**（不硬注）：自动注入是 best-effort 增强，"没找到"
 *   的明说口径属于显式检索动作 `kb_search`（那里必须明说）；
 * - **超预算 ⇒ 截断并明说**（"还有 N 段未注入"）：不许静默硬塞（负例 N4）。
 */
internal object KbAutoInject {

    /**
     * @param store 索引存储壳（装配层注入）
     * @param userText 本轮用户输入（检索查询词就是它，不另设查询）
     * @return 要拼进本轮输入的 note；不需要注入 ⇒ null
     */
    fun note(store: KbIndexStore, userText: String): String? {
        if (userText.isBlank()) return null
        val index = store.loadCached() ?: return null
        val result = KbRetriever.search(index, userText)
        if (result.hits.isEmpty()) return null
        val (picked, truncated) = KbRetriever.trimToBudget(result.hits, KbRetriever.INJECT_TOKEN_BUDGET)
        if (picked.isEmpty()) return null
        return buildString {
            append("【知识库】根据本轮提问，从本地索引（").append(index.rootLabel)
            append("，").append(index.docs.size).append(" 个文件）找到 ")
            append(result.hits.size).append(" 段相关内容")
            if (truncated) {
                append("，因每轮注入预算（").append(KbRetriever.INJECT_TOKEN_BUDGET)
                append(" tokens）只注入 ").append(picked.size).append(" 段")
            }
            append("：\n")
            picked.forEach { append(KbRetriever.renderFragment(it.chunk)).append('\n') }
            append("（回答请按上面「来源」标注出处；上面没有的内容不要编造来源）")
        }.trimEnd()
    }
}
