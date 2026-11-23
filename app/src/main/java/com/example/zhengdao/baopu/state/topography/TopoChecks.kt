// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百六十六批 · 判据：纯函数，只回答「这张地形自洽吗」。
// 不下生理结论、不进闸、不落库、不装配（源码门禁钉）。
//
// 五条判据：
// ① 四件齐——凸／凹／厚各 5 维齐＋左右 11 对（结构上由 [Topography] 保证，这里出账）；
// ② 与体脂联动——脂多 ⇒ 凹陷变浅、骨性隆起被覆盖；**胖而「锁骨上窝深」⇒ 红**；
// ③ 写成完全对称 ⇒ 提示（人几乎没有完全对称）；
// ④ 左右差 >1 档 ⇒ 须给原因（惯用侧／旧伤／训练），不给即红；
// ⑤ 厚度与形状正交——厚度换遍三档，凸凹结论逐字不变（厚度≠形状，形状归 848）。
//
// 三条口径写在这里，免得每单重吵：
// · 体脂档复用 805 的 `FatBand`（0–3）。815（体型分布）尚未落地，本包**不新造体脂档**；
//   815 落地后由装配层把它的体脂档映射到 `FatBand`，本包一个字不改。
// · 判据② 只管**骨性隆起**会不会被盖住；腹直肌沟是沟（脂多反而更深）、斜方肌坡是坡，
//   两者不算「被覆盖」——把它们也拦进来，就是拿一条粗规则去否掉两个真事实。
// · 判据⑤ 不引用 848 的类型：厚度不该看一眼形状。探针只证明「改厚度，别的结论不动」。
package com.example.zhengdao.baopu.state.topography

import com.example.zhengdao.baopu.state.bodyparts.BodyPartGuard
import com.example.zhengdao.baopu.state.bodyparts.FatBand

/** 三态判定。[severity] 越大越严——取最严时用它，不依赖枚举声明次序。 */
enum class TopoVerdict(val label: String, val severity: Int) {
    PASS("通过", 0),
    HINT("提示", 1),
    DENY("红", 2),
    ;

    companion object {
        fun worst(a: TopoVerdict, b: TopoVerdict): TopoVerdict =
            if (a.severity >= b.severity) a else b
    }
}

/**
 * 一条结论。
 *
 * @param why 人话理由。里面带角色标识与档名，所以两者本身也得干净（不带单位／百分号）
 */
data class TopoFinding(
    val verdict: TopoVerdict,
    val code: String,
    val why: String,
    val source: String = TOPO_SOURCE,
    val asOf: String = TOPO_AS_OF,
) {
    init {
        require(code.isNotBlank()) { "结论缺代码：无来源" }
        require(why.isNotBlank()) { "结论缺理由：无来源" }
        BodyPartGuard.rejectMeasures(why)
        BodyPartGuard.rejectDiagnosis(why)
    }

    /** 一行结论：判定＋代码＋理由（来源 日期）。 */
    fun line(): String =
        "[" + verdict.label + "] " + code + "：" + why + "（" + source + " " + asOf + "）"
}

/**
 * 同场一个人：地形＋体脂档。
 *
 * @param fat 体脂档（805 `FatBand`；815 落地后由装配层映射，本包不换算）
 */
data class TopoPerson(
    val roleId: String,
    val fat: FatBand,
    val topo: Topography,
) {
    init {
        require(roleId.isNotBlank()) { "角色缺标识：结论点名不了" }
    }

    fun line(): String = roleId + "（体脂 " + fat.label + "）｜" + topo.line()
}

/** 判据集合。全部纯函数：同一张单跑两遍，结论逐字一致。 */
object TopoChecks {

    /** 左右差在几档以内算常态。超过它就必须给原因。 */
    const val GAP_COMMON_MAX: Int = 1

    /** 判据② 的红线：脂多时「深」这一档是矛盾的（锁骨上窝尤其）。 */
    const val FAT_HOLLOW_RED_LEVEL: Int = 3

    /** 判据② 的软线：脂多写到「中」只能提示，不判红。 */
    const val FAT_HOLLOW_SOFT_LEVEL: Int = 2

    /** 判据② 的覆盖红线：脂多时骨性隆起写到「清楚」及以上即矛盾。 */
    const val FAT_RELIEF_RED_LEVEL: Int = 2

