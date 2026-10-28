// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单第二百七十四批（Opinion 接进真链路），三件活：
//   W1 分类器结论 → Opinion：`source=CLASSIFIER`；`lawRef` 三条映射——未分类⇒L7、
//      hardDeny⇒L8、越界⇒L9（其余结论**不产意见**，它们走 173 的卡，不是"意见"）。
//   W2 送调解器做预算判定：每轮 ≤1 张、每日御案 ≤3 张；`critical＋L21` 只有**带理由**
//      才允许越预算（理由原样进账）。
//   W3 结论落账可回放：每一条都记「该弹／延后／不弹 ＋ 为什么」，**不设时间窗**
//      （30 天后仍查得到，与 [com.example.zhengdao.baopu.inspect.OpinionVotes] 的
//      30 天投票窗无关——那是"降敏"的窗，不是"查账"的窗）。
//
// 三条红线（逐条守）：
//   1. **不改分类器判定**：本文件只读 [RiskVerdict]，一个字段都不回写；调用点在
//      `GuardPipeline.prepare` 的 ②③ 段之后，返回值与判定分支逐字不动。
//   2. **不改 208**：`DecisionLedger.kt` 一个字节不动，也不往它里面塞新 kind（它的
//      kind 是闭集，加一项就是改它）。本文件照 `ShadowLedger` 的房型另起一本
//      `意见判定.jsonl`，落**同一棵目录**（`抱朴记忆/`，取自 208 已装配的落点）。
//   3. **不接 UI 弹卡**：本批只判「该不该弹」并落账；「压下的进队列」是本批的交付物，
//      真的把队列画成卡是下一批的事（[pending] 就是那道接口）。
//
// 为什么"轮"读的是 258 的用户轮而不是自己数：`prepare` 拿不到引擎的 step（本批租约
// 不含 `RunEngine`），而 258 已经在**每轮用户输入**处留了唯一接线点（`MediatorWiring`），
// 那就是这个 App 里"一轮"的既有定义。本文件**只读** `turns()`，不写它的状态。
// 代价写在报告里：非聊天路径若该计数不前进 ⇒ 所有意见落在同一轮，只会弹一张、其余进
// 队列——方向是"宁可少打扰"，且账上 `round` 列照实记，回放时一眼看得出。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.MediatorWiring
import com.example.zhengdao.baopu.RiskCode
import com.example.zhengdao.baopu.RiskVerdict
import com.example.zhengdao.baopu.inspect.ActionCode
import com.example.zhengdao.baopu.inspect.Budget
import com.example.zhengdao.baopu.inspect.LawRef
import com.example.zhengdao.baopu.inspect.Opinion
import com.example.zhengdao.baopu.inspect.OpinionAdapter
import com.example.zhengdao.baopu.inspect.Severity
import com.example.zhengdao.baopu.inspect.Source
import com.example.zhengdao.core.RunLog
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

/** 预算判定的三种结论（W3 的"该弹/延后/不弹"就是这三个字面量）。 */
internal enum class OpinionDecision(val wire: String, val cn: String) {
    /** 该弹：这一刻弹出来是值得的（本轮第一张、今日御案未满）。 */
    POP("pop", "该弹"),

    /** 延后：这一刻不弹，但**进队列**（不许丢——负例点名）。 */
    DEFER("defer", "延后"),

    /** 不弹：这条根本不需要人看一眼（已被硬拒／无法可依）。 */
    SKIP("skip", "不弹"),
    ;

