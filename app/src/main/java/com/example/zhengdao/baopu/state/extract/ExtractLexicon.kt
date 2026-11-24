// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百五十三批 · 词表层：正文片段 → 既有档。
// 本文件一个档名都不自造：每条规则的 grade 都取自既有枚举的 label 或既有词表的 words。
package com.example.zhengdao.baopu.state.extract

import com.example.zhengdao.baopu.state.anchor.AnchorFields
import com.example.zhengdao.baopu.state.bodyparts.BlockAlignment
import com.example.zhengdao.baopu.state.bodyparts.LatFlare
import com.example.zhengdao.baopu.state.bodyparts.LineaAlba
import com.example.zhengdao.baopu.state.bodyparts.PecGapWidth
import com.example.zhengdao.baopu.state.bodyparts.PecLowerTrend
import com.example.zhengdao.baopu.state.bodyparts.Posture
import com.example.zhengdao.baopu.state.bodyparts.RectusBlocks
import com.example.zhengdao.baopu.state.bodyparts.Serratus
import com.example.zhengdao.baopu.state.bodyparts.TrapSlope
import com.example.zhengdao.baopu.state.physiology.Fatigue
import com.example.zhengdao.baopu.state.physiology.SweatLevel
import com.example.zhengdao.baopu.state.prepuce.AreaLevel
import com.example.zhengdao.baopu.state.prepuce.Circumcision
import com.example.zhengdao.baopu.state.prepuce.CoverState
import com.example.zhengdao.baopu.state.prepuce.ExposeType
import com.example.zhengdao.baopu.state.prepuce.FlaccidSize
import com.example.zhengdao.baopu.state.prepuce.RetractState

/** 一条词表规则。[grade] 为 null 只用于 810 状态词（只进 traces，不造档）。 */
internal data class Rule(
    val dim: ExtractDim,
    val pattern: String,
    val grade: String?,
    val exact: Boolean = true,
    val stateOnly: Boolean = false,
)

/** 一条 pending/no-grade 词表。[pending] 为单号字符串。 */
internal data class MissRule(
    val pattern: String,
    val reason: MissReason,
    val pending: String? = null,
    val dim: ExtractDim? = null,
    val note: String = "",
)

/**
 * 817 基线字段名常量表。只列 817 已合入的字段名；躯干八维（848）与六档面积（804/831）
 * 在 817 里没有卡字段，故留空——观测照抽，但不许喂 817 校验。
 * 869 合入后调用方注入新表即可，抽取器一行不改。
 */
object FieldNames {
    fun of817(): FieldNameTable = Table("817", mapOf(
        ExtractDim.COVER_STATE to "prepuce.cover",
        ExtractDim.EXPOSE_TYPE to "prepuce.expose",
        ExtractDim.CIRCUMCISION to "prepuce.circum",
        ExtractDim.FLACCID_SIZE to "prepuce.size",
        ExtractDim.SWEAT to "trace.sweat",
        ExtractDim.FATIGUE to "trace.fatigue",
        ExtractDim.BREATH to "trace.breath",
        ExtractDim.POSTURE to "trace.posture",
        ExtractDim.SKIN to "trace.skin",
    ))

    /** 调用方可注入的字段名表；[names] 未登记的维度返回 null（观测保留，出口不喂校验）。 */
    data class Table(override val source: String, private val names: Map<ExtractDim, String>) : FieldNameTable {
        override fun field(dim: ExtractDim): String? = names[dim]
    }
}

object ExtractLexicon {

    /** 比喻标记：命中这些结构时整段进 misses，不许当成档位描述。 */
    val METAPHOR_START = listOf("像", "仿佛", "如同", "宛如", "好似", "似乎", "好像")
    val METAPHOR_END = listOf("一样", "一般", "似的", "那般")

    /** 模糊词：命中前后 6 字内出现 ⇒ 低置信，出口一律不喂校验。 */
    val HEDGES = listOf("似乎", "大概", "有点", "好像", "或许", "大约", "勉强", "仿佛")

