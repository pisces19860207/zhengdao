// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第八十七批 · 运行时「好」R1：**RunTrace / 回放**（§B 91 §1）。
//
// 病灶：一轮跑完，能查到的只有 `run_log` 的「状态行」——Thinking/Acting/Compressing
// 一行一行躺着，但**串不成一次运行**（哪几步是一次 run、每步花了多久、调了哪个工具、
// 参数是什么形状、失败判的哪一类、策略给的是重试还是换通道——全散着或干脆没记）。
// 于是「这次为什么慢/为什么停」只能靠猜。
//
// 本文件给一次运行一条**结构化轨迹**：一行一个边界（步 / 工具 / 压缩），字段全是
// 枚举、计数、hash、时刻——**不存正文**（不存提示词、不存工具参数、不存模型回答）。
// 红线的执行方式是物理的：`TraceRow` 里根本没有能装正文的字段，
// 工具参数只留 [RunTraceRules.argsHash] 的 16 位摘要（判据 T4 从源码层钉）。
//
// 写入点 = 引擎**现有的**边界（步开步/读数/工具批/压缩），不新造事件总线；
// 读取 = [RunTraceStore.traceOf]（本批不做 UI，留给日志与后续界面）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.BaopuDb
import com.example.zhengdao.baopu.BaopuSql
import com.example.zhengdao.baopu.LlmUsage
import java.security.MessageDigest
import org.json.JSONObject

/** 轨迹阶段（派工单 §1 点名的三种边界，一种不多）。 */
internal enum class TracePhase { THINK, ACT, COMPRESSING }

/**
 * 一次边界的结果状态。
 *
 * [CONFIRM] 单列：那次工具调用没过（停在等人批准），它不是「成功」也不是「失败」，
 * 记成 OK 会让回放的人以为它真跑了（第五十四批 P1-1 的同一类谎言）。
 */
internal enum class TraceStatus { OK, FAILED, REJECTED, CONFIRM, TIMEOUT, SKIPPED, CANCELLED }

/**
 * 一行轨迹（＝库表 `run_trace` 的一行；字段与 [RunTraceRules.COLUMNS] 一一对应）。
 *
 * 没有 `text`/`payload`/`args`/`prompt` 这类字段是**故意的**：新增字段若想装正文，
 * 判据 T4 会红（那条用例扫本类声明与建表 DDL 的列名）。
 */
internal data class TraceRow(
    val runId: String,
    val sessionId: Long,
    val step: Int,
    val seq: Int,
    val phase: TracePhase,
    val status: TraceStatus,
    val tool: String = "",
    val argsHash: String = "",
    /** 本次边界的墙钟耗时；[RunTraceRules.UNKNOWN_DURATION_MS] = 没测到（不编 0）。 */
    val durationMs: Long = 0,
    val tokensIn: Int = 0,
    val tokensOut: Int = 0,
    /** 失败分类（[FailureKind] 名；空串＝这次没失败）。 */
    val failureKind: String = "",
    /** 策略结论（[FailurePolicy.decisionLabel]；空串＝没走到策略）。 */
    val decision: String = "",
    val startedAt: Long = 0,
    val endedAt: Long = 0,
) {
    /** 落库语句（薄封装：SQL 全在 [BaopuSql]，本类不拼 SQL）。 */
    fun toStmt(): BaopuSql.Stmt = BaopuSql.insertRunTrace(
        runId = runId,
        sessionId = sessionId,
        step = step,
        seq = seq,
        phase = phase.name,
        tool = tool,
        argsHash = argsHash,
        status = status.name,
        durationMs = durationMs,
        tokensIn = tokensIn,
        tokensOut = tokensOut,
        failureKind = failureKind,
        decision = decision,
        startedAt = startedAt,
        endedAt = endedAt,
    )
}

/** 轨迹的纯规则（无 IO / 无 Android / 无时钟；JVM 直测）。 */
internal object RunTraceRules {

    /** 「没测到耗时」的哨兵值（与 `BaopuUsageStep.elapsedMs` 同口径：负数＝未知，不写 0 假装知道）。 */
    const val UNKNOWN_DURATION_MS = -1L

