// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 2026-10-17（抱朴刀 3 · C 段）：从 `RunEngine.kt` 拆出——派工单 §9 行数纪律
// 「单文件新增 ≤ 400 行；超了拆文件」。与 `ToolCallCodec.kt` 同款定位：
// 协议/历史的纯函数编解码，JVM 直测。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.HistoryMessage
import org.json.JSONObject

/** 历史回灌的一行（db 原始形状；映射判据全在 [HistoryCodec]）。 */
internal data class HistoryRow(val id: String, val role: String, val kind: String?, val payload: String)

/**
 * 历史回灌编解码（C 段 · 判据 J9）——**纯函数**，JVM 直测。
 *
 * 要解决的事：被杀续跑原来只回灌 `kind='text'`（`RunEngine.loadHistory` 的旧 SQL
 * 带 `part.kind = 'text'` 过滤）⇒ 模型对不上账——它不记得自己刚读过什么、改过什么。
 *
 * 同形是在这里保证的：tool part 落库时存的 `result` 就是在线路径回灌给模型的
 * 文本（Done = 已围栏；失败/被拒 = `失败：…`/`被拒：…` 裸文本，在线时是
 * `DataFence.wrap(…, "失败：…")` 进历史的）⇒ 解码**原样取出已带围栏的**，
 * 没带围栏的（失败/被拒行）在这里补包——与在线路径逐字节同形，不二次包。
 */
internal object HistoryCodec {

    fun decode(rows: List<HistoryRow>, now: () -> Long = System::currentTimeMillis): List<HistoryMessage> {
        val out = mutableListOf<HistoryMessage>()
        for (row in rows) {
            val text = when (row.kind) {
                "tool" -> toolText(row.payload, now)
                else -> row.payload
            }
            out += HistoryMessage(row.id, row.role, text)
        }
        return out
    }

    /** tool part 的 payload（`{tool, step, result}`）→ 在线同形的回灌文本。 */
    private fun toolText(payload: String, now: () -> Long): String {
        val json = runCatching { JSONObject(payload) }.getOrNull()
        val tool = json?.optString("tool")?.ifBlank { null } ?: "tool"
        val result = json?.optString("result").orEmpty()
        // 已带围栏（Done 行）⇒ 原样；没带（失败/被拒行）⇒ 补包（在线时就是包着的）
        return if (result.startsWith(DataFence.FENCE_OPEN)) result else DataFence.wrap(tool, now(), result)
    }
}
