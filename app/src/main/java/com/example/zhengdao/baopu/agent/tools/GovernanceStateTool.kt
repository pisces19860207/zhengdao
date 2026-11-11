// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百二十七批（2026-11-07）· 治理出口的机器口：`governance_state`（只读 R0）。
//
// 一把出九段 JSON（口径唯一真相在 `docs/metrics-registry.md`，本文件只按它取数，不另算）：
//   ratios 四率（违规/误判/干预/跳过，当日窗）／cards 弹卡三数（healthLine 同源，本批
//   从 healthLine 提出 [AskGovernance.healthCounts] 共用，没造第二遍）／gray 灰度档
//   （506 登记表逐能力当前档，从 208 账读回）／conflicts 冲突队列条数（互查账待回放行）
//   ／identity 执行体（476 身份账）／openLoops 未闭环 top-N（236 欠账）／goal 目标（482）
//   ／lease 租约（558：活/今日授予/命中/免卡/撤销/降权/过期，同源数据函数 [LeaseReadings]）
//   ／companion 相处改判（571：法典 L34 的改判账——当日次数＋最近一次原判/新判/依据）。
//
// 三条写死（metrics-registry.md 〇节，代码里不许参数化）：
//   · 窗口＝当日 00:00 起（本地日历天，[AskGovernance.dayOf] 同一把尺）；状态读数不切窗；
//   · 分母 0 ⇒ "no_sample"（不编 0%）；0<分母<10 ⇒ "unknown"（METRICS.md R1）；
//   · 计数只来自账本 replay——每段的「来源」列点名的函数就是取数路（558 的 lease.live
//     走内存权威，是 registry 明文的例外，理由在 §8）。
//
// 判据：读不到 ⇒ 该段 {"unknown":"原因"}，**不抛异常**；八段永远一起返回。
// 558 的铁律：**旧七段的键序与字节一个不动**（金样例钉在 GovernanceStateLeaseSectionTest），
// lease 只许追加在最后。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.ClosureRules
import com.example.zhengdao.baopu.CommitmentArchive
import com.example.zhengdao.baopu.CommitmentStore
import com.example.zhengdao.baopu.DecisionKind
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.DecisionRecord
import com.example.zhengdao.baopu.DecisionReview
import com.example.zhengdao.baopu.authz.LeaseReadings
import com.example.zhengdao.baopu.consequence.CanaryBand
import com.example.zhengdao.baopu.consequence.CanaryCapability
import com.example.zhengdao.baopu.consequence.ConsequenceLedger
import com.example.zhengdao.baopu.consequence.IdentityState
import com.example.zhengdao.baopu.consequence.IdentityStore
import com.example.zhengdao.baopu.inspect.InspectVerdict
import com.example.zhengdao.baopu.inspect.LogStep
import com.example.zhengdao.baopu.inspect.Metrics
import com.example.zhengdao.baopu.inspect.MutualLedger
import com.example.zhengdao.baopu.inspect.PatrolEntry
import com.example.zhengdao.baopu.inspect.PatrolLedger
import com.example.zhengdao.baopu.plan.GoalRules
import com.example.zhengdao.baopu.plan.GoalStore
import java.io.File
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

/**
 * 治理出口工具（527 机器口）。执行器零写入：只读各本账，回一段 JSON（[ToolOutcome.Ok]）。
 *
 * 运行环境注入口 [env] 房型同 `IndexTools.ctx`／`BackupTools.ctx`：Context 只到装配层
 * （[com.example.zhengdao.baopu.BaopuRuntime.engine] 注入一次），工具层不认识 Android；
 * JVM 单测给假件直接打六段对拍。env 为 null ⇒ 依赖它的段如实 `unknown`，不冒充 0。
 */
internal object GovernanceStateTool {

    const val NAME = "governance_state"

    /** 出口形状版本（改 metrics-registry.md 的段形状时一起升；482 加 `goal` 段 ⇒ 2，558 加 `lease` 段 ⇒ 3，571 加 `companion` 段 ⇒ 4）。 */
    const val VERSION = 4

    /** 未闭环 top-N（metrics-registry.md §6：挂账最久的前 N 条；写死不参数化）。 */
    const val OPEN_LOOP_TOP = 10

    /**
     * 运行环境（装配层注入；null ＝ 未装配，相关段退 `unknown`＋原因）。
     *
     * @param identityDir 执行体目录所在的 filesDir（476 身份账 `执行体/身份.json` 在它下面）
     * @param logSteps run_log 的动作/摆卡行取数壳（照 METRICS.md §1.3 的唯一真相源；
     *   SQLite 没有唯一的 jsonl 副本，所以这一路是注入的只读查询，不是 jsonl replay）
     */
    data class Env(
        val identityDir: () -> File?,
        val logSteps: (fromMs: Long) -> List<LogStep>,
    )

