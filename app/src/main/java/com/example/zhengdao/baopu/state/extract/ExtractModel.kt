// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百五十三批 · 描写抽取：契约层。
// 正文 → observed（档位观测）／traces（状态痕迹）／misses（写了但抽不出）。
// 纯数据：零 IO、零网络、零随机、零时钟；同输入必得同结论。
package com.example.zhengdao.baopu.state.extract

/** 本单唯一来源口径。 */
const val EXTRACT_SOURCE: String =
    "派工单-2026-11-23-第八百五十三批（描写抽取）＋ERRATA 2026-11-23（下体追加）"
const val EXTRACT_AS_OF: String = "2026-11-23"

/** 置信三档。低档不许越过出口喂连续性校验（见 [ExtractResult.observedForCheck]）。 */
enum class Confidence(val label: String) {
    HIGH("高"), MIDDLE("中"), LOW("低"),
}

/**
 * 原文片段位置。[text] 必须与原文 [start, end) 逐字相同——构造即校验，不许归一化。
 */
data class Span(val start: Int, val end: Int, val text: String) {
    init {
        require(start >= 0 && end >= start) { "span 位置非法：$start-$end" }
        require(text.length == end - start) { "span 长度与片段不符：$start-$end / ${text.length}" }
    }

    fun line(): String = "$start-$end:$text"
}

/** 抽取维度。[tierSource] 是档位来源单号，逐条随观测出门。 */
enum class ExtractDim(val label: String, val tierSource: String) {
    // A · 躯干八维（848）
    RECTUS_BLOCKS("腹直肌块数", "848"),
    LINEA_ALBA("白线深浅", "848"),
    BLOCK_ALIGNMENT("左右等高", "848"),
    PEC_LOWER_TREND("胸肌下缘走向", "848"),
    PEC_GAP("胸沟宽窄", "848"),
    TRAP_SLOPE("斜方坡度", "848"),
    LAT_FLARE("背阔外扩", "848"),
    SERRATUS("前锯肌", "848"),
    // B · 男性下体（804/831 已合档）
    COVER_STATE("覆盖三态", "817"),
    AREA_LEVEL("覆盖六档", "831"),
    EXPOSE_TYPE("露出四型", "804"),
    CIRCUMCISION("环切", "804"),
    FLACCID_SIZE("疲软尺寸", "804"),
    RETRACT_STATE("回缩态", "804"),
    // C · 状态痕迹（817/658）
    SWEAT("出汗", "817"),
    FATIGUE("疲劳", "817"),
    BREATH("呼吸", "817"),
    POSTURE("姿态", "817"),
    SKIN("皮肤变化", "817"),
    ERECT_STATE("疲软/勃起态", "810"),
    ;

    val isTorso: Boolean get() = tierSource == "848"
    val isGenital: Boolean get() = this in GENITAL_DIMS
    val isTrace: Boolean get() = this in TRACE_DIMS

    companion object {
        val GENITAL_DIMS: Set<ExtractDim> = setOf(
            COVER_STATE, AREA_LEVEL, EXPOSE_TYPE, CIRCUMCISION, FLACCID_SIZE, RETRACT_STATE,
        )
        val TRACE_DIMS: Set<ExtractDim> = setOf(SWEAT, FATIGUE, BREATH, POSTURE, SKIN, ERECT_STATE)
    }
}

/**
 * 可配字段名表。817 基线由 [FieldNames.of817] 产出并标 source=817；
 * 869 增字段后调用方注入新表即可，抽取器不改一行。
 */
interface FieldNameTable {
    val source: String
    fun field(dim: ExtractDim): String?
}

/** 一条档位观测：每条必带 [span] 与 [confidence]，[grade] 逐字引用既有档。 */
data class Observed(
    val dim: ExtractDim,
    val field: String?,
    val grade: String,
    val span: Span,
    val confidence: Confidence,
) {
    init {
        require(grade.isNotBlank()) { "观测档不许为空" }
    }

    fun line(): String = "${dim.label}|$field|$grade|${confidence.label}|${span.line()}"
}

