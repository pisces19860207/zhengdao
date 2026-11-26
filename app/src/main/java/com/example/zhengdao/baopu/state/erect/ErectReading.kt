// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百一十批 · 五行各自的读数类型。
//
// 关键结构约束（判据② 硬度与尺寸独立）：
// · 硬度读数 [HardnessState] 只有硬度档（810 的 EHS 1..4）＋906 并列的疲软档，没有大小字段
//   ——想写「大且硬」没有入口；
// · 大小读数 [SizeState] 只有分型＋增长档，没有硬度字段；
// · 两者拼进对照表时是两行，不是一格。{大} 不等于 {硬}。
package com.example.zhengdao.baopu.state.erect

import com.example.zhengdao.baopu.state.bodyparts.BodyPartGuard

/** ⑥ 对照表的一行。key 是行 id，五行的 key 见 SheetRow 伴生对象里的 SIZE/ANGLE/HARDNESS/PREPUCE/CORONA。 */
interface SheetRow {
    val key: String
    fun line(): String

    companion object {
        const val SIZE = "大小"
        const val ANGLE = "角度"
        const val HARDNESS = "硬度"
        const val PREPUCE = "包皮位"
        const val CORONA = "冠外扩"
        /** 五行冻结表，表驱动校验，不手数。 */
        val ALL_KEYS: List<String> = listOf(SIZE, ANGLE, HARDNESS, PREPUCE, CORONA)
    }
}

/** ③ 角度两态：疲软一列、勃起一列。两列都必须给，缺一造不出来。 */
data class AnglePair(val flaccid: ErectAngle, val erect: ErectAngle) : SheetRow {
    override val key: String get() = SheetRow.ANGLE

    override fun line(): String {
        val text = "疲软=" + flaccid.label + "／勃起=" + erect.label
        gate(text)
        ErectGuard.rejectAngleValue(text)
        return text
    }
}

/** ①＋② 大小读数：分型与增长档并列，两列都在。 */
data class SizeState(val type: GrowthType, val tier: GrowthTier) : SheetRow {
    override val key: String get() = SheetRow.SIZE
    override fun line(): String {
        val text = "分型=" + type.label + "／增长=" + tier.label
        gate(text)
        return text
    }
}

/**
 * ④ 硬度读数：810 的 [tier]（EHS 1..4）与 906 新增的 [flaccidness] **并列**。
 * **没有、也不许有大小字段**——这是判据②的结构落点；
 * 谁想在这里写「大而硬」，类型上就没有位置，只能另开一行 SizeState。
 *
 * flaccidness = FLACCID 表示"当前疲软"（已知状态，有明确档）；
 * null 表示真的不知道，只渲染 [FULLNESS_ABSENT]，绝不拿来冒充疲软。
 */
data class HardnessState(
    val tier: HardnessTier,
    val flaccidness: FullnessTier? = FullnessTier.FLACCID,
) : SheetRow {
    override val key: String get() = SheetRow.HARDNESS
    override fun line(): String {
        val flaccidText = if (flaccidness == null) {
            FULLNESS_ABSENT
        } else {
            flaccidness.label + "(" + flaccidness.name + ")"
        }
        val text = "硬度=" + flaccidText + "／勃起=" + tier.label
        gate(text)
        return text
    }
}

/**
 * ⑥ 包皮位两态。档位只用字符串引用 804/831 的 AreaLevel 六档，不 import 其类型。
 * 传表外档即红（[PREPUCE_LEVELS] 冻结表）。
 */
data class PrepuceState(val flaccid: String, val erect: String) : SheetRow {
    override val key: String get() = SheetRow.PREPUCE
    init {
        require(flaccid in PREPUCE_LEVELS) { "疲软包皮位档不在 804/831 六档内：$flaccid" }
        require(erect in PREPUCE_LEVELS) { "勃起包皮位档不在 804/831 六档内：$erect" }
    }

    override fun line(): String {
        val text = "疲软=" + flaccid + "／勃起=" + erect
        gate(text)
        return text
    }

    companion object {
        /** 804/831 AreaLevel 六档 label（字符串引用，不自造；与 PrepuceCoverage.AreaLevel 逐字对齐）。 */
        val PREPUCE_LEVELS: List<String> = listOf(
            "完全覆盖", "几乎全部", "过半", "仅冠", "退至冠后", "完全露出",
        )
    }
}

/** ⑤＋⑤占位 廓形读数。档名＋pending:814（截面）＋pending:775（子区）。 */
data class CoronaState(val profile: ErectProfile) : SheetRow {
    override val key: String get() = SheetRow.CORONA
    override fun line(): String {
        val text = "廓形=" + profile.label + "｜截面 " + PENDING_SECTION + "｜子区 " + PENDING_SUBZONE
        gate(text)
        ErectGuard.rejectInventedPlaceholder(text)
        return text
    }
}

/** 所有读数行共用的一道闸：805 三道（长度/百分比、色号、诊断）＋本包倍数与合并档。 */
private fun gate(text: String) {
    BodyPartGuard.rejectMeasures(text)
    BodyPartGuard.rejectDiagnosis(text)
    BodyPartGuard.rejectAbsoluteColor(text)
    ErectGuard.rejectFactor(text)
    ErectGuard.rejectMerged(text)
}
