// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百九十批 · 形轨.方位可达：对外唯一入口（判据 741 先例：本包只此一处碰几何面）。
// 三轴齐才判：区（+子区缝）× 方位六档（相对该区）× 距离四档；缺一即 incomplete（判据②）。
// 四项判定消费 655 现成件（链长闭式界＝Reachability、自碰撞/穿地穿沙＝GJK/EPA 原语按
// PoseValidity 同一协议），不另造判据；手臂链与摆姿段是本单的工程抽象（○）。
package com.example.zhengdao.baopu.state.bearing

import com.example.zhengdao.baopu.state.form.BodyState
import com.example.zhengdao.baopu.state.geometry.Environment
import com.example.zhengdao.baopu.state.geometry.Reachability
import com.example.zhengdao.baopu.state.geometry.SegmentProxy
import com.example.zhengdao.baopu.state.geometry.ViolationKind
import kotlin.math.ceil

/** 方位可达判定入口。纯函数：同输入同结论，逐字节一致（判据⑦）。 */
object BearingReach {

    /** 距离档 REACH_SPAN：655 链长三角界闭式卡死（与 655 四项并列的第五个 blockedBy 取值）。 */
    const val REACH_SPAN: String = "REACH_SPAN"

    private val SIDES = listOf("l", "r")

    /** 阻断类目的固定优先序（判据③：取值与 655 四项一致，越限即红）。 */
    private val KIND_ORDER = listOf(
        ViolationKind.LIMIT_EXCEEDED.name,
        ViolationKind.SELF_COLLISION.name,
        ViolationKind.GROUND_PENETRATION.name,
        ViolationKind.SAND_PENETRATION.name,
    )

    fun judge(
        body: BodyState,
        env: Environment = Environment(0.0, 0.0),
        zoneId: String,
        subzone: String? = null,
        bearing: Bearing6? = null,
        distance: DistanceTier? = null,
    ): BearingOutcome {
        val missing = missingAxes(body, zoneId, subzone, bearing, distance)
        if (missing.isNotEmpty()) {
            return BearingOutcome.Incomplete(BearingIncomplete(missing, givenSummary(zoneId, subzone, bearing, distance)))
        }
        val b = requireNotNull(bearing)
        val tier = requireNotNull(distance)
        val candidates = instancesOf(body, zoneId).flatMap { inst ->
            SIDES.map { side -> candidateOf(body, env, zoneId, inst, side, b, tier) }
        }
        return BearingOutcome.Judged(verdictOf(zoneId, subzone, b, tier, pick(candidates)))
    }

    // ── 三轴与实例解析 ─────────────────────────────────────────────────────

    /**
     * 缺轴清单，顺序固定：目标区→子区→方位→距离（判据②⑤：缺一即 incomplete，不许默认补全）。
     *
     * 子区这一轴只在**不在册**时算缺（887 把口径写清，免得按旧用例名读成「请求子区就 incomplete」）：
     * 在册的子区随结论回显（`subzoneRequested=true`），几何仍按**区代理体**判，`granularity` 记 ZONE
     * 并在 `granularityReason` 里如实写「子区级几何不在本单」；不在册的子区当场 incomplete，不猜近义词。
     * 清单唯一来源＝[SubzoneSource]（转发 `subzone/SubzoneId`，775 已落地，887 认账不回退）。
     */
    private fun missingAxes(body: BodyState, zoneId: String, subzone: String?, bearing: Bearing6?, distance: DistanceTier?): List<String> {
        val missing = ArrayList<String>()
        if (zoneId.isBlank() || instancesOf(body, zoneId).isEmpty()) {
            missing.add("目标区（zoneId 空缺或不在 660 十三区实例表）")
        }
        if (subzone != null && subzone !in SubzoneSource.of(zoneId)) {
            missing.add("子区（『$subzone』不在子区枚举唯一来源 SubzoneSource：${SubzoneSource.SOURCE}）")
        }
        if (bearing == null) missing.add("方位（前/后/左/右/上/下六档未给）")
        if (distance == null) missing.add("距离（贴体/近/中/远未给）")
        return missing
    }

    private fun givenSummary(zoneId: String, subzone: String?, bearing: Bearing6?, distance: DistanceTier?): String =
        "zoneId=$zoneId, subzone=${subzone ?: "未给"}, bearing=${bearing?.name ?: "未给"}, distance=${distance?.name ?: "未给"}"

    /** 区实例（660 代理体是唯一清单来源）；按名排序保确定性。 */
    private fun instancesOf(body: BodyState, zoneId: String): List<SegmentProxy> =
        body.proxies.genital.filter { it.name.startsWith("$zoneId.") }.sortedBy { it.name }