    /** 工具名落库上限（工具名是枚举级短串；超了说明有人往里塞了别的东西）。 */
    const val TOOL_NAME_MAX = 64

    /** args 摘要长度（sha256 前 16 位十六进制：够区分、不够反推）。 */
    const val ARGS_HASH_LEN = 16

    /** 库表列序（转手 [BaopuSql.RUN_TRACE_COLUMNS]：SQL 与游标映射共用一份，不抄第二遍）。 */
    val COLUMNS: List<String> = BaopuSql.RUN_TRACE_COLUMNS

    /**
     * 一次运行的 id：`r<sessionId>@<startedAtMs>`（＋可选序号）。
     *
     * 为什么不用 UUID：回放时要能一眼看出「哪个会话、什么时候起的」；同一毫秒起两次
     * （同一会话不可能，单活跃门挡着）用 [nonce] 兜底，仍然可读。
     */
    fun newRunId(sessionId: Long, startedAtMs: Long, nonce: Int = 0): String =
        if (nonce == 0) "r$sessionId@$startedAtMs" else "r$sessionId@$startedAtMs#$nonce"

    /**
     * 工具参数的摘要（**只留摘要，不留参数**）。
     *
     * 为什么是 hash 而不是「截断后的参数」：参数里可能就是整段正文（`write_file` 的
     * content）、绝对路径、甚至用户隐私。派工单 §1 红线的原话是「只存 hash/枚举/计数」。
     *
     * 用途：同一轮里「同一个工具、同样的参数」重试 ⇒ 两行 `args_hash` 相等，
     * 回放时一眼看出是重试而不是两次不同的调用。
     */
    fun argsHash(args: JSONObject?): String = argsHashOf(args?.toString() ?: "")

    /** 同上（已有字符串形态时用）。 */
    fun argsHashOf(raw: String): String {
        if (raw.isEmpty()) return ""
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(ARGS_HASH_LEN)
        for (b in digest) {
            if (hex.length >= ARGS_HASH_LEN) break
            hex.append("0123456789abcdef"[(b.toInt() shr 4) and 0x0f])
            if (hex.length >= ARGS_HASH_LEN) break
            hex.append("0123456789abcdef"[b.toInt() and 0x0f])
        }
        return hex.toString()
    }

    /** 工具名收口（超长截断；非空才留，空名记空串）。 */
    fun capToolName(name: String): String = name.take(TOOL_NAME_MAX)

    /**
     * 边界耗时：起止都给全 ⇒ 差值（**负数一律归 0**：设备时钟回拨不该让耗时为负）；
     * 起点未知（≤0）⇒ [UNKNOWN_DURATION_MS]。
     */
    fun durationBetween(startedAtMs: Long, endedAtMs: Long): Long =
        if (startedAtMs <= 0L || endedAtMs <= 0L) {
            UNKNOWN_DURATION_MS
        } else {
            (endedAtMs - startedAtMs).coerceAtLeast(0L)
        }

    /** 计数非负（服务端偶尔回负数或 null；negatives 一律归 0，不把脏数写进账本）。 */
    fun nonNegative(value: Int?): Int = (value ?: 0).coerceAtLeast(0)
}

/**
 * 轨迹记录器（引擎线程上的**内存态 ＋ 逐行落库**）。
 *
 * 为什么不直接在引擎里拼行：引擎那 1900 行已经够挤了，而且边界的「起止时刻」必须
 * 有人替它记（步开始于 Thinking 落库那一刻，结束于模型读数到手那一刻——中间隔着一次
 * 网络往返）。这里只管三件事：runId 分配、序号自增、时刻配对；落库交给注入的 [emit]。
 *
 * **单向**：只有引擎往里写，没有读回（回放走 [RunTraceStore] 读库）。
 * [emit] 在引擎线程上同步调用，实现方只许「丢给 db 单写者线程」，不许 `.get()` 阻塞
 * （trace 是账本，不该让一次运行等它）。
 */
