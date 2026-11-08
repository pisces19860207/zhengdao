// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百三十一批 · 子智能体 v2 ④「回滚粒度」：子 A 成功、子 B 失败 ⇒
// **A 产物保留，B 的写全撤**。实现方式是**复用 470 的回滚保险箱**（不另造快照）：
// 写类动作前按「这条子任务」的 opId 入库，子任务终态不成功就按该 opId 回滚。
//
// 为什么 opId 用子任务 id：470 的 ByOp 本来就是「一笔动作一炉」，子任务正好是
// 一笔（并发下两条子任务同时写也不会互相踩）；重试仍落同一个 opId——同一件事
// 的两次尝试要一起撤，不能只撤最后一次。
//
// 边界：本件只做「路径目标怎么认、什么时候入库、什么时候撤」；真正的字节
// 存取在 [RollbackVault]（470 唯一定义处），这里不复制它的分档逻辑。
package com.example.zhengdao.baopu.agent.multiagent

import com.example.zhengdao.baopu.agent.PathBatch
import com.example.zhengdao.baopu.agent.ToolCall
import com.example.zhengdao.baopu.safety.RollbackScope
import com.example.zhengdao.baopu.safety.RollbackVault
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 子任务的写守卫（协调器与执行器之间的一根针）。
 *
 * - [beforeWrite]：执行器在真正调用写类工具**之前**调（入库）；
 * - [afterTerminal]：协调器在子任务进终态时调（不成功就按 opId 撤回）。
 *
 * 接口在判据层、实现注入 ⇒ JVM 单测能用真保险箱（临时目录）跑完整「A 留 B 撤」。
 */
internal interface SubTaskWriteGuard {

    /** 写类调用前入库（读类调用点不调它；实现里仍按 [isWrite] 再核一次，防漏）。 */
    fun beforeWrite(task: SubTask, call: ToolCall)

    /** 子任务终态：成功保留产物，失败/超时/取消撤回它这一次（含重试）的全部写。 */
    fun afterTerminal(task: SubTask, result: SubTaskResult)

    companion object {
        /** 没接线时的空实现（v1 只读路径 / 单测不关心写时的默认值）。 */
        val NONE: SubTaskWriteGuard = object : SubTaskWriteGuard {
            override fun beforeWrite(task: SubTask, call: ToolCall) = Unit

            override fun afterTerminal(task: SubTask, result: SubTaskResult) = Unit
        }
    }
}

/** 子任务路径目标的口径（与 470/466 同一口径：单数键＋`paths` 批量形参）。 */
internal object SubAgentWrites {

    /** 单数路径形参名（按工具真实参数名；未知工具只认 `path`）。 */
    private val SINGLE_KEYS: List<String> = listOf("path", "from", "to", "dir", "dest")

    /** 这笔调用要碰的路径（相对路径按工作区根解析；去重保序）。 */
    fun targetsOf(call: ToolCall, root: File): List<String> {
        val single = SINGLE_KEYS.mapNotNull { key ->
            call.arguments.optString(key).trim().takeIf { it.isNotEmpty() }
        }
        return (single + PathBatch.of(call.arguments)).map { absoluteOf(it, root) }.distinct()
    }

    /** 相对路径 ⇒ 工作区根下绝对路径（绝对路径原样）。 */
    fun absoluteOf(raw: String, root: File): String =
        if (File(raw).isAbsolute) File(raw).absolutePath else File(root, raw).absolutePath

    /**
     * 目标在不在工作区根内（v2 子任务写只允许落在工作区）。
     *
     * 用 canonicalPath 比：`..` 折叠与大小写差异都归一；不存在的目标由父目录解析
     * （Java 的 canonical 对不存在的文件也能给出规范路径）。
     */
    fun insideRoot(path: String, root: File): Boolean {
        val base = runCatching { root.canonicalPath }.getOrElse { root.absolutePath }
        val target = runCatching { File(path).canonicalPath }.getOrElse { File(path).absolutePath }
        return target == base || target.startsWith(base + File.separator)
    }

    /** 参数里的路径键一律改写成绝对路径（执行器只认绝对路径，与父引擎同形）。 */
    fun absolutize(args: JSONObject, root: File): JSONObject {
        val out = JSONObject(args.toString())
        SINGLE_KEYS.forEach { key ->
            val raw = out.optString(key).trim()
            if (raw.isNotEmpty()) out.put(key, absoluteOf(raw, root))
        }
        val batch = out.optJSONArray(PathBatch.ARG_PATHS)
        if (batch != null) {
            val resolved = JSONArray()
            for (i in 0 until batch.length()) {
                val raw = batch.optString(i).trim()
                if (raw.isNotEmpty()) resolved.put(absoluteOf(raw, root))
            }
            out.put(PathBatch.ARG_PATHS, resolved)
        }
        return out
    }
}

/**
 * 生产守卫：写前把原件存进 470 保险箱（opId＝`sub-<子任务id>`），终态不成功就回滚。
 *
 * 口径三条：
 * - 成功（DONE）⇒ **什么都不做**（A 的产物要留住）；
 * - 失败/超时/取消 ⇒ 按 opId 回滚，回滚结果如实落账（原样报告 restored/failed）；
 * - 保险箱没装（JVM 或装配未到位）⇒ 只留一行账，不假装撤过。
 */
internal class VaultSubTaskWriteGuard(
    private val sessionKey: String,
    private val workspaceRoot: File,
    private val isWrite: (String) -> Boolean,
    private val ledger: SubAgentLedgerSink? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) : SubTaskWriteGuard {

    override fun beforeWrite(task: SubTask, call: ToolCall) {
        if (!isWrite(call.name)) return
        val paths = SubAgentWrites.targetsOf(call, workspaceRoot)
        if (paths.isEmpty()) return
        val made = RollbackVault.stash(opIdOf(task.id), call.name, paths, sessionKey, task.parentId, now())
        ledger?.append(
            SubAgentLedger.row(task, SubAgentLedgerKind.WRITE, tool = call.name, ok = true, detail = "写前入库 ${made.size} 件", nowMs = now()),
        )
    }

    override fun afterTerminal(task: SubTask, result: SubTaskResult) {
        if (result.ok) return
        if (!RollbackVault.isInstalled()) {
            ledger?.append(
                SubAgentLedger.row(task, SubAgentLedgerKind.ROLLBACK, ok = false, detail = "保险箱未装配：未撤回", nowMs = now()),
            )
            return
        }
        val out = RollbackVault.rollback(RollbackScope.ByOp(opIdOf(task.id)), now())
        ledger?.append(
            SubAgentLedger.row(task, SubAgentLedgerKind.ROLLBACK, ok = out.failed == 0, detail = out.summaryLine, nowMs = now()),
        )
        log("子 Agent ${task.id} 未成功（${result.status}）：撤回它的写 —— ${out.summaryLine}")
    }

    companion object {
        /** 子任务在保险箱里的 opId（同一子任务的重试同号：一起撤）。 */
        fun opIdOf(taskId: String): String = "sub-$taskId"
    }
}
