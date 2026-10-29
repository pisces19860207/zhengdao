// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第二百六十五批 · 反思自治器的**接线层**（"装好没接电"的第二根线；第一根是 258 的调解器）。
//
// 这批之前的事实：`AutonomyRules`（236 三档判据）、`AutonomyProposal`、`CommitmentStore`
// （236 承诺档案读写）三件**有实现、有单测、生产链路零调用点**——`grep ReflectionAutonomy
// app/src/main` 只命中它自己那个文件。所以"该不该自己改"这套判据在运行时**一次都没生效过**。
//
// 本文件只做三件事，一件不多（派工单 §1 的 R1–R3 逐字对应）：
//   R1 接入点：每轮**收工后**调一次反思判定（派工单写的 "runSettled" 在代码里就是
//      `RunEngine.settle(...)`；全仓唯一调用点＝它的最后一句）。`RunEngine.settle` 的 15 处
//      调用点全过那个漏斗——实测数，不是估的。**不在轮次开始时调**——那时本轮的轨迹与账本
//      还没写，反思的是残缺状态（负例②钉这条）。
//      同名不同物的那条**故意不接**：`SubAgentTurnOutcomes.settle`（多代理子轮）是个纯分类器，
//      判"这堆原料算 Text 还是 Calls 还是 Failed"，不是收尾；子轮嵌在主轮里，接了会把同一轮的
//      证据数两遍。
//   R2 落地：值得反思 ⇒ 往 236 已经定义好的 `产物/进化档案-承诺.md` **追加一行**
//      （键／段／结论／档位／依据／一句话，依据列就是证据指针），下一次收工**先读回来**。
//   R3 不许自嗨：没有可点开的证据 ⇒ **零字节落盘**，只写一行日志（负例①／④）。
//
// 四条纪律（与红线一一对应）：
//   1. **不改判据**：出不出条目用 205 的 `evidenceOpenable`、可不可执行用 236 的
//      `ClosureRules.promiseFindings`、重不重复用 205 的 `ReflectionDedup`、谁能自动改用 236 的
//      `AutonomyRules.decide`。本文件只搬运，一个常量都不重写（负例⑤钉这条）。
//   2. **不新增落盘状态**：写的是 236 已有的档案文件与已有的列，没有新表、新 SP 键、新文件。
//   3. **不碰 [DecisionLedger]**：249 正占着 208 那条道；本批一行都不记（派工单红线）。
//   4. **不塞回上下文**：反思结果只落档案＋出日志，**绝不进 `extraNotes`／`history`**（负例③）。
//      这一条与 258 的 W3 故意不同：调解指令是"这一步该怎么说话"的当轮口令（≤60 字、带标记），
//      而反思条目是**账**——把账读进上下文，模型下一轮就会照着账自我表功，那正是"自嗨"的形状。
//
// 为什么不调 [AutonomySelfEdit]：它是 A 档的**执行壳**（写技能正文／人设＋记 208）。收工点上
// 没有拟写正文、也没有可跑的 206 对拍，所以本层把 `rollbackReady／auditReady／evalRunnable`
// 三件一律填 false ⇒ `AutonomyRules.decide` 的 `AutoEdit` 在这里**不可达**（`ReflectionWiringTest`
// 钉住）。接线只判档位、只出指针，**一个字都不自改**——自治 ≠ 免检，何况这里连检都跑不了。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.agent.RunTraceStore
import com.example.zhengdao.baopu.agent.TraceRow
import com.example.zhengdao.baopu.agent.TraceStatus
import com.example.zhengdao.core.RunLog
import java.io.File
import java.time.LocalDate
import org.json.JSONObject

// ── 一轮收工时的盘上事实（壳读好装进来；判据层不摸盘、不建线程、不碰 Android）─────────

