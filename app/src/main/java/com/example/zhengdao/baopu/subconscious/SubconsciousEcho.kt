// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百七十六批 · 下意识执行器的**纯判据层**（625/M3，类脑批二）。
// 派工单：`_zd-private/docs/派工单-2026-11-16-第六百七十六批-下意识.md`。
//
// 「回响」是什么：低频（自带天闸 ≤1 次/天）从**项目账行**里挑出值得想起的事，
// 浮成一枚**信号**——只出信号、不执行动作（判据⑥由源码门禁钉死：本包没有任何
// 落盘账本/改档/执行工具的引用）。三个候选来源（与派工单 §一①逐字对应）：
//   · 最近失败——账上有右相 FAIL 结论的条目（还没收）；
//   · 未完成步骤——立了账却一直「在飞」的条目（放了几天没动）；
//   · 用户明确提过但没做——带来源消息号、却被冻结/撤掉的条目。
//
// 左右相分工（派工单 §一③，判据本体一个字不抄）：
//   · 右相判三态（可建议/不建议/不确定）——按账龄与「最近已经提过」判，只有
//     可建议那条浮出；三态都是**结论**，闭集不许开第四态（310 口径）。
//   · 左相判打扰——授权／夜间／专注的判定**唯一一份**在
//     [com.example.zhengdao.baopu.agent.QuietVerify.hushedReasons]，这里只引用：
//     免打扰窗口内 ⇒ 整轮延后（不落行、也不耗当日额度——天亮之后下一轮还能跑）。
//
// 模型只「选不造」（派工单 §一⑤）：模型拿到的候选 ≤ [EchoLimits.CANDIDATES_CAP] 条，
// 它回的 id 凡是不在候选集里的一律扔（[sanitizeModelPick]）——模型只能从给定的
// 账行里**挑**，不能凭空造一个没出处的建议（判据②：候选必须指回账行）。
//
// 给 677/678 留的缝（边界：共用执行器，不另造）：候选从外面喂进来
// （[SubconsciousRunner.run] 只收列表），677 的「灵感」、678 的「海马重放」
// 各自造自己的候选列表＋在 [EchoKind] 加一枚枚举项即可，执行器的闸、天闸、
// 起居注行数判据一个字不用改。
//
// 纪律：零 Android、零 IO、零时钟——时刻/时区/免打扰状态全由调用方注入，
// JVM 单测直测（时间注入夹具）。
package com.example.zhengdao.baopu.subconscious

import com.example.zhengdao.baopu.agent.QuietGrant
import com.example.zhengdao.baopu.agent.QuietVerify
import com.example.zhengdao.baopu.agent.SessionShape
import com.example.zhengdao.baopu.inspect.PatrolScheduler

/** 限值的唯一住处（改任何一条＝改回响制度，不许调用处各写一把）。 */
internal object EchoLimits {

    /** 喂给模型的候选上限（派工单 §一⑤：≤8）。 */
    const val CANDIDATES_CAP = 8

    /** 一轮最多浮几枚信号（建议页签第三类行：3 枚顶天，多了就是刷屏）。 */
    const val ROWS_MAX = 3

    /** 「最近失败」的回看窗（7 天；再老的失败不回响——它早该收或撤了）。 */
    const val FAIL_RECENT_MS: Long = 7L * 24 * 60 * 60 * 1000

    /** 「未完成步骤」的搁置线（在飞且立账超过 3 天没动 ⇒ 想得起）。 */
    const val STEP_STALE_MS: Long = 3L * 24 * 60 * 60 * 1000

    /** 「在飞」条目太老就不回响（30 天：多半是死账，该撤而不是被想起）。 */
    const val STEP_TOO_OLD_MS: Long = 30L * 24 * 60 * 60 * 1000

    /** 「提过没做」的回看窗（30 天）。 */
    const val MENTIONED_RECENT_MS: Long = 30L * 24 * 60 * 60 * 1000

    /**
     * 同一条账行的回响冷却（3 天内提过 ⇒ 不建议——别翻来覆去提同一件事）。
     *
     * 天闸（≤1 次/天）管的是**执行器**的节奏；这条管的是**同一条账**的重复打扰。
     */
    const val SUGGEST_COOLDOWN_MS: Long = 3L * 24 * 60 * 60 * 1000