internal class TraceJournal(
    private val emit: (TraceRow) -> Unit,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private var runId: String = ""
    private var sessionId: Long = -1L
    private var seq: Int = 0
    private val stepStartedAt = HashMap<Int, Long>()
    private val stepTokens = HashMap<Int, IntArray>()

    /** 开一次运行（每次 `runLoop` 进来一次；续跑是新 run）。返回本次 runId。 */
    fun newRun(sessionId: Long, startedAtMs: Long = clock(), nonce: Int = 0): String {
        this.sessionId = sessionId
        this.runId = RunTraceRules.newRunId(sessionId, startedAtMs, nonce)
        seq = 0
        stepStartedAt.clear()
        stepTokens.clear()
        return runId
    }

    /** 当前 runId（空串＝还没开跑；UI/日志按它查 [RunTraceStore.traceOf]）。 */
    fun currentRunId(): String = runId

    /** 开一步（`Thinking` 落库那一刻）：只记起点，不出行。 */
    fun beginStep(step: Int, at: Long = clock()) {
        stepStartedAt[step] = at
        stepTokens.remove(step)
    }

    /** 记一步的读数（`persistUsage` 同一处调；没读到也调，只是留空）。 */
    fun noteUsage(step: Int, usage: LlmUsage?) {
        if (usage == null) return
        stepTokens[step] = intArrayOf(
            RunTraceRules.nonNegative(usage.promptTokens),
            RunTraceRules.nonNegative(usage.completionTokens),
        )
    }

    /**
     * 收一步（模型这一轮有结论了：正常回答/要调工具/坏参数/失败）。
     *
     * @param failureKind 失败分类（正常结束传 null）
     * @param decision 策略结论（正常结束传 null）
     */
    fun settleStep(
        step: Int,
        status: TraceStatus,
        failureKind: FailureKind? = null,
        decision: FailureAction? = null,
        at: Long = clock(),
    ) {
        val start = stepStartedAt[step] ?: 0L
        val tokens = stepTokens[step]
        emit(
            TraceRow(
                runId = runId,
                sessionId = sessionId,
                step = step,
                seq = seq++,
                phase = TracePhase.THINK,
                status = status,
                durationMs = RunTraceRules.durationBetween(start, at),
                tokensIn = tokens?.get(0) ?: 0,
                tokensOut = tokens?.get(1) ?: 0,
                failureKind = failureKind?.name ?: "",
                decision = decision?.let { FailurePolicy.decisionLabel(it) } ?: "",
                startedAt = start.coerceAtLeast(0L),
                endedAt = at,
            ),
        )
    }

    /**
     * 记一次工具调用（工具批边界）。
     *
     * @param startedAtMs 起点；**审批路径没有起点**（耗在等人点按钮上，工具本身只是最后那一下）
     *   ⇒ 传 0 记 [RunTraceRules.UNKNOWN_DURATION_MS]，绝不把「等人的时间」冒充「工具的耗时」。
     */
    fun noteTool(
        step: Int,
        tool: String,
        args: JSONObject?,
        startedAtMs: Long,
        endedAtMs: Long = clock(),
        status: TraceStatus,
        failureKind: FailureKind? = null,
    ) {
        emit(
            TraceRow(
                runId = runId,
                sessionId = sessionId,
                step = step,
                seq = seq++,
                phase = TracePhase.ACT,
                status = status,
                tool = RunTraceRules.capToolName(tool),
                argsHash = RunTraceRules.argsHash(args),
                durationMs = RunTraceRules.durationBetween(startedAtMs, endedAtMs),
                failureKind = failureKind?.name ?: "",
                startedAt = startedAtMs.coerceAtLeast(0L),
                endedAt = endedAtMs,
            ),
        )
    }

    /** 记一次压缩（`COMPRESSING` 边界；成功/跳过/失败都记一行，回放才看得出「压了几次、成没成」）。 */
    fun noteCompress(
        step: Int,
        startedAtMs: Long,
        endedAtMs: Long = clock(),
        status: TraceStatus,
        failureKind: FailureKind? = null,
    ) {
        emit(
            TraceRow(
                runId = runId,
                sessionId = sessionId,
                step = step,
                seq = seq++,
                phase = TracePhase.COMPRESSING,
                status = status,
                durationMs = RunTraceRules.durationBetween(startedAtMs, endedAtMs),
                failureKind = failureKind?.name ?: "",
                startedAt = startedAtMs.coerceAtLeast(0L),
                endedAt = endedAtMs,
            ),
        )
    }
}

