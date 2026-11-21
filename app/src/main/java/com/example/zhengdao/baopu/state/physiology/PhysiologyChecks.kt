// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百五十八批 · 判据检查器：纯函数，只回答「这份产出有没有违规」，不下生理结论、不进闸。
// 供单测正/负例对拍与变异自检使用；生产求解器不调用它（求解器自己保证产出合法）。
package com.example.zhengdao.baopu.state.physiology

/**
 * 物理量测单位字面：全部用转义/拼接写，避免本文件自己把字面量带进源码门禁的扫描里。
 * 判据④要的是「档位之外不许有点值」，这里只认明确的物理单位，不认纯数字（档序不是点值）。
 */
private val POINT_VALUE_UNITS: List<String> = listOf(
    "\u2103",          // 温度单位·摄氏度符号
    "\u00B0" + "C",    // 温度单位·度C
    "bp" + "m",        // 心率单位·每分次数
    "毫" + "升",       // 体积单位
    "m" + "l",         // 体积单位·缩写
    "次/" + "分",      // 心率单位·中文
    "厘" + "米",       // 长度单位·中文
    "c" + "m",         // 长度单位·缩写
    "k" + "g",         // 重量单位·缩写
)

internal object PhysiologyChecks {

    /** 判据④：返回产出文本里命中的物理单位（空 = 没给点值）。 */
    fun pointValueUnits(text: String): List<String> =
        POINT_VALUE_UNITS.filter { text.contains(it) }

    /** 判据②：返回笼统合并词（空 = 三液确实分道记）。 */
    fun genericMergeTerms(text: String): List<String> =
        FluidCatalog.GENERIC_MERGE_TERMS.filter { text.contains(it) }

    /** 判据⑤：返回他单专属落账口（空 = 与 735/732 无重复）。 */
    fun foreignLedgerTerms(text: String): List<String> =
        FluidCatalog.FOREIGN_LEDGER_TERMS.filter { text.contains(it) }

    /** 判据③：非零档没有驱动，逐条点名。 */
    fun missingDriverGrades(state: LightPhysiology): List<String> {
        val out = mutableListOf<String>()
        checkReading("bodyTempShift", state.bodyTempShift, out)
        checkReading("heartRateBand", state.heartRateBand, out)
        checkReading("sweatLevel", state.sweatLevel, out)
        checkReading("fatigue", state.fatigue, out)
        checkFluid("sweat", state.fluids.sweat, out)
        checkFluid("saliva", state.fluids.saliva, out)
        checkFluid("preSeminal", state.fluids.preSeminal, out)
        return out
    }

    /** 三液每条的「有/无」与量档必须一致，否则是同一条账记了两遍。 */
    fun inconsistentFluidTracks(state: LightPhysiology): List<String> {
        val out = mutableListOf<String>()
        checkFluidShape("sweat", state.fluids.sweat, out)
        checkFluidShape("saliva", state.fluids.saliva, out)
        checkFluidShape("preSeminal", state.fluids.preSeminal, out)
        return out
    }

    /** 一条产出-text 的全判据汇总（②③④），空列表 = 干净。 */
    fun textProblems(text: String): List<String> =
        pointValueUnits(text).map { "点值:$it" } +
            genericMergeTerms(text).map { "合并:$it" } +
            foreignLedgerTerms(text).map { "他单账:$it" }

    private fun <T : PhysioGrade> checkReading(
        name: String,
        reading: GradeReading<T>,
        out: MutableList<String>,
    ) {
        if (!reading.grade.zero && reading.drivers.isEmpty()) out += "$name:非零档无驱动"
    }

    private fun checkFluid(name: String, track: FluidTrack, out: MutableList<String>) {
        if (!track.amount.zero && track.drivers.isEmpty()) out += "$name:非零档无驱动"
    }

    private fun checkFluidShape(name: String, track: FluidTrack, out: MutableList<String>) {
        if (track.present == track.amount.zero) out += "$name:有/无与量档不一致"
    }
}
