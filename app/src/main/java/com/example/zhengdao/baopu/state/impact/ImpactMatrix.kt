// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百二十七批 · 七类力 × 13 子区矩阵（§追加·作用力矩阵）。
// 每格给后果档 0–3（无/轻微/明显/剧烈）＋是否允许描写＋依据一句；不适用写「不适用」不留空。
// 扭转是医学禁区：只许写「剧痛 / 失能」这一现象，不写任何处置。
// 日常动作（掏/拉链/解裤/坐下/起身/排尿）不进本矩阵，见 ImpactRoutine。
package com.example.zhengdao.baopu.state.impact

import com.example.zhengdao.baopu.state.prepuce.AreaLevel
import com.example.zhengdao.baopu.state.prepuce.SkinAmount

/** 子区顺序＝[ImpactSite] 声明序：龟头 冠沟 系带 尿道口 内板 外板 柱身 根部 阴囊 睾丸 精索 耻骨 会阴。 */
internal val MATRIX_TIER: Map<ForceKind, IntArray> = mapOf(
    ForceKind.COMPRESSION to intArrayOf(2, 2, 3, 2, 2, 1, 1, 1, 1, 2, 1, 0, 1),
    ForceKind.BLUNT to intArrayOf(3, 3, 3, 3, 2, 2, 1, 1, 2, 3, 3, 0, 1),
    ForceKind.FRICTION to intArrayOf(2, 2, 3, 2, 2, 1, 1, 0, 1, 1, 0, 0, 1),
    ForceKind.TRACTION to intArrayOf(2, 3, 3, 2, 2, 2, 1, 1, 1, 1, 2, 0, 1),
    ForceKind.SHEAR to intArrayOf(2, 2, 3, 1, 2, 1, 0, 0, 1, 1, 1, 0, 1),
    ForceKind.TORSION to intArrayOf(-1, -1, -1, -1, -1, -1, -1, 3, -1, 3, 2, -1, -1),
    ForceKind.SUCTION to intArrayOf(2, 2, 2, 2, 2, 1, 1, 0, -1, -1, -1, -1, 0),
)

/** 后果形态表：剧烈档必须挂形态（发红 / 肿胀 / 破损 / 失能 / 瘀点），不许只写「很痛」。 */
internal val FORM_OF: Map<ForceKind, List<String>> = mapOf(
    ForceKind.COMPRESSION to listOf("未见变化", "闷胀", "发红、闷胀", "肿胀、压痕"),
    ForceKind.BLUNT to listOf("未见变化", "一沉", "一沉、发红", "肿胀、短暂停顿"),
    ForceKind.FRICTION to listOf("未见变化", "发热", "发红、微破损", "破损、肿胀"),
    ForceKind.TRACTION to listOf("未见变化", "牵拉感", "牵拉、发红", "破损、肿胀"),
    ForceKind.SHEAR to listOf("未见变化", "擦动感", "发红、毛囊受力", "表皮破损"),
    ForceKind.TORSION to listOf("不适用", "剧痛", "剧痛", "剧痛、失能"),
    ForceKind.SUCTION to listOf("未见变化", "充血", "充血、瘀点", "瘀点、肿胀"),
)

/** 负压撤除后消退档；不适用子区为 null。 */
internal val WITHDRAWAL_OF: Array<WithdrawalTier?> = arrayOf(
    WithdrawalTier.HOURS, WithdrawalTier.HOURS, WithdrawalTier.MINUTES, WithdrawalTier.MINUTES,
    WithdrawalTier.HOURS, WithdrawalTier.MINUTES, WithdrawalTier.MINUTES, WithdrawalTier.MINUTES,
    null, null, null, null, WithdrawalTier.MINUTES,
)

/** 有覆盖位缓冲的子区：内板 / 外板 / 龟头 / 冠沟 / 系带 / 尿道口 / 柱身。 */
internal val BUFFERED_SITES: Set<ImpactSite> = setOf(
    ImpactSite.GLANS, ImpactSite.CORONA, ImpactSite.FRENULUM, ImpactSite.MEATUS,
    ImpactSite.PREPUCE_INNER, ImpactSite.PREPUCE_OUTER, ImpactSite.SHAFT,
)

internal val SENSITIVE_SITES: Set<ImpactSite> =
    setOf(ImpactSite.GLANS, ImpactSite.CORONA, ImpactSite.FRENULUM)

internal val ROBUST_SITES: Set<ImpactSite> = setOf(ImpactSite.SHAFT, ImpactSite.ROOT)

