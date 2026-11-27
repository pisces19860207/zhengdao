// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第九百三十九批 · 判据：纯函数，只回答两件事。
// · [purposeCheck]：为什么写（未成年 ＋ 以性为目的才红，按三要素）；
// · [textFindings]：这一行有没有写医学结论、有没有由发育推能力与品性。
//
// 刻意不判的东西（口径已定稿，别自己加禁）：年龄数值、身体变化细节多少、
// 既往史细节、档位高低。判据只看目的与两条禁令，不看「写了多少」。
package com.example.zhengdao.baopu.state.bodyparts

/** 三要素闭集：以性为目的的三个机器落点，任一命中即「以性为目的」那一侧。 */
enum class SexualElement(val code: String, val label: String) {
    SCENE("MINOR-SEXUAL-SCENE", "处在性场景／性唤起语境"),
    GENITAL_FOCUS("MINOR-GENITAL-FOCUS", "特写式聚焦性器官／性反应"),
    SEXUALIZED_GAZE("MINOR-SEXUALIZED-GAZE", "性化凝视"),
    ;

    companion object {
        const val COUNT = 3
    }
}

/** 目的判定只有两值：ALLOW／RED。注意没有「须核」——未成年＋性目的不是商量余地。 */
enum class PurposeVerdict(val label: String) {
    ALLOW("允许"),
    RED("红"),
    ;

    companion object {
        const val COUNT = 2
    }
}

/**
 * 一次目的判定的事实。
 *
 * [minor] 是唯一的前置条件；三个要素各自独立，任一为真即「以性为目的」。
 * 这里没有年龄数值字段——几岁不进来，因为「未成年」是外部给的确定事实，不是从年龄推的。
 */
data class PurposeFrame(
    val minor: Boolean,
    val sexualScene: Boolean = false,
    val genitalFocus: Boolean = false,
    val sexualizedGaze: Boolean = false,
) {
    /** 命中的要素，按闭集顺序给，便于逐条出 code。 */
    val elements: List<SexualElement>
        get() {
            val out = ArrayList<SexualElement>(SexualElement.COUNT)
            if (sexualScene) out += SexualElement.SCENE
            if (genitalFocus) out += SexualElement.GENITAL_FOCUS
            if (sexualizedGaze) out += SexualElement.SEXUALIZED_GAZE
            return out
        }

    /** 是否以性为目的（三要素任一）。 */
    val sexual: Boolean get() = elements.isNotEmpty()
}

/** purposeCheck 的返回值：判定 + 命中的要素 + 一句理由。 */
data class PurposeCheck(
    val verdict: PurposeVerdict,
    val elements: List<SexualElement>,
    val why: String,
) {
    init {
        require(verdict != PurposeVerdict.RED || elements.isNotEmpty()) { "判定红却给不出要素：矛盾" }
    }

    /** 落成结论行：ALLOW 一行；RED 每个要素一行（三要素齐就是三行）。 */
    fun findings(): List<PubertyFinding> = when (verdict) {
        PurposeVerdict.ALLOW -> listOf(PubertyFinding(Verdict.PASS, CODE_ALLOW, why))
        PurposeVerdict.RED -> elements.map { element ->
            PubertyFinding(
                Verdict.DENY, element.code,
                "未成年＋" + element.label + "：以性为目的，唯一硬红",
            )
        }
    }

    companion object {
        const val CODE_ALLOW: String = "PUBERTY-ALLOW"
    }
}

/** 判据集合。全部是纯函数：同一次输入跑两遍，结论逐字一致。 */
object PubertyJudge {

    const val CODE_DIAGNOSIS: String = "PUBERTY-DIAGNOSIS"
    const val CODE_INFER: String = "PUBERTY-INFER"

    const val ALLOW_ADULT: String = "成年不在硬红里：只看为什么写，身体、年龄与细节都允许"
    const val ALLOW_MINOR: String = "未成年但不以性为目的：允许写身体、年龄与细节"

    /**
     * 目的判定：唯一硬红＝未成年且以性为目的（三要素任一）。
     *
     * 三件事在这里被刻意排除：年龄数值（不进参数）、细节多少（不进参数）、
     * 档位高低（不进参数）。所以换一份更细的发育史，判定不会变。
     */
    fun purposeCheck(frame: PurposeFrame): PurposeCheck {
        if (!frame.minor) return PurposeCheck(PurposeVerdict.ALLOW, emptyList(), ALLOW_ADULT)
        val hits = frame.elements
        if (hits.isEmpty()) return PurposeCheck(PurposeVerdict.ALLOW, emptyList(), ALLOW_MINOR)
        val why = "未成年且以性为目的（" + hits.joinToString("、") { it.label } + "）：唯一硬红"
        return PurposeCheck(PurposeVerdict.RED, hits, why)
    }

    /** 文字臂：这一行有没有写医学结论、有没有由发育推能力与品性。两条各出各的 code。 */
    fun textFindings(text: String): List<PubertyFinding> {
        val out = ArrayList<PubertyFinding>(2)
        if (PubertyGuard.diagnosisHit(text) != null) {
            out += PubertyFinding(Verdict.DENY, CODE_DIAGNOSIS, "发育史不下医学结论：只写过程与先后，不写判定")
        }
        if (PubertyGuard.inferenceHit(text) != null) {
            out += PubertyFinding(Verdict.DENY, CODE_INFER, "由发育推能力与品性：只写变化，不写结论")
        }
        return out
    }

    /** 整张单的判据：目的（一次）＋每条细节的文字判定。同码只留一条。 */
    fun check(history: PubertyHistory, frame: PurposeFrame): List<PubertyFinding> {
        val out = ArrayList<PubertyFinding>()
        out += purposeCheck(frame).findings()
        val lines = ArrayList<String>(history.details.size + 1)
        history.ageNote?.let { lines += it }
        lines += history.details
        lines.forEach { out += textFindings(it) }
        return out.distinctBy { it.code }
    }

    /** 一行汇总：过／须核／红 各几条。 */
    fun summary(findings: List<PubertyFinding>): String {
        val pass = findings.count { it.verdict == Verdict.PASS }
        val check = findings.count { it.verdict == Verdict.NEED_CHECK }
        val deny = findings.count { it.verdict == Verdict.DENY }
        return "汇总：过 " + pass + "／须核 " + check + "／红 " + deny
    }
}