    /** A · 躯干八维（848）——档逐字取 TorsoGrades 五个枚举的 label。 */
    internal fun torso(): List<Rule> = listOf(
        r(ExtractDim.RECTUS_BLOCKS, "界不清", RectusBlocks.INDISTINCT.label),
        r(ExtractDim.RECTUS_BLOCKS, "两块", RectusBlocks.TWO.label),
        r(ExtractDim.RECTUS_BLOCKS, "四块", RectusBlocks.FOUR.label),
        r(ExtractDim.RECTUS_BLOCKS, "六块", RectusBlocks.SIX.label),
        r(ExtractDim.RECTUS_BLOCKS, "八块", RectusBlocks.EIGHT.label),
        r(ExtractDim.LINEA_ALBA, "被脂覆盖", LineaAlba.COVERED.label),
        r(ExtractDim.LINEA_ALBA, "白线浅", LineaAlba.SHALLOW.label),
        r(ExtractDim.LINEA_ALBA, "白线中", LineaAlba.MEDIUM.label),
        r(ExtractDim.LINEA_ALBA, "白线深", LineaAlba.DEEP.label),
        r(ExtractDim.BLOCK_ALIGNMENT, "左右等高", BlockAlignment.EVEN.label),
        r(ExtractDim.BLOCK_ALIGNMENT, "略错", BlockAlignment.SLIGHT_OFFSET.label),
        r(ExtractDim.BLOCK_ALIGNMENT, "明显错", BlockAlignment.CLEAR_OFFSET.label),
        r(ExtractDim.PEC_LOWER_TREND, "下缘平", PecLowerTrend.FLAT.label),
        r(ExtractDim.PEC_LOWER_TREND, "外高内低", PecLowerTrend.OUTER_HIGH.label),
        r(ExtractDim.PEC_LOWER_TREND, "外低内高", PecLowerTrend.OUTER_LOW.label),
        r(ExtractDim.PEC_LOWER_TREND, "圆钝", PecLowerTrend.ROUND_BLUNT.label),
        r(ExtractDim.PEC_GAP, "胸沟窄", PecGapWidth.NARROW.label),
        r(ExtractDim.PEC_GAP, "胸沟宽", PecGapWidth.WIDE.label),
        r(ExtractDim.PEC_GAP, "胸沟中", PecGapWidth.MID.label),
        r(ExtractDim.TRAP_SLOPE, "斜方平缓", TrapSlope.GENTLE.label),
        r(ExtractDim.TRAP_SLOPE, "斜方陡", TrapSlope.STEEP.label),
        r(ExtractDim.TRAP_SLOPE, "斜方中", TrapSlope.MID.label),
        r(ExtractDim.LAT_FLARE, "倒三角", LatFlare.V_TAPER.label),
        r(ExtractDim.LAT_FLARE, "下缘外翻", LatFlare.LOWER_FLARE.label),
        r(ExtractDim.LAT_FLARE, "直下", LatFlare.STRAIGHT_DOWN.label),
        r(ExtractDim.SERRATUS, "前锯肌隐约", Serratus.FAINT.label),
        r(ExtractDim.SERRATUS, "前锯肌明显", Serratus.OBVIOUS.label),
        r(ExtractDim.SERRATUS, "前锯肌无", Serratus.NONE.label),
    )

    /** B① · 覆盖三态（817 已合、可喂校验）＋六档面积（804/831 已合、817 无卡字段故不喂）。 */
    internal fun coverage(): List<Rule> = listOf(
        r(ExtractDim.COVER_STATE, "覆盖", CoverState.COVERED.label),
        r(ExtractDim.COVER_STATE, "半露", CoverState.PARTIAL.label),
        r(ExtractDim.COVER_STATE, "露出", CoverState.EXPOSED.label),
        r(ExtractDim.AREA_LEVEL, "完全覆盖", AreaLevel.FULL_COVER.label),
        r(ExtractDim.AREA_LEVEL, "几乎全部", AreaLevel.ALMOST_ALL.label),
        r(ExtractDim.AREA_LEVEL, "过半", AreaLevel.OVER_HALF.label),
        r(ExtractDim.AREA_LEVEL, "只露一点", AreaLevel.CORONA_ONLY.label),
        r(ExtractDim.AREA_LEVEL, "退到冠后", AreaLevel.RETRACTED_BEHIND_CORONA.label),
        r(ExtractDim.AREA_LEVEL, "退至冠后", AreaLevel.RETRACTED_BEHIND_CORONA.label),
        r(ExtractDim.AREA_LEVEL, "完全露出", AreaLevel.FULLY_EXPOSED.label),
        r(ExtractDim.AREA_LEVEL, "全露", AreaLevel.FULLY_EXPOSED.label),
    )

