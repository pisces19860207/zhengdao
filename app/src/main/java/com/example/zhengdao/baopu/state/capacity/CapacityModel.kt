// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百九十五批 · 容量与分配的判定口径（741 补丁）：输入与结论的数据模型。
// 纯数据、无 IO、无随机、无时钟：四要素缺一不可，判矛盾要逐项带来源锚的排除清单。
// 与 741 的空间三态（进得去/勉强/进不去）是两套，互不相干。
package com.example.zhengdao.baopu.state.capacity

/** 容量断言的四要素。序数即 missing 的输出次序，保证两跑逐字一致。 */
enum class CapacityFactor(val label: String) {
    QUANTITY("数量"),
    ALLOCATION("分配"),
    SCOPE("范围"),
    PERIOD("时段"),
}

/** 房间之外的去处：房间只是一类，客厅/沙发/地板/走廊/站外都要逐一点名。 */
enum class Place(val label: String) {
    ROOM("房间"),
    LIVING_ROOM("客厅"),
    SOFA("沙发"),
    FLOOR("地板"),
    CORRIDOR("走廊"),
    OFF_SITE("站外"),
}

/** 数量的来源。ROOM_COUNT_DERIVED＝拿房间数反推容量，判定层一律红/拒。 */
enum class CountingBasis {
    EXPLICIT,
    ROOM_COUNT_DERIVED,
}

/**
 * 来源锚：一句原文引文 [quote]，加上引文里必须真的出现的字/词 [requiredToken]。
 * 「站内已满」⇒ token=「满」；「无沙发」⇒ token=「无」。
 * 引文里没有这个词 ⇒ 锚不成立，这条排除不算数。
 */
data class SourceAnchor(val quote: String, val requiredToken: String) {
    val valid: Boolean get() = quote.isNotBlank() && requiredToken.isNotBlank() && requiredToken in quote
}

/** 一条「已排除的替代安排」，必须带来源锚；没有锚就不算排除过。 */
data class ExcludedAlternative(val arrangement: String, val anchor: SourceAnchor) {
    val valid: Boolean get() = arrangement.isNotBlank() && anchor.valid
}

/**
 * 一条容量断言的结构化输入。四要素用可空：null／空串／空集都算「缺」。
 * [alternatives] 已排除的替代安排（判矛盾的证据）；[confirmations] 原文明确支持的陈述（判支持的证据）。
 */
data class CapacityClaim(
    val quantity: String?,
    val allocation: String?,
    val scope: Set<Place>?,
    val period: String?,
    val alternatives: List<ExcludedAlternative> = emptyList(),
    val confirmations: List<SourceAnchor> = emptyList(),
    val countingBasis: CountingBasis = CountingBasis.EXPLICIT,
)

/** 三态：矛盾／支持／未知。与 741 的空间三态是两套，互不相干。 */
enum class CapacityVerdict(val label: String) {
    CONTRADICTION("矛盾"),
    SUPPORT("支持"),
    UNKNOWN("未知"),
}

/** 结论：三态 + 缺失要素 + 已排除清单（判矛盾时非空）+ 一句说明理由。 */
data class CapacityResult(
    val verdict: CapacityVerdict,
    val missing: List<CapacityFactor>,
    val excludedAlternatives: List<String>,
    val reason: String,
) {
    init {
        require(excludedAlternatives == excludedAlternatives.distinct()) { "排除清单不许重复" }
        require(verdict != CapacityVerdict.CONTRADICTION || excludedAlternatives.isNotEmpty()) {
            "判矛盾必须给非空的已排除替代安排清单"
        }
        require(missing == CapacityFactor.entries.filter { it in missing }) { "missing 须按四要素固定次序且去重" }
    }

    /** 一行机读快照，用于同输入同结论的逐字比对。 */
    fun line(): String = "${verdict.label}|缺=${missing.joinToString("、") { it.label }}|" +
        "排除=${excludedAlternatives.joinToString("、")}| $reason"
}
