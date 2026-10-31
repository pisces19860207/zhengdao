// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百八十三批 · 学习器接进调解器（派工单 §1 的后三件）：
//   ② **学习只改"什么时候问"**（阈值/次序），不许改"什么必须问"（法条）——
//      撞上法条 ⇒ 学习结果**作废**并**记一次违规**（判据②）；
//   ③ **信号只来自用户**：批/驳/搁置/关掉卡（中立 0）/事后重复问——
//      **四器结论（CLASSIFIER/PATROL/AUDIT/SHADOW）与自身判断没有当标签的入口**；
//   ④ **自决单**（L33）：每条"不打扰"都落一条理由进追认队列，30 天可回放。
//
// 挡在哪：调解器判"弹不弹"走 `OpinionWiring.onOpinion`（274/277 的预算判定）；
// 本文件是它前面的一闸 [LearnWiring.onOpinion]——学习判"该压"的建议型意见到不了预算
// （不弹、不打扰、落自决单），法条绑定的意见学习**无权过问**，照原路送预算。
// 真正把这一闸接进 `GuardPipeline` 是下一批的一行调用（本批租约只有三个新文件，不碰旧文件）。
//
// 持久化：判决经验账 [JudgmentExperienceStore] 是 **280 预留的口**——280 的"判决经验持久化
// 到工作区"（落 `Download/证道/` 下的账）合入前用内存桩跑通链路；签名只有 append/readAll，
// 280 换真实现时本文件零改动。
//
// 四条纪律（照 258/274/277 的房型）：
//   1. **不落盘**（本批）：内存桩不碰文件——落盘是 280 的活，本批抢了就是两套账；
//   2. **不设时间窗的是账，设窗的是阈值**：经验账 30 天后仍可回放（判据④）；
//      但学出来的"关 3 次"只在 30 天窗内数信号——窗外的不算数，账上的不消失；
//   3. **不改判据**：预算、豁免、严重度全在 `OpinionJudge`/`Budget` 里，本文件只搬运不重写；
//   4. **写失败不抛**：账是留痕不是闸门，落账失败记一行日志（208/影子账同一口径）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.DecisionActor
import com.example.zhengdao.baopu.DecisionDraft
import com.example.zhengdao.baopu.DecisionKind
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.inspect.LawRef
import com.example.zhengdao.baopu.inspect.Opinion
import com.example.zhengdao.baopu.inspect.Severity
import com.example.zhengdao.baopu.policy.VerdictExperienceRuntime
import com.example.zhengdao.core.RunLog

// ── 判决经验账（280 预留的持久化口）────────────────────────────────────────────

/**
 * 判决经验持久化口（**280 预留**）：280 的"判决经验持久化到工作区"落地前，只有内存桩。
 *
 * 为什么只有两个方法：本批对账的全部需求就是"写一行、读全本"（阈值从全本算，回放也读全本）。
 * 280 落 `Download/证道/` 的账后实现换掉，接口不动——签名是这批留的，改签名就是改法。
 */
internal interface JudgmentExperienceStore {
    fun append(row: ExperienceRow)

    /** 全本读（**不设时间窗**：回放不许拿"太久没看"当过滤条件——判据④的本体）。 */
    fun readAll(): List<ExperienceRow>
}

/** 经验账一行的种类（回放按它分组；机器键见 [ExperienceRow.key]）。 */
internal enum class ExperienceKind(val wire: String) {
    /** 用户信号（批/驳/搁置/关卡/重问）——**标签只有这一种**。 */
    SIGNAL("signal"),

    /** 学习器放行、调解器判过的观察行（回放时用户的信号靠它对上卡）。 */
    ASK("ask"),

    /** 学习判的"不打扰"。 */
    SUPPRESS("suppress"),

    /** 学习撞法条记的违规（判据②）。 */
    VIOLATION("violation"),

