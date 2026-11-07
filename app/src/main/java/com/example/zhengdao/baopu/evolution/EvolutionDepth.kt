// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百零五批 · 追加口径：**自进化深度裁定**（`设计-自进化深度裁定-2026-11-06`）。
//
// 用户那句话的意思是——进化到哪一步，不由模型自己说，也不由单笔改动的大小说，
// 由**两相合起来定**：左相看这件事值不值得打扰（成本侧），右看这件事有没有证据（证据侧）。
// 本文件把这句话做成一个可执行的合成规则＋一本能回放的账，而不是一句口号。
//
// ## 三档（[DepthTier]）与它的红线
//   浅（默认）＝只记不改：写账、写经验，不动提示词、不动技能、不动记忆组织；
//   中＝改组织形态：合并／去重／指针化／重排——**不改语义、不新增能力**；
//   深＝改结构：技能步骤、提示词结构、注入策略——**必须过灰度**。
//   制度类（A 内核、B/C 前三条、法典判据）**不在任何档内**：它们出现在候选里就直接拒
//   （[InstitutionalGuard]，判据⑥）。
//
// ## 合成规则（写死、可测；顺序不许倒）
//   ① 默认浅；
//   ② 证据 PASS ＋ 预算有余 ＋ 画像置信高 ⇒ 中；**三条同时**满足才升（缺一不升，判据③）；
//   ③ ②成立 ＋ 同类连续 N 次成功 ＋ 灰度可用 ＋ 可回滚已验证 ⇒ 深；
//   ④ 任一 FAIL／UNVERIFIABLE 增多／用户明确降档／误拦率上升 ⇒ **立刻降一档**（不等周期，判据④）。
//
// ## 模型的口子有多大（判据⑤）
// 模型可以写一条参谋意见（建议通过／不通过＋理由＋风险点），落账 [EvolutionAdvice]；
// 它**不可以**：给自己的产出发通过（同源自评）、覆盖右相三态、决定深度档。
// 这三条不是道德劝告，是形状：`depth` 与 `verdict` 都不是 [EvolutionAdvice] 的字段，
// 而 [EvolutionAdvice.selfIssued] 一旦为真，[AdvisorGate.accept] 直接拒收并记账——
// 「模型给自己的产出发通过」这件事在代码里没有可用的通道。
//
// ## 今天到不了的那一格
// 深档要求"灰度可用"。473 灰度在仓库里**还不存在**（本批实测：无 canary/灰度模块），
// 所以 [CanaryStatus] 只有 `UNAVAILABLE` 是真实的，深档在生产里必然判不出来。
// 这不是遗漏，是纪律：**没有灰度就不许有深档**，等 473 交付了，那一路的读数才有出处。
package com.example.zhengdao.baopu.evolution

import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.core.RunLog
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** 自进化的三个深度档（闭集；[wire] 是账上稳定键）。 */
internal enum class DepthTier(val wire: String, val cn: String, val oneLine: String) {
    /** 只记不改（默认档：新装／清数据后的第一次自进化就在这一档）。 */
    SHALLOW("shallow", "浅", "只记不改：写账与经验，不动提示词、技能与记忆组织"),

    /** 改组织形态（合/去/指/排），不改语义、不新增能力。 */
    MEDIUM("medium", "中", "改组织形态：合并、去重、指针化、重排（不改语义）"),

    /** 改结构——必须过灰度；灰度不可用时这一档判不出来。 */
    DEEP("deep", "深", "改结构：技能步骤、提示词结构、注入策略（必须过灰度）"),
    ;

    companion object {
        fun ofWire(wire: String): DepthTier? = entries.firstOrNull { it.wire == wire }
    }
}

/** 灰度（473）可用性——本批只有 [UNAVAILABLE] 是真的，见文件头「今天到不了的那一格」。 */
internal enum class CanaryStatus(val wire: String, val cn: String) {
    AVAILABLE("available", "灰度可用"),
    UNAVAILABLE("unavailable", "灰度没交付"),
    ;
}

