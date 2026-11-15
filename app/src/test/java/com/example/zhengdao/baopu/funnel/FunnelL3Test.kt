// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百四十七批（L3 轮）· L3 建议注入与读数的**行为单测**（零模型、零 IO、假时钟）。
//
// 钉本轮判据的行为面：
//   ① 建议只是一句话：≤60 字、单行、无 "name:" 类结构、无"必须"措辞（含超 60 字 fail-closed）；
//   ③ 多意图保原文：并列两件事的原文进主循环上下文一件不少——建议层根本不碰用户文本，
//      这里用真 ContextAssembler 走一遍（同款房型见 ExtraNotesOrderGateTest）；
//   ④ 读数每轮一行、五字段齐；同一输入两次读数一致（纯函数确定性）；
//   ⑤ L2 undecided ⇒ 只用 L1 建议（L1 也没命中就没建议），读数标 L2=undecided
//      （负例：静默用 L2 的组 ⇒ 本文件第 4 例直接红）；
//   信箱（publish/consume）：单次消费、代际覆盖、残值过期丢弃、等待窗口、叫停即弃。
package com.example.zhengdao.baopu.funnel

import com.example.zhengdao.baopu.ContextAssembler
import com.example.zhengdao.baopu.HardFacts
import com.example.zhengdao.baopu.SegmentKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FunnelL3Test {

    private val usedSids = mutableListOf<String>()

    @After
    fun tearDown() {
        FunnelSuggest.nowMs = { System.currentTimeMillis() }
        FunnelSuggest.pause = { ms -> Thread.sleep(ms) }
        usedSids.forEach { FunnelSuggest.clearForTest(it) }
        usedSids.clear()
    }

    /** 假时钟：等待循环不真睡；返回的数组就是可拨的钟。 */
    private fun installFakeClock(startMs: Long = 1_000_000L): LongArray {
        val clock = longArrayOf(startMs)
        FunnelSuggest.nowMs = { clock[0] }
        FunnelSuggest.pause = { ms -> clock[0] += ms }
        return clock
    }

    private fun freshSid(name: String): String {
        val sid = "647-l3-$name"
        usedSids.add(sid)
        return sid
    }

    // ── 判据①：建议只是一句话 ──────────────────────────────────────────────

    @Test
    fun `判据一_各态建议都是单行不超60字无schema无必须`() {
        val cases = listOf(
            FunnelSuggest.build("logistics", null, FunnelSuggest.STATE_SKIP_L1),
            FunnelSuggest.build(FunnelL1Rules.ROUTE_DETERMINISTIC, null, FunnelSuggest.STATE_SKIP_L1),
            FunnelSuggest.build(FunnelL1Rules.ROUTE_MISS, "查询", FunnelSuggest.STATE_HIT),
            FunnelSuggest.build(FunnelL1Rules.ROUTE_MISS, "卡片", FunnelSuggest.STATE_HIT),
        )
        cases.forEach { s ->
            val line = s.line
            assertTrue("有组就该有建议行：${s.groupLabel}", line != null)
            assertTrue("建议行必须单行", line!!.none { c -> c == '\n' })
            assertTrue("建议行必须 ≤60 字（实际 ${line.length}）：$line", line.length <= FunnelSuggest.MAX_LINE_CHARS)
            assertTrue("建议行不许带 name: 类结构", !line.contains("name:") && !line.contains("name ="))
            assertTrue("建议行不许出现 schema 字样", !line.contains("schema"))
            assertTrue("建议行不许说\"必须\"", !line.contains("必须"))
            assertTrue("建议行必须明写仅供参考", line.contains("仅供参考"))
        }
    }

    @Test
    fun `判据一_超60字fail-closed宁可不注入`() {
        val hugeGroup = "组" + "名".repeat(FunnelSuggest.MAX_LINE_CHARS)
        val s = FunnelSuggest.build(hugeGroup, null, FunnelSuggest.STATE_SKIP_L1)
        assertNull("超 60 字必须整行不注入（fail-closed），而不是截半句", s.line)
        assertEquals("组意图照记在读数上", hugeGroup, s.groupLabel)
    }

    // ── 判据⑤：L2 undecided 只用 L1；L1 优先 ───────────────────────────────

    @Test
    fun `判据五_L1命中优先于L2的组`() {
        val s = FunnelSuggest.build("logistics", "查询", FunnelSuggest.STATE_HIT)
        assertEquals("logistics", s.groupLabel)
        assertTrue("建议行必须点名 L1", s.line!!.contains("L1"))
    }

    @Test
    fun `判据五_L2未决时不吃L2的组_L1未命中就没建议`() {
        // 负例正面打：L2 给了组但状态是 undecided ⇒ 静默用 L2 就是这一例红
        val s = FunnelSuggest.build(FunnelL1Rules.ROUTE_MISS, "查询", FunnelSuggest.STATE_UNDECIDED)
        assertNull("L2 undecided 不许注入任何建议行", s.line)
        assertEquals(FunnelSuggest.NO_GROUP, s.groupLabel)
        val line = FunnelSuggest.readingLineOf("7", s, toolCount = 42, segments = 5, costMs = 123L)
        assertTrue("读数必须明标 L2=undecided（判据⑤）", line.contains("L2=undecided"))
        assertTrue("读数建议组必须读「无」", line.contains("建议组=${FunnelSuggest.NO_GROUP}"))
    }

    @Test
    fun `判据五_L2回退与跳过态也不吃L2的组`() {
        listOf(FunnelSuggest.STATE_FALLBACK, FunnelSuggest.STATE_SKIP_L1).forEach { st ->
            val s = FunnelSuggest.build(FunnelL1Rules.ROUTE_MISS, "查询", st)
            assertNull("$st 不许拿 L2 的组当建议", s.line)
        }
    }

    @Test
    fun `判据五_L1未命中且L2命中组时用L2`() {
        val s = FunnelSuggest.build(FunnelL1Rules.ROUTE_MISS, "查询", FunnelSuggest.STATE_HIT)
        assertEquals("查询", s.groupLabel)
        assertTrue(s.line!!.contains("L2 倾向组「查询」"))
    }

    @Test
    fun `确定性执行有自己的措辞`() {
        val s = FunnelSuggest.build(FunnelL1Rules.ROUTE_DETERMINISTIC, null, FunnelSuggest.STATE_SKIP_L1)
        assertTrue(s.line!!.contains("可回放脚本"))
        assertTrue(s.line!!.length <= FunnelSuggest.MAX_LINE_CHARS)
    }

    // ── 判据④：读数一行五字段、确定性 ──────────────────────────────────────

    @Test
    fun `判据四_读数行五字段齐且同输入两次一致`() {
        val s = FunnelSuggest.build("card", null, FunnelSuggest.STATE_SKIP_L1)
        val a = FunnelSuggest.readingLineOf("7", s, toolCount = 42, segments = 5, costMs = 123L)
        val b = FunnelSuggest.readingLineOf("7", s, toolCount = 42, segments = 5, costMs = 123L)
        assertEquals("同一输入两次读数必须逐字一致", a, b)
        for (mark in listOf("建议组=card", "实际装载工具数=42", "注入段数=5", "L2=${FunnelSuggest.STATE_SKIP_L1}", "耗时ms=123")) {
            assertTrue("读数缺字段「$mark」：$a", a.contains(mark))
        }
        assertTrue("读数必须挂 L3 前缀进同一张表", a.startsWith(FunnelSuggest.LOG_PREFIX))
    }

    @Test
    fun `读数_没有信箱时组与状态都读无`() {
        val a = FunnelSuggest.readingLineOf("7", null, toolCount = 9, segments = 3, costMs = 1L)
        assertTrue(a.contains("建议组=无"))
        assertTrue(a.contains("L2=无"))
    }

    // ── 判据③：多意图保原文（建议进上下文不吃掉半句） ──────────────────────

    @Test
    fun `判据三_并列两件事的原文进上下文一件不少`() {
        val multi = "查一下我昨天发的物流，顺便把那张角色卡导出成文件"
        val s = FunnelSuggest.build("logistics", null, FunnelSuggest.STATE_SKIP_L1)
        val prompt = ContextAssembler.assemble(
            persona = emptyList(),
            facts = HardFacts(workspacePath = "/ws", model = "m"),
            stickyMemories = emptyList(),
            newMemories = emptyList(),
            history = emptyList(),
            current = multi,
            images = emptyList(),
            visionSupported = false,
            extraNotes = listOfNotNull(s.line),
        )
        val current = prompt.segments.first { it.kind == SegmentKind.CURRENT }.text
        assertTrue("原文必须逐字进主循环上下文（不许只给被识别出的那半句）", current.contains(multi))
        assertTrue("建议行必须也在本轮上下文里", current.contains(s.line!!))
        assertTrue("建议行不许夹带用户文本", !s.line.contains("物流") && !s.line.contains("角色卡"))
    }

    // ── 信箱：publish/consume 的语义 ────────────────────────────────────────

    @Test
    fun `信箱_消费一次即清_第二次拿到空`() {
        installFakeClock()
        val sid = freshSid("once")
        FunnelSuggest.publish(sid, "card", null, FunnelSuggest.STATE_SKIP_L1)
        val first = FunnelSuggest.consumeForTurn(sid)
        assertEquals("card", first?.groupLabel)
        assertEquals("undecided 之外状态照传", FunnelSuggest.STATE_SKIP_L1, first?.l2State)
        assertNull("同一格不许被消费两次", FunnelSuggest.consumeForTurn(sid))
    }

    @Test
    fun `信箱_覆盖取最新一代`() {
        installFakeClock()
        val sid = freshSid("latest")
        FunnelSuggest.publish(sid, "card", null, FunnelSuggest.STATE_SKIP_L1)
        FunnelSuggest.publish(sid, FunnelL1Rules.ROUTE_MISS, "查询", FunnelSuggest.STATE_HIT)
        assertEquals("查询", FunnelSuggest.consumeForTurn(sid)?.groupLabel)
    }

    @Test
    fun `信箱_上一轮残值过期丢弃_不许当本轮建议`() {
        val clock = installFakeClock()
        val sid = freshSid("stale")
        FunnelSuggest.publish(sid, "card", null, FunnelSuggest.STATE_SKIP_L1)
        clock[0] += 5_000L // 残值比等待窗口还老
        assertNull("残值必须丢弃而不是塞给本轮", FunnelSuggest.consumeForTurn(sid, waitMs = 2_000L))
    }

    @Test
    fun `信箱_等待窗口内落账就消费`() {
        val clock = installFakeClock()
        val sid = freshSid("wait")
        var published = false
        FunnelSuggest.pause = { ms ->
            clock[0] += ms
            if (!published && clock[0] >= 1_000_050L) {
                published = true
                FunnelSuggest.publish(sid, FunnelL1Rules.ROUTE_MISS, "查询", FunnelSuggest.STATE_HIT)
            }
        }
        val s = FunnelSuggest.consumeForTurn(sid, waitMs = 2_000L)
        assertEquals("L2 那一枪在窗口内落账就该等到", "查询", s?.groupLabel)
    }

    @Test
    fun `信箱_窗口到点没落账就放弃_行为与从前一致`() {
        val clock = installFakeClock()
        val sid = freshSid("timeout")
        assertNull(FunnelSuggest.consumeForTurn(sid, waitMs = 2_000L))
        assertEquals("等待必须走满窗口", 2_000L, clock[0] - 1_000_000L)
    }

    @Test
    fun `信箱_用户叫停立刻放弃等待`() {
        val clock = installFakeClock()
        val sid = freshSid("stop")
        assertNull(FunnelSuggest.consumeForTurn(sid, waitMs = 2_000L, stop = { true }))
        assertEquals("叫停不许多等一毫秒", 1_000_000L, clock[0])
    }
}