    /** 反思归因（判错的"为什么"，[ReflectAttribution] 落的）。 */
    ATTRIBUTION("attribution"),

    /** 事后重复问：压错了的回执（撤销压制）。 */
    REASK("reask"),

    /**
     * 第二百九十八批：两级裁决里**草稿被法典判据否掉**（判据③要求记的那一笔）。
     *
     * 与 VIOLATION 分开记：283 的那一笔是「学习想压、撞上法条」；这一笔是「表里学到的
     * 结论与法典判据不一致」——同一件事在接线层的两站，分不开就没法各修各的阈值。
     */
    DRAFT_VETO("draft_veto"),
}

/**
 * 经验账的一行。
 *
 * @param cardClass 卡的"类"（意见=gist；反思=`反思:$dedupKey`）——学习按类调"何时问"
 * @param key 稳定机器键（`signal:dismiss` 这类；文案会改，键不许改）
 * @param reason 一句人话（**缺理由的行不落账**——L33 同一口径，没有理由的经验没有信息）
 * @param evidence ≥1 条引用（意见 id／档案行号）；空 ⇒ 不落账——**没有落点的标签是编的**
 */
internal data class ExperienceRow(
    val id: String,
    val atMs: Long,
    val kind: ExperienceKind,
    val cardClass: String,
    val key: String,
    val reason: String,
    val evidence: List<String>,
)

/** 内存桩（280 合入前的实现）：进程内单列表，上限兜底防病态增长；重启即空是**本批的已知限制**。 */
internal class InMemoryJudgmentExperienceStore(private val cap: Int = 500) : JudgmentExperienceStore {

    private val rows = ArrayList<ExperienceRow>()

    override fun append(row: ExperienceRow) {
        rows += row
        if (rows.size > cap) {
            RunLog.log("抱朴经验账：内存桩到顶 ${rows.size} 行，丢最旧一行（280 落盘后无此限制）")
            rows.removeAt(0)
        }
    }

    override fun readAll(): List<ExperienceRow> = rows.toList()
}

// ── 用户信号（标签的唯一入口）──────────────────────────────────────────────────

/**
 * 用户对一张卡表了态（派工单点名的五个，闭集）。
 *
 * **四器结论与自身判断没有对应的枚举值**——这是负例"拿四器结论当标签"的第一道墙：
 * 机器侧的一切结论走 [LearnWiring.onOpinion]（只产生观察行，永远产生不了 [ExperienceKind.SIGNAL]）。
 *
 * @param vote 投票值：只有"批"是正票（学习调"次序"用）；关卡是**中立 0**（不是说卡错，是说别现在问）
 */
internal enum class UserSignal(val wire: String, val cn: String, val vote: Int) {
    APPROVE("approve", "批", 1),
    REJECT("reject", "驳", -1),
    DEFER("defer", "搁置", 0),

    /** 关掉卡：对内容中立 0，但计"关"——学的是"什么时候别问"，不是"这卡错了"。 */
    DISMISS("dismiss", "关卡", 0),

    /** 事后重复问：压错了的回执——撤销压制，不投票。 */
    REASK("reask", "重问", 0),
    ;

    companion object {
        /** 认不出就 null（自造值不进账，也不静默当别的类）。 */
        fun ofWire(wire: String): UserSignal? = entries.firstOrNull { it.wire == wire }

        /**
         * 御案三裁 ⇒ 学习五信号（第二百九十八批接线）。映射写在这里而不是 UI 里：
         * 「搁置」是"以后再说"，**不是"关"**——[LearnPolicy.stats] 只把关卡与驳回计进阈值，
         * 搁置要是一算成关，三次"明天再看"就能把一类永久压住，那是学歪了。
         */
        fun ofRoyal(verdict: RoyalVerdict): UserSignal = when (verdict) {
            RoyalVerdict.APPROVE -> APPROVE
            RoyalVerdict.REJECT -> REJECT
            RoyalVerdict.HOLD -> DEFER
        }
    }
}

