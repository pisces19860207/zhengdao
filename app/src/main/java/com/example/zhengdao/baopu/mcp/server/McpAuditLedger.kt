// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百八十四单 · MCP 服务端的**审计账**（五件必备之四）。
//
// 房型照 323 的 紧急处置.jsonl（EmergencyWiring）：追加式 jsonl、内存兜底、落盘走
// [com.example.zhengdao.baopu.scout.LedgerFanout]、落点与 208 同一棵 抱朴记忆/。
// 三条纪律：
//   1. **只记账不拦事**（208/499 同一口径）：账是留痕，闸是判定——判据②③的"拒"
//      由 McpServerCore 做，这里只负责把每一次拒/每一次放都留一行；
//   2. **永不记 token**：auth_fail 行里只有"没配上"，连指纹都不进（指纹已在
//      设置页可查，账上多一份就多一份撞库面）；
//   3. **拒绝账必须落得出**（判据②③的验收就是"读得到那行"）：文件写失败时
//      memory 里必有，replay() 两路合并，测试直接打内存与 tmp 目录两路。
package com.example.zhengdao.baopu.mcp.server

import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.scout.LedgerFanout
import com.example.zhengdao.core.RunLog
import java.io.File
import org.json.JSONObject

internal object McpAuditLedger {

    /** 账本文件名（与 紧急处置.jsonl 同一棵目录）。 */
    const val FILE_NAME = "MCP服务端审计.jsonl"

    // ── 事件闭集（wire 值是账上稳定键，加了新事件不许改旧的）──────────────
    const val EV_AUTH_FAIL = "auth_fail"       // 判据②：错/缺 token 被 401
    const val EV_TOOL_REJECT = "tool_reject"   // 判据③：非白名单工具被拒
    const val EV_LEASE_REJECT = "lease_reject" // 租约未命中（非免卡类）被拒
    const val EV_CALL = "call"                 // 一次 tools/call 的收场（含失败/执行件缺位）
    const val EV_METHOD = "method"             // 其余方法（initialize/tools/list/ping…）
    const val EV_SERVER = "server"             // 启停/停机/绑定变化（detail 里写动作）

    private const val MEMORY_CAP = 200
    private const val FORMAT_VERSION = 1

    private val lock = Any()
    private val memory = ArrayList<JSONObject>()

    /** 单测注入 tmp 目录用；生产走懒解析（DecisionLedger 装配好的那棵 抱朴记忆/）。 */
    @Volatile
    private var installedDirs: List<File>? = null

    /** 测试/特殊装配口；生产不必调（append 时现解析）。 */
    fun install(dirs: List<File>) {
        installedDirs = dirs
    }

    fun resetForTest() {
        synchronized(lock) {
            memory.clear()
            installedDirs = null
        }
    }

    /**
     * 记一行。失败不抛、不挡主流程（账是留痕不是闸门）；内存永远先记——
     * 判据②③读账走 [replay]，两路合并后"拒的那行"一定找得到。
     *
     * @param fields method/tool/outcome/reason 等稳定键（值先由调用方清洗：无参数回显）。
     */
    fun append(ev: String, fields: Map<String, String> = emptyMap()) {
        val row = JSONObject().put("v", FORMAT_VERSION).put("ts", System.currentTimeMillis()).put("ev", ev)
        fields.forEach { (k, v) -> if (v.isNotEmpty()) row.put(k, v) }
        val dirs = installedDirs ?: listOfNotNull(DecisionLedger.installedRoot())
        val fileOk = dirs.isNotEmpty() && runCatching {
            LedgerFanout(dirs, FILE_NAME).appendLine(row.toString())
        }.getOrDefault(false)
        if (!fileOk) {
            // 盘上没落上（没装配/写失败）⇒ 内存兜底——replay() 两路合并，账行不丢。
            synchronized(lock) {
                memory.add(row)
                if (memory.size > MEMORY_CAP) memory.removeAt(0)
            }
        }
        RunLog.log("[mcp-audit] $ev ${fields.entries.joinToString(" ") { "${it.key}=${it.value}" }}" + if (fileOk) "" else "（盘上没落上，内存有）")
    }

    /** 回放（盘为主、内存兜底，同 id 不存在——每行独立；limit>0 取最新 N 行）。 */
    fun replay(limit: Int = 0): List<JSONObject> {
        val fromFile = installedDirs ?: listOfNotNull(DecisionLedger.installedRoot())
        val rows = ArrayList<JSONObject>()
        fromFile.forEach { dir ->
            runCatching {
                val f = File(dir, FILE_NAME)
                if (f.isFile) f.readLines(Charsets.UTF_8).forEach { line ->
                    if (line.isBlank()) return@forEach
                    runCatching { JSONObject(line) }.getOrNull()?.let { rows.add(it) }
                }
            }
        }
        synchronized(lock) { rows.addAll(memory) }
        val sorted = rows.sortedBy { it.optLong("ts", 0L) }
        return if (limit > 0 && sorted.size > limit) sorted.takeLast(limit) else sorted
    }
}
