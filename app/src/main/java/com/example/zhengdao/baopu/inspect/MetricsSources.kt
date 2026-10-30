// 独立开发声明：本文件为本项目原创，不含第三方源码拷贝。
//
// 第二百八十一批：读数层的**取数壳**。算在 [Metrics]（纯函数），这里只做三件事：
// 读库（`rawQuery`）、读账本（JSONL）、读技能验收快照（`验收.json`）。
//
// 三条纪律与 239 的 `PatrolSources` 同款：
// 1. **只读**：没有 `execSQL`／没有追加口／没有建目录（`InspectReadOnlyGateTest` 从源码层钉）；
// 2. **每条 SQL 都带 `LIMIT`**（读数不是全库导出；超限按新→旧截断，与巡按同一纪律）；
// 3. **取数窗取最大窗**（30 天），各指标再按自己的窗口（7/30 天）筛——
//    窗口口径只在 [MetricKey.windowDays] 一处，取数层不另抄一份。
//
// 一处实话：M9 的判词没有持久账（281 不写新账，账本白名单不在本批租约里），
// 所以 [snapshot] 的 M9 由调用方递进来；空表就是"这周没判"，读数会如实 unknown。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.AcceptanceState
import com.example.zhengdao.baopu.BaopuDb
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.DecisionRecord
import com.example.zhengdao.baopu.DecisionReview
import com.example.zhengdao.baopu.SkillRules
import com.example.zhengdao.baopu.TeachReplayStore
import java.io.File

/** 取数上限（读数一次最多看多少行/多少个目录；别把整库搬进内存）。 */
internal object MetricsBudget {

    /** 一次最多读多少行日志/轨迹/子任务（新→旧截断）。 */
    const val ROWS = 2_000

    /** 取数窗（天）：等于最大的指标窗（误判率与技能成功率都是 30 天）。 */
    const val SPAN_DAYS = 30

    /** 一次最多读多少次巡按。 */
    const val PATROLS = 60

    /** 每层技能目录最多看多少个。 */
    const val SKILL_DIRS = 200

    private const val DAY_MS = 86_400_000L

    /** 取数起点（各指标自己的窗在 [Metrics] 里再筛）。 */
    fun fromOf(nowMs: Long): Long = nowMs - SPAN_DAYS * DAY_MS
}

/** 读数的取数口（供界面/装配层调用；M9 判词由调用方给）。 */
internal object MetricsSources {

    /** 记忆根（208 装配在哪，账本就落哪；没装配 ⇒ null，调用方直接不出数）。 */
    fun memoryRoot(): File? = DecisionLedger.installedRoot()

    /** 一次取全（任何一路失败 ⇒ 那一路为空表，其余照算；绝不因一路缺数把别的数编出来）。 */
    fun snapshot(
        db: BaopuDb,
        projectRoot: File,
        memoryRoot: File,
        nowMs: Long,
        m9: List<M9Judgment> = emptyList(),
    ): Metrics.Inputs {
        val from = MetricsBudget.fromOf(nowMs)
        return Metrics.Inputs(
            nowMs = nowMs,
            ledger = ledger(memoryRoot),
            reviews = reviews(memoryRoot),
            logs = logSteps(db, from),
            runs = runSteps(db, from),
            runSummaries = runSummaries(db, from),
            requests = toolRequests(db, from),
            subTasks = subTasks(db, from),
            skills = skills(projectRoot, memoryRoot),
            patrols = patrols(memoryRoot),
            acts = PatrolSources.writeTraces(db, from),
            m9 = m9,
        )
    }

    /** 208 账本（读侧上限照 208 自己的 `READ_LIMIT`）。 */
    fun ledger(root: File): List<DecisionRecord> = DecisionLedger.read(root)

    /** 219 抽检结论。 */
    fun reviews(root: File): List<DecisionReview.Entry> = DecisionReview.read(root)

    /** 239 巡按台账（新→旧，最多 [MetricsBudget.PATROLS] 次）。 */
    fun patrols(root: File, limit: Int = MetricsBudget.PATROLS): List<PatrolEntry> =
        PatrolLedger.read(root, limit)

