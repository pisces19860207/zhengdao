// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
//
// 第七百四十七批 · 人物锚定：七维档案（派工单① 的数据类）。
// 规格口径（§三 表末＋§四 用法 A/B 的 yaml）：
// · 存储恒为全量：一张卡同时装着 L1 标签、L2 细项、L3 自由补充；
//   **切粒度只换渲染视图，不动存储**（判据① 的「切换不丢数据」由这条结构保证）。
// · 一行数据的去向由 AnchorSpecTable 那一行自己决定：
//   CLOSED（分级行）→ words 里的档；FREE（按部位「填什么」行）→ 自由文本；带单位行 → 数值。
//   放错地方直接拒，不猜用户想填哪——猜就会造出口规外的字段（判据①「不新增」的另一半）。
// · §一 锚的是「这个人是什么样」：本类不含身份标签、不含关系状态（判据⑥）。
package com.example.zhengdao.baopu.state.anchor

/**
 * 一份七维档案（用法 A 的 NPC 预设、用法 B 的玩家偏好都用它承载）。
 *
 * @param labels L1 核心标签（§三 那 15 个里的若干个，规格建议勾 2–4 个，不硬卡）
 * @param values L2 细项：封闭档行的取值，key＝字段 id
 * @param numbers 数值行（带单位：身高 cm／体重 kg／持久 min）
 * @param texts 落点行的自由描述（规格 §二④「按部位」那一套，每行都是独立落点）
 * @param kinks 性癖权重 0–5（§二⑦）
 * @param freeNote L3 自由补充（规格：一段文本，「写不进去的都塞这」）
 */
