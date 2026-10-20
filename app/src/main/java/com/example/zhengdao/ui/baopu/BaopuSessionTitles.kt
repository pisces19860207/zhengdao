// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第七十二批（§B 80 结构切片 · 纯逻辑抽取）：从 `BaopuChatController.loadSessionLibrary`
// 尾部抽出的**标题兜底**（§2.1.2）——空标题会话取首条用户消息前 20 字。
//
// 为什么抽它：这是一条「显示口径」判据，跟 SQL 无关，却夹在会话库读取里；抽出来才能
// 直接钉住「有标题绝不被覆盖」「取不到就留空、由 UI 显示未命名」两条边界。**行为零变化**。
package com.example.zhengdao.ui.baopu

internal object BaopuSessionTitles {

    /** 需要补查首条用户消息的会话 id（标题为空/全空白的那批；保序）。 */
    fun blankIds(rows: List<BaopuSessionEntry>): List<Long> =
        rows.filter { it.title.isBlank() }.map { it.id }

    /**
     * 标题兜底：**只改空标题**，有标题的一行原样返回（连对象都不换）。
     *
     * 取不到首条正文 ⇒ 留空串（由 UI 显示「未命名会话」）——**不许**在这里编一个名字，
     * 也不许拿当前工作区补（K3 同一口径：库里没记就是没记）。
     */
    fun withFallback(
        rows: List<BaopuSessionEntry>,
        firstTexts: Map<Long, String>,
    ): List<BaopuSessionEntry> = rows.map { e ->
        if (e.title.isNotBlank()) {
            e
        } else {
            e.copy(title = firstTexts[e.id].orEmpty().trim().take(20))
        }
    }
}