/**
 * @param runId 本轮的 runId（`r<sessionId>@<起点>`；空串＝本轮没起 run ⇒ 无从反思）
 * @param startedAtMs 本轮起点（**账本行的认领窗口**从这里算；`0` ⇒ 认不了任何账本行）
 * @param endedAtMs 收工时刻
 * @param trace 本轮全部轨迹行（工具失败与用户拒绝都从这里认）
 * @param ledger 两层 `技能/_变更.jsonl` 的**全部**行（窗口过滤在判据层做，好断言）
 */
internal data class SettledRound(
    val runId: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val trace: List<TraceRow>,
    val ledger: List<SkillAuditLine>,
)

/** 一轮收工反思的结果（四态；调用方逐态出声，不许合并成"成功/失败"）。 */
internal sealed interface SettledReflectionOutcome {
    /** 本轮没有任何可反思的事实 ⇒ 一行日志，盘上一个字节没动（R3）。 */
    data class NoSignal(val line: String) : SettledReflectionOutcome

    /** 有事实，但证据点不开／提炼不出落点／7 天内提过／只是感想 ⇒ 同样零落盘（R3）。 */
    data class NotWritten(val line: String) : SettledReflectionOutcome

    /** 入账一行（带证据与档位）。[tierLine] 是 236 判据那句人话。 */
    data class Written(val row: CommitmentLine, val tierLine: String) : SettledReflectionOutcome

    /** 该入账却没写成（落盘失败）⇒ **必须出声**（负例④：静默丢账比不反思更坏）。 */
    data class WriteFailed(val row: CommitmentLine, val why: String) : SettledReflectionOutcome
}

// ── 判据层（纯函数；三类信号 → 一条候选 → 一行档案）──────────────────────────────

internal object SettledReflectionRules {

    /**
     * 本批写的行在「一句话」列的**固定前缀**（258 的 `REASON_PREFIX` 同一手法）。
     *
     * 为什么要有它：本批复用 236 的承诺档案，那张表里没有"谁写的"这一列，
     * 唯一能挂牌的地方就是「一句话」列的首段——将来要按来源分组统计（或把本批的行清掉）
     * 都认这个前缀，不靠"看着像"。
     */
    const val NOTE_PREFIX = "收工反思："

    /** 「一句话」列的可读上限（236 记 208 时用的是 60/80；这行是给人读的档案，放宽到 160）。 */
    private const val NOTE_MAX = 160

    /**
     * 出条闸：≥1 条证据、**条条可点开**。
     *
     * 与 205 的 [ReflectionCandidate.presentable]（≥2 条）**故意不同**，理由说清楚免得被当成偷工：
     * 那边出的是**弹给用户的卡**，一张错卡要花掉她的注意力，所以要两手证据；这边写的是
     * append-only 的一行账，下次收工自己读回来，一条真证据就该入账。
     * 但"没有证据一个字节不写"这半句两边完全一致（R3／负例①）。
     */
    fun writable(c: ReflectionCandidate): Boolean =
        c.evidences.isNotEmpty() && c.evidences.all { it.openable }

    // ── 三类信号（派工单 §1 R2 的列举序：工具失败／账本行／用户反馈）────────────────

    /** ①工具失败：`FAILED` 且带失败分类（没分类的失败行认不出"哪一类坑"，不算信号）。 */
    fun failures(r: SettledRound): List<TraceRow> =
        r.trace.filter { it.status == TraceStatus.FAILED && it.failureKind.isNotBlank() }

    /** ③用户反馈：本轮被用户**拒绝**的调用（人给的信号，比机器判的失败更硬）。 */
    fun rejections(r: SettledRound): List<TraceRow> =
        r.trace.filter { it.status == TraceStatus.REJECTED }

    /**
     * ②账本行：本轮窗口内写的技能变更账。
     *
     * 起点为 `0`（不知道本轮什么时候开的）⇒ **一条都不认**：宁可少认，不许把上一轮的
     * 改动当成这一轮的证据（错认的证据比没证据更坏——它会让档案说假话）。
     */
    fun ledgerInRound(r: SettledRound): List<SkillAuditLine> =
        if (r.startedAtMs <= 0L) {
            emptyList()
        } else {
            r.ledger.filter { it.ts >= r.startedAtMs && it.ts <= r.endedAtMs }
        }

