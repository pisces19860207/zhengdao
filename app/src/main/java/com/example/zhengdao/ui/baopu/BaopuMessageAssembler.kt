// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第七十二批（§B 80 结构切片 · 纯逻辑抽取）：从 `BaopuChatController.readMessages` 抽出的
// **纯装配**——输入是那条 SELECT 摊平后的行（一条消息 × 一个 part 一行），输出是 UI 消息列表。
//
// 为什么抽它：这段判据（空消息丢不丢、plan 进 `msg.plan` 不进 parts、tool payload 怎么解析）
// 全是普通值变换，却埋在「读库 + 游标 + 装配」里 ⇒ JVM 测不到，只能靠真机看。
// **行为一个字没改**：搬移前后逐行等价，原 KDoc 里的判据（J16／第十七批 C 段）照旧。
package com.example.zhengdao.ui.baopu

import org.json.JSONObject

/**
 * `readMessages` 那条 SELECT 摊平后的一行。
 *
 * [kind] / [payload] 的可空已在调用方归一（LEFT JOIN 没配上 ⇒ `"text"` / `""`），
 * 与搬移前 `if (c.isNull(3)) "text" else …` 逐字等价——本类不再判 null，免得两份判据漂移。
 */
internal data class BaopuPartRow(
    val messageId: Long,
    val role: String,
    val createdAt: Long,
    val kind: String,
    val payload: String,
)

/**
 * 库行 → [BaopuUiMessage] 的纯装配（无 DB、无 Compose、无 Android）。
 *
 * 三条判据（搬移前就在，不许改）：
 * 1. **只有 part 或只有 plan 的消息照样建**——纯 plan 消息的气泡由列表层跳过（J16）；
 * 2. **plan payload 不进 parts**，解析失败就当没有（`BaopuUiPlan.fromPayload` 返 null）；
 * 3. **tool part 的 payload 是 JSON**（`tool` / `result`），坏 JSON 不抛、结果退回原文。
 */
internal object BaopuMessageAssembler {

    fun assemble(rows: List<BaopuPartRow>): List<BaopuUiMessage> {
        val out = mutableListOf<BaopuUiMessage>()
        var curId = -1L
        var curRole = ""
        var curAt = 0L
        var parts = mutableListOf<BaopuUiPart>()
        var curPlan: BaopuUiPlan? = null

        fun flush() {
            if (curId >= 0 && (parts.isNotEmpty() || curPlan != null)) {
                out += BaopuUiMessage(curId, curRole, parts.toList(), curAt, curPlan)
            }
        }

        for (row in rows) {
            if (row.messageId != curId) {
                flush()
                curId = row.messageId
                curRole = row.role
                curAt = row.createdAt
                parts = mutableListOf()
                curPlan = null
            }
            if (row.kind == "plan") {
                // 同消息多条 plan（理论不会）⇒ 后一条赢；解析不出条目 ⇒ 当作没有
                BaopuUiPlan.fromPayload(row.payload)?.let { curPlan = it }
                continue
            }
            parts += if (row.kind == "tool") {
                val json = runCatching { JSONObject(row.payload) }.getOrNull()
                BaopuUiPart(
                    kind = "tool",
                    tool = json?.optString("tool") ?: "",
                    text = json?.optString("result") ?: row.payload,
                )
            } else {
                // 保留库里的原始 kind（不硬归一成 "text"）：part.kind 是自由文本，
                // 未来新增 kind（如压缩标记）不该在列表层被静默吞掉。
                BaopuUiPart(kind = row.kind, text = row.payload)
            }
        }
        flush()
        return out
    }
}