    /** 账龄过半 ⇒ 右相改判「不确定」（还能提，但得说清楚把握不大）。 */
    const val STALE_FRACTION_NUM = 1

    /** 见 [STALE_FRACTION_NUM]（分母 2：过半＝账龄 > 回看窗/2）。 */
    const val STALE_FRACTION_DEN = 2
}

/**
 * 回响的三类来源（派工单 §一①的闭集）。
 *
 * ⚠️ 677（灵感闪回）与 678（海马重放）**允许**在本枚举加项、并各自造候选列表——
 * 这是共用执行器留好的缝；除此之外不许任何人另写一个执行器（源码门禁盯着）。
 */
internal enum class EchoKind(val wire: String, val label: String) {

    /** 最近失败：右相判过 FAIL 且还没收的账。 */
    FAIL_RECENT("fail_recent", "下意识"),

    /** 未完成步骤：立了账一直「在飞」的。 */
    STEP_UNFINISHED("step_unfinished", "想起"),

    /** 用户明确提过但没做：带来源消息号、被冻结或撤掉的。 */
    MENTIONED_NOT_DONE("mentioned_not_done", "想起"),
    ;

    /** 本类候选的回看窗（账龄超过它 ⇒ 右相不给可建议）。 */
    val windowMs: Long
        get() = when (this) {
            FAIL_RECENT -> EchoLimits.FAIL_RECENT_MS
            STEP_UNFINISHED -> EchoLimits.STEP_TOO_OLD_MS
            MENTIONED_NOT_DONE -> EchoLimits.MENTIONED_RECENT_MS
        }

    companion object {
        /** 认不出就 null（账上自造值不静默当别的类）。 */
        fun ofWire(raw: String): EchoKind? = entries.firstOrNull { it.wire == raw }
    }
}

/** 右相的三态结论（派工单 §一③；闭集，不许开第四态）。 */
internal enum class EchoVerdict(val cn: String) {

    /** 账新鲜、最近没提过：浮出。 */
    RECOMMEND("可建议"),

    /** 太老，或 3 天内已经提过：这轮不浮。 */
    NOT_RECOMMEND("不建议"),

    /** 还在窗内但已过半程：算不上「可建议」，也不至于「不建议」。 */
    UNCERTAIN("不确定"),
    ;
}

/**
 * 一条回响候选。**必须指回账行**（判据②）：[ledgerId] 为空在构造期就炸——
 * 缺引用的候选活不到出卡那一步，这是结构保证，不是运行时检查。
 *
 * @param id 候选稳定 id（`echo:<ledgerId>`；同一条账行同一时刻只有一个候选）
 * @param kind 来源类（决定 typeLabel：下意识/想起；677 的灵感是第四项）
 * @param title 一句话标题（账行标题原样，不加工——信号不是转述）
 * @param ledgerId 指回的项目账行 id（`L2` 起稳定递增）
 * @param atMs 账行立账时刻（判账龄用）
 * @param verdictOfLedger 账行上右相最近一次结论（FAIL/UNVERIFIABLE/PASS；空＝没验过）
 */
internal data class SubconsciousCandidate(
    val id: String,
    val kind: EchoKind,
    val title: String,
    val ledgerId: String,
    val atMs: Long,
    val verdictOfLedger: String = "",
) {
    init {
        require(ledgerId.isNotBlank()) { "回响候选必须指回账行（缺引用 ＝ 不出卡，判据②）" }
        require(id.isNotBlank()) { "回响候选必须有稳定 id" }
        require(!title.contains('\n') && !title.contains('\t')) { "标题必须是一行（信号不是转述）" }
    }

    /** 去重键（同一条账行在冷却期内只浮一次）。 */
    val dedupeKey: String get() = ledgerId
}

/**
 * 一枚浮出的回响信号（「建议」页签新类型行的数据源；只读、无动作）。
 *
 * @param verdict 右相对这条的三态结论（浮出的恒为可建议——其余两态不浮）
 * @param modelPicked 这条是模型挑的（true）还是规则退回挑的（false）——页签副标题如实说
 */