    /** `run_log` 的动作/摆卡行（只取会话、步、状态、时刻；正文类字段一概不出库）。 */
    fun logSteps(db: BaopuDb, fromMs: Long, limit: Int = MetricsBudget.ROWS): List<LogStep> = runCatching {
        db.read { d ->
            val rows = ArrayList<LogStep>()
            d.rawQuery(
                "SELECT id, session_id, step, state, created_at FROM run_log " +
                    "WHERE created_at >= ? ORDER BY id DESC LIMIT ?",
                arrayOf(fromMs.toString(), limit.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    rows += LogStep(c.getLong(0), c.getLong(1), c.getInt(2), c.getString(3), c.getLong(4))
                }
            }
            rows
        }
    }.getOrDefault(emptyList())

    /**
     * `run_trace` 的步（任务＝run_id；同一步的多行按 `started_at` 最小那条取）。
     *
     * 为什么按 (run_id, session_id, step) 分组：一个 run 的同一 step 可能落多行轨迹
     * （阶段不同），不分组的"任务数"会被同一个 run 重复放大。
     */
    fun runSteps(db: BaopuDb, fromMs: Long, limit: Int = MetricsBudget.ROWS): List<RunStep> = runCatching {
        db.read { d ->
            val rows = ArrayList<RunStep>()
            d.rawQuery(
                "SELECT run_id, session_id, step, MIN(started_at) FROM run_trace " +
                    "WHERE started_at >= ? GROUP BY run_id, session_id, step " +
                    "ORDER BY MIN(started_at) DESC LIMIT ?",
                arrayOf(fromMs.toString(), limit.toString()),
            ).use { c ->
                while (c.moveToNext()) rows += RunStep(c.getString(0), c.getLong(1), c.getInt(2), c.getLong(3))
            }
            rows
        }
    }.getOrDefault(emptyList())

    /**
     * `run_trace` 按 run 聚合的终态摘要（任务成功率按它算；口径 §3.1／§3.6#4）。
     *
     * 取数：`ORDER BY id DESC LIMIT ?` 拿**最新**的 N 行（超限按新→旧截断，与巡按同一纪律），
     * 再翻回 `id` 升序分组——状态序列的顺序就是发生顺序，终态判定靠它。
     * 窗口判据取 `MAX(started_at, ended_at)`：批准后补记那行 `started_at = 0`（`RunTrace.kt:235`），
     * 按 `started_at` 开窗会把它读丢 ⇒ 末行退回 CONFIRM、好端端一个跑完的 run 被判成「还在等批」。
     * 截断的代价如实说：跨截断线的老 run 只读到后半段（起点被抬高、回滚归属窗变窄），不补、不猜。
     *
     * 为什么不用 GROUP BY 在 SQL 里做：终态判定要**整个状态序列**（最后一行 + 中途有没有救回来的 OK），
     * 聚成一个字符串就又多一套口径；判定留在 [RunVerdict] 一处。
     */
    fun runSummaries(db: BaopuDb, fromMs: Long, limit: Int = MetricsBudget.ROWS): List<RunSummary> = runCatching {
        db.read { d ->
            val rows = ArrayList<TraceRowLite>()
            d.rawQuery(
                "SELECT run_id, session_id, status, started_at, ended_at FROM run_trace " +
                    "WHERE MAX(started_at, ended_at) >= ? ORDER BY id DESC LIMIT ?",
                arrayOf(fromMs.toString(), limit.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    rows += TraceRowLite(
                        runId = c.getString(0),
                        sessionId = c.getLong(1),
                        status = c.getString(2),
                        startedAt = c.getLong(3),
                        endedAt = c.getLong(4),
                    )
                }
            }
            rows.asReversed().groupBy { it.runId }.map { (runId, rs) -> summarize(runId, rs) }.sortedBy { it.runId }
        }
    }.getOrDefault(emptyList())

    /** 一组同 run 的行（已按 `id` 升序）⇒ 一个终态摘要。 */
    private fun summarize(runId: String, rows: List<TraceRowLite>): RunSummary = RunSummary(
        runId = runId,
        sessionId = rows.first().sessionId,
        startedAtMs = rows.map { it.startedAt }.filter { it > 0 }.minOrNull() ?: rows.first().startedAt,
        atMs = rows.maxOf { maxOf(it.startedAt, it.endedAt) },
        statuses = rows.map { it.status },
    )

