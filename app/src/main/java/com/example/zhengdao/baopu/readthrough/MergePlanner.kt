// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：第六百一十批（通读框架 · 分层归并）。硬规则：
// 1. 层级固定 片→章→块（每 100 章）→全书；章内片超 100、全书块超 100 ⇒ 就地再分层，
//    **任何一次归并调用的输入都 ≤ 100**——「一次合并 1700 片」在本规划器里结构上不存在；
// 2. 单片章不产生章层调用（章＝该片直通）——预算里不数没必要的调用；
// 3. 层数与调用数都是纯函数算出来的，预算预检（④）与 3000 章夹具（判据②）共用它。
package com.example.zhengdao.baopu.readthrough

/**
 * 一次归并调用。inputIds 是下一层产物（片 id / 章产物 id / 块产物 id）。
 *
 * @param layer 归并层（1 = 章层；2 = 块层（每 100 章）；3 = 全书层；>3 = 超长书/超长章再分层）
 * @param kind  CHAPTER＝章内片的归并；BLOCK＝每 100 章的归并；BOOK＝全书归并
 */
internal data class MergeCall(
    val layer: Int,
    val kind: String,
    val outputId: String,
    val inputIds: List<String>,
) {
    companion object {
        const val KIND_CHAPTER = "CHAPTER"
        const val KIND_BLOCK = "BLOCK"
        const val KIND_BOOK = "BOOK"
    }
}

internal data class MergePlan(
    val calls: List<MergeCall>,
    /** 总层数（含 L0 抽取层；正常书＝4：片→章→块→书；超长书/超长章会更大）。 */
    val layers: Int,
) {
    /** 单次归并最大输入数（判据「不许一次合并 1700 片」的机检数）。 */
    val maxInputs: Int get() = calls.maxOfOrNull { it.inputIds.size } ?: 0
}

/**
 * 归并规划器（**纯函数**）。
 *
 * 层级形状：
 * - 章层：每章的片 → 1 个章产物。片 >100 的章就地再分层（递归每组 100，全程 CHAPTER）；
 * - 块层：章产物按 **每 100 章** 一组 → 块（单层分组，不收拢到 1——收拢是书层的事）；
 * - 书层：块 → 1。块 >100 的书就地再分层（递归每组 100，全程 BOOK）。
 */
internal object MergePlanner {

    /** 单次归并的输入上限（「每 100 章」同源口径）。 */
    const val GROUP_SIZE = 100

    /**
     * 规划整本书的归并树。
     *
     * @param chapters 每章的（章号, 片数）——章号用**真实章号**（0 起；略读选中章可以不连续，
     *                 产物 id「c<章号>」必须与 [ReadSlice.id]、chapter jsonl 文件名同口径）
     * @return 归并调用清单（执行顺序＝清单顺序：先章层后块层再书层）
     */
    fun plan(chapters: List<Pair<Int, Int>>): MergePlan {
        if (chapters.isEmpty()) return MergePlan(emptyList(), 1)
        val calls = mutableListOf<MergeCall>()

        // 章层：每章的片 → 1 个章产物（单片的章直通，不出调用）
        val chapterIds = mutableListOf<String>()
        var maxChapterLayer = 1
        chapters.forEach { (ci, count) ->
            val sliceIds = (0 until count).map { si -> "c${ci}t${si}" }
            val (ids, depth) = groupLevel(sliceIds, 1, MergeCall.KIND_CHAPTER, "c$ci", calls)
            chapterIds += ids.first()
            maxChapterLayer = maxOf(maxChapterLayer, depth)
        }

        // 块层：每 100 章一个块（单层分组）
        val blockIds = chapterIds.chunked(GROUP_SIZE).mapIndexed { bi, group ->
            val id = "b$bi"
            calls += MergeCall(maxChapterLayer + 1, MergeCall.KIND_BLOCK, id, group)
            id
        }

        // 书层：块 → 1（块超 100 ⇒ 就地再分层）。book.jsonl 是通读的**最终交付物** ⇒
        // 只要有块就出书层调用——1 个块也合（把块摘要整理成全书摘要），不直通。
        val maxBlockLayer = if (blockIds.isEmpty()) maxChapterLayer else maxChapterLayer + 1
        val maxBookLayer = when {
            blockIds.isEmpty() -> maxBlockLayer
            blockIds.size == 1 -> {
                calls += MergeCall(maxBlockLayer + 1, MergeCall.KIND_BOOK, "book", blockIds)
                maxBlockLayer + 1
            }
            else -> groupLevel(blockIds, maxBlockLayer + 1, MergeCall.KIND_BOOK, "book", calls).second
        }
        return MergePlan(calls, 1 + maxOf(maxChapterLayer, maxBlockLayer, maxBookLayer))
    }

    /**
     * 把一串产物 id 收拢成 1 个（递归，每组 ≤ [GROUP_SIZE] 个输入）。
     *
     * @return（收拢后的 id 表（≤1 个），本层深度）
     */
    private fun groupLevel(
        ids: List<String>,
        layer: Int,
        rootKind: String,
        prefix: String,
        calls: MutableList<MergeCall>,
    ): Pair<List<String>, Int> {
        if (ids.size <= 1) return ids to (layer - 1).coerceAtLeast(0)
        val groups = ids.chunked(GROUP_SIZE)
        val outputs = groups.mapIndexed { gi, group ->
            val outputId = if (groups.size == 1) prefix else "${prefix}r${layer}g$gi"
            calls += MergeCall(layer, rootKind, outputId, group)
            outputId
        }
        return groupLevel(outputs, layer + 1, rootKind, prefix, calls)
    }
}
