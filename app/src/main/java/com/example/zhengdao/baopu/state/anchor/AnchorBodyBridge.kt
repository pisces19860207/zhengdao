// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
//
// 第七百四十七批 · 人物锚定：与 660（形轨·形体参数）的读数桥（派工单判据⑦）。
// 660 已在 bbdd8e189 之前合入 main（合并提交 3d39bf3ef），所以这座桥是**真接**，不是留口子：
// 凡 660 已经有细字段的地方一律读它，本模块不复制一份数值、更不自造字段名。
//
// 三档口径（判据⑦ 的落点）：
// · CONNECTED：660 给数值、七维该行也收数值 ⇒ 直接给读数；
// · PARTIAL：660 给的是连续量（体脂率、肌肉分级），七维该行要的是**档**（低／中／高），
//   而规格 §二② 没给数值→档的阈值 ⇒ 只报 660 的原始读数，**不猜阈值**，标 ○ 待核；
// · NOT_IN_660：660 根本没有这个口径（骨架／腿型／手部），七维该行由本表自持 ⇒ 标「未接入」。
//
// 名册（ROSTER）里每条都写着 660 的真实字段路径；AnchorSourceAssertTest 拿它回查
// HumanBodySpec.kt 源码，名册里出现 660 源码没有的字段名 ⇒ 判据⑦ 直接红（这就是「不许自造字段」的牙）。
package com.example.zhengdao.baopu.state.anchor

import com.example.zhengdao.baopu.state.form.HumanBodySpec
import com.example.zhengdao.baopu.state.form.Origin
import com.example.zhengdao.baopu.state.form.Override

/** 某一行接 660 接到什么程度（判据⑦ 的三档＋一档「660 有口径但这次没给值」）。 */
enum class BridgeStatus(val cn: String) {
    CONNECTED("已接入"),
    PARTIAL("数值已接·档待核"),
    UNSET("660 未给值"),
    NOT_IN_660("未接入"),
}

/** 名册里的一条对应关系：七维一行 ↔ 660 的一个真实字段（或「660 无此口径」）。 */
data class BridgeField(
    val fieldId: String,
    val sixSixtyPath: String,
    val unit: String = "",
    val graded: Boolean = false,
    val note: String = "",
)

/** 一次实际读数结果（值缺时只报状态，绝不补默认值）。 */
data class BridgeReading(
    val fieldId: String,
    val status: BridgeStatus,
    val sixSixtyPath: String,
    val value: Double?,
    val unit: String,
    val origin: String,
    val source: String,
    val asOf: String,
    val note: String,
) {
    /** 只有 CONNECTED 才允许直接进七维数值槽；PARTIAL 必须先过人工阈值（○ 待核）。 */
    val usableAsNumber: Boolean get() = status == BridgeStatus.CONNECTED && value != null
}

/**
 * 660 细字段 → 七维的只读桥。
 * 660 侧一字不改（租约里写明「一律不改其文件」），这里只有 getter。
 */
object AnchorBodyBridge {

    /** 判据⑦ 点名：660 已合 ⇒ 这座桥是实接；未合时 read(null) 全表标「未接入」。 */
    const val SIX_SIXTY_MERGED = "660 已合入 main（3d39bf3ef）：真接，不留自造字段"

    /** 七维里需要外部数值口径的行（BODY 全表＋§二④ 给了硬上限的数值落点）。 */
    val ROSTER: List<BridgeField> = listOf(
        BridgeField("body.height", "HumanBodySpec.heightCm", "cm"),
        BridgeField("body.weight", "HumanBodySpec.weightKg", "kg"),
        BridgeField("body.fat", "HumanBodySpec.bodyFatPct", "%", graded = true,
            note = "七维档＝低／中／高；阈值规格未给 ⇒ ○ 待核"),
        BridgeField("body.muscle", "HumanBodySpec.muscleGrade", "级", graded = true,
            note = "七维档＝无／薄／中／厚；阈值规格未给 ⇒ ○ 待核"),
        BridgeField("body.ratio", "HumanBodySpec.shoulderRatio", "比例", graded = true,
            note = "肩腰臀三项比例；660 只给肩比，腰／臀无对应 ⇒ ○ 待核"),
        BridgeField("body.frame", "660 无骨架口径", note = "窄／中／宽由七维自持"),
        BridgeField("body.legs", "660 无腿型口径", note = "长／短／直／O／X 由七维自持"),
        BridgeField("body.chest", "660 无乳房档", note = "平／小／中／大由七维自持；660 只有 chest_upper 截面几何"),
        BridgeField("body.hands", "660 无手部口径", note = "手／指／骨节／指甲由七维自持"),
        BridgeField("g.overall", "GenitalInput.lengthMm＋girthMm", "mm",
            note = "规格硬上限：勃起 ≤16cm、直径 ≤5.5cm ⇒ 上限核对见 AnchorBodyBridge.capCheck"),
        BridgeField("g.scrotum", "GenitalInput.scrotumVolumeGrade＋scrotumSagGrade", "级",
            note = "档走 g.scrotumLaxity／g.scrotumHang；660 只给级号，档名待人工对"),
        BridgeField("g.testis", "GenitalInput.hangSide＋testisAsymmetryMm", "mm", graded = true,
            note = "位置档走 g.testisPosition；660 给的是毫米实测，不做档换算"),
        BridgeField("g.shaft", "GenitalInput.curvature＋bendDirection", "",
            note = "青筋走 vesselVisibility；弯曲方向为自由文本"),
    )

