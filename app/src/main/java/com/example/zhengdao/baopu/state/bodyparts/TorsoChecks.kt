// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百四十八批 · 判据：纯函数，只回答「同场这几个人分得开不开」「形状与档案对不对得上」。
// 不下生理结论、不进闸、不落库、不装配（源码门禁钉）。
//
// 三条判据：
// ② 差异下限：同场躯干可见者 ≥2 人时，任意两人形状须 ≥2 维不同；<2 维 ⇒ 红；
// ③ 只有体积差：形状八维全同、只有体积不同 ⇒ 提示（这是同一副躯干的胖瘦，不是两个人）；
// ④ 档案矛盾：形状与档案倾向相反 —— ≥2 维相反 ⇒ 红，1 维相反 ⇒ 提示（个体差异能解释一维，
//    解释不了两维）。
//
// 每条结论都带来源与日期；why 里不出长度／百分比、不出诊断词（BodyPartGuard 当场拦）。
package com.example.zhengdao.baopu.state.bodyparts

/** 三态判定。[severity] 越大越严——取最严时用它，不依赖枚举声明次序。 */
enum class TorsoVerdict(val label: String, val severity: Int) {
    PASS("通过", 0),
    HINT("提示", 1),
    DENY("红", 2),
    ;

    companion object {
        fun worst(a: TorsoVerdict, b: TorsoVerdict): TorsoVerdict =
            if (a.severity >= b.severity) a else b
    }
}

/**
 * 一条结论。
 *
 * @param why 人话理由。里面会带角色标识，所以标识本身也得干净（不带单位／百分号）
 */