/**
 * 左相看的是成本。
 *
 * 每一项都是**别处已有的事实**转手（本层不测不改）：额度＝御案当日预算、画像置信＝耐心画像、
 * 授权档＝任务级授权那三档。给不出来的那一项就留在默认值，默认值一律偏保守（不许拿"不知道"升档）。
 */
internal data class CostFacts(
    /** 弹卡预算余额（今日还能打扰几次；≤0＝已经用尽）。 */
    val cardBudgetLeft: Int = 0,
    /** 画像置信度 0..1（耐心画像的可信度；0＝没画像）。 */
    val profileConfidence: Double = 0.0,
    /** 当日已打扰次数。 */
    val interruptedToday: Int = 0,
    /** 当前授权档（0＝只读，1＝确认后动，2＝自决）。 */
    val authTier: Int = 0,
) {
    /** 预算有余：还有额度、今天没打扰满、且授权档不是「只读」。 */
    fun budgetSpares(): Boolean = cardBudgetLeft > 0 && interruptedToday < INTERRUPT_SOFT_MAX && authTier > 0

    /** 画像置信高（≥0.7：低置信的画像不配拿来升档）。 */
    fun profileConfident(): Boolean = profileConfidence >= PROFILE_CONF_HIGH

    companion object {
        /** 当日打扰的软上限（超过就别再往上升：升档意味着更多自决，先看看是不是已经吵到人了）。 */
        const val INTERRUPT_SOFT_MAX = 3

        /** 画像置信的升档线。 */
        const val PROFILE_CONF_HIGH = 0.7
    }
}

/** 右相看的是证据（三态复用 [EvolutionConclusion]，不另立一套结论词）。 */
internal data class EvidenceFacts(
    /** 上一场进化的三态结论。 */
    val last: EvolutionConclusion = EvolutionConclusion.UNVERIFIABLE,
    /** 证据是否足（有出处、可复核）。 */
    val evidenceEnough: Boolean = false,
    /** 有反证（同题有相反的读数）。 */
    val hasCounterEvidence: Boolean = false,
    /** UNVERIFIABLE 是否比上一窗增多（含糊变多＝别升档）。 */
    val unverifiableRising: Boolean = false,
    /** 误拦率是否上升（判据④那一格：防线过紧也是降档理由）。 */
    val falseBlockRising: Boolean = false,
    /** 同类改动连续成功次数（深档要的那条 N）。 */
    val sameKindSuccessStreak: Int = 0,
    /** 可回滚**已验证**（不是"理论上能回"，是回滚真跑过一次）。 */
    val rollbackVerified: Boolean = false,
    /** 灰度状态。 */
    val canary: CanaryStatus = CanaryStatus.UNAVAILABLE,
    /** 用户明确降过档（说"浅一点"）。 */
    val userAskedLower: Boolean = false,
) {
    /** 判据④的即时降档条件（任一条命中就降，不等周期）。 */
    fun mustDowngrade(): Boolean = last == EvolutionConclusion.FAILED ||
        unverifiableRising || falseBlockRising || userAskedLower

    /** 判据②的三条：PASS ＋ 证据足 ＋（左相那两条由 [CostFacts] 判）。 */
    fun passWithEvidence(): Boolean = last == EvolutionConclusion.LANDED && evidenceEnough && !hasCounterEvidence

    companion object {
        /** 深档要的"同类连续成功"次数（与 339 连 3 次 FAIL 那条同一量级）。 */
        const val SAME_KIND_SUCCESS_MIN = 3
    }
}

/** 一次定档的**依据**（判据①：谁定的、依据哪几项、当时读数——三样都得在账上看得见）。 */
internal data class DepthDecision(
    val depth: DepthTier,
    val by: String,
    val reasons: List<String>,
    val readings: Map<String, String>,
    val atMs: Long,
) {
    fun toJson(): String = JSONObject()
        .put("v", DepthLedger.VERSION)
        .put("at", atMs)
        .put("by", by)
        .put("depth", depth.wire)
        .put("why", JSONArray(reasons))
        .put("rd", JSONObject(readings))
        .toString()
}

/** 深度账（一行一次定档；落点与灾异／升级同一棵目录，没装配 ⇒ 只进内存）。 */
internal object DepthLedger {

