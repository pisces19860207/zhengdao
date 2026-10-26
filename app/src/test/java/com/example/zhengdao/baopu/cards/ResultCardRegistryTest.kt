// 独立开发声明：本文件为本项目原创。
package com.example.zhengdao.baopu.cards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第二百一十二批 · 注册表（派工单 §2.5「新增场景＝加一份 JSON，不改组件代码」）。
 *
 * 两条要钉死的：
 * ① **没有硬编码模板 id**：给什么 id 就读什么文件（否则「加场景不用改代码」是空话）；
 * ② 读不到／读坏 ⇒ 当作没有这张卡（不弹空卡、不拿上一份顶替），并且**坏结果也进缓存**
 *    （同一份坏文件不必每次重编）。
 */
class ResultCardRegistryTest {

    private val minimal = """{"templateId":"demo.any","version":1,"titleKey":"t","groups":[
        {"key":"g","rows":[{"key":"a","labelKey":"l"},{"key":"b","labelKey":"l2","dialable":true}]}]}"""

    @Test
    fun `任意模板 id 都能加载_没有白名单`() {
        val reads = ArrayList<String>()
        val reg = ResultCardRegistry { path -> reads += path; if (path.endsWith("demo.any.json")) minimal else null }
        val card = reg.card("demo.any", mapOf("a" to "甲"), { k -> k })
        assertEquals("demo.any", card?.templateId)
        assertEquals(listOf("baopu/cards/demo.any.json"), reads)
    }

    @Test
    fun `同一模板只读一次_坏结果也缓存`() {
        var n = 0
        val reg = ResultCardRegistry { n++; "不是 JSON" }
        repeat(3) {
            assertTrue(reg.template("x") is TemplateLoad.Bad)
        }
        assertEquals("坏结果必须进缓存，否则每次打开同一个坏模板都要重编一遍", 1, n)
        assertNull(reg.card("x", emptyMap(), { k -> k }))
    }

    @Test
    fun `读不到资产_不弹空卡`() {
        val reg = ResultCardRegistry { null }
        val load = reg.template("nope")
        assertTrue(load is TemplateLoad.Bad)
        assertTrue(
            "要说清缺哪一份文件（新增场景最容易漏的就是这一步）",
            (load as TemplateLoad.Bad).reason.contains("baopu/cards/nope.json"),
        )
        assertNull(reg.card("nope", mapOf("a" to "1"), { k -> k }))
    }

    @Test
    fun `读资产抛异常也不许把界面带崩`() {
        val reg = ResultCardRegistry { throw IllegalStateException("assets 被关了") }
        assertTrue(reg.template("x") is TemplateLoad.Bad)
    }

    @Test
    fun `路径就是 assets 根下的固定位置`() {
        assertEquals("baopu/cards/study.knowledge.json", ResultCardRegistry.assetPathOf("study.knowledge"))
    }
}
