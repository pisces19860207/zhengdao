// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百七十五批 · 龟头体与柱身静态形态（子区层）：子区单与**唯一出口**。
//
// 一张子区单＝14 行全齐（判据②：缺一行就不成单，造不出来）。常态单由静态表直接生成
// （[SubzoneSheet.static]）；某个角色偏离常态时，只许改**档**，不许改**轴**——
// 每行用哪几条形态轴由 [SubzoneTable] 定死，改轴就是换问题，不叫覆盖。
//
// 出口只有 [SubzoneSheet.line] 一个，出门前再过一遍 805 的三道文字闸：
// 不出长度／面积／百分比、不出绝对色号、不出判定词（判据④的运行时那一半；
// 源码那一半在 `SubzoneSourceAssertTest`）。
package com.example.zhengdao.baopu.state.subzone

import com.example.zhengdao.baopu.state.bodyparts.BodyPartGuard

/**
 * 一个子区的（可能是角色改过的）读数。
 *
 * @param forms 只许用该子区在静态表里登记过的轴；档序仍受 [FormAxis] 的闭集约束
 */
data class SubzoneReading(
    val id: SubzoneId,
    val texture: Texture,
    val color: ColorShift,
    val forms: List<FormValue>,
) {
    init {
        require(forms.isNotEmpty()) { "${id.label}：形态档至少一条轴" }
        require(forms.size <= SubzoneRow.MAX_FORMS) { "${id.label}：形态档最多 ${SubzoneRow.MAX_FORMS} 条轴" }
        require(forms.map { it.axis }.toSet().size == forms.size) { "${id.label}：同一形态轴登记了两次" }
    }

    fun line(): String = buildString {
        append(id.code).append('|').append(texture.label).append('|').append(color.label)
        append('|').append(forms.joinToString(",") { it.line() })
    }

    companion object {
        /** 常态读数：直接从静态表取，不在这里抄第二份默认值。 */
        fun of(id: SubzoneId): SubzoneReading = of(SubzoneTable.row(id))

        fun of(row: SubzoneRow): SubzoneReading =
            SubzoneReading(row.id, row.texture, row.color, row.forms)
    }
}

/**
 * 子区单。键是子区 id，一格一个；同一子区记两次即抛（两个真相不允许），缺一行也抛。
 */
class SubzoneSheet private constructor(private val readings: Map<SubzoneId, SubzoneReading>) {

    init {
        require(readings.size == SubzoneId.entries.size) { missing(readings) }
        for (reading in readings.values) {
            val declared = SubzoneTable.row(reading.id).forms.map { it.axis }.toSet()
            val extra = reading.forms.map { it.axis }.filterNot { declared.contains(it) }
            require(extra.isEmpty()) { undeclaredAxis(reading.id, extra.first()) }
        }
    }

    val size: Int get() = readings.size

    fun reading(id: SubzoneId): SubzoneReading = readings.getValue(id)

    /** 唯一出口：一行一个子区，出门前过 805 的三道闸。 */
    fun line(): String {
        val text = SubzoneId.entries.joinToString("\n") { readings.getValue(it).line() }
        BodyPartGuard.rejectMeasures(text)
        BodyPartGuard.rejectAbsoluteColor(text)
        BodyPartGuard.rejectDiagnosis(text)
        return text
    }

    companion object {
        /** 常态单：14 行全用静态表的值。 */
        fun static(): SubzoneSheet = SubzoneSheet(autoMap().values.associateBy { it.id })

        /** 由若干读数成单；没给的行保持常态（不在没给的地方编数）。 */
        fun of(overrides: List<SubzoneReading>): SubzoneSheet {
            val dup = overrides.groupBy { it.id }.filterValues { it.size > 1 }.keys
            require(dup.isEmpty()) { "同一子区记了两次：" + dup.joinToString("、") { it.label } }
            val map = autoMap().toMutableMap()
            overrides.forEach { map[it.id] = it }
            return SubzoneSheet(map)
        }

        private fun autoMap(): Map<SubzoneId, SubzoneReading> =
            SubzoneId.entries.associateWith { SubzoneReading.of(it) }

        private fun missing(readings: Map<SubzoneId, SubzoneReading>): String {
            val missing = SubzoneId.entries.filterNot { readings.containsKey(it) }
            return "子区单缺行（判据②缺则红）：" + missing.joinToString("、") { it.label }
        }

        private fun undeclaredAxis(id: SubzoneId, axis: FormAxis): String =
            id.label + "：静态表没给它登记「" + axis.label + "」这条轴——角色只许改档，不许改轴"
    }
}