    /** B① · 四型逐条可判；判不出的（只写「全露」没给成因）在抽取器里进 misses。 */
    internal fun expose(): List<Rule> = listOf(
        r(ExtractDim.EXPOSE_TYPE, "环切后", ExposeType.POST_CIRCUMCISION.label),
        r(ExtractDim.EXPOSE_TYPE, "自然全露", ExposeType.NATURAL.label),
        r(ExtractDim.EXPOSE_TYPE, "状态性全露", ExposeType.ERECTION_ONLY.label),
        r(ExtractDim.EXPOSE_TYPE, "仅勃起", ExposeType.ERECTION_ONLY.label, exact = false),
        r(ExtractDim.EXPOSE_TYPE, "手动未复位", ExposeType.MANUAL_UNRESTORED.label),
        r(ExtractDim.CIRCUMCISION, "已环切", Circumcision.DONE.label),
        r(ExtractDim.CIRCUMCISION, "未环切", Circumcision.KEPT.label),
        r(ExtractDim.FLACCID_SIZE, "疲软偏短", FlaccidSize.SHORT.label),
        r(ExtractDim.FLACCID_SIZE, "疲软中等", FlaccidSize.MEDIUM.label),
        r(ExtractDim.FLACCID_SIZE, "疲软偏长", FlaccidSize.LONG.label),
        r(ExtractDim.RETRACT_STATE, "可完全回缩", RetractState.FULL.label),
        r(ExtractDim.RETRACT_STATE, "部分回缩", RetractState.PARTIAL.label),
        r(ExtractDim.RETRACT_STATE, "不可回缩", RetractState.NONE.label),
    )

    /** C · 状态痕迹：出汗/疲劳/姿态/皮肤取自 817 引用表，呼吸取自 747 t.breath 词表。 */
    internal fun traces(): List<Rule> {
        val breath = AnchorFields.spec("t.breath").words
        val skin = AnchorFields.spec("posture.skinFeel").words
        return listOf(
            r(ExtractDim.SWEAT, "无汗", SweatLevel.NONE.label),
            r(ExtractDim.SWEAT, "微汗", SweatLevel.SLIGHT.label),
            r(ExtractDim.SWEAT, "明显出汗", SweatLevel.OBVIOUS.label),
            r(ExtractDim.SWEAT, "湿透", SweatLevel.SOAKED.label),
            r(ExtractDim.FATIGUE, "清醒", Fatigue.AWAKE.label),
            r(ExtractDim.FATIGUE, "轻疲", Fatigue.LIGHT.label),
            r(ExtractDim.FATIGUE, "中疲", Fatigue.MID.label),
            r(ExtractDim.FATIGUE, "力竭", Fatigue.EXHAUSTED.label),
            r(ExtractDim.BREATH, "呼吸浅", breath[0]),
            r(ExtractDim.BREATH, "呼吸深", breath[1]),
            r(ExtractDim.BREATH, "屏息", breath[2]),
            r(ExtractDim.POSTURE, "放松", Posture.RELAXED.label),
            r(ExtractDim.POSTURE, "收紧", Posture.TIGHTENED.label),
            r(ExtractDim.POSTURE, "屈曲用力", Posture.FLEXED.label),
            r(ExtractDim.SKIN, "皮肤光滑", skin[0]),
            r(ExtractDim.SKIN, "皮肤粗糙", skin[1]),
            r(ExtractDim.SKIN, "皮肤多毛", skin[2]),
            r(ExtractDim.SKIN, "皮肤有痣", skin[3]),
        )
    }

    /**
     * B② · 810 未落地的疲软/勃起两态：只许进 traces，grade=null，pending:810。
     * 不设 erectState 档、不造 groinBulge 档。
     */
    internal fun stateWords(): List<Rule> = listOf(
        Rule(ExtractDim.ERECT_STATE, "疲软", null, stateOnly = true),
        Rule(ExtractDim.ERECT_STATE, "勃起", null, stateOnly = true),
        Rule(ExtractDim.ERECT_STATE, "半勃", null, stateOnly = true),
    )

