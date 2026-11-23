// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百七十五批 · 龟头体与柱身静态形态（子区层）：判据（纯函数，零 IO、零随机、零时钟）。
//
// 三条硬常识（判据③，误写即红）：
// ① 系带在腹侧——不是正中背面，也不是背侧；
// ② 尿道口是**偏腹侧纵裂**——不是正中圆孔；
// ③ 龟头更光滑、颜色深一档——比柱身，不是「一样」也不是「更粗糙更浅」。
//
// 每条都由一个纯函数答，单测拿正例与**反例**各打一遍：反例不红就等于闸没牙。
// 结论一律三种（沿用 805 的 Verdict：过／须核／红），不另立第四种词。
package com.example.zhengdao.baopu.state.subzone

import com.example.zhengdao.baopu.state.bodyparts.BodyPartGuard
import com.example.zhengdao.baopu.state.bodyparts.Verdict

/**
 * 一条子区结论。[id] 为 null ＝ 结论不指向某个子区（整表级）。
 * 构造即守：有代码、有理由，理由里没有长度／面积／百分比、没有绝对色号、没有判定词。
 */
data class SubzoneFinding(
    val verdict: Verdict,
    val code: String,
    val id: SubzoneId?,
    val why: String,
) {
    init {
        require(code.isNotBlank()) { "子区结论缺代码" }
        require(why.isNotBlank()) { "子区结论缺理由" }
        BodyPartGuard.rejectMeasures(why)
        BodyPartGuard.rejectAbsoluteColor(why)
        BodyPartGuard.rejectDiagnosis(why)
    }

    fun line(): String = "[" + verdict.label + "] " + code + "：" + why
}

object SubzoneRules {

    /** 龟头与柱身的质地最少差一档（「更光滑」的机器口径）。 */
    const val MIN_TEXTURE_STEP: Int = Texture.STEP

    /** 龟头的色档至少要深一档（「颜色深一档」的机器口径）。 */
    const val MIN_COLOR_STEP: Int = 1

    // ── 判据②：每区三项齐 ────────────────────────────────────────────────

    /**
     * 判据②：给定的 id 清单里，凡没有对应行的就报红，点名缺哪个。
     *
     * 生产侧 [SubzoneTable] 构造时已要求 14 行齐全，所以这条函数在正常路径上恒空——
     * 它存在的意义是**能被负例打中**：传一份少一行的清单，必须点名那一行。
     */
    fun completeness(ids: List<SubzoneId>, rows: List<SubzoneRow>): List<SubzoneFinding> {
        val present = rows.map { it.id }.toSet()
        return ids.filterNot { present.contains(it) }.map { id ->
            SubzoneFinding(
                Verdict.DENY, "SZ-ROW-MISSING", id,
                id.label + "没有静态形态行：质地／色档／形态三项都不在册（判据②缺则红）",
            )
        }
    }

    // ── 硬常识①：系带在腹侧 ──────────────────────────────────────────────

    /**
     * 系带必须记在腹侧。
     *
     * 这条读的是 [SubzoneId.side]（结构属性），不是某条文字——所以它在**源码层**改一格就红，
     * 而不是靠「有人在注释里写了腹侧」糊过去。传别的子区进来会红，正好当闸的负例控制项。
     */
    fun frenulumSideVerdict(id: SubzoneId): Verdict =
        if (id.side == SubzoneSide.VENTRAL) Verdict.PASS else Verdict.DENY

    fun frenulumOnVentral(ids: List<SubzoneId> = SubzoneId.entries): List<SubzoneFinding> =
        ids.filter { it.group == SubzoneGroup.FRENULUM }.mapNotNull { id ->
            val verdict = frenulumSideVerdict(id)
            if (verdict == Verdict.PASS) null else SubzoneFinding(
                verdict, "SZ-FRENULUM-SIDE", id,
                "系带记在" + id.side.label + "：系带在腹侧（不在正中背面，也不在背侧）",
            )
        }

    // ── 硬常识②：尿道口是偏腹侧纵裂 ─────────────────────────────────────

    /**
     * 裂隙方向单档判：0＝纵裂偏腹侧（过）；1＝纵裂但居中（须核，方向不符）；
     * 2＝圆孔（红，这是误写）。
     */
    fun slitVerdict(tier: Int): Verdict = when (tier) {
        SLIT_VENTRAL -> Verdict.PASS
        SLIT_CENTER -> Verdict.NEED_CHECK
        else -> Verdict.DENY
    }

