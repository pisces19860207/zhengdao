// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百六十六批 · 形轨.轮廓可见度（比值模型）：r = H / C_eff，四档三态＋一句话。
// 参考实现：证道设计报告/附件-轮廓模型/silhouette_model.py（照翻，未重新设计）；
// 物理依据与实测 C 表见《男性素材-事实底盘》十二节；阈值是作者裁定，
// 正文与更动规则见 docs/facts/silhouette-thresholds.md。
//
// 边界（666 派工单）：只做三态＋一句话。不做渲染、不做褶皱、不做「准确轮廓」。
package com.example.zhengdao.baopu.body

import java.util.Locale

/** 四档判读词。rank 升序＝更明显；「更明显」的方向只由 H 与 C 的比值推动。 */
enum class SilhouetteTier(val label: String) {
    HIDDEN("隐藏"),
    FAINT("隐约"),
    VISIBLE("可见"),
    PROMINENT("明显"),
    ;

    /** 档位序数：单调性断言用（越大越明显）。 */
    val rank: Int get() = ordinal
}

/** 三态：分档出读数 / 面料无实测拒绝 / 阈值缺失拒绝（fail-closed）。 */
enum class SilhouetteState { GRADED, REFUSED_NO_FABRIC, REFUSED_NO_THRESHOLD }

/** 位置标签：只进一句话（给读数定位），不进比值。朝向/侧偏属状态内核，不在本单。 */
enum class SilhouettePosition(val label: String) {
    WAIST("腰线"),
    CROTCH("裆缝"),
    THIGH("大腿根"),
}

/**
 * 姿态标签：坐/蹲布料张紧，有效弯曲长度 ×0.85（作者裁定，对齐设计报告 12.6
 * 「坐时角度变、布料张力升」；蹲与坐同档处理）。判据④要求姿态影响确定性：
 * 同 H/C 下坐姿比站姿更易显，方向由标签唯一决定，不掺随机。
 */
enum class SilhouettePosture(val label: String, val tensionFactor: Double) {
    STAND("站", 1.0),
    SIT("坐", 0.85),
    SQUAT("蹲", 0.85),
}

/**
 * 面料弯曲长度 C（cm）实测表条目。构造私有：C 只许来自实测表（ASTM D1388 /
 * BS 3356 / ISO 4674-2），调用方没有自填 C 的路径（判据⑥是结构性拒绝，不靠自觉）。
 */
class FabricSpec private constructor(val name: String, val bendingLengthCm: Double, val source: String) {
    companion object {
        private fun of(name: String, cm: Double, source: String) = FabricSpec(name, cm, source)

        /** 实测表（对齐设计报告 12.3 与参考实现 FABRIC；针织内裤是工程取值，如实标注）。 */
        val ALL: List<FabricSpec> = listOf(
            of("雪纺/真丝薄料", 1.2, "实测 ASTM D1388（区间 1.0–1.5，取 1.2）"),
            of("针织内裤(棉)", 2.0, "工程取值 ○（针织普遍偏软，未查到成衣实测）"),
            of("棉衬衫布", 2.5, "实测 ASTM D1388（区间 2.1–3.5，取 2.5）"),
            of("7.5oz卡其", 2.9, "实测 ASTM D1388"),
            of("10oz斜纹", 4.4, "实测 ASTM D1388"),
            of("12oz牛仔", 5.2, "实测 ASTM D1388（牛仔整体 5–8，取 5.2）"),
            of("厚帆布", 11.0, "实测 BS 3356（区间 8–15，取 11.0）"),
        )

        private val BY_NAME: Map<String, FabricSpec> = ALL.associateBy { it.name }

        /** 表里没有的面料返回 null：调用方必须拒绝，不许自填 C（负例：自填 C=5 ⇒ 红）。 */
        fun byName(name: String): FabricSpec? = BY_NAME[name]
    }
}

/**
 * 阈值表：三条上界须有限、为正且严格递增。任何一条缺失或非法 ⇒ isComplete()=false
 * ⇒ 核心拒绝判读（fail-closed，判据⑦：拒绝，而不是默认可见）。
 */
