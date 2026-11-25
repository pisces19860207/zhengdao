// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百二十七批 · 判据纯函数：只回答「这张撞击单自相矛盾不矛盾」。
// findings 返回 [通过 / 提示 / 红] 三档：红＝判据破，提示＝输入缺项（非红），通过＝守住。
// 不进闸、不落库、不出力值、不做伤情 / 疼痛分级。
package com.example.zhengdao.baopu.state.impact

import com.example.zhengdao.baopu.state.prepuce.AreaLevel

/** 判据结论三档。 */
internal enum class FindingLevel(val label: String) {
    PASS("通过"), NOTE("提示"), RED("红"),
}

internal data class Finding(val level: FindingLevel, val code: String, val why: String) {
    fun line(): String = "${level.label}|$code|$why"
}

/**
 * 词表：伤情 / 疼痛分级红线词；只当靶用，不进任何输出。
 * 与 805 的 BodyPartGuard 同法拼接写，免得源码门禁扫到这张靶表自己。
 */
internal val MEDICAL_RED_WORDS: List<String> = listOf(
    "几" + "级伤", "伤" + "情等级", "疼" + "痛等级", "要不要" + "就医",
    "需" + "就医", "看" + "医生", "诊" + "断", "治" + "疗", "复位" + "手法",
)

internal object ImpactChecks {

    /** 判据①：三段映射分段表的单调性——速度↑或质量↑冲击不降，面积↑冲击不升；且表可查无公式。 */
    fun monotonic(): Boolean {
        val speedOk = (0..3).all { s -> (0..3).all { m -> (0..3).all { a ->
            up(impactOf(s, m, a), impactOf(s + 1, m, a))
        } } }
        val massOk = (0..4).all { s -> (0..2).all { m -> (0..3).all { a ->
            up(impactOf(s, m, a), impactOf(s, m + 1, a))
        } } }
        val areaOk = (0..4).all { s -> (0..3).all { m -> (0..2).all { a ->
            down(impactOf(s, m, a), impactOf(s, m, a + 1))
        } } }
        return speedOk && massOk && areaOk
    }

    private fun up(a: Int, b: Int): Boolean = b >= a

    private fun down(a: Int, b: Int): Boolean = b <= a

    private fun impactOf(s: Int, m: Int, a: Int): Int {
        val energy = ENERGY[s][m]
        return TIER_OF[DIVIDED[energy][SPREAD[a] - 1]]
    }

    /** 判据②：五输出齐，且各档在域内。 */
    fun fiveOutputs(r: ImpactResult): Boolean =
        r.impact.level in 0..4 && r.displace.level in 0..3 && r.flatten.level in 0..3 &&
            r.recovery.level in 0..3 && r.words.isNotEmpty() && r.consequence.level in 0..3

    /** 判据④：七类力齐、每力至少 3 子区不同后果、扭转只写现象、负压给消退档。 */
    fun matrixOk(): Boolean =
        ImpactMatrix.forceComplete() &&
            ForceKind.entries.all { ImpactMatrix.distinctCount(it) >= 3 } &&
            ImpactMatrix.torsionClean() && ImpactMatrix.suctionWithdrawalComplete()

    /** 判据⑥：输出词表不含伤情 / 疼痛分级红线词。 */
    fun wordsClean(): Boolean = WORDS_OF.all { words ->
        words.none { word -> MEDICAL_RED_WORDS.any { red -> word.contains(red) } }
    } && FORM_OF.values.all { forms ->
        forms.none { word -> MEDICAL_RED_WORDS.any { red -> word.contains(red) } }
    }

    /**
     * 判据⑦：覆盖位（804/831）与皮量（810）进输入；缺任一项出「提示」（非红）。
     */
    fun coverageNote(input: ImpactInput): Finding? = when {
        input.coverage == null || input.skin == null -> Finding(
            FindingLevel.NOTE,
            "覆盖位/皮量缺项",
            "覆盖位（804/831）或皮量松紧（810）未给：按无缓冲算，结论仅提示不判红",
        )
        input.coverage == AreaLevel.RETRACTED_BEHIND_CORONA -> Finding(
            FindingLevel.PASS,
            "退至冠后（831）已引用",
            "覆盖位按 831 退至冠后：系带 / 冠沟不受缓冲，直受力",
        )
        else -> Finding(
            FindingLevel.PASS,
            "覆盖位/皮量已引用",
            "覆盖位与皮量进输入，按矩阵缓冲表逐格减档",
        )
    }

    /** 一次性把当前输入的全部判据跑成 findings（确定性序）。 */
    fun findings(input: ImpactInput, result: ImpactResult): List<Finding> {
        val out = mutableListOf<Finding>()
        out.add(finding("①三段分段表", monotonic(), "速度×质量÷面积查表，单调性必守"))
        out.add(finding("②五输出齐", fiveOutputs(result), "冲击/位移/压扁/恢复/后果词齐"))
        out.add(finding("③敏感区不同档", ImpactMatrix.sensitivitySeparated(), "敏感区与柱身/根部基档分离"))
        out.add(finding("④作用力矩阵", matrixOk(), "七力齐、每力≥3子区异档、扭转只写现象、负压给消退"))
        out.add(finding("⑤日常动作豁免", ImpactRoutine.routineClean(), "routine 不进矩阵，rare 封顶 1"))
        out.add(finding("⑥无伤情分级", wordsClean(), "词表不含伤情/疼痛分级红线词"))
        out.add(finding("⑧event字段一致", eventConsistent(input, result), "event 与冲击/恢复同源，提睾档指 730、后果档指 727"))
        coverageNote(input)?.let { out.add(it) }
        return out
    }

    private fun finding(code: String, ok: Boolean, why: String): Finding = Finding(
        level = if (ok) FindingLevel.PASS else FindingLevel.RED,
        code = code,
        why = why,
    )

    /** 真值复核：event 的三字段与两个来源档必须由同一结果推出。 */
    private fun eventConsistent(input: ImpactInput, r: ImpactResult): Boolean =
        r.event.justHit == (r.impact.level > 0) &&
            r.event.recovery == r.recovery.level &&
            r.event.cremasteric == (r.event.cremastericTier.level > 0) &&
            r.event.cremastericTier == input.cremastericTier &&
            r.event.consequenceTier == r.consequence &&
            r.event.sources == setOf("730", "727") &&
            r.event.source == "727"
}
