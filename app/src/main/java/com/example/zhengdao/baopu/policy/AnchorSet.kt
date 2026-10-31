// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百一十批 · 锚定样本（Anchor Set）：**新判据的标准答案**。
//
// 一句话口径：LLM 裁判从「神谕」降为「顾问」之后，它说的话要有**人对过的答案**可对。
// 锚定集就是那批答案——从历史账里挑人工确认过「对／不对」的样本，每条三样：
// 场景一句话、旧判据结论、人工结论（＋证据出处）。新判据必须在**每一条**上都与人工结论
// 同侧，才许进御案；任一条不一致 ⇒ 退回候选区，并把不一致清单一起带回去。
//
// 为什么门槛是 100% 而不是「足够高」：一致率是**比值**，比值天然邀请「98% 也算过」这种
// 讨价还价；而锚定集的用途是判裁判有没有漂，一条对不上就已经说明它会错。
// 所以这里**只数不一致的条数**（整数），不产百分比、不产分数。
//
// 本文件零 IO：JSONL 的读与「只增不删」的账比对由 CI 侧的
// `tools/check-criteria-gates.py` 做（policy 包由 122 批的 `PolicySourceGateTest`
// 逐文件钉死零 Android／零 IO）。这里只放**结构**与**算式**。
//
// 签字：蓝总管初筛、用户签字（抽样核 10 条，判错 ≥2 条整批打回）。
// 未生效的锚定集**不参与放行**——见 [status]：没有签名 ⇒ 任何候选都进不了御案（fail closed）。
package com.example.zhengdao.baopu.policy

/** 一条锚定样本。全部字段都是给人读的，没有任何数值评分。 */
internal data class AnchorCase(
    val id: String,
    /** 场景一句话。 */
    val scene: String,
    /** 旧判据当时的结论（原样记录，用来看出草案是不是在往同一个方向漂）。 */
    val oldVerdict: String,
    /** 人工结论：只认 [VERDICT_PASS]／[VERDICT_FAIL] 两个词打头。 */
    val humanVerdict: String,
    /** 证据出处：账行 id 或 `文件:行`。没有出处的行不算锚定样本。 */
    val evidence: String,
    /** 初筛人（蓝总管）。空白 ⇒ 这条还没被人看过。 */
    val screenedBy: String,
    /** 签字人（用户）。空白 ⇒ 这条还没生效。 */
    val signedBy: String,
    /** 签字时间（原样字符串，本文件不读时钟）。 */
    val signedAt: String,
) {
    /** 这条样本是不是**已生效**：初筛＋签字＋证据三样齐。 */
    fun inForce(): Boolean =
        screenedBy.isNotBlank() && signedBy.isNotBlank() && signedAt.isNotBlank() && evidence.isNotBlank()

    /** 结论落在哪一侧；对不上两个关键词就是「无法判定」，**不许当通过**。 */
    fun side(): String = sideOf(humanVerdict)
}

internal const val VERDICT_PASS = "通过"
internal const val VERDICT_FAIL = "不通过"

/** 判据侧同理：只认这两个词打头，其余（包括分数、包括「基本通过」）都是无法判定。 */
private fun sideOf(verdict: String): String {
    val t = verdict.trim()
    return when {
        t.startsWith(VERDICT_FAIL) -> VERDICT_FAIL
        t.startsWith(VERDICT_PASS) -> VERDICT_PASS
        else -> VERDICT_UNKNOWN
    }
}

internal const val VERDICT_UNKNOWN = "无法判定"

/** 锚定集整体处于什么状态。 */
internal enum class AnchorStatus {
    /** 空集：没有任何样本，准入无从谈起。 */
    EMPTY,

    /** 有行但没签齐：不参与放行，只出诊断。 */
    UNSIGNED,

    /** 每一条都初筛＋签字到位：可以拿它当标准答案。 */
    IN_FORCE,
}

/** 一条对不上的锚定样本（退回候选区时就是这张清单）。 */
internal data class AnchorMismatch(
    val anchorId: String,
    val scene: String,
    val humanSide: String,
    val candidateSide: String,
    val evidence: String,
)

/** 锚定集：一组样本 ＋ 三条算式（生效判定、一致比对、只增不删）。 */
internal object AnchorSet {

    /** 整体状态：空／未签齐／生效。未生效是**默认**，不是巧合。 */
    fun status(cases: List<AnchorCase>): AnchorStatus = when {
        cases.isEmpty() -> AnchorStatus.EMPTY
        cases.all { it.inForce() } -> AnchorStatus.IN_FORCE
        else -> AnchorStatus.UNSIGNED
    }

    /**
     * 一致比对：新判据对每条锚定样本的结论（`锚 id → 结论`）与人工结论逐条对。
     *
     * 两处从严：
     * - 草案**没覆盖到**某条样本 ⇒ 记一条不一致（无法判定≠通过，311 批同口径）；
     * - 结论不是那两个词打头 ⇒ 也算不一致。
     *
     * 返回的是**清单**（要退回候选区给人看），不是比率。
     */
    fun mismatches(cases: List<AnchorCase>, candidateVerdicts: Map<String, String>): List<AnchorMismatch> =
        cases.map { case ->
            val raw = candidateVerdicts[case.id]
            val got = if (raw == null) VERDICT_UNKNOWN else sideOf(raw)
            val want = case.side()
            if (got == want) {
                null
            } else {
                AnchorMismatch(
                    anchorId = case.id,
                    scene = case.scene,
                    humanSide = want,
                    candidateSide = got,
                    evidence = case.evidence,
                )
            }
        }.filterNotNull()

    /** 生效样本的条数（纪元账只记整数）。 */
    fun inForceCount(cases: List<AnchorCase>): Int = cases.count { it.inForce() }

    /**
     * 只增不删校验：锚定集是标准答案，改一条＝改标准＝立法。
     *
     * @return 违规描述；没有违规返回 null。
     */
    fun appendOnlyViolation(prev: List<AnchorCase>, next: List<AnchorCase>): String? {
        val prevById = prev.associateBy { it.id }
        val nextById = next.associateBy { it.id }
        val removed = prevById.keys - nextById.keys
        if (removed.isNotEmpty()) {
            return "锚定集被删行（只增不删）：${removed.sorted().joinToString(",")}"
        }
        val changed = prevById.keys.sorted().filter { id ->
            val before = prevById.getValue(id)
            val after = nextById.getValue(id)
            differsBeforeSignature(before, after) && !reSigned(before, after)
        }
        if (changed.isNotEmpty()) {
            return "锚定集旧行被改且没有新签字（改锚＝改标准，须用户点头）：" +
                changed.joinToString(",")
        }
        return null
    }

    /**
     * 只认「签字字段变了」＝补签动作：`signedBy`／`signedAt` 任一处变化才算有人重新授权。
     * 改正文却把旧签名原样留着（最常见的静默改法）⇒ 不算补签，见 [appendOnlyViolation]。
     */
    private fun reSigned(before: AnchorCase, after: AnchorCase): Boolean =
        before.signedBy != after.signedBy || before.signedAt != after.signedAt

    /**
     * 比「签字之前的内容」：签字之后的 `signedBy/signedAt` 变化不算改锚（那是补签动作），
     * 而场景／旧结论／人工结论／证据／初筛人任一处变了，就是改了标准答案本身。
     */
    private fun differsBeforeSignature(before: AnchorCase, after: AnchorCase): Boolean =
        before.scene != after.scene ||
            before.oldVerdict != after.oldVerdict ||
            before.humanVerdict != after.humanVerdict ||
            before.evidence != after.evidence ||
            before.screenedBy != after.screenedBy
}
