// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.ui

import android.content.Context
import com.example.zhengdao.terminal.Store
import com.example.zhengdao.rootfs.RunLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Agent 账本（2026-10-08）：**装过哪些 Agent** 落在公共区，重装 App 之后据此一键恢复。
 *
 * 用户原话：「包括终端里下载的 agent 的安装主程序，重新装 APP 的话也要像装环境一样的，
 * 自己就瞬间装好了」。环境那一半已经做到了（`Download/证道/rootfs/` 里有包就免下载，
 * 见 TerminalActivity.findLocalArchive）——Agent 这一半缺的正是"**还记得装过什么**"：
 * 卸载 App 会把 `~/.claude`、`~/.hermes`、`~/.local/bin` 全带走，判定"已安装"的文件
 * 探测自然全部落空，用户只能凭记忆一个个点回来。
 *
 * 落点：`Download/证道/agents/installed.json`（与 `logs/`、`cache/`、`rootfs/` 并列）。
 * 内容刻意只有"装过什么 + 什么时候"：
 *
 * ```json
 * {"schema":1,"updatedAt":1690000000000,
 *  "agents":[{"id":"hermes","name":"Hermes","at":1690000000000,"state":"installed"}]}
 * ```
 *
 * **边界（用户已拍板）**：账本里不写任何凭据、不写配置内容、不写 home 路径——
 * 它只是一张"待办清单"。API key、登录态仍在私有 home（`~/.claude`、`~/.hermes`）。
 *
 * 写入时机：派发安装命令时记 `installing`；主页每次刷新时把"文件探测到已装"的记成
 * `installed`（用户在终端里手动敲命令装好的也算）。原子写：先写 `.part` 再改名，
 * 半路被杀不会留下半截 JSON 让下次恢复失败。
 */
object AgentLedger {

    private const val SCHEMA = 1
    const val STATE_INSTALLING = "installing"
    const val STATE_INSTALLED = "installed"

    data class Entry(val id: String, val name: String, val at: Long, val state: String)

    /** 账本文件（公共区；调用方一般不用直接碰）。 */
    fun file(ctx: Context): File = Store.ledgerFile(ctx)

    /** 读账本；文件不存在/损坏一律返回空表（账本是"锦上添花"，坏掉不能拖垮主页）。 */
    fun entries(ctx: Context): List<Entry> = parse(readText(file(ctx)))

    internal fun readText(f: File): String? = runCatching { if (f.isFile) f.readText() else null }.getOrNull()

    /** 记「这一轮开始装」（派发安装命令时调用）。 */
    fun markStarted(ctx: Context, agent: AppState.AgentInfo) =
        upsert(ctx, Entry(agent.id, agent.name, System.currentTimeMillis(), STATE_INSTALLING))

    /** 记「已装好」（文件探测到可执行文件时调用；手动装的也会被这里收编）。 */
    fun markInstalled(ctx: Context, agent: AppState.AgentInfo) =
        upsert(ctx, Entry(agent.id, agent.name, System.currentTimeMillis(), STATE_INSTALLED))

    /**
     * 用当前清单同步一次：装着的记成已装；**清单里已经不存在的 id 直接剔除**
     *（否则会留下一个点了注定失败的「恢复」项）。
     * @return 账本里现在还剩几条
     */
    fun syncFrom(ctx: Context, agents: List<AppState.AgentInfo>): Int {
        val known = agents.associateBy { it.id }
        val before = entries(ctx)
        val kept = before.filter { known.containsKey(it.id) }.toMutableList()
        // 数量变了 = 有清单里已不存在的 id 被剔除，同样要落盘
        var changed = kept.size != before.size
        agents.filter { it.installed }.forEach { a ->
            val i = kept.indexOfFirst { it.id == a.id }
            if (i < 0) {
                kept.add(Entry(a.id, a.name, System.currentTimeMillis(), STATE_INSTALLED))
                changed = true
            } else if (kept[i].state != STATE_INSTALLED) {
                kept[i] = kept[i].copy(state = STATE_INSTALLED, at = System.currentTimeMillis())
                changed = true
            }
        }
        if (changed) write(ctx, kept)
        return kept.size
    }

    /**
     * 「恢复全部」的候选：账本里记着装过、而**当前探测不到**的那些（按当前清单顺序）。
     * 顺序跟清单走（而不是跟账本时间走），保证恢复顺序与用户第一次安装的顺序一致。
     *
     * 还要求 `restorable`：官方安装器在受限网络下必然失败的（AGY，E-040）、或依赖链重得
     * "恢复出来也是半截环境"的（Hermes，E-081）都不进候选，否则主页会一直挂着一颗点了必错的
     * 「恢复全部」按钮（用户 2026-10-08 / 2026-10-10 两次拍板）。
     */
    fun restoreCandidates(ctx: Context, agents: List<AppState.AgentInfo>): List<AppState.AgentInfo> =
        pickRestoreCandidates(entries(ctx).map { it.id }.toSet(), agents)

    /** 纯函数半边（JVM 可测，见 AgentLedgerTest）。 */
    internal fun pickRestoreCandidates(
        ledgerIds: Set<String>,
        agents: List<AppState.AgentInfo>,
    ): List<AppState.AgentInfo> =
        agents.filter { it.id in ledgerIds && !it.installed && it.installCmd != null && it.restorable }

    private fun upsert(ctx: Context, e: Entry) {
        runCatching {
            val list = entries(ctx).toMutableList()
            val i = list.indexOfFirst { it.id == e.id }
            if (i >= 0) list[i] = e else list.add(e)
            write(ctx, list)
        }.onFailure { RunLog.log("账本写入失败：${it.message}") }
    }

    /** 原子写：`.part` → rename（失败退回复制 + 删）。 */
    private fun write(ctx: Context, list: List<Entry>) {
        val f = file(ctx)
        val part = File(f.parentFile, f.name + ".part")
        runCatching {
            f.parentFile?.mkdirs()
            part.writeText(render(list, System.currentTimeMillis()))
            if (!part.renameTo(f)) {
                part.copyTo(f, overwrite = true)
                part.delete()
            }
        }.onFailure {
            RunLog.log("账本落盘失败（${f.absolutePath}）：${it.message}")
        }
    }

    // ── 纯函数（可单测，见 AgentLedgerTest）────────────────────────────────

    internal fun render(list: List<Entry>, now: Long): String {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("name", e.name)
                    .put("at", e.at)
                    .put("state", e.state)
            )
        }
        return JSONObject()
            .put("schema", SCHEMA)
            .put("updatedAt", now)
            .put("agents", arr)
            .toString(2)
    }

    internal fun parse(json: String?): List<Entry> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONObject(json).optJSONArray("agents") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Entry(
                    id = id,
                    name = o.optString("name").takeIf { it.isNotBlank() } ?: id,
                    at = o.optLong("at"),
                    state = o.optString("state").takeIf { it.isNotBlank() } ?: STATE_INSTALLED,
                )
            }
        }.getOrDefault(emptyList())
    }
}
