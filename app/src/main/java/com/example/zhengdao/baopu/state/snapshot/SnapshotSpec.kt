// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百三十四批 · 统一状态快照（X1：SceneState 本体）：字段规格表。
// 本文件只登记「一个字段从哪来」，不定义任何状态档、不判定、不装配。
// 分层作曲器：整体快照 → 六段 → 每段字段 → 字段溯源；本件是最后一层（唯一真相）。
package com.example.zhengdao.baopu.state.snapshot

/**
 * 一条快照字段的溯源登记。
 *
 * [sourceOrder] 是派工单号（既有单），[sourceSymbol] 是既有包里的符号，
 * [sourceFile] 是 `包/文件.kt:行号`。三者任一为空 ⇒ 字段不许进快照。
 * [missing] 是「这一格没给」时必须原样输出的 absent 理由，绝不用默认值顶替。
 */
internal data class FieldSpec(
    val id: String,
    val section: String,
    val sourceOrder: String,
    val sourceSymbol: String,
    val sourceFile: String,
    val missing: String,
)

/** 六段的固定次序。段名也是 JSON 的一级键，不得运行时改动。 */
internal object SnapshotSpec {

    const val VERSION = "834-x1"
    const val SOURCE = "派工单-第八百三十四批-统一快照"

    const val SEC_BODY = "body"
    const val SEC_TIME = "time"
    const val SEC_SPACE = "space"
    const val SEC_ENVIRONMENT = "environment"
    const val SEC_LIGHT = "light"
    const val SEC_FLUID = "fluid"

    val SECTIONS: List<String> = listOf(
        SEC_BODY,
        SEC_TIME,
        SEC_SPACE,
        SEC_ENVIRONMENT,
        SEC_LIGHT,
        SEC_FLUID,
    )