    /** 静置体：全代理体去掉目标实例、同区兄弟实例（整手代理半径分不开同区两侧）与同侧臂三段。 */
    private fun staticSet(body: BodyState, side: String, zoneId: String, targetInstance: String): List<SegmentProxy> {
        val own = setOf("shoulder_$side", "elbow_$side", "wrist_$side")
        return body.proxies.all.filter {
            it.name != targetInstance && it.name !in own && it.name.substringBefore('.') != zoneId
        }
    }

    /** 其余区实例（目标实例与同区兄弟除外）：手对它们一律走「目标点不深陷才豁免」的守卫。 */
    private fun otherInstances(body: BodyState, zoneId: String): List<String> =
        body.proxies.genital.filter { it.name.substringBefore('.') != zoneId }.map { it.name }

    // ── 单候选（实例×侧）判定 ──────────────────────────────────────────────

    private class Cand(
        val instance: String,
        val side: String,
        val attempt: ArmAttempt,
        val state: BearingReachState,
        val blockedBy: List<String>,
        val bandCm: Double,
    ) {
        val cost: Double get() = if (attempt.hasEvidence) attempt.qualifyingUtil else attempt.bestUtil
    }

    private fun candidateOf(
        body: BodyState,
        env: Environment,
        zoneId: String,
        instance: SegmentProxy,
        side: String,
        bearing: Bearing6,
        tier: DistanceTier,
    ): Cand {
        val chain = BearingGeometry.armChain(side, body)
        val spec = BearingGeometry.TIERS.getValue(tier)
        val target = BearingGeometry.targetPoint(instance.shape, bearing, tier)
        val reach = Reachability.ofChain(chain, (target - chain.rootPosCm).norm(), spec.bandCm, 1)
        val spanBlocked = reach.state == Reachability.State.UNREACHABLE
        val attempt = BearingSampler.evaluate(
            chain, staticSet(body, side, zoneId, instance.name),
            BearingGeometry.permissions(side, instance.name, otherInstances(body, zoneId)),
            env, target, spec.bandCm, side, body, spanBlocked,
        )
        return Cand(instance.name, side, attempt, stateOf(attempt, spec.bandCm), blockedByOf(attempt, spanBlocked, spec.bandCm), spec.bandCm)
    }

    /** 三态机（判据①③）：有证据按利用率分档；无证据时最近构型被 655 四项卡住或需越限 ⇒ unreachable。 */
    private fun stateOf(attempt: ArmAttempt, bandCm: Double): BearingReachState = when {
        attempt.spanBlocked -> BearingReachState.UNREACHABLE
        attempt.hasEvidence -> if (attempt.qualifyingUtil >= BearingGeometry.AWKWARD_UTIL) BearingReachState.AWKWARD else BearingReachState.REACHABLE
        attempt.bestViolations.isNotEmpty() -> BearingReachState.UNREACHABLE
        attempt.bestUtil >= BearingGeometry.LIMIT_UTIL && attempt.bestErrorCm > bandCm -> BearingReachState.UNREACHABLE
        else -> BearingReachState.AWKWARD
    }

    /** blockedBy：跨距闭式⇒REACH_SPAN；否则取最近构型的违规类（固定序），顶限⇒LIMIT_EXCEEDED（越限即红）。 */
    private fun blockedByOf(attempt: ArmAttempt, spanBlocked: Boolean, bandCm: Double): List<String> = when {
        spanBlocked -> listOf(REACH_SPAN)
        attempt.hasEvidence -> emptyList()
        attempt.bestViolations.isNotEmpty() -> KIND_ORDER.filter { k -> attempt.bestViolations.any { it.kind.name == k } }
        attempt.bestUtil >= BearingGeometry.LIMIT_UTIL && attempt.bestErrorCm > bandCm ->
            listOf(ViolationKind.LIMIT_EXCEEDED.name)
        else -> emptyList()
    }

    /** 候选取优：态高者优先，同态取利用率低者，再按实例名/侧序（sortedWith 稳定 ⇒ 逐字节确定）。 */
    private fun pick(candidates: List<Cand>): Cand = candidates.sortedWith(
        compareBy({ -stateRank(it.state) }, { it.cost }, { it.instance }, { it.side }),
    ).first()

    private fun stateRank(state: BearingReachState): Int = when (state) {
        BearingReachState.REACHABLE -> 2
        BearingReachState.AWKWARD -> 1
        BearingReachState.UNREACHABLE -> 0
    }

    // ── 七项结论装配 ───────────────────────────────────────────────────────

