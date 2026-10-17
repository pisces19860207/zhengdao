// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Provider / 预设 / 免费过滤单测（刀 0 · 2026-10-16）。
 *
 * 钉四件事：协议选择走对端点与头；三个预设形状正确；**用户 key 不被预设挤掉**；
 * URL 许可（https 或环回——与 NetworkSecurityConfigTest 同口径）。
 */
class ProviderTest {

    @Test
    fun `四个预设都在且形状正确`() {
        val all = Providers.all()
        assertEquals(4, all.size)
        assertEquals(
            setOf("builtin-free", "openai-compat", "anthropic", "deepseek"),
            all.map { it.id }.toSet(),
        )
        all.forEach {
            assertTrue("预设 baseUrl 必须 https：${it.id}", it.baseUrl.startsWith("https://"))
            assertTrue("预设 model 不能空：${it.id}", it.model.isNotBlank())
            assertTrue("预设 label 不能空：${it.id}", it.label.isNotBlank())
        }
        assertEquals(LlmProtocol.OPENAI_COMPAT, Providers.of("builtin-free")!!.protocol)
        assertEquals(LlmProtocol.OPENAI_COMPAT, Providers.of("openai-compat")!!.protocol)
        assertEquals(LlmProtocol.ANTHROPIC, Providers.of("anthropic")!!.protocol)
        assertEquals(LlmProtocol.OPENAI_COMPAT, Providers.of("deepseek")!!.protocol)
        assertTrue(Providers.of("builtin-free")!!.builtinFree)
        assertFalse(Providers.of("openai-compat")!!.builtinFree)
        assertFalse(Providers.of("anthropic")!!.builtinFree)
        assertFalse(Providers.of("deepseek")!!.builtinFree)
    }

    @Test
    fun `DeepSeek预设四项元数据齐_且思考默认高`() {
        // 第十四批 J2：baseUrl/model/contextWindow/maxOutput 四项；effort 默认 high
        val d = Providers.of("deepseek")!!
        assertEquals("https://api.deepseek.com/v1", d.baseUrl)
        assertEquals("deepseek-flash", d.model)
        assertEquals(1_048_576, d.contextWindow)
        assertEquals(393_216, d.maxOutput)
        assertEquals("high", d.reasoningEffort)
        // 只加预设，不改任何现有预设的值（派工单 A 段第 2 条原话）
        assertNull(Providers.OPENAI_COMPAT.contextWindow)
        assertNull(Providers.OPENAI_COMPAT.maxOutput)
        assertNull(Providers.ANTHROPIC.contextWindow)
        assertNull(Providers.BUILTIN_FREE.contextWindow)
    }

    @Test
    fun `免费预设带伪装头与默认模型首位`() {
        val free = Providers.BUILTIN_FREE
        assertEquals(Providers.FREE_MODELS.first(), free.model)
        assertEquals("opencode/1.18.34", free.headers["User-Agent"])
        assertEquals("cli", free.headers["x-opencode-client"])
        // 免费档占位 Bearer：钉住这个值，防后人"顺手修掉"（② 台账取证，别当 bug）
        assertEquals("null", free.apiKey)
        assertEquals(Providers.FREE_PLACEHOLDER_KEY, free.apiKey)
    }

    @Test
    fun `用户key是一等公民_只换key不动其余`() {
        for (preset in Providers.all()) {
            val withKey = Providers.withUserKey(preset, "sk-user-123")
            assertEquals("sk-user-123", withKey.apiKey)
            assertEquals(preset.baseUrl, withKey.baseUrl)
            assertEquals(preset.model, withKey.model)
            assertEquals(preset.protocol, withKey.protocol)
            assertEquals(preset.headers, withKey.headers)
            assertEquals(preset.builtinFree, withKey.builtinFree)
        }
        // 预设本体不可变（data class copy 语义）：换 key 不会写回预设
        assertEquals("null", Providers.BUILTIN_FREE.apiKey)
        assertEquals("", Providers.OPENAI_COMPAT.apiKey)
        assertEquals("", Providers.ANTHROPIC.apiKey)
    }

    @Test
    fun `免费通道换用户key后仍是免费车道`() {
        val v = Providers.withUserKey(Providers.BUILTIN_FREE, "sk-real")
        assertTrue("builtinFree 描述通道不描述 key", v.builtinFree)
        assertEquals("sk-real", v.apiKey)
    }

    @Test
    fun `URL许可_https放行_环回放行_外部明文拒绝`() {
        assertTrue(Provider.isPermittedBaseUrl("https://api.openai.com/v1"))
        assertTrue(Provider.isPermittedBaseUrl("https://opencode.ai/zen/v1"))
        assertTrue(Provider.isPermittedBaseUrl("http://127.0.0.1:8080/v1"))
        assertTrue(Provider.isPermittedBaseUrl("http://localhost/v1"))
        assertFalse(Provider.isPermittedBaseUrl("http://api.deepseek.com/v1"))
        assertFalse(Provider.isPermittedBaseUrl("ftp://x/v1"))
        assertFalse(Provider.isPermittedBaseUrl("api.openai.com/v1"))
    }

    @Test
    fun `构造provider时非法URL当场炸`() {
        assertThrows(IllegalArgumentException::class.java) {
            Provider("x", "x", LlmProtocol.OPENAI_COMPAT, "http://evil.com/v1", "m")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Provider("x", "x", LlmProtocol.OPENAI_COMPAT, "https://ok.com/v1", "  ")
        }
        // 环回可以（本机假服务器 / MockWebServer 走这条）
        assertNotNull(
            Provider("x", "x", LlmProtocol.OPENAI_COMPAT, "http://127.0.0.1:1/v1", "m"),
        )
    }

    @Test
    fun `免费模型默认序首位与台账一致`() {
        // ② 证道设计报告/抱朴-免费模型能力-2026-10-16.md 的"刀 0 免费默认序"
        assertEquals("muse-spark-1.3-contributor-free", Providers.FREE_MODELS[0])
        assertEquals(5, Providers.FREE_MODELS.size)
        assertTrue(Providers.FREE_MODELS.all { it.endsWith("-free") })
    }
}