// ── 学习判据（纯函数：零 IO、零时钟、零 Android）────────────────────────────────

internal object LearnPolicy {

    /** 学习窗（派工单原文「30 天批 N 次/关 M 次」）：窗外信号不参与阈值——账上照旧可回放。 */
    const val WINDOW_DAYS = 30L

    /** 学出来的阈值：同类 30 天内被关 ≥3 次 ⇒ 不弹（判据①）。改这个数＝改"何时问"，法条一个字没动。 */
    const val SUPPRESS_AFTER_CLOSES = 3

    /**
     * 法条点名的必问集（判据②的"什么必须问"——学习的禁区）：
     * · **L7 未分类**：fail-closed 必问（法典卷三原文「未分类 ⇒ R2 且必问」）；
     * · **L8 硬拒**：红线清单（原件区/密码框/私有 URL/批量…），绕过就是改法；
     * · **critical**：急案与真假冲突，L21 豁免那条路自己带理由，学习不插手。
     * 其余法号的意见（L9 越界这一类观察卡、L12 审计发现…）归 L33 的宰相自决权——
     * "弹卡"或"不弹、落自决单"都在授权内，学习调"何时问"的合法辖地就是这一带。
     */
    fun mustAsk(opinion: Opinion): Boolean =
        opinion.severity == Severity.CRITICAL || opinion.lawRef == LawRef.L7 || opinion.lawRef == LawRef.L8

    /** 一个类的信号读数（窗口内、最近一次重问之后的）。 */
    data class ClassStats(val closes: Int, val approves: Int, val rejects: Int, val lastReaskAtMs: Long?)

    /** 信号行 ⇒ 类读数。"关"＝关卡＋驳回（都是"别再弹"；搁置是"以后"，不计）；重问把之前的关清零重数。 */
    fun stats(rows: List<ExperienceRow>, cardClass: String, now: Long): ClassStats {
        val windowStart = now - WINDOW_DAYS * 24L * 3_600_000L
        val mine = rows.filter { it.cardClass == cardClass }
        val lastReask = mine.filter { it.kind == ExperienceKind.REASK }.maxOfOrNull { it.atMs }
        val signals = mine.filter {
            it.kind == ExperienceKind.SIGNAL && it.atMs >= windowStart && it.atMs <= now &&
                (lastReask == null || it.atMs > lastReask)
        }
        return ClassStats(
            closes = signals.count { it.key == "signal:dismiss" || it.key == "signal:reject" },
            approves = signals.count { it.key == "signal:approve" },
            rejects = signals.count { it.key == "signal:reject" },
            lastReaskAtMs = lastReask,
        )
    }

    /** 压不压的结论（含一句能落账的理由——判据①的"账上有 N 条理由"之外的这一条）。 */
    data class SuppressVerdict(val suppressed: Boolean, val closes: Int, val reason: String)

    /**
     * 该不该压这一类。真值条件：30 天窗内、最近一次重问之后，同类被关 ≥[SUPPRESS_AFTER_CLOSES] 次。
     *
     * **这只回答"学到了什么"**；它有没有资格压这一张，是 [LearnWiring.onOpinion] 里法条闸的事
     * （判据②的分工：判据层说想压，接线层判能不能压）。
     */
    fun shouldSuppress(rows: List<ExperienceRow>, cardClass: String, now: Long): SuppressVerdict {
        val s = stats(rows, cardClass, now)
        if (s.closes < SUPPRESS_AFTER_CLOSES) {
            return SuppressVerdict(false, s.closes, "")
        }
        return SuppressVerdict(
            suppressed = true,
            closes = s.closes,
            reason = "同类 ${LearnPolicy.WINDOW_DAYS} 天内已被关 ${s.closes} 次（每关一条理由都在账上）——学到的阈值：不弹",
        )
    }

