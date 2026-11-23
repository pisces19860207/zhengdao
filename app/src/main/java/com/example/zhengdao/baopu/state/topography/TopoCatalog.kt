// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百六十六批 · 地形目录表：哪一族有哪几档、哪几维分左右。
//
// 这张表是**唯一真相**：档名从档位枚举取，这里只登记「族 → 档位枚举」，不另抄一份档名，
// 免得枚举改了表没改。构造时把「漏一族、档序不连续、左右维数对不上」全部拦下。
//
// 两个数一定要盯住（改档就会破）：
// · 分左右的维 11（凸 4＋凹 5＋厚 2），中轴 4（腹直肌沟＋胸厚／腹厚／臀厚）；
// · 每维可选档数合计 55（凸 5×4 ＋ 凹 5×4 ＋ 厚 5×3）。
package com.example.zhengdao.baopu.state.topography

/** 地形目录表。 */
object TopoCatalog {

    val RELIEF_DIMS: List<ReliefDim> = ReliefDim.entries

    val HOLLOW_DIMS: List<HollowDim> = HollowDim.entries

    val DEPTH_DIMS: List<DepthDim> = DepthDim.entries

    /** 族 → 档位枚举（唯一真相）。顺序＝族声明序（凸／凹／厚）。 */
    val GRADES: Map<TopoFamily, List<TopoGrade>> = mapOf(
        TopoFamily.RELIEF to ReliefGrade.entries,
        TopoFamily.HOLLOW to HollowGrade.entries,
        TopoFamily.DEPTH to DepthGrade.entries,
    )

    /** 分左右的维数。判据③④ 的「11 对」由它算出来，不手写。 */
    const val LATERAL_COUNT: Int = 11

    /** 中轴维数。 */
    const val MIDLINE_COUNT: Int = 4

    init {
        require(GRADES.size == TopoFamily.COUNT) {
            "目录表漏了族：" + TopoFamily.entries.filterNot { GRADES.containsKey(it) }
                .joinToString("、") { it.label }
        }
        GRADES.forEach { (family, list) ->
            require(list.isNotEmpty()) { family.label + "：档位不能为空" }
            require(list.map { it.level } == list.indices.toList()) {
                family.label + "：档序必须 0..n 连续，否则按档序取档会取错"
            }
        }
        require(allDims().size == RELIEF_DIMS.size + HOLLOW_DIMS.size + DEPTH_DIMS.size) {
            "维数对不上：凸／凹／厚三族维名重复了"
        }
        require(lateralDims().size == LATERAL_COUNT) {
            "分左右的维应为 " + LATERAL_COUNT + " 个，实为 " + lateralDims().size
        }
        require(allDims().size - lateralDims().size == MIDLINE_COUNT) {
            "中轴维应为 " + MIDLINE_COUNT + " 个"
        }
    }

    /** 全部维，顺序＝凸／凹／厚。 */
    fun allDims(): List<TopoDim> = RELIEF_DIMS + HOLLOW_DIMS + DEPTH_DIMS

    fun lateralDims(): List<TopoDim> = allDims().filter { it.lateral }

    fun grades(family: TopoFamily): List<TopoGrade> = GRADES.getValue(family)

    /** 某维可选的档（按族取）。 */
    fun choicesOf(dim: TopoDim): List<TopoGrade> = grades(dim.family)

    /** 某族最高档序。 */
    fun orderMax(family: TopoFamily): Int = grades(family).lastIndex

    /** 某族的档名序列（对外快照用，不手抄）。 */
    fun words(family: TopoFamily): List<String> = grades(family).map { it.label }

    /** 骨性隆起维（判据②「胖则被覆盖」只管这几个）。 */
    fun bonyDims(): List<ReliefDim> = RELIEF_DIMS.filter { it.bony }

    /** 每维可选档数合计（表驱动，不手数）。 */
    val totalChoices: Int get() = allDims().sumOf { choicesOf(it).size }
}
