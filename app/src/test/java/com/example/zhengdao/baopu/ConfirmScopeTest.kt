// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百七十一批：确认卡降噪 v2 的纯逻辑测试（键归一 / 风险分类 / 授权账本 / 审计）。
// 判据对应派工单 §2：同 key 同作用域只问一次；参数变 ⇒ 新 key；换会话不继承；
// 高风险只能一次；授权可撤销；运行/会话结束自动清；审计记「谁授权/范围/次数」。
package com.example.zhengdao.baopu

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 范围授权纯逻辑（ConfirmScope.kt）——无 Android、无引擎，JVM 直测。
 *
 * 账本是进程级单例 ⇒ 每例前后 [ConfirmGrants.resetForTest]（与
 * WorkspaceGrants.resetForTest 同款纪律，别让上一例的授权渗到下一例）。
 */
class ConfirmScopeTest {

    @After
    fun tearDown() {
        ConfirmGrants.resetForTest()
        ConfirmScopeChoice.resetForTest()
    }

    private fun args(vararg pairs: Pair<String, Any?>): JSONObject =
        JSONObject().also { j -> pairs.forEach { (k, v) -> j.put(k, v) } }

    // ── 决策键：工具 + 规范化参数 ────────────────────────────────────────

    @Test
    fun `键序不同_同一个请求_键相同`() {
        val a = args("path" to "x.txt", "content" to "甲", "overwrite" to true)
        val b = args("overwrite" to true, "content" to "甲", "path" to "x.txt")
        assertEquals(
            "模型两次输出的键序不该制造两个请求",
            ConfirmKeys.of("write_file", a),
            ConfirmKeys.of("write_file", b),
        )
    }

    @Test
    fun `参数改一处_新键_重新问`() {
        val a = args("path" to "x.txt", "content" to "甲")
        val b = args("path" to "x.txt", "content" to "乙")
        assertTrue(
            "内容变了就是另一个请求（键必须不同）",
            ConfirmKeys.of("write_file", a) != ConfirmKeys.of("write_file", b),
        )
    }

    @Test
    fun `换工具_不共享键`() {
        val same = args("path" to "x.txt")
        assertTrue(
            "不同工具不共享授权（键必须不同）",
            ConfirmKeys.of("write_file", same) != ConfirmKeys.of("edit_file", same),
        )
    }

    @Test
    fun `嵌套对象也归一_值逐字保留`() {
        val a = args("opts" to args("b" to 2, "a" to 1), "s" to " 保留空格 ")
        val b = args("s" to " 保留空格 ", "opts" to args("a" to 1, "b" to 2))
        assertEquals(ConfirmKeys.of("t", a), ConfirmKeys.of("t", b))
        val c = args("opts" to args("a" to 1, "b" to 2), "s" to " 保留空格")
        assertTrue("字符串值逐字保留（trim 掉就是改请求）", ConfirmKeys.of("t", a) != ConfirmKeys.of("t", c))
    }

    // ── 风险分类：高风险永远只给「仅这一次」 ──────────────────────────────

    @Test
    fun `写盘删除扣费类_高风险_只给仅这一次`() {
        val highRisk = listOf(
            "write_file", "edit_file", "delete_path", "move_path", "convert_encoding",
            "image_ops", "library_organize", "card_export", "card_build",
            "draw_chart", "draw_table", "render_artifact", "kb_index",
            "novel_create", "novel_project", "novel_continue",
            "generate_image", "bash",
        )
        highRisk.forEach { tool ->
            assertTrue("$tool 应按高风险算", ConfirmRisk.isHighRisk(tool))
            assertEquals(
                "$tool 只该拿到「仅这一次」",
                listOf(ConfirmScope.ONLY_ONCE),
                ConfirmRisk.scopesFor(tool),
            )
        }
    }

    @Test
    fun `网页操作类_可范围授权_四档齐全`() {
        listOf(
            "browser_open", "browser_click", "browser_type", "browser_tap",
            "browser_swipe", "browser_long_press", "browser_key",
        ).forEach { tool ->
            assertFalse("$tool 不该按高风险算", ConfirmRisk.isHighRisk(tool))
            assertEquals(4, ConfirmRisk.scopesFor(tool).size)
        }
    }

    @Test
    fun `未知工具与外部MCP_保守按高风险`() {
        listOf("future_tool", "mcp_weather_get", "totally_unknown").forEach { tool ->
            assertTrue("$tool 认不出 ⇒ 高风险（保守：漏一个只会更安全）", ConfirmRisk.isHighRisk(tool))
        }
    }

    // ── 账本：授予 / 覆盖 / 撤销 / 寿命 ─────────────────────────────────

    private val KEY = "browser_click|{\"url\":\"https://a\"}"

    @Test
    fun `仅这一次_永不入账_下次照问`() {
        assertFalse("没有授权时不该覆盖", ConfirmGrants.covers(1L, KEY, "browser_click"))
        // 「仅这一次」在 ConfirmFlow 里就不该调 grant；这里直验 covers 的判据：
        // 只认已入账的授权
        assertFalse(ConfirmGrants.covers(1L, KEY, "browser_click"))
    }

    @Test
    fun `本次运行授权_命中免卡且计数`() {
        ConfirmGrants.grant(1L, KEY, "browser_click", ConfirmScope.THIS_RUN)
        assertTrue(ConfirmGrants.covers(1L, KEY, "browser_click"))
        assertTrue(ConfirmGrants.covers(1L, KEY, "browser_click"))
        val used = ConfirmGrants.auditFor(1L).filter { it.decision == ConfirmAuditDecision.USED }
        assertEquals("用了两次 ⇒ 两条 USED", 2, used.size)
        assertEquals("次数要累加在授权上", 2, used.last().useCount)
    }