data class TorsoFinding(
    val verdict: TorsoVerdict,
    val code: String,
    val why: String,
    val source: String = TORSO_SOURCE,
    val asOf: String = TORSO_AS_OF,
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

/** 判据集合。全部纯函数：同一批人跑两遍，结论逐字一致。 */
object TorsoChecks {

    /** 差异下限：两人形状至少差这么多维才算「分得开」。 */
    const val MIN_SHAPE_DIFF: Int = 2

    /** 档案矛盾判红的门槛：≥2 维相反才算档案写错。 */
    const val PROFILE_DENY_AT: Int = 2

    /** 差异下限启用的最少人数（躯干可见者）。 */
    private const val MIN_PERSONS: Int = 2

    /**
     * 判据②③：两个人的形状比对。
     * 前置条件：两人躯干都必须可见（裸／半裸）。不可见的人进这个函数直接拒——
     * 「先判可见再比形状」这件事不靠调用方自觉（穿衣可见度归 687，本包不重算）。
     */
    fun pair(a: TorsoPerson, b: TorsoPerson): TorsoFinding {
        require(a.torsoVisible && b.torsoVisible) { INVISIBLE_REJECT }
        val diff = TorsoCatalog.diff(a.shape, b.shape)
        val who = a.roleId + " 与 " + b.roleId
        return when {
            diff.size >= MIN_SHAPE_DIFF -> TorsoFinding(
                TorsoVerdict.PASS, "SHAPE-OK",
                "同场 " + who + "：形状差 " + diff.size + " 维（" + describe(a, b, diff) + "）",
            )
            diff.size == 1 -> TorsoFinding(
                TorsoVerdict.DENY, "SHAPE-THIN",
                "同场 " + who + "：形状只差 1 维（" + describe(a, b, diff) + "），未达下限 " +
                    MIN_SHAPE_DIFF + " 维",
            )
            a.volume.differs(b.volume) -> TorsoFinding(
                TorsoVerdict.HINT, "SHAPE-VOLUME-ONLY",
                "同场 " + who + "：形状八维全同，只差体积（" + a.volume.diffWords(b.volume) +
                    "）——这是同一副躯干的胖瘦，换名字还是同一副躯干",
            )
            else -> TorsoFinding(
                TorsoVerdict.DENY, "SHAPE-IDENTICAL",
                "同场 " + who + "：形状八维全同、体积也同档：两个人写成了同一副躯干",
            )
        }
    }

    /**
     * 判据②的场景汇总：只看躯干可见的人，任意两人都要过 [pair]；取最严的一条。
     * 可见者不足 2 人 ⇒ 通过（下限不启用），并在理由里说清是几人。
     */
    fun scene(persons: List<TorsoPerson>): TorsoFinding {
        val shown = persons.filter { it.torsoVisible }
        if (shown.size < MIN_PERSONS) {
            return TorsoFinding(
                TorsoVerdict.PASS, "SCENE-TOO-FEW",
                "同场躯干可见者 " + shown.size + " 人，不足 " + MIN_PERSONS + " 人：差异下限不启用",
            )
        }
        return pairs(shown).map { (a, b) -> pair(a, b) }
            .reduce { acc, it -> if (it.verdict.severity > acc.verdict.severity) it else acc }
    }

    /** 判据④：形状与档案倾向。0 维相反 ⇒ 过；1 维 ⇒ 提示；≥2 维 ⇒ 红。 */
    fun profileVerdict(person: TorsoPerson): TorsoFinding {
        val hits = TorsoTendencies.of(person.profile)
            .filter { it.violatedBy(person.shape.of(it.dim)) }
        val head = person.roleId + "（档案 " + person.profile.label + "）"
        return when {
            hits.isEmpty() -> TorsoFinding(
                TorsoVerdict.PASS, "PROFILE-OK",
                head + "：形状与档案倾向对得上，" + TorsoTendencies.of(person.profile).size +
                    " 维全部落在允许档内",
            )
            hits.size < PROFILE_DENY_AT -> TorsoFinding(
                TorsoVerdict.HINT, "PROFILE-DEVIATE",
                head + "：1 维与倾向相反（" + describeTendency(person, hits) +
                    "）——一维可按个体差异解释，不必改档，但要能说出依据",
            )
            else -> TorsoFinding(
                TorsoVerdict.DENY, "PROFILE-CONFLICT",
                head + "：" + hits.size + " 维与倾向相反（" + describeTendency(person, hits) +
                    "）——这不是个体差异，是档案或形状写错了",
            )
        }
    }

    /**
     * 一张场子的全部结论：先场景差异（判据②③），再逐人档案（判据④）。
     * 顺序＝入参顺序，逐字可回放。
     */
    fun all(persons: List<TorsoPerson>): List<TorsoFinding> =
        listOf(scene(persons)) + persons.map { profileVerdict(it) }

    /** 取最严的一条：红压提示、提示压通过。 */
    fun worst(findings: List<TorsoFinding>): TorsoVerdict =
        findings.fold(TorsoVerdict.PASS) { acc, f -> TorsoVerdict.worst(acc, f.verdict) }

    /** 一行汇总：通过／提示／红 各几条。 */
    fun summary(findings: List<TorsoFinding>): String {
        val pass = findings.count { it.verdict == TorsoVerdict.PASS }
        val hint = findings.count { it.verdict == TorsoVerdict.HINT }
        val deny = findings.count { it.verdict == TorsoVerdict.DENY }
        return "通过 " + pass + "／提示 " + hint + "／红 " + deny
    }

    /** 一场戏的唯一出口：汇总一行＋逐条结论。出门前同样过闸。 */
    fun sceneLine(persons: List<TorsoPerson>): String {
        val findings = all(persons)
        val text = summary(findings) + "\n" + findings.joinToString("\n") { it.line() }
        BodyPartGuard.rejectMeasures(text)
        BodyPartGuard.rejectDiagnosis(text)
        return text
    }

    const val INVISIBLE_REJECT: String =
        "躯干不可见者不进形状差异判据：先由可见度判定给出裸／半裸，再比形状"

    private fun describe(a: TorsoPerson, b: TorsoPerson, dims: List<TorsoDimension>): String =
        dims.joinToString("；") { TorsoCatalog.describeDiff(a.shape, b.shape, it) }

    private fun describeTendency(person: TorsoPerson, hits: List<Tendency>): String =
        hits.joinToString("；") { t ->
            t.dim.label + "=" + person.shape.of(t.dim).label + "，应为 " + t.allowedWords()
        }

    /** 全部两人组合，顺序固定（i<j，按入参序）。 */
    private fun pairs(shown: List<TorsoPerson>): List<Pair<TorsoPerson, TorsoPerson>> =
        shown.indices.flatMap { i -> (i + 1 until shown.size).map { j -> shown[i] to shown[j] } }
}