    /** 本轮有没有可反思的事实（三类全空 ⇒ 只记一行日志）。 */
    fun hasSignal(r: SettledRound): Boolean =
        failures(r).isNotEmpty() || ledgerInRound(r).isNotEmpty() || rejections(r).isNotEmpty()

    /** 没落盘时那一行日志（R3 的"只记一行"）。 */
    fun noSignalLine(r: SettledRound): String =
        "收工反思：本轮 ${r.trace.size} 条轨迹，无失败／无拒绝／无技能账本行 ⇒ 没有证据，不落盘（${r.runId}）"

    /** 本轮那**一条**候选（一轮只出一条；按列举序取第一类非空的信号）。 */
    fun candidate(r: SettledRound, wsRoot: File, globalRoot: File): ReflectionCandidate? {
        failureCandidate(r)?.let { return it }
        ledgerCandidate(r, wsRoot, globalRoot)?.let { return it }
        return rejectionCandidate(r)
    }

    private fun failureCandidate(r: SettledRound): ReflectionCandidate? {
        val all = failures(r)
        if (all.isEmpty()) return null
        // 失败最多的那个工具（并列取先出现的）——一条候选只认一个落点，别把三件事糊成一句。
        val tool = all.groupingBy { it.tool }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
        if (tool.isBlank()) return null
        // 次数／种类／证据**只数落点自己那些行**：句子里每个数都得说的是同一个工具，
        // 拿全轮总数配一个工具名，读的人会去数不上账（A3 钉住这条）。
        val rows = all.filter { it.tool == tool }
        val kinds = rows.map { it.failureKind }.distinct().sorted()
        return ReflectionCandidate(
            shape = ReflectionShape.REPEAT_FAILURE,
            target = tool,
            changeKind = ReflectionChangeKind.EDIT_FLOW,
            claim = "本轮「$tool」失败 ${rows.size} 次（${kinds.joinToString("、")}）——把这次的失败条件写进它的用法说明",
            // 与 205 的 `twoNewest` 同一口径（最新两条），不另立第二个数。
            evidences = rows.takeLast(2).map { traceEvidence(r, it) },
            benefit = "下一轮同名工具少走 ${rows.size - 1} 次失败；模型照说明先探再动",
            risk = "只改说明文字；改工具描述属 B 档（会改变「什么时候会动」），必须人点",
            landing = "工具「$tool」的用法说明／相关技能正文",
            createdAt = r.endedAtMs,
        )
    }

    private fun ledgerCandidate(r: SettledRound, wsRoot: File, globalRoot: File): ReflectionCandidate? {
        val lines = ledgerInRound(r)
        if (lines.isEmpty()) return null
        val last = lines.last()
        if (last.name.isBlank()) return null
        val actions = lines.map { it.action }.distinct().sorted()
        val evidences = mutableListOf(
            ReflectionEvidence(
                display = "${relOf(last.file, wsRoot, globalRoot)} 第 ${last.lineNo} 行（${last.action}：${last.reason}）",
                path = relOf(last.file, wsRoot, globalRoot),
                anchor = "L${last.lineNo}",
                kind = ReflectionEvidenceKind.SKILL_LEDGER,
                openable = evidenceOpenable(relOf(last.file, wsRoot, globalRoot), "L${last.lineNo}", wsRoot, globalRoot),
            ),
        )
        // 第二手证据＝被改的那份正文（在盘上才算；不在 ⇒ 只有一条证据，照样能入账）。
        bodyFileOf(last)?.let { f ->
            val p = relOf(f, wsRoot, globalRoot)
            evidences += ReflectionEvidence(
                display = "$p（本轮改的就是它）",
                path = p,
                anchor = "L1",
                kind = ReflectionEvidenceKind.SKILL_BODY,
                openable = evidenceOpenable(p, "L1", wsRoot, globalRoot),
            )
        }
        return ReflectionCandidate(
            shape = ReflectionShape.REPEAT_LABOR,
            target = last.name,
            changeKind = ReflectionChangeKind.EDIT_SKILL,
            claim = "本轮手改了技能「${last.name}」（${actions.joinToString("、")}，共 ${lines.size} 行账）——把这次的做法固化进正文",
            evidences = evidences,
            benefit = "同一诉求下次照技能直做，少一次手改",
            risk = "只动这条技能的正文；草稿的试用期计数不由本层碰",
            landing = "技能「${last.name}」正文",
            createdAt = r.endedAtMs,
        )
    }

