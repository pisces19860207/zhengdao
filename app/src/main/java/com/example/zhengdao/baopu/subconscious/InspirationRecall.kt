// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百七十七批 · 534 灵感闪回 ②「关联唤醒」——三重命中才浮的**纯判据**，
// 与 676 执行器的**来源缝**（适配层；676 未合入，接口形状按其派工单约定落，
// 合入后由 SubconsciousRunner 调本缝并回归）。
//
// 依据（用户裁定，逐字落码）：
// - 2026-11-08 立 534：「只在"词面≥2 个二元组 + 30 天内 + 同任务/主题"三重重合时」
//   ⇒ 三重命中＝语义近邻（词面重合二元组 ≥2）＋ 近期上下文（30 天内）＋ 用户状态（同任务/主题），
//   **缺一不浮**（三个负例各有单测钉）；
// - 2026-11-12 并入统一口径：默认开（可关）、与 625 共用一个执行器（无第二浮出入口）、
//   入口＝决策中心「建议」页签第三类行（typeLabel 区分，不做胶囊）、
//   起居注同形 ACTION＋本账 jsonl、模型只"选不造"（≤8 候选、≤1 次/天、不可用退回规则）；
// - 625 派工单（执行器约定）：候选必须带稳定 id；模型返回值不在候选里 ⇒ 整条丢弃；
//   只出信号，不执行动作。
//
// 三条纪律：
//   1. **零调度**：本文件（与本包）没有循环/线程/定时器——执行节奏归 676 的
//      SubconsciousRunner（或本单的生产调用点：打开建议页签时算一轮，机会式、零后台调度）；
//      源码门禁扫「本包无调度构造」钉死（判据⑤：没有第二个执行器）；
//   2. **零时钟**：一切时刻由调用方注入（时间注入夹具，判据④的可测性地基）；
//   3. **失败兜底**：哪一重没中、为什么没浮，[InspirationRound.note] 一行说明——
//      不许猜、不许编（M3 三件套口径）。
//
// 本件无判定权（523 三件套②）：不产生 RiskDecision/PROCEED/REJECT，不改档，不写权威账。
package com.example.zhengdao.baopu.subconscious

import com.example.zhengdao.baopu.MemoryRules
import com.example.zhengdao.baopu.inspect.PatrolScheduler
import com.example.zhengdao.baopu.kernel.BaopuKernel
import com.example.zhengdao.baopu.safety.DiaryCodec
import com.example.zhengdao.baopu.safety.DiaryEvent
import com.example.zhengdao.baopu.safety.DiaryKind
import com.example.zhengdao.core.TimeFormats
import java.io.File

/** 喂给执行器/选择器的一条候选（625 口径：摘要 ≤30 字＋稳定 id；id 即 L0 指针）。 */
internal data class InspirationCandidate(
    val id: String,
    val preview: String,
    val tags: List<String>,
    val createdAtMs: Long,
    val body: String,
) {
    companion object {
        /** 从 L0 记录适配（[InspirationStore] 的产出 ⇒ 执行器口径的候选）。 */
        fun of(r: InspirationRecord): InspirationCandidate = InspirationCandidate(
            id = r.id,
            preview = r.preview,
            tags = r.tags,
            createdAtMs = r.createdAtMs,
            body = r.body,
        )
    }
}

/**
 * 一轮唤醒的信号（全部注入；本层不读库、不读钟）。
 *
 * @param contextText 近期上下文文本（当前会话最近若干条消息拼的；空 ⇒ 上下文这一重不中）
 * @param lastMessageAtMs 该上下文里最新一条消息的时刻（0 ⇒ 同上）
 * @param latestUserText 用户**最近一句话**（当前焦点；「同任务/主题」的锚点）
 * @param nowMs 本轮时刻（时间注入）
 */
internal data class RecallSignals(
    val nowMs: Long,
    val contextText: String,
    val lastMessageAtMs: Long,
    val latestUserText: String,
)