    const val FILE_NAME = "进化深度.jsonl"

    /** 行格式版本。 */
    const val VERSION = 1

    private const val MEMORY_CAP = 200

    private val memory = ArrayList<DepthDecision>()

    /** 记一行（写不动只留日志——定档这件事本身照样有效）。 */
    @Synchronized
    fun record(d: DepthDecision): Boolean {
        memory.add(d)
        if (memory.size > MEMORY_CAP) memory.removeAt(0)
        val root = DecisionLedger.installedRoot() ?: return false
        val ok = runCatching { File(root, FILE_NAME).appendText(d.toJson() + "\n", Charsets.UTF_8) }.isSuccess
        if (!ok) RunLog.log("抱朴深度：定档账没落上（${d.depth.wire}）——盘上没这一行")
        return ok
    }

    /** 最近一次定档（内存优先，回落盘账；都没有 ⇒ null＝这台机器还没定过档）。 */
    @Synchronized
    fun latest(): DepthDecision? {
        memory.lastOrNull()?.let { return it }
        val root = DecisionLedger.installedRoot() ?: return null
        val rows = runCatching {
            File(root, FILE_NAME).readLines(Charsets.UTF_8).mapNotNull(DepthLedger::parse)
        }.getOrDefault(emptyList())
        return rows.lastOrNull()
    }

    /** 读账（回放）。 */
    fun replay(): List<DepthDecision> {
        val root = DecisionLedger.installedRoot() ?: return memory.toList()
        val rows = runCatching {
            File(root, FILE_NAME).readLines(Charsets.UTF_8).mapNotNull(DepthLedger::parse)
        }.getOrDefault(emptyList())
        return (rows + memory).distinctBy { it.atMs }
    }

    fun parse(raw: String): DepthDecision? = runCatching {
        val o = JSONObject(raw)
        val depth = DepthTier.ofWire(o.optString("depth")) ?: return@runCatching null
        val arr = o.optJSONArray("why") ?: JSONArray()
        DepthDecision(
            depth = depth,
            by = o.optString("by"),
            reasons = (0 until arr.length()).map { arr.getString(it) },
            readings = o.optJSONObject("rd")?.let { rd -> rd.keys().asSequence().associateWith { rd.getString(it) } }
                ?: emptyMap(),
            atMs = o.optLong("at", 0L),
        )
    }.getOrNull()

    /** 直测收尾（生产不调）：磁盘账不动（"新装"那一条判据靠的就是没装配时读不到档）。 */
    @Synchronized
    fun resetForTest() {
        memory.clear()
    }
}

/**
 * 深度裁定（进程内单例：合成规则 [DepthRules] ＋ 一本定档账）。
 *
 * 谁调：进化那条链在**开提案之前**问一句「这次能改到哪一步」，拿到档再决定动作形状。
 * 本批把它做成可测的判定与账——生产接线（谁喂 [CostFacts] 的真实读数）归后续批次，
 * 理由与 330／236 那两批同款：判据没齐就不硬接，接了就是把猜测写进执行路径。
 */
internal object EvolutionDepth {

    /** 定档人（账上"谁定的"那一格；两相合成，不是模型）。 */
    const val DECIDER = "左右相合成"

    /** 现在这一档（没定过档 ⇒ [DepthTier.SHALLOW]：新装/清数据后第一次就是只记不改）。 */
    fun currentOrNull(): DepthTier? = DepthLedger.latest()?.depth

    fun current(): DepthTier = currentOrNull() ?: DepthTier.SHALLOW

    /**
     * 定一次档并落账（判据①②③④都在这一个口）。
     *
     * @param by 定档人（默认两相合成；调用方是别的东西就写别的名字，账上不许冒充）
     * @return 这次的结论（含依据与读数，账上就是它）
     */
    @Synchronized
    fun adjudicate(
        cost: CostFacts,
        evidence: EvidenceFacts,
        nowMs: Long = System.currentTimeMillis(),
        by: String = DECIDER,
    ): DepthDecision {
        val previous = currentOrNull()
        val decision = DepthRules.decide(previous, cost, evidence, nowMs, by)
        DepthLedger.record(decision)
        RunLog.log("抱朴深度：${decision.depth.cn}｜依据=${decision.reasons.joinToString("、")}")
        return decision
    }

