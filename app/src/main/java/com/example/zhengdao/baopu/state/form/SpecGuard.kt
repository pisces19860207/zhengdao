// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百六十批 · 形轨.形体参数：输入域守卫。可行域全在口径表里，本文件不写死任何数值。
// 超域/悬空键/缺色即拒（fail-closed），不给"兜底默认"。
package com.example.zhengdao.baopu.state.form

/** 把 HumanBodySpec 的每个覆盖值与档位对照口径表校验。 */
object SpecGuard {

    fun check(spec: HumanBodySpec, props: Proportions, table: ZoneTable) {
        checkKeys(spec, props, table)
        checkParts(spec.parts, props)
        checkSkin(spec.skin, props, table)
        checkFlex(spec, props)
    }

    private fun checkFlex(spec: HumanBodySpec, props: Proportions) {
        val f = props.flexibility
        val g = spec.flexibilityGrade
        if (g != null) require(g.toDouble() in f.grade.low..f.grade.high) {
            "柔韧档 $g 超域 ${f.grade.low}..${f.grade.high}"
        }
        val band = spec.ageBand
        if (band != null) require(band in f.ageBandDefault) {
            "年龄段 $band 不在口径表 ${f.ageBandDefault.keys}"
        }
    }

    private fun checkKeys(spec: HumanBodySpec, props: Proportions, table: ZoneTable) {
        val segIds = props.segments.map { it.id }.toSet()
        val secIds = props.sections.map { it.id }.toSet()
        val zoneIds = table.zones.map { it.zoneId }.toSet()
        require(spec.segments.keys.all { it in segIds }) {
            "分段覆盖键悬空：${spec.segments.keys - segIds}"
        }
        require(spec.sections.keys.all { it in secIds }) {
            "截面覆盖键悬空：${spec.sections.keys - secIds}"
        }
        require(spec.genital.zoneRadiusMm.keys.all { it in zoneIds }) {
            "区内半径覆盖键悬空：${spec.genital.zoneRadiusMm.keys - zoneIds}"
        }
        require(spec.skin.zoneColors.keys.all { it in zoneIds }) {
            "色值键悬空：${spec.skin.zoneColors.keys - zoneIds}"
        }
    }

    private fun checkParts(p: PartsSpec, props: Proportions) {
        val pairs = listOf(
            p.pecMass to props.part("pec_mass"),
            p.pecInsertion to props.part("pec_insertion"),
            p.absBlocks?.toDouble() to props.part("abs_blocks"),
            p.absDepth to props.part("abs_depth"),
            p.gluteMass to props.part("glute_mass"),
        )
        for ((v, s) in pairs) {
            if (v != null) require(v in s.low..s.high) { "${s.id} 超域 ${s.low}..${s.high}，得 $v" }
        }
    }

    private fun checkSkin(skin: SkinSpec, props: Proportions, table: ZoneTable) {
        grade(skin.elasticityGrade, props.skin.elasticity)
        grade(skin.hairGrade, props.skin.hair)
        val digits = props.skin.hexDigits.default.toInt()
        for ((zone, color) in skin.zoneColors) {
            require(color.hex.length - 1 == digits) {
                "$zone 色值须 $digits 位 hex（口径表 skin.color_hex_pattern），得 ${color.hex}"
            }
        }
        val missing = table.zones.map { it.zoneId } - skin.zoneColors.keys
        require(missing.isEmpty()) { "缺色值的区：$missing（每区颜色必带 source，缺即拒）" }
    }

    private fun grade(v: Int?, s: NumericSpec) {
        if (v != null) require(v.toDouble() in s.low..s.high) { "${s.id} 档 $v 超域 ${s.low}..${s.high}" }
    }
}