    /** 判据① 四件齐：出账，不出判（结构上造不出来不齐的单子）。 */
    fun completeness(p: TopoPerson): TopoFinding = TopoFinding(
        TopoVerdict.PASS, "TOPO-COMPLETE",
        p.roleId + "：四件齐——凸 " + TopoCatalog.RELIEF_DIMS.size +
            "／凹 " + TopoCatalog.HOLLOW_DIMS.size +
            "／厚 " + TopoCatalog.DEPTH_DIMS.size +
            " 维齐全，左右 " + p.topo.lateral().size + " 对，中轴 " + p.topo.midline().size + " 维单记",
    )

    /** 判据② 前半：脂多 ⇒ 凹陷变浅。「胖而锁骨上窝深」就在这条里判红。 */
    fun fatHollow(p: TopoPerson): TopoFinding {
        if (!p.fat.high) {
            return TopoFinding(
                TopoVerdict.PASS, "FAT-HOLLOW-NA",
                p.roleId + "：体脂 " + p.fat.label + "，不启用「脂多则凹陷变浅」",
            )
        }
        val hollows = p.topo.all().filterIsInstance<HollowReading>()
        val deep = hollows.filter { deeper(it) >= FAT_HOLLOW_RED_LEVEL }
        val soft = hollows.filter { deeper(it) >= FAT_HOLLOW_SOFT_LEVEL }
        return when {
            deep.isNotEmpty() -> TopoFinding(
                TopoVerdict.DENY, "FAT-HOLLOW",
                p.roleId + "：体脂 " + p.fat.label + "，却写着 " + words(deep) +
                    "——脂多先把窝填浅，锁骨上窝尤其填得早",
            )
            soft.isNotEmpty() -> TopoFinding(
                TopoVerdict.HINT, "FAT-HOLLOW-MID",
                p.roleId + "：体脂 " + p.fat.label + "，" + words(soft) +
                    " 写到「中」：不是矛盾，但要能说出哪一侧的窝还看得见",
            )
            else -> TopoFinding(
                TopoVerdict.PASS, "FAT-HOLLOW-OK",
                p.roleId + "：体脂 " + p.fat.label + "，五处凹陷都在浅及以下，与脂量对得上",
            )
        }
    }

    /** 判据② 后半：脂多 ⇒ 骨性隆起被覆盖。腹直肌沟与斜方肌坡不在管辖区（见文件头）。 */
    fun fatRelief(p: TopoPerson): TopoFinding {
        if (!p.fat.high) {
            return TopoFinding(
                TopoVerdict.PASS, "FAT-RELIEF-NA",
                p.roleId + "：体脂 " + p.fat.label + "，不启用「脂多则隆起被覆盖」",
            )
        }
        val shown = TopoCatalog.bonyDims().map { p.topo.relief(it) }
            .filter { deeper(it) >= FAT_RELIEF_RED_LEVEL }
        return if (shown.isEmpty()) {
            TopoFinding(
                TopoVerdict.PASS, "FAT-RELIEF-OK",
                p.roleId + "：体脂 " + p.fat.label + "，三处骨性隆起都在隐约及以下：对得上",
            )
        } else {
            TopoFinding(
                TopoVerdict.DENY, "FAT-RELIEF",
                p.roleId + "：体脂 " + p.fat.label + "，骨性隆起却写着 " + words(shown) +
                    "——脂层压在上面，这几个点先被盖住",
            )
        }
    }

    /** 判据③：左右 11 对全同档 ⇒ 提示。人几乎没有完全对称。 */
    fun symmetry(p: TopoPerson): TopoFinding {
        val gaps = p.topo.lateral().map { it.gap }
        return if (gaps.all { it == 0 }) {
            TopoFinding(
                TopoVerdict.HINT, "ASYM-ALL-EVEN",
                p.roleId + "：左右 " + gaps.size + " 对全部同档——人几乎没有完全对称，" +
                    "至少惯用侧那边该有半档到一档的差",
            )
        } else {
            TopoFinding(
                TopoVerdict.PASS, "ASYM-VARIED",
                p.roleId + "：左右有差的 " + gaps.count { it > 0 } + " 对，最大差 " +
                    p.topo.maxGap() + " 档：像人",
            )
        }
    }

