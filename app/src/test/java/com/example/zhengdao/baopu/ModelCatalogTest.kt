// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {

    @Test
    fun models_url_appends_v1_once() {
        assertEquals("https://api.deepseek.com/v1/models", ModelCatalog.modelsUrl("https://api.deepseek.com"))
        assertEquals("https://api.deepseek.com/v1/models", ModelCatalog.modelsUrl("https://api.deepseek.com/"))
        assertEquals("https://x/v1/models", ModelCatalog.modelsUrl("https://x/v1"))
        assertEquals("https://x/v1/models", ModelCatalog.modelsUrl("https://x/v1/"))
        assertEquals("https://x/v1/models", ModelCatalog.modelsUrl("https://x/v1/models"))
    }

    @Test
    fun parses_openai_style_list() {
        val json = """{"object":"list","data":[{"id":"deepseek-chat"},{"id":"deepseek-reasoner"}]}"""
        assertEquals(listOf("deepseek-chat", "deepseek-reasoner"), ModelCatalog.parseModelIds(json))
    }

    @Test
    fun parses_top_level_array_and_dedupes_sorts() {
        val json = """[{"id":"b"},{"id":"a"},{"id":"a"},{"name":"c"}]"""
        assertEquals(listOf("a", "b", "c"), ModelCatalog.parseModelIds(json))
    }

    @Test
    fun bad_json_is_empty_not_throw() {
        assertTrue(ModelCatalog.parseModelIds("not json at all").isEmpty())
        assertTrue(ModelCatalog.parseModelIds("""{"data":[]}""").isEmpty())
    }

    @Test
    fun skips_entries_without_id_or_name() {
        val json = """{"data":[{"object":"model"},{"id":"ok","name":"x"}]}"""
        assertEquals(listOf("ok"), ModelCatalog.parseModelIds(json))
    }

    // ── 第一百三十一批 ③：三种清单形状分别解析（派工单「三协议模型列表解析（fake）」） ──

    @Test
    fun 形状从接口地址现推() {
        assertEquals(CatalogShape.ANTHROPIC, ModelCatalog.shapeFor(Providers.ANTHROPIC.baseUrl))
        assertEquals(CatalogShape.OPENAI_COMPAT, ModelCatalog.shapeFor(Providers.DEEPSEEK.baseUrl))
        assertEquals(CatalogShape.OPENAI_COMPAT, ModelCatalog.shapeFor(Providers.OPENAI_COMPAT.baseUrl))
        assertEquals(CatalogShape.OPENAI_COMPAT, ModelCatalog.shapeFor(Providers.OPENROUTER.baseUrl))
        assertEquals(CatalogShape.OPENAI_COMPAT, ModelCatalog.shapeFor(Providers.OPENROUTER_FREE.baseUrl))
        assertEquals(CatalogShape.GEMINI_NATIVE,
            ModelCatalog.shapeFor("https://generativelanguage.googleapis.com/v1beta"))
        assertEquals(CatalogShape.GEMINI_NATIVE, ModelCatalog.shapeFor("https://proxy.example.cn/v1beta"))
        assertEquals("大小写与首尾空格不影响现推", CatalogShape.ANTHROPIC,
            ModelCatalog.shapeFor("  HTTPS://API.ANTHROPIC.COM/v1 "))
        // ③「预设也拉」：五条预设的地址每一条都推得出形状 ⇒ 没有一条是"只能手填"的死路
        val shapes = Providers.all().associate { it.id to ModelCatalog.shapeFor(it.baseUrl) }
        assertEquals(CatalogShape.OPENAI_COMPAT, shapes.getValue("openai-compat"))
        assertEquals(CatalogShape.OPENAI_COMPAT, shapes.getValue("deepseek"))
        assertEquals(CatalogShape.ANTHROPIC, shapes.getValue("anthropic"))
        assertEquals(CatalogShape.OPENAI_COMPAT, shapes.getValue("openrouter"))
        assertEquals(CatalogShape.OPENAI_COMPAT, shapes.getValue("openrouter-free"))
    }

    @Test
    fun 清单地址按形状拼() {
        assertEquals("https://api.anthropic.com/v1/models",
            ModelCatalog.modelsUrl(Providers.ANTHROPIC.baseUrl, CatalogShape.ANTHROPIC))
        assertEquals("https://api.deepseek.com/v1/models",
            ModelCatalog.modelsUrl(Providers.DEEPSEEK.baseUrl, CatalogShape.OPENAI_COMPAT))
        assertEquals("Gemini 的版本段是 v1beta：补 /v1/models 会 404",
            "https://generativelanguage.googleapis.com/v1beta/models",
            ModelCatalog.geminiModelsUrl("https://generativelanguage.googleapis.com"))
        assertEquals("https://x.dev/v1/models", ModelCatalog.geminiModelsUrl("https://x.dev/v1"))
        assertEquals("已经写到 /models 就原样",
            "https://generativelanguage.googleapis.com/v1beta/models",
            ModelCatalog.geminiModelsUrl("https://generativelanguage.googleapis.com/v1beta/models/"))
        assertEquals("按形状走对应拼接函数（调用方只记一个入口）",
            "https://generativelanguage.googleapis.com/v1beta/models",
            ModelCatalog.modelsUrl("https://generativelanguage.googleapis.com", CatalogShape.GEMINI_NATIVE))
    }

    @Test
    fun 鉴权形态_另两档走头_Gemini走查询参数() {
        val compat = ModelCatalog.authFor(CatalogShape.OPENAI_COMPAT, "sk-abc")
        assertNull(compat.queryKey)
        assertEquals(mapOf("Authorization" to "Bearer sk-abc"), compat.headers)
        assertEquals("没 Key 就不带 Authorization 头（OpenRouter 清单免 Key 公开靠的是这一条）",
            emptyMap<String, String>(), ModelCatalog.authFor(CatalogShape.OPENAI_COMPAT, "  ").headers)

        val anthropic = ModelCatalog.authFor(CatalogShape.ANTHROPIC, "sk-ant-abc")
        assertNull(anthropic.queryKey)
        // 版本号转手自对话层那一份常量：清单与对话必须同版本，抄字面量就是埋第二真相
        assertEquals(LlmWire.ANTHROPIC_VERSION, anthropic.headers.getValue("anthropic-version"))
        assertEquals("sk-ant-abc", anthropic.headers.getValue("x-api-key"))
        assertEquals("没 Key 也得带版本号（Anthropic 缺它会 400，报错才有得读）",
            mapOf("anthropic-version" to LlmWire.ANTHROPIC_VERSION),
            ModelCatalog.authFor(CatalogShape.ANTHROPIC, "").headers)

        val gemini = ModelCatalog.authFor(CatalogShape.GEMINI_NATIVE, "AIzaSyFake")
        assertEquals("AIzaSyFake", gemini.queryKey)
        assertEquals(emptyMap<String, String>(), gemini.headers)
        assertNull("Gemini 没 Key ⇒ 查询参数也不挂（别拼出 key= 这种半截 URL）",
            ModelCatalog.authFor(CatalogShape.GEMINI_NATIVE, "").queryKey)
    }

    @Test
    fun OpenAI兼容形状带元数据() {
        val json = """
            {"object":"list","data":[
              {"id":"deepseek/deepseek-v4.1-flash","name":"DeepSeek: DeepSeek V4.1 Flash",
               "description":"ignore me","context_length":1048576,"max_completion_tokens":65536},
              {"id":"nvidia/nemotron-3.5-super-vl:free",
               "top_provider":{"context_length":1000000,"max_completion_tokens":131072}}
            ]}
        """.trimIndent()
        val got = ModelCatalog.parseEntries(json, CatalogShape.OPENAI_COMPAT)
        assertEquals(2, got.size)
        assertEquals("按 ID 排序（原样回吐时也稳定）",
            listOf("deepseek/deepseek-v4.1-flash", "nvidia/nemotron-3.5-super-vl:free"),
            got.map { it.id })
        assertEquals("DeepSeek: DeepSeek V4.1 Flash", got[0].name)
        assertEquals(1_048_576, got[0].contextWindow)
        assertEquals(65_536, got[0].maxOutput)
        assertEquals("top_provider 里的元数据也认（OpenRouter 两种写法都在发）",
            1_000_000, got[1].contextWindow)
        assertEquals(131_072, got[1].maxOutput)
        assertNull("端点没给人类名 ⇒ null，不拿 ID 假装", got[1].name)
    }

    @Test
    fun Anthropic形状的人类名在display_name() {
        val json = """
            {"data":[
              {"type":"model","id":"claude-sonnet-4-5","display_name":"Claude Sonnet 4.5"},
              {"type":"model","id":"claude-opus-4-1","display_name":"Claude Opus 4.1"}
            ]}
        """.trimIndent()
        val got = ModelCatalog.parseEntries(json, CatalogShape.ANTHROPIC)
        assertEquals(listOf("claude-opus-4-1", "claude-sonnet-4-5"), got.map { it.id })
        assertEquals("Claude Sonnet 4.5", got[1].name)
        assertEquals("它的清单不给窗口 ⇒ null（按『未声明』显示，不许编）", null, got[0].contextWindow)
    }

    @Test
    fun Gemini原生剥掉models前缀() {
        val json = """
            {"models":[
              {"name":"models/gemini-2.5-pro","displayName":"Gemini 2.5 Pro",
               "inputTokenLimit":1048576,"outputTokenLimit":65536},
              {"name":"models/gemini-2.5-flash","displayName":"Gemini 2.5 Flash"}
            ]}
        """.trimIndent()
        val got = ModelCatalog.parseEntries(json, CatalogShape.GEMINI_NATIVE)
        assertEquals("资源名必须剥前缀，否则发给它的 model 名 404",
            listOf("gemini-2.5-flash", "gemini-2.5-pro"), got.map { it.id })
        assertEquals("Gemini 2.5 Pro", got[1].name)
        assertEquals(1_048_576, got[1].contextWindow)
        assertEquals(65_536, got[1].maxOutput)
        assertEquals("gemini-2.5-pro", got[1].id)
        assertEquals("端点给了人类名 ⇒ 显示名第 1 级就命中",
            "Gemini 2.5 Pro", ModelPickerRules.displayName(got[1]))
    }

    @Test
    fun 窗口字段是零或负数当作没声明() {
        val json = """{"data":[{"id":"a","context_length":0},{"id":"b","context_length":-1},
            {"id":"c","context_length":"not-a-number"},{"id":"d"}]}"""
        val got = ModelCatalog.parseEntries(json, CatalogShape.OPENAI_COMPAT)
        assertEquals(4, got.size)
        got.forEach { e ->
            assertNull("${e.id} 的 0／负数／脏类型都算未声明，不许折成数字", e.contextWindow)
        }
    }

    @Test
    fun 坏JSON与空清单都是空表不抛() {
        CatalogShape.values().forEach { shape ->
            assertTrue("$shape 坏 JSON ⇒ 空表（拉不到就退回手填）",
                ModelCatalog.parseEntries("{not json", shape).isEmpty())
            assertTrue(ModelCatalog.parseEntries("", shape).isEmpty())
            assertTrue(ModelCatalog.parseEntries("""{"data":[]}""", shape).isEmpty())
        }
        assertEquals("去重也照旧", 1,
            ModelCatalog.parseEntries("""{"data":[{"id":"a"},{"id":"a"}]}""", CatalogShape.OPENAI_COMPAT).size)
    }

    @Test
    fun 拉取入口对预设一视同仁() {
        // ③「不只拉自定义」：预设的 baseUrl 也走得通这一条拼接（网络留 IO 协程，本单不跑真机）
        Providers.all().forEach { p ->
            val url = ModelCatalog.modelsUrl(p.baseUrl, ModelCatalog.shapeFor(p.baseUrl))
            assertTrue(p.id + " 的清单地址要 https：" + url, url.startsWith("https://"))
            assertTrue(url.endsWith("/models"))
        }
        assertEquals(ModelCatalog.OPENROUTER_MODELS_URL,
            ModelCatalog.modelsUrl(Providers.OPENROUTER.baseUrl, CatalogShape.OPENAI_COMPAT))
    }

    @Test
    fun 地址不合法时给的是可读错误而不是异常() {
        // 这条覆盖"拼请求就炸"那一段：不联网，只验错误形态（网络那半段本单不跑真机 ⇒ 未验）
        val (entries, error) = ModelCatalog.fetchEntries("not a url", "")
        assertTrue(entries.isEmpty())
        assertNotNull(error)
        assertTrue("要说清是地址的问题：" + error, error!!.contains("接口地址不合法"))
    }
}