    /** 体检单/回执那一行。 */
    fun sheetLines(): List<String> {
        val d = DepthLedger.latest()
            ?: return listOf("深度：还没定过档（新装默认浅档：只记不改）")
        return listOf("深度：${d.depth.cn}（${d.depth.oneLine}）｜定档人=${d.by}")
    }

    /** 直测收尾。 */
    fun resetForTest() {
        DepthLedger.resetForTest()
    }
}

/** 合成规则（纯函数：无 IO、无时钟注入除外；判据②③④的牙齿全在这里）。 */
internal object DepthRules {

    /**
     * 定档（顺序写死）：先看要不要**立刻降**，再看这一档站不站得住，最后才看能不能升——
     * 每次最多升一档，且**中→深只有 [canGoDeep] 一条路**（[canGoMedium] 那三条是从浅进中的门，
     * 拿它跨进深档＝绕过灰度，正是判据③「升档需三条同时满足」被咬的地方）。
     *
     * @param previous 上一档（null＝还没定过 ⇒ 从浅起）
     */
    fun decide(
        previous: DepthTier?,
        cost: CostFacts,
        evidence: EvidenceFacts,
        nowMs: Long,
        by: String,
    ): DepthDecision {
        val base = previous ?: DepthTier.SHALLOW
        val target = when {
            evidence.mustDowngrade() -> oneStepDown(base)
            // 深档的格子里掉了任何一格（灰度没交付也算）⇒ 退回中档：深档不是挣来就一直在
            base == DepthTier.DEEP && !canGoDeep(cost, evidence) -> oneStepDown(base)
            base == DepthTier.MEDIUM && canGoDeep(cost, evidence) -> nextUp(base)
            base == DepthTier.SHALLOW && canGoMedium(cost, evidence) -> nextUp(base)
            else -> base
        }
        return DepthDecision(
            depth = target,
            by = by,
            reasons = reasonsOf(target, base, cost, evidence),
            readings = readingsOf(cost, evidence),
            atMs = nowMs,
        )
    }

    /** ②：证据 PASS＋预算有余＋画像置信高 ⇒ 中（三条同时，缺一不升）。 */
    fun canGoMedium(cost: CostFacts, evidence: EvidenceFacts): Boolean =
        evidence.passWithEvidence() && cost.budgetSpares() && cost.profileConfident()

    /** ③：②成立 ＋ 连续 N 次成功 ＋ 灰度可用 ＋ 可回滚已验证 ⇒ 深。 */
    fun canGoDeep(cost: CostFacts, evidence: EvidenceFacts): Boolean =
        canGoMedium(cost, evidence) &&
            evidence.sameKindSuccessStreak >= EvidenceFacts.SAME_KIND_SUCCESS_MIN &&
            evidence.canary == CanaryStatus.AVAILABLE &&
            evidence.rollbackVerified

    /** 升一档（已经在顶就还在顶）。 */
    fun nextUp(d: DepthTier): DepthTier = when (d) {
        DepthTier.SHALLOW -> DepthTier.MEDIUM
        DepthTier.MEDIUM -> DepthTier.DEEP
        DepthTier.DEEP -> DepthTier.DEEP
    }

    /** 降一档（已经在底就还在底；判据④「立刻降一档」不降两档——一档是一档的事）。 */
    fun oneStepDown(d: DepthTier): DepthTier = when (d) {
        DepthTier.DEEP -> DepthTier.MEDIUM
        DepthTier.MEDIUM -> DepthTier.SHALLOW
        DepthTier.SHALLOW -> DepthTier.SHALLOW
    }

    private fun reasonsOf(
        target: DepthTier,
        base: DepthTier,
        cost: CostFacts,
        evidence: EvidenceFacts,
    ): List<String> = when {
        target == base -> holdReasons(base, cost, evidence)
        evidence.mustDowngrade() -> downgradeReasons(evidence)
        target == DepthTier.MEDIUM && base == DepthTier.DEEP ->
            listOf("深档站不住：灰度可用／可回滚已验证／连续成功，这一场有格没成立")
        target == DepthTier.DEEP -> deepReasons(evidence)
        else -> mediumReasons(cost)
    }

