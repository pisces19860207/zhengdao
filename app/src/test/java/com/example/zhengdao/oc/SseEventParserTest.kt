// 独立开发声明：本文件为本项目从零编写。被测类是 [SseEventParser]（本项目自研，见其 KDoc）。
package com.example.zhengdao.oc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第㉙刀（§B 顺序 5 / issue #9）：SSE「类型 → 语义」**纯函数**的单测。
 *
 * 钉三件事：
 * 1. **正例**：既有 10 个类型 + 本刀新接入的 4 类，各归各位（改名/挪位立刻红）；
 * 2. **负例（关键）**：**未知类型不崩、不丢** —— 落到 `Event.Unknown` 且**类型名与原文都完整
 *    带出去**（`raw` 必须逐字相等，不许截断/改写）。这是既有设计约束③，本刀不许放松；
 * 3. **负例**：坏输入（坏 JSON / 空串 / 类型名缺失）不抛异常 —— 门禁脚本式的"解析层不许带崩
 *    整条 SSE 流"。
 */
class SseEventParserTest {

    private fun parse(name: String?, data: String) = SseEventParser.parse(name, data) { null }

    // ── ① 既有 10 个类型（回归：搬动解析层时语义不许变） ──

    @Test
    fun `既有类型各归各位`() {
        assertTrue(parse("server.connected", """{"type":"server.connected"}""")
            is SseClient.Event.Connected)
        assertTrue(parse("message.updated", "{}") is SseClient.Event.MessageUpdated)
        assertTrue(parse("message.part.updated", "{}") is SseClient.Event.MessageUpdated)
        assertTrue(parse("part.removed", "{}") is SseClient.Event.PartRemoved)
        assertTrue(parse("message.part.removed", "{}") is SseClient.Event.PartRemoved)
        assertTrue(parse("session.idle", "{}") is SseClient.Event.SessionIdle)
        assertTrue(parse("permission.asked", "{}") is SseClient.Event.PermissionAsked)
        assertTrue(parse("permission.updated", "{}") is SseClient.Event.PermissionAsked)
        assertTrue(parse("todo.updated", "{}") is SseClient.Event.TodoUpdated)
    }

    @Test
    fun `part_updated 取 delta 与不取 delta 两种形态`() {
        val withDelta = parse("part.updated", """{"properties":{"delta":"追加的"}}""")
        assertEquals("追加的", (withDelta as SseClient.Event.PartUpdated).delta)

        val full = parse("part.updated", """{"properties":{"text":"全量"}}""")
        assertNull("无 delta 字段 ⇒ null（调用方按全量覆盖处理）", (full as SseClient.Event.PartUpdated).delta)
    }

    // ── ② 第㉙刀新接入的 4 类（覆盖 6 个字面类型） ──

    @Test
    fun `新接入的 4 类各归各位`() {
        assertTrue(parse("session.created", "{}") is SseClient.Event.SessionsChanged)
        assertTrue(parse("session.model.selected", "{}") is SseClient.Event.SessionModelSelected)
        assertTrue(parse("message.removed", "{}") is SseClient.Event.MessageRemoved)
        for (t in listOf("model.updated", "provider.updated", "models-dev.refreshed")) {
            assertTrue("$t 应归 ModelCatalogChanged", parse(t, "{}") is SseClient.Event.ModelCatalogChanged)
        }
    }

    // ── ③ 负例（关键）：未知类型不崩、不丢 ──

    @Test
    fun `未知类型进 Unknown 且类型名与原文完整保留`() {
        // 真机实测到达过的"仍然未知"的类型（本刀没接的那些）
        val cases = listOf(
            "project.updated",
            "integration.updated",
            "agent.updated",
            "command.updated",
            "skill.updated",
            "websearch.updated",
            "reference.updated",
            "plugin.updated",
            "lsp.updated",
            "filesystem.changed",
            "从未见过的.新事件",
        )
        for (t in cases) {
            val raw = """{"id":"evt_x","type":"$t","data":{"k":1}}"""
            val ev = parse(t, raw)
            assertTrue("$t 必须是 Unknown（不许被吞掉）", ev is SseClient.Event.Unknown)
            ev as SseClient.Event.Unknown
            assertEquals("类型名必须原样带出", t, ev.type)
            assertEquals("原文必须逐字保留（不截断、不改写）", raw, ev.raw)
        }
    }

    @Test
    fun `类型名缺失时从 payload 摸；都摸不到则兜底 unknown 且原文仍保留`() {
        // 服务端实测把类型放在 JSON 的 type 字段里（真机抓包：data: {"id":…,"type":"server.connected"}）
        val fromPayload = parse(null, """{"id":"evt_1","type":"session.idle","data":{}}""")
        assertTrue(fromPayload is SseClient.Event.SessionIdle)

        val raw = """{"id":"evt_2","data":{"k":1}}"""
        val none = parse(null, raw)
        assertTrue(none is SseClient.Event.Unknown)
        assertEquals("unknown", (none as SseClient.Event.Unknown).type)
        assertEquals("原文必须逐字保留", raw, none.raw)
    }

    // ── ④ 负例：坏输入不抛（解析层不许带崩 SSE 循环） ──

    @Test
    fun `坏 JSON 与空串不抛异常且不丢原文`() {
        val bad = listOf("{", "不是 JSON", "", "\u0000", """{"type":}""", "\"未闭合")
        for (data in bad) {
            val ev = parse("某个.未知类型", data)   // 类型名有 ⇒ 走 Unknown 分支
            ev as SseClient.Event.Unknown
            assertEquals("某个.未知类型", ev.type)
            assertEquals("坏输入也不许改写原文", data, ev.raw)
        }
        // 类型名也没有 + 坏数据 ⇒ 兜底 unknown，同样不抛
        for (data in bad) {
            assertTrue(parse(null, data) is SseClient.Event.Unknown)
        }
    }

    @Test
    fun `大小写不同视为未知（不猜、不丢）`() {
        val ev = parse("Session.Idle", """{"type":"Session.Idle"}""")
        assertTrue("大小写不符 ⇒ 归 Unknown（宁可记录也不猜错语义）", ev is SseClient.Event.Unknown)
        assertEquals("Session.Idle", (ev as SseClient.Event.Unknown).type)
    }
}