    /** 全部字段，按段分组、组内固定次序。装配与渲染都只走这张表。 */
    val FIELDS: List<FieldSpec> = listOf(
        // ── 人体逐区态 ──────────────────────────────────────────────
        FieldSpec("body.spec.ok", SEC_BODY, "660", "BodyState.ok",
            "form/BodyState.kt:107", "660 形体未给"),
        FieldSpec("body.spec.ratios", SEC_BODY, "660", "BodyState.ratiosInPopulationRange",
            "form/BodyState.kt:111", "660 形体未给"),
        FieldSpec("body.spec.variants", SEC_BODY, "723/731", "BodyState.variants",
            "form/BodyState.kt:131", "723 变体未采样"),
        FieldSpec("body.zones.now", SEC_BODY, "731", "zoneNow（本单不建）",
            "派工单-第八百三十四批 §由来", "731 逐区态生产类型未落地；本单不造新档"),
        FieldSpec("body.parts.count", SEC_BODY, "654", "BodyParts.size",
            "bodyparts/BodyPartSheet.kt:78", "部位单未给"),
        FieldSpec("body.injury.grade", SEC_BODY, "728", "InjuryGrade.label",
            "injury/InjuryGrades.kt:18", "伤情观察档未给"),
        FieldSpec("body.pain.grade", SEC_BODY, "728", "PainGrade.label",
            "injury/InjuryGrades.kt:41", "疼痛观察档未给"),
        FieldSpec("body.stimulus.rhythm", SEC_BODY, "734/736", "RhythmReading(f/d/w)",
            "stimulus/Rhythm.kt:11", "节律读数未给"),
        FieldSpec("body.anchor.id", SEC_BODY, "747", "AnchorProfile.id",
            "anchor/AnchorProfile.kt:24", "人物锚定档案未给"),

        // ── 时间 ───────────────────────────────────────────────────
        FieldSpec("time.tick", SEC_TIME, "834", "SnapshotInput.tick（场景时钟，非墙钟）",
            "snapshot/SnapshotInput.kt:14", "场景时钟未给"),
        FieldSpec("time.dayOfYear", SEC_TIME, "657", "SolarSolver.declinationDeg(dayOfYear)",
            "environment/SolarSolver.kt:62", "年日序未给"),
        FieldSpec("time.solarTime", SEC_TIME, "657", "SolarSolver.TrueSolarTime.hours",
            "environment/SolarSolver.kt:42", "真太阳时未给"),
        FieldSpec("time.solarTerm", SEC_TIME, "821", "节气档（本仓未落地）",
            "派工单-第八百三十四批 §一", "821 节气档无既有生产包；本单不造新档"),

        // ── 空间 ───────────────────────────────────────────────────
        FieldSpec("space.position.zone", SEC_SPACE, "790", "BearingVerdict.zoneId",
            "bearing/BearingTypes.kt:91", "790 方位结论未给"),
        FieldSpec("space.pose.valid", SEC_SPACE, "655", "PoseVerdict.valid",
            "geometry/PoseValidity.kt:50", "655 姿态读数未给"),
        FieldSpec("space.pose.violations", SEC_SPACE, "655", "PoseVerdict.violations",
            "geometry/PoseValidity.kt:51", "655 姿态读数未给"),
        FieldSpec("space.reach.state", SEC_SPACE, "655", "ReachResult.state",
            "geometry/Reachability.kt:19", "655 可达读数未给"),
        FieldSpec("space.bearing", SEC_SPACE, "790", "BearingVerdict.bearing",
            "bearing/BearingTypes.kt:94", "790 方位结论未给"),
        FieldSpec("space.distance", SEC_SPACE, "790", "BearingVerdict.distance",
            "bearing/BearingTypes.kt:95", "790 方位结论未给"),
        FieldSpec("space.containment.state", SEC_SPACE, "741", "ContainmentVerdict.state.label",
            "containment/ContainmentVerdict.kt:12", "741 容纳结论未给"),
        FieldSpec("space.containment.stance", SEC_SPACE, "741", "ContainmentVerdict.stance.label",
            "containment/ContainmentVerdict.kt:15", "741 容纳结论未给"),
        FieldSpec("space.cavity.state", SEC_SPACE, "686", "CavityGap.state",
            "cavity/CavityMap.kt:19", "686 腔隙表未给"),
        FieldSpec("space.capacity.verdict", SEC_SPACE, "795", "CapacityResult.verdict.label",
            "capacity/CapacityModel.kt:69", "795 容量结论未给"),
        FieldSpec("space.motion.now", SEC_SPACE, "motion", "MotionAction（静态关键姿，无当前态类型）",
            "motion/MotionGrades.kt:6", "motion 包只有 655 静态关键姿，无当前动作态生产类型；731 未落地；本单不造新档"),

        // ── 环境档 ─────────────────────────────────────────────────
        FieldSpec("environment.sun.azimuth", SEC_ENVIRONMENT, "657", "SunState.azimuthDeg",
            "environment/SolarSolver.kt:23", "657 环境状态未给"),
        FieldSpec("environment.sun.elevation", SEC_ENVIRONMENT, "657", "SunState.elevationDeg",
            "environment/SolarSolver.kt:24", "657 环境状态未给"),
        FieldSpec("environment.sun.shadowRatio", SEC_ENVIRONMENT, "657", "SunState.shadowRatio",
            "environment/SolarSolver.kt:25", "657 环境状态未给"),
        FieldSpec("environment.sun.intensity", SEC_ENVIRONMENT, "657", "SunState.intensity",
            "environment/SolarSolver.kt:27", "657 环境状态未给"),
        FieldSpec("environment.wind.feels", SEC_ENVIRONMENT, "657", "WindState.feels",
            "environment/WindTable.kt:25", "657 环境状态未给"),
        FieldSpec("environment.humidity.evaporation", SEC_ENVIRONMENT, "657", "HumidityState.evaporation",
            "environment/HumiditySolver.kt:23", "657 环境状态未给"),
        FieldSpec("environment.sweat.grade", SEC_ENVIRONMENT, "657", "SweatState.grade",
            "environment/HumiditySolver.kt:30", "657 环境状态未给"),
        FieldSpec("environment.sand.label", SEC_ENVIRONMENT, "657", "SandMoisture.label",
            "environment/HumiditySolver.kt:37", "657 环境状态未给"),

        // ── 光影六元组（824 降为本段） ──────────────────────────────
        FieldSpec("light.sourceType", SEC_LIGHT, "824", "光源类型（六元组之一）",
            "派工单-第八百二十四批 §一", "824 光影子段无生产包；光源类型档未落地"),
        FieldSpec("light.intensity", SEC_LIGHT, "657", "SunState.intensity（六元组之强度）",
            "environment/SolarSolver.kt:27", "657 日照档未给"),
        FieldSpec("light.colorTemp", SEC_LIGHT, "824", "色温（六元组之一）",
            "派工单-第八百二十四批 §一", "824 光影子段无生产包；色温档未落地"),
        FieldSpec("light.direction", SEC_LIGHT, "657", "SunState.azimuthDeg（六元组之方向）",
            "environment/SolarSolver.kt:23", "657 太阳方位未给"),
        FieldSpec("light.period", SEC_LIGHT, "657", "SolarSolver.TrueSolarTime.hours（六元组之时段）",
            "environment/SolarSolver.kt:42", "657 真太阳时未给"),
        FieldSpec("light.weather", SEC_LIGHT, "824", "天气（六元组之一）",
            "派工单-第八百二十四批 §一", "824 光影子段无生产包；657 只有风/湿度，无独立天气档"),
        FieldSpec("light.shadow.hardness", SEC_LIGHT, "824", "阴影硬/软与锐影一致（子段验收）",
            "派工单-第八百二十四批 §二", "824 阴影判据未落地生产包；硬/软光无对应档"),
        FieldSpec("light.material.response", SEC_LIGHT, "824", "皮肤/湿润/毛发材质响应（子段验收）",
            "派工单-第八百二十四批 §三", "824 材质响应判据未落地生产包；油/汗/毛依据无对应档"),

        // ── 液体态 ─────────────────────────────────────────────────
        FieldSpec("fluid.volume", SEC_FLUID, "780", "FluidBody.volume.grade.label",
            "fluid/FluidModel.kt:56", "780 液体态未给"),
        FieldSpec("fluid.viscosity", SEC_FLUID, "780", "FluidBody.viscosity.grade.label",
            "fluid/FluidModel.kt:57", "780 液体态未给"),
        FieldSpec("fluid.temperature", SEC_FLUID, "780", "FluidBody.temperature.grade.label",
            "fluid/FluidModel.kt:58", "780 液体态未给"),
        FieldSpec("fluid.dryness", SEC_FLUID, "780", "FluidBody.dryness.grade.label",
            "fluid/FluidModel.kt:59", "780 液体态未给"),
        FieldSpec("fluid.channels.sweat", SEC_FLUID, "658", "FluidChannels.sweat",
            "physiology/LightPhysiology.kt:25", "658 三液分道未给"),
        FieldSpec("fluid.channels.saliva", SEC_FLUID, "658", "FluidChannels.saliva",
            "physiology/LightPhysiology.kt:26", "658 三液分道未给"),
        FieldSpec("fluid.channels.preSeminal", SEC_FLUID, "658", "FluidChannels.preSeminal",
            "physiology/LightPhysiology.kt:27", "658 三液分道未给"),
        FieldSpec("fluid.surfaceFlow", SEC_FLUID, "806", "体表水流路径（未装配）",
            "派工单-第八百三十四批 §一", "806 体表水流未装配到场景输入"),
    )

    private val BY_ID: Map<String, FieldSpec> = FIELDS.associateBy { it.id }

    fun of(id: String): FieldSpec = BY_ID.getValue(id)

    fun fieldsOf(section: String): List<FieldSpec> = FIELDS.filter { it.section == section }
}
