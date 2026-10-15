// 独立开发声明：本文件为本项目从零编写。被测类是 [SseEventPayload]（本项目自研，见其 KDoc）。
package com.example.zhengdao.oc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 第㉙刀：4 类新事件里两种需要取字段的 payload 提取器的单测。
 *
 * 钉两条：① **多路径兜底**（真机上字段位置不稳定：可能挂 `data` / `properties` /
 * `data.properties`）；② **猜不到就返回 null、绝不抛**，且**不许把事件自身的 id 当实体 id**
 * （那条会把「删一条消息」删成另一条）。
 */
class SseEventPayloadTest {

    // ── modelOf：session.model.selected ──

    @Test
    fun `modelOf 认得字符串与对象两种形态`() {
        assertEquals(
            "mimo-v2.6-flash-free",
            SseEventPayload.modelOf("""{"type":"session.model.selected","data":{"model":"mimo-v2.6-flash-free"}}"""),
        )
        // 对象形态 ⇒ 与 parseStepModel 同口径拼成 providerID/modelID
        assertEquals(
            "anthropic/claude-4.5-sonnet",
            SseEventPayload.modelOf(
                """{"type":"session.model.selected","data":{"model":{"providerID":"anthropic","modelID":"claude-4.5-sonnet"}}}"""
            ),
        )
        // 对象里只有 providerID ⇒ 也不许返回空串（宁可给一半，也别让界面没提示）
        assertEquals(
            "anthropic",
            SseEventPayload.modelOf("""{"data":{"model":{"providerID":"anthropic"}}}"""),
        )
        // modelID / modelId 平铺
        assertEquals("m1", SseEventPayload.modelOf("""{"data":{"modelID":"m1"}}"""))
        assertEquals("m2", SseEventPayload.modelOf("""{"properties":{"modelId":"m2"}}"""))
    }

    @Test
    fun `modelOf 取不到或坏 JSON 时返回 null 且不抛`() {
        for (raw in listOf("""{"data":{"k":1}}""", "{}", "", "{", "不是 JSON")) {
            assertNull("raw=$raw 应当拿不到模型标识", SseEventPayload.modelOf(raw))
        }
    }

    // ── messageIdOf：message.removed ──

    @Test
    fun `messageIdOf 认得三种挂法`() {
        assertEquals("msg_1", SseEventPayload.messageIdOf("""{"data":{"messageID":"msg_1"}}"""))
        assertEquals("msg_2", SseEventPayload.messageIdOf("""{"properties":{"messageID":"msg_2"}}"""))
        assertEquals("msg_3", SseEventPayload.messageIdOf("""{"data":{"message":{"id":"msg_3"}}}"""))
        assertEquals("msg_4", SseEventPayload.messageIdOf("""{"data":{"id":"msg_4"}}"""))
    }

    @Test
    fun `messageIdOf 绝不把事件自身的 id 当消息 id`() {
        // 真机事件信封：{"id":"evt_…","type":"…","data":{…}} —— root.id 是**事件**的 id。
        // 拿它当消息 id 的后果不是"删不掉"，而是"删错"：它会去 messages 里找一条 id 恰好
        // 等于 evt_… 的消息（不存在 ⇒ 白删），或者更糟 —— 一旦将来 id 空间撞上就删错。
        val raw = """{"id":"evt_13e7ddc59001V9RybjB4fjwSPH","type":"message.removed","data":{"k":1}}"""
        assertNull(SseEventPayload.messageIdOf(raw))
    }

    @Test
    fun `messageIdOf 取不到或坏 JSON 时返回 null 且不抛`() {
        for (raw in listOf("""{"data":{"k":1}}""", "{}", "", "{", "不是 JSON")) {
            assertNull("raw=$raw 应当拿不到消息 id", SseEventPayload.messageIdOf(raw))
        }
    }
}
