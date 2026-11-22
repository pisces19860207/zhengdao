// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百八十批 · 唯一渲染出口：把六个判定器的产出拼成稳定文本。
// 纯函数：零 IO、零时钟、零随机、零模型；同一输入两次调用逐字节相同（判据⑧）。
// 段落顺序由代码顺序钉死，不依赖任何集合迭代次序；全文只有档名，没有点值。
package com.example.zhengdao.baopu.state.fluid

internal object FluidRenderer {

    /** ① 四维变化：四维分列，每维「档名(驱动)」；零档不给驱动位。 */
    fun renderChange(body: FluidBody): String = listOf(
        "量=" + read(body.volume),
        "黏=" + read(body.viscosity),
        "温=" + read(body.temperature),
        "干=" + read(body.dryness),
    ).joinToString("/", prefix = "四维[", postfix = "]") +
        "·外观=" + FluidLook.appearanceOf(body).label +
        "·" + FLUID_DISCLAIMER

    /** ② 混合：主液先写，次液其次，再写规则、外观、流动、黏度、干得快慢。 */
    fun renderMix(m: Mixture): String = listOf(
        "主=" + m.dominant.label,
        "次=" + (m.secondary?.label ?: "-"),
        "规则=" + m.rule.label,
        "外观=" + m.appearance.label,
        "流动=" + m.flow.label,
        "黏度=" + m.viscosity.label,
        "干=" + m.dryHint.label,
    ).joinToString("/", prefix = "混合[", postfix = "]") + "·" + FLUID_DISCLAIMER

    /** ③ 垂坠：倾向档后面必须紧跟体位——体位是必填项，永远不是短横。 */
    fun renderDroop(d: DroopReading): String =
        "垂坠=" + d.tendency.label + "(体位=" + d.posture.label + ")·" + FLUID_DISCLAIMER

    /** ④ 织物：由外到内逐层「材质=结果」，层数如实列出。 */
    fun renderFabric(stack: FabricStack): String =
        stack.layers.joinToString("/", prefix = "织物[", postfix = "]") { it.material.label + "=" + it.outcome.label } +
            "·" + FLUID_DISCLAIMER

    /** ⑤ 覆盖：认定的覆盖 + 该量档的上限 + 载体（没给就写短横）。 */
    fun renderCoverage(r: CoverageReading): String =
        "覆盖=" + r.coverage.label + "(上限=" + r.maxAllowed.label +
            "/载体=" + (r.carrier?.label ?: "-") + ")·" + FLUID_DISCLAIMER

    /** ⑥ 离体：并存就逐段列，段序即记录序。 */
    fun renderDeparture(set: DepartureSet): String = when (set.kind) {
        DepartureKind.MIXED -> set.segments.joinToString("/", prefix = "并存[", postfix = "]") { renderSegment(it) }
        else -> renderSegment(set.segments[0])
    } + "·" + FLUID_DISCLAIMER

    /** 单段文案：并存时的判据⑤要逐段对拍，所以这一段必须能单独取出来。 */
    fun renderSegment(part: DeparturePart): String = when (part) {
        is Ejection -> "喷射(驱动=" + part.drive.label + "/初速=" + part.axis.label +
            "/其后只受重力/落点=" + part.landing.target + "·" + part.landing.surface.label + ")"
        is Shed -> "甩出(强度=" + part.intensity.label + "/脱离=" + part.detach.label +
            "/甩向=" + part.dir.label + "/无盆底参与)"
        is Seepage -> "重力流(贴体/无初速)"
    }

    private fun <G : FluidGrade> read(r: GradeRead<G>): String =
        r.grade.label + "(" + (if (r.drivers.isEmpty()) "-" else r.drivers.joinToString("+") { it.label }) + ")"
}