    /**
     * 次序（学习的另一半）：压着的类根本不该出现在队列里（上游已拦）；
     * 余下的按「批过的类往前排 → 严重度高在前 → 早压下的先看」排。
     * 纯函数：调用方给读数，判据自己不碰账。
     */
    fun orderRulings(
        rulings: List<Ruling>,
        statsOf: (String) -> ClassStats,
    ): List<Ruling> = rulings.sortedWith(
        compareByDescending<Ruling> { statsOf(it.opinion.gist).approves }
            .thenByDescending { it.opinion.severity.rank }
            .thenBy { it.atMs },
    )
}

// ── 接线层（进程内单例，照 258/274 的房型）──────────────────────────────────────

internal object LearnWiring {

    /** 208 里本批违规行的**固定前缀**（kind 复用 `eval_verdict`——红线不动 `DecisionLedger.kt`，258 同一套挂牌纪律）。 */
    const val REASON_PREFIX = "learn:"

    /** 自决单 why 列的机器键（回放按它认出"这是学习器压的"）。 */
    const val SUPPRESS_WHY = "learn_suppress"

    /** 持久化口：283 交的是内存桩；298 起生产装 [JudgmentExperienceLedger]（换实现，接口一个字没动）。 */
    private var store: JudgmentExperienceStore = InMemoryJudgmentExperienceStore()

    /**
     * 换持久化实现（298 装配点调一次：`installStore(JudgmentExperienceLedger)`）。
     *
     * 装完立刻按新账折一次表——启动顺序是"先装账、再装表"，这一步让两者从第一刻就对得上。
     */
    fun installStore(next: JudgmentExperienceStore, now: Long = System.currentTimeMillis()) {
        store = next
        refreshTable(now, "install")
    }

    /**
     * 把账折成表并换指针（**只有会挪阈值的行落账后才调**：信号／重问／违规／草稿被否）。
     *
     * 观察行（ASK）不触发——每条意见都重建一张表，等于把"每判一次扫一遍全本"换个地方做。
     */
    private fun refreshTable(now: Long, source: String = "signal") {
        RunLog.log(VerdictExperienceRuntime.refresh(store.readAll(), now, source))
    }

    /** 轮来源（null＝进程级；照 `OpinionWiring.installRoundSource` 的房型，账上照实记来源不装轮号）。 */
    @Volatile
    private var roundSource: (() -> String)? = null

    private var seq = 0

    // ── 信号入口（标签的唯一来源）────────────────────────────────────────

    /**
     * 用户对一张卡表了态。**只该被 UI 的决定按钮调**——四器结论与自身判断没有入口。
     *
     * @param cardClass 类（意见=gist；反思卡=`反思:$dedupKey`，归因行里有现成的）
     * @param ref 用户动作的落点（意见判定 id／档案行锚）：空白 ⇒ 拒收——没有落点的标签是编的
     * @param note 用户留的原话理由；空 ⇒ 用类别句兜底（账上理由列不许空白，L33 同一口径）
     */
    @Synchronized
    fun onUserSignal(
        signal: UserSignal,
        cardClass: String,
        ref: String,
        now: Long = System.currentTimeMillis(),
        note: String = "",
    ): ExperienceRow? {
        if (cardClass.isBlank() || ref.isBlank()) {
            RunLog.log("抱朴学习器：信号缺类或缺落点，不收（$signal）")
            return null
        }
        val row = ExperienceRow(
            id = newId(now),
            atMs = now,
            kind = if (signal == UserSignal.REASK) ExperienceKind.REASK else ExperienceKind.SIGNAL,
            cardClass = cardClass.trim(),
            key = "signal:${signal.wire}",
            reason = note.trim().ifEmpty { defaultReason(signal) },
            evidence = listOf(ref.trim()),
        )
        store.append(row)
        // 298：用户的每一次表态都可能挪动"关到几次就不弹"——落账即折表（单写者，一次换指针）
        refreshTable(now, if (signal == UserSignal.REASK) "reask" else "signal")
        RunLog.log(logLine(row))
        return row
    }

