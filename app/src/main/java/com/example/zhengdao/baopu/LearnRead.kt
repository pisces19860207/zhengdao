// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百批 · 学习**读侧**接线：283/298 把"写"接通了（信号入账/折表/草稿查表），
// 但 283 预留的三个读口（弹卡前问/排序/回放）生产调用全 0——学习**写得进去、读不出来**。
// 本文件把三个读口接进生产：①弹卡前问 `shouldSuppress`/`mustAsk`；②排序用
// `orderRulings`/`stats`；③出卡上下文（证据区）带最近同类经验。
//
// 三条铁律（派工单 400 原文，判据全在本文件＋测试里）：
//   1. **读不许改三态**：对经验账／经验表／意见队列只读——不 append、不 refresh、
//      不 enqueue、不记 208。判据层只吃行列表，IO 壳只调 [LearnWiring.replay]
//      （源级门禁 `LearnReadGateTest` 扫本文件禁出现写口字样）；
//   2. **样本 <5 不生效**：某类的窗内信号行（与 [LearnPolicy.stats] 同一把尺：
//      30 天窗内、最近一次重问之后）不足 [LearnReadJudge.MIN_SAMPLES] 条时，
//      学出来的结论一律当"没学过"——行为与接线前逐字一致；
//   3. **每次"因经验而变"要能说出依据**：每个生效判定都带一句证据
//      （样本数＋关闭数＋账上理由），调用方落 RunLog／卡面证据区。
//
// 房型照 283：判据（[LearnReadJudge]，纯函数零 IO 零时钟）与 IO 壳（[LearnRead]）分离。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.agent.ExperienceKind
import com.example.zhengdao.baopu.agent.ExperienceRow
import com.example.zhengdao.baopu.agent.LearnPolicy
import com.example.zhengdao.baopu.agent.LearnWiring
import com.example.zhengdao.baopu.agent.Ruling
import com.example.zhengdao.baopu.inspect.LawRef
import com.example.zhengdao.baopu.inspect.Opinion

/** 读侧判据（纯函数：零 IO、零时钟——行与 `now` 都由调用方给）。 */
internal object LearnReadJudge {

    /** 样本地板（铁律②）：窗内信号行不足此数 ⇒ 学习不生效，照接线前行为。 */
    const val MIN_SAMPLES = 5

    /** 一次弹卡咨询的结论。 */
    data class Consult(
        /** true＝照旧摆卡；false＝压（不摆）。 */
        val allowed: Boolean,
        /** true＝学习真下了结论（样本够且关满阈值）——只有这时才谈"因经验而变"。 */
        val effective: Boolean,
        /** 窗内信号样本数（对拍用）。 */
        val samples: Int,
        /** 该类窗内关闭数（对拍用）。 */
        val closes: Int,
        /** 依据一句（铁律③；空＝学习没参与，行为与接线前一致）。 */
        val evidence: String,
    )

    /** 一次排序的结论：排好的表＋生效依据（null＝没到地板，保持接线前的新→旧）。 */
    data class OrderedResult(val rulings: List<Ruling>, val activatedLine: String?)

    /**
     * 某类的窗内信号行——**与 [LearnPolicy.stats] 同一把尺**（30 天窗内、最近一次重问
     * 之后；测试里有对拍：本函数数出的关闭数必须等于 `stats().closes`）。
     */
    fun windowSignals(rows: List<ExperienceRow>, cardClass: String, now: Long): List<ExperienceRow> {
        val windowStart = now - LearnPolicy.WINDOW_DAYS * 24L * 3_600_000L
        val mine = rows.filter { it.cardClass == cardClass }
        val lastReask = mine.filter { it.kind == ExperienceKind.REASK }.maxOfOrNull { it.atMs }
        return mine.filter {
            it.kind == ExperienceKind.SIGNAL && it.atMs >= windowStart && it.atMs <= now &&
                (lastReask == null || it.atMs > lastReask)
        }
    }