    /** 维持那一格要说清维持在哪一档、为什么没往上走。 */
    private fun holdReasons(
        base: DepthTier,
        cost: CostFacts,
        evidence: EvidenceFacts,
    ): List<String> = when {
        base == DepthTier.DEEP ->
            listOf("维持深档：灰度可用、回滚已验证、连续 ${evidence.sameKindSuccessStreak} 次同类成功")
        canGoMedium(cost, evidence) -> listOf("维持：中档条件在、深档条件不够——不跨格升")
        else -> listOf("维持：升档的三条没同时满足，降档的即时条件也没命中")
    }

    /** 降档那条：总因之外还要点名是哪几格触的（判据④「立刻降一档」要看得懂降在哪）。 */
    private fun downgradeReasons(evidence: EvidenceFacts): List<String> = buildList {
        add("降一档：即时降档条件命中")
        if (evidence.last == EvolutionConclusion.FAILED) add("上一场 FAIL")
        if (evidence.unverifiableRising) add("没法验增多")
        if (evidence.falseBlockRising) add("误拦率上升")
        if (evidence.userAskedLower) add("用户明确降档")
    }

    private fun deepReasons(evidence: EvidenceFacts): List<String> = listOf(
        "升两格挣来的：PASS＋预算有余＋画像高置信",
        "连续 ${evidence.sameKindSuccessStreak} 次同类成功",
        "灰度可用",
        "可回滚已验证",
    )

    /** 进中档那四格：后两格是左相的，只在真有余量／真置信时才写（不拿没成立的格子冒充依据）。 */
    private fun mediumReasons(cost: CostFacts): List<String> = buildList {
        add("升到中档：上一场 PASS")
        add("证据足且无反证")
        if (cost.budgetSpares()) add("预算有余")
        if (cost.profileConfident()) add("画像置信高")
    }

    /** 当时的读数全量进账（判据①「当时读数」那一格；不拿事后解释冒充现场）。 */
    private fun readingsOf(cost: CostFacts, evidence: EvidenceFacts): Map<String, String> = linkedMapOf(
        "cardBudgetLeft" to cost.cardBudgetLeft.toString(),
        "profileConfidence" to cost.profileConfidence.toString(),
        "interruptedToday" to cost.interruptedToday.toString(),
        "authTier" to cost.authTier.toString(),
        "last" to evidence.last.name,
        "evidenceEnough" to evidence.evidenceEnough.toString(),
        "counter" to evidence.hasCounterEvidence.toString(),
        "unverifiableRising" to evidence.unverifiableRising.toString(),
        "falseBlockRising" to evidence.falseBlockRising.toString(),
        "streak" to evidence.sameKindSuccessStreak.toString(),
        "rollbackVerified" to evidence.rollbackVerified.toString(),
        "canary" to evidence.canary.wire,
        "userAskedLower" to evidence.userAskedLower.toString(),
    )
}

/**
 * 模型参谋的一条意见（判据⑤的形状）。
 *
 * @param selfIssued 这条意见评的是**模型自己的产物**（同源自评）——为真时 [AdvisorGate] 拒收
 * @param blocked 被拦下的那一条会带真（拦了要看得见，见 [AdvisorGate.accept]）
 * @param blockNote 拦下的理由（照实落，不粉饰）
 */