/** 三重命中的逐重结论（哪一重没中要能说出来——失败兜底）。 */
internal data class TripleHit(
    val semantic: Boolean,
    val recentContext: Boolean,
    val userState: Boolean,
) {
    /** 缺一不浮：三重全中才算浮出候选。 */
    val all: Boolean get() = semantic && recentContext && userState

    /** 没中的那几重（人话，进 note）。 */
    fun misses(): List<String> = buildList {
        if (!semantic) add("语义不近邻")
        if (!recentContext) add("近期上下文缺")
        if (!userState) add("不同任务/主题")
    }
}

/** 三重命中的阈值与窗口（用户裁定原话的数字；改＝改判据，不许调用处私改）。 */
internal object InspirationGates {

    /** 词面重合的二元组下限（用户 2026-11-08 原话「词面≥2 个二元组」）。 */
    const val SEMANTIC_MIN_BIGRAMS = 2

    /** 近期上下文的窗口（用户原话「30 天内」；毫秒）。 */
    const val RECALL_WINDOW_DAYS = 30L

    /** 一天的毫秒数（窗口换算只在这一处乘）。 */
    const val DAY_MS = 86_400_000L

    /** 「同任务/主题」的最小重合数（标签∪预览 与用户最近一句话的词面重合）。 */
    const val TOPIC_MIN_HITS = 1

    /** 词面重合的二元组数（切词复用 [MemoryRules.tokensOf]，不另造第二把尺）。 */
    fun semanticBigrams(candidate: InspirationCandidate, contextText: String): Int =
        MemoryRules.tokensOf(candidate.body).intersect(MemoryRules.tokensOf(contextText)).size

    /** 重合 ①：语义近邻（词面 ≥2 个二元组）。 */
    fun semanticNear(candidate: InspirationCandidate, contextText: String): Boolean =
        semanticBigrams(candidate, contextText) >= SEMANTIC_MIN_BIGRAMS

    /** 重合 ②：近期上下文——上下文非空白，且最新一条消息在 30 天内。 */
    fun recentContext(signals: RecallSignals): Boolean {
        if (signals.contextText.isBlank()) return false
        if (signals.lastMessageAtMs <= 0L) return false
        return signals.nowMs - signals.lastMessageAtMs <= RECALL_WINDOW_DAYS * DAY_MS
    }

    /** 重合 ③：用户状态（同任务/主题）——标签∪预览 与用户最近一句话词面重合 ≥1。 */
    fun sameTopic(candidate: InspirationCandidate, latestUserText: String): Boolean {
        if (latestUserText.isBlank()) return false
        val cue = candidate.tags.flatMap { MemoryRules.tokensOf(it) }.toSet() +
            MemoryRules.tokensOf(candidate.preview)
        val focus = MemoryRules.tokensOf(latestUserText)
        return cue.intersect(focus).size >= TOPIC_MIN_HITS
    }

    /** 三重合一（modelPicked＝模型选中 ⇒ 语义那重直接成立——「选不造」的语义侧）。 */
    fun judge(candidate: InspirationCandidate, signals: RecallSignals, modelPicked: Boolean): TripleHit =
        TripleHit(
            semantic = modelPicked || semanticNear(candidate, signals.contextText),
            recentContext = recentContext(signals),
            userState = sameTopic(candidate, signals.latestUserText),
        )
}

/**
 * 模型选择口（统一口径⑥：模型只「选不造」）。
 *
 * 只喂 ≤[MAX_CANDIDATES] 条候选摘要；返回**候选内**的 id 列表。
 * 返回了候选之外的 id ⇒ 那条**整条丢弃**（625 口径：模型不许造）。
 * 模型不可用 ⇒ [InspirationSelection.Unavailable] ⇒ 判据层退回关键词规则版（不崩、不空转）。
 */
internal fun interface InspirationSelector {
    fun pick(candidates: List<InspirationCandidate>, contextText: String): InspirationSelection
}

/** 选择结果：模型选中的候选 id（必须是候选内 id），或「不可用」。 */
internal sealed interface InspirationSelection {
    data class Picked(val ids: List<String>) : InspirationSelection
    data object Unavailable : InspirationSelection
}

