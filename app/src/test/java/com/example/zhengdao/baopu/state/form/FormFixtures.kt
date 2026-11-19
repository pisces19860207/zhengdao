// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百六十批 · 形轨.形体参数：单测夹具。只读 docs/facts 三件，零写盘、零临时目录。
// 色板是**测试夹具**，不是授权色值：生产侧无来源色值一律拒收（防编色）。
package com.example.zhengdao.baopu.state.form

import com.example.zhengdao.baopu.body.AnatomyZone
import com.example.zhengdao.baopu.body.AnatomyZones
import java.io.File

object FormFixtures {

    const val COLOR_SOURCE = "单测夹具色板（非授权色值，不入生产）"
    const val AS_OF = "2026-11-18"
    const val FAKE_SOURCE = "假底（换底演练用，非事实）"

    fun repoRoot(): File =
        if (File(FormTables.PROPORTIONS_REL_PATH).isFile) File(".") else File("..")

    val props: Proportions by lazy { FormTables.loadProportions(repoRoot()) }
    val zones: ZoneTable by lazy { FormTables.loadZones(repoRoot()) }
    val anatomy: List<AnatomyZone> by lazy { AnatomyZones.load(repoRoot()) }

    /** 龟头必须比柱身深（INV-03 的行为侧），故这里给的是有深浅差的一组夹具色。 */
    fun colors(): Map<String, ZoneColor> = mapOf(
        "radix" to "#c89a7a",
        "shaft" to "#c89a7a",
        "raphe" to "#b98a6a",
        "glans" to "#a86a5a",
        "corona" to "#a86a5a",
        "sulcus" to "#b07a62",
        "frenulum" to "#b98a6a",
        "meatus" to "#9a5a4a",
        "prepuce" to "#c89a7a",
        "scrotum" to "#b08060",
        "testis" to "#b08060",
        "cord" to "#b08060",
        "mons" to "#c89a7a",
    ).mapValues { ZoneColor(it.value, COLOR_SOURCE, AS_OF) }

    fun spec(
        heightCm: Double = 170.0,
        weightKg: Double = 65.0,
        bodyFatPct: Double = 18.0,
        genital: GenitalInput = GenitalInput(),
        parts: PartsSpec = PartsSpec(),
    ): HumanBodySpec = HumanBodySpec(
        heightCm = heightCm, weightKg = weightKg, bodyFatPct = bodyFatPct,
        muscleGrade = 3, parts = parts,
        skin = SkinSpec(zoneColors = colors()), genital = genital,
    )

    fun state(
        spec: HumanBodySpec = spec(),
        facts: FactSource = FactsFromMd,
    ): BodyState = BodyModel.of(spec, props, zones, anatomy, facts)

    fun override(value: Double) = Override(value, "单测夹具实测", AS_OF)

    private fun scaled(t: PercentileTable, k: Double) = PercentileTable(
        t.id, t.unit, t.points.map { it.copy(value = it.value * k) }, FAKE_SOURCE, AS_OF,
    )

    private fun scaledRange(r: FactRange, k: Double) =
        r.copy(low = r.low * k, high = r.high * k, source = FAKE_SOURCE)

    /**
     * 换底演练用的假 FactSource：分位整体缩放，接口与结构一字不改。
     * 660 侧的关系型判据必须照样全绿——绿不了就说明有调用点绕过接口偷看了常量表。
     */
    fun fakeFacts(k: Double = 1.1): FactSource = object : FactSource {
        override val lengthRangePct = scaled(FactsFromMd.lengthRangePct, k)
        override val girthRangePct = scaled(FactsFromMd.girthRangePct, k)
        override val defaultCapMm = FactsFromMd.defaultCapMm * k
        override val hardnessGradeCount = FactsFromMd.hardnessGradeCount
        override val skinTempRiseC = scaledRange(FactsFromMd.skinTempRiseC, k)
        override val statureCorrelationR = FactsFromMd.statureCorrelationR
        override val colorDeepenStep = FactsFromMd.colorDeepenStep
        override val rareLength = FactsFromMd.rareLength.copy(
            valueMm = FactsFromMd.rareLength.valueMm * k, source = FAKE_SOURCE,
        )
        override val diameterAdvisories = FactsFromMd.diameterAdvisories.map {
            it.copy(fromMm = it.fromMm * k, toMm = it.toMm * k, source = FAKE_SOURCE)
        }
    }
}