    // ── 意见的学闸（调解器判"弹不弹"前面的学习一闸）──────────────────────

    /**
     * 学习器挡在 [OpinionWiring.onOpinion] 前面的一闸：**只改"什么时候问"，不改"什么必须问"**。
     *
     * · 法条点名的必问集（[LearnPolicy.mustAsk]：L7 未分类／L8 硬拒／critical）：
     *   学习**无权过问**。学出来的"该压"撞上它 ⇒ 学习结果作废＋记一次违规（判据②），
     *   意见原样送预算——弹不弹仍由法条与预算说了算。
     * · 其余（L33 宰相自决权辖地，含建议型）：学到"同类已关 3 次"就**不弹不打扰**，
     *   落自决单（L33）＋经验账；压下的**不进预算账**（预算账只记调解器判过的；
     *   这本经验账＋追认队列管回放）。阈值没到 ⇒ 照原路送预算，行为与接线前逐字一致。
     *
     * @return 调解器的判定（压下的返回 null——它根本没进调解器）
     */
    @Synchronized
    fun onOpinion(
        opinion: Opinion,
        tool: String = "",
        now: Long = System.currentTimeMillis(),
        exemptionReason: String? = null,
        alreadyRefused: Boolean = false,
        callId: String = "",
    ): Ruling? {
        val cls = classKeyOf(opinion)
        val verdict = LearnPolicy.shouldSuppress(store.readAll(), cls, now)
        if (verdict.suppressed) {
            if (LearnPolicy.mustAsk(opinion)) {
                recordViolation(opinion, cls, verdict, now)
                return OpinionWiring.onOpinion(opinion, tool, now, exemptionReason, alreadyRefused, callId)
            }
            val row = ExperienceRow(
                id = newId(now),
                atMs = now,
                kind = ExperienceKind.SUPPRESS,
                cardClass = cls,
                key = "suppress:closes=${verdict.closes}",
                reason = verdict.reason,
                evidence = listOf("closes:$cls:${verdict.closes}") + opinion.evidence.take(1),
            )
            store.append(row)
            // L33（277 的账）：判了"不打扰"就落一条自决单——理由＝学出来的那句，opinionId 指向经验账这一行
            // （压下的意见不进意见判定账，两本账靠经验账对齐；缺理由时 note 自己会拒，不会出空账）。
            SelfRulingLedger.note(
                opinion = opinion,
                opinionId = row.id,
                round = roundMark(),
                tool = tool,
                why = SUPPRESS_WHY,
                reason = verdict.reason,
                now = now,
            )
            RunLog.log(logLine(row))
            return null
        }
        val ruling = OpinionWiring.onOpinion(opinion, tool, now, exemptionReason, alreadyRefused, callId)
        // 观察行（**不是标签**）：回放时把"弹/延后"与用户后来的表态对上号；账上不设窗，永远对得上。
        store.append(
            ExperienceRow(
                id = newId(now),
                atMs = now,
                kind = ExperienceKind.ASK,
                cardClass = cls,
                key = "ask:${ruling.decision.wire}",
                reason = ruling.detail.ifEmpty { "调解器判 ${ruling.decision.cn}" },
                evidence = listOf(ruling.id),
            ),
        )
        return ruling
    }

    // ── 读数（回放／下批接线的 consult 口）────────────────────────────────

    /** 全本回放（**不设时间窗**：新→旧；30 天后照样读得到——判据④）。 */
    @Synchronized
    fun replay(): List<ExperienceRow> = store.readAll().sortedByDescending { it.atMs }

    /** 某一类的用户信号行（测试与报告用；生产读数走 [LearnPolicy.stats]）。 */
    @Synchronized
    fun signalsOf(cardClass: String): List<ExperienceRow> =
        replay().filter { it.cardClass == cardClass && it.kind == ExperienceKind.SIGNAL }

