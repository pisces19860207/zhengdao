// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百二十八批 C 片：五件示教/回放工具的端口层负例＋注册面对账。
//
// 全在 JVM 打：执行体只认 [TeachGateway] 端口，假网关就能验「信任闸拦没拦、
// 拒点透没透传、回执带没带取证」。判据（TeachExecutor/TeachSafety/TeachSiteTrust）
// 一个都不 mock，它们各自的测试文件盯着。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.agent.BaopuToolCatalog
import com.example.zhengdao.baopu.agent.BaopuTeach
import com.example.zhengdao.baopu.agent.TeachGateway
import com.example.zhengdao.baopu.agent.TeachTools
import com.example.zhengdao.baopu.agent.ToolCall
import com.example.zhengdao.baopu.agent.ToolGroup
import com.example.zhengdao.baopu.agent.ToolGroupTable
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.baopu.agent.ToolPermission
import com.example.zhengdao.baopu.agent.ToolSchemaRules
import com.example.zhengdao.baopu.agent.ToolTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TeachToolsTest {

    /** 假网关：记下每一次调用，页面动作不真发生。 */
    private class FakeGw(
        var url: String? = "https://m.example.com/a",
        var trusted: List<String> = emptyList(),
        var allowed: List<String>? = listOf("m.example.com"),
    ) : TeachGateway {
        val calls = mutableListOf<String>()
        var trustAddErr: String? = null
        var trustRemoveErr: String? = "名单里没有它"

        override fun currentUrl(): String? = url
        override fun trustedHosts(): List<String> = trusted
        override fun trustAdd(raw: String): String? { calls += "add:$raw"; return trustAddErr }
        override fun trustRemove(raw: String): String? { calls += "remove:$raw"; return trustRemoveErr }
        override fun startRecording(): String { calls += "start"; return "开始示教：请操作页面" }
        override fun stopRecording(): String { calls += "stop"; return "已录到 3 步，生成技能草稿「m.example.com-流程」" }
        override fun saveDraft(name: String): String { calls += "save:$name"; return "已保存技能「$name」" }
        override fun skillAllowedDomains(name: String): List<String>? = allowed
        override fun replay(name: String, sentence: String?): String {
            calls += "replay:$name/${sentence ?: "-"}"
            return "回放完成：3 步；取证：/tmp/evidence"
        }
    }

    @After
    fun tearDown() {
        BaopuTeach.gateway = null
    }

    private fun specOf(name: String) =
        BaopuToolCatalog.ENTRIES.first { it.name == name }.factory({ File(".") })

    private fun runTool(name: String, gw: TeachGateway?, args: JSONObject): ToolOutcome {
        BaopuTeach.gateway = gw
        val spec = specOf(name)
        return spec.executor(args)
    }

    // ── N2：信任名单默认空 ⇒ 示教/回放全拒，且要说清怎么加白 ────────────────

    @Test
    fun `负例2_信任名单默认空_开始示教被拒_回执教人加白`() {
        val gw = FakeGw(trusted = emptyList())
        val out = runTool("teach_record_start", gw, JSONObject())
        assertTrue("必须失败而不是悄悄开始", out is ToolOutcome.Failed)
        val msg = (out as ToolOutcome.Failed).error
        assertTrue("要点名站点：$msg", msg.contains("m.example.com"))
        assertTrue("要给加白办法：$msg", msg.contains("teach_site_trust add"))
        assertEquals("一个页面动作都没发生", 0, gw.calls.size)
    }

    @Test
    fun `信任过的站点_开始示教放行`() {
        val gw = FakeGw(trusted = listOf("m.example.com"))
        val out = runTool("teach_record_start", gw, JSONObject())
        assertTrue(out is ToolOutcome.Ok)
        assertEquals(listOf("start"), gw.calls)
    }

    @Test
    fun `玄览没开页_说人话不猜`() {
        val gw = FakeGw(url = null, trusted = listOf("m.example.com"))
        val out = runTool("teach_record_start", gw, JSONObject())
        assertTrue(out is ToolOutcome.Failed)
        assertTrue((out as ToolOutcome.Failed).error.contains("玄览还没有打开的页面"))
        assertEquals(0, gw.calls.size)
    }

    // ── 回放的前置闸：name 必填 → 技能存在 → 授权域全信任 → 当前页信任 ────────

    @Test
    fun `负例2b_回放_授权域里有未信任站点_整场不跑并点名`() {
        val gw = FakeGw(
            trusted = listOf("m.example.com"),
            allowed = listOf("m.example.com", "other.example.com"),
        )
        val out = runTool(
            "browser_replay_skill", gw,
            JSONObject().put("name", "查货"),
        )
        assertTrue(out is ToolOutcome.Failed)
        val msg = (out as ToolOutcome.Failed).error
        assertTrue("要点名差哪个域：$msg", msg.contains("other.example.com"))
        assertTrue("回放一次都没发起", gw.calls.none { it.startsWith("replay:") })
    }

    @Test
    fun `回放_没有这个技能_照实说`() {
        val gw = FakeGw(trusted = listOf("m.example.com"), allowed = null)
        val out = runTool(
            "browser_replay_skill", gw,
            JSONObject().put("name", "不存在的技能"),
        )
        assertTrue(out is ToolOutcome.Failed)
        assertTrue((out as ToolOutcome.Failed).error.contains("没有这个回放技能"))
    }

    @Test
    fun `回放_name必填_跨信任站点的当前页也拒`() {
        val noName = runTool("browser_replay_skill", FakeGw(), JSONObject())
        assertTrue("name 不能为空", noName is ToolOutcome.Failed)

        val crossSite = runTool(
            "browser_replay_skill",
            FakeGw(url = "https://evil.example.net/", trusted = listOf("m.example.com")),
            JSONObject().put("name", "查货"),
        )
        assertTrue("当前页不在信任名单也要拒", crossSite is ToolOutcome.Failed)
    }

    @Test
    fun `回放_信任齐备_回执带结果与取证`() {
        val gw = FakeGw(trusted = listOf("m.example.com"))
        val out = runTool(
            "browser_replay_skill", gw,
            JSONObject().put("name", "查货").put("sentence", "查昨天那单"),
        )
        val text = (out as ToolOutcome.Ok).text
        assertTrue("回执要带结果：$text", text.contains("回放完成：3 步"))
        assertTrue("回执要带取证：$text", text.contains("取证"))
        assertEquals("replay:查货/查昨天那单", gw.calls.single())
    }

    // ── 信任名单工具：add/remove/list ───────────────────────────────────────

    @Test
    fun `teach_site_trust_add_坏域名拒_好域名归一后转发`() {
        val gw = FakeGw().apply { trustAddErr = null }
        val bad = runTool(
            "teach_site_trust", gw,
            JSONObject().put("action", "add").put("host", "192.168.1.1"),
        )
        assertTrue("IP 不收", bad is ToolOutcome.Failed)
        assertEquals(0, gw.calls.size)

        val ok = runTool(
            "teach_site_trust", gw,
            JSONObject().put("action", "add").put("host", "https://M.Example.Com/x"),
        )
        assertTrue(ok is ToolOutcome.Ok)
        assertEquals("add:m.example.com", gw.calls.single())
        assertTrue((ok as ToolOutcome.Ok).text.contains("撤销：teach_site_trust remove m.example.com"))
    }

    @Test
    fun `teach_site_trust_list_空名单说后果_非空列名字`() {
        val empty = runTool("teach_site_trust", FakeGw(), JSONObject().put("action", "list"))
        assertTrue((empty as ToolOutcome.Ok).text.contains("信任名单是空的"))
        assertTrue((empty as ToolOutcome.Ok).text.contains("默认全拒"))

        val some = runTool(
            "teach_site_trust",
            FakeGw(trusted = listOf("m.example.com", "b.com")),
            JSONObject().put("action", "list"),
        )
        val text = (some as ToolOutcome.Ok).text
        assertTrue(text.contains("信任名单（2）"))
        assertTrue(text.contains("m.example.com"))
    }

    @Test
    fun `teach_site_trust_撤了个寂寞要照实说_非法action也要拒`() {
        val gw = FakeGw().apply { trustRemoveErr = "名单里没有「b.com」" }
        val miss = runTool(
            "teach_site_trust", gw,
            JSONObject().put("action", "remove").put("host", "b.com"),
        )
        assertTrue(miss is ToolOutcome.Failed)
        assertTrue((miss as ToolOutcome.Failed).error.contains("名单里没有"))

        val badAction = runTool("teach_site_trust", gw, JSONObject().put("action", "grant"))
        assertTrue(badAction is ToolOutcome.Failed)

        val noHost = runTool("teach_site_trust", gw, JSONObject().put("action", "remove"))
        assertTrue("remove 缺 host 要点名", noHost is ToolOutcome.Failed)
    }

    // ── 录制/保存：端口透传与缺草稿话术 ─────────────────────────────────────

    @Test
    fun `stop与save_端口透传_回执原样给`() {
        val gw = FakeGw()
        val stop = runTool("teach_record_stop", gw, JSONObject())
        assertTrue((stop as ToolOutcome.Ok).text.contains("已录到 3 步"))
        val save = runTool("teach_skill_save", gw, JSONObject().put("name", "查货"))
        assertTrue((save as ToolOutcome.Ok).text.contains("已保存技能「查货」"))
        assertEquals(listOf("stop", "save:查货"), gw.calls)
    }

    @Test
    fun `teach_record_stop与start无参数也要过SchemaCheck的签字表`() {
        // S5：required 为空必须在签字表里登记过原因（漏登记 ⇒ 生成侧直接红）
        ToolSchemaRules.REQUIRED_EMPTY_OK.containsKey("teach_record_start")
        ToolSchemaRules.REQUIRED_EMPTY_OK.containsKey("teach_record_stop")
        ToolSchemaRules.REQUIRED_EMPTY_OK.containsKey("teach_skill_save")
        ToolSchemaRules.REQUIRED_EMPTY_OK.containsKey("teach_record_start")
    }

    // ── N1：表里没有这一行 ⇒ fail-closed（R2＋必问＋unclassified_tool） ─────

    @Test
    fun `负例1_五行都进了RiskMatrix_拔掉一行就fail-closed`() {
        val five = listOf(
            "teach_record_start", "teach_record_stop", "teach_skill_save",
            "browser_replay_skill", "teach_site_trust",
        )
        // 全在表里：全量注册名求差集为空（全覆盖门禁）
        val registered = BaopuToolCatalog.ENTRIES.map { it.name }.toSet() +
            setOf("write_skill", "append_persona")  // 第四刀：read/bash（COMPAT）已退役
        assertEquals("注册工具必须全有账", emptyList<String>(), RiskMatrix.uncovered(registered))

        // 拔掉 browser_replay_skill 一行 ⇒ 查不到档 = R2＋MUST_ASK＋unclassified_tool
        val broken = RiskTable.DEFAULT.copy(tools = RiskTable.DEFAULT.tools - "browser_replay_skill")
        val v = RiskMatrix.classify(
            ToolCall("t", "browser_replay_skill", JSONObject()),
            RiskCtx(),
            broken,
        )
        assertEquals(com.example.zhengdao.baopu.RiskClass.R2, v.risk)
        assertEquals(RiskDecision.MUST_ASK, v.decision)
        assertTrue(v.unclassified)
        assertEquals("unclassified_tool", v.auditTag)
    }

    // ── 注册面对账：组、默认开关、spec 元数据 ───────────────────────────────

    @Test
    fun `注册面_五件都在表上_组是BROWSER_默认启用`() {
        val five = listOf(
            "teach_record_start", "teach_record_stop", "teach_skill_save",
            "browser_replay_skill", "teach_site_trust",
        )
        val entries = BaopuToolCatalog.ENTRIES.map { it.name }
        five.forEach { name ->
            assertNotNull("ENTRIES 缺 $name", entries.firstOrNull { it == name })
            assertEquals("$name 的组", ToolGroup.BROWSER, ToolGroupTable.GROUP_OF[name])
            assertTrue("DEFAULT_ENABLED 缺 $name", name in BaopuToolCatalog.DEFAULT_ENABLED)
        }
        // 「默认可见 ≤12」的硬卡归 ProgressiveToolsTest B1 盯（读同一个真运行时），
        // 这里只对账五件的组与开关，不重复实现那张卡。
    }

    @Test
    fun `spec元数据_回放120秒必确认_停录制免确认_信任必确认`() {
        val replay = specOf("browser_replay_skill")
        assertEquals(ToolPermission.CONFIRM, replay.permission)
        assertEquals(ToolTimeout.REPLAY_MS, replay.timeoutMs)

        val stop = specOf("teach_record_stop")
        assertEquals(ToolPermission.FREE, stop.permission)

        val start = specOf("teach_record_start")
        assertEquals(ToolPermission.CONFIRM, start.permission)

        val trust = specOf("teach_site_trust")
        assertEquals(ToolPermission.CONFIRM, trust.permission)

        // 描述的注入面自律上限
        BaopuToolCatalog.ENTRIES.map { it.name }.filter { it.startsWith("teach") || it == "browser_replay_skill" }
            .forEach { name ->
                val s = specOf(name)
                assertTrue("$name 描述超 500：${s.description.length}", s.description.length <= 500)
            }
    }
}