internal data class EvolutionAdvice(
    val id: String,
    val atMs: Long,
    val about: String,
    val stance: String,
    val reason: String,
    val risks: List<String>,
    val selfIssued: Boolean,
    val blocked: Boolean = false,
    val blockNote: String = "",
) {
    fun toJson(): String = JSONObject()
        .put("id", id)
        .put("at", atMs)
        .put("about", about)
        .put("stance", stance)
        .put("why", reason)
        .put("risk", JSONArray(risks))
        .put("self", selfIssued)
        .put("blocked", blocked)
        .put("note", blockNote)
        .toString()

    companion object {
        fun parse(raw: String): EvolutionAdvice? = runCatching {
            val o = JSONObject(raw)
            val arr = o.optJSONArray("risk") ?: JSONArray()
            EvolutionAdvice(
                id = o.optString("id"),
                atMs = o.optLong("at", 0L),
                about = o.optString("about"),
                stance = o.optString("stance"),
                reason = o.optString("why"),
                risks = (0 until arr.length()).map { arr.getString(it) },
                selfIssued = o.optBoolean("self", false),
                blocked = o.optBoolean("blocked", false),
                blockNote = o.optString("note"),
            )
        }.getOrNull()
    }
}

/** 参谋意见的闸（可给意见，不给票；三件事在形状上就办不到）。 */
internal object AdvisorGate {

    /** 意见账（与深度账同一棵目录；被拦的意见也落账——"它想过"这件事要看得见）。 */
    const val FILE_NAME = "进化参谋意见.jsonl"

    private const val MEMORY_CAP = 200

    private val memory = ArrayList<EvolutionAdvice>()

    /**
     * 收一条意见。
     *
     * 判据⑤要的是「被拦**并记账**」，所以拒收不是悄悄丢：被拦的那条照样进账，
     * 只是打上 `blocked` 与理由——「模型给自己的产出发过通过」这件事必须查得出来。
     *
     * @return null＝拒收（账上有这一行）；非 null＝收下
     */
    @Synchronized
    fun accept(advice: EvolutionAdvice): EvolutionAdvice? {
        val why = when {
            advice.selfIssued -> "同源自评：模型给自己的产出发通过——意见没有票"
            advice.about.isBlank() -> "没说清评的是谁"
            advice.reason.isBlank() -> "没说清为什么"
            else -> null
        }
        val recorded = if (why == null) advice else advice.copy(blocked = true, blockNote = why)
        memory.add(recorded)
        if (memory.size > MEMORY_CAP) memory.removeAt(0)
        val root = DecisionLedger.installedRoot()
        if (root != null) {
            val ok =
                runCatching { File(root, FILE_NAME).appendText(recorded.toJson() + "\n", Charsets.UTF_8) }.isSuccess
            if (!ok) RunLog.log("抱朴参谋：意见没落上账（${recorded.id}）")
        }
        if (why != null) {
            RunLog.log("抱朴参谋：$why（已记进意见账）")
            return null
        }
        return advice
    }

    /**
     * 意见能不能改动这两样——答案恒为否，函数存在的意义是让"改不了"有一个可测的口。
     *
     * 深度档归两相（[DepthRules]）、验收结论归规则层＋人（右相三态不被覆盖）；
     * 模型意见只是**输入之一**。
     */
    fun affectsDepth(): Boolean = false

    fun overridesVerdict(): Boolean = false

    fun replay(): List<EvolutionAdvice> = memory.toList()

    /** 读盘账（没装配 ⇒ 空表，不编历史）。 */
    fun diskReplay(): List<EvolutionAdvice> {
        val root = DecisionLedger.installedRoot() ?: return emptyList()
        return runCatching {
            File(root, FILE_NAME).readLines(Charsets.UTF_8).mapNotNull(EvolutionAdvice::parse)
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun resetForTest() {
        memory.clear()
    }
}

/** 制度类红线（设计文档判据⑥：制度类出现在候选里 ⇒ 直接拒，不进任何档）。 */
internal object InstitutionalGuard {

    /**
     * 是不是制度类改动。
     *
     * 三条既有事实的形状：A 内核、B/C 前三条（人设锁头）、法典判据——它们**不在任何档内**，
     * 判据不在这层重算，只认调用方带进来的键（`kernel`／`persona_head`／`criteria`）。
     */
    fun isInstitutional(areaKey: String): Boolean = areaKey in AREA_KEYS

    /** 能不能进候选：制度类一律 false（谁都不许顺手改）。 */
    fun allowed(areaKey: String): Boolean = !isInstitutional(areaKey)

    private val AREA_KEYS = setOf("kernel", "persona_head", "criteria")
}