    /**
     * `run_trace` 的**工具请求**（人介入率／不可逆动作占比按它算；口径 §3.2）。
     *
     * 去重键是 **(run_id, step, tool, args_hash) 四元组**：
     * - 同一步批准后会落两行（挂起 CONFIRM、批准后按真结果再记一行，`RunEngine.kt:3629`），
     *   两行的 tool 与 args_hash 一样 ⇒ 聚合成**一个**请求；
     * - 批编排一个 step 能连跑多个工具调用（`RunEngine.kt:1953`）⇒ 不同 tool 的请求必须分开算，
     *   所以不用 (run_id, step) 两元组。
     *
     * `tool = ''` 的行（THINK／COMPRESSING 边界）不是工具请求，直接不进。
     * 时刻取 `MAX(started_at)`：批准后补的那行 `started_at = 0`，取最小会把请求拖出滚动窗。
     * 窗口判据同 `runSummaries()`（§3.6#4）取 `MAX(started_at, ended_at)`：那行的 `status = OK` 也在 `started_at = 0` 上，
     * 按 `started_at` 开窗就看不见「摆卡获批后真执行了」——不可逆动作占比会把它当成没执行。
     */
    fun toolRequests(db: BaopuDb, fromMs: Long, limit: Int = MetricsBudget.ROWS): List<ToolRequest> = runCatching {
        db.read { d ->
            val rows = ArrayList<ToolRequest>()
            d.rawQuery(
                "SELECT run_id, session_id, step, tool, MAX(started_at), " +
                    "MAX(CASE WHEN status = 'CONFIRM' THEN 1 ELSE 0 END), " +
                    "MAX(CASE WHEN status = 'OK' THEN 1 ELSE 0 END) " +
                    "FROM run_trace WHERE MAX(started_at, ended_at) >= ? AND tool <> '' " +
                    "GROUP BY run_id, step, tool, args_hash " +
                    "ORDER BY MAX(started_at) DESC LIMIT ?",
                arrayOf(fromMs.toString(), limit.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    rows += ToolRequest(
                        runId = c.getString(0),
                        sessionId = c.getLong(1),
                        step = c.getInt(2),
                        tool = c.getString(3),
                        atMs = c.getLong(4),
                        hasConfirm = c.getInt(5) == 1,
                        hasOk = c.getInt(6) == 1,
                    )
                }
            }
            rows
        }
    }.getOrDefault(emptyList())

    /** `sub_task` 的终态行（`ended_at` 为窗口依据；未结束的 ended_at=0 自然不进窗）。 */
    fun subTasks(db: BaopuDb, fromMs: Long, limit: Int = MetricsBudget.ROWS): List<SubTaskRow> = runCatching {
        db.read { d ->
            val rows = ArrayList<SubTaskRow>()
            d.rawQuery(
                "SELECT task_id, status, ended_at FROM sub_task " +
                    "WHERE ended_at >= ? ORDER BY ended_at DESC LIMIT ?",
                arrayOf(fromMs.toString(), limit.toString()),
            ).use { c -> while (c.moveToNext()) rows += SubTaskRow(c.getString(0), c.getString(1), c.getLong(2)) }
            rows
        }
    }.getOrDefault(emptyList())

    /**
     * 两层技能目录的验收快照（`技能/<名>/验收.json`）。
     *
     * 只 `listFiles` 数目录、只读有 `验收.json` 的那几个；整份读不出（schema 不对/坏文件）
     * ⇒ 这一条不进读数（不静默当"零样本"以外的任何东西）。
     */
    fun skills(
        projectRoot: File,
        globalRoot: File,
        cap: Int = MetricsBudget.SKILL_DIRS,
    ): List<SkillSnapshot> = listOf(
        "project" to SkillRules.projectSkillDir(projectRoot),
        "global" to SkillRules.globalSkillDir(globalRoot),
    ).flatMap { (scope, dir) ->
        val subs = runCatching {
            dir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }.orEmpty()
        }.getOrDefault(emptyList())
        subs.take(cap).mapNotNull { sub ->
            val file = File(sub, TeachReplayStore.ACCEPTANCE_FILE)
            if (!file.isFile) return@mapNotNull null
            val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return@mapNotNull null
            AcceptanceState.fromJson(text)?.let { SkillSnapshot(scope, sub.name, it) }
        }
    }
}

/** 取数中间行（只在这一个文件里用；对外只出 [RunSummary]）。 */
private data class TraceRowLite(
    val runId: String,
    val sessionId: Long,
    val status: String,
    val startedAt: Long,
    val endedAt: Long,
)
