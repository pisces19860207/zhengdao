// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-11-22-第七百八十批（三液的变化与混合）。
//
// 本包只做「液体的样子」：四维档位怎么变、两液混在一起算什么、挂不挂得住、布吸不吸、
// 量够不够铺满、离开身体走哪种轨迹。全是档位与枚举：没有点值、不判诊断、不给行为指导、
// 不写事实、不进任何闸。
// 与邻单分工：658 管三液分道（有没有、多少），735 管前列腺排出两型的落账口，
// 731 管摆动（pendulum）——本单既不重记那两笔，也不建摆动模型。
package com.example.zhengdao.baopu.state.fluid

/** 统一依据口径：本单派工单＋工程假设，不引用任何诊疗资料原文。 */
internal const val FLUID_SOURCE =
    "派工单-2026-11-22-第七百八十批（三液的变化与混合）＋本项目工程假设"
internal const val FLUID_AS_OF = "2026-11-22"

/** 本单只产状态、不下结论的统一附言；渲染唯一出口是 [FluidRenderer]。 */
internal const val FLUID_DISCLAIMER = "非诊断·仅档位"

/**
 * 档位共契约：档序只表先后强弱，不是任何物理量测值；[zero] 标记「无/常温/未变」这类零档。
 * 全包没有任何计量字段——没有体积、长度、时间、酸碱度、成分。
 */
internal interface FluidGrade {
    val level: Int
    val label: String
    val zero: Boolean
}

/** ① 量档 0–3：无/微/中/多。口径与 658 三液分道的量档同标度（0–3 四档），本包只读不写它。 */
internal enum class VolumeGrade(
    override val level: Int,
    override val label: String,
    override val zero: Boolean,
) : FluidGrade {
    NONE(0, "无", true),
    TRACE(1, "微", false),
    MODERATE(2, "中", false),
    COPIOUS(3, "多", false),
    ;

    companion object {
        const val COUNT = 4

        fun of(level: Int): VolumeGrade = values().first { it.level == level.coerceIn(0, COUNT - 1) }
    }
}

/** ① 黏稠档 0–3：稀薄/偏稀/稠/黏稠。只讲「好不好拉出丝」，不讲任何黏度数值。 */
internal enum class ViscosityGrade(
    override val level: Int,
    override val label: String,
    override val zero: Boolean,
) : FluidGrade {
    RUNNY(0, "稀薄", true),
    THIN(1, "偏稀", false),
    CREAMY(2, "稠", false),
    GLUTINOUS(3, "黏稠", false),
    ;

    companion object {
        const val COUNT = 4
        const val TOP_LEVEL = 3

        fun of(level: Int): ViscosityGrade =
            values().first { it.level == level.coerceIn(0, COUNT - 1) }

        /** 升一档；已在顶档就停在顶档（判据②的期望值也用这一条算，避免两处各写一份）。 */
        fun upOne(g: ViscosityGrade): ViscosityGrade = of(g.level + 1)
    }
}

/** ① 温度档 0–3：偏凉/常温/偏暖/偏热。零档＝常温；全档无数值。 */
internal enum class FluidTempGrade(
    override val level: Int,
    override val label: String,
    override val zero: Boolean,
) : FluidGrade {
    COOL(0, "偏凉", false),
    MILD(1, "常温", true),
    WARM(2, "偏暖", false),
    HOT(3, "偏热", false),
    ;

    companion object {
        const val COUNT = 4

        fun of(level: Int): FluidTempGrade =
            values().first { it.level == level.coerceIn(0, COUNT - 1) }
    }
}

/** ① 干涸档 0–3：湿润/初干/干痕/干涸。零档＝湿润。 */
internal enum class DrynessGrade(
    override val level: Int,
    override val label: String,
    override val zero: Boolean,
) : FluidGrade {
    WET(0, "湿润", true),
    TACKY(1, "初干", false),
    DRY_MARK(2, "干痕", false),
    DRIED(3, "干涸", false),
    ;

    companion object {
        const val COUNT = 4
        const val DRIED_LEVEL = 3
        const val DRY_MARK_LEVEL = 2

        fun of(level: Int): DrynessGrade =
            values().first { it.level == level.coerceIn(0, COUNT - 1) }
    }
}

/** ① 每维必须挂的驱动四类：时间/暴露/温湿/事件。四类之外不许自造第五类。 */
internal enum class ChangeDriver(val label: String) {
    TIME("时间"),
    EXPOSURE("暴露"),
    TEMP_HUMIDITY("温湿"),
    EVENT("事件"),
    ;

    companion object {
        const val COUNT = 4
    }
}

/** 液种：汗/唾液/前液/精液。混合规则只认这四个；不新增「体液」这类合并种。 */
internal enum class FluidKind(val id: String, val label: String) {
    SWEAT("sweat", "汗"),
    SALIVA("saliva", "唾液"),
    PRE_SEMINAL("preSeminal", "前液"),
    SEMEN("semen", "精液"),
    ;

    companion object {
        const val COUNT = 4
    }
}

