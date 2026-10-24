// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十六批 · 格式金样：技能 JSON 的规范形态在这里钉死（交付报告里的格式定义
// 与这份判据互为锚——改格式必须先过这里，不许悄悄漂）。顺带把学习器真实产出的
// 5 步样例导出到 build/teach-sample/（报告引用的就是它，不是手写的）。
package com.example.zhengdao.baopu

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TeachSampleTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 紧凑两步技能的**完整**规范序列化（金样：键序、嵌套、转义都以这份为准）。 */
    @Test
    fun `金样_紧凑技能的完整序列化一字不差`() {
        val skill = TeachSkill(
            name = "样例-查单",
            purpose = "按单号查状态",
            createdAtMs = 0L,
            startUrl = "https://demo.example.com/list",
            allowedDomains = listOf("demo.example.com"),
            clickTagWhitelist = listOf("BUTTON"),
            fingerprint = TeachFingerprint(listOf(TeachFingerprintAnchor("header", "单号"))),
            steps = listOf(
                TeachStep(
                    index = 1, action = TeachAction.TYPE, url = "https://demo.example.com/list",
                    locators = listOf(
                        TeachLocator(TeachLocatorKind.ELEM_ID, "q"),
                        TeachLocator(TeachLocatorKind.INPUT_NAME, "wb"),
                    ),
                    value = "WB1",
                    snapshot = TeachElementSnapshot("INPUT", "searchbox", null, null, "search"),
                    expect = listOf(TeachExpect(TeachExpectKind.TEXT_APPEARS, "WB1")),
                ),
                TeachStep(
                    index = 2, action = TeachAction.CLICK, url = "https://demo.example.com/list",
                    locators = listOf(
                        TeachLocator(TeachLocatorKind.ROLE_NAME, "button|查询"),
                        TeachLocator(TeachLocatorKind.TEXT, "查询"),
                    ),
                    value = null,
                    snapshot = TeachElementSnapshot("BUTTON", "button", "查询", "查询", null),
                    expect = listOf(TeachExpect(TeachExpectKind.URL_CHANGED, null)),
                ),
            ),
        )
        val expected = """{"schema":"zl-teach/1","name":"样例-查单","purpose":"按单号查状态","createdAt":0,""" +
            """"startUrl":"https://demo.example.com/list","allowedDomains":["demo.example.com"],""" +
            """"clickTagWhitelist":["BUTTON"],"fingerprint":{"anchors":[{"kind":"header","name":"单号"}]},""" +
            """"steps":[{"i":1,"action":"type","url":"https://demo.example.com/list",""" +
            """"locators":[{"tier":4,"kind":"elemId","value":"q"},{"tier":5,"kind":"inputName","value":"wb"}],""" +
            """"value":"WB1","snapshot":{"tag":"INPUT","role":"searchbox","name":"","text":"","inputType":"search"},""" +
            """"expect":[{"kind":"textAppears","value":"WB1"}]},""" +
            """{"i":2,"action":"click","url":"https://demo.example.com/list",""" +
            """"locators":[{"tier":1,"kind":"roleName","value":"button|查询"},{"tier":7,"kind":"text","value":"查询"}],""" +
            """"value":"","snapshot":{"tag":"BUTTON","role":"button","name":"查询","text":"查询","inputType":""},""" +
            """"expect":[{"kind":"urlChanged","value":""}]}]}"""
        assertEquals(expected, skill.toJsonText())
        assertEquals(skill, TeachSkill.fromJson(expected))
    }

    /** 学习器真产出的 5 步技能：结构齐整（每步≥2 档、有 expect、指纹在），导出给报告。 */
    @Test
    fun `导出_学习器真实产出的五步样例_结构判据齐`() {
        val draft = TeachLearner.buildDraft(
            TeachLearnerTestAccess.fiveEvents(), "查货", "查当日报文",
            TeachPageFacts(
                "https://search.example.com/start",
                listOf("关键词"), listOf("搜索", "提交"),
            ),
            nowMs = 1_728_000_000_000L,
        )
        val skill = draft.skill
        assertEquals(5, skill.steps.size)
        assertTrue(skill.steps.all { it.locators.size >= 2 && it.expect.isNotEmpty() })
        assertTrue(skill.fingerprint.anchors.any { it.name == "关键词" })

        // 落一遍盘（走 184 写口），再把两份产物拷到 build/teach-sample/ 供交付报告引用
        val projectRoot = tmp.newFolder("ws")
        val globalRoot = tmp.newFolder("app")
        val saved = TeachReplayStore.saveConfirmed(
            draft, projectRoot, globalRoot,
            PersonaFiles(projectRoot = projectRoot, globalRoot = globalRoot),
            "20261025-000000",
        )
        assertNotNull(saved.written)
        val out = File("build/teach-sample").apply { mkdirs() }
        runCatching {
            saved.written!!.copyTo(File(out, "回放-查货.json"), overwrite = true)
            TeachReplayStore.skillMdFile(projectRoot, "查货").copyTo(File(out, "SKILL-查货.md"), overwrite = true)
            File(out, "摘要-查货.txt").writeText(draft.summary, Charsets.UTF_8)
        }

        // 结构判据（JSON 层面再核一遍——报告里的格式表与这份对得上）。
        // 键集用 Set 比：解析进 HashMap 的键序不定，规范键序由原文（金样）钉。
        val o = JSONObject(skill.toJsonText())
        assertEquals(TeachSkill.SCHEMA, o.getString("schema"))
        assertEquals(9, o.length()) // 顶层键恰好 9 个：schema/name/purpose/createdAt/startUrl/allowedDomains/clickTagWhitelist/fingerprint/steps
        val steps = o.getJSONArray("steps")
        for (i in 0 until steps.length()) {
            val s = steps.getJSONObject(i)
            assertEquals(
                setOf("i", "action", "url", "locators", "value", "snapshot", "expect"),
                s.keys().asSequence().toSet(),
            )
            assertTrue(s.getJSONArray("locators").length() >= 2)
        }
    }
}