/**
 * 676 执行器的**来源缝**（适配层）。
 *
 * 676 的 SubconsciousRunner 派工单约定：候选来源注册进执行器，执行器低频跑回响、
 * 只出信号不执行动作。本单 676 未合入 ⇒ 先按约定形状立缝：执行器合入后把本缝
 * 注册进去即可（回归项，见交付报告）；在那之前生产走 [runInspirationRecall]
 * 的机会式调用点（打开建议页签算一轮——零后台调度，不是第二个执行器）。
 */
internal interface SubconsciousSource {
    /** 本来源的候选（灵感 L0 里现存的条目；缺引用/坏条目不许混进来）。 */
    fun inspirationCandidates(): List<InspirationCandidate>
}

/** 「灵感」来源的生产实现（534 只加这一类来源——本单的全部新增面）。 */
internal class InspirationSource(private val store: InspirationStore) : SubconsciousSource {

    /** 灵感 L0 里现存的候选（坏文件已在 scan 计数，不混进候选）。 */
    override fun inspirationCandidates(): List<InspirationCandidate> =
        store.scan().records.map(InspirationCandidate::of)

    /** 坏文件计数（0 ⇒ 忽略；>0 ⇒ 调用方要说出来——失败兜底口径）。 */
    fun brokenCount(): Int = store.scan().broken
}

/** 浮出的一行（「建议」页签灵感类行的数据源；id 稳定 ⇒ 点开对得上）。 */
internal data class InspirationRow(
    /** 页签行/卡的稳定键：`inspiration:<L0 id>`。 */
    val id: String,
    /** 浮出时刻（毫秒）。 */
    val atMs: Long,
    /** L0 指针（点开回原文；也是账行「依据哪条」的那一格）。 */
    val l0Id: String,
    /** 原文（卡面逐字展示——判据①「可点开」）。 */
    val body: String,
    /** 标签（可空）。 */
    val tags: List<String>,
    /** 灵感是哪天记的（副标题显示它，不是浮出时刻——记账要分清这两个时刻）。 */
    val recordedAtMs: Long,
) {
    /** 行标题＝原文前 30 字（625 口径的候选摘要形态）。 */
    val title: String get() = body.take(InspirationRecord.PREVIEW_CHARS)

    /** 副标题：依据哪条 L0＋哪天记的（可追）。 */
    val subtitle: String get() = "依据 L0 $l0Id · ${TimeFormats.dayStamp(recordedAtMs)} 记"
}

/** 「建议」页签灵感类行的进程内持有者（房型同 [com.example.zhengdao.baopu.audit.ClaimEvidenceRowStore]）。 */
internal object InspirationRowStore {
    @Volatile
    private var latest: List<InspirationRow> = emptyList()

    /** 一轮算完才调；页签读这一份（只读）。 */
    fun publish(rows: List<InspirationRow>) {
        latest = rows.toList()
    }

    fun latest(): List<InspirationRow> = latest

    /** 测试专用：谁污染谁还原。 */
    fun resetForTest() {
        latest = emptyList()
    }
}

/**
 * 点开注入信箱（判据③「不点不进上下文」的唯一机制）。
 *
 * **只有点开灵感卡这一个动作会 [offer]**（源码门禁扫全仓唯一调用点）；
 * 引擎下一轮拼提示词时 [drain]（取走即清，不重放——房型同 ImpactMailbox）。
 * 不点 ⇒ 信箱永远空 ⇒ 拼出来的提示词与没有本功能时逐字节相同。
 */
internal object InspirationMailbox {

    private var pending: String? = null

    /** 放进信箱（点开那一次；覆盖式——同时只带最新一条）。 */
    fun offer(text: String) {
        synchronized(this) { pending = text }
    }

    /** 取走（没有 ⇒ 空列表；有 ⇒ 一条，且立刻清空）。 */
    fun drain(): List<String> = synchronized(this) {
        val t = pending
        pending = null
        if (t.isNullOrBlank()) emptyList() else listOf(t)
    }

    fun clear() = synchronized(this) { pending = null }
}

