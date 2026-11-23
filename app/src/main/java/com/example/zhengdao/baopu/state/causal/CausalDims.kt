// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百三十五批 · 因果链骨架：九个「可被后果写到的维度」。
//
// 本批只做一件事：把既有各包的**档位**连成一条可回放的链，不做连续物理。
// 所以这个文件里没有任何新档位——每一维的取值区间与文案都由**业主包**给：
//
//   部位·伤情 → 728 `InjuryGrade`（0–3）      感觉·疼痛 → 728 `PainGrade`（0–3）
//   姿态·关节 → 725 `JointAngleGrade`（1–5）    姿态·重心 → 725 `ComHeightGrade`（1–5）
//   皮肤·湿   → 806 `FlowVolume`（0–3）         皮肤·温   → 658 `BodyTempShift`（0–3）
//   感觉·疲劳 → 658 `Fatigue`（0–3）            感觉·体感 → 657 `felt_temp`（0–3，四档文案）
//   光影·日照 → 657 `intensityOf`（0–3，四档文案）
//
// 区间一律**算**出来（`COUNT - 1`），不另抄一份数字：业主包加一档，这里自动跟着变，
// 改不动就意味着「本包偷偷定了一份区间」。判据⑥（不新造档）就是靠这一条钉住的。
//
// 两个「没有枚举」的维度（体感/日照）在 657 里只有 String 标签，没有类型，
// 所以这里只抄**档名**（不抄公式、不抄阈值），并由测试拿 657 的真跑输出反查：
// 热输入必须得到第 3 档「烫」、正午必须得到第 3 档「强」——抄错一个字就红。
package com.example.zhengdao.baopu.state.causal

import com.example.zhengdao.baopu.state.environment.WindTable
import com.example.zhengdao.baopu.state.injury.InjuryGrade
import com.example.zhengdao.baopu.state.injury.PainGrade
import com.example.zhengdao.baopu.state.motion.ComHeightGrade
import com.example.zhengdao.baopu.state.motion.JointAngleGrade
import com.example.zhengdao.baopu.state.physiology.BodyTempShift
import com.example.zhengdao.baopu.state.physiology.Fatigue
import com.example.zhengdao.baopu.state.surfaceflow.FlowVolume

/** 657 体感温度四档的档名（`HumiditySolver.feltTemp` 逐字抄；只抄文案，不抄分数式）。 */
internal val FEEL_LABELS: List<String> = listOf("偏凉", "适中", "闷热", "烫")

/** 657 日照档四档的档名（`SolarSolver.intensityOf` 逐字抄；只抄文案，不抄高度角阈值）。 */
internal val LIGHT_LABELS: List<String> = listOf("无", "弱", "中", "强")

/** 657 体感档的 kind 键（`WindTable.Effect.kind`）；本包按它从效应清单里取档，不猜顺序。 */
internal const val FELT_TEMP_KIND = "felt_temp"

/**
 * 九维：一维＝一个「能被后果写到的档位槽」。
 *
 * [owner] 是业主单号，[ownerSymbol] 是业主包里的符号名——渲染与溯源都印它，
 * 这样任何一行输出都能指回「这个档是谁定的」。
 */
internal enum class CausalDim(
    val id: String,
    val label: String,
    val owner: String,
    val min: Int,
    val max: Int,
    val ownerSymbol: String,
) {
    SITE_INJURY("site.injury", "部位·伤情", "728", 0, InjuryGrade.COUNT - 1, "InjuryGrade"),
    SENSE_PAIN("sense.pain", "感觉·疼痛", "728", 0, PainGrade.COUNT - 1, "PainGrade"),
    POSE_JOINT("pose.joint", "姿态·关节角", "725", JointAngleGrade.L1.level, JointAngleGrade.L5.level, "JointAngleGrade"),
    POSE_COM("pose.com", "姿态·重心", "725", ComHeightGrade.L1.level, ComHeightGrade.L5.level, "ComHeightGrade"),
    SKIN_WET("skin.wet", "皮肤·湿", "806", 0, FlowVolume.COUNT - 1, "FlowVolume"),
    SKIN_TEMP("skin.temp", "皮肤·温", "658", 0, BodyTempShift.COUNT - 1, "BodyTempShift"),
    SENSE_FEEL("sense.feel", "感觉·体感", "657", 0, FEEL_LABELS.size - 1, "felt_temp"),
    SENSE_FATIGUE("sense.fatigue", "感觉·疲劳", "658", 0, Fatigue.COUNT - 1, "Fatigue"),
    LIGHT("light.sun", "光影·日照", "657", 0, LIGHT_LABELS.size - 1, "intensityOf"),
    ;

    /** 档数：区间宽度＋1；与业主包 COUNT 同值，用作「档数没被改过」的断言锚。 */
    val count: Int get() = max - min + 1

    /** fail-closed 钳位：越界写到端点，但调用方（检查器）会单独判一条红，不静默吞掉。 */
    fun clamp(level: Int): Int = level.coerceIn(min, max)

    fun inRange(level: Int): Boolean = level in min..max

    /** 档名一律向业主包要；两处没有枚举的向 657 的档名表要。 */
    fun labelOf(level: Int): String {
        val v = clamp(level)
        return when (this) {
            SITE_INJURY -> InjuryGrade.of(v).label
            SENSE_PAIN -> PainGrade.of(v).label
            POSE_JOINT -> JointAngleGrade.of(v).name
            POSE_COM -> ComHeightGrade.of(v).name
            SKIN_WET -> FlowVolume.of(v).label
            SKIN_TEMP -> BodyTempShift.of(v).label
            SENSE_FEEL -> FEEL_LABELS[v]
            SENSE_FATIGUE -> Fatigue.of(v).label
            LIGHT -> LIGHT_LABELS[v]
        }
    }

    /** 渲染用短署名：`肿起（728）`。 */
    fun short(level: Int): String = "${labelOf(level)}（$owner）"

    companion object {
        const val COUNT = 9

        fun of(id: String): CausalDim = values().first { it.id == id }
    }
}

/**
 * 657 效应清单里取「体感温度」那一档：按 kind 键取，取不到就返回 null（不猜位置、不给默认档）。
 * 这是本包与 657 之间唯一的取值口，别处不许再写 `effects[3]` 这种按序取值。
 */
internal fun feltTempGrade(effects: List<WindTable.Effect>): Int? =
    effects.firstOrNull { it.kind == FELT_TEMP_KIND }?.grade

/** 657 体感档名（与 [CausalDim.SENSE_FEEL] 同源）。 */
internal fun feltTempLabel(grade: Int): String = FEEL_LABELS[grade.coerceIn(0, FEEL_LABELS.size - 1)]
