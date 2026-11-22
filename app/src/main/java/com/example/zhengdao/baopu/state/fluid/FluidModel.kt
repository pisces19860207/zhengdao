// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百八十批 · 只读数据模型：全字段是档位、枚举、驱动列表。
// 没有计量值、没有 timestamp、没有随机种子；同输入构造出的对象必相等（判据⑧）。
package com.example.zhengdao.baopu.state.fluid

/**
 * 一条判定结果：值可能为空（判不出来就不编），问题分两档——
 * [IssueSeverity.RED] 是「这样写就是错的」，[IssueSeverity.PROMPT] 是「缺个交代，补上再用」。
 */
internal enum class IssueSeverity(val label: String) {
    RED("红"),
    PROMPT("提示"),
}

internal data class FluidIssue(
    val code: String,
    val severity: IssueSeverity,
    val detail: String,
) {
    /** 稳定文本：报告、测试、变异都拿它对齐，不另写一份。 */
    fun line(): String = "[" + severity.label + "]" + code + "：" + detail
}

internal data class FluidVerdict<out T>(
    val value: T?,
    val issues: List<FluidIssue>,
) {
    val reds: List<FluidIssue> get() = issues.filter { it.severity == IssueSeverity.RED }
    val prompts: List<FluidIssue> get() = issues.filter { it.severity == IssueSeverity.PROMPT }

    /** 有值且无红：可以拿去用。只有提示时仍然算「可出」，但提示必须随行带出去。 */
    val ok: Boolean get() = value != null && reds.isEmpty()

    companion object {
        fun <T> of(value: T): FluidVerdict<T> = FluidVerdict(value, emptyList())

        fun <T> red(code: String, detail: String): FluidVerdict<T> =
            FluidVerdict(null, listOf(FluidIssue(code, IssueSeverity.RED, detail)))

        fun <T> prompt(value: T?, code: String, detail: String): FluidVerdict<T> =
            FluidVerdict(value, listOf(FluidIssue(code, IssueSeverity.PROMPT, detail)))
    }
}

/** 一条档位读数：档 + 驱动。零档允许无驱动；非零档必须 ≥1 驱动（派工单 ① 每维带驱动）。 */
internal data class GradeRead<G : FluidGrade>(
    val grade: G,
    val drivers: List<ChangeDriver>,
)

/**
 * ① 四维变化：量/黏稠/温度/干涸，四维各自独立。
 * 不许用一维顶替另一维（量多≠稠、稠≠干），四维也没有任何一个合并字段。
 */
internal data class FluidBody(
    val volume: GradeRead<VolumeGrade>,
    val viscosity: GradeRead<ViscosityGrade>,
    val temperature: GradeRead<FluidTempGrade>,
    val dryness: GradeRead<DrynessGrade>,
)

/** 时间推进档：片刻/一阵/许久。只表档位，不写任何时长数值。 */
internal enum class ElapsedBand(val level: Int, val label: String) {
    BRIEF(0, "片刻"),
    WHILE(1, "一阵"),
    LONG(2, "许久"),
    ;

    companion object {
        const val COUNT = 3

        fun of(level: Int): ElapsedBand = values().first { it.level == level.coerceIn(0, COUNT - 1) }
    }
}

/** 暴露档：有遮盖/裸露/迎风。迎风才带走水分。 */
internal enum class ExposureBand(val level: Int, val label: String) {
    COVERED(0, "有遮盖"),
    OPEN_AIR(1, "裸露"),
    AIRFLOW(2, "迎风"),
    ;

    companion object {
        const val COUNT = 3

        fun of(level: Int): ExposureBand =
            values().first { it.level == level.coerceIn(0, COUNT - 1) }
    }
}

/** 温湿档：凉干/温和/偏暖/湿热。温与湿合成一档，对应「温湿」这一条驱动。 */
internal enum class EnvBand(val level: Int, val label: String) {
    COOL_DRY(0, "凉干"),
    MILD(1, "温和"),
    WARM(2, "偏暖"),
    HOT_HUMID(3, "湿热"),
    ;

    companion object {
        const val COUNT = 4

        fun of(level: Int): EnvBand = values().first { it.level == level.coerceIn(0, COUNT - 1) }
    }
}

/** 事件四类：无/新液/接触/动作。**只有 [FRESH_FLUID] 算「新来源」**（判据③）。 */
internal enum class EventKind(val label: String) {
    NONE("无"),
    FRESH_FLUID("新液"),
    CONTACT("接触"),
    MOTION("动作"),
    ;

    companion object {
        const val COUNT = 4

        /** 新来源：能让干涸重新变湿的唯一事件。 */
        fun isFreshSource(e: EventKind): Boolean = e == FRESH_FLUID
    }
}

/** 一步变化入参：时间、暴露、温湿、事件各一档。 */
internal data class FluidStep(
    val elapsed: ElapsedBand,
    val exposure: ExposureBand,
    val env: EnvBand,
    val event: EventKind,
) {
    companion object {
        /** 全零步：片刻/有遮盖/温和/无事件——什么都不该变。 */
        val STILL = FluidStep(ElapsedBand.BRIEF, ExposureBand.COVERED, EnvBand.MILD, EventKind.NONE)
    }
}

/** 混合方式：只许「主液主导＋次液修正」；[AVERAGED] 是可表达的错，判红（判据①）。 */
internal enum class MixMode(val label: String) {
    DOMINANT_CORRECTION("主液主导＋次液修正"),
    AVERAGED("平均式"),
}