/** 一条状态痕迹。810 未落地两态只许进这里：[grade] 为 null，不造 erectState 档。 */
data class TraceHit(
    val dim: ExtractDim,
    val field: String?,
    val word: String,
    val grade: String?,
    val span: Span,
    val confidence: Confidence,
    val pending: String? = null,
) {
    fun line(): String =
        "${dim.label}|$field|$word|${grade ?: "无档"}|${confidence.label}|${pending ?: "-"}|${span.line()}"
}

/** 写了但抽不出的原因。 */
enum class MissReason {
    /** 比喻：不许理解、不许猜成硬度档。 */
    METAPHOR,
    /** 正文有描写但仓内无既有档可落。 */
    NO_GRADE,
    /** 未落地单号占位（814/875/775/731/810）。 */
    PENDING,
    /** 多种档皆可能，判不出 ⇒ 不许猜。 */
    AMBIGUOUS,
}

/** 一条显式 miss。[pending] 只放单个单号，形如 814；不许塞一句话。 */
data class Miss(
    val dim: ExtractDim?,
    val reason: MissReason,
    val span: Span,
    val pending: String? = null,
    val note: String,
) {
    init {
        require(reason != MissReason.PENDING || pending?.toIntOrNull() != null) {
            "pending miss 必须带纯数字单号"
        }
        require(note.isNotBlank()) { "miss 必须写原因，不许静默丢" }
    }

    fun line(): String = "${dim?.label ?: "-"}|$reason|${pending ?: "-"}|$note|${span.line()}"
}

/** 三列表唯一结果。[line] 为稳定快照，供报告逐字对齐。 */
data class ExtractResult(
    val observed: List<Observed>,
    val traces: List<TraceHit>,
    val misses: List<Miss>,
) {
    /**
     * 喂 817 连续性校验的唯一出口：低置信一律不越级；字段与档必须同时被 817 白名单接受。
     * 未落地字段（如 869 未合的躯干档）落不到 817 ⇒ 留在 observed，不进本出口。
     */
    fun observedForCheck(): Map<String, List<String>> = buildMap<String, MutableList<String>> {
        observed.forEach { hit ->
            val id = hit.field
            if (hit.dim.isTrace) return@forEach
            if (hit.confidence == Confidence.LOW || id == null || !CastingRefsGate.known(id)) return@forEach
            if (!CastingRefsGate.accepts(id, hit.grade)) return@forEach
            getOrPut(id) { ArrayList() }.add(hit.grade)
        }
    }

    /**
     * 喂 817 鲜活判据的唯一出口：同样挡低置信；810 状态词 grade 为 null 永不进。
     * 817 把五类痕迹（出汗/疲劳/呼吸/姿态/皮肤）登记为 TRACE 字段，它们在本抽取器里
     * 是有既有档的 [Observed]；这里一并收集，供 VitalityCheck 按字段 id 判鲜活。
     */
    fun traceIdsForCheck(): Set<String> {
        val fromTraces = traces.mapNotNull { hit ->
            val id = hit.field
            if (hit.confidence == Confidence.LOW || hit.grade == null || id == null) null
            else if (CastingRefsGate.known(id) && CastingRefsGate.accepts(id, hit.grade)) id else null
        }
        val fromObserved = observed.mapNotNull { hit ->
            val id = hit.field
            if (!hit.dim.isTrace || hit.confidence == Confidence.LOW || id == null) null
            else if (CastingRefsGate.known(id) && CastingRefsGate.accepts(id, hit.grade)) id else null
        }
        return (fromTraces + fromObserved).toSet()
    }

    fun line(): String = buildString {
        append("【observed】\n")
        observed.forEach { append(it.line()).append('\n') }
        append("【traces】\n")
        traces.forEach { append(it.line()).append('\n') }
        append("【misses】\n")
        misses.forEach { append(it.line()).append('\n') }
    }
}

/**
 * 817 引用闸门：只消费字段名与档白名单，不改 casting 包、不复制档表。
 * 单独成对象，便于源码门禁钉住「只查 known/accepts，不许自造字段」。
 */
object CastingRefsGate {
    fun known(field: String): Boolean = com.example.zhengdao.baopu.state.casting.CastingRefs.known(field)

    fun accepts(field: String, grade: String): Boolean =
        com.example.zhengdao.baopu.state.casting.CastingRefs.field(field).accepts(grade)
}
