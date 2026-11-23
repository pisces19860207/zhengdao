// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百七十五批 · 龟头体与柱身静态形态（子区层）：下游引用账（判据①）。
//
// 判据①要的不是「我声明这个 id 有用」，而是**每个 id 都能说出一个具体下游拿它干什么**，
// 至少落在 804／727／867 三者之一上。缺一条引用，那个 id 就是没人接的死词，当场红。
//
// 为什么要在代码里记这本账而不是只写在报告里：报告会过期、代码不会。867 的组合表、
// 727 的撞击落点、804 的覆盖位图都要按 code 拼字符串，一旦某个 id 悄悄没了引用，
// 门禁应当在**这里**先红，而不是等三单各自跑到一半发现查不到。
package com.example.zhengdao.baopu.state.subzone

import com.example.zhengdao.baopu.state.bodyparts.BodyPartGuard
import com.example.zhengdao.baopu.state.bodyparts.Verdict

/** 三个消费方。派工单点名的那三单，别的不进账（790 的 `SubzoneSource` 是**通道**，不是消费方）。 */
enum class Consumer(val orderId: String, val what: String) {
    P804("804", "包皮专批：覆盖位图按面记三态、面积由位图单向聚合"),
    P727("727", "撞击响应：冲击档落在哪个子区"),
    P867("867", "拟真组合表：跨子区取合法组合"),
}

/** 一条引用：谁用、用它干什么。 */
data class SubzoneRef(val consumer: Consumer, val note: String) {
    init {
        require(note.isNotBlank()) { "引用缺说明：只写单号不算引用" }
        BodyPartGuard.rejectMeasures(note)
        BodyPartGuard.rejectDiagnosis(note)
    }
}

object SubzoneRefs {

    private val P804 = Consumer.P804
    private val P727 = Consumer.P727
    private val P867 = Consumer.P867

    /**
     * 十四个 id 的引用账。每条 note 写「拿它干什么」，不写「很重要」。
     *
     * 804 的引用是按**组**对的：它的 `SubRegion.group`（GLANS／CORONA／SULCUS／FRENULUM／
     * MEATUS／SHAFT）与本表的 `zoneId` 同名对上，所以它按面记、本表按段记，两边在区一级能 join。
     */
    private val REFS: Map<SubzoneId, List<SubzoneRef>> = mapOf(
        SubzoneId.APEX to listOf(
            SubzoneRef(P804, "覆盖最先退出的那一块：龟头五面全露出之前，尖端已经露了"),
            SubzoneRef(P727, "撞击落在尖端：头型最前的一点"),
            SubzoneRef(P867, "尖端形状与包皮口缘的组合"),
        ),
        SubzoneId.GLANS_BODY to listOf(
            SubzoneRef(P804, "龟头五面（背／腹／左右侧缘）的覆盖主体压在体部这一段上"),
            SubzoneRef(P867, "饱满度与中线同冠部隆起的组合"),
        ),
        SubzoneId.GLANS_CROWN to listOf(
            SubzoneRef(P804, "CORONA 组：冠单独一组，永不与沟合并"),
            SubzoneRef(P867, "冠部隆起与沟深浅的组合"),
        ),
        SubzoneId.SULCUS to listOf(
            SubzoneRef(P804, "SULCUS 组：退至冠后判的就是沟还盖不盖着"),
            SubzoneRef(P867, "沟深浅与包皮内板的组合"),
        ),
        SubzoneId.FRENULUM to listOf(
            SubzoneRef(P804, "FRENULUM 组：系带是腹侧落点，覆盖三态单列一格"),
            SubzoneRef(P727, "撞击落在系带：腹侧那条带"),
            SubzoneRef(P867, "带长短与带紧度的组合"),
        ),
        SubzoneId.SHAFT_PROXIMAL to listOf(
            SubzoneRef(P727, "撞击分段：近端（收腰那一段）"),
            SubzoneRef(P867, "三段形走向与包皮覆盖的组合"),
        ),
        SubzoneId.SHAFT_MID to listOf(
            SubzoneRef(P804, "柱身三面（背／腹／侧缘）的覆盖判在中段上"),
            SubzoneRef(P727, "撞击分段：中段（柱身最长的一段，腹侧中线也在这段）"),
        ),
        SubzoneId.SHAFT_DISTAL to listOf(
            SubzoneRef(P727, "撞击分段：远端（向冠部起坡那一段）"),
            SubzoneRef(P867, "远端段形与冠部过渡的组合"),
        ),
        SubzoneId.RADIX to listOf(
            SubzoneRef(P727, "根部撞击：体内段，只记档不记外观"),
        ),
        SubzoneId.MEATUS_RIM to listOf(
            SubzoneRef(P867, "口缘厚薄与裂隙方向的组合"),
            SubzoneRef(P727, "撞击落在尿道口：口缘那一圈"),
        ),
        SubzoneId.MEATUS_SLIT to listOf(
            SubzoneRef(P867, "裂隙方向是组合表的一维：偏腹侧纵裂与圆孔不是同一格"),
            SubzoneRef(P804, "MEATUS 组：口的位置偏腹侧，覆盖位图单列"),
        ),
        SubzoneId.PREPUCE_INNER to listOf(
            SubzoneRef(P804, "覆盖位图的皮侧：内板贴龟头，翻开时先离"),
            SubzoneRef(P867, "内板褶与沟深浅的组合"),
        ),
        SubzoneId.PREPUCE_OUTER to listOf(
            SubzoneRef(P804, "覆盖位图的皮侧：外板朝外，与柱身同质同色"),
            SubzoneRef(P867, "外板褶与柱身段形的组合"),
        ),
        SubzoneId.PREPUCE_ORIFICE to listOf(
            SubzoneRef(P867, "口缘形状与冠部、与柱身近端的组合"),
            SubzoneRef(P804, "覆盖位图：口缘是那圈边，覆盖从多到少都绕它转"),
        ),
    )

