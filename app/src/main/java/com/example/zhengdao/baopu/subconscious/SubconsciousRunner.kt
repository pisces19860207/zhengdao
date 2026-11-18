// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百七十六批 · 下意识执行器的**编排层**（仍是纯逻辑：零 Android、零 IO、零时钟）。
//
// 一轮回响的次序（谁先谁后是判据，不是实现细节）：
//   ① 天闸（判据①）：今天已经跑过 ⇒ 这轮什么都不做（不调模型、不落行、不耗额度）；
//   ② 左相（判据③）：免打扰窗口内 ⇒ 整轮延后——不落行、**也不耗当日额度**
//      （天亮/授权到期后的下一轮还能跑；「延后不是丢弃」，330 的老口径）；
//   ③ 候选 ≤ [EchoLimits.CANDIDATES_CAP] 条喂给模型（判据⑤）；
//   ④ 模型只「选不造」：[sanitizeModelPick] 把不在候选集里的 id 全扔；
//      模型不可用 / 没挑出合法 id ⇒ 退回 [rulesPick]（判据④：不崩、不空转——
//      没有重试、没有第二趟，退回是一次性的）；
//   ⑤ 右相三态（判据③）：只有可建议的浮出（[rulesPick]/[sanitizeModelPick] 内已判）；
//   ⑥ 起居注同形 ACTION 行（判据⑤）：**一枚信号一行**——0 枚 ⇒ 0 行，
//      行数与该次建议数一致是结构保证（[EchoOutcome.actionRows] 由信号逐条映出）。
//
// 本文件一个写口都没有：落账归装配层（[SubconsciousWiring]），本层只产出。
// 只出信号不执行（判据⑥）：返回类型里没有任何「去做某事」的出口，参数里也没有——
// 执行器想执行都没有抓手。
//
// 本件无判定权（523 三件套②）：不产生 RiskDecision/PROCEED/REJECT，不改档，不写权威账。
package com.example.zhengdao.baopu.subconscious

import com.example.zhengdao.baopu.agent.QuietGrant
import com.example.zhengdao.baopu.agent.SessionShape
import com.example.zhengdao.baopu.safety.DiaryEvent
import com.example.zhengdao.baopu.safety.DiaryKind

/**
 * 模型挑选口（「选不造」的缝）：收 ≤ [EchoLimits.CANDIDATES_CAP] 条候选，
 * 回挑中的候选 id 列表。返回 null ＝ 模型不可用（没配置/没钥匙/超时/解析不了）——
 * 调用方退回规则版；**实现方不许**自己挑候选集以外的 id（会被扔掉，见判据②）。
 */
internal fun interface EchoPicker {
    fun pick(candidates: List<SubconsciousCandidate>): List<String>?
}

/** 一轮回响的产物（装配层照它落账；测试照它断言）。 */
internal data class EchoOutcome(

    /** 浮出的信号（≤ [EchoLimits.ROWS_MAX]；空表 ＝ 这轮真跑了但没什么可提）。 */
    val signals: List<SubconsciousSignal>,

    /** 起居注同形 ACTION 行——**逐条映自 [signals]**，行数判据靠这个不变量。 */
    val actionRows: List<DiaryEvent>,

    /** 这轮真跑了吗（过了天闸且左相放行）。false ⇒ 其余字段全是空/默认。 */
    val ran: Boolean,

    /** 左相延后（免打扰窗口内；判据③负例：此时 signals 必须为空）。 */
    val deferred: Boolean,

    /** 延后/跳过的一句话原因（落日志用；空 ＝ 没跳过）。 */
    val skipWhy: String,

    /** 这轮的挑选是模型做的吗（false ＝ 规则退回；判据④的读数）。 */
    val modelPicked: Boolean,

    /** 这轮的「今天」桶（装配层拿去记 last-run 天闸，下次比对用）。 */
    val dayKey: Long,
)

/** 下意识执行器（编排层；判据全在 [SubconsciousEcho]，落账全在装配层）。 */
internal object SubconsciousRunner {

    /** 起居注 ACTION 行的依据格前缀（装配层按它认领自己的行，不误吃别人的 ACTION）。 */
    const val ACTION_WHY = "676 回响"