/**
 * 轨迹读取（`traceOf(runId)`）。
 *
 * 本批**不做 UI**（派工单 §1「读取：供日志/后续 UI 用」）：这里只把库里的行还原成
 * [TraceRow]，调用方（日志、诊断、以后的回放面板）自己决定怎么展示。
 *
 * 老库可读：v11 及更早的库升级到 v12 只**加表**（[BaopuSql.RUN_TRACE_DDL] 全是
 * `IF NOT EXISTS`），`run_trace` 空表 ⇒ 老 run 的 `traceOf` 返回空表，不抛错。
 */
internal class RunTraceStore(private val db: BaopuDb) {

    /** 一次运行的全部轨迹（按 `seq` 升序 ＝ 发生顺序）。 */
    fun traceOf(runId: String): List<TraceRow> {
        if (runId.isBlank()) return emptyList()
        val stmt = BaopuSql.selectRunTrace(runId)
        return db.read { d ->
            d.rawQuery(stmt.sql, stmt.args).use { c ->
                val rows = ArrayList<TraceRow>(c.count)
                while (c.moveToNext()) rows.add(readRow(c))
                rows
            }
        }
    }

    /** 该会话最近一次有轨迹的 runId（没有 ⇒ null；日志页「看上一次」用）。 */
    fun latestRunId(sessionId: Long): String? {
        val stmt = BaopuSql.selectLatestRunTrace(sessionId)
        return db.read { d ->
            d.rawQuery(stmt.sql, stmt.args).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }
    }

    private fun readRow(c: android.database.Cursor): TraceRow = TraceRow(
        runId = c.getString(c.idx("run_id")),
        sessionId = c.getLong(c.idx("session_id")),
        step = c.getInt(c.idx("step")),
        seq = c.getInt(c.idx("seq")),
        phase = enumOr(c.getString(c.idx("phase")), TracePhase.THINK),
        status = enumOr(c.getString(c.idx("status")), TraceStatus.FAILED),
        tool = c.getString(c.idx("tool")),
        argsHash = c.getString(c.idx("args_hash")),
        durationMs = c.getLong(c.idx("duration_ms")),
        tokensIn = c.getInt(c.idx("tokens_in")),
        tokensOut = c.getInt(c.idx("tokens_out")),
        failureKind = c.getString(c.idx("failure_kind")),
        decision = c.getString(c.idx("decision")),
        startedAt = c.getLong(c.idx("started_at")),
        endedAt = c.getLong(c.idx("ended_at")),
    )

    /**
     * 列下标（`getColumnIndexOrThrow` 而非 `getColumnIndex`）。
     *
     * 为什么：`getColumnIndex` 找不到列时返回 **-1**，而 `Cursor.getLong(-1)` 是越界调用
     * ——lint 的 `Range` 规则直接把 15 处调用判成 Error（第八十七批实测：178→193）。
     * 本类的 SELECT 列序由 [BaopuSql.RUN_TRACE_COLUMNS] 与建表 DDL 双向钉死，
     * 列**必然**存在 ⇒ 真缺列就该当场炸（抛 IllegalArgumentException），
     * 而不是读一个下标 -1 出来。
     */
    private fun android.database.Cursor.idx(name: String): Int = getColumnIndexOrThrow(name)

    /**
     * 枚举还原：库里出现没见过的字面量（未来版本降级、手改库）⇒ 落到兜底值，
     * **不抛**——回放工具读不了历史账，比读出来标个兜底更糟。
     */
    private inline fun <reified T : Enum<T>> enumOr(raw: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == raw } ?: fallback
}
