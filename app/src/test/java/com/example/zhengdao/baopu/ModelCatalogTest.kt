// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
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
}