    private fun verdictOf(zoneId: String, subzone: String?, bearing: Bearing6, tier: DistanceTier, c: Cand): BearingVerdict = BearingVerdict(
        zoneId = zoneId,
        zoneInstance = c.instance,
        subzoneRequested = subzone != null,
        bearing = bearing,
        distance = tier,
        state = c.state,
        jointTiers = tiersOf(c),
        path = pathOf(c),
        granularity = BearingGranularity.ZONE,
        granularityReason = granularityReason(subzone),
        baseline = BearingBaseline.FIXTURE,
        baselineReason = "660 默认站姿代理体（肘腕陷骨盆属夹具）；真形体归 699，接入后重跑本判定并切 real",
        notThisOrder = NotThisOrder.entries.toList(),
        usedArm = c.side,
        samplesTried = c.attempt.samplesTried,
        bestGapMm = gapMm(c),
        evidenceNote = evidenceOf(c),
    )

    private fun granularityReason(subzone: String?): String = if (subzone == null) {
        "未给子区：判到区级（判据④标原因；子区枚举唯一来源 ${SubzoneSource.SOURCE}）"
    } else {
        "子区「$subzone」已在册并随结论回显，但几何仍按区代理体判：子区级几何不在本单（判据④如实标）"
    }

    /** 关节档：肩=两自由度最大利用率，肘/腕各取自身；髋膝=1（fixture 站姿不参与，站位归 725/741）。 */
    private fun tiersOf(c: Cand): JointTiers {
        val utils = if (c.attempt.hasEvidence && c.attempt.qualifyingUtils.size >= 4) {
            c.attempt.qualifyingUtils
        } else {
            c.attempt.bestUtils
        }
        if (utils.size < 4) return JointTiers(1, 1, 1, 1, 1)
        return JointTiers(
            shoulder = tierOf(maxOf(utils[0], utils[1])),
            elbow = tierOf(utils[2]),
            wrist = tierOf(utils[3]),
            hip = 1,
            knee = 1,
        )
    }

    private fun tierOf(util: Double): Int = ceil(util * 5.0).toInt().coerceIn(1, 5)

    private fun pathOf(c: Cand): BearingPath = when {
        c.state == BearingReachState.UNREACHABLE -> BearingPath(false, c.blockedBy, "路径不可行：卡在 ${c.blockedBy.joinToString()}")
        c.attempt.hasEvidence && c.state == BearingReachState.AWKWARD ->
            BearingPath(true, emptyList(), "路径存在但需扭转（关节利用率顶近别扭阈值，见 jointTiers）")
        c.state == BearingReachState.AWKWARD ->
            BearingPath(false, emptyList(), "路径可行性证据不足：采样＋细化未找到带宽内干净构型，最近构型也被四项放行（见证据注）")
        else -> BearingPath(true, emptyList(), "路径可行")
    }

    private fun gapMm(c: Cand): Int = if (c.attempt.hasEvidence) {
        0
    } else {
        Math.round(maxOf(0.0, c.attempt.bestErrorCm - c.bandCm) * 10.0).toInt()
    }

    private fun evidenceOf(c: Cand): String = when {
        c.attempt.spanBlocked -> "655 链长三角界闭式：目标距离超出可达带（REACH_SPAN）"
        c.attempt.hasEvidence -> fmt(
            "采样${c.attempt.samplesTried}次：合格构型利用率 %.3f（别扭阈值 %.2f）；髋膝=1 为 fixture 站姿（站位归 725/741）",
            c.attempt.qualifyingUtil, BearingGeometry.AWKWARD_UTIL,
        )
        c.attempt.bestViolations.isNotEmpty() -> fmt(
            "采样${c.attempt.samplesTried}次：最近构型距 %.1fcm 被卡——%s", c.attempt.bestErrorCm,
            c.attempt.bestViolations.joinToString("；") { "${it.segment}×${it.other} ${it.kind.name} ${it.amountMilli}mm" },
        )
        c.attempt.bestUtil >= BearingGeometry.LIMIT_UTIL -> fmt(
            "采样${c.attempt.samplesTried}次：最近构型距 %.1fcm 且利用率 %.3f 顶限——需越限才够到（越限即红）",
            c.attempt.bestErrorCm, c.attempt.bestUtil,
        )
        else -> fmt(
            "采样${c.attempt.samplesTried}次＋细化：最近构型距 %.1fcm 且四项干净——证据不足以判 unreachable（655 采样语义），按本单三态口径记 awkward",
            c.attempt.bestErrorCm,
        )
    }

    /** 定点数格式化（Locale.ROOT：小数点恒为点，跨机器逐字节一致）。 */
    private fun fmt(pattern: String, vararg args: Any?): String = String.format(java.util.Locale.ROOT, pattern, *args)
}
