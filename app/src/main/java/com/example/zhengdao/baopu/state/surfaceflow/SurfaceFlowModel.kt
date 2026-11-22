// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百零六批 · 只读数据模型：全字段是档位、枚举与部位，结构本身允许写错，
// 对错由判定层给码（判据才有反例可测）。无 IO、无随机、无时钟、无点值。
package com.example.zhengdao.baopu.state.surfaceflow

/** 一条问题：码 + 严重度 + 说清哪个部位。 */
internal data class SurfaceIssue(
    val code: String,
    val severity: IssueSeverity,
    val detail: String,
) {
    /** 稳定文本：单测、报告、变异逐字对齐，不另写一份。 */
    fun line(): String = "[" + severity.label + "]" + code + "：" + detail
}

/** 判定结果：有红就不出值——判不出来就不编。提示随值一起出去。 */
internal data class SurfaceVerdict<out T>(
    val value: T?,
    val issues: List<SurfaceIssue>,
) {
    val reds: List<SurfaceIssue> get() = issues.filter { it.severity == IssueSeverity.RED }
    val prompts: List<SurfaceIssue> get() = issues.filter { it.severity == IssueSeverity.PROMPT }
    val ok: Boolean get() = value != null && reds.isEmpty()

    companion object {
        fun <T> of(value: T): SurfaceVerdict<T> = SurfaceVerdict(value, emptyList())

        fun <T> red(code: String, detail: String): SurfaceVerdict<T> =
            SurfaceVerdict(null, listOf(SurfaceIssue(code, IssueSeverity.RED, detail)))

        fun <T> prompt(value: T?, code: String, detail: String): SurfaceVerdict<T> =
            SurfaceVerdict(value, listOf(SurfaceIssue(code, IssueSeverity.PROMPT, detail)))
    }
}

/** 一条路径：型 + 经过的部位 + 行为 + 去向。去向可空（走到端点就止）。 */
internal data class FlowPath(
    val kind: PathKind,
    val from: Site,
    val behavior: FlowBehavior,
    val to: Site? = null,
)

/** 一处积水：部位 + 档 + 出口。出口必填由判定层守，结构允许空以便测反例。 */
internal data class PoolRecord(
    val site: Site,
    val level: PoolLevel,
    val outlet: Site? = null,
    val via: PathKind = PathKind.ALONG_GROOVE,
)

/** 一处滴落：部位 + 最低点标记 + 包皮依据。后三者用可空表达「漏标」。 */
internal data class DripRecord(
    val site: Site,
    val isLowest: Boolean?,
    val prepuceTissue: PrepuceTissue? = null,
    val prepuceLength: PrepuceLength? = null,
)

/** 毛束导流是修饰：必须点名部位并说明沾没沾水。 */
internal data class HairWick(
    val site: Site,
    val wet: Boolean,
)

/** 一个汗区的读数：分区 + 量档 + 渗出阶段 + 蒸发档 + 是否先起珠/成片。 */
internal data class SweatZoneRead(
    val zone: SweatZone,
    val volume: FlowVolume,
    val stage: SeepStage,
    val evapTier: EvapTier?,
    val beadOrSheet: Boolean,
)

/** 汗画像：至少两区；uniformRunoff 是显式写「全身均匀流下」的入口。 */
internal data class SweatProfile(
    val zones: List<SweatZoneRead>,
    val uniformRunoff: Boolean = false,
)

/** 一条判定入参。所有字段可选/可空，方便把八条判据的反例构造出来。 */
internal data class SurfaceFlowClaim(
    val source: WaterSource,
    val posture: FlowPosture? = null,
    val paths: List<FlowPath> = emptyList(),
    val pools: List<PoolRecord> = emptyList(),
    val drips: List<DripRecord> = emptyList(),
    val hairWicks: List<HairWick> = emptyList(),
    val sweat: SweatProfile? = null,
    val note: String = "",
)

/** 四件输出的结构化形态，与 [SurfaceFlowResult] 一一对应。 */
internal data class FlowOutputs(
    val paths: List<String>,
    val poolAt: List<String>,
    val dripAt: List<String>,
    val posture: String,
) {
    /** 一行机读快照：四个字段固定次序，同输入逐字相同。 */
    fun line(): String = buildString {
        append("paths[").append(paths.joinToString(";")).append("]|")
        append("poolAt[").append(poolAt.joinToString(";")).append("]|")
        append("dripAt[").append(dripAt.joinToString(";")).append("]|")
        append("posture=").append(posture)
    }
}

/** 通过判定后的值：原样带出结构化输入，渲染由 [SurfaceFlowRenderer] 独家负责。 */
internal data class SurfaceFlowResult(
    val source: WaterSource,
    val posture: FlowPosture,
    val paths: List<FlowPath>,
    val pools: List<PoolRecord>,
    val drips: List<DripRecord>,
    val hairWicks: List<HairWick>,
    val sweat: SweatProfile?,
)