    @Volatile
    var env: Env? = null

    fun spec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = NAME,
        description = "读本机治理出口的九段只读读数（不改任何东西）：ratios 四率" +
            "（违规/误判/干预/跳过，当日窗）、cards 弹卡三数（今日自动放过/问卡/压卡）、" +
            "gray 灰度档（逐能力当前档）、conflicts 冲突队列条数（待回放）、identity 执行体身份、" +
            "openLoops 未闭环承诺 top-10、goal 目标（一句话目标/进度/预算用量）、" +
            "lease 租约（活/今日授予/命中/免卡/撤销/降权/过期）、companion 相处改判" +
            "（当日次数＋最近一次：原判/新判/依据，法典 L34）。" +
            "口径唯一真相在 docs/metrics-registry.md：分母 0 回 " +
            "no_sample，不编 0%；读不到的段回 unknown 加原因，不抛异常。（这个工具在 FILES 组：" +
            "看不到就先 capabilities 解锁）",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(),
            "required" to jsonArrayOf(),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.FREE,
        riskTier = "R0", // 只读：只读账、不落一笔
        readOnly = true,
        parallelSafe = true,
        timeoutMs = ToolTimeout.FILE_MS,
    ) { _ -> run(workspaceRoot(), System.currentTimeMillis()) }

    /** 九段一起出（[nowMs] 由调用方给：单测传常量钉边界，生产传当前时刻）。 */
    fun run(workspaceRoot: File, nowMs: Long): ToolOutcome {
        val json = JSONObject()
            .put("v", VERSION)
            .put("day", AskGovernance.dayOf(nowMs).toString())
            .put("ratios", ratiosSection(nowMs))
            .put("cards", cardsSection(nowMs))
            .put("gray", graySection())
            .put("conflicts", conflictsSection())
            .put("identity", identitySection())
            .put("openLoops", openLoopsSection(workspaceRoot))
            .put("goal", goalSection(workspaceRoot))
            .put("lease", leaseSection(nowMs))
            .put("companion", companionSection(nowMs))
        return ToolOutcome.Ok(json.toString())
    }

    // ── 段一：ratios 四率（当日窗；率的三态见 metrics-registry.md W2）────────

    private fun ratiosSection(nowMs: Long): JSONObject {
        val out = JSONObject()
        val ledger = readLedger()
        out.put(
            "violation",
            if (ledger == null) unknown("账本根未装配，决策记录读不到")
            else rateJson(ratioViolation(ledger, nowMs)),
        )
        val reviews = DecisionLedger.installedRoot()?.let { root ->
            runCatching { DecisionReview.read(root) }.getOrNull()
        }
        out.put(
            "misjudge",
            if (reviews == null) unknown("账本根未装配，抽检结论读不到")
            else rateJson(ratioMisjudge(reviews, nowMs)),
        )
        val shell = env
        val steps = shell?.let { runCatching { it.logSteps(dayStartMs(nowMs)) }.getOrNull() }
        val intervention: JSONObject = when {
            shell == null -> unknown("run_log 取数壳未装配")
            steps == null -> unknown("run_log 读不出")
            else -> rateJson(ratioIntervention(steps, nowMs))
        }
        out.put("intervention", intervention)
        val patrols = DecisionLedger.installedRoot()?.let { root ->
            runCatching { PatrolLedger.read(root) }.getOrNull()
        }
        out.put(
            "skip",
            if (patrols == null) unknown("账本根未装配，巡按记录读不到")
            else rateJson(ratioSkip(patrols, nowMs)),
        )
        return out
    }

    /** 违规率：被撤销的当日免问动作 ÷ 当日免问动作（回滚行本身不切窗，只认它指到的当日行）。 */
    internal fun ratioViolation(ledger: List<DecisionRecord>, nowMs: Long): Pair<Int, Int> {
        val auto = ledger.filter { it.kind == DecisionKind.AUTO_ACT && isToday(it.atMs, nowMs) }
        val rolledBackIds = ledger.filter { it.kind == DecisionKind.ROLLBACK_OF }
            .mapNotNull { it.rollbackOf }
            .toSet()
        return auto.count { it.id in rolledBackIds } to auto.size
    }

    /** 误判率：抽检判「错」÷ 已判（同 record_id 取最后一条；按判定时刻切当日）。 */
    internal fun ratioMisjudge(reviews: List<DecisionReview.Entry>, nowMs: Long): Pair<Int, Int> {
        val latest = DecisionReview.latestPerRecord(reviews).values
            .filter { isToday(it.atMs, nowMs) }
            .filter { it.verdict == DecisionRecord.VERDICT_CONFIRMED || it.verdict == DecisionRecord.VERDICT_WRONG }
        return latest.count { it.verdict == DecisionRecord.VERDICT_WRONG } to latest.size
    }

    /** 干预率：有摆卡的 (会话,步) ÷ 有痕迹的 (会话,步)（都按 (会话,步) 去重；当日）。 */
    internal fun ratioIntervention(rows: List<LogStep>, nowMs: Long): Pair<Int, Int> {
        val today = rows.filter { isToday(it.atMs, nowMs) }
        val steps = today.map { it.sessionId to it.step }.toSet()
        val confirms = today.filter { it.state == Metrics.STATE_CONFIRM }
            .map { it.sessionId to it.step }
            .toSet()
        return confirms.size to steps.size
    }

    /** 跳过率：巡按判「查不了」的检查项 ÷ 巡按跑过的检查项（无六项明细的老行不计入；当日）。 */
    internal fun ratioSkip(patrols: List<PatrolEntry>, nowMs: Long): Pair<Int, Int> {
        val today = patrols.filter { isToday(it.atMs, nowMs) && it.findings.isNotEmpty() }
        val total = today.sumOf { it.findings.size }
        val skipped = today.sumOf { e -> e.findings.count { it.verdict == InspectVerdict.NO_DATA } }
        return skipped to total
    }

    // ── 段二：cards 弹卡三数（healthLine 同源——healthCounts 一份，两处读）────

    private fun cardsSection(nowMs: Long): JSONObject {
        if (DecisionLedger.installedRoot() == null) return unknown("账本根未装配，治理账读不到")
        return runCatching {
            val c = AskGovernance.healthCounts(nowMs)
            JSONObject().put("pass", c.pass).put("card", c.cards).put("compress", c.compress)
        }.getOrElse { unknown("治理账读不出：${it.javaClass.simpleName}") }
    }

    // ── 段三：gray 灰度（506 登记表逐能力当前档；档从 208 账读回，不读开关）────

    private fun graySection(): JSONObject {
        val ledger = readLedger() ?: return unknown("账本根未装配，档位账读不到")
        val out = JSONObject()
        CanaryCapability.entries.forEach { cap ->
            val band: CanaryBand = runCatching { ConsequenceLedger.currentCanaryBand(ledger, cap.wire) }
                .getOrElse { CanaryCapability.ofWire(cap.wire)?.defaultBand ?: CanaryBand.OFF }
            out.put(cap.wire, JSONObject().put("band", band.wire).put("cn", band.cn))
        }
        return out
    }

    // ── 段四：conflicts 冲突队列条数（互查账 kind=conflict 且 pending_replay）──

    private fun conflictsSection(): JSONObject {
        val root = DecisionLedger.installedRoot() ?: return unknown("账本根未装配，互查记录读不到")
        val rows = runCatching { MutualLedger.read(root) }
            .getOrElse { return unknown("互查记录读不出：${it.javaClass.simpleName}") }
        val pending = rows.count {
            it.optString("kind") == MutualLedger.KIND_CONFLICT && it.optBoolean("pending_replay", false)
        }
        return JSONObject().put("pending_replay", pending)
    }

    // ── 段五：identity 执行体（476 身份账；撤销/轮换留痕照读）────────────────

    private fun identitySection(): JSONObject {
        val dir = env?.let { runCatching { it.identityDir() }.getOrNull() }
            ?: return unknown("执行体目录未装配")
        val rows = runCatching { IdentityStore.load(dir) }
            .getOrElse { return unknown("身份账读不出：${it.javaClass.simpleName}") }
        if (rows.isEmpty()) return unknown("执行体身份账读不到（未首启生成）")
        val row = rows.lastOrNull { it.state == IdentityState.ACTIVE } ?: rows.last()
        return JSONObject().put("id", row.id).put("state", row.state.wire)
    }

    // ── 段六：openLoops 未闭环 top-N（236 欠账；挂账最久在前）────────────────

    private fun openLoopsSection(workspaceRoot: File): JSONObject {
        val file = runCatching { CommitmentStore.fileOf(workspaceRoot) }
            .getOrElse { return unknown("工作区根不可用") }
        if (!file.isFile) return JSONObject().put("total", 0).put("items", JSONArray())
        val lines = runCatching { CommitmentArchive.parse(file.readText(Charsets.UTF_8)) }
            .getOrElse { return unknown("承诺档案读不出：${it.javaClass.simpleName}") }
        val open = ClosureRules.openBooks(lines)
        val items = JSONArray()
        open.take(OPEN_LOOP_TOP).forEach { b ->
            val stage = lines.lastOrNull { it.key == b.key }?.stage?.wire ?: ""
            items.put(
                JSONObject()
                    .put("key", b.key)
                    .put("since", b.since.toString())
                    .put("stage", stage),
            )
        }
        return JSONObject().put("total", open.size).put("items", items)
    }

    // ── 共用小件 ─────────────────────────────────────────────────────────────

    /** 账本根读侧（null＝未装配；读不出＝null，调用方各自给 unknown）。 */
    private fun readLedger(): List<DecisionRecord>? {
        val root = DecisionLedger.installedRoot() ?: return null
        return runCatching { DecisionLedger.read(root) }.getOrNull()
    }

    /** 率的三态（metrics-registry.md W2）：0 样本不编 0%，样本不足不出百分比。 */
    internal fun rateOf(n: Int, d: Int): String = when {
        d == 0 -> "no_sample"
        d < Metrics.MIN_DENOM -> "unknown"
        else -> "${Metrics.decimals(n * 100.0 / d)}%"
    }

    private fun rateJson(pair: Pair<Int, Int>): JSONObject = JSONObject()
        .put("n", pair.first)
        .put("d", pair.second)
        .put("rate", rateOf(pair.first, pair.second))

    /** 段降级（527 判据：读不到 ⇒ unknown＋原因，不抛异常）。 */
    private fun unknown(reason: String): JSONObject = JSONObject().put("unknown", reason)

    /**
     * 第七段：目标（第四百八十二批）。
     *
     * 「目标：… / 进度 3/5 步 / 预算用 40%」这一行的**唯一来源**：百分数由
     * [GoalRules.percentText] 算好，本工具与屏幕都不再算（322 判据：判据层没有算术）。
     * 没有立目标 ⇒ `{"unknown": …}`，不补一行「暂无」冒充读数。
     */
    private fun goalSection(workspace: File): JSONObject {
        val goal = GoalStore.readGoal(workspace) ?: return unknown("这次会话还没有立目标")
        return JSONObject()
            .put("sentence", goal.sentence)
            .put("state", goal.state.wire)
            .put("stateCn", goal.state.cn)
            .put("progress", "${goal.done}/${goal.total}")
            .put("budget", GoalRules.percentText(goal.stepsUsed, goal.budget.maxSteps))
            .put("pledge", goal.pledgeId)
            .put("line", GoalRules.progressLine(goal))
    }

    /**
     * 第八段：租约（第五百五十八批；排在最后——旧七段的键序与字节一个不动）。
     *
     * 七格全从 [LeaseReadings.counts] 一处来（registry §8：出口段、体检尾句、屏幕行
     * 三个读口读同一份）。`live` 是内存权威（W3 的明文例外，理由见 registry §8），
     * 其余六格走 467 租约账 replay。落点未装配（首启没跑过管线动作）⇒ 整段 unknown。
     */
    private fun leaseSection(nowMs: Long): JSONObject {
        val c = LeaseReadings.counts(nowMs) ?: return unknown("租约账落点未装配")
        return JSONObject()
            .put("live", c.live)
            .put("grantedToday", c.grantedToday)
            .put("hits", c.hits)
            .put("savedCards", c.savedCards)
            .put("revoked", c.revoked)
            .put("downgraded", c.downgraded)
            .put("expired", c.expired)
    }

    /**
     * 第九段：相处改判（第五百七十一批；排在最后——旧八段的键序与字节一个不动）。
     *
     * 法典 L34 的"账在哪"：改判落一行（原判/新判/依据），行账在 [CompanionRejudgeLedger]，
     * 本段只**抄**它的回放（当日次数＋最近一次），不算第二遍。账落点未装配 ⇒ 整段 unknown；
     * 账在但没改判过 ⇒ `{"today":0}`（真零，不编 last 冒充读数）。
     */
    private fun companionSection(nowMs: Long): JSONObject {
        val root = DecisionLedger.installedRoot()
            ?: return unknown("账本根未装配，相处改判账读不到")
        val rows = runCatching { CompanionRejudgeLedger.replay() }
            .getOrElse { return unknown("相处改判账读不出：${it.javaClass.simpleName}") }
        val last = rows.firstOrNull() ?: return JSONObject().put("today", 0)
        val out = JSONObject().put("today", rows.count { isToday(it.atMs, nowMs) })
        return out.put(
            "last",
            JSONObject()
                .put("from", last.from)
                .put("fromCn", last.fromCn)
                .put("to", last.to)
                .put("toCn", last.toCn)
                .put("basis", last.basis)
                .put("at", last.atMs),
        )
    }

    /** 当日归属（本地日历天；与体检/账本同一把尺）。 */
    private fun isToday(atMs: Long, nowMs: Long): Boolean =
        AskGovernance.dayOf(atMs) == AskGovernance.dayOf(nowMs)

    /** 当日 00:00 的毫秒（本地时区；run_log 取数壳的窗口起点）。 */
    internal fun dayStartMs(nowMs: Long): Long =
        AskGovernance.dayOf(nowMs).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