    /** 未落地与无档词表：一律显式 misses，逐条标 pending 单号。 */
    internal fun misses(): List<MissRule> = listOf(
        mr("截面", "814", ExtractDim.AREA_LEVEL, "截面档未落地（814）"),
        mr("廓形", "814", ExtractDim.AREA_LEVEL, "廓形档未落地（814）"),
        mr("很显", "875", null, "外观隆起档未落地（875）"),
        mr("不太显", "875", null, "外观隆起档未落地（875）"),
        mr("几乎不显", "875", null, "外观隆起档未落地（875）"),
        MissRule("包着", MissReason.AMBIGUOUS, null, ExtractDim.AREA_LEVEL,
            "「包着」可为过半/几乎全部/完全覆盖，判不出具体档，不许猜"),
        mr("青筋", "731", null, "青筋可见档未落地（731），且须驱动因"),
        mr("子区质地", "775", null, "子区质地档未落地（775）"),
        mr("meatus", "775", null, "meatus 形态档未落地（775）"),
        mr("短发", null, null, "817 hair.style 为 NONE 源、无既有封闭档，不许自造「短」档"),
        mr("长发", null, null, "817 hair.style 为 NONE 源、无既有封闭档，不许自造「长」档"),
    )

    /** 全部可落档规则（状态词单独走 traces）。 */
    internal val ALL: List<Rule> = (torso() + coverage() + expose() + traces()).distinctBy { it.dim.name + it.pattern }

    internal val ALL_STATE: List<Rule> = stateWords()

    internal val ALL_MISS: List<MissRule> = misses()

    private fun r(dim: ExtractDim, pattern: String, grade: String, exact: Boolean = true) =
        Rule(dim, pattern, grade, exact)

    private fun mr(
        pattern: String,
        pending: String?,
        dim: ExtractDim?,
        note: String,
    ) = MissRule(pattern, if (pending == null) MissReason.NO_GRADE else MissReason.PENDING, pending, dim, note)

    /** 维 → 既有档取法。表驱动：分支是数据，不是 when，圈复杂度恒为 1。 */
    private val gradeTable: Map<ExtractDim, () -> List<String>> = mapOf(
        ExtractDim.RECTUS_BLOCKS to { RectusBlocks.entries.map { it.label } },
        ExtractDim.LINEA_ALBA to { LineaAlba.entries.map { it.label } },
        ExtractDim.BLOCK_ALIGNMENT to { BlockAlignment.entries.map { it.label } },
        ExtractDim.PEC_LOWER_TREND to { PecLowerTrend.entries.map { it.label } },
        ExtractDim.PEC_GAP to { PecGapWidth.entries.map { it.label } },
        ExtractDim.TRAP_SLOPE to { TrapSlope.entries.map { it.label } },
        ExtractDim.LAT_FLARE to { LatFlare.entries.map { it.label } },
        ExtractDim.SERRATUS to { Serratus.entries.map { it.label } },
        ExtractDim.COVER_STATE to { CoverState.entries.map { it.label } },
        ExtractDim.AREA_LEVEL to { AreaLevel.entries.map { it.label } },
        ExtractDim.EXPOSE_TYPE to { ExposeType.entries.map { it.label } },
        ExtractDim.CIRCUMCISION to { Circumcision.entries.map { it.label } },
        ExtractDim.FLACCID_SIZE to { FlaccidSize.entries.map { it.label } },
        ExtractDim.RETRACT_STATE to { RetractState.entries.map { it.label } },
        ExtractDim.SWEAT to { SweatLevel.entries.map { it.label } },
        ExtractDim.FATIGUE to { Fatigue.entries.map { it.label } },
        ExtractDim.BREATH to { AnchorFields.spec("t.breath").words },
        ExtractDim.POSTURE to { Posture.entries.map { it.label } },
        ExtractDim.SKIN to { AnchorFields.spec("posture.skinFeel").words },
        ExtractDim.ERECT_STATE to { emptyList() },
    )

    init {
        require(gradeTable.keys == ExtractDim.entries.toSet()) { "既有档表必须覆盖全部维度，不许漏维" }
    }

    /** 把同维所有档取出来，供源码/单测核对「规则里的档全在既有档内」。 */
    fun legalGrades(dim: ExtractDim): List<String> = gradeTable.getValue(dim)()

    init {
        // 自检：任何规则的档都必须逐字在既有档内；状态词必须无档。
        ALL.forEach { rule ->
            val grade = requireNotNull(rule.grade) { "落档规则的 grade 不许为空：${rule.pattern}" }
            require(grade in legalGrades(rule.dim)) { "自造档：${rule.dim.label} 的「$grade」不在既有档内" }
        }
        ALL_STATE.forEach { require(it.grade == null && it.stateOnly) { "810 状态词不许带档" } }
        require(ALL_MISS.all { it.pattern.isNotBlank() && it.note.isNotBlank() }) { "miss 词表不许空" }
    }
}