    init {
        val missing = SubzoneId.entries.filter { REFS[it].isNullOrEmpty() }
        require(missing.isEmpty()) { missingRefsMessage(missing) }
        val unknown = REFS.keys - SubzoneId.entries.toSet()
        require(unknown.isEmpty()) { "引用账里有已改名的旧 id：${unknown.joinToString("、") { it.code }}（改名即打断下游）" }
    }

    fun of(id: SubzoneId): List<SubzoneRef> = REFS.getValue(id)

    /** 判据①的机械落点：给的账里，凡没有引用的 id 报红并点名。可被负例打中（传空账即全红）。 */
    fun missingRefs(
        ids: List<SubzoneId> = SubzoneId.entries,
        refs: Map<SubzoneId, List<SubzoneRef>> = REFS,
    ): List<SubzoneFinding> = ids.filter { refs[it].isNullOrEmpty() }.map { id ->
        SubzoneFinding(
            Verdict.DENY, "SZ-NO-CONSUMER", id,
            id.label + "（" + id.code + "）没有下游引用：804／727／867 至少要有其一，否则它是死词",
        )
    }

    /** 每个消费方各引用几个 id（报告里直接抄这张表）。 */
    fun coverageOf(refs: Map<SubzoneId, List<SubzoneRef>> = REFS): Map<Consumer, Int> {
        val out = linkedMapOf<Consumer, Int>()
        for (consumer in Consumer.entries) {
            out[consumer] = refs.values.count { list -> list.any { it.consumer == consumer } }
        }
        return out
    }

    /** 一行一个 id：code → 804/727/867。 */
    fun line(): String = SubzoneId.entries.joinToString("\n") { id ->
        val who = of(id).map { it.consumer.orderId }.distinct().joinToString("/")
        id.code + "→" + who
    }

    private fun missingRefsMessage(missing: List<SubzoneId>): String =
        "引用账漏了子区（判据①缺则红）：" + missing.joinToString("、") { it.label }
}