/** ② 外观四型：水光/挂丝/凝块/干痕。 */
internal enum class Appearance(val label: String) {
    SHEEN("水光"),
    STRINGY("挂丝"),
    CLOT("凝块"),
    DRY_MARK("干痕"),
}

/** ② 流动三型：挂住/下滑/拉丝。 */
internal enum class FlowKind(val label: String) {
    HOLD("挂住"),
    SLIDE("下滑"),
    STRETCH("拉丝"),
}

/** ③ 体位五型。垂坠倾向必须带体位，缺体位判红（派工单 ③「必须带体位」）。 */
internal enum class Posture(val label: String) {
    STANDING("站立"),
    SITTING("坐姿"),
    SUPINE("仰卧"),
    PRONE("俯卧"),
    LATERAL("侧卧"),
    ;

    companion object {
        const val COUNT = 5

        /** 竖直位（站立/坐姿）：重力沿体表往下带；卧姿不往下带。 */
        fun isUpright(p: Posture): Boolean = p == STANDING || p == SITTING
    }
}

/** ③ 液体垂坠倾向 0–3：不垂/略垂/下垂/垂坠。只讲倾向档，不讲角度、周期、摆动。 */
internal enum class DroopTendency(
    override val level: Int,
    override val label: String,
    override val zero: Boolean,
) : FluidGrade {
    NONE(0, "不垂", true),
    SLIGHT(1, "略垂", false),
    OBVIOUS(2, "下垂", false),
    HEAVY(3, "垂坠", false),
    ;

    companion object {
        const val COUNT = 4

        fun of(level: Int): DroopTendency =
            values().first { it.level == level.coerceIn(0, COUNT - 1) }
    }
}

/** ④ 织物被触及后的三态：珠滚/吸收/透过。未触及的层另记 [LayerOutcome.DRY]，不是第四态。 */
internal enum class LayerOutcome(val label: String) {
    DRY("未触及"),
    BEADED("珠滚"),
    ABSORBED("吸收"),
    PASSED_THROUGH("透过"),
}

/** ④ 材质四类：棉/化纤/混纺/羊毛。四类之外不许自造第五类。 */
internal enum class FabricMaterial(val label: String) {
    COTTON("棉"),
    SYNTHETIC("化纤"),
    BLEND("混纺"),
    WOOL("羊毛"),
    ;

    companion object {
        const val COUNT = 4
    }
}

/** ⑤ 覆盖档：无/点状/局部/半覆盖/全覆盖。量档只决定上限，见 [FluidCoverage.maxOf]。 */
internal enum class Coverage(
    override val level: Int,
    override val label: String,
    override val zero: Boolean,
) : FluidGrade {
    NONE(0, "无", true),
    SPOT(1, "点状", false),
    LOCAL(2, "局部", false),
    HALF(3, "半覆盖", false),
    FULL(4, "全覆盖", false),
    ;

    companion object {
        const val COUNT = 5

        fun of(level: Int): Coverage = values().first { it.level == level.coerceIn(0, COUNT - 1) }
    }
}

/** ⑤ 载体三类：体表/织物/器物。量 3 才允许全覆盖，且全覆盖必须点名载体。 */
internal enum class CoverageCarrier(val label: String) {
    BODY("体表"),
    FABRIC("织物"),
    OBJECT("器物"),
}

/** ⑥ 盆底驱动三档：强/中/弱。喷射必须给这一档——没有它就不是喷射。 */
internal enum class PelvicDrive(val label: String) {
    STRONG("强"),
    MID("中"),
    WEAK("弱"),
}

/** ⑥ 甩动强度三档：强/中/弱。 */
internal enum class SwingIntensity(val label: String) {
    STRONG("强"),
    MID("中"),
    WEAK("弱"),
}

/** ⑥ 脱离方向：切向/法向。甩出的初速必须沿切向；给法向即判红。 */
internal enum class DetachDir(val label: String) {
    TANGENTIAL("切向"),
    NORMAL("法向"),
}

/** ⑥ 甩动方向：向上/向下/侧向/斜向。甩出必须给方向。 */
internal enum class SwingDir(val label: String) {
    UP("向上"),
    DOWN("向下"),
    SIDE("侧向"),
    DIAGONAL("斜向"),
}

/** ⑥ 初速走向：本单只认「沿开口轴向」这一条，喷射之外的类型不许挂它。 */
internal enum class SpeedAxis(val label: String) {
    MEATUS_AXIS("沿开口轴向"),
}

/** ⑥ 落点所在的表面四类：体表/织物/地面/器物。 */
internal enum class SurfaceKind(val label: String) {
    SKIN("体表"),
    FABRIC("织物"),
    FLOOR("地面"),
    OBJECT("器物"),
}

/** ⑥ 离体三型 + 并存。三型互斥；两条以上同时成立记 [MIXED]，两段分别记。 */
internal enum class DepartureKind(val id: String, val label: String) {
    EJECTION("ejection", "喷射"),
    SHED_BY_MOTION("shedByMotion", "甩出"),
    SEEPAGE("seepage", "重力流"),
    MIXED("mixed", "并存"),
    ;

    companion object {
        const val SINGLE_COUNT = 3
    }
}
