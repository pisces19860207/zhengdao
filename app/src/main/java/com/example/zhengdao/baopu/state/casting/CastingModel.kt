// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百一十七批 · 外观卡数据模型：不可变卡 + 逐次变更留痕。
// 卡可以细（白名单 ≤40 项），注入必须短（另见 CastingSheet 的 120 字摘要）。
// 构造即校验：字段须在既有档白名单、取值须被既有档接受；无既有档者不许填值。
package com.example.zhengdao.baopu.state.casting

/**
 * 一条变更留痕。[seq] 从 1 起单调递增；[from]/[to] 是「档名列表」，空表＝未设定。
 * 只看当前值不够——谁在什么时候把什么改成什么，必须逐条留着可回放。
 */
data class CardChange(
    val seq: Int,
    val roleId: String,
    val fieldId: String,
    val from: List<String>,
    val to: List<String>,
    val reason: String,
) {
    init {
        require(seq >= 1) { "留痕序号从 1 起" }
        require(reason.isNotBlank()) { "变更必须写理由，不许空" }
    }

    fun line(): String = "$seq|$roleId|$fieldId|${from.joinToString("+")}->${to.joinToString("+")}|$reason"
}

/**
 * 一张角色外观卡。
 * [values] 的 key 是 [CastingRefs] 白名单字段 id，value 是档名列表（multi 字段可多项）；
 * 没出现的字段＝未设定，不是新造一个「无」档。
 * [profileId] 是 747 七维档案 id（同档案角色由此归并，判⑥）；[isMain] 标主要角色。
 * [history] 不可变追加，[revision] 与 history.size 恒等。
 */
data class CastingCard(
    val roleId: String,
    val profileId: String,
    val isMain: Boolean,
    val values: Map<String, List<String>> = emptyMap(),
    val history: List<CardChange> = emptyList(),
) {
    init {
        require(roleId.isNotBlank()) { "角色 id 不许空" }
        require(profileId.isNotBlank()) { "档案 id 不许空（判⑥ 同档案归并要用）" }
        require(values.size <= CastingRefs.MAX_CARD_ITEMS) {
            "卡字段 ${values.size} 项超预算 ${CastingRefs.MAX_CARD_ITEMS}，须合并同类后再落卡"
        }
        require(history.map { it.seq } == (1..history.size).toList()) {
            "留痕序号必须从 1 连续递增，不许断号或重号"
        }
        validateValues()
    }

    /** 修订号＝已留痕变更条数；初始卡为 0。 */
    val revision: Int get() = history.size

    private fun validateValues() {
        values.forEach { (fieldId, words) ->
            val ref = CastingRefs.field(fieldId) // 表外字段直接拒（不许新增维度）
            require(words.isNotEmpty()) { "$fieldId 给了空值；未设定请不要写进卡" }
            require(ref.source != RefSource.NONE) {
                "$fieldId（${ref.cn}）仓内无既有封闭档，不许填值；留未设定走可补卡"
            }
            words.forEach { w ->
                require(ref.accepts(w)) {
                    "$fieldId 的「$w」不在既有档（${ref.source}）内，不许新造档"
                }
            }
        }
    }

    fun has(fieldId: String): Boolean = fieldId in values

    fun of(fieldId: String): List<String> = values[fieldId] ?: emptyList()

    /**
     * 改一个字段并返回新卡（原卡不变），同时追加一条留痕。
     * 传空表＝撤掉该字段（回到未设定），同样留痕。
     */
    fun with(fieldId: String, words: List<String>, reason: String): CastingCard {
        val ref = CastingRefs.field(fieldId)
        if (words.isNotEmpty()) {
            require(ref.source != RefSource.NONE) { "$fieldId 无既有档，不许填值" }
            words.forEach { require(ref.accepts(it)) { "$fieldId 的新档「$it」不在既有档内" } }
        }
        val next = LinkedHashMap(values)
        if (words.isEmpty()) next.remove(fieldId) else next[fieldId] = words.distinct()
        val change = CardChange(
            seq = revision + 1, roleId = roleId, fieldId = fieldId,
            from = of(fieldId), to = words.distinct(), reason = reason,
        )
        return copy(values = next, history = history + change)
    }

    /**
     * 粒度预算的结构性落点：卡字段白名单本身即为上限（CARD_FIELDS.size ≤ 40）。
     * 「超了合并同类」在结构上不会发生——白名单只有 33 个卡字段，够细又封顶；
     * 若将来白名单扩到 >40，CastingRefs 的 init 会当场拒装配，不会让超预算的卡落库。
     * 这里返回当前项数与预算，供面板/报告显示，不参与判定。
     */
    fun budget(): Pair<Int, Int> = values.size to CastingRefs.MAX_CARD_ITEMS

    /** 稳定快照：同卡逐字一致，用于确定性双跑比对。 */
    fun line(): String = buildString {
        append("role=$roleId|profile=$profileId|main=$isMain|rev=$revision\n")
        CastingRefs.CARD_FIELDS.forEach { ref ->
            if (ref.id in values) append(ref.id).append('=').append(values[ref.id]!!.joinToString("+")).append('\n')
        }
        history.forEach { append(it.line()).append('\n') }
    }
}