    /** 判据④：左右差 >1 档须给原因，不给即红。 */
    fun asymCause(p: TopoPerson): TopoFinding {
        val beyond = p.topo.lateral().filter { it.gap > GAP_COMMON_MAX }
        val bare = beyond.filter { it.cause == null }
        return when {
            bare.isNotEmpty() -> TopoFinding(
                TopoVerdict.DENY, "ASYM-NO-CAUSE",
                p.roleId + "：" + words(bare) + " 差 " + bare.maxOf { it.gap } +
                    " 档却没有原因——超出常态（≤" + GAP_COMMON_MAX + " 档）必须写" +
                    AsymCause.entries.joinToString("／") { it.label } + " 之一",
            )
            beyond.isNotEmpty() -> TopoFinding(
                TopoVerdict.PASS, "ASYM-EXPLAINED",
                p.roleId + "：" + words(beyond) + " 有差，已记原因：" + causes(beyond) + "——可留",
            )
            else -> TopoFinding(
                TopoVerdict.PASS, "ASYM-WITHIN",
                p.roleId + "：左右最大差 " + p.topo.maxGap() + " 档，在常态（≤" +
                    GAP_COMMON_MAX + " 档）内",
            )
        }
    }

    /**
     * 判据⑤：厚度与形状正交。把厚度整体换成薄／中／厚各一遍，
     * 凸起与凹陷的读数（含左右差与原因）必须逐字不变。变了就是「拿厚度当形状判」。
     */
    fun depthOrthogonal(p: TopoPerson): TopoFinding {
        val base = surfaceLine(p.topo)
        val leak = DepthGrade.entries.filter { grade -> surfaceLine(uniformDepth(p.topo, grade)) != base }
        return if (leak.isEmpty()) {
            TopoFinding(
                TopoVerdict.PASS, "DEPTH-ORTHOGONAL",
                p.roleId + "：厚度换遍 " + DepthGrade.COUNT +
                    " 档（全薄／全中／全厚），凸凹结论逐字不变：厚度≠形状，形状归 848",
            )
        } else {
            TopoFinding(
                TopoVerdict.DENY, "DEPTH-LEAK",
                p.roleId + "：厚度换成 " + leak.joinToString("／") { it.label } +
                    " 之后凸凹结论跟着变了——把厚度当形状判了",
            )
        }
    }

    /** 判据 ①–⑤ 全跑，顺序固定，逐字可回放。 */
    fun all(p: TopoPerson): List<TopoFinding> = listOf(
        completeness(p),
        fatHollow(p),
        fatRelief(p),
        symmetry(p),
        asymCause(p),
        depthOrthogonal(p),
    )

    /** 取最严的一条：红压提示、提示压通过。 */
    fun worst(findings: List<TopoFinding>): TopoVerdict =
        findings.fold(TopoVerdict.PASS) { acc, f -> TopoVerdict.worst(acc, f.verdict) }

    /** 一行汇总：通过／提示／红 各几条。 */
    fun summary(findings: List<TopoFinding>): String {
        val pass = findings.count { it.verdict == TopoVerdict.PASS }
        val hint = findings.count { it.verdict == TopoVerdict.HINT }
        val deny = findings.count { it.verdict == TopoVerdict.DENY }
        return "通过 " + pass + "／提示 " + hint + "／红 " + deny
    }

    /** 一张单的唯一出口：汇总一行＋逐条结论。出门前同样过闸。 */
    fun sceneLine(p: TopoPerson): String {
        val findings = all(p)
        val text = summary(findings) + "\n" + findings.joinToString("\n") { it.line() }
        BodyPartGuard.rejectMeasures(text)
        BodyPartGuard.rejectDiagnosis(text)
        return text
    }

    /** 读数较显的一侧档序（左右取大）。判据② 按「显的那一侧」判，免得一深一浅被平均掉。 */
    private fun deeper(r: TopoReading): Int = maxOf(r.left.level, r.right.level)

    /** 点名读数：分左右写两侧，中轴写一笔。 */
    private fun words(items: List<TopoReading>): String =
        items.joinToString("；") { it.shapeLine() }

    private fun causes(items: List<TopoReading>): String =
        items.joinToString("；") { it.dim.label + "=" + (it.cause?.label ?: "未记") }

    /** 凸＋凹的指纹（含左右差与原因）。判据⑤ 拿它比「换厚度前后」。 */
    private fun surfaceLine(t: Topography): String =
        t.all().filterNot { it.dim.family == TopoFamily.DEPTH }.joinToString("；") { r ->
            r.shapeLine() + "|差" + r.gap + "|" + (r.cause?.label ?: "无")
        }

    /** 把厚度整体换成一档（凸凹原样）。只为判据⑤ 的探针服务，不是装配入口。 */
    private fun uniformDepth(t: Topography, grade: DepthGrade): Topography =
        Topography(t.all().map { r -> if (r is DepthReading) DepthReading(r.dim, grade, grade) else r })
}