    private fun rejectionCandidate(r: SettledRound): ReflectionCandidate? {
        val all = rejections(r)
        if (all.isEmpty()) return null
        val tool = all.groupingBy { it.tool }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
        if (tool.isBlank()) return null
        val rows = all.filter { it.tool == tool }
        return ReflectionCandidate(
            shape = ReflectionShape.REPEAT_FAILURE,
            target = tool,
            changeKind = ReflectionChangeKind.EDIT_FLOW,
            claim = "本轮用户拒绝了「$tool」${rows.size} 次——把它的触发条件写清，别下次又摆同一张卡",
            evidences = rows.takeLast(2).map { traceEvidence(r, it) },
            benefit = "少打扰用户 ${rows.size} 次；拒绝的原因留在档案里，下次先自检",
            risk = "只改说明文字；确认口径本身属 C 档，本层一个字不碰",
            landing = "工具「$tool」的用法说明",
            createdAt = r.endedAtMs,
        )
    }

    /** 轨迹证据（205 对 TRACE 的同一口径：path 形如 `run_trace:<runId>`，点得开＝那行真在库里）。 */
    private fun traceEvidence(r: SettledRound, row: TraceRow): ReflectionEvidence {
        val why = if (row.failureKind.isNotBlank()) row.failureKind else row.status.name
        return ReflectionEvidence(
            display = "run_trace ${r.runId}#${row.seq}（${row.tool.ifBlank { "?" }} $why）",
            path = "run_trace:${r.runId}",
            anchor = "#${row.seq}",
            kind = ReflectionEvidenceKind.TRACE,
            openable = true,
        )
    }

    // ── 候选 ⇒ 236 的提案与承诺行 ─────────────────────────────────────────────

    /** 205 的改法 ⇒ 236 的改法（两张表各管一件事：那边管"这是什么改动"，这边管"谁能自动改"）。 */
    fun autonomyKindOf(k: ReflectionChangeKind): AutonomyChangeKind = when (k) {
        ReflectionChangeKind.NEW_SKILL -> AutonomyChangeKind.SKILL_NEW
        ReflectionChangeKind.EDIT_SKILL -> AutonomyChangeKind.SKILL_EXAMPLE_ADD
        ReflectionChangeKind.EDIT_FLOW -> AutonomyChangeKind.TOOL_DESCRIPTION_EDIT
        // 退役＝删技能＝C 档：收工反思不提议删东西（真要删是人自己的决定，不是机器的建议）。
        ReflectionChangeKind.RETIRE -> AutonomyChangeKind.SKILL_DELETE
    }

    /**
     * 候选 ⇒ 236 的提案。
     *
     * `currentText`／`newText` 一律空串：收工点上**没有拟写正文**（本批不生成正文，红线不许自改）。
     * 三件条件（回滚点／留痕／对拍）一律 false ⇒ `AutonomyRules.decide` 的 A 档在这里不可达，
     * 结论只可能是 `NeedsApproval`（B）或 `NeverAuto`（C）。这不是偷懒，是"没条件验就不许自改"。
     */
    fun proposalOf(c: ReflectionCandidate): AutonomyProposal = AutonomyProposal(
        kind = autonomyKindOf(c.changeKind),
        target = c.target,
        currentText = "",
        newText = "",
        scopeProject = true,
        rollbackReady = false,
        auditReady = false,
        evalRunnable = false,
        candidateKey = c.dedupKey,
    )

