// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百七十五批 · 龟头体与柱身静态形态（子区层）：静态形态表（每子区三项齐：质地／色档／形态档）。
//
// 这张表回答的是「常态下这块地方长什么样、摸起来怎样、颜色比柱身深还是浅」，**不是**某个角色的读数：
// 角色偏离常态时走 [SubzoneReading] 覆盖，档域仍是这三张闭集。
//
// 判据②的机器落点在本文件：表按 14 个 id 逐项登记，缺一区、同一区登记两次、某区形态档为空
// 或超过三轴，构造时就抛（不是等到判定才发现少了东西）。
//
// 禁数值（判据④）：note 一栏是给人看的一句形态话，构造即过 805 的三道文字闸
// （不出长度／面积／百分比、不出绝对色号、不出判定词）。
package com.example.zhengdao.baopu.state.subzone

import com.example.zhengdao.baopu.state.bodyparts.BodyPartGuard
import com.example.zhengdao.baopu.state.bodyparts.Verdict

/**
 * 一行的静态形态。
 *
 * @param texture 质地（光滑／一般／粗糙）
 * @param color 颜色相对档（与柱身比：浅／同／深一档／深两档）
 * @param forms 形态档 1–3 条轴；同一行不许重复登记同一轴（一个轴两个答案＝两个真相）
 * @param note 一句形态说明（禁数值、禁判定词，构造即验）
 */
data class SubzoneRow(
    val id: SubzoneId,
    val texture: Texture,
    val color: ColorShift,
    val forms: List<FormValue>,
    val note: String,
) {
    init {
        require(forms.isNotEmpty()) { "${id.label}：形态档至少一条轴（判据②三项齐）" }
        require(forms.size <= MAX_FORMS) { "${id.label}：形态档最多 $MAX_FORMS 条轴，收到 ${forms.size}" }
        require(forms.map { it.axis }.toSet().size == forms.size) { "${id.label}：同一形态轴登记了两次" }
        require(note.isNotBlank()) { "${id.label}：缺一句形态说明" }
        BodyPartGuard.rejectMeasures(note)
        BodyPartGuard.rejectAbsoluteColor(note)
        BodyPartGuard.rejectDiagnosis(note)
    }

    fun formOf(axis: FormAxis): FormValue? = forms.firstOrNull { it.axis == axis }

    /** 一行的机读快照：code｜质地｜色档｜轴=档,…（顺序＝表内声明序，两跑逐字一致）。 */
    fun line(): String = buildString {
        append(id.code).append('|').append(texture.label).append('|').append(color.label)
        append('|').append(forms.joinToString(",") { it.line() })
    }

    companion object {
        const val MAX_FORMS: Int = 3
    }
}

/**
 * 静态形态表：14 行，一行一个子区。
 *
 * 默认值的出身写死在 [DEFAULTS_SOURCE]：**常态（疲软、静置）**下的形态常识，不是某个角色的样子，
 * 也不是医学描述。三处有明确依据的，单测现读事实底盘核（系带腹侧、尿道口偏腹侧纵裂、龟头更光滑更深）；
 * 其余（如内板比柱身浅）标「形态常识」，允许角色覆盖，但档域不许离开闭集。
 */
object SubzoneTable {

    const val DEFAULTS_SOURCE: String = "常态形态常识（疲软、静置口径）；三条硬常识另核 669 事实底盘"

    private val GLANS_SMOOTH_DEEP: Pair<Texture, ColorShift> = Texture.SMOOTH to ColorShift.DEEPER_1
    private val SHAFT_PLAIN_SAME: Pair<Texture, ColorShift> = Texture.PLAIN to ColorShift.SAME

