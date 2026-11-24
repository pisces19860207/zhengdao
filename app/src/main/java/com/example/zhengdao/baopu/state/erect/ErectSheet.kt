// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百一十批 · 两态对照表：疲软 vs 勃起，五行逐项对照。
//
// 判据① 六件齐——本表是第六件，也是前五件的落点；五行缺一即红，点名缺哪行。
// 判据② 硬度与尺寸独立——大小是 SIZE 行、硬度是 HARDNESS 行，两行不合并。
// 唯一出口 line()：五行全出，出门过 805 三道闸＋本包倍数/合并档/占位闸。
package com.example.zhengdao.baopu.state.erect

import com.example.zhengdao.baopu.state.bodyparts.BodyPartGuard

/**
 * 一张两态对照表。构造时按五行冻结表校验：
 * · 缺任意一行 ⇒ 红，错误点名缺哪行；
 * · 同一行记两次 ⇒ 红（两个真相）；
 * · 表外行名进不来。
 */
class ErectSheet private constructor(private val rows: List<SheetRow>) {

    init {
        val keys = rows.map { it.key }
        require(keys.size == keys.toSet().size) { DUPLICATE_REJECT + "（${dupName(keys)}）" }
        val missing = SheetRow.ALL_KEYS.filterNot { it in keys }
        require(missing.isEmpty()) { MISSING_PREFIX + missing.joinToString("、") }
        val extra = keys.filterNot { it in SheetRow.ALL_KEYS }
        require(extra.isEmpty()) { UNKNOWN_PREFIX + extra.joinToString("、") }
    }

    val size: Int get() = rows.size

    /** 按冻结表顺序取行（大小／角度／硬度／包皮位／冠外扩），逐字可回放。 */
    fun ordered(): List<SheetRow> = SheetRow.ALL_KEYS.map { k -> rows.first { it.key == k } }

    fun row(key: String): SheetRow = rows.first { it.key == key }

    /** 唯一出口：五行逐行出，汇总文本一次过闸。 */
    fun line(): String {
        val text = ordered().joinToString("\n") { it.key + "｜" + it.line() }
        BodyPartGuard.rejectMeasures(text)
        BodyPartGuard.rejectDiagnosis(text)
        BodyPartGuard.rejectAbsoluteColor(text)
        ErectGuard.rejectMerged(text)
        ErectGuard.rejectInventedPlaceholder(text)
        return text
    }

    companion object {
        const val MISSING_PREFIX: String = "两态对照表缺行："
        const val DUPLICATE_REJECT: String = "同一行记了两次：两个真相"
        const val UNKNOWN_PREFIX: String = "对照表不收表外行："

        /** 期望五行（表驱动，不手数）。 */
        val EXPECTED_SIZE: Int get() = SheetRow.ALL_KEYS.size

        /** 完整构造：五行齐才成表。 */
        fun of(size: SizeState, angle: AnglePair, hardness: HardnessState,
               prepuce: PrepuceState, corona: CoronaState): ErectSheet =
            ErectSheet(listOf(size, angle, hardness, prepuce, corona))

        /** 行列表构造——给测试注入「缺行」用；生产走 [of]。 */
        internal fun fromRows(rows: List<SheetRow>): ErectSheet = ErectSheet(rows)

        private fun dupName(keys: List<String>): String =
            keys.groupingBy { it }.eachCount().filter { it.value > 1 }.keys.joinToString("、")
    }
}
