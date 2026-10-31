// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百零四批 · 触发器的**生产装配**（Context 相关全在这里；判据在 [EvalTriggerRules]、
// 执行链在 [EvalTriggerEngine]，都是零 Android 的纯层）。
//
// 装配四件事：
//   1. **取数**：run_trace（终态）／DecisionLedger（回滚）／skill_event（纠错与成功率）／
//      最后一条用户消息（人话 intent）——全只读，每条带 LIMIT；
//   2. **评测**：163 批的离线机械评测（`eval/evolution/` 的 13 场景＋假决策表，随 APK 进包，
//      见 `StageEvalAssetsTask`）——**零网络、零真模型、零额度**；自动触发烧钱是禁区，
//      网络评测仍归 168 批那条「用户拨过开关才跑」的口；
//   3. **账本**：`filesDir/evolution/trigger-ledger.jsonl`（限频状态＋证据落账，见
//      [TriggerLedgerStore]）；
//   4. **路由**：未办成单（290 批）/进化提案候选（86 批）——**不弹卡**：本文件没有任何
//      UI/通知/卡片的依赖，触发了也只是两本账各多一行。
//
// 全链路不抛异常：收尾触发崩了只留一行日志（65/89 口径：记账不许挡住用户的事）。
package com.example.zhengdao.baopu.evolution.trigger