    /**
     * ①弹卡前问 [LearnPolicy.shouldSuppress]／[LearnPolicy.mustAsk]。
     *
     * · 样本 <5 ⇒ 不生效（allowed=true、effective=false、evidence 空）；
     * · 关满阈值且非必问 ⇒ 压（allowed=false），依据＝账上那句＋样本数；
     * · 关满阈值但撞法条必问集（opinion 在 L7/L8/critical）⇒ 照摆（法条优先），
     *   依据说清"学习想压、法条要问"——学习结果作废但不记违规（记账是写侧
     *   `LearnWiring.onOpinion` 的活，读侧一条不写）。
     *
     * @param opinion 该卡的原始意见；反思卡等非意见卡传 null（mustAsk 无从谈起）
     */
    fun consult(
        rows: List<ExperienceRow>,
        cardClass: String,
        opinion: Opinion?,
        now: Long,
    ): Consult {
        val signals = windowSignals(rows, cardClass, now)
        if (signals.size < MIN_SAMPLES) {
            return Consult(allowed = true, effective = false, samples = signals.size, closes = 0, evidence = "")
        }
        val verdict = LearnPolicy.shouldSuppress(rows, cardClass, now)
        if (!verdict.suppressed) {
            return Consult(allowed = true, effective = false, samples = signals.size, closes = verdict.closes, evidence = "")
        }
        if (opinion != null && LearnPolicy.mustAsk(opinion)) {
            val mandate = if (opinion.lawRef == LawRef.NONE) "critical（L21 紧急上报权）" else opinion.lawRef.name
            return Consult(
                allowed = true,
                effective = true,
                samples = signals.size,
                closes = verdict.closes,
                evidence = "同类 ${LearnPolicy.WINDOW_DAYS} 天关 ${verdict.closes} 次（样本 ${signals.size}），" +
                    "但 $mandate 必问——学习无权过问，法条优先",
            )
        }
        return Consult(
            allowed = false,
            effective = true,
            samples = signals.size,
            closes = verdict.closes,
            evidence = "${verdict.reason}（样本 ${signals.size}）",
        )
    }

    /**
     * ②排序用 [LearnPolicy.orderRulings]：批过的类往前排 → 严重度高在前 → 早压下的先看。
     *
     * 地板逐类算：没到 [MIN_SAMPLES] 的类 approves 按 0 计（不享受学到的提前量）。
     * 全队列没有任何类到地板 ⇒ 原样返回接线前口径（atMs 新→旧），一个字不差。
     */
    fun ordered(rows: List<ExperienceRow>, rulings: List<Ruling>, now: Long): OrderedResult {
        val floorClasses = rulings.map { it.opinion.gist }.distinct()
            .filter { windowSignals(rows, it, now).size >= MIN_SAMPLES }
        if (floorClasses.isEmpty()) {
            return OrderedResult(rulings.sortedByDescending { it.atMs }, null)
        }
        val out = LearnPolicy.orderRulings(rulings) { cls ->
            if (windowSignals(rows, cls, now).size >= MIN_SAMPLES) {
                LearnPolicy.stats(rows, cls, now)
            } else {
                LearnPolicy.ClassStats(closes = 0, approves = 0, rejects = 0, lastReaskAtMs = null)
            }
        }
        return OrderedResult(
            rulings = out,
            activatedLine = "学习排序生效：${floorClasses.joinToString("、")} 达样本地板（≥$MIN_SAMPLES）" +
                "——批过的类往前排、严重度高在前、早压下先看（每类的信号都在账上）",
        )
    }

    /**
     * ③最近同类经验（新→旧，至多 [limit] 条，种类不限——signal/ask/suppress/attribution
     * 的理由都算经验）。样本 <5 ⇒ 空表（铁律②：薄样本不进上下文）。
     */
    fun recent(rows: List<ExperienceRow>, cardClass: String, limit: Int, now: Long): List<ExperienceRow> {
        if (windowSignals(rows, cardClass, now).size < MIN_SAMPLES) return emptyList()
        return rows.filter { it.cardClass == cardClass }.sortedByDescending { it.atMs }.take(limit)
    }
}

/** 读侧 IO 壳（只调 [LearnWiring.replay]，一个写口都没有）。 */
internal object LearnRead {

    /** 读侧日志前缀（"因经验而变"的行都带它，验收 grep 用）。 */
    const val LOG_PREFIX = "抱朴学习读侧："

    /** 全本读一次（调用方多问几类时复用，省得每类重读账本文件）。 */
    fun rows(): List<ExperienceRow> = LearnWiring.replay()

    /** ①弹卡前问（自读账本版；已持全本时用 [LearnReadJudge.consult]）。 */
    fun cardAllowed(
        cardClass: String,
        opinion: Opinion? = null,
        now: Long = System.currentTimeMillis(),
    ): LearnReadJudge.Consult = LearnReadJudge.consult(rows(), cardClass, opinion, now)

    /** ②排序（自读账本版；御案列表持全本时用 [LearnReadJudge.ordered]）。 */
    fun orderedRulings(rulings: List<Ruling>, now: Long): LearnReadJudge.OrderedResult =
        LearnReadJudge.ordered(rows(), rulings, now)

    /** ③最近同类经验（自读账本版；默认 3 条——宁少不滥，205 同一口径）。 */
    fun recentExperience(
        cardClass: String,
        limit: Int = 3,
        now: Long = System.currentTimeMillis(),
    ): List<ExperienceRow> = LearnReadJudge.recent(rows(), cardClass, limit, now)
}