    /** 候选 ⇒ 承诺的形状（236 的 `promiseFindings` 拿它当"是不是只有感想"的判据输入）。 */
    fun promiseOf(c: ReflectionCandidate): PromiseShape {
        val verify = when (c.changeKind) {
            ReflectionChangeKind.EDIT_SKILL ->
                "技能/_变更.jsonl 里「${c.target}」同名同理由不再新增行（本轮 ${c.evidences.size} 条证据可点开）"

            else -> "下一轮 run_trace 里「${c.target}」不再出现同类信号（失败／拒绝）"
        }
        return PromiseShape(key = c.dedupKey, target = c.landing, verifyBy = verify, action = c.claim)
    }

    /** 档位那一列（A/B/C 的短名；判据那句长话进「一句话」列）。 */
    fun tierLabelOf(v: AutonomyVerdict): String = when (v) {
        is AutonomyVerdict.AutoEdit -> AutonomyTier.A_SELF_EDIT.label
        is AutonomyVerdict.NeedsApproval -> v.tier.label
        is AutonomyVerdict.NeverAuto -> AutonomyTier.C_NEVER_AUTO.label
    }

    /** 一条候选＋档位结论 ⇒ 档案那一行（**证据在「依据」列**：`path:anchor`，点得开）。 */
    fun lineOf(
        c: ReflectionCandidate,
        v: AutonomyVerdict,
        shape: PromiseShape,
        date: LocalDate,
    ): CommitmentLine = CommitmentLine(
        date = date,
        key = shape.key,
        stage = CommitmentStage.PROMISE,
        verdict = shape.action,
        tier = tierLabelOf(v),
        evidence = c.evidences.joinToString("；") { "${it.path}:${it.anchor}" },
        note = (NOTE_PREFIX + "落点 ${shape.target}｜验法 ${shape.verifyBy}｜档位 ${AutonomyTell.line(v)}")
            .take(NOTE_MAX),
    )

    // ── 去重（复用 205 的窗；键与承诺键同形，所以两套账对得上）──────────────────────

    /**
     * 承诺档案的旧行 ⇒ 205 去重要吃的形状。
     *
     * 承诺键就是 `target::改法`（[ReflectionCandidate.dedupKey] 的拼法），所以拆回来即可，
     * 不需要第二套键。「问过人了·否决」映射成 205 的**否决**⇒ 30 天冷档窗自动生效；
     * 其余一律当"待定"⇒ 7 天窗生效（同一件事一周内只入账一次）。
     */
    fun archiveOf(lines: List<CommitmentLine>): List<ReflectionArchiveLine> = lines.mapNotNull { l ->
        val pair = splitKey(l.key) ?: return@mapNotNull null
        val rejected = l.stage == CommitmentStage.ASKED && l.verdict == CommitmentAsk.DISAGREED.wire
        ReflectionArchiveLine(
            date = l.date,
            target = pair.first,
            changeKind = pair.second,
            claim = l.verdict,
            evidences = l.evidence,
            decision = if (rejected) {
                ReflectionArchive.DECISION_REJECTED
            } else {
                ReflectionArchive.DECISION_PENDING
            },
        )
    }

    /** `target::改法` ⇒ 两半（认不出改法 ⇒ null，**不猜**：猜出来的键会把两件事并成一件事）。 */
    private fun splitKey(key: String): Pair<String, ReflectionChangeKind>? {
        val i = key.lastIndexOf("::")
        if (i <= 0 || i + 2 >= key.length) return null
        val kind = ReflectionChangeKind.of(key.substring(i + 2)) ?: return null
        return key.substring(0, i) to kind
    }

    // ── 路径（证据要写成"相对工作区/全局根"的样子，读者才点得开）──────────────────────

