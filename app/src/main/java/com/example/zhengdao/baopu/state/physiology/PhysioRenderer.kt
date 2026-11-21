// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百五十八批 · 唯一渲染出口：把四状态与三液分道拼成稳定文本。
// 纯函数：零 IO、零时钟、零随机、零模型；同一输入两次调用逐字节相同（判据⑥）。
package com.example.zhengdao.baopu.state.physiology

internal object PhysioRenderer {

    /** 固定顺序拼读回文本；顺序由代码顺序钉死，不依赖任何集合迭代次序。 */
    fun render(state: LightPhysiology): String = listOf(
        "体温偏移=" + reading(state.bodyTempShift),
        "心率档=" + reading(state.heartRateBand),
        "出汗=" + reading(state.sweatLevel),
        "疲劳=" + reading(state.fatigue),
        "三液[汗=" + fluid(state.fluids.sweat) +
            "/唾液=" + fluid(state.fluids.saliva) +
            "/前液=" + fluid(state.fluids.preSeminal) + "]",
    ).joinToString(" · ") + " · $PHYSIO_DISCLAIMER"

    /** 档名 + 驱动名；零档无驱动时标短横，不给任何数值。 */
    private fun <T : PhysioGrade> reading(r: GradeReading<T>): String =
        r.grade.label + "(" + (if (r.drivers.isEmpty()) "-" else r.drivers.joinToString("+") { it.label }) + ")"

    /** 三液每条：无 / 量档(驱动)；不写体积数。 */
    private fun fluid(f: FluidTrack): String =
        if (f.present) f.amount.label + "(" + f.drivers.joinToString("+") { it.label } + ")" else "无"
}