    @Test
    fun `高风险工具_即便账本有行也不覆盖`() {
        // 构造：直接往账本塞一条写工具的授权（模拟上游漏摁）⇒ covers 必须拒
        ConfirmGrants.grant(1L, "write_file|{\"path\":\"x\"}", "write_file", ConfirmScope.THIS_SESSION)
        assertFalse(
            "纵深防御：高风险永远现场问（负例「高风险被范围授权绕过」第二道）",
            ConfirmGrants.covers(1L, "write_file|{\"path\":\"x\"}", "write_file"),
        )
    }

    @Test
    fun `运行结束_清本次运行与本技能_不清本会话`() {
        ConfirmGrants.grant(1L, KEY, "browser_click", ConfirmScope.THIS_RUN)
        ConfirmGrants.grant(1L, "browser_click|{\"url\":\"b\"}", "browser_click", ConfirmScope.THIS_SKILL)
        ConfirmGrants.grant(1L, "browser_click|{\"url\":\"c\"}", "browser_click", ConfirmScope.THIS_SESSION)

        ConfirmGrants.endRun(1L)

        assertFalse("THIS_RUN 失效", ConfirmGrants.covers(1L, KEY, "browser_click"))
        assertFalse("THIS_SKILL 失效", ConfirmGrants.covers(1L, "browser_click|{\"url\":\"b\"}", "browser_click"))
        assertTrue("THIS_SESSION 跨运行仍有效（157 同口径）", ConfirmGrants.covers(1L, "browser_click|{\"url\":\"c\"}", "browser_click"))
        val ended = ConfirmGrants.auditFor(1L).filter { it.decision == ConfirmAuditDecision.RUN_ENDED }
        assertEquals("两条失效各记一条审计", 2, ended.size)
    }

    @Test
    fun `会话结束_全清_换会话不继承`() {
        ConfirmGrants.grant(1L, KEY, "browser_click", ConfirmScope.THIS_SESSION)
        ConfirmGrants.endSession(1L)
        assertFalse("本会话失效", ConfirmGrants.covers(1L, KEY, "browser_click"))
        assertFalse("新会话没有继承（结构性：账本按 sessionId 分）", ConfirmGrants.covers(2L, KEY, "browser_click"))
    }

    @Test
    fun `撤销授权后_不再覆盖`() {
        ConfirmGrants.grant(1L, KEY, "browser_click", ConfirmScope.THIS_SESSION)
        assertTrue(ConfirmGrants.covers(1L, KEY, "browser_click"))
        assertEquals(1, ConfirmGrants.revoke(1L, KEY))
        assertFalse("撤销后不再覆盖", ConfirmGrants.covers(1L, KEY, "browser_click"))
        assertTrue(
            "撤销留痕",
            ConfirmGrants.auditFor(1L).any { it.decision == ConfirmAuditDecision.REVOKED },
        )
    }

    @Test
    fun `审计_记谁授权_范围_次数`() {
        ConfirmGrants.grant(7L, KEY, "browser_click", ConfirmScope.THIS_SESSION)
        ConfirmGrants.covers(7L, KEY, "browser_click")
        val audit = ConfirmGrants.auditFor(7L)
        val granted = audit.single { it.decision == ConfirmAuditDecision.GRANTED }
        assertEquals("谁授权的：会话", 7L, granted.sessionId)
        assertEquals("哪个工具", "browser_click", granted.tool)
        assertEquals("范围", ConfirmScope.THIS_SESSION, granted.scope)
        assertTrue("时间戳", granted.at > 0L)
        val used = audit.single { it.decision == ConfirmAuditDecision.USED }
        assertEquals("次数", 1, used.useCount)
    }

    @Test
    fun `同一授权重复批_同范围只更新时间戳_不换 scope`() {
        ConfirmGrants.grant(1L, KEY, "browser_click", ConfirmScope.THIS_SESSION)
        ConfirmGrants.grant(1L, KEY, "browser_click", ConfirmScope.THIS_SESSION)
        val active = ConfirmGrants.activeGrants(1L)
        assertEquals("同一 key 一条授权（不许叠）", 1, active.size)
        assertEquals(ConfirmScope.THIS_SESSION, active.single().scope)
    }

    // ── 旁路：UI → 内核的作用域传递 ─────────────────────────────────────

    @Test
    fun `旁路_记了就能取_取一次即删`() {
        ConfirmScopeChoice.record("call-1", ConfirmScope.THIS_RUN)
        assertEquals(ConfirmScope.THIS_RUN, ConfirmScopeChoice.consume("call-1"))
        assertEquals("消费即删（不会跨卡串味）", null, ConfirmScopeChoice.consume("call-1"))
        assertEquals("没记过 ⇒ null = 仅这一次", null, ConfirmScopeChoice.consume("call-2"))
    }

    @Test
    fun `旁路_按callId各管各_不串卡`() {
        ConfirmScopeChoice.record("call-a", ConfirmScope.THIS_RUN)
        ConfirmScopeChoice.record("call-b", ConfirmScope.THIS_SKILL)
        assertEquals(ConfirmScope.THIS_SKILL, ConfirmScopeChoice.consume("call-b"))
        assertEquals(ConfirmScope.THIS_RUN, ConfirmScopeChoice.consume("call-a"))
    }

    @Test
    fun `scope键_稳定可回读`() {
        ConfirmScope.entries.forEach { s ->
            assertEquals("键回读要稳（审计行解析用）", s, ConfirmScope.fromKey(s.key))
        }
        assertEquals("认不出的键按仅这一次（保守）", ConfirmScope.ONLY_ONCE, ConfirmScope.fromKey("???"))
    }
}
