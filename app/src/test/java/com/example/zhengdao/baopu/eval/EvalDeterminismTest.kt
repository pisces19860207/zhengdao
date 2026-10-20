// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第九十四批 ② 与 §2「测试」——
// - eval 显式 temperature=0、seed 只在 provider 支持时传；
// - 不支持时标记正确（NON_DETERMINISTIC），不假装可复现；
// - **负例**：默认对话被强制 temperature=0 ⇒ 门禁红。
//
// 这组用例**不联网、不花钱、不需要 key**：量的是「参数有没有按口径发出去」，
// 真跑模型那部分归 EvalGateTest（有 key 才跑）。
package com.example.zhengdao.baopu.eval

import com.example.zhengdao.baopu.LlmClient
import com.example.zhengdao.baopu.LlmMessage
import com.example.zhengdao.baopu.LlmProtocol
import com.example.zhengdao.baopu.Provider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EvalDeterminismTest {

    // ── ① seed 解析：默认不发 ─────────────────────────────────────────────

    @Test
    fun `seed_默认不发_关掉写法不发_数字才发`() {
        assertNull("没设环境变量 ⇒ 不发", EvalDeterminism.seedFrom(null))
        for (off in listOf("", "   ", "off", "OFF", "none", "disabled", "false", "null", "abc")) {
            assertNull("「$off」应当算「不发」", EvalDeterminism.seedFrom(off))
        }
        assertEquals(1234L, EvalDeterminism.seedFrom("1234"))
        assertEquals("带空格也要认得出来", 42L, EvalDeterminism.seedFrom(" 42 "))
        assertEquals("0 是合法种子，不是「关」", 0L, EvalDeterminism.seedFrom("0"))
    }

    @Test
    fun `确定性标签_没发seed就不许自称可复现`() {
        val plain = EvalDeterminism()
        assertFalse("默认没发 seed ⇒ 不是确定性档", plain.deterministic)
        assertTrue(plain.label().startsWith("NON_DETERMINISTIC"))
        assertFalse("标签里不能写「可复现」这种承诺", plain.label().contains("可复现"))
        assertTrue("原因要说清为什么", plain.reason().contains("seed"))

        val seeded = EvalDeterminism(seed = 1234L)
        assertTrue(seeded.deterministic)
        assertEquals("DETERMINISTIC(temperature=0, seed=1234)", seeded.label())
        assertTrue("即使发了 seed 也不许承诺逐字节相同", seeded.reason().contains("仍可能不同"))

        val hot = EvalDeterminism(temperature = 0.7, seed = 1234L)
        assertFalse("温度不是 0 ⇒ 采样本身随机，有 seed 也不算确定性档", hot.deterministic)
        assertTrue(hot.label().startsWith("NON_DETERMINISTIC"))
    }

    @Test
    fun `确定性块_进结果记录_没值也要留字段`() {
        val j = EvalDeterminism(seed = 7L).toJson()
        assertEquals(0.0, j.getDouble("temperature"), 0.0)
        assertEquals(7L, j.getLong("seed"))
        assertTrue(j.getBoolean("deterministic"))
        assertTrue(j.getString("label").contains("seed=7"))
        assertTrue(j.getString("reason").isNotBlank())

        val none = EvalDeterminism().toJson()
        assertTrue("没发 seed 时字段要在、值写 null（不许悄悄少一个字段）", none.has("seed"))
        assertTrue(none.isNull("seed"))
        assertFalse(none.getBoolean("deterministic"))
    }

    // ── ② eval 请求体：temperature 必发，seed 看条件 ──────────────────────

    @Test
    fun `eval请求体_显式temperature0_且旧字段一个不少`() {
        val body = EvalHarness.bodyOf(cfg(), messages(), JSONArray().put(JSONObject().put("type", "function")))
        assertEquals("eval 必须显式发 temperature=0", 0.0, body.getDouble("temperature"), 0.0)
        assertFalse("没开 seed 就不许发 seed 字段（假固定比不固定更糟）", body.has("seed"))
        // 旧字段逐项对照：谁在加 temperature 的时候顺手改坏了别的，这里立刻红
        assertEquals("deepseek-flash", body.getString("model"))
        assertFalse(body.getBoolean("stream"))
        assertEquals(2_048, body.getInt("max_tokens"))
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
        assertTrue("messages 不许丢", body.has("messages"))
        assertTrue("tools 不许丢（没有它模型根本看不见工具）", body.has("tools"))
    }

    @Test
    fun `eval请求体_开了seed才发seed`() {
        val body = EvalHarness.bodyOf(cfg(seed = 1234L), messages(), JSONArray())
        assertEquals(1234L, body.getLong("seed"))
        assertEquals(0.0, body.getDouble("temperature"), 0.0)
    }

    @Test
    fun `eval请求体_关掉思考时才不带thinking`() {
        val body = EvalHarness.bodyOf(cfg(disableThinking = false), messages(), JSONArray())
        assertFalse(body.has("thinking"))
    }

    // ── ③ 负例门禁：线上对话不许被塞 eval 参数 ────────────────────────────

    @Test
    fun `负例_线上对话请求体里不许出现temperature或seed`() {
        // 走**生产那个函数**（LlmClient.chatRequest），不看内部拼装，直接读请求体的字节——
        // eval 的确定性参数一旦漏进默认对话，这里立刻红。
        for (protocol in listOf(LlmProtocol.OPENAI_COMPAT, LlmProtocol.ANTHROPIC)) {
            val p = provider(protocol)
            val req = LlmClient(p).chatRequest(listOf(LlmMessage(LlmMessage.ROLE_USER, "你好")))
            val body = okio.Buffer().also { req.body!!.writeTo(it) }.readUtf8()
            assertFalse(
                "$protocol 的线上请求体里出现了 temperature ⇒ eval 的确定性参数漏进默认对话了",
                body.contains("\"temperature\""),
            )
            assertFalse(
                "$protocol 的线上请求体里出现了 seed ⇒ eval 的确定性参数漏进默认对话了",
                body.contains("\"seed\""),
            )
        }
    }

    // ── 夹具 ─────────────────────────────────────────────────────────────

    private fun cfg(seed: Long? = null, disableThinking: Boolean = true) = EvalConfig(
        apiKey = "test-key-not-used",
        disableThinking = disableThinking,
        seed = seed,
    )

    private fun messages(): JSONArray = JSONArray()
        .put(JSONObject().put("role", "system").put("content", "系统提示"))
        .put(JSONObject().put("role", "user").put("content", "读一下 a.md"))

    private fun provider(protocol: LlmProtocol) = Provider(
        id = "test-${protocol.name.lowercase()}",
        label = "测试通道",
        protocol = protocol,
        baseUrl = "http://127.0.0.1:1",
        model = "deepseek-flash",
    )
}
