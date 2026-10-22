// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Provider / 预设 / 免费过滤单测（刀 0 · 2026-10-16）。
 *
 * 钉四件事：协议选择走对端点与头；三个预设形状正确；**用户 key 不被预设挤掉**；
 * URL 许可（https 或环回——与 NetworkSecurityConfigTest 同口径）。
 */
class ProviderTest {

    @Test
    fun `五个预设都在且形状正确_且一个Key都不内置`() {
        // 第一百三十一批 ①：内置 zen 免费通道（整条 403）删除，换成 OpenRouter 免费档；
        // 预设从四条变五条。第五个新加的这条**不带 Key**——负例「内置 key」钉的就是它。
        val all = Providers.all()
        assertEquals(5, all.size)
        assertEquals(
            setOf("openai-compat", "anthropic", "deepseek", "openrouter", "openrouter-free"),
            all.map { it.id }.toSet(),
        )
        all.forEach {
            assertTrue("预设 baseUrl 必须 https：${it.id}", it.baseUrl.startsWith("https://"))
            assertTrue("预设 model 不能空：${it.id}", it.model.isNotBlank())
            assertTrue("预设 label 不能空：${it.id}", it.label.isNotBlank())
            assertEquals("预设本体不许带 Key：${it.id}", "", it.apiKey)
        }
        assertEquals(LlmProtocol.OPENAI_COMPAT, Providers.of("openrouter-free")!!.protocol)
        assertEquals(LlmProtocol.OPENAI_COMPAT, Providers.of("openai-compat")!!.protocol)
        assertEquals(LlmProtocol.ANTHROPIC, Providers.of("anthropic")!!.protocol)
        assertEquals(LlmProtocol.OPENAI_COMPAT, Providers.of("deepseek")!!.protocol)
        assertEquals(LlmProtocol.OPENAI_COMPAT, Providers.of("openrouter")!!.protocol)
        assertTrue(Providers.of("openrouter-free")!!.builtinFree)
        assertFalse(Providers.of("openai-compat")!!.builtinFree)
        assertFalse(Providers.of("anthropic")!!.builtinFree)
        assertFalse(Providers.of("deepseek")!!.builtinFree)
        assertFalse(Providers.of("openrouter")!!.builtinFree)
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
        // 第一百三十一批 ①：新的免费车道与 OpenRouter 正式档都带 ① 实测元数据，
        // 不再是"没依据"的 zen 免费通道（那条整条 403，元数据全是猜的）。
        assertEquals(1_000_000, Providers.OPENROUTER_FREE.contextWindow)
        assertEquals(131_072, Providers.OPENROUTER_FREE.maxOutput)
        assertEquals(1_048_576, Providers.OPENROUTER.contextWindow)
        assertNull(Providers.OPENROUTER.maxOutput)
    }

    @Test
    fun `免费车道不内置Key_不带伪装头_默认模型是实测那条free`() {
        val free = Providers.OPENROUTER_FREE
        // 派工单 ①「App 不兜底、不内置」：交出来的预设 Key 必须是空的
        assertEquals("", free.apiKey)
        // 旧 zen 免费通道的伪装头（User-Agent / x-opencode-client）随整条通道一起删掉了
        assertTrue("免费车道不许再带伪装头：${free.headers}", free.headers.isEmpty())
        // 默认模型＝① 实测（2026-10-22）18 条 `:free` 里的那条带 vl 的；
        // 清单**不硬编码**（③ 免费档每次现拉），仓库里只留这一条默认值。
        assertEquals("nvidia/nemotron-3.5-super-vl:free", free.model)
        assertTrue(free.model.endsWith(":free"))
        assertEquals("https://openrouter.ai/api/v1", free.baseUrl)
        // ① 实测台账里这条默认模型的 architecture.input_modalities =
        // ["text", "image", "video"] ⇒ 不是纯文本模型；按 2026-10-18 用户口径
        // 「除非是文本模型，不然肯定要打开」＋「纯文本模型自己就会说看不见」⇒ true，
        // 不在本地替模型判。真机发图未验（本单红线）；端上不认图时按纪律改回 false。
        assertTrue("默认模型 input_modalities 含 image ⇒ 图照发", free.vision)
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
        assertEquals("", Providers.OPENROUTER_FREE.apiKey)
        assertEquals("", Providers.OPENAI_COMPAT.apiKey)
        assertEquals("", Providers.ANTHROPIC.apiKey)
    }

    @Test
    fun `免费车道换用户key后仍是免费车道`() {
        val v = Providers.withUserKey(Providers.OPENROUTER_FREE, "sk-real")
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
    fun `免费限额写在数据层_仓库不再有硬编码免费模型清单`() {
        // ①「免费限额 UI 明示」：数字是 ① 官方口径（2026-10-22 两处交叉核对），不编。
        val hint = Providers.FREE_QUOTA_HINT
        assertTrue(hint.contains("20 次/分钟"))
        assertTrue(hint.contains("50 次/天"))
        assertTrue("超额要说 429 会自动恢复，不许留 403 死路", hint.contains("429"))
        assertFalse("旧的 zen 免费通道 403 口径不许回来", hint.contains("403"))
        // ③：免费档清单每次现拉、不缓存 ⇒ Providers 里不再有 FREE_MODELS 这个数组
        val src = File("src/main/java/com/example/zhengdao/baopu/Provider.kt").readText()
        assertFalse("免费模型清单不许硬编码进仓库", src.contains("FREE_MODELS"))
        assertFalse("旧的内置免费通道（整条 403）不许回来", src.contains("BUILTIN_FREE"))
    }
}
