// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十六批 · 技能 JSON 模型单测：格式往返、规范序列化（哈希判据的地基）。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeachModelsTest {

    private fun sampleSkill(): TeachSkill = TeachSkill(
        name = "search.example.com-查货",
        purpose = "查当日报文",
        createdAtMs = 1_728_000_000_000L,
        startUrl = "https://search.example.com/start",
        allowedDomains = listOf("search.example.com"),
        clickTagWhitelist = listOf("BUTTON"),
        fingerprint = TeachFingerprint(
            listOf(TeachFingerprintAnchor("header", "关键词"), TeachFingerprintAnchor("button", "搜索")),
        ),
        steps = listOf(
            TeachStep(
                index = 1,
                action = TeachAction.TYPE,
                url = "https://search.example.com/start",
                locators = listOf(
                    TeachLocator(TeachLocatorKind.ELEM_ID, "search-input"),
                    TeachLocator(TeachLocatorKind.INPUT_NAME, "q"),
                    TeachLocator(TeachLocatorKind.PLACEHOLDER, "输入关键词"),
                ),
                value = "WB12345678",
                snapshot = TeachElementSnapshot("INPUT", "searchbox", null, null, "search"),
                expect = listOf(TeachExpect(TeachExpectKind.TEXT_APPEARS, "WB12345678")),
            ),
            TeachStep(
                index = 2,
                action = TeachAction.CLICK,
                url = "https://search.example.com/start",
                locators = listOf(
                    TeachLocator(TeachLocatorKind.ROLE_NAME, "button|搜索"),
                    TeachLocator(TeachLocatorKind.TEXT, "搜索"),
                    TeachLocator(TeachLocatorKind.CSS_CLASS, "btn.primary"),
                ),
                value = null,
                snapshot = TeachElementSnapshot("BUTTON", "button", "搜索", "搜索", null),
                expect = listOf(TeachExpect(TeachExpectKind.URL_CHANGED, null)),
            ),
        ),
    )

    @Test
    fun `序列化往返_字段一字不差`() {
        val skill = sampleSkill()
        val text = skill.toJsonText()
        val back = TeachSkill.fromJson(text)
        assertNotNull(back)
        assertEquals(skill, back)
    }

    @Test
    fun `规范序列化_同数据两次输出字节一致`() {
        // 「技能未变（哈希不变）」负例判据的地基：键序固定，不依赖 JSONObject 无序输出
        assertEquals(sampleSkill().toJsonText(), sampleSkill().toJsonText())
        // 换一种构造顺序（步骤 list 顺序即语义），只要数据同，输出同
        val json1 = sampleSkill().toJsonText()
        val json2 = TeachSkill.fromJson(json1)!!.toJsonText()
        assertEquals(json1, json2)
    }

    @Test
    fun `schema不对或步骤空_拒绝解析`() {
        assertNull(TeachSkill.fromJson("""{"schema":"别的","steps":[]}"""))
        assertNull(TeachSkill.fromJson("""{"schema":"zl-teach/1","steps":[]}"""))
        assertNull(TeachSkill.fromJson("""{"schema":"zl-teach/1"}"""))
        assertNull(TeachSkill.fromJson("not json"))
        // 非 WAIT 步骤定位链空 ⇒ 拒（技能不完整不许回放）
        val noLocator = """{"schema":"zl-teach/1","name":"x","steps":[
            {"i":1,"action":"click","locators":[],"snapshot":{"tag":"BUTTON"}}]}"""
        assertNull(TeachSkill.fromJson(noLocator))
        // WAIT 步允许空定位链（只等 expect）
        val waitOnly = """{"schema":"zl-teach/1","name":"x","steps":[
            {"i":1,"action":"waitFor","locators":[],"snapshot":{"tag":"BODY"},
             "expect":[{"kind":"rowCountChange","value":""}]}]}"""
        assertNotNull(TeachSkill.fromJson(waitOnly))
    }

    @Test
    fun `十档置信度_语义档全过执行闸_低档永远过不了`() {
        (1..8).forEach { t ->
            assertTrue("tier $t 应高于执行闸", tierConfidence(t) >= TEACH_EXECUTE_MIN_CONFIDENCE)
        }
        (9..10).forEach { t ->
            assertTrue("tier $t 应低于执行闸", tierConfidence(t) < TEACH_EXECUTE_MIN_CONFIDENCE)
        }
        assertEquals(TEACH_LOW_TIERS, setOf(9, 10))
    }

    @Test
    fun `定位链tier升序_从JSON读回也按tier排序`() {
        val skill = sampleSkill()
        val locators = TeachSkill.fromJson(skill.toJsonText())!!.steps[1].locators
        assertEquals(listOf(1, 7, 9), locators.map { it.tier })
    }

    @Test
    fun `改一个字段_序列化必须变_哈希判据成立`() {
        val a = sampleSkill()
        val b = sampleSkill().copy(steps = a.steps.drop(1))
        assertNotEquals(a.toJsonText(), b.toJsonText())
    }

    @Test
    fun `JSON顶层键序_schema在前steps在后_格式报告的锚`() {
        // 键序钉在**原文**上（规范序列化手写固定键序；解析后的 HashMap 键序不定不参与判据）
        val text = sampleSkill().toJsonText()
        val order = listOf("\"schema\"", "\"name\"", "\"purpose\"", "\"createdAt\"", "\"startUrl\"",
            "\"allowedDomains\"", "\"clickTagWhitelist\"", "\"fingerprint\"", "\"steps\"")
        var last = -1
        order.forEach { key ->
            val idx = text.indexOf(key)
            assertTrue("$key 必须在原文里", idx >= 0)
            assertTrue("$key 必须按规范键序出现", idx > last)
            last = idx
        }
    }
}
