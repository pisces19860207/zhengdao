// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百零五批 · 475 判据② 的牙齿：**「不许把『不确定』当越界」**。
//
// 这一条只有读判定表才验得得到——运行时"含糊"和"越界"看起来下场可以一样（都停手），
// 区别在**停之后要不要递升级件**：越界 ⇒ 递（人的答复决定下一步），含糊 ⇒ 不递。
// 后者由两道形状保证：① [BoundaryVerdict.UNCERTAIN] 与 [BoundaryVerdict.OUT_OF_SCOPE] 是两个值；
// ② [EscalationTrigger] 是闭集、没有 UNCERTAIN 那一格 ⇒ 说不准的事连升级的资格都没有。
//
// 五维声明（派工单 §二①：可用工具／可写根／预算／时长／允许站点）逐维一张表，
// 每维三种下场各一测：声明了＋事实证到超出 ⇒ 越界；声明了＋事实在内 ⇒ 在界内；
// **没声明 ⇒ 说不准**（这一格最要紧：287 批把"空名单"读成"全禁"的老毛病就是从这儿走漏的）。
package com.example.zhengdao.baopu.escalate

import com.example.zhengdao.baopu.agent.ToolGroupTable
import com.example.zhengdao.baopu.kernel.BaopuKernel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityBoundaryTest {

    /** 五维全声明的靶边界：单独翻某一维才看得出那一维自己的下场。 */
    private fun declared(
        tools: Set<String> = setOf("browser_click"),
        roots: List<String> = listOf("/sdcard/Download/证道"),
        budget: Int = 5,
        durationMs: Long = 1_000L,
        sites: List<String> = listOf("example.com"),
    ) = CapabilityBoundary(
        kind = CapabilityKind.BROWSER,
        tools = tools,
        writableRoots = roots,
        budgetCalls = budget,
        maxDurationMs = durationMs,
        allowedSites = sites,
    )

    /** 各维都明说在界内的一格（越界事实＝没拦；其余取声明值内的读数）。 */
    private fun quiet(tool: String = "browser_click") = BoundaryFacts(
        tool = tool,
        writeTarget = "/sdcard/Download/证道/资料库/甲.md",
        host = "example.com",
        callsUsed = 1,
        elapsedMs = 10L,
        scopeEscapeDenied = false,
    )

    // ── §二①：声明的五个字段每一类都得有地方放 ──────────────────────────────

    @Test
    fun `声明五样各是一格`() {
        val b = declared()
        assertEquals(setOf("browser_click"), b.tools)
        assertTrue(b.declaresRoots)
        assertTrue(b.declaresBudget)
        assertTrue(b.declaresDuration)
        assertTrue(b.declaresSites)
        assertEquals(BoundaryVerdict.IN_SCOPE, BoundaryRules.verdictOf(b, quiet()))
    }

    @Test
    fun `空名单是没声明不是全禁也不是已越界`() {
        // 这一格是判据②的根：287 之后"工作区不是权限边界"，声明表里没有根名单不等于什么都不许。
        val b = declared(roots = emptyList(), sites = emptyList(), budget = 0, durationMs = 0L)
        assertFalse(b.declaresRoots)
        assertFalse(b.declaresSites)
        assertFalse(b.declaresBudget)
        assertFalse(b.declaresDuration)
        val facts = quiet().copy(
            host = "not-trusted.com",
            writeTarget = "/anywhere/else.md",
            callsUsed = 99,
            elapsedMs = 9_000L,
        )
        assertEquals("四维都没声明 ⇒ 只能说说不准，不许说越界", BoundaryVerdict.UNCERTAIN, BoundaryRules.verdictOf(b, facts))
    }

    @Test
    fun `未归类的OTHER没有声明表`() {
        assertNull("映射不上的工具 ⇒ null 声明 ⇒ 判出来只能是不确定（不猜边界）", CapabilityBoundaries.of(CapabilityKind.OTHER))
        assertFalse(CapabilityBoundaries.declares(CapabilityKind.OTHER))
        assertEquals(
            BoundaryVerdict.UNCERTAIN,
            BoundaryRules.verdictOf(CapabilityBoundaries.of(CapabilityKind.OTHER), quiet("mystery_tool")),
        )
    }

    @Test
    fun `声明表里的数都来自既有事实`() {
        // 预算继承内核整轮步数（不是新阈值）；组员名单来自分组表（不另立工具清单）。
        val browser = CapabilityBoundaries.of(CapabilityKind.BROWSER)!!
        assertEquals(BaopuKernel.Budget.MAX_STEPS, browser.budgetCalls)
        assertTrue("网页类在分组表里有组员：${browser.tools.size}", browser.tools.isNotEmpty())
        assertTrue(
            "名单里每一项在分组表里都属 browser 组",
            browser.tools.all { ToolGroupTable.GROUP_OF[it]?.key == CapabilityKind.BROWSER_GROUP },
        )
        val write = CapabilityBoundaries.of(CapabilityKind.FILE_WRITE)!!
        val writeGroup = ToolGroupTable.GROUP_OF["write_file"]?.key
        assertTrue("write_file 属文件类组员：$writeGroup", write.tools.contains("write_file"))
        val read = CapabilityBoundaries.of(CapabilityKind.FILE_READ)!!
        val fileGroupKeys = CapabilityKind.FILE_GROUPS
        assertTrue("名单里每一项都属文件类组", read.tools.all { (ToolGroupTable.GROUP_OF[it]?.key ?: "") in fileGroupKeys })
        assertEquals(
            "读写两类共用同一份组员名单：这一维只答「这类管不管这个工具」，读写切分在 ofTool 那一格已经做过" +
                "（拿 ToolFallback.WRITE_TOOLS 之类另切一刀＝第二把尺，这里钉住不许）",
            write.tools,
            read.tools,
        )
        assertTrue(CapabilityBoundaries.of(CapabilityKind.NETWORK)!!.tools.isNotEmpty())
        assertTrue(CapabilityBoundaries.of(CapabilityKind.MODEL)!!.tools.isNotEmpty())
    }

    @Test
    fun `能力类映射唯一口径`() {
        // 网页组 ⇒ BROWSER；联网组 ⇒ NETWORK；文件类组按工具自己的 readOnly 分读写；其余 ⇒ OTHER。
        assertEquals(CapabilityKind.BROWSER, CapabilityKind.ofTool(CapabilityKind.BROWSER_GROUP, false))
        assertEquals(CapabilityKind.NETWORK, CapabilityKind.ofTool(CapabilityKind.WEBSEARCH_GROUP, true))
        assertEquals(CapabilityKind.FILE_READ, CapabilityKind.ofTool("core", true))
        assertEquals(CapabilityKind.FILE_WRITE, CapabilityKind.ofTool("edit", false))
        assertEquals(CapabilityKind.OTHER, CapabilityKind.ofTool("schedule", false))
        assertEquals(CapabilityKind.OTHER, CapabilityKind.ofTool(null, false))
        assertEquals("OTHER 也在闭集里有一格（没归类是事实，不是第三种判决）", 6, CapabilityKind.entries.size)
    }

    // ── 判据②：含糊一律落 UNCERTAIN ─────────────────────────────────────────

    @Test
    fun `没采到越界事实这一维说不准`() {
        // 采集方没这条事实（null）≠「没拦」——不许拿"不知道"顶一个下场。
        assertEquals(
            BoundaryVerdict.UNCERTAIN,
            BoundaryRules.verdictOf(declared(), quiet().copy(scopeEscapeDenied = null)),
        )
    }

    @Test
    fun `站点维_名单没声明就说不准`() {
        assertEquals(
            BoundaryVerdict.UNCERTAIN,
            BoundaryRules.verdictOf(declared(sites = emptyList()), quiet().copy(host = "example.com")),
        )
    }

    @Test
    fun `根维_名单没声明就说不准`() {
        assertEquals(
            BoundaryVerdict.UNCERTAIN,
            BoundaryRules.verdictOf(declared(roots = emptyList()), quiet()),
        )
    }

    @Test
    fun `时长维_没量到不拿0冒充只用了一瞬`() {
        assertEquals(BoundaryVerdict.UNCERTAIN, BoundaryRules.verdictOf(declared(), quiet().copy(elapsedMs = -1L)))
        assertEquals(BoundaryVerdict.IN_SCOPE, BoundaryRules.verdictOf(declared(), quiet().copy(elapsedMs = 0L)))
    }

    @Test
    fun `拦下了但不是越界_明说在界内`() {
        // 「参数没给」是拒，「路径在边界外」才是越界——把前者当后者就是派工单禁的那件事。
        val b = declared(tools = setOf("read_file"))
        assertEquals(
            BoundaryVerdict.IN_SCOPE,
            BoundaryRules.verdictOf(
                b,
                quiet("read_file").copy(writeTarget = null, scopeEscapeDenied = false, deniedButNotScope = true),
            ),
        )
        assertEquals(
            "没声明的那一类只能说『判不出来』——既不升级成越界，也不假装在界内",
            BoundaryVerdict.UNCERTAIN,
            BoundaryRules.verdictOf(null, BoundaryFacts(tool = "some_tool", deniedButNotScope = true)),
        )
    }

    @Test
    fun `不确定这一格在升级触发里没有口`() {
        assertEquals(
            "越界与轨迹达阈之外不许有第三种升级",
            setOf("out_of_scope", "trace_over_limit"),
            EscalationTrigger.entries.map { it.wire }.toSet(),
        )
        assertNull("『不确定』不是触发词（构造不出来的升级才是这条判据的硬证明）", EscalationTrigger.ofWire("uncertain"))
        assertNull(EscalationTrigger.ofWire(BoundaryVerdict.UNCERTAIN.wire))
    }

    // ── 判据②的另一半：证到超出已声明的边界 ⇒ 就是越界 ──────────────────────

    @Test
    fun `站点维_不在名单内越界`() {
        val b = declared()
        assertEquals(BoundaryVerdict.IN_SCOPE, BoundaryRules.verdictOf(b, quiet().copy(host = "a.example.com")))
        assertEquals(BoundaryVerdict.OUT_OF_SCOPE, BoundaryRules.verdictOf(b, quiet().copy(host = "evil.com")))
    }

    @Test
    fun `预算维_用超越界`() {
        val b = declared()
        assertEquals(BoundaryVerdict.IN_SCOPE, BoundaryRules.verdictOf(b, quiet().copy(callsUsed = 5)))
        assertEquals(BoundaryVerdict.OUT_OF_SCOPE, BoundaryRules.verdictOf(b, quiet().copy(callsUsed = 6)))
    }

    @Test
    fun `时长维_用超越界`() {
        val b = declared()
        assertEquals(BoundaryVerdict.OUT_OF_SCOPE, BoundaryRules.verdictOf(b, quiet().copy(elapsedMs = 1_001L)))
    }

    @Test
    fun `根维_写目标在声明根外越界`() {
        val b = declared()
        val v = BoundaryRules.verdictOf(b, quiet().copy(writeTarget = "/sdcard/Documents/乙.md"))
        assertEquals(BoundaryVerdict.OUT_OF_SCOPE, v)
    }

    @Test
    fun `根比较按组件_同前缀兄弟目录过不去`() {
        // 字符串前缀会把 `/sdcard/Download/证道-其他` 当成在 `/sdcard/Download/证道` 里。
        assertFalse(BoundaryRules.isInsideRoot("/sdcard/Download/证道", "/sdcard/Download/证道-其他/x.md"))
        assertTrue(BoundaryRules.isInsideRoot("/sdcard/Download/证道", "/sdcard/Download/证道/x.md"))
        assertTrue(BoundaryRules.isInsideRoot("/sdcard/Download/证道/", "/sdcard/Download/证道/x.md"))
        assertTrue(BoundaryRules.isInsideRoot("\\sdcard\\Download\\证道", "/sdcard/Download/证道/x.md"))
        assertFalse("空根串不参与比较", BoundaryRules.isInsideRoot("", "/sdcard/Download/证道/x.md"))
    }

    @Test
    fun `第②段硬事实没有声明表也照判越界`() {
        // 判据③「越界 ⇒ 发升级」不能因为声明表少一格就哑掉。
        assertEquals(
            BoundaryVerdict.OUT_OF_SCOPE,
            BoundaryRules.verdictOf(null, BoundaryFacts(tool = "any_tool", scopeEscapeDenied = true)),
        )
        val escaped = quiet().copy(scopeEscapeDenied = true, writeTarget = "/elsewhere/x.md")
        assertEquals(
            BoundaryVerdict.OUT_OF_SCOPE,
            BoundaryRules.verdictOf(declared(), escaped),
        )
    }

    @Test
    fun `多维合成_有一维越界就压过含糊`() {
        // 顺序不许倒：反过来就变成"有一维不知道 ⇒ 全体不知道"，真越界会被含糊淹掉。
        val b = declared()
        val outOverUncertain = quiet().copy(host = "evil.com", elapsedMs = -1L)
        assertEquals("站点维证到越界，时长维的含糊淹不掉它", BoundaryVerdict.OUT_OF_SCOPE, BoundaryRules.verdictOf(b, outOverUncertain))
        val onlyUncertain = quiet().copy(elapsedMs = -1L)
        assertEquals("没有越界、但有说不准 ⇒ 说不准（不递升级）", BoundaryVerdict.UNCERTAIN, BoundaryRules.verdictOf(b, onlyUncertain))
    }
}