/**
 * 本单自己的账：`<工作区>/产物/灵感闪回.jsonl`。
 *
 * 行形状＝起居注同形 ACTION 行（复用 [DiaryCodec]／[DiaryKind.ACTION]，635 同款房型）。
 * 为什么不直接写起居注：469 判据「值守期行数==动作数」的唯一写口在 GuardPipeline.finish，
 * 多开一个写口起居注就对不上数 ⇒ 本单落自己的账，形状一致、语义独立；676 合入后
 * 其执行器统一写起居注 ACTION（回归项），本账继续当 534 的明细账。
 *
 * 行 kinds（why 里的状态格）：浮出／点开／忽略。全部带 L0 id（「依据哪条账」可查）。
 */
internal object InspirationLedger {

    /** 认领自己行的 why 前缀（不误吃起居注/别的账的 ACTION 行）。 */
    const val WHY = "534 灵感闪回"

    const val FILE_NAME = "灵感闪回.jsonl"

    /** 忽略的抑制窗（625 口径「忽略 24h 不再出现」）。 */
    const val IGNORE_SUPPRESS_MS: Long = 24L * 60 * 60 * 1000

    /** 落点：`<工作区>/产物/灵感闪回.jsonl`（与 625 的 下意识.jsonl 同区，用户可见）。 */
    fun fileOf(workspace: File): File = File(File(workspace, BaopuKernel.Data.DIR_PRODUCTS), FILE_NAME)

    /** 追加一行（时刻由调用方给；失败返回 false 不抛——账没落上要说，不许静默）。 */
    fun append(file: File, event: DiaryEvent): Boolean = runCatching {
        file.parentFile?.mkdirs()
        file.appendText(DiaryCodec.encode(event) + "\n")
        true
    }.getOrDefault(false)

    /** 读回本账（坏行跳过；只认自己的 why 前缀）。 */
    fun read(file: File): List<DiaryEvent> = runCatching {
        if (!file.isFile) emptyList()
        else file.readLines().mapNotNull(DiaryCodec::parse).filter { it.why.startsWith(WHY) }
    }.getOrDefault(emptyList())

    /** 浮出一行（一轮浮 N 条 ⇒ N 行——「行数与该次建议一致」靠这个一一对应）。 */
    fun floatedEvent(atMs: Long, l0Id: String, title: String, subtitle: String) = DiaryEvent(
        kind = DiaryKind.ACTION,
        atMs = atMs,
        why = "$WHY·浮出·$l0Id",
        what = title,
        result = subtitle,
    )

    /** 点开一行（点开＝注入下一轮上下文——判据③的账面留痕）。 */
    fun openedEvent(atMs: Long, l0Id: String) = DiaryEvent(
        kind = DiaryKind.ACTION,
        atMs = atMs,
        why = "$WHY·点开·$l0Id",
        what = "原文已放行注入下一轮上下文（一次）",
        result = l0Id,
    )

    /** 忽略一行（这条 24h 内不再浮）。 */
    fun ignoredEvent(atMs: Long, l0Id: String) = DiaryEvent(
        kind = DiaryKind.ACTION,
        atMs = atMs,
        why = "$WHY·忽略·$l0Id",
        what = "用户忽略",
        result = "24h 内不再浮",
    )

    /** 最近一次浮出在哪个「天桶」（≤1 次/天的判据读数；桶口径复用 PatrolScheduler）。 */
    fun lastFloatedDay(events: List<DiaryEvent>): Long? = events
        .filter { it.why.contains("·浮出·") }
        .maxOfOrNull { PatrolScheduler.dayBucket(it.atMs) }

    /** 24h 内被忽略的候选 id（这批条目暂不浮——「不再出现」的读侧）。 */
    fun suppressedIds(events: List<DiaryEvent>, nowMs: Long): Set<String> = events
        .filter { it.why.contains("·忽略·") && nowMs - it.atMs <= IGNORE_SUPPRESS_MS }
        .map { it.why.substringAfterLast("·") }
        .toSet()
}

/** 一轮唤醒的产出（rows 至多 1 条——「每天 ≤1 次」的浮出口；note 说明为什么没浮）。 */
internal data class InspirationRound(
    val rows: List<InspirationRow>,
    val note: String,
    /** 选择器模式：「模型」＝模型选中；「规则」＝关键词退回（统一口径⑥要标注低置信来源）。 */
    val selectorMode: String,
)