    /** 违规行（判据②的读数口）。 */
    @Synchronized
    fun violations(): List<ExperienceRow> = replay().filter { it.kind == ExperienceKind.VIOLATION }

    /**
     * 咨询口：这一类现在学出来是"问"还是"别问"（反思出卡侧下一批接的一行——
     * 反思候选出卡前问一句，压着的类就不摆卡；判据与法条闸的分工同 [onOpinion]）。
     *
     * 第二百九十八批起：**读表不读盘**（`current()` 一次 volatile 读）。判决与咨询因此
     * 永远同一版表——两处各扫一遍全本，早晚会给出两个答案。
     */
    @Synchronized
    fun shouldAsk(cardClass: String, now: Long = System.currentTimeMillis()): Boolean =
        !VerdictExperienceRuntime.current().suppresses(cardClass, now)

    /** 延后队列按学到的次序重排（次序=学习的另一半；压着的类上游已拦，不会出现在这里）。 */
    @Synchronized
    fun orderDeferred(now: Long = System.currentTimeMillis()): List<Ruling> = LearnPolicy.orderRulings(
        OpinionWiring.deferred(),
        { VerdictExperienceRuntime.current().statsOf(it, now) },
    )

    /** 意见的"类"：意见账上没有别的稳定键，gist 就是"这张卡讲什么"（≤8 字，单行）。 */
    fun classKeyOf(opinion: Opinion): String = opinion.gist

    /** 换轮来源（照 `OpinionWiring.installRoundSource` 的房型；引擎接 `beginRound` 后一行收窄）。 */
    fun installRoundSource(source: () -> String) {
        roundSource = source
    }

    /** 日志那一行（抽成纯函数：格式也是判据，埋进 RunLog 里就抓不到）。 */
    internal fun logLine(row: ExperienceRow): String =
        "抱朴学习器：${row.kind.wire}（${row.key}）${row.cardClass}｜${row.reason}"

    // ── 内部 ─────────────────────────────────────────────────────────────

    private fun defaultReason(signal: UserSignal): String = when (signal) {
        UserSignal.APPROVE -> "用户批了这张卡"
        UserSignal.REJECT -> "用户驳了这张卡"
        UserSignal.DEFER -> "用户搁置：以后再说"
        UserSignal.DISMISS -> "用户关掉了这张卡（对内容中立，只表达「现在别问」）"
        UserSignal.REASK -> "用户事后又问了同一件事：压错了"
    }

    /**
     * 记一次违规（判据②）：经验账一行（可回放）＋ 208 一行（决策中心可查；没装配落点时
     * `record` 返回 null，只留日志——照 258 的口径）。**学习结果本身不加惩罚直接作废**：
     * 这一条按法条走，学习账上那 3 次"关"原样保留（下次撞法条照样记）。
     */
    private fun recordViolation(
        opinion: Opinion,
        cls: String,
        verdict: LearnPolicy.SuppressVerdict,
        now: Long,
    ) {
        val mandate = if (opinion.lawRef == LawRef.NONE) "critical（L21 紧急上报权）" else opinion.lawRef.name
        val row = ExperienceRow(
            id = newId(now),
            atMs = now,
            kind = ExperienceKind.VIOLATION,
            cardClass = cls,
            key = "violation:law_first",
            reason = "学习想压「$cls」（${LearnPolicy.WINDOW_DAYS} 天关 ${verdict.closes} 次），" +
                "但 $mandate 要问：学习结果作废、法条优先",
            evidence = listOf(
                "law:${opinion.lawRef.name}",
                "closes:$cls:${verdict.closes}",
                "sev:${opinion.severity.name}",
            ),
        )
        store.append(row)
        refreshTable(now, "violation")
        val rec = runCatching {
            DecisionLedger.record(
                DecisionDraft(
                    kind = DecisionKind.EVAL_VERDICT,
                    actor = DecisionActor.AUTO,
                    reason = "${REASON_PREFIX}violation ${row.key} ${row.reason}",
                    evidence = "learn:violation:class=$cls:law=${opinion.lawRef.name}:closes=${verdict.closes}",
                    before = "学习判：同类 ${LearnPolicy.WINDOW_DAYS} 天关 ${verdict.closes} 次，该压",
                    after = "作废：$mandate 要问，照送预算",
                    atMs = now,
                ),
            )
        }.getOrNull()
        if (rec == null) {
            RunLog.log("抱朴学习器：违规没落进 208（${DecisionLedger.lastError}）")
        }
        RunLog.log(logLine(row))
    }

