// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百二十三批 · 法典卷十 **L32 追责与收紧**——「同类第二次，权限必须收紧」由生产判定说死。
//
// 出处：第三百二十三批派工单（L29–L33 五条真缺口门禁；判据原文＝法典卷十 L32，
// 私有侧《抱朴-法典-2026-10-27》；不在本库内，别按库内路径找）。
//
// ## 判据（输入→输出，逐条可执行）
//   ①**认定要有效**：复盘认定（「本不该判灾异」或「处置超白名单」）必须由
//     **双盲复核任一方提出**（[Finding.raisedByBlindReview]）＋**御批确认**
//     （[Finding.confirmedByThrone]）——两样缺一 ⇒ 认定无效，**不许据此动权限**
//     （程序不许自定罪：谁都能「认定」的话，收紧就成了绕过授权的后门）；
//   ②**第一次放行**：同类第一次有效认定 ⇒ 只**记违规**，权限不动（[Standing.OPEN]）——
//     人都有失手，第一次的账要记、门不下死；
//   ③**第二次收紧**：窗口内同类第二次有效认定 ⇒ 该类别权限**收紧两级**，
//     回「必须先上奏」（[Standing.PETITION_FIRST]）并再记违规；
//   ④**收紧后再犯＝永久**：同类别第三次有效认定 ⇒ **永久需上奏**
//     （[Standing.PETITION_FOREVER]），**重开须御批**（[Verdict.reopenRequiresImperialApproval]）；
//   ⑤**窗口归零**：窗口（[WINDOW_MS]，30 天）外的同类旧账不参与计数——归零的是计数不是记录
//     （与 L24 [ProgressivePenalty] 同一把尺；账本身永久留档，那是 208 台账的事）。
//
// ## 为什么第一次只记账不动权限
// 收紧的代价由全类别承担：一次失手就锁死，下一次真灾异来时该类别已经没有自主权可用。
// 第一次记违规（可回放、可累计），把「收紧」留给「同类再犯」——这正是派工单点名的
// 「第一次放行、第二次收紧，可测」。
//
// ## 为什么只有判定
// 双盲复核的产出与御批的确认还没有生产调用方（L31 双盲复核未接线）；本层零 IO、零时钟，
// 认定事实（kind/category/时刻/两枚举布尔）全由调用方投影，本层只回「该类别此后怎么办」。
package com.example.zhengdao.baopu.law

/** 复盘认定的两类（法典 L32 原文点名的两条；[wire] 是账上稳定键）。 */
internal enum class AccountabilityKind(val wire: String, val cn: String) {
    /** 复盘认定「本不该判灾异」。 */
    MISJUDGED_ANOMALY("misjudged_anomaly", "本不该判灾异"),

    /** 复盘认定「处置超白名单」。 */
    OVER_WHITELIST("over_whitelist", "处置超白名单"),
    ;
}

/** 一条复盘认定（事实由调用方投影：谁提出的、御批确认没有、什么时候）。 */
internal data class AccountabilityFinding(
    val kind: AccountabilityKind,
    /** 出事的是哪个类别（收紧只落在这一类上，不许一竿子打翻所有类别）。 */
    val category: String,
    /** 认定时刻（毫秒）。 */
    val atMs: Long,
    /** 双盲复核任一方提出（不良人／金吾卫都算；程序自己不算）。 */
    val raisedByBlindReview: Boolean,
    /** 御批确认（没人批的认定不许动权限）。 */
    val confirmedByThrone: Boolean,
)

/** 该类别收紧后的常设档（收紧两级＝[PETITION_FIRST]；永久＝[PETITION_FOREVER]）。 */
internal enum class AccountabilityStanding(val wire: String, val cn: String) {
    /** 照旧：第一次有效认定只记账，权限不动（放行）。 */
    OPEN("open", "照旧"),

    /** 必须先上奏：同类第二次，该类别权限收紧两级。 */
    PETITION_FIRST("petition_first", "必须先上奏"),

    /** 永久需上奏：收紧后同类再犯；重开须御批。 */
    PETITION_FOREVER("petition_forever", "永久需上奏"),
    ;
}

/** L32 的一次判定结论（该类别此后怎么办，全在这里，执行层不用自己再判）。 */
internal data class AccountabilityVerdict(
    val lawRef: String,
    val category: String,
    /** 认定有没有效（双盲提出＋御批确认，缺一即无效）。 */
    val findingValid: Boolean,
    /** 窗口内同类有效认定的此前次数（不含本次）。 */
    val priorCount: Int,
    /** 该类别的常设档（本次认定生效后的位置）。 */
    val standing: AccountabilityStanding,
    /** 本次有没有动权限（收紧）。 */
    val tightened: Boolean,
    /** 记违规（有效认定 ⇒ 恒 true；无效认定 ⇒ false，账都不许记）。 */
    val violationRecorded: Boolean,
    /** 永久档才有：重开须御批（执行层不许自行恢复）。 */
    val reopenRequiresImperialApproval: Boolean,
    val reason: String,
    val howToLift: String,
)

/** L32 追责收紧的唯一判据实现（生产）。 */
internal object AccountabilityGate {