internal data class SubconsciousSignal(
    val id: String,
    val kind: EchoKind,
    val title: String,
    val ledgerId: String,
    val atMs: Long,
    val verdict: EchoVerdict,
    val modelPicked: Boolean,
    val publishedAtMs: Long,
)

/**
 * 右相判三态（纯函数）：
 *   · 冷却期内提过 / 账龄超窗 ⇒ 不建议；
 *   · 账龄过半（> 窗/2）⇒ 不确定；
 *   · 其余 ⇒ 可建议。
 */
internal fun rightMinisterJudge(candidate: SubconsciousCandidate, nowMs: Long, suggestedAtMs: Long?): EchoVerdict {
    val age = (nowMs - candidate.atMs).coerceAtLeast(0L)
    if (suggestedAtMs != null && nowMs - suggestedAtMs < EchoLimits.SUGGEST_COOLDOWN_MS) {
        return EchoVerdict.NOT_RECOMMEND
    }
    if (age > candidate.kind.windowMs) return EchoVerdict.NOT_RECOMMEND
    val half = candidate.kind.windowMs * EchoLimits.STALE_FRACTION_NUM / EchoLimits.STALE_FRACTION_DEN
    return if (age > half) EchoVerdict.UNCERTAIN else EchoVerdict.RECOMMEND
}

/**
 * 左相判打扰（判据本体在 [QuietVerify.hushedReasons]，这里只是引用——免打扰
 * 以后多一个源，两处不会各改一半）。返回非空 ＝ 此刻在免打扰窗口里。
 */
internal fun leftMinisterHushReasons(
    grant: QuietGrant?,
    shape: SessionShape,
    nowMs: Long,
): List<String> = QuietVerify.hushedReasons(grant, shape, nowMs)

/** 一类内部怎么排：未完成步骤最久搁置的先，其余新→旧。 */
private fun orderWithinKind(c: SubconsciousCandidate): Long =
    if (c.kind == EchoKind.STEP_UNFINISHED) c.atMs else -c.atMs

/**
 * 规则版挑选（模型不可用时的退回，判据④；也是没有模型那一路的**默认**）。
 *
 * 确定性排序：最近失败最先、未完成步骤其次、提过没做最后；一类之内见
 * [orderWithinKind]。超过 [EchoLimits.ROWS_MAX] 的丢——回响是轻声提醒，不是清单。
 */
internal fun rulesPick(
    candidates: List<SubconsciousCandidate>,
    suggestedAtByKey: Map<String, Long>,
    nowMs: Long,
): List<SubconsciousCandidate> = candidates
    .asSequence()
    .filter { rightMinisterJudge(it, nowMs, suggestedAtByKey[it.dedupeKey]) == EchoVerdict.RECOMMEND }
    .sortedWith(compareBy({ it.kind.ordinal }, ::orderWithinKind))
    .take(EchoLimits.ROWS_MAX)
    .toList()

/**
 * 模型选不造的 sanitize（判据②⑤的结构保证）：模型回的 id 凡是候选集里没有的，
 * 一律扔——它只能从给定的账行里挑，不能造一条没出处的建议。返回 null ＝
 * 没挑出任何合法 id（调用方退回规则版），不给「模型造了半条」的中间态。
 */
internal fun sanitizeModelPick(
    pickedIds: List<String>,
    candidates: List<SubconsciousCandidate>,
    suggestedAtByKey: Map<String, Long>,
    nowMs: Long,
): List<SubconsciousCandidate>? {
    if (pickedIds.isEmpty()) return null
    val byId = candidates.associateBy { it.id }
    val valid = pickedIds.asSequence()
        .mapNotNull(byId::get)
        .distinct()
        .filter { rightMinisterJudge(it, nowMs, suggestedAtByKey[it.dedupeKey]) == EchoVerdict.RECOMMEND }
        .take(EchoLimits.ROWS_MAX)
        .toList()
    return if (valid.isEmpty()) null else valid
}

/**
 * 「今天」的桶（本地日历日；复用巡按 [PatrolScheduler.dayBucket] 同一把尺——
 * 日历口径全仓只住一层，回响不另立一套「什么算一天」）。
 */
internal fun echoDayKey(nowMs: Long): Long = PatrolScheduler.dayBucket(nowMs)
