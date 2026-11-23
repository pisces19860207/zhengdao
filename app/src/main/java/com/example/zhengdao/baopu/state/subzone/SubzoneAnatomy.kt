// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百七十五批 · 龟头体与柱身静态形态（子区层）：三柱与白膜——只给「可触及／不可触及」与方位档。
//
// 派工单 §④ 的边界：**不做解剖图、不做横断面**。所以这里只有两张小表：
// 三根柱各自在哪一侧（背左／背右／腹中）、摸得到摸不到；白膜能不能摸到。
// 没有半径、没有壁厚、没有层次图——那些进了 814（截面廓形）与 660（形体参数）的地盘。
//
// 一条容易写错的常识落在这里：**三根柱是分不出单根的**。疲劳时背侧摸到的是一条整体的硬脊，
// 不是「两根并排的管子」；所以三柱一律记「不可触及」，整体硬度归 660 的硬度档，不在这里再记一笔。
package com.example.zhengdao.baopu.state.subzone

import com.example.zhengdao.baopu.state.bodyparts.BodyPartGuard
import com.example.zhengdao.baopu.state.bodyparts.Verdict

/** 三根柱：两根阴茎海绵体（背侧左右各一）＋一根尿道海绵体（腹侧正中）。 */
enum class Column(val label: String) {
    CAVERNOSUM_LEFT("左海绵体"),
    CAVERNOSUM_RIGHT("右海绵体"),
    SPONGIOSUM("尿道海绵体"),
}

/** 可触及两档。没有第三档「部分可及」——分不出单根就是分不出。 */
enum class Touchability(val label: String) {
    PALPABLE("可触及"),
    IMPALPABLE("不可触及"),
}

/** 方位档：背左／背右／腹中。只有这三格，因为只有三根柱。 */
enum class ColumnBearing(val label: String) {
    DORSAL_LEFT("背左"),
    DORSAL_RIGHT("背右"),
    VENTRAL_MID("腹中"),
}

/** 一根柱的一行：哪根、在哪、摸不摸得到，外加一句说明（禁数值、禁判定词）。 */
data class ColumnSpec(
    val column: Column,
    val bearing: ColumnBearing,
    val touch: Touchability,
    val note: String,
) {
    init {
        require(note.isNotBlank()) { "${column.label}：缺一句说明" }
        BodyPartGuard.rejectMeasures(note)
        BodyPartGuard.rejectAbsoluteColor(note)
        BodyPartGuard.rejectDiagnosis(note)
    }

    fun line(): String = column.label + "|" + bearing.label + "|" + touch.label
}

/**
 * 三柱表。构造即守两条：三根齐（缺一即抛）、方位不重复（两根柱记在同一侧＝两个真相）。
 * 判据（哪根该在哪一侧、摸不摸得到）在 [Tunica.findings] 里，可被负例打中。
 */
object ColumnTable {

    val entries: List<ColumnSpec> = listOf(
        ColumnSpec(
            Column.CAVERNOSUM_LEFT, ColumnBearing.DORSAL_LEFT, Touchability.IMPALPABLE,
            "背侧偏左的一根，与右侧那根并成一条硬脊，分不出单根",
        ),
        ColumnSpec(
            Column.CAVERNOSUM_RIGHT, ColumnBearing.DORSAL_RIGHT, Touchability.IMPALPABLE,
            "背侧偏右的一根，与左侧那根并成一条硬脊，分不出单根",
        ),
        ColumnSpec(
            Column.SPONGIOSUM, ColumnBearing.VENTRAL_MID, Touchability.IMPALPABLE,
            "腹侧正中一根，尿道走在里面，表面摸到的是沟不是柱",
        ),
    )

    private val byColumn: Map<Column, ColumnSpec> = entries.associateBy { it.column }

    init {
        require(byColumn.size == entries.size) { "三柱表同一根柱登记了两次" }
        require(byColumn.keys.containsAll(Column.entries.toSet())) { missingColumns(byColumn) }
        require(entries.map { it.bearing }.toSet().size == entries.size) { "两根柱记在同一方位：方位只有一个真相" }
    }

    fun spec(column: Column): ColumnSpec = byColumn.getValue(column)

    fun line(): String = entries.joinToString("\n") { it.line() }

    private fun missingColumns(byColumn: Map<Column, ColumnSpec>): String {
        val missing = Column.entries.filterNot { byColumn.containsKey(it) }
        return "三柱表漏了柱：" + missing.joinToString("、") { it.label }
    }
}

/**
 * 白膜：包被三柱的那层鞘。只记两件事——摸不到、包着哪三根。
 *
 * 不做壁厚、不做分层（814 的地盘），也不记「紧不紧」：那是 660 的紫红防漂锁与硬度档要管的事。
 */
object Tunica {

    const val LABEL: String = "白膜"
    const val NOTE: String = "包着三根柱的一层鞘，静置时摸不到它本身"

    val touch: Touchability = Touchability.IMPALPABLE

    val wraps: List<Column> get() = Column.entries

    /**
     * 三柱与白膜的判据（可被负例打中）：
     * ① 尿道海绵体必须在腹中，两根阴茎海绵体必须分居背左与背右；
     * ② 三柱都记「不可触及」（分不出单根）；
     * ③ 白膜记「不可触及」，且包被三柱。
     */
    fun findings(specs: List<ColumnSpec>): List<SubzoneFinding> {
        val out = mutableListOf<SubzoneFinding>()
        out += bearingFindings(specs)
        out += specs.filter { it.touch != Touchability.IMPALPABLE }.map { spec ->
            SubzoneFinding(
                Verdict.DENY, "SZ-COLUMN-TOUCH", null,
                spec.column.label + "记成「" + spec.touch.label + "」：三根柱分不出单根，只能记不可触及",
            )
        }
        if (touch != Touchability.IMPALPABLE) {
            out += SubzoneFinding(
                Verdict.DENY, "SZ-TUNICA-TOUCH", null,
                LABEL + "记成「" + touch.label + "」：它包在皮下，静置时摸不到它本身",
            )
        }
        if (wraps.toSet() != Column.entries.toSet()) {
            out += SubzoneFinding(Verdict.DENY, "SZ-TUNICA-WRAP", null, LABEL + "没有包全三根柱：少一根就是另一件事")
        }
        return out
    }

    private fun bearingFindings(specs: List<ColumnSpec>): List<SubzoneFinding> {
        val expected = mapOf(
            Column.CAVERNOSUM_LEFT to ColumnBearing.DORSAL_LEFT,
            Column.CAVERNOSUM_RIGHT to ColumnBearing.DORSAL_RIGHT,
            Column.SPONGIOSUM to ColumnBearing.VENTRAL_MID,
        )
        return specs.mapNotNull { spec ->
            val want = expected[spec.column] ?: return@mapNotNull null
            if (spec.bearing == want) return@mapNotNull null
            SubzoneFinding(
                Verdict.DENY, "SZ-COLUMN-BEARING", null,
                spec.column.label + "记在「" + spec.bearing.label + "」：应为「" + want.label + "」——两根在背、一根在腹",
            )
        }
    }
}
