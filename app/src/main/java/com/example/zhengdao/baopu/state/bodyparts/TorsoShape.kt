// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百四十八批 · 躯干形状单：一条形状怎么写、怎么出行。
//
// 「五项齐」在这里是**结构性**的：TorsoShape 的八个字段全是非空枚举，少一项根本构造不出来；
// 想记「还没看」的维度，只能用该维的零档（界不清／被脂覆盖／无）——零档是事实，空字段不是。
//
// 唯一出口 [TorsoShape.line] 出门前过 805 的两道闸（不出长度／百分比、不出诊断词），
// 闸是同一个对象（BodyPartGuard），本包不另立一套，免得两套口径。
package com.example.zhengdao.baopu.state.bodyparts

/**
 * 躯干形状（八维，全部离散档）。
 *
 * @param rectusBlocks 腹直肌块数（含零档「界不清」）
 * @param lineaAlba 白线深浅（含零档「被脂覆盖」）
 * @param blockAlignment 腹直肌左右两列是否等高
 * @param pecLowerTrend 胸肌下缘走向
 * @param pecGap 胸沟宽窄
 * @param trapSlope 斜方肌坡度
 * @param latFlare 背阔外扩
 * @param serratus 前锯肌
 */
data class TorsoShape(
    val rectusBlocks: RectusBlocks,
    val lineaAlba: LineaAlba,
    val blockAlignment: BlockAlignment,
    val pecLowerTrend: PecLowerTrend,
    val pecGap: PecGapWidth,
    val trapSlope: TrapSlope,
    val latFlare: LatFlare,
    val serratus: Serratus,
) {

    /** 按维取档（表驱动：判据与出口都走这一个口子，不在判据里另写 when）。 */
    fun of(dim: TorsoDimension): TorsoGrade = when (dim) {
        TorsoDimension.RECTUS_BLOCKS -> rectusBlocks
        TorsoDimension.LINEA_ALBA -> lineaAlba
        TorsoDimension.BLOCK_ALIGNMENT -> blockAlignment
        TorsoDimension.PEC_LOWER_TREND -> pecLowerTrend
        TorsoDimension.PEC_GAP -> pecGap
        TorsoDimension.TRAP_SLOPE -> trapSlope
        TorsoDimension.LAT_FLARE -> latFlare
        TorsoDimension.SERRATUS -> serratus
    }

    /** 档位向量（维度声明序）。判定层做指纹、比对用。 */
    fun levels(): List<Int> = TorsoDimension.entries.map { levelOf(it) }

    fun levelOf(dim: TorsoDimension): Int = of(dim).level

    /** 一行机读快照：维=档，维=档…（顺序＝维度声明序，逐字可回放）。 */
    fun line(): String {
        val text = TorsoDimension.entries.joinToString(SEP) { dim -> dim.label + MARK + of(dim).label }
        BodyPartGuard.rejectMeasures(text)
        BodyPartGuard.rejectDiagnosis(text)
        return text
    }

    companion object {
        const val SEP: String = "，"
        const val MARK: String = "="

        /** 八维全同的判据用这个：形状相同就是同一个人（体积另算）。 */
        fun sameShape(a: TorsoShape, b: TorsoShape): Boolean = TorsoCatalog.diff(a, b).isEmpty()
    }
}

/**
 * 体积签。**全部复用 805 既有档，本包一个新造体积档都没有**（判据⑤ 的落点）。
 * 它只回答一个问题：两人「是不是只差在量」。不参与形状判据，也不与 805 的体脂判据抢账。
 *
 * @param pec 805 `PecVolume`（胸肌体积）
 * @param abs 805 `AbsBlock`（分块显出多少——这里只当「多显」的体量代理）
 * @param arm 805 `Girth`（上肢围度档）
 */
data class TorsoVolume(
    val pec: PecVolume,
    val abs: AbsBlock,
    val arm: Girth,
) {

    /** 体积是否不同。任一维档不同即不同。 */
    fun differs(o: TorsoVolume): Boolean = pec != o.pec || abs != o.abs || arm != o.arm

    /** 逐维说清哪几项不同（只列不同的，没有就空串）。 */
    fun diffWords(o: TorsoVolume): String {
        val parts = mutableListOf<String>()
        if (pec != o.pec) parts += "胸肌 " + pec.label + "→" + o.pec.label
        if (abs != o.abs) parts += "腹肌 " + abs.label + "→" + o.abs.label
        if (arm != o.arm) parts += "上肢 " + arm.label + "→" + o.arm.label
        return parts.joinToString("；")
    }

    fun line(): String = "体积｜胸肌=" + pec.label + "，腹肌=" + abs.label + "，上肢=" + arm.label
}

/**
 * 同场一个人。躯干形状＋体积签＋档案。
 *
 * @param roleId 角色标识（判定结论里点名用）
 * @param profile 档案（判据④ 用；档案由 815 定，本包只当校验依据）
 * @param shape 八维形状
 * @param volume 体积签（判据③ 用）
 * @param torsoVisible 躯干是否可见（裸／半裸）。**本包不判穿衣**：由 687 的可见度判定后传布尔进来，
 *   只把可见的人放进「同场 ≥2 人」这条判据
 */
data class TorsoPerson(
    val roleId: String,
    val profile: TorsoProfile,
    val shape: TorsoShape,
    val volume: TorsoVolume,
    val torsoVisible: Boolean,
) {
    init {
        require(roleId.isNotBlank()) { "同场角色缺标识：结论点名不了" }
    }

    fun line(): String =
        roleId + "（" + profile.label + "，" + (if (torsoVisible) "躯干可见" else "躯干不可见") +
            "）｜" + shape.line() + "｜" + volume.line()
}