data class SilhouetteThresholds(
    val hiddenBelow: Double,
    val faintBelow: Double,
    val visibleBelow: Double,
) {
    fun isComplete(): Boolean =
        listOf(hiddenBelow, faintBelow, visibleBelow).all { it.isFinite() } &&
            hiddenBelow > 0.0 &&
            hiddenBelow < faintBelow &&
            faintBelow < visibleBelow

    companion object {
        /** 作者裁定（2026-11-16），正文见 docs/facts/silhouette-thresholds.md。 */
        val DEFAULT = SilhouetteThresholds(0.5, 1.2, 2.0)
    }
}

/** 判读结果（三态）。Graded 带四档词＋比值＋一句话；Refused 带拒绝态＋一句话。 */
sealed interface SilhouetteVerdict {
    val state: SilhouetteState
    val sentence: String

    data class Graded(
        val tier: SilhouetteTier,
        val ratio: Double,
        override val sentence: String,
    ) : SilhouetteVerdict {
        override val state: SilhouetteState = SilhouetteState.GRADED
    }

    data class Refused(
        override val state: SilhouetteState,
        override val sentence: String,
    ) : SilhouetteVerdict
}

/** 核心模型：纯函数，零模型、零联网、零随机（判据⑧：同输入恒同输出）。 */
object Silhouette {

    /**
     * r = H / C_eff。H 是形轨高度场输出的凸起高度（cm，非负有限，违规即抛——
     * 输入契约由调用方保证，本函数不猜）；C_eff = 实测 C × 姿态张紧系数。
     * 四档边界左闭右开：[0.5,1.2) 隐约、[1.2,2.0) 可见、≥2.0 明显。
     */
    fun grade(
        bumpHeightCm: Double,
        fabricName: String,
        position: SilhouettePosition,
        posture: SilhouettePosture,
        thresholds: SilhouetteThresholds = SilhouetteThresholds.DEFAULT,
    ): SilhouetteVerdict {
        require(bumpHeightCm.isFinite() && bumpHeightCm >= 0.0) { "H 须为非负有限值：$bumpHeightCm" }
        if (!thresholds.isComplete()) {
            return refused(SilhouetteState.REFUSED_NO_THRESHOLD, "阈值表缺失，拒绝判读（fail-closed）")
        }
        val fabric = FabricSpec.byName(fabricName)
            ?: return refusedNoFabric(fabricName)
        val ratio = bumpHeightCm / (fabric.bendingLengthCm * posture.tensionFactor)
        val tier = tierOf(ratio, thresholds)
        val sentence = "${fabric.name}，凸起${mm(bumpHeightCm)}mm ⇒ ${tier.label}" +
            "（${position.label}·${posture.label}）"
        return SilhouetteVerdict.Graded(tier, ratio, sentence)
    }

    /** 分档只由 r 与阈值表决定；阈值不在本方法里兜底——缺表在上游已拒绝。 */
    private fun tierOf(r: Double, t: SilhouetteThresholds): SilhouetteTier = when {
        r < t.hiddenBelow -> SilhouetteTier.HIDDEN
        r < t.faintBelow -> SilhouetteTier.FAINT
        r < t.visibleBelow -> SilhouetteTier.VISIBLE
        else -> SilhouetteTier.PROMINENT
    }

    private fun refused(state: SilhouetteState, why: String): SilhouetteVerdict =
        SilhouetteVerdict.Refused(state, why)

    /**
     * 拒绝语也守 ≤40 字（判据⑤不分档态）：面料名是外部输入，截到 16 字加省略号，
     * 保证任何名字下拒绝语都不超长。
     */
    private fun refusedNoFabric(name: String): SilhouetteVerdict {
        val shown = if (name.length > 16) name.take(16) + "…" else name
        return refused(SilhouetteState.REFUSED_NO_FABRIC, "面料「$shown」无实测 C，拒绝判读")
    }

    /** cm 转 mm，Locale.ROOT 固定小数点（判据⑧：输出逐字节一致，不吃本地化）。 */
    private fun mm(cm: Double): String = String.format(Locale.ROOT, "%.0f", cm * 10.0)
}