    const val LAW_REF = "L32"

    /**
     * 「同类」的计数窗口：30 天（与 L24 [ProgressivePenalty.WINDOW_MS] 同一把尺；
     * 复盘账本身 30 天可回放——窗口外旧账不参与计数，归零的是计数不是记录）。
     */
    const val WINDOW_MS = 30L * 24 * 60 * 60 * 1000

    /**
     * 判一次：这条认定落下来，该类别的权限走到哪一档。
     *
     * @param finding 本次认定（[AccountabilityFinding.raisedByBlindReview] 与
     *   [AccountabilityFinding.confirmedByThrone] 决定它有没有效）
     * @param priorSameCategory 同类别历史认定（可以混别的类别、混窗口外的，本层自己筛；
     *   只数**有效**的——无效认定不许替下一次攒次数，与 L24「疑罪从无不计数」同尺）
     * @param nowMs 现在（由调用方给，本层不读时钟）
     * @param windowMs 窗口，默认 [WINDOW_MS]
     */
    fun decide(
        finding: AccountabilityFinding,
        priorSameCategory: List<AccountabilityFinding>,
        nowMs: Long,
        windowMs: Long = WINDOW_MS,
    ): AccountabilityVerdict {
        require(finding.category.isNotBlank()) { "L32 的 category 不许为空：收紧落在类别上，没类别就落不下去" }
        require(finding.atMs <= nowMs) { "L32 的认定时刻不许晚于现在：未来的账不参与判定" }
        val valid = finding.raisedByBlindReview && finding.confirmedByThrone
        if (!valid) {
            return AccountabilityVerdict(
                lawRef = LAW_REF,
                category = finding.category,
                findingValid = false,
                priorCount = 0,
                standing = AccountabilityStanding.OPEN,
                tightened = false,
                violationRecorded = false,
                reopenRequiresImperialApproval = false,
                reason = "认定无效：须双盲复核任一方提出＋御批确认，两样缺一不许据此动权限（程序不许自定罪）",
                howToLift = "走完双盲复核再奏：认定有效的那一刻才按累进定档",
            )
        }
        val prior = priorSameCategory.count {
            it.kind == finding.kind && it.category == finding.category &&
                it.raisedByBlindReview && it.confirmedByThrone &&
                it.atMs <= nowMs && nowMs - it.atMs <= windowMs
        }
        val (standing, tightened, reason) = ladderOf(n = prior + 1, kindCn = finding.kind.cn)
        return AccountabilityVerdict(
            lawRef = LAW_REF,
            category = finding.category,
            findingValid = true,
            priorCount = prior,
            standing = standing,
            tightened = tightened,
            violationRecorded = true,
            reopenRequiresImperialApproval = standing == AccountabilityStanding.PETITION_FOREVER,
            reason = reason,
            howToLift = liftOf(standing),
        )
    }

    /**
     * 双盲复核的出场口（339）：把一条复核不一致（DISAGREEMENT）按 L32 认定口径收进来——
     * **提出位自动为真**（影子就是双盲任一方，调用方不许替它填 false，也不许替它装御批）；
     * **御批确认位留给人**（[AccountabilityFinding.confirmedByThrone]＝false）——
     * 复核说了不算：收紧哪个类别的权限是认定后果，按法典只有御批能定。
     * 御批到场那天，把这一条的 confirmedByThrone 置真再走 [decide]，累进照算。
     */
    fun blindReviewFinding(
        kind: AccountabilityKind,
        category: String,
        atMs: Long,
    ): AccountabilityFinding = AccountabilityFinding(
        kind = kind,
        category = category,
        atMs = atMs,
        raisedByBlindReview = true,
        confirmedByThrone = false,
    )

    /** L32 的累进梯子：同类有效认定第 n 次 ⇒ 该类别走到哪一档（判据②③④的本体）。 */
    private fun ladderOf(
        n: Int,
        kindCn: String,
    ): Triple<AccountabilityStanding, Boolean, String> = when {
        n == 1 -> Triple(
            AccountabilityStanding.OPEN,
            false,
            "同类第 1 次（$kindCn）：记违规，权限照旧——第一次放行，门不下死",
        )
        n == 2 -> Triple(
            AccountabilityStanding.PETITION_FIRST,
            true,
            "同类第 2 次（$kindCn）：该类别权限收紧两级，回「必须先上奏」",
        )
        else -> Triple(
            AccountabilityStanding.PETITION_FOREVER,
            true,
            "同类第 $n 次（$kindCn）：收紧后仍再犯，该类别永久需上奏",
        )
    }

    /** 「怎么解」一句（法典硬要求：收紧时必须连解除方式一起给，不许只说「不行」）。 */
    private fun liftOf(standing: AccountabilityStanding): String = when (standing) {
        AccountabilityStanding.OPEN -> "无需解除：这次只记账；同类再犯即收紧"
        AccountabilityStanding.PETITION_FIRST -> "该类别先上奏：走御批通道仍可用；30 天窗口归零后不因旧账继续累进"
        AccountabilityStanding.PETITION_FOREVER -> "重开须御批：御案上明批恢复该类别，本层不给自行恢复的口子"
    }
}