import android.content.Context
import com.example.zhengdao.baopu.BaopuDb
import com.example.zhengdao.baopu.DecisionKind
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.ProposalCandidate
import com.example.zhengdao.baopu.ProposalCandidateStore
import com.example.zhengdao.baopu.ProposalEvidence
import com.example.zhengdao.baopu.ProposalKind
import com.example.zhengdao.baopu.SkillRefine
import com.example.zhengdao.baopu.evolution.eval.EvolutionCases
import com.example.zhengdao.baopu.evolution.eval.EvolutionRunner
import com.example.zhengdao.baopu.evolution.eval.FakePolicyModel
import com.example.zhengdao.baopu.scout.ScoutDirs
import com.example.zhengdao.baopu.scout.UnfinishedItem
import com.example.zhengdao.baopu.scout.UnfinishedLedger
import com.example.zhengdao.baopu.scout.UnfinishedOutcome
import com.example.zhengdao.baopu.scout.UnfinishedReason
import com.example.zhengdao.baopu.scout.UnfinishedRules
import com.example.zhengdao.core.RunLog
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal object EvalTriggerWiring {

    /** 限频账本落点（App 私有；机器遥测，不进工作区）。 */
    fun ledgerFile(ctx: Context): File = File(File(ctx.filesDir, "evolution"), "trigger-ledger.jsonl")

    /** 进包的离线评测两份数据（仓库 `eval/evolution/` 是唯一真源，构建期复制——见 build.gradle.kts）。 */
    internal const val CASES_ASSET = "eval/evolution/cases.json"
    internal const val POLICY_ASSET = "eval/evolution/fake-policy.json"

    /** 会话收尾触发口（[com.example.zhengdao.baopu.agent.EngineEvent.Settled] 之后调用）。 */
    fun onRunSettled(ctx: Context, db: BaopuDb, sessionId: Long, scope: CoroutineScope) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val inputs = gatherInputs(db, sessionId, System.currentTimeMillis())
                    engine(ctx).onRoundSettled(inputs)
                }
            }.onFailure {
                RunLog.log("[eval-trigger] 收尾触发异常（不阻塞对话）：${it.javaClass.simpleName}: ${it.message}")
            }
        }
    }

    /**
     * ④ 预留：用户手动触发（本批不画界面；设置页/语音那批接这个口）。
     * 同样过限频——手动是入口，不是旁路。
     */
    fun triggerManually(ctx: Context, scope: CoroutineScope, note: String) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { engine(ctx).manual(note) }
            }.onFailure {
                RunLog.log("[eval-trigger] 手动触发异常：${it.javaClass.simpleName}: ${it.message}")
            }
        }
    }

    // ── 引擎装配（评测与账本的真依赖；路由的真写口） ─────────────────────

    private fun engine(ctx: Context): EvalTriggerEngine {
        val store = TriggerLedgerStore(ledgerFile(ctx))
        return EvalTriggerEngine(
            readRows = { store.read() },
            appendRow = { store.append(it) },
            runEval = { offlineEval(ctx) },
            recordUnfinished = { signal, reading, at -> toUnfinished(ctx, signal, reading, at) },
            recordProposal = { signal, reading, at -> toProposal(ctx, signal, reading, at) },
            log = { RunLog.log(it) },
        )
    }

    /**
     * 生产评测：163 批离线机械评测的**基线臂**（candidate=null）。
     *
     * 为什么是它：零网络零额度（自动触发不许烧钱）、确定性（同一份用例逐字复现）、
     * 且判的是真 `GuardPipeline`——工具/围栏这一层有没有被改坏，正是"任务失败了"
     * 之外必须机检的那半边。
     *
     * 临时工作区显式落 App cache：163 批默认的 `Files.createTempDirectory` 只在 JVM
     * 里可靠，Android 的 tmpdir 不保证可写——它以前只被测试调过，本批是第一个生产
     * 调用点，得自己把这个坑填上。
     */
    private fun offlineEval(ctx: Context): TriggerEvalReading {
        val cases = EvolutionCases.parse(assetsText(ctx, CASES_ASSET))
        val policy = FakePolicyModel.parse(assetsText(ctx, POLICY_ASSET))
        val runner = EvolutionRunner(cases, policy) {
            File(ctx.cacheDir, "eval-trigger").apply { mkdirs() }
        }
        val arm = runner.run(candidate = null, arm = "baseline")
        return TriggerEvalReading(
            ran = arm.ran,
            passed = arm.passed,
            total = arm.cases.size,
            passRate = arm.passRate,
            failedCases = arm.cases.filter { !it.ok }.map { it.id },
            elapsedMs = arm.elapsedMs,
        )
    }

    private fun assetsText(ctx: Context, path: String): String =
        ctx.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }

    // ── 路由写口（不弹卡：只往两本既有账里各记一行） ─────────────────────

    /** ① 进「未办成单」：这件事没办成＋已自动评测的读数。 */
    private fun toUnfinished(
        ctx: Context,
        signal: EvalTriggerSignal,
        reading: TriggerEvalReading,
        at: Long,
    ): String {
        val intent = signal.intent.ifBlank { "会话任务（${signal.ref}）" }
        val evalNote = if (reading.error.isBlank()) {
            "已自动评测：机械场景 ${reading.passed}/${reading.total} 过"
        } else {
            "已自动评测但没跑出读数：${reading.error}"
        }
        val item = UnfinishedItem(
            intent = intent,
            outcome = UnfinishedOutcome.FAILED,
            reason = UnfinishedReason.OTHER,
            reasonDetail = evalNote,
            reasonRef = signal.ref,
            fixable = false,
            atMs = at,
        )
        val ok = UnfinishedLedger(ScoutDirs.ledgerDirs(ctx)).record(item)
        return if (ok) "unfinished:${UnfinishedRules.keyOf(item)}" else ""
    }

    /** ②③ 进化提案：PENDING 候选，payload 是**规则拼的事实**（没有一个字来自模型）。 */
    private fun toProposal(
        ctx: Context,
        signal: EvalTriggerSignal,
        reading: TriggerEvalReading,
        at: Long,
    ): String {
        if (signal.target.isBlank()) return ""
        val evalNote = if (reading.error.isBlank()) {
            "自动评测读数：${reading.passed}/${reading.total} 场景过"
        } else {
            "自动评测未跑出读数（${reading.error}）"
        }
        val payload = buildString {
            append(signal.intent.ifBlank { "技能「${signal.target}」触发自动评测" })
            append("；").append(evalNote)
            append("。建议复盘该技能的正文与使用时机（本候选由触发规则生成，待人工决策）。")
        }
        val candidate = ProposalCandidate(
            id = SkillRefine.dedupeKey(signal.target, payload),
            kind = ProposalKind.SKILL_BODY,
            target = signal.target,
            payload = payload,
            evidence = ProposalEvidence(skillEventIds = signal.skillEventIds.take(20), atMs = at),
            createdAt = at,
        )
        val added = ProposalCandidateStore.under(File(ctx.filesDir, MEMORY_ROOT)).appendNew(listOf(candidate))
        return if (added > 0) "proposal:${candidate.id}" else ""
    }

    /** 提案候选库的根（与 `BaopuChatController` 里 86 批的装配同一处）。 */
    private const val MEMORY_ROOT = "抱朴记忆"

    // ── 取数（全只读；每条带 LIMIT；读失败收成空表——判不了就不猜） ───────

    private fun gatherInputs(db: BaopuDb, sessionId: Long, nowMs: Long): EvalTriggerInputs = EvalTriggerInputs(
        nowMs = nowMs,
        sessionId = sessionId,
        runs = recentRuns(db, sessionId),
        rollbackAtMs = rollbackRows(db, sessionId),
        sessionSkillRows = skillRows(db, sessionId, sinceMs = null),
        windowSkillRows = skillRows(db, sessionId = null, sinceMs = nowMs - RATE_WINDOW_MS),
        intent = shortIntent(lastUserText(db, sessionId)),
    )

    /** 成功率窗（30 天）——与 281 `MetricKey.SKILL_SUCCESS.windowDays` 同数（一个口径）。 */
    private const val RATE_WINDOW_MS = 30L * 24 * 60 * 60 * 1000

    /** 本会话最近的 run（id 倒序取尾再正序分组；终态判定要整个状态序列）。 */
    private fun recentRuns(db: BaopuDb, sessionId: Long): List<TriggerRunRow> = runCatching {
        db.read { d ->
            val rows = ArrayList<Triple<String, String, LongArray>>()
            d.rawQuery(
                "SELECT run_id, status, started_at, ended_at FROM run_trace " +
                    "WHERE session_id = ? ORDER BY id DESC LIMIT ?",
                arrayOf(sessionId.toString(), "400"),
            ).use { c ->
                while (c.moveToNext()) {
                    rows += Triple(
                        c.getString(0),
                        c.getString(1),
                        longArrayOf(c.getLong(2), c.getLong(3)),
                    )
                }
            }
            rows.asReversed()
                .groupBy { it.first }
                .map { (runId, rs) ->
                    TriggerRunRow(
                        runId = runId,
                        sessionId = sessionId,
                        startedAtMs = rs.map { it.third[0] }.filter { it > 0 }.minOrNull() ?: 0L,
                        atMs = rs.maxOf { maxOf(it.third[0], it.third[1]) },
                        statuses = rs.map { it.second },
                    )
                }
                .sortedBy { it.atMs }
        }
    }.getOrDefault(emptyList())

    /** 本会话的回滚账行时刻（[DecisionKind.ROLLBACK_OF]；读不到账根＝没有）。 */
    private fun rollbackRows(db: BaopuDb, sessionId: Long): List<Long> {
        val root = DecisionLedger.installedRoot() ?: return emptyList()
        return runCatching {
            DecisionLedger.read(root)
                .filter { it.kind == DecisionKind.ROLLBACK_OF && it.sessionId == sessionId }
                .map { it.atMs }
        }.getOrDefault(emptyList())
    }

    /** 效果信号行（[sessionId] 与 [sinceMs] 至少给一个；按 id 升序）。 */
    private fun skillRows(db: BaopuDb, sessionId: Long?, sinceMs: Long?): List<TriggerSkillRow> {
        val where = StringBuilder("kind = 'effect' AND outcome IS NOT NULL")
        val args = mutableListOf<String>()
        if (sessionId != null) {
            where.append(" AND session_id = ?")
            args += sessionId.toString()
        }
        if (sinceMs != null) {
            where.append(" AND at >= ?")
            args += sinceMs.toString()
        }
        return runCatching {
            db.read { d ->
                val rows = ArrayList<TriggerSkillRow>()
                d.rawQuery(
                    "SELECT id, name, outcome, at FROM skill_event WHERE $where ORDER BY id LIMIT ?",
                    args.toTypedArray() + arrayOf("500"),
                ).use { c ->
                    while (c.moveToNext()) {
                        rows += TriggerSkillRow(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3))
                    }
                }
                rows
            }
        }.getOrDefault(emptyList())
    }

    /** 本会话最后一条用户消息正文（只进内存做人话 intent，一个字不落表）。 */
    private fun lastUserText(db: BaopuDb, sessionId: Long): String = runCatching {
        db.read { d ->
            d.rawQuery(
                "SELECT p.payload FROM message m JOIN part p ON p.message_id = m.id " +
                    "WHERE m.session_id = ? AND m.role = 'user' AND p.kind = 'text' AND p.seq = 0 " +
                    "ORDER BY m.id DESC LIMIT 1",
                arrayOf(sessionId.toString()),
            ).use { c -> if (c.moveToNext()) c.getString(0) ?: "" else "" }
        }
    }.getOrDefault("")

    /** intent 取首行、截短——未办成单的"想做的事"是一句人话，不是一段日志。 */
    private fun shortIntent(text: String): String {
        val first = text.trim().lineSequence().firstOrNull().orEmpty().trim()
        if (first.length <= INTENT_MAX) return first
        return first.take(INTENT_MAX) + "…"
    }

    private const val INTENT_MAX = 40
}