    /**
     * 记一笔「草稿被否」（第二百九十八批 · 判据③）：经验账一行 ＋ 208 一行（供修正阈值）。
     *
     * 与 [recordViolation] 的分工要说清：那一笔是**学习闸**（283）想压、撞上法条；
     * 这一笔是**判决路径**（298）查表得出的草稿被法典判据否掉。落在同一本账里但
     * kind/前缀不同（`draft_veto` vs `violation`），回放分得开、阈值各修各的。
     *
     * 由 `OpinionWiring.submit` 在验证层否掉草稿时调——**记账之后才走完整流程**，
     * 顺序本身就是判据：账上先有"为什么否"，后面那条判定才是"完整流程优先"的证据。
     */
    @Synchronized
    fun recordDraftVeto(
        opinion: Opinion,
        rule: String,
        vetoWhy: String,
        now: Long = System.currentTimeMillis(),
    ): ExperienceRow {
        val cls = classKeyOf(opinion)
        val closes = VerdictExperienceRuntime.current().statsOf(cls, now).closes
        val row = ExperienceRow(
            id = newId(now),
            atMs = now,
            kind = ExperienceKind.DRAFT_VETO,
            cardClass = cls,
            key = "draft_veto:law_first",
            reason = vetoWhy,
            evidence = listOf(
                "law:${opinion.lawRef.name}",
                "sev:${opinion.severity.name}",
                "closes:$cls:$closes",
                rule,
            ),
        )
        store.append(row)
        val rec = runCatching {
            DecisionLedger.record(
                DecisionDraft(
                    kind = DecisionKind.EVAL_VERDICT,
                    actor = DecisionActor.AUTO,
                    reason = "${REASON_PREFIX}draft_veto ${row.key} $vetoWhy",
                    evidence = "learn:draft_veto:class=$cls:law=${opinion.lawRef.name}:closes=$closes",
                    before = "草稿（命中经验表 $rule）：不弹",
                    after = "作废：法典判据要问，走完整宰相流程",
                    atMs = now,
                ),
            )
        }.getOrNull()
        if (rec == null) {
            RunLog.log("抱朴草稿：被否没落进 208（${DecisionLedger.lastError}）")
        }
        RunLog.log(logLine(row))
        // 被否也是读数：这一类"学到的别弹"与法条顶上了 ⇒ 按最新账重折（修正阈值的输入）
        refreshTable(now, "draft_veto")
        return row
    }

    /** 轮标记（照实记来源：装了来源写 `round#N`，没装写 `process`——不装假的）。 */
    private fun roundMark(): String {
        val installed = roundSource?.let { runCatching(it).getOrNull() }?.takeIf { it.isNotBlank() }
        return if (installed != null) "round#$installed" else OpinionWiring.PROCESS_ROUND
    }

    private fun newId(now: Long): String {
        seq += 1
        return "lx:$now:$seq"
    }

    /** 清空（测试专用：单例状态会跨用例污染，谁用谁还；下游两本账一起清——同一条链路）。 */
    @Synchronized
    fun resetForTest() {
        store = InMemoryJudgmentExperienceStore()
        seq = 0
        roundSource = null
        SelfRulingLedger.resetForTest()
        OpinionWiring.resetForTest()
    }
}