    private fun relOf(f: File, wsRoot: File, globalRoot: File): String {
        for (root in listOf(wsRoot, globalRoot)) {
            val rp = root.absolutePath
            val fp = f.absolutePath
            if (fp.startsWith(rp) && fp.length > rp.length) {
                return fp.substring(rp.length).trimStart('\\', '/').replace('\\', '/')
            }
        }
        return f.path.replace('\\', '/')
    }

    /** 账本行对应的那份技能正文（`技能/<名>/SKILL.md`；不在盘上 ⇒ null，不硬造证据）。 */
    private fun bodyFileOf(line: SkillAuditLine): File? {
        // 账本文件就住在技能目录下（`技能/_变更.jsonl`）⇒ 它的父目录就是那一层的技能目录，
        // 两层各认各的，不用再按 scope 猜落点。
        val skillDir = line.file.parentFile ?: return null
        val f = File(File(skillDir, line.name), SkillRules.SKILL_FILE)
        return if (f.isFile) f else null
    }
}

// ── 接线（薄 IO：读轨迹、读账本、写档案、出声；判据一律在上面）──────────────────────

/**
 * 收工后反思的一次执行（**无状态**：状态全在盘上的承诺档案里，所以每轮 new 一个也不丢账）。
 *
 * @param store 承诺档案的唯一写口（236 的 [CommitmentStore]；本层不开第二个写口）
 * @param traceOf 读一轮轨迹（生产＝`RunTraceStore(db).traceOf`；单测注入假实现，不必起 SQLite）
 */
