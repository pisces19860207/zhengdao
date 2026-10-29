// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十二批 · 对拍器的**取数壳**——脏活（读 run_trace、读 208）集中在这一个文件里，
// 判据全在 [TraceCrossCheck]（纯函数，JVM 直测）。三条纪律照 PatrolSources 的房型：
// 1. **只读**：只有 rawQuery 与 DecisionLedger.read，一个写口都没有
//    （对拍器不许写账——派工单负例③；InspectReadOnlyGateTest 的全层扫描照钉本文件）；
// 2. 每条 SQL 带 `?` 绑定与 LIMIT（抽检不是全检）；
// 3. 读失败不炸：出 [CrossCheckPass] 的空读数，巡按照常有字。
//
// 为什么不 import `RunTraceStore`／`TraceRow`：勘验层的门禁钉着「agent 包只许借 227 那一条判据」
// （InspectReadOnlyGateTest.负例③）。所以轨迹行在这里折成 [TraceEdge]（最小形状，AuditGapCheck
// 的 WriteTrace 同款先例），列名与线值照 `BaopuSql.RUN_TRACE_COLUMNS` 与 TracePhase/TraceStatus
// 的字面量抄——改一处要连着改两处，两侧的测试都钉着。
package com.example.zhengdao.baopu.inspect

import android.database.Cursor
import com.example.zhengdao.baopu.BaopuDb
import com.example.zhengdao.baopu.DecisionLedger
import java.io.File

/** 一次 run 的跨度（从 run_trace 折出；对拍按它选窗）。 */
internal data class RunSpan(
    val runId: String,
    val sessionId: Long,
    val startedAt: Long,
    val endedAt: Long,
)

internal object TraceCrossCheckSources {

    /** 一趟巡按最多对拍几个 run（最近优先；对拍是逐 run 计数，贵在行数不在 run 数）。 */
    const val RUNS_LIMIT = 20

    /**
     * 窗口内最近的 run（旧→新返回——升格的「连续」按起跑顺序数）。
     *
     * 列名照 `BaopuSql.RUN_TRACE_DDL`；不 import agent 包（门禁），改列名时这里跟着改，
     * 两侧测试都钉着。
     */
    fun recentRuns(db: BaopuDb, fromMs: Long, limit: Int = RUNS_LIMIT): List<RunSpan> = runCatching {
        db.read { d ->
            d.rawQuery(
                "SELECT run_id, session_id, MIN(started_at), MAX(ended_at) FROM run_trace " +
                    "WHERE ended_at >= ? GROUP BY run_id ORDER BY MIN(id) DESC LIMIT ?",
                arrayOf(fromMs.toString(), limit.toString()),
            ).use { c ->
                val rows = ArrayList<RunSpan>(c.count)
                while (c.moveToNext()) {
                    rows += RunSpan(c.getString(0), c.getLong(1), c.getLong(2), c.getLong(3))
                }
                rows.asReversed()
            }
        }
    }.getOrDefault(emptyList())

    /** 一个 run 的全部轨迹行（seq 升序＝发生顺序）。 */
    fun traceOf(db: BaopuDb, runId: String): List<TraceEdge> {
        if (runId.isBlank()) return emptyList()
        return runCatching {
            db.read { d ->
                d.rawQuery(
                    "SELECT run_id, session_id, seq, step, phase, tool, status, duration_ms, " +
                        "failure_kind, started_at, ended_at FROM run_trace " +
                        "WHERE run_id = ? ORDER BY seq ASC LIMIT ?",
                    arrayOf(runId, InspectLimits.READ_LIMIT.toString()),
                ).use { c ->
                    val rows = ArrayList<TraceEdge>(c.count)
                    while (c.moveToNext()) rows += edge(c)
                    rows
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun edge(c: Cursor): TraceEdge = TraceEdge(
        runId = c.getString(0),
        sessionId = c.getLong(1),
        seq = c.getInt(2),
        step = c.getInt(3),
        phase = c.getString(4),
        tool = c.getString(5),
        status = c.getString(6),
        durationMs = c.getLong(7),
        failureKind = c.getString(8),
        startedAt = c.getLong(9),
        endedAt = c.getLong(10),
    )

    /**
     * 对拍一趟（PatrolRunner 挂在巡按里调）：窗内最近 run 逐个四维对拍 ⇒ findings ＋ Opinion。
     *
     * 不写任何账（连自己的都不写）；summaryLine 交给调用方进 RunLog。
     */
    fun pass(db: BaopuDb, root: File, nowMs: Long): CrossCheckPass {
        val fromMs = PatrolRunner.windowFrom(nowMs)
        val runs = recentRuns(db, fromMs)
        if (runs.isEmpty()) {
            return CrossCheckPass(0, emptyList(), emptyList(), "对拍：窗内没有 run")
        }
        val ledger = runCatching { DecisionLedger.read(root, InspectLimits.READ_LIMIT) }
            .getOrDefault(emptyList())
        val all = runs.flatMap { run -> TraceCrossCheck.checkRun(run.runId, traceOf(db, run.runId), ledger, PatrolSources.writeToolNames()) }
        val findings = all.filter { it.verdict != CrossCheckVerdict.CLEAR }
        val opinions = TraceCrossCheck.opinions(all)
        val mismatch = findings.count { it.verdict == CrossCheckVerdict.MISMATCH }
        val missing = findings.count { it.verdict == CrossCheckVerdict.MISSING_SIDE }
        val summary = "对拍 ${runs.size} run：不符 $mismatch，缺侧 $missing"
        return CrossCheckPass(runs.size, findings, opinions, summary)
    }
}