data class AnchorProfile(
    val id: String,
    val labels: List<String> = emptyList(),
    val values: Map<String, List<String>> = emptyMap(),
    val numbers: Map<String, Double> = emptyMap(),
    val texts: Map<String, String> = emptyMap(),
    val kinks: Map<String, Int> = emptyMap(),
    val freeNote: String = "",
) {
    init {
        id.requireId("档案 id")
        validateLabels()
        validateValues()
        validateNumbers()
        validateTexts()
        validateKinks()
    }

    // ── 判据①：缺维拒算，并报缺哪一维 ──────────────────────────────

    /** 这一维在档案里一条数据都没有（性癖维看权重，其余看细项／数值／落点）。 */
    fun dimEmpty(dim: DimKind): Boolean = when (dim) {
        DimKind.KINK -> kinks.isEmpty()
        else -> !(values.keys.hasAnyOf(dim) || numbers.keys.hasAnyOf(dim) || texts.keys.hasAnyOf(dim))
    }

    /** 七个维度里当前为空的维（按 §二 顺序）。 */
    fun missingDims(): List<DimKind> = DimKind.values().filter { dimEmpty(it) }

    /** 齐了才让算；缺维直接拒，报错点名到底缺哪几维（判据①）。 */
    fun requireComplete() {
        val missing = missingDims()
        if (missing.isNotEmpty()) {
            throw AnchorSpecException(
                "七维不齐，拒算：缺 ${missing.joinToString("、") { it.cn }}" +
                    "（共缺 ${missing.size} 维／7；档案 ${id}）"
            )
        }
    }

    /** 只有一维数据的维清单齐 ⇒ true（供面板显示完成度，不做判据）。 */
    fun completeness(): Int = DimKind.values().count { !dimEmpty(it) }

    // ── 判据①：三级粒度切换（视图，不动存储） ──────────────────────

    /** 某一粒度下要渲染的行（L1＝标签，L2＝七维细项，L3＝自由补充那一段）。 */
    fun view(level: Granularity): List<String> = when (level) {
        Granularity.L1 -> labels.map { l -> "$l｜${AnchorFields.L1_TAG_WORDS[l] ?: "○ 待核"}" }
        Granularity.L2 -> DimKind.values().flatMap { renderDim(it) }
        Granularity.L3 -> if (freeNote.isBlank()) emptyList() else listOf("自由补充：$freeNote")
    }

    /** 一维的渲染：按 §二 表内顺序出行，没填的行不出现（不猜、不补默认值）。 */
    private fun renderDim(dim: DimKind): List<String> = AnchorFields.ofDim(dim).mapNotNull { row ->
        when {
            dim == DimKind.KINK && row.id in kinks -> "${row.group}·${row.label}＝${kinks[row.id]}"
            row.id in numbers -> "${row.label}＝${numbers[row.id]}${row.unit}"
            row.id in texts -> "${row.label}＝${texts[row.id]}"
            row.id in values -> "${row.label}＝${values[row.id]!!.joinToString("+")}"
            else -> null
        }
    }

    /** 全量快照（稳定字符串）：判据⑤ 的回放把「当时七维」存成它，粒度切换不影响它。 */
    fun snapshot(): String = buildString {
        append("profile=").append(id).append('\n')
        append("labels=").append(labels.joinToString("+")).append('\n')
        AnchorFields.ALL.forEach { row ->
            when {
                row.id in numbers -> append(row.id).append('=').append(numbers[row.id]).append(row.unit).append('\n')
                row.id in texts -> append(row.id).append('=').append(texts[row.id]).append('\n')
                row.id in values -> append(row.id).append('=').append(values[row.id]!!.joinToString("+")).append('\n')
                row.id in kinks -> append(row.id).append('=').append(kinks[row.id]).append('\n')
            }
        }
        append("note=").append(freeNote)
    }

    // ── 校验（全部走 AnchorSpecException，报错点名到 id） ────────────

    private fun validateLabels() {
        labels.forEach { if (it !in AnchorFields.L1_TAGS) reject("L1 标签「$it」不在 §三 的 15 个里，不许新增") }
        if (labels.distinct().size != labels.size) reject("L1 标签重复")
    }

    private fun validateValues() {
        values.forEach { (fieldId, words) ->
            val row = AnchorFields.spec(fieldId)
            if (!row.closed) reject("${row.label}（$fieldId）是落点行，自由描述走 texts，档不能当值填")
            if (words.isEmpty()) reject("$fieldId 给了空值")
            if (!row.multi && words.size > 1) reject("${row.label}（$fieldId）规格是单选，收到 ${words.size} 个值")
            words.forEach { if (!row.accepts(it)) reject("${row.label} 的「$it」不在 §二 表内，不许新增取值") }
        }
    }

    private fun validateNumbers() {
        numbers.forEach { (fieldId, value) ->
            val row = AnchorFields.spec(fieldId)
            if (!row.measured) reject("${row.label}（$fieldId）规格没给单位，不是数值行：$value")
            if (!value.isFinite()) reject("$fieldId 的数值非有限：$value")
        }
    }

    private fun validateTexts() {
        texts.forEach { (fieldId, text) ->
            val row = AnchorFields.spec(fieldId)
            if (row.closed) reject("${row.label}（$fieldId）是封闭档行，取值走 values：表内 ${row.words.joinToString("／")}")
            if (text.isBlank()) reject("$fieldId 的落点描述为空")
        }
    }

    private fun validateKinks() {
        kinks.forEach { (fieldId, weight) ->
            val row = AnchorFields.spec(fieldId)
            if (row.dim != DimKind.KINK) reject("$fieldId 不是 §二⑦ 的性癖项，不收权重")
            AnchorFields.requireKinkWeight(weight)
        }
    }

    private fun String.requireId(what: String) {
        if (isBlank()) reject("$what 不许为空")
    }

    private fun reject(message: String): Nothing = throw AnchorSpecException("档案 $id：$message")
}

/** id 集合里有没有属于这一维的字段（只看键：空串也算填过，规格不认「填了个空」是缺维）。 */
private fun Set<String>.hasAnyOf(dim: DimKind): Boolean = any { AnchorFields.spec(it).dim == dim }
