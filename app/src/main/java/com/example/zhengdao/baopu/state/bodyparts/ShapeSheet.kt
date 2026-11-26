// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第九百三十五批 · 两表：一张「看得见什么」，一张「凭什么这么说」。
//
// 为什么要两张表：形态和评价混排成一句，就是本单要剪的那条边。分开以后，
// 形态表里只有部位与档名（写不出「所以」），来源表里只有维与出处（写不出形状），
// 「腹肌明显」与「能力强」再也不能在同一格里首尾相接。
package com.example.zhengdao.baopu.state.bodyparts

/**
 * 一个人的形态单。
 *
 * [shape] 只放档名（如「分块明显」「圆润」「偏短」），不放评价；
 * [preferences] 每条都带 who；[claims] 是有来源的断言，判据只读它们。
 */
data class ShapeProfile(
    val shape: Map<ShapeAspect, String> = emptyMap(),
    val preferences: List<Preference> = emptyList(),
    val claims: List<Claim> = emptyList(),
) {
    init {
        require(shape.values.none { it.isBlank() }) { "形态行缺档名" }
        require(preferences.map { it.who }.all { it.isNotBlank() }) { "口味缺 who" }
    }

    /** 某个角色对某个形态的口味；没有就返回 null（缺 who 的单子构造不出来）。 */
    fun preferenceOf(who: String, about: ShapeAspect): Preference? =
        preferences.firstOrNull { it.who == who && it.about == about }
}

/** 渲染器：形态表 + 来源表 + 判据。纯函数，无 IO。 */
object ShapeSheet {

    const val HEAD = "== 形态（只写看得见什么）=="
    const val SOURCE_HEAD = "== 来源（凭什么这么说）=="
    const val JUDGE_HEAD = "== 判据 =="
    const val TAIL = "-- 935 形态单结束 --"

    /**
     * 完整输出：先形态、再来源、最后判据。
     * 形态行过 [ShapeGuard.shapeLine]（不许带推导词），来源行只写维与出处。
     */
    fun render(profile: ShapeProfile): String {
        val out = StringBuilder()
        out.append(HEAD).append('\n')
        if (profile.shape.isEmpty()) {
            out.append("（未记形态）").append('\n')
        } else {
            ShapeAspect.entries.filter { profile.shape.containsKey(it) }.forEach { aspect ->
                out.append(ShapeGuard.shapeLine(aspect, profile.shape.getValue(aspect))).append('\n')
            }
        }

        out.append(SOURCE_HEAD).append('\n')
        val lines = sourceLines(profile)
        if (lines.isEmpty()) out.append("（未记来源）").append('\n') else lines.forEach { out.append(it).append('\n') }

        out.append(JUDGE_HEAD).append('\n')
        val findings = ShapeJudge.all(profile.claims)
        if (findings.isEmpty()) {
            out.append("[过] SHAPE-OK：形态、口味与来源三张单互不越界").append('\n')
        } else {
            findings.forEach { out.append(it.line()).append('\n') }
        }
        out.append(ShapeJudge.summary(findings)).append('\n')
        out.append(TAIL)
        return out.toString()
    }

    /** 来源行：每条断言的维与出处；口味单独成行，带 who。 */
    fun sourceLines(profile: ShapeProfile): List<String> {
        val out = ArrayList<String>()
        profile.claims.forEach { claim ->
            val dim = claim.judged ?: return@forEach
            val sources = claim.sources.filter { it != Source.MORPHOLOGY }.map { it.label }
            if (sources.isNotEmpty()) out += dim.label + "：" + sources.joinToString("／")
        }
        profile.preferences.forEach { out += "性吸引力（口味）：" + it.line() }
        return out
    }

    /** 形态表单独的文本（人读用，不含判据）。 */
    fun shapeTable(profile: ShapeProfile): String =
        ShapeAspect.entries.filter { profile.shape.containsKey(it) }
            .joinToString("\n") { ShapeGuard.shapeLine(it, profile.shape.getValue(it)) }
}
