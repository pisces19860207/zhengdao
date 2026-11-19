// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百六十批 · 形轨.形体参数：事实取值口。
// 只读取值：业务码不许写分布字面量，一律经 FactSource；换底只换实现，调用点一行不动。
package com.example.zhengdao.baopu.state.form

import kotlin.math.PI

/** 证据等级（事实底盘 §五 口径）：分布有据 / 临床共识 / 工程假设。 */
enum class Evidence(val mark: String) {
    VERIFIED("✅ 已核原文"),
    CONSENSUS("◐ 临床共识"),
    ASSUMPTION("○ 工程假设"),
}

/** 一条带来源的区间读数：只给区间，不给假点值；缺来源即拒收。 */
data class FactRange(
    val id: String,
    val low: Double,
    val high: Double,
    val unit: String,
    val source: String,
    val asOf: String,
    val evidence: Evidence,
) {
    init {
        require(source.isNotBlank()) { "$id 缺 source，拒收" }
        require(asOf.isNotBlank()) { "$id 缺 asOf，拒收" }
        require(low.isFinite() && high.isFinite()) { "$id 区间非有限" }
        require(low <= high) { "$id 区间下界大于上界" }
    }

    val width: Double get() = high - low

    fun contains(value: Double) = value in low..high

    /** 区间中点，只作展示锚点，不作"真值"。 */
    fun mid() = (low + high) / 2.0
}

/** 分位点。 */
data class PercentilePoint(val pct: Int, val value: Double, val evidence: Evidence)

/**
 * 分位表：pct 与读数都必须单调递增。
 * 表外的分位不外推——外推就是编数，直接拒。
 */
class PercentileTable(
    val id: String,
    val unit: String,
    val points: List<PercentilePoint>,
    val source: String,
    val asOf: String,
) {
    init {
        require(source.isNotBlank()) { "$id 缺 source，拒收" }
        require(asOf.isNotBlank()) { "$id 缺 asOf，拒收" }
        require(points.isNotEmpty()) { "$id 分位表为空" }
        require(points.all { it.pct in 0..100 }) { "$id 分位须在 0..100" }
        require(points.zipWithNext().all { (a, b) -> a.pct < b.pct }) { "$id 分位须严格递增" }
        require(points.zipWithNext().all { (a, b) -> a.value <= b.value }) { "$id 读数须单调不减" }
    }

    val lowPct: Int get() = points.first().pct
    val highPct: Int get() = points.last().pct

    /** 表内线性插值；表外拒收。 */
    fun at(pct: Int): Double {
        require(pct in lowPct..highPct) { "$id 分位 $pct 在表外（$lowPct..$highPct），不外推" }
        val exact = points.firstOrNull { it.pct == pct }
        if (exact != null) return exact.value
        val hi = points.first { it.pct > pct }
        val lo = points.last { it.pct < pct }
        val t = (pct - lo.pct).toDouble() / (hi.pct - lo.pct).toDouble()
        return lo.value + (hi.value - lo.value) * t
    }

    /** 取一段分位区间，来源与日期随表走。 */
    fun range(fromPct: Int, toPct: Int, evidence: Evidence = Evidence.VERIFIED): FactRange {
        require(fromPct <= toPct) { "$id 分位区间倒置" }
        return FactRange("$id.p$fromPct-$toPct", at(fromPct), at(toPct), unit, source, asOf, evidence)
    }
}

/** 罕见长度：可存在，但不得作默认。 */
data class RareLength(
    val valueMm: Double,
    val rarity: String,
    val notDefault: Boolean,
    val source: String,
    val asOf: String,
    val evidence: Evidence,
) {
    init {
        require(source.isNotBlank()) { "罕见长度缺 source，拒收" }
        require(rarity.isNotBlank()) { "罕见长度必须写明罕见程度" }
        require(notDefault) { "罕见长度不得作默认抽样值" }
    }
}

/** 直径档位提示：落在该区间就必须附带这句话。 */
data class DiameterAdvisory(
    val fromMm: Double,
    val toMm: Double,
    val note: String,
    val source: String,
    val asOf: String,
) {
    init {
        require(fromMm <= toMm) { "直径提示区间倒置" }
        require(note.isNotBlank()) { "直径提示缺文案" }
        require(source.isNotBlank()) { "直径提示缺 source，拒收" }
    }

    fun covers(diameterMm: Double) = diameterMm in fromMm..toMm
}

/**
 * 只读取值口。
 * 实现可换（md 常量表 → facts.json），调用点一行不动。
 * 注意：本口不提供任何"由身高推尺寸"的入口——两者只允许弱相关。
 */
interface FactSource {
    /** 长度分位表（mm）。 */
    val lengthRangePct: PercentileTable

    /** 周长分位表（mm）；等效直径由周长换算。 */
    val girthRangePct: PercentileTable

    /** 等效直径默认抽样上限（mm）：超过它仍可存在，但必须附提示。 */
    val defaultCapMm: Double

    /** 硬度档位数（含"无反应"档）。 */
    val hardnessGradeCount: Int

    /** 皮温升幅区间（℃）：量级有据、幅度无文献。 */
    val skinTempRiseC: FactRange

    /** 与身高的相关系数区间：只允许弱相关，故不得据身高反推尺寸。 */
    val statureCorrelationR: FactRange

    /** 充血时肤色加深的档数区间（色阶增量）。 */
    val colorDeepenStep: FactRange

    /** 罕见长度：可存在，不得作默认。 */
    val rareLength: RareLength

    /** 直径提示表。 */
    val diameterAdvisories: List<DiameterAdvisory>

    /** 等效直径 = 周长 / π（真实截面略扁，故称"等效"）。 */
    fun equivalentDiameterMm(girthMm: Double): Double = girthMm / PI

    /** 由周长分位换算直径分位区间。 */
    fun diameterRangePct(fromPct: Int, toPct: Int): FactRange {
        val g = girthRangePct.range(fromPct, toPct)
        return FactRange(
            id = "diameter.p$fromPct-$toPct",
            low = equivalentDiameterMm(g.low),
            high = equivalentDiameterMm(g.high),
            unit = "mm",
            source = g.source,
            asOf = g.asOf,
            evidence = g.evidence,
        )
    }

    /** 该直径必须附带的提示（可多条）。 */
    fun advisoriesFor(diameterMm: Double): List<String> =
        diameterAdvisories.filter { it.covers(diameterMm) }.map { it.note }
}