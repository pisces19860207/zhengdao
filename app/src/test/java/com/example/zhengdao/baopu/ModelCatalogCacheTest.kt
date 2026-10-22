// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单《第一百三十一批 · 模型选择 P0》③「拉取中缓存先行；OpenRouter 免费档不缓存、每次重拉」。
//
// 时钟是注入的（`now` 参数），所以这里能确定性地测 TTL；进程内那份 Map 由 clear() 复位，
// 每条用例开头都先清，避免互相污染（这条纪律写在 ModelCatalogCache 的文件注释里）。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ModelCatalogCacheTest {

    private val t0 = 1_700_000_000_000L
    private val base = "https://api.deepseek.com/v1"
    private val a = ModelEntry("deepseek-chat", "DeepSeek Chat", 128_000, null)
    private val b = ModelEntry("deepseek-reasoner", null, null, null)

    @Before
    fun 复位() {
        ModelCatalogCache.clear()
    }

    @Test
    fun 拉回来的表先顶着_缓存先行() {
        assertTrue(ModelCatalogCache.cached(base, neverCache = false, now = t0).isEmpty())
        ModelCatalogCache.put(base, listOf(a, b), t0)
        assertEquals(listOf(a, b), ModelCatalogCache.cached(base, neverCache = false, now = t0))
    }

    @Test
    fun 免费档永远不命中() {
        ModelCatalogCache.store(base, listOf(a), neverCache = true, now = t0)
        assertTrue("neverCache ⇒ 一个字都不落", ModelCatalogCache.keys().isEmpty())
        assertTrue(ModelCatalogCache.cached(base, neverCache = true, now = t0).isEmpty())
    }

    @Test
    fun 保质期十分钟() {
        assertEquals(10 * 60_000L, ModelCatalogCache.TTL_MS)
        ModelCatalogCache.put(base, listOf(a), t0)
        assertEquals(1, ModelCatalogCache.cached(base, neverCache = false, now = t0 + ModelCatalogCache.TTL_MS).size)
        assertTrue("过一分钟就认不出来了（宁可重拉也不显示过期清单）",
            ModelCatalogCache.cached(base, neverCache = false, now = t0 + ModelCatalogCache.TTL_MS + 1).isEmpty())
    }

    @Test
    fun 按地址分格_首尾空格不算两个地址() {
        ModelCatalogCache.put("  $base  ", listOf(a), t0)
        assertEquals(setOf(base), ModelCatalogCache.keys())
        assertEquals(1, ModelCatalogCache.cached(base, neverCache = false, now = t0).size)
        assertTrue("换了地址不许拿旧表顶",
            ModelCatalogCache.cached("https://openrouter.ai/api/v1", neverCache = false, now = t0).isEmpty())
    }

    @Test
    fun 空表与空地址不写() {
        ModelCatalogCache.put(base, emptyList(), t0)
        ModelCatalogCache.put("", listOf(a), t0)
        assertTrue(ModelCatalogCache.keys().isEmpty())
    }

    @Test
    fun 同地址重写覆盖旧表() {
        ModelCatalogCache.put(base, listOf(a), t0)
        ModelCatalogCache.put(base, listOf(b), t0 + 1)
        assertEquals(listOf(b), ModelCatalogCache.cached(base, neverCache = false, now = t0 + 1))
        assertEquals("只有一格", setOf(base), ModelCatalogCache.keys())
    }

    @Test
    fun keys只暴露地址不暴露内容() {
        ModelCatalogCache.put(base, listOf(a), t0)
        val keys = ModelCatalogCache.keys()
        assertEquals(1, keys.size)
        assertFalse(keys.any { it.contains("Key") || it.contains("sk-") })
    }
}
