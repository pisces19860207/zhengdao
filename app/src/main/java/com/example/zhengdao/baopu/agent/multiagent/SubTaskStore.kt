// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单《第一百二十七批 · 多 Agent 协作 v1》§1「审计：父子任务 id、
// 耗时、失败原因可查」与开工闸门《多Agent协作v1-协作模型-2026-10-21.md》§7。
//
// 房型：接口在本包（协调器只认接口 ⇒ JVM 单测注内存假件），生产实现
// [BaopuSubTaskStore] 走 BaopuDb 单写者（SQL 文本在 BaopuSql，与全仓同一规矩）。
// 一切写失败只 log 不抛——审计是账本不是闸门（与 run_trace/artifact 同房型）。
package com.example.zhengdao.baopu.agent.multiagent

import com.example.zhengdao.baopu.BaopuDb
import com.example.zhengdao.baopu.BaopuSql

/** 子任务审计存储（协调器唯一的数据出口）。 */
internal interface SubTaskStore {
    /** 分解落账：整批 PENDING（返回行 id 与入参同序）。 */
    fun insertBatch(tasks: List<SubTask>, sessionId: Long, now: Long)

    /** 一条开跑（PENDING ⇒ RUNNING）。 */
    fun markRunning(task: SubTask, at: Long)

    /** 一条进终态（连同结果/原因/步数）。 */
    fun markFinished(result: SubTaskResult, goal: String, input: String, acceptance: String, tools: List<String>, attempts: Int)

    /** 审计查询：一笔分解的全部子任务（按 seq）。 */
    fun childrenOf(parentId: String): List<SubTaskRecord>

    /** 审计查询：一个会话的全部分解开销（按 id 正序）。 */
    fun ofSession(sessionId: Long): List<SubTaskRecord>
}

/**
 * BaopuDb 实现（v16 `sub_task` 表）。
 *
 * 写走 [BaopuDb.write] 的 Future 但**不 `.get()`**（与轨迹落库同口径：账本不该
 * 让一次运行等它写盘；顺序由单写者线程保证）。读走 [BaopuDb.read]（调用方线程）。
 */
internal class BaopuSubTaskStore(
    private val db: BaopuDb,
    private val log: (String) -> Unit = {},
) : SubTaskStore {

    override fun insertBatch(tasks: List<SubTask>, sessionId: Long, now: Long) {
        tasks.forEach { t ->
            runCatching {
                db.write { d ->
                    val stmt = BaopuSql.insertSubTask(
                        taskId = t.id,
                        parentId = t.parentId,
                        sessionId = sessionId,
                        seq = t.seq,
                        goal = t.goal,
                        input = t.input,
                        acceptance = t.acceptance,
                        tools = t.tools.joinToString(","),
                        status = SubAgentRules.statusName(SubTaskStatus.PENDING),
                        attempts = 1,
                        steps = 0,
                        startedAt = 0L,
                        endedAt = 0L,
                        failureReason = "",
                        resultText = "",
                        createdAt = now,
                    )
                    d.execSQL(stmt.sql, stmt.args)
                }
            }.onFailure {
                log("子任务审计：落账失败 ${t.id}（不影响执行）${it.javaClass.simpleName}")
            }
        }
    }

    override fun markRunning(task: SubTask, at: Long) {
        runCatching {
            db.write { d ->
                val stmt = BaopuSql.updateSubTaskRunning(task.id, at)
                d.execSQL(stmt.sql, stmt.args)
            }
        }.onFailure {
            log("子任务审计：markRunning 失败 ${task.id}（不影响执行）${it.javaClass.simpleName}")
        }
    }

    override fun markFinished(
        result: SubTaskResult,
        goal: String,
        input: String,
        acceptance: String,
        tools: List<String>,
        attempts: Int,
    ) {
        runCatching {
            db.write { d ->
                val stmt = BaopuSql.updateSubTaskFinished(
                    taskId = result.id,
                    status = SubAgentRules.statusName(result.status),
                    attempts = attempts,
                    steps = result.steps,
                    startedAt = result.startedAt,
                    endedAt = result.endedAt,
                    failureReason = result.failureReason,
                    resultText = SubAgentRules.truncate(result.text, SubAgentRules.RESULT_TEXT_CAP),
                )
                d.execSQL(stmt.sql, stmt.args)
            }
        }.onFailure {
            log("子任务审计：markFinished 失败 ${result.id}（不影响执行）${it.javaClass.simpleName}")
        }
    }

    override fun childrenOf(parentId: String): List<SubTaskRecord> = read(
        BaopuSql.selectSubTasksOfParent(parentId),
    )

    override fun ofSession(sessionId: Long): List<SubTaskRecord> = read(
        BaopuSql.selectSubTasksOfSession(sessionId),
    )

    private fun read(stmt: BaopuSql.Stmt): List<SubTaskRecord> = runCatching {
        db.read { d ->
            d.rawQuery(stmt.sql, stmt.args).use { c ->
                val out = mutableListOf<SubTaskRecord>()
                while (c.moveToNext()) {
                    out += SubTaskRecord(
                        taskId = c.getString(0),
                        parentId = c.getString(1),
                        sessionId = c.getLong(2),
                        seq = c.getInt(3),
                        goal = c.getString(4),
                        input = c.getString(5),
                        acceptance = c.getString(6),
                        tools = c.getString(7),
                        status = c.getString(8),
                        attempts = c.getInt(9),
                        steps = c.getInt(10),
                        startedAt = c.getLong(11),
                        endedAt = c.getLong(12),
                        failureReason = c.getString(13),
                        resultText = c.getString(14),
                    )
                }
                out
            }
        }
    }.getOrElse {
        log("子任务审计：读失败（${it.javaClass.simpleName}）")
        emptyList()
    }
}