    /**
     * 跑一轮回响。
     *
     * @param candidates 候选（来源不论——本批三类、677 灵感、678 重放都从这里进）
     * @param nowMs 本轮时刻（时间注入；本层不读钟）
     * @param grant 本会话的免打扰授权（没有 ＝ null）
     * @param shape 本轮形状（钟点注入；夜间/专注在 [SessionShape.hush] 里判）
     * @param lastRunDayKey 上一次真跑的「今天」桶（null ＝ 从没跑过）
     * @param suggestedAtByKey 各账行上次被浮出的时刻（右相冷却用；没有 ＝ 空表）
     * @param picker 模型挑选口（null ＝ 这轮就按规则挑——模型那一路整条不激活）
     */
    fun run(
        candidates: List<SubconsciousCandidate>,
        nowMs: Long,
        grant: QuietGrant?,
        shape: SessionShape,
        lastRunDayKey: Long?,
        suggestedAtByKey: Map<String, Long>,
        picker: EchoPicker?,
    ): EchoOutcome {
        val dayKey = echoDayKey(nowMs)
        if (lastRunDayKey == dayKey) {
            return skipped(dayKey, "今天已经回响过（一天 ≤1 次）")
        }
        val hush = leftMinisterHushReasons(grant, shape, nowMs)
        if (hush.isNotEmpty()) {
            return EchoOutcome(
                signals = emptyList(),
                actionRows = emptyList(),
                ran = false,
                deferred = true,
                skipWhy = "免打扰窗口内不弹（${hush.joinToString("＋")}）——延后，不耗当日额度",
                modelPicked = false,
                dayKey = dayKey,
            )
        }
        // 候选截到喂模型的口径（≤8；判据⑤）。缺引用的候选活不到这里——
        // [SubconsciousCandidate] 构造期就拒了，这里是第二道防线，直接丢。
        val pool = candidates.filter { it.ledgerId.isNotBlank() }.take(EchoLimits.CANDIDATES_CAP)
        val (picked, fromModel) = pickByModelOrRules(pool, picker, suggestedAtByKey, nowMs)
        val publishedAt = nowMs
        val signals = picked.map { c ->
            SubconsciousSignal(
                id = c.id,
                kind = c.kind,
                title = c.title,
                ledgerId = c.ledgerId,
                atMs = c.atMs,
                verdict = EchoVerdict.RECOMMEND,
                modelPicked = fromModel,
                publishedAtMs = publishedAt,
            )
        }
        // 判据⑤：一枚信号一行 ACTION（0 枚 ⇒ 0 行）；行里写清「谁建议、依据哪条账」。
        val actionRows = signals.map { s ->
            DiaryEvent(
                kind = DiaryKind.ACTION,
                atMs = publishedAt,
                why = "$ACTION_WHY · ${s.id}",
                what = "${s.title}（依据账行 ${s.ledgerId}）",
                result = "${s.kind.label}·右相可建议",
            )
        }
        return EchoOutcome(
            signals = signals,
            actionRows = actionRows,
            ran = true,
            deferred = false,
            skipWhy = "",
            modelPicked = fromModel,
            dayKey = dayKey,
        )
    }

    /** 天闸拦下的那一种空轮（modelPicked 恒 false——没跑就没有模型）。 */
    private fun skipped(dayKey: Long, why: String): EchoOutcome = EchoOutcome(
        signals = emptyList(),
        actionRows = emptyList(),
        ran = false,
        deferred = false,
        skipWhy = why,
        modelPicked = false,
        dayKey = dayKey,
    )

    /**
     * 模型优先、规则兜底（判据④⑤）。模型回什么由 [sanitizeModelPick] 把关：
     * 不在候选集里的 id 扔掉、右相不建议的也进不来；拿不到合法结果 ⇒ 规则版。
     * **没有重试**——模型这趟不行就规则，不空转。第二返回值 ＝ 这轮是不是模型挑的。
     */
    private fun pickByModelOrRules(
        pool: List<SubconsciousCandidate>,
        picker: EchoPicker?,
        suggestedAtByKey: Map<String, Long>,
        nowMs: Long,
    ): Pair<List<SubconsciousCandidate>, Boolean> {
        if (picker != null) {
            val byModel = runCatching { picker.pick(pool) }.getOrNull()
                ?.let { sanitizeModelPick(it, pool, suggestedAtByKey, nowMs) }
            if (byModel != null) return byModel to true
        }
        return rulesPick(pool, suggestedAtByKey, nowMs) to false
    }
}