    val rows: List<SubzoneRow> = listOf(
        row(
            SubzoneId.APEX, GLANS_SMOOTH_DEEP,
            "尖端收在最前，圆钝收口，不收成尖角",
            FormValue(FormAxis.TIP, FormAxis.TIP.tierOf("圆钝")),
        ),
        row(
            SubzoneId.GLANS_BODY, GLANS_SMOOTH_DEEP,
            "体部是龟头最饱满的一段，正中一道浅中线",
            FormValue(FormAxis.PLUMP, FormAxis.PLUMP.tierOf("满")),
            FormValue(FormAxis.MIDLINE, FormAxis.MIDLINE.tierOf("浅")),
        ),
        row(
            SubzoneId.GLANS_CROWN, GLANS_SMOOTH_DEEP,
            "冠部比体部更宽更凸，与体部之间分得清",
            FormValue(FormAxis.TRANSITION, FormAxis.TRANSITION.tierOf("分明")),
            FormValue(FormAxis.EDGE, FormAxis.EDGE.tierOf("中")),
        ),
        row(
            SubzoneId.SULCUS, GLANS_SMOOTH_DEEP,
            "整圈的环沟，沟底比两侧暗一点",
            FormValue(FormAxis.GROOVE, FormAxis.GROOVE.tierOf("中")),
            FormValue(FormAxis.RING, FormAxis.RING.tierOf("整环")),
        ),
        row(
            SubzoneId.FRENULUM, GLANS_SMOOTH_DEEP,
            "腹侧一条细带，把包皮与沟连在一起",
            FormValue(FormAxis.BAND, FormAxis.BAND.tierOf("中")),
            FormValue(FormAxis.TIGHT, FormAxis.TIGHT.tierOf("中")),
        ),
        row(
            SubzoneId.SHAFT_PROXIMAL, SHAFT_PLAIN_SAME,
            "近端收腰，接龟头那一段最细",
            FormValue(FormAxis.TAPER, FormAxis.TAPER.tierOf("收")),
        ),
        row(
            SubzoneId.SHAFT_MID, SHAFT_PLAIN_SAME,
            "中段平直，腹侧正中一道浅缝",
            FormValue(FormAxis.MIDLINE, FormAxis.MIDLINE.tierOf("浅")),
        ),
        row(
            SubzoneId.SHAFT_DISTAL, SHAFT_PLAIN_SAME,
            "远端略胀，向冠部起坡",
            FormValue(FormAxis.TAPER, FormAxis.TAPER.tierOf("胀")),
        ),
        row(
            SubzoneId.RADIX, SHAFT_PLAIN_SAME,
            "根部埋在体表之下，外面看不到",
            FormValue(FormAxis.VISIBLE, FormAxis.VISIBLE.tierOf("不可见")),
        ),
        row(
            SubzoneId.MEATUS_RIM, GLANS_SMOOTH_DEEP,
            "口缘薄，一圈贴着中间的裂",
            FormValue(FormAxis.RIM, FormAxis.RIM.tierOf("薄")),
        ),
        row(
            SubzoneId.MEATUS_SLIT, GLANS_SMOOTH_DEEP,
            "裂沿纵向走，略偏腹侧",
            FormValue(FormAxis.SLIT, FormAxis.SLIT.tierOf("纵裂偏腹侧")),
        ),
        row(
            SubzoneId.PREPUCE_INNER, Texture.SMOOTH to ColorShift.LIGHTER,
            "内板贴着龟头，比柱身浅一档",
            FormValue(FormAxis.FOLD, FormAxis.FOLD.tierOf("细褶")),
        ),
        row(
            SubzoneId.PREPUCE_OUTER, SHAFT_PLAIN_SAME,
            "外板朝外，与柱身同一路质地与颜色",
            FormValue(FormAxis.FOLD, FormAxis.FOLD.tierOf("细褶")),
        ),
        row(
            SubzoneId.PREPUCE_ORIFICE, SHAFT_PLAIN_SAME,
            "口缘一圈圆整，厚薄居中",
            FormValue(FormAxis.RIM, FormAxis.RIM.tierOf("中")),
        ),
    )

    private val byId: Map<SubzoneId, SubzoneRow> = rows.associateBy { it.id }

    init {
        require(byId.size == rows.size) { "静态形态表同一子区登记了两次" }
        require(byId.size == SubzoneId.entries.size) { missingRows(byId) }
        val emptyGroups = SubzoneGroup.entries.filter { g -> rows.none { it.id.group == g } }
        require(emptyGroups.isEmpty()) { "静态形态表有组没落到任何子区：" + emptyGroups.joinToString("、") { it.label } }
        val denied = SubzoneRules.hardCommonSense(rows).filter { it.verdict == Verdict.DENY }
        require(denied.isEmpty()) { "静态形态表违反硬常识：" + denied.joinToString("；") { it.why } }
    }

    fun row(id: SubzoneId): SubzoneRow = byId.getValue(id)

    fun rowsOfGroup(group: SubzoneGroup): List<SubzoneRow> = rows.filter { it.id.group == group }

    /** 宿主区 → 子区行。13 区里本单元没登记的（睾丸／阴囊／精索／耻骨区／阴茎缝）返回空表。 */
    fun rowsOfZone(zoneId: String): List<SubzoneRow> = rows.filter { it.id.zoneId == zoneId }

    /** 整表快照，一行一个子区（顺序＝表内声明序）。 */
    fun line(): String = rows.joinToString("\n") { it.line() }

    private fun row(id: SubzoneId, tone: Pair<Texture, ColorShift>, note: String, vararg forms: FormValue) =
        SubzoneRow(id, tone.first, tone.second, forms.toList(), note)

    private fun missingRows(byId: Map<SubzoneId, SubzoneRow>): String {
        val missing = SubzoneId.entries.filterNot { byId.containsKey(it) }
        return "静态形态表漏了子区（判据②缺则红）：" + missing.joinToString("、") { it.label }
    }
}