    /**
     * 读一次 660。spec 为 null 表示这一张卡没带 660 身体输入：
     * 660 有口径的行标「660 未给值」，660 无口径的行标「未接入」——两种都不编值。
     */
    fun read(spec: HumanBodySpec?): List<BridgeReading> = ROSTER.map { field -> route(field, spec) }

    /** 判据⑦ 的另一半：660 未合时走这里——整表标「未接入」，接口留着，一个字段都不自造。 */
    fun unavailable(): List<BridgeReading> = ROSTER.map { notIn660(it) }

    private fun route(f: BridgeField, spec: HumanBodySpec?): BridgeReading = when (f.fieldId) {
        "body.height" -> number(f, spec?.heightCm, MEASURED)
        "body.weight" -> number(f, spec?.weightKg, MEASURED)
        "body.fat" -> number(f, spec?.bodyFatPct, MEASURED)
        "body.muscle" -> number(f, spec?.muscleGrade?.toDouble(), DERIVED)
        "body.ratio" -> overrideReading(f, spec?.shoulderRatio)
        "g.overall" -> overrideReading(f, spec?.genital?.lengthMm)
        "g.scrotum" -> number(f, spec?.genital?.scrotumVolumeGrade?.toDouble(), DERIVED)
        "g.testis" -> number(f, spec?.genital?.testisAsymmetryMm, DERIVED)
        "g.shaft" -> number(f, spec?.genital?.curvature, DERIVED)
        else -> notIn660(f)
    }

    /**
     * 规格 §二④ 整体行的硬上限（勃起尺寸 ≤16cm、直径 ≤5.5cm）：只核对、不改写 660 的输入。
     * 660 给的是周长（girthMm），等效直径按 ÷π 换算——这个换算式是 660 自己的口径（GenitalInput 注释），
     * 本桥不另定一套。
     */
    fun capCheck(spec: HumanBodySpec): List<String> {
        val g = spec.genital
        val over = mutableListOf<String>()
        g.lengthMm?.let { if (it.value > MAX_LENGTH_MM) over += "勃起尺寸 ${it.value}mm 超规格上限 ${MAX_LENGTH_MM}mm" }
        g.girthMm?.let {
            val diameter = it.value / PI
            if (diameter > MAX_DIAMETER_MM) over += "等效直径 ${diameter.toInt()}mm（周长 ${it.value}mm÷π）超规格上限 ${MAX_DIAMETER_MM}mm"
        }
        return over
    }

    private const val MAX_LENGTH_MM = 160.0
    private const val MAX_DIAMETER_MM = 55.0
    private const val PI = 3.141592653589793
    private val MEASURED = Origin.MEASURED.label
    private val DERIVED = Origin.DERIVED.label

    /** 「660 无此口径」行：状态即结论，值一律 null。 */
    private fun notIn660(f: BridgeField) = BridgeReading(
        fieldId = f.fieldId, status = BridgeStatus.NOT_IN_660, sixSixtyPath = f.sixSixtyPath,
        value = null, unit = f.unit, origin = "", source = "", asOf = "", note = f.note,
    )

    /** 660 有口径但这一张卡没给值。 */
    private fun unset(f: BridgeField, origin: String) = BridgeReading(
        fieldId = f.fieldId, status = BridgeStatus.UNSET, sixSixtyPath = f.sixSixtyPath,
        value = null, unit = f.unit, origin = origin, source = "", asOf = "", note = f.note,
    )

    /** 连续读数（裸 Double）：660 侧不带逐值来源，出处统一挂输入卡（见 SOURCE_INPUT_CARD）。 */
    private fun number(f: BridgeField, value: Double?, origin: String) = if (value == null) unset(f, origin) else BridgeReading(
        fieldId = f.fieldId,
        status = if (f.graded) BridgeStatus.PARTIAL else BridgeStatus.CONNECTED,
        sixSixtyPath = f.sixSixtyPath, value = value, unit = f.unit,
        origin = origin, source = SOURCE_INPUT_CARD, asOf = "", note = f.note,
    )

    /** Override 读数：660 的覆盖值自带 source＋asOf，照抄，不洗白成自己的口径。 */
    private fun overrideReading(f: BridgeField, o: Override?) = when {
        o == null -> unset(f, MEASURED)
        else -> BridgeReading(
            fieldId = f.fieldId,
            status = if (f.graded) BridgeStatus.PARTIAL else BridgeStatus.CONNECTED,
            sixSixtyPath = f.sixSixtyPath, value = o.value, unit = f.unit,
            origin = MEASURED, source = o.source, asOf = o.asOf, note = f.note,
        )
    }

    /**
     * 660 的连续量（身高／体重／体脂率）在 HumanBodySpec 里是裸 Double，出处挂在整张输入卡上；
     * 本桥不替它编来源，只声明「来源＝660 形体输入卡」，要逐值溯源由 660 侧的 Override 负责。
     */
    private const val SOURCE_INPUT_CARD = "660 HumanBodySpec 输入卡（逐值出处见 660 侧 Override）"
}