    fun meatusSlit(rows: List<SubzoneRow>): List<SubzoneFinding> {
        val row = rows.firstOrNull { it.id == SubzoneId.MEATUS_SLIT }
            ?: return listOf(
                SubzoneFinding(
                    Verdict.DENY, "SZ-MEATUS-SLIT", SubzoneId.MEATUS_SLIT,
                    "尿道口缺裂隙方向这一子区：方向没有落点，硬常识就判不了",
                ),
            )
        val form = row.formOf(FormAxis.SLIT)
            ?: return listOf(
                SubzoneFinding(
                    Verdict.DENY, "SZ-MEATUS-SLIT", SubzoneId.MEATUS_SLIT,
                    "裂隙方向这一行没登记走向档：方向空着＝没判",
                ),
            )
        val verdict = slitVerdict(form.tier)
        if (verdict == Verdict.PASS) return emptyList()
        return listOf(
            SubzoneFinding(
                verdict, "SZ-MEATUS-SLIT", SubzoneId.MEATUS_SLIT,
                "裂隙方向记成「" + form.label + "」：尿道口是偏腹侧纵裂，不是正中圆孔",
            ),
        )
    }

    // ── 硬常识③：龟头更光滑、颜色深一档 ─────────────────────────────────

    /** 三条硬常识的总入口；[SubzoneTable] 构造时跑一遍（违反即不让表立起来）。 */
    fun hardCommonSense(rows: List<SubzoneRow>): List<SubzoneFinding> =
        frenulumOnVentral() + meatusSlit(rows) + textureOrder(rows) + colorOrder(rows) + shaftBaseline(rows)

    /**
     * 龟头每一行都要比柱身**最光滑的那一行**还光滑至少一档。
     *
     * 拿柱身「最光滑的一行」当参照，是最宽松的比法：连最宽松的比法都过不了，就是真比柱身糙。
     * 反过来，只要把龟头任意一行改成「一般」，这一行当场红。
     */
    fun textureOrder(rows: List<SubzoneRow>): List<SubzoneFinding> {
        val glans = rows.filter { it.id.group == SubzoneGroup.GLANS }
        val shaft = rows.filter { it.id.group == SubzoneGroup.SHAFT }
        if (glans.isEmpty() || shaft.isEmpty()) {
            return listOf(SubzoneFinding(Verdict.DENY, "SZ-TEXTURE-SHAPE", null, "缺龟头行或缺柱身行：没法比质地"))
        }
        val shaftBest = shaft.maxOf { it.texture.rank }
        return glans.mapNotNull { row ->
            val step = row.texture.rank - shaftBest
            if (step >= MIN_TEXTURE_STEP) return@mapNotNull null
            SubzoneFinding(
                Verdict.DENY, "SZ-GLANS-TEXTURE", row.id,
                "龟头记成「" + row.texture.label + "」，柱身最光滑的一行是「" +
                    Texture.ofRank(shaftBest).label + "」：龟头比柱身更光滑，至少差一档",
            )
        }
    }

    /**
     * 龟头的色档要比柱身深至少一档；深两档的放行但标「须核」（那是角色个性档，不是误写）。
     */
    fun colorOrder(rows: List<SubzoneRow>): List<SubzoneFinding> {
        val glans = rows.filter { it.id.group == SubzoneGroup.GLANS }
        val shaft = rows.filter { it.id.group == SubzoneGroup.SHAFT }
        if (glans.isEmpty() || shaft.isEmpty()) {
            return listOf(SubzoneFinding(Verdict.DENY, "SZ-COLOR-SHAPE", null, "缺龟头行或缺柱身行：没法比色档"))
        }
        val base = shaft.maxOf { it.color.step }
        val out = mutableListOf<SubzoneFinding>()
        for (row in glans) {
            val step = row.color.step - base
            when {
                step <= 0 -> out += SubzoneFinding(
                    Verdict.DENY, "SZ-GLANS-COLOR", row.id,
                    "龟头记成「" + row.color.label + "」：龟头比柱身深一档，记「同」或「浅」都是误写",
                )
                step > MIN_COLOR_STEP -> out += SubzoneFinding(
                    Verdict.NEED_CHECK, "SZ-GLANS-COLOR-PLUS", row.id,
                    "龟头记成「" + row.color.label + "」：比常见的一档更深，属角色个性档，须核不改结论",
                )
            }
        }
        return out
    }

    /**
     * 柱身是色档的参照物，自己必须是「同」。
     *
     * 柱身若记成「深一档」，那「相对柱身」就变成相对自己，一圈套一圈——所以这一档当场红，
     * 不是「也算数」。
     */
    fun shaftBaseline(rows: List<SubzoneRow>): List<SubzoneFinding> {
        val shaft = rows.filter { it.id.group == SubzoneGroup.SHAFT }
        if (shaft.isEmpty()) {
            return listOf(SubzoneFinding(Verdict.DENY, "SZ-SHAFT-BASELINE", null, "缺柱身行：色档没有参照物"))
        }
        return shaft.filter { it.color != ColorShift.BASELINE }.map { row ->
            SubzoneFinding(
                Verdict.DENY, "SZ-SHAFT-BASELINE", row.id,
                "柱身记成「" + row.color.label + "」：柱身是色档的参照物，只能记「同」",
            )
        }
    }

    /** 硬常识②的档序常量：与 [FormAxis.SLIT] 的档名表一一对应，改档名表就要改这里（单测钉住）。 */
    const val SLIT_VENTRAL: Int = 0
    const val SLIT_CENTER: Int = 1
    const val SLIT_ROUND: Int = 2
}