internal class ReflectionWiring(
    private val wsRoot: File,
    private val globalRoot: File,
    private val store: CommitmentStore,
    private val traceOf: (String) -> List<TraceRow>,
    private val log: (String) -> Unit = { RunLog.log(it) },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * R1 的入口：收工后调**一次**。
     *
     * 先读回上次的账（R2 的"下次能读到"），再判本轮有没有证据（R3），有才写。
     * 全程不抛：读盘/读库的失败都降级成"这一轮没反思成"，并出声（负例④）。
     */
    fun onSettled(runId: String, startedAtMs: Long): SettledReflectionOutcome {
        val prior = store.read()
        reportDebt(prior)
        if (runId.isBlank()) {
            val line = "收工反思：本轮没有 runId（收尾早于开跑）⇒ 无从认领证据，不落盘"
            log(line)
            return SettledReflectionOutcome.NotWritten(line)
        }
        val round = SettledRound(
            runId = runId,
            startedAtMs = startedAtMs,
            endedAtMs = clock(),
            trace = traceOf(runId),
            ledger = ledgerLines(),
        )
        if (!SettledReflectionRules.hasSignal(round)) {
            val line = SettledReflectionRules.noSignalLine(round)
            log(line)
            return SettledReflectionOutcome.NoSignal(line)
        }
        return writeOrExplain(round, prior)
    }

    /** R2 的读回：上次收工写的账这次**先读**（236 负例⑥的同一要求）。只出声，不进上下文。 */
    private fun reportDebt(prior: List<CommitmentLine>) {
        val debt = ClosureRules.mustFollowUp(prior)
        if (debt.isEmpty()) return
        log("收工反思：上次还欠 ${debt.size} 条没闭合的承诺（最早一条：${ClosureTell.debtLine(debt.first())}）")
    }

    /** 有信号 ⇒ 过四道闸（可点开／去重／可执行／档位），全过才写一行。 */
    private fun writeOrExplain(
        round: SettledRound,
        prior: List<CommitmentLine>,
    ): SettledReflectionOutcome {
        val c = SettledReflectionRules.candidate(round, wsRoot, globalRoot)
            ?: return notWritten("有信号但提炼不出落点（工具名／技能名是空的）⇒ 不落盘")
        if (!SettledReflectionRules.writable(c)) {
            val bad = c.evidences.count { !it.openable }
            return notWritten("「${c.target}」的证据点不开（${c.evidences.size} 条里 $bad 条不在盘上）⇒ 不落盘")
        }
        val today = ReflectionDedup.today(clock())
        if (ReflectionDedup.suppressed(c, SettledReflectionRules.archiveOf(prior), today)) {
            return notWritten("「${c.dedupKey}」7 天内已入账（或 30 天内被否决）⇒ 不重复写")
        }
        val shape = SettledReflectionRules.promiseOf(c)
        val findings = ClosureRules.promiseFindings(shape)
        if (findings.isNotEmpty()) return notWritten("这条反思不可执行（${findings.joinToString("；")}）⇒ 不落盘")
        return write(c, shape, today)
    }

    /** 判档位（236）＋落一行（236 的档案）；写不进去必须出声。 */
    private fun write(
        c: ReflectionCandidate,
        shape: PromiseShape,
        today: LocalDate,
    ): SettledReflectionOutcome {
        val verdict = AutonomyRules.decide(SettledReflectionRules.proposalOf(c))
        val row = SettledReflectionRules.lineOf(c, verdict, shape, today)
        if (!store.append(row)) {
            val why = "承诺档案写不进去：${CommitmentStore.fileOf(wsRoot).absolutePath}"
            log("收工反思：$why ——**这条反思没保住**（不是「本轮没得反思」）")
            return SettledReflectionOutcome.WriteFailed(row, why)
        }
        log("收工反思：入账一条（${row.key}｜${row.tier}｜证据 ${row.evidence}）")
        return SettledReflectionOutcome.Written(row, AutonomyTell.line(verdict))
    }

    private fun notWritten(why: String): SettledReflectionOutcome {
        val line = "收工反思：$why（R3：没有可用证据就不写盘）"
        log(line)
        return SettledReflectionOutcome.NotWritten(line)
    }

    /**
     * 两层 `技能/_变更.jsonl`（房型照 205 的 `ReflectionRunner.ledgerLines()`）。
     *
     * 为什么不直接调它：那边是 `private`，为复用它去改 205 的文件就等于动了一个已验收批次的
     * 交付物（红线"不改反思判据本身"连同它的文件一起守）。这里是**读盘**，不是判据，
     * 判据（窗口认领）在 [SettledReflectionRules.ledgerInRound]。
     */
    private fun ledgerLines(): List<SkillAuditLine> {
        val out = mutableListOf<SkillAuditLine>()
        for (dir in listOf(SkillRules.projectSkillDir(wsRoot), SkillRules.globalSkillDir(globalRoot))) {
            val f = SkillAudit.fileIn(dir)
            if (!f.isFile) continue
            val text = runCatching { f.readText(Charsets.UTF_8) }.getOrNull() ?: continue
            text.lines().forEachIndexed { i, raw ->
                if (raw.isBlank()) return@forEachIndexed
                val o = runCatching { JSONObject(raw) }.getOrNull() ?: return@forEachIndexed
                val name = o.optString("name")
                if (name.isEmpty()) return@forEachIndexed
                out += SkillAuditLine(
                    file = f,
                    lineNo = i + 1,
                    ts = o.optLong("ts", 0L),
                    scope = o.optString("scope"),
                    name = name,
                    action = o.optString("action"),
                    reason = o.optString("reason"),
                    indexUpdated = o.optBoolean("indexUpdated", false),
                )
            }
        }
        return out
    }

    companion object {
        /** 生产装配（引擎侧只给四样：工作区根、人设口、库、日志口）。 */
        fun of(
            wsRoot: File,
            personaFiles: PersonaFiles,
            db: BaopuDb,
            log: (String) -> Unit,
        ): ReflectionWiring {
            val globalRoot = personaFiles.globalFile().parentFile ?: wsRoot
            return ReflectionWiring(
                wsRoot = wsRoot,
                globalRoot = globalRoot,
                store = CommitmentStore(CommitmentStore.fileOf(wsRoot), log),
                traceOf = { RunTraceStore(db).traceOf(it) },
                log = log,
            )
        }
    }
}