/** 命中的混合规则。规则名册固定三条 + 单液，不许临时发明第四条。 */
internal enum class MixRule(val id: String, val label: String) {
    SWEAT_PRE_SEMINAL("sweat+preSeminal", "汗＋前液：体表水光，黏度不升"),
    SALIVA_PRE_SEMINAL("saliva+preSeminal", "唾液＋前液：更滑，干得慢"),
    PRE_SEMINAL_SEMEN("preSeminal+semen", "前液＋精液：黏度跳一档"),
    PLAIN("plain", "单液，无混合规则"),
}

/** 干得快慢：常态/干得慢。只是一个档位方向，不给时长。 */
internal enum class DryHint(val label: String) {
    NORMAL("常态"),
    SLOW("干得慢"),
}

/** ② 混合入参。主液必须显式给（null ＝ 没给，判据①提示）。 */
internal data class MixRequest(
    val primary: FluidKind?,
    val secondary: FluidKind?,
    val primaryViscosity: ViscosityGrade = ViscosityGrade.THIN,
    val secondaryViscosity: ViscosityGrade = ViscosityGrade.RUNNY,
    val onSkin: Boolean = true,
    val mode: MixMode = MixMode.DOMINANT_CORRECTION,
)

/**
 * ② 混合产出：**主液是谁**先写死，再看外观与流动。
 * [baseViscosity] 是混合前主液的黏度，留给判据②核对「精液出现后有没有升档」。
 */
internal data class Mixture(
    val dominant: FluidKind,
    val secondary: FluidKind?,
    val baseViscosity: ViscosityGrade,
    val viscosity: ViscosityGrade,
    val appearance: Appearance,
    val flow: FlowKind,
    val dryHint: DryHint,
    val rule: MixRule,
    val onSkin: Boolean,
)

/** ③ 垂坠入参：体位可空——就是为了让「缺体位」可被判红。 */
internal data class DroopRequest(
    val posture: Posture?,
    val volume: VolumeGrade,
    val viscosity: ViscosityGrade,
)

/** ③ 垂坠产出：倾向档 + 它所依附的体位（体位是必填项，不是装饰）。 */
internal data class DroopReading(
    val tendency: DroopTendency,
    val posture: Posture,
)

/** ④ 一层织物：材质 + 被触及后的结果。层序由外到内。 */
internal data class FabricLayer(
    val material: FabricMaterial,
    val outcome: LayerOutcome,
)

/** ④ 织物栈：至少一层；层序固定由外到内，不许跳层。 */
internal data class FabricStack(val layers: List<FabricLayer>)

/** ⑤ 覆盖请求：量档 + 想要铺多大 + 铺在什么载体上（量 3 全覆盖时必填）。 */
internal data class CoverageClaim(
    val volume: VolumeGrade,
    val coverage: Coverage,
    val carrier: CoverageCarrier?,
)

/** ⑤ 覆盖产出：这一量档下允许的最大覆盖 + 最终认定的覆盖与载体。 */
internal data class CoverageReading(
    val coverage: Coverage,
    val maxAllowed: Coverage,
    val carrier: CoverageCarrier?,
)

/** ⑥ 落点：一句话点名落在哪里 + 那是什么表面。字符串是描述，不是坐标。 */
internal data class Landing(
    val target: String,
    val surface: SurfaceKind,
)

/** ⑥ 喷射入参：盆底驱动与落点都可空——空即判红（判据⑤）。 */
internal data class EjectionRequest(
    val drive: PelvicDrive?,
    val landing: Landing?,
    val axis: SpeedAxis = SpeedAxis.MEATUS_AXIS,
)

/** ⑥ 喷射轨迹：初速沿开口轴向，其后只受重力（抛物线）。这两条是类型自带的，不靠文案自觉。 */
internal data class Ejection(
    val drive: PelvicDrive,
    val landing: Landing,
    val axis: SpeedAxis,
) : DeparturePart {
    override val kind: DepartureKind get() = DepartureKind.EJECTION

    /** 初速之后只有重力：本单不写第二种受力。 */
    val gravityOnly: Boolean get() = true
}

/** ⑥ 甩出入参：强度、脱离方向、甩动方向都可空——缺一项即判红（判据⑥）。 */
internal data class ShedRequest(
    val intensity: SwingIntensity?,
    val detach: DetachDir?,
    val dir: SwingDir?,
)

/** ⑥ 甩出轨迹：**无盆底参与**，初速＝切向速度。 */
internal data class Shed(
    val intensity: SwingIntensity,
    val detach: DetachDir,
    val dir: SwingDir,
) : DeparturePart {
    override val kind: DepartureKind get() = DepartureKind.SHED_BY_MOTION

    /** 甩出与盆底无关：这里是常量 false，不是「可选参数」。 */
    val pelvicInvolved: Boolean get() = false
}

/** ⑥ 重力流：无初速、贴体流。结构上就没有「初速」与「抛物线」两个字段。 */
internal data class Seepage(val attachedToBody: Boolean) : DeparturePart {
    override val kind: DepartureKind get() = DepartureKind.SEEPAGE

    companion object {
        /** 唯一构造口：贴体。贴不贴体不是可选项。 */
        val ATTACHED = Seepage(true)
    }
}

/** 离体段共契约：三段各是一个类型，[kind] 由类型决定，调用方改不了。 */
internal sealed interface DeparturePart {
    val kind: DepartureKind
}

/** ⑥ 离体产出：逐段记。一段 ⇒ 该型；两段以上 ⇒ [DepartureKind.MIXED]，段序即渲染序。 */
internal data class DepartureSet(val segments: List<DeparturePart>) {

    init {
        require(segments.isNotEmpty()) { "离体段不许为空：没有液体离开身体就没有这一笔" }
    }

    /** 并存标记：两条以上同时成立。 */
    val kind: DepartureKind get() = if (segments.size == 1) segments[0].kind else DepartureKind.MIXED

    val kinds: List<DepartureKind> get() = segments.map { it.kind }
}