/** 一格矩阵结论。[tier] = -1 表示不适用；[allowDepict] 为是否允许描写。 */
internal data class MatrixCell(
    val force: ForceKind,
    val site: ImpactSite,
    val tier: Int,
    val form: String,
    val allowDepict: Boolean,
    val withdrawal: WithdrawalTier?,
    val basis: String,
) {
    val tierLabel: String get() = if (tier < 0) "不适用" else ConsequenceTier.of(tier).label

    fun line(): String = buildString {
        append(force.label).append('|').append(site.label).append('|').append(tierLabel)
        append('|').append(form)
        append('|').append(if (allowDepict) "可描写" else "不描写")
        if (withdrawal != null) append("|消退=").append(withdrawal.label)
        append('|').append(basis)
    }
}

/** 覆盖位缓冲：覆盖（804 FULL_COVER / ALMOST_ALL / OVER_HALF）与 810 皮量富余各减一档，下限 0。 */
internal object ImpactMatrix {

    fun bufferSteps(coverage: AreaLevel?, skin: SkinAmount?): Int {
        var steps = 0
        if (coverage != null && coverage.order <= 2) steps++
        if (skin == SkinAmount.AMPLE) steps++
        return steps
    }

    /** 查表主口：先取基档，再按覆盖位 / 皮量缓冲；扭转固定为现象档，不参与缓冲。 */
    fun cell(
        force: ForceKind,
        site: ImpactSite,
        coverage: AreaLevel? = null,
        skin: SkinAmount? = null,
    ): MatrixCell {
        val base = MATRIX_TIER.getValue(force)[site.ordinal]
        val tier = if (force == ForceKind.TORSION || site !in BUFFERED_SITES) {
            base
        } else if (base <= 0) {
            base
        } else {
            (base - bufferSteps(coverage, skin)).coerceAtLeast(0)
        }
        val form = if (tier < 0) "不适用" else FORM_OF.getValue(force)[tier]
        return MatrixCell(
            force = force,
            site = site,
            tier = tier,
            form = form,
            allowDepict = tier >= 0 && force != ForceKind.TORSION,
            withdrawal = if (force == ForceKind.SUCTION) WITHDRAWAL_OF[site.ordinal] else null,
            basis = basisOf(site),
        )
    }

    /** ◐ 依据一句：敏感区神经末梢密集 / 粘膜薄，同强度下反应更强。 */
    fun basisOf(site: ImpactSite): String = when {
        site in SENSITIVE_SITES -> "◐ 神经末梢密集、粘膜薄，同强度下反应更强（常识）"
        site in ROBUST_SITES -> "◐ 皮肤与皮下组织较厚，同强度下反应更钝（常识）"
        else -> "◐ 工程假设：按派工单子区清单逐区记档，非医学判定"
    }

    /** 同一类力在几个子区给出不同后果（判据④：≥3）。 */
    fun distinctCount(force: ForceKind): Int =
        ImpactSite.entries.map { cell(force, it).tierLabel }.distinct().size

    /** 七类力是否齐全（判据④）。 */
    fun forceComplete(): Boolean = ForceKind.entries.size == 7 && MATRIX_TIER.size == 7

    /** 扭转只写现象：形态词不得越出「剧痛 / 失能」一族，且不许出现处置词。 */
    fun torsionClean(): Boolean = ImpactSite.entries
        .map { cell(ForceKind.TORSION, it) }
        .all { c -> c.form == "不适用" || c.form.split('、').all { it == "剧痛" || it == "失能" } }

    /** 负压：所有适用格必须给撤除后消退档。 */
    fun suctionWithdrawalComplete(): Boolean = ImpactSite.entries
        .map { cell(ForceKind.SUCTION, it) }
        .all { c -> c.tier < 0 || c.withdrawal != null }

    /**
     * 同一强度下敏感区不得与柱身 / 根部同档（判据③）：逐力取「适用格」比较基档。
     * 扭转只有根部 / 睾丸 / 精索适用，敏感区全为不适用 ⇒ 该力不参与比较（无同档可言）。
     */
    fun sensitivitySeparated(): Boolean = ForceKind.entries.all { force ->
        val base = MATRIX_TIER.getValue(force)
        val sensitive = SENSITIVE_SITES.map { base[it.ordinal] }.filter { it >= 0 }
        val robust = ROBUST_SITES.map { base[it.ordinal] }.filter { it >= 0 }
        sensitive.isEmpty() || robust.isEmpty() || sensitive.min() > robust.max()
    }

    /** 全矩阵行（确定性序：力序 × 子区序）。 */
    fun rows(): List<MatrixCell> = ForceKind.entries.flatMap { force ->
        ImpactSite.entries.map { cell(force, it) }
    }
}