    companion object {
        /** 认不出就 null（自造值不渲染，也不静默当别的类——照 208/影子账的解析纪律）。 */
        fun ofWire(wire: String): OpinionDecision? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * "为什么"的**稳定机器键**（回放按它分组；人话在 [Ruling.detail]）。
 *
 * 为什么不用 detail 当判据：文案会改，键不许改——改键等于让老账本读不出"当时为什么"。
 */
internal enum class OpinionWhy(val wire: String) {
    /** 预算内，正常弹。 */
    BUDGET_OK("budget_ok"),

    /** 本轮已经弹过一张（每轮 ≤1）。 */
    ROUND_TAKEN("round_taken"),

    /** 今日御案已满（每日 ≤3）。 */
    DAY_QUOTA("day_quota"),

    /** critical＋L21 带理由，越预算放行（理由原样进账）。 */
    EXEMPT_CRITICAL("exempt_critical"),

    /** critical＋L21 **没带理由**：不许越预算（负例本体）。 */
    EXEMPT_NO_REASON("exempt_no_reason"),

    /** 分类器已经硬拒了这一步，再问一遍等于把红线交给用户点。 */
    ALREADY_REFUSED("already_refused"),

    /** 无法可依且不致命：只记档，不打扰。 */
    NO_LAW("no_law"),
    ;

    companion object {
        fun ofWire(wire: String): OpinionWhy? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * 一条判定（W3 的一行账）。
 *
 * [royalToday] 是**判定那一刻**当日已弹张数（含本条）：回放时能证明"第 4 张为什么被压"，
 * 而不是只看到一个孤零零的 `defer`。
 */
internal data class Ruling(
    val id: String,
    val opinion: Opinion,
    val decision: OpinionDecision,
    val why: OpinionWhy,
    /** 一句人话（≤40 字，单行）：账本给人看的那一列。 */
    val detail: String,
    val round: Int,
    val royalToday: Int,
    val atMs: Long,
    /** 触发这条意见的工具名（没有就是空串——例如系统级观察）。 */
    val tool: String,
    /** 豁免理由（只有 [OpinionWhy.EXEMPT_CRITICAL] 有值）。 */
    val exemptReason: String? = null,
) {
    /** 当日（本地时区）。 */
    val day: String get() = OpinionWiring.dayOf(atMs).toString()

    fun toJson(): JSONObject = JSONObject()
        .put("v", VERSION)
        .put("id", id)
        .put("at", atMs)
        .put("day", day)
        .put("round", round)
        .put("src", opinion.source.name)
        .put("sev", opinion.severity.name)
        .put("law", opinion.lawRef.name)
        .put("act", opinion.action.name)
        .put("gist", opinion.gist)
        .put("tool", tool)
        .put("decision", decision.wire)
        .put("why", why.wire)
        .put("detail", detail)
        .put("royal", royalToday)
        .put("exempt", exemptReason ?: "")
        .put("ev", JSONArray(opinion.evidence))

    companion object {
        /** 行格式版本（改形状才 +1；加字段不算）。 */
        const val VERSION = 1

        fun parse(raw: String): Ruling? {
            val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            val id = o.optString("id")
            val decision = OpinionDecision.ofWire(o.optString("decision")) ?: return null
            val why = OpinionWhy.ofWire(o.optString("why")) ?: return null
            val source = Source.entries.firstOrNull { it.name == o.optString("src") } ?: return null
            val severity = Severity.entries.firstOrNull { it.name == o.optString("sev") } ?: return null
            val law = LawRef.entries.firstOrNull { it.name == o.optString("law") } ?: return null
            val action = ActionCode.entries.firstOrNull { it.name == o.optString("act") } ?: return null
            val evidence = (o.optJSONArray("ev") ?: JSONArray()).let { a ->
                (0 until a.length()).mapNotNull { i -> a.optString(i).ifEmpty { null } }
            }
            if (id.isEmpty() || evidence.isEmpty()) return null
            // 坏行（gist 超长、证据为空…）一律跳过：回放是读历史，不许被一行脏数据整本读不出
            return runCatching {
                Ruling(
                    id = id,
                    opinion = Opinion(source, severity, o.optString("gist"), action, law, evidence),
                    decision = decision,
                    why = why,
                    detail = o.optString("detail"),
                    round = o.optInt("round", 0),
                    royalToday = o.optInt("royal", 0),
                    atMs = o.optLong("at", 0L),
                    tool = o.optString("tool"),
                    exemptReason = o.optString("exempt").ifEmpty { null },
                )
            }.getOrNull()
        }
    }
}

/**
 * 判定本身（**纯函数**：不读时钟、不碰盘、不改入参）。
 *
 * 拆出来是为了让"为什么"可以被单独断言：预算规则混进单例状态里，就只能靠日志猜。
 */
internal object OpinionJudge {

    data class Verdict(
        val decision: OpinionDecision,
        val why: OpinionWhy,
        val detail: String,
        val exemptReason: String? = null,
    )

    /**
     * 判一条。
     *
     * @param royalToday 当日已弹张数（判定前的值）
     * @param roundUsed 本轮是否已经弹过一张
     * @param alreadyRefused 分类器是否已经硬拒了这一步（硬拒不是"问"，不需要再打扰）
     * @param exemptionReason 调用方给的豁免理由（只有 critical＋L21 用得上）
     */
    fun judge(
        opinion: Opinion,
        royalToday: Int,
        roundUsed: Boolean,
        budget: Budget = Budget(),
        alreadyRefused: Boolean = false,
        exemptionReason: String? = null,
    ): Verdict {
        // ① 已经被硬拒：这一步不会发生了，再弹一张"要不要允许"是把红线交给用户点
        if (alreadyRefused) {
            return Verdict(OpinionDecision.SKIP, OpinionWhy.ALREADY_REFUSED, "分类器已硬拒，这一步不会发生")
        }
        // ② 无法可依且不致命：只记档（不是"吞掉"——账上有它，只是不打扰）
        if (opinion.lawRef == LawRef.NONE && opinion.severity != Severity.CRITICAL) {
            return Verdict(OpinionDecision.SKIP, OpinionWhy.NO_LAW, "无法可依且不致命：记档不打扰")
        }
        // ③ 预算内 ⇒ 该弹
        val dayFull = royalToday >= budget.perDayRoyalMax
        if (!roundUsed && !dayFull) {
            return Verdict(OpinionDecision.POP, OpinionWhy.BUDGET_OK, "本轮第一张且今日御案未满")
        }
        // ④ 越预算：只有 critical＋L21 且**带理由**才放行（[Budget.exemptionFor] 是唯一判据）
        val reason = exemptionReason?.trim().orEmpty()
        if (reason.isNotEmpty()) {
            budget.exemptionFor(opinion, reason)?.let {
                return Verdict(
                    OpinionDecision.POP,
                    OpinionWhy.EXEMPT_CRITICAL,
                    "急案豁免越预算：$reason",
                    exemptReason = reason,
                )
            }
        }
        // ⑤ 想越预算却没资格：critical＋L21 缺理由要**单独留一个键**（负例的判据本体）
        if (opinion.severity == Severity.CRITICAL && opinion.lawRef == LawRef.L21) {
            return Verdict(
                OpinionDecision.DEFER,
                OpinionWhy.EXEMPT_NO_REASON,
                "急案没有理由，不许越预算：进队列",
            )
        }
        return if (roundUsed) {
            Verdict(OpinionDecision.DEFER, OpinionWhy.ROUND_TAKEN, "本轮已弹过一张：进队列")
        } else {
            Verdict(OpinionDecision.DEFER, OpinionWhy.DAY_QUOTA, "今日御案已满（${budget.perDayRoyalMax} 张）：进队列")
        }
    }
}

/**
 * 接线层（进程内单例）：分类器结论进、判定与账本出。
 *
 * 为什么是 `object`：轮/日计数、队列都是"这几步顺不顺"的进程内状态，与 258 的
 * `MediatorWiring` 同一口径（重启就忘）；**账本是例外**——它落盘，因为"30 天后仍可查"
 * 只能靠盘，而且重启后当日御案数要从账本种子化（否则重启一次就白送 3 张）。
 */
internal object OpinionWiring {

    /** 账本文件名（与 208/影子账同一棵目录：`抱朴记忆/`，用户可看可备份）。 */
    const val FILE_NAME = "意见判定.jsonl"

    /** 内存里最多留多少条（回放以盘上那份为准；这条只是"没装配落点"时的兜底）。 */
    private const val MEMORY_CAP = 200

    /** 队列上限（只封住病态增长；**不丢**——到顶时记一条日志，仍然进账）。 */
    private const val PENDING_CAP = 64

    /** 轮的来源（生产＝258 的用户轮；测试可换）。 */
    @Volatile
    private var roundSource: () -> Int = { MediatorWiring.turns() }

    private var roundKey: Int = Int.MIN_VALUE
    private var roundPops: Int = 0

    private var day: LocalDate? = null
    private var royalToday: Int = 0

    private var seq: Int = 0

    /** 压下的那些（W2 的"进队列"；下一批画卡就从这里取）。 */
    private val pending = ArrayDeque<Ruling>()

    /** 本轮判过的（同事件只判一次：与 `OpinionMerger.sameEvent` 同一口径）。 */
    private val seen = HashMap<String, Ruling>()

    /** 本进程写过的（没装配落点时的兜底；装配了就与盘上那份合并去重）。 */
    private val memory = ArrayList<Ruling>()

    // ── W1 分类器结论 → Opinion ──────────────────────────────────────────

    /**
     * 把分类器的结论包成一条意见；**没有"要说的结论"就返回 null**。
     *
     * 三条映射是派工单点名的，别的一个都不加：未分类⇒L7、hardDeny⇒L8、越界⇒L9。
     * 其余判定（R2/R3 必问、批量、覆盖…）走 173 的确认卡，它们不是"意见"——
     * 把它们也包成意见，等于让调解器替 173 决定要不要弹，那是抢判。
     */
    fun opinionOf(call: ToolCall, verdict: RiskVerdict): Opinion? {
        val law = when {
            verdict.hardDeny -> LawRef.L8
            verdict.unclassified -> LawRef.L7
            RiskCode.PATH_OUTSIDE_WORKSPACE in verdict.reasonCodes -> LawRef.L9
            else -> return null
        }
        // 硬拒是红线（原件区/密码框/私有 URL）；未分类与越界是"缺口"，不是"危险"
        val severity = if (verdict.hardDeny) Severity.CRITICAL else Severity.MAJOR
        val gist = when (law) {
            LawRef.L8 -> "硬拒"
            LawRef.L7 -> "未分类"
            else -> "越界访问"
        }
        val action = if (verdict.hardDeny) ActionCode.SUGGEST_PAUSE else ActionCode.REVIEW
        // 证据：稳定机器键 ＋ 工具名 ＋ 矩阵版本（"当时是哪一版表"必须能回放）
        val evidence = listOf(
            "risk:${verdict.reasonCode.id}",
            "tool:${call.name}",
            "matrix:v${verdict.matrixVersion}",
        )
        return OpinionAdapter.CLASSIFIER.adapt(severity, gist, action, law, evidence)
    }

    /**
     * ② 段那条"越界要问一次"（`Guarded.Ask`）包成 L9 意见。
     *
     * 为什么它单独一个入口：越界在**②段就返回了**（根本不进 ③ 的矩阵），
     * 只挂 ③ 的话，最常见的越界反而一条意见都不产。
     */
    fun outsideOpinion(call: ToolCall): Opinion =
        OpinionAdapter.CLASSIFIER.adapt(
            Severity.MAJOR,
            "越界访问",
            ActionCode.REVIEW,
            LawRef.L9,
            listOf("gate:path_guard", "tool:${call.name}"),
        )

    // ── W1＋W2 的接线口（GuardPipeline 只调这两个） ─────────────────────

    /**
     * ③ 段判定之后调（**只观察**）：返回这一条被判成什么，`null`＝这一步没有意见。
     *
     * 不因"包意见/落账"失败而抛：构造包了 runCatching，落账失败只记一行日志——
     * 接线层出错不许把工具调用带死（"记录是留痕不是闸门"，208 同一口径）。
     */
    @Synchronized
    fun onVerdict(call: ToolCall, verdict: RiskVerdict, now: Long = System.currentTimeMillis()): Ruling? {
        val opinion = runCatching { opinionOf(call, verdict) }.getOrNull() ?: return null
        return submit(opinion, call.name, now, alreadyRefused = verdict.hardDeny)
    }

    /** ② 段越界之后调（**只观察**）。 */
    @Synchronized
    fun onOutsidePath(call: ToolCall, now: Long = System.currentTimeMillis()): Ruling =
        submit(outsideOpinion(call), call.name, now, alreadyRefused = false)

    /**
     * 通用入口：任何一器的意见都能走这里（四器统一形状之后，调解器只认 [Opinion]）。
     *
     * 分类器是**第一条真链路**（W1）；巡按/审计/影子接进来时用同一个口，
     * 不另起一套预算状态——两套计数等于两套预算。
     *
     * @param exemptionReason 只有 `critical＋L21` 用得上；空/blank ⇒ 不许越预算
     */
    @Synchronized
    fun onOpinion(
        opinion: Opinion,
        tool: String = "",
        now: Long = System.currentTimeMillis(),
        exemptionReason: String? = null,
        alreadyRefused: Boolean = false,
    ): Ruling = submit(opinion, tool, now, alreadyRefused, exemptionReason)

    // ── W2 预算判定 ＋ W3 落账 ──────────────────────────────────────────

    @Synchronized
    private fun submit(
        opinion: Opinion,
        tool: String,
        now: Long,
        alreadyRefused: Boolean,
        exemptionReason: String? = null,
    ): Ruling {
        val round = runCatching { roundSource() }.getOrDefault(0)
        if (round != roundKey) {
            roundKey = round
            roundPops = 0
            seen.clear()
        }
        // 同轮同事件只判一次（同一条意见被两个工具撞出来不该记两笔）
        val key = "${opinion.source.name}:${opinion.gist}:$tool:${opinion.evidence.first()}"
        seen[key]?.let { return it }

        seedDay(now)
        val verdict = OpinionJudge.judge(
            opinion = opinion,
            royalToday = royalToday,
            roundUsed = roundPops > 0,
            alreadyRefused = alreadyRefused,
            exemptionReason = exemptionReason,
        )
        seq += 1
        val ruling = Ruling(
            id = "op:$now:$round:$seq",
            opinion = opinion,
            decision = verdict.decision,
            why = verdict.why,
            detail = verdict.detail,
            round = round,
            // 账上记"含本条"的张数：第 4 张被压时，这一列正好是 4（回放能自证）
            royalToday = if (verdict.decision == OpinionDecision.POP) royalToday + 1 else royalToday,
            atMs = now,
            tool = tool,
            exemptReason = verdict.exemptReason,
        )
        if (verdict.decision == OpinionDecision.POP) {
            roundPops += 1
            royalToday += 1
        }
        if (verdict.decision == OpinionDecision.DEFER) {
            if (pending.size >= PENDING_CAP) {
                RunLog.log("抱朴意见：队列已满 ${pending.size} 条，仍照记不丢（${ruling.id}）")
            }
            pending.addLast(ruling)
        }
        seen[key] = ruling
        record(ruling)
        RunLog.log(logLine(ruling))
        return ruling
    }

    /** 当日御案数：换日就**从账本种子化**（重启不许白送 3 张）。 */
    private fun seedDay(now: Long) {
        val today = dayOf(now)
        if (today == day) return
        day = today
        royalToday = replay().count { it.decision == OpinionDecision.POP && dayOf(it.atMs) == today }
    }

    /**
     * 落账（W3）。
     *
     * 落点＝208 已装配的那棵目录（`抱朴记忆/`），**不是** 208 那本账：
     * 它记的是"替你做过什么"，这本记的是"该不该打扰你"，两件事混一本会让抽检的分母算错。
     * 没装配落点（纯 JVM 单测）⇒ 只进内存，回放照样查得到。
     */
    private fun record(ruling: Ruling) {
        memory.add(ruling)
        if (memory.size > MEMORY_CAP) memory.removeAt(0)
        val root = root() ?: return
        runCatching {
            val f = fileIn(root)
            f.parentFile?.mkdirs()
            f.appendText(ruling.toJson().toString() + "\n", Charsets.UTF_8)
        }.onFailure {
            RunLog.log("抱朴意见：账没落上（${ruling.id}）——${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /**
     * 回放（**不设时间窗**）。
     *
     * 为什么没有 `since` 参数：30 天后仍可查是判据本体。`OpinionVotes.WINDOW_MS`
     * 那个 30 天窗是"降敏投票"的窗，拿它筛账就等于"老意见自动作废"，与本批要求相反。
     */
    @Synchronized
    fun replay(limit: Int = 0): List<Ruling> {
        val fromFile = root()?.let { r ->
            runCatching {
                val f = fileIn(r)
                if (!f.isFile) emptyList() else f.readLines(Charsets.UTF_8).mapNotNull(Ruling::parse)
            }.getOrDefault(emptyList())
        }.orEmpty()
        val all = (fromFile + memory).distinctBy { it.id }.sortedByDescending { it.atMs }
        return if (limit > 0) all.take(limit) else all
    }

    /** 压下的那些（**在队列里，没被吞**；下一批画卡从这里取）。 */
    @Synchronized
    fun pending(): List<Ruling> = pending.toList()

    /** 当前轮与当日已弹张数（日志/测试用）。 */
    @Synchronized
    fun state(): Pair<Int, Int> = roundKey to royalToday

    /** 换轮的来源（测试注入；生产默认＝258 的用户轮）。 */
    fun installRoundSource(source: () -> Int) {
        roundSource = source
    }

    /** 日志那一行（抽成纯函数：格式也是判据，埋在 RunLog 里就抓不到）。 */
    internal fun logLine(ruling: Ruling): String =
        "抱朴意见：${ruling.decision.cn}（${ruling.why.wire}）${ruling.opinion.gist}" +
            "｜轮=${ruling.round} 今日御案=${ruling.royalToday}｜${ruling.detail}"

    /** 本地时区的"哪一天"（每日御案按它切）。 */
    internal fun dayOf(now: Long): LocalDate =
        Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()

    /** 账本落点（与 208 同一棵目录；没装配 ⇒ null）。 */
    private fun root(): File? = DecisionLedger.installedRoot()

    /** 落点文件。 */
    fun fileIn(globalRoot: File): File = File(globalRoot, FILE_NAME)

    /** 清空（测试专用：单例状态会让用例互相污染，谁用谁还）。 */
    @Synchronized
    fun resetForTest() {
        roundKey = Int.MIN_VALUE
        roundPops = 0
        day = null
        royalToday = 0
        seq = 0
        pending.clear()
        seen.clear()
        memory.clear()
        roundSource = { MediatorWiring.turns() }
    }
}