/** 一轮至多喂给选择器多少候选（统一口径⑥：≤8 条）。 */
internal const val MAX_CANDIDATES = 8

/**
 * 一轮灵感唤醒（**纯判据**：候选＋信号＋选择器＋本账 ⇒ 至多一行浮出）。
 *
 * 顺序：≤1 次/天的日桶闸 → 忽略抑制 → 选择器（模型/退回）→ 三重命中逐条判 →
 * 取最优 1 条。全空 ⇒ rows 空＋note 说出原因（不猜、不编）。
 *
 * @param ledgerEvents 本账现读（[InspirationLedger.read]）——日桶与抑制都从它来
 */
internal fun runInspirationRecall(
    candidates: List<InspirationCandidate>,
    signals: RecallSignals,
    selector: InspirationSelector,
    ledgerEvents: List<DiaryEvent>,
): InspirationRound {
    val day = PatrolScheduler.dayBucket(signals.nowMs)
    if (InspirationLedger.lastFloatedDay(ledgerEvents) == day) {
        return InspirationRound(emptyList(), "今天已浮过（每天 ≤1 次），这轮不浮", "未调用")
    }
    val pool = candidates
        .filterNot { it.id in InspirationLedger.suppressedIds(ledgerEvents, signals.nowMs) }
        .take(MAX_CANDIDATES)
    if (pool.isEmpty()) return InspirationRound(emptyList(), "没有可浮的候选（L0 空或都在忽略窗内）", "未调用")
    val picked = pickedIds(selector, pool, signals.contextText)
    val hits = pool.mapNotNull { c ->
        val hit = InspirationGates.judge(c, signals, c.id in picked)
        if (hit.all) c else null
    }
    if (hits.isEmpty()) {
        return InspirationRound(emptyList(), missNote(pool, signals, picked), modeOf(picked))
    }
    // 取最优一条：模型选中的在前 ⇒ 词面重合多的在前 ⇒ 记得越新的在前（同输入同输出，不抖）。
    val best = hits.minWithOrNull(
        compareByDescending<InspirationCandidate> { it.id in picked }
            .thenByDescending { InspirationGates.semanticBigrams(it, signals.contextText) }
            .thenByDescending { it.createdAtMs }
            .thenBy { it.id },
    )!!
    return InspirationRound(
        rows = listOf(
            InspirationRow(
                id = "inspiration:${best.id}",
                atMs = signals.nowMs,
                l0Id = best.id,
                body = best.body,
                tags = best.tags,
                recordedAtMs = best.createdAtMs,
            ),
        ),
        note = "浮出 ${best.id}（${modeOf(picked)}）",
        selectorMode = modeOf(picked),
    )
}

/** 模型选中且**在候选内**的 id（返回候选外 id ⇒ 整条丢弃——625 口径「不许造」）。 */
private fun pickedIds(selector: InspirationSelector, pool: List<InspirationCandidate>, context: String): Set<String> {
    val ids = when (val s = selector.pick(pool, context)) {
        is InspirationSelection.Picked -> s.ids.toSet()
        InspirationSelection.Unavailable -> return emptySet()
    }
    val known = pool.map { it.id }.toSet()
    return ids.intersect(known)
}

/** 选择器模式的标注（「模型」/「规则」——规则版即「低置信·规则」的账面写法）。 */
private fun modeOf(picked: Set<String>): String = if (picked.isEmpty()) "规则" else "模型"

/** 没浮的原因行（失败兜底：哪一重没中要能说出来）。 */
private fun missNote(pool: List<InspirationCandidate>, signals: RecallSignals, picked: Set<String>): String {
    val recent = if (InspirationGates.recentContext(signals)) "中" else "缺"
    val misses = pool.mapNotNull { c ->
        val hit = InspirationGates.judge(c, signals, c.id in picked)
        if (hit.all) null else "${c.id}：${hit.misses().joinToString("＋")}"
    }
    return "没浮（近期上下文$recent；${modeOf(picked)}）：" + misses.joinToString("；")
}
