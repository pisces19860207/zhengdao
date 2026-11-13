// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 622 判据③④：可版本化动作脚本的导出/导入全链路。
 *
 * - **录一次 → 导出 → 导入 → 回放成功**（判据③正例）；
 * - **改一个校验点 ⇒ 回放停在正确那一步**（判据③负例）；
 * - **脚本层直接拒不可逆动作**：手改塞进来的提交类点击，导入拒、落盘拒（判据④）；
 * - **不存凭据**：带明文凭证的脚本导出拒、导入拒；
 * - v1 老脚本向后兼容（读得出、序列化升 v2）。
 */
class TeachScriptExchangeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val projectRoot: File by lazy { tmp.newFolder("ws") }
    private val globalRoot: File by lazy { tmp.newFolder("app") }

    private fun files(): PersonaFiles = PersonaFiles(projectRoot = projectRoot, globalRoot = globalRoot)

    private val startUrl = "https://demo.example.com/list"
    private val detailUrl = "https://demo.example.com/detail"

    /** 「录一次」：两个原始事件（填单号 → 点查询），与真实录制流同一条建草稿路。 */
    private fun recordDraft(): TeachSkillDraft {
        val events = listOf(
            TeachRawEvent(
                action = TeachAction.TYPE,
                url = startUrl,
                element = TeachElementInfo(
                    tag = "INPUT", role = "searchbox", name = null, text = null,
                    elemId = "q", inputName = "wb", inputType = "text",
                ),
                value = "WB1",
                beforeUrl = startUrl, afterUrl = startUrl, beforeRows = 5, afterRows = 5,
            ),
            TeachRawEvent(
                action = TeachAction.CLICK,
                url = startUrl,
                element = TeachElementInfo(tag = "BUTTON", role = "button", name = "查询", text = "查询"),
                value = null,
                beforeUrl = startUrl, afterUrl = detailUrl, beforeRows = 5, afterRows = 7,
            ),
        )
        return TeachLearner.buildDraft(
            events, "查单", "按单号查状态",
            TeachPageFacts(startUrl, listOf("单号"), listOf("查询")),
            nowMs = 1_728_000_000_000L,
        )
    }

    private fun save(skillDraft: TeachSkillDraft) {
        val r = TeachReplayStore.saveConfirmed(skillDraft, projectRoot, globalRoot, files(), "20261113-000001")
        assertNotNull("保存应成功：${r.reason}", r.written)
    }

    /** 回放用的假 DOM：按技能里真实记下的定位链命中唯一元素（与 TeachExecutorTest 同型）。 */
    private inner class StubDom : TeachDom {
        var urlNow: String = startUrl
        private val hits = mutableMapOf<String, TeachHit>()

        fun registerAll(skill: TeachSkill) {
            for (s in skill.steps) {
                if (s.locators.isEmpty()) continue
                val hit = when (s.action) {
                    TeachAction.TYPE, TeachAction.SELECT -> TeachHit(
                        tag = s.snapshot.tag, role = s.snapshot.role,
                        name = s.snapshot.name?.takeIf { it.isNotBlank() },
                        text = s.snapshot.text?.takeIf { it.isNotBlank() },
                        inputType = "text", inputName = "wb",
                    )
                    else -> TeachHit(
                        tag = s.snapshot.tag, role = s.snapshot.role,
                        name = s.snapshot.name?.takeIf { it.isNotBlank() },
                        text = s.snapshot.text?.takeIf { it.isNotBlank() },
                    )
                }
                s.locators.forEach { hits["${it.kind}:${it.value}"] = hit }
            }
        }

        override fun ask(query: TeachQuery): TeachReply = when (query) {
            is TeachQuery.Scan ->
                TeachReply.Hits(hits["${query.locator.kind}:${query.locator.value}"]?.let { listOf(it) } ?: emptyList())
            is TeachQuery.FindBySnapshot -> TeachReply.Hits(emptyList())
            TeachQuery.CurrentUrl -> TeachReply.Url(urlNow)
            is TeachQuery.TextPresent -> TeachReply.Flag(query.text == "WB1")
            TeachQuery.RowCount -> TeachReply.Count(7)
            TeachQuery.HeadersAndButtons -> TeachReply.Anchors(listOf("单号"), listOf("查询"))
            is TeachQuery.Act -> {
                if (query.action == TeachAction.CLICK) urlNow = detailUrl
                TeachReply.Acted(ok = true, reason = null, urlAfter = urlNow)
            }
            TeachQuery.DomSummary -> TeachReply.Summary("title=演示 rows=7 interactive=[查询]")
        }
    }

    private fun replay(skill: TeachSkill): TeachRunReport {
        val dom = StubDom().apply { registerAll(skill) }
        var finalCalled = false
        val sink = object : TeachEvidenceSink {
            val dirs = mutableListOf<String>()
            override fun begin(skillName: String, stamp: String): String? {
                val d = "mem://$skillName-$stamp"
                dirs += d
                return d
            }
            override fun step(index: Int, action: TeachAction, summary: String, screenshotRef: String?) {}
            override fun final(summary: String, screenshotRef: String?) { finalCalled = true }
            override fun finish(report: TeachRunReport) {}
        }
        // 假钟要会走：被改坏的校验点永远不满足，靠轮询超时停（静止假钟会死循环）
        var t = 0L
        val executor = TeachExecutor(dom = dom, evidence = sink, clock = { t }, sleeper = { t += 150 })
        val report = executor.run(skill)
        // 判据⑤：**成功**回放终态取证必采（失败回放的终局证据是失败取证，不走 final）
        if (report.ok) assertTrue("终态取证必采（判据⑤）", finalCalled)
        return report
    }

    // ── 判据③ 正例：录一次 → 导出 → 导入 → 回放成功 ────────────────────────────

    @Test
    fun `判据③_录一次_导出_导入_回放成功_脚本五要素齐`() {
        val draft = recordDraft()
        save(draft)
        // 导出
        val exp = TeachScriptExchange.export(projectRoot, projectRoot, "查单")
        assertNotNull("导出应成功：${exp.reason}", exp.file)
        val json = JSONObject(exp.file!!.readText(Charsets.UTF_8))
        // 五要素：版本/目标站/动作序列/每步校验点/最终快照
        assertEquals("zl-teach/2", json.getString("schema"))
        assertEquals("demo.example.com", json.getString("targetSite"))
        assertTrue(json.getJSONArray("steps").length() == 2)
        assertTrue(
            "每步有校验点",
            (0 until json.getJSONArray("steps").length()).all { i ->
                json.getJSONArray("steps").getJSONObject(i).getJSONArray("expect").length() > 0
            },
        )
        assertTrue(json.has("finalSnapshot"))
        assertEquals(detailUrl, json.getJSONObject("finalSnapshot").getString("url"))
        // 「模拟换机」：技能库清空
        File(projectRoot, "技能").deleteRecursively()
        assertNull(TeachReplayStore.load(projectRoot, "查单"))
        // 导入
        val imp = TeachScriptExchange.import(projectRoot, globalRoot, files(), "查单", "20261113-000002")
        assertNotNull("导入应成功：${imp.reason}", imp.file)
        val back = TeachReplayStore.load(projectRoot, "查单")
        assertNotNull(back)
        assertEquals(draft.skill, back)
        // 回放成功
        val report = replay(back!!)
        assertTrue("回放应成功：${report.stopReason}", report.ok)
        assertEquals(2, report.stepsDone)
        assertTrue(report.evidenceDir != null || report.drifts.isEmpty())
    }

    // ── 判据③ 负例：改一个校验点 ⇒ 回放停在正确那一步 ──────────────────────────

    @Test
    fun `判据③负例_改第1步校验点_回放停在正确那步并给建议`() {
        save(recordDraft())
        val exp = TeachScriptExchange.export(projectRoot, projectRoot, "查单")
        val tampered = exp.file!!.readText(Charsets.UTF_8)
            .replace("\"kind\":\"textAppears\",\"value\":\"WB1\"", "\"kind\":\"textAppears\",\"value\":\"页面上不存在的词\"")
        assertTrue("篡改必须生效", tampered.contains("页面上不存在的词"))
        exp.file.writeText(tampered, Charsets.UTF_8)
        // 导入（schema 合法 ⇒ 闸放行）并回放
        val imp = TeachScriptExchange.import(projectRoot, globalRoot, files(), "查单", "20261113-000003")
        assertNotNull(imp.file)
        val report = replay(TeachReplayStore.load(projectRoot, "查单")!!)
        assertFalse(report.ok)
        assertEquals("必须停在**被改的那一步**（第 1 步）", 1, report.failedStep)
        assertTrue(report.stopReason!!.contains("第 1 步"))
        assertNotNull("失败必须带建议（A④）", report.suggestion)
        assertTrue(report.suggestion!!.contains("校验点"))
    }

    @Test
    fun `判据③负例_改第2步校验点_停在第2步而非重跑或乱点`() {
        save(recordDraft())
        val exp = TeachScriptExchange.export(projectRoot, projectRoot, "查单")
        val tampered = exp.file!!.readText(Charsets.UTF_8)
            .replace("\"kind\":\"urlChanged\",\"value\":\"\"", "\"kind\":\"urlChanged\",\"value\":\"\",\"broken\":\"x\"")
        assertTrue("篡改必须生效", tampered.contains("\"broken\":\"x\""))
        exp.file.writeText(tampered, Charsets.UTF_8)
        // 严格解析：篡改出的未知键会让导入整份拒绝（编辑器不可能塞结构外的东西）
        val imp = TeachScriptExchange.import(projectRoot, globalRoot, files(), "查单", "20261113-000004")
        assertNull("结构外键必须在 schema 闸死", imp.file)
        assertTrue(imp.reason!!.contains("不是可导入的玄览动作脚本"))
        // 改成「合法但作废」的校验点：重新导出一份干净的（上一次篡改的文件已被闸判死），
        // 第 2 步改成 TEXT_APPEARS 指向不存在的词 ⇒ 第 2 步停
        val exp2 = TeachScriptExchange.export(projectRoot, projectRoot, "查单")
        val tampered2 = exp2.file!!.readText(Charsets.UTF_8)
            .replace("\"kind\":\"urlChanged\",\"value\":\"\"", "\"kind\":\"textAppears\",\"value\":\"页面上不存在的词\"")
        assertTrue("第二次篡改必须生效", tampered2.contains("页面上不存在的词"))
        exp2.file.writeText(tampered2, Charsets.UTF_8)
        val imp2 = TeachScriptExchange.import(projectRoot, globalRoot, files(), "查单", "20261113-000005")
        assertNotNull("合法形状的篡改应能进门（判据留给回放）：${imp2.reason}", imp2.file)
        val report = replay(TeachReplayStore.load(projectRoot, "查单")!!)
        assertFalse(report.ok)
        assertEquals(2, report.failedStep)
    }

    // ── 判据④：脚本层直接拒不可逆动作（不许进门，回放更执行不到） ────────────────

    @Test
    fun `判据④负例_手改脚本塞提交类点击_导入拒`() {
        save(recordDraft())
        val exp = TeachScriptExchange.export(projectRoot, projectRoot, "查单")
        val poisoned = exp.file!!.readText(Charsets.UTF_8)
            .replace("\"name\":\"查询\"", "\"name\":\"提交订单\"")
        exp.file.writeText(poisoned, Charsets.UTF_8)
        val imp = TeachScriptExchange.import(projectRoot, globalRoot, files(), "查单", "20261113-000006")
        assertNull("不可逆动作必须在脚本层拒收", imp.file)
        assertTrue(imp.reason!!.contains("不可逆动作"))
        assertTrue(imp.reason!!.contains("提交"))
    }

    @Test
    fun `判据④负例_手改草稿塞提交类点击_落盘层也拒`() {
        val draft = recordDraft()
        val poisoned = draft.copy(
            skill = draft.skill.copy(
                steps = draft.skill.steps.map { s ->
                    if (s.action == TeachAction.CLICK) {
                        s.copy(snapshot = s.snapshot.copy(name = "确认删除"))
                    } else {
                        s
                    }
                },
            ),
        )
        val r = TeachReplayStore.saveConfirmed(poisoned, projectRoot, globalRoot, files(), "20261113-000007")
        assertNull("落盘层（save 确认卡那一步）必须拒", r.written)
        assertTrue(r.reason!!.contains("不可逆动作"))
    }

    // ── 不存凭据：导出与导入两道闸 ──────────────────────────────────────────────

    @Test
    fun `不存凭据_手改脚本塞明文凭证_导入拒_导出也拒`() {
        save(recordDraft())
        val exp = TeachScriptExchange.export(projectRoot, projectRoot, "查单")
        val poisoned = exp.file!!.readText(Charsets.UTF_8)
            .replace("\"value\":\"WB1\"", "\"value\":\"eyJhbGciOiJIUzI1NiJ9\"")
        exp.file.writeText(poisoned, Charsets.UTF_8)
        val imp = TeachScriptExchange.import(projectRoot, globalRoot, files(), "查单", "20261113-000008")
        assertNull("导入必须拒（不存凭据）", imp.file)
        assertTrue(imp.reason!!.contains("明文凭证"))
        // 导出侧同闸：直接改盘上技能（模拟手改）再导出
        val f = TeachReplayStore.sidecarFile(projectRoot, "查单")
        f.writeText(poisoned, Charsets.UTF_8)
        val exp2 = TeachScriptExchange.export(projectRoot, projectRoot, "查单")
        assertNull("导出必须拒（带病脚本不出门）", exp2.file)
        assertTrue(exp2.reason!!.contains("明文凭证"))
    }

    // ── 版本化：v1 老脚本向后兼容，序列化升 v2 ──────────────────────────────────

    @Test
    fun `版本化_v1老脚本读得出_序列化升v2_新字段缺省`() {
        val v1 = """{"schema":"zl-teach/1","name":"老技能","purpose":"","createdAt":0,""" +
            """"startUrl":"https://old.example.com/","allowedDomains":["old.example.com"],""" +
            """"clickTagWhitelist":["BUTTON"],"fingerprint":{"anchors":[]},"steps":[{"i":1,"action":"click",""" +
            """"url":"https://old.example.com/","locators":[{"tier":7,"kind":"text","value":"查看"}],""" +
            """"value":"","snapshot":{"tag":"BUTTON","role":"button","name":"查看","text":"查看","inputType":""},""" +
            """"expect":[{"kind":"urlChanged","value":""}]}]}"""
        val back = TeachSkill.fromJson(v1)
        assertNotNull("v1 必须还能读（向后兼容）", back)
        assertEquals("", back!!.targetSite)
        assertNull(back.finalSnapshot)
        // 序列化升 v2：版本串与空终态都在字节里（终态取证不缺位）；
        // 解析侧空终态归一为 null（构造→序列化→解析的往返判据不受影响）
        assertEquals("zl-teach/2", TeachSkill.SCHEMA)
        val upText = back.toJsonText()
        assertTrue("序列化必须写 v2 版本串", upText.contains("\"schema\":\"zl-teach/2\""))
        assertTrue("v1 缺省的最终快照序列化成空终态（取证不缺位）", upText.contains("\"finalSnapshot\":{\"url\":\"\",\"expects\":[]}"))
        val up = TeachSkill.fromJson(upText)!!
        assertEquals("", up.targetSite)
        assertNull("空终态解析归一为 null", up.finalSnapshot)
        // 不认识的 schema 版本整份拒收
        assertNull(TeachSkill.fromJson(v1.replace("zl-teach/1", "zl-teach/9")))
    }
}
