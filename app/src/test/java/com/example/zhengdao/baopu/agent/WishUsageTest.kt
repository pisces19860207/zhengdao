// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百二十五批 · 灰度「真实使用」受理层的判据靶：
//   ① 幂等：同一来源同一归属键，第二次不增（只落一行"已算过"的痕）；跨重启也认账上凭据；
//   ② 手动不计：manual=true 只留痕，uses 不动；
//   ③ 三类来源都能计：工具命中／卡片采纳／内容进台账；
//   ④ 每次一行：每一个受理事件落一行账（含被挡的）；被挡的那些不算凭据；
//   ⑤ 无主／未装配：只留痕不计数，不许把 uses 顶上去了却没人认领。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.wish.RehearsalReading
import com.example.zhengdao.baopu.wish.SelfCheckVerdict
import com.example.zhengdao.baopu.wish.WishLedger
import com.example.zhengdao.baopu.wish.WishOutcome
import com.example.zhengdao.baopu.wish.WishPipeline
import com.example.zhengdao.baopu.wish.WishPorts
import com.example.zhengdao.baopu.wish.WishProduct
import com.example.zhengdao.baopu.wish.WishUsageLedger
import com.example.zhengdao.baopu.wish.WishUseEvent
import com.example.zhengdao.baopu.wish.WishUseSource
import com.example.zhengdao.baopu.wish.WishUseVerdict
import com.example.zhengdao.baopu.wish.WishWiring
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class WishUsageTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = File.createTempFile("zd-w525", "").let { it.delete(); it.mkdirs(); it }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun ports() = WishPorts(
        generateBody = { "安全的技能正文：把 md 合并成一本" },
        writeDraft = { _, _ -> true },
        runRehearsal = { RehearsalReading(ok = true, realUntouched = true, note = "全通") },
        collectCheck = { SelfCheckVerdict.Pass("机械判定通过") },
        doPromote = { true },
        doRollback = { _, _ -> true },
    )

    private fun pipeline() = WishPipeline(root, ports()) { 1_000_000L }

    /** 在**同一条**流水线上起一条灰度许愿（首进 uses=1），返回 (wishId, 技能名)。 */
    private fun grayWish(p: WishPipeline, text: String, product: WishProduct = WishProduct.SKILL): Pair<String, String> {
        val out = p.submit(text, product)
        val wishId = (out as WishOutcome.AwaitingConfirmation).wishId
        p.confirm(wishId, out.spec.candidates.map { it.id })
        val name = WishWiring.skillName(text) ?: text
        return wishId to name
    }

    private fun lines(): Int = WishUsageLedger.readAll(root).size

    // ── ① 幂等 ──────────────────────────────────────────────────────────

    @Test
    fun `判据①_同一事件只算一次`() {
        val p = pipeline()
        val (wishId, name) = grayWish(p, "把工作区里的 .md 合并成一本")
        val event = WishUseEvent(WishUseSource.TOOL_HIT, name)

        val first = WishUsageWiring.recordInto(root, p, event)
        val second = WishUsageWiring.recordInto(root, p, event)

        assertEquals("第一次要算", WishUseVerdict.COUNTED, first.verdict)
        assertTrue(first.counted)
        assertEquals(2, first.uses)
        assertEquals("第二次是同一件事 ⇒ 挡住", WishUseVerdict.DUPLICATE, second.verdict)
        assertFalse(second.counted)
        assertEquals("uses 只许涨一次", 2, p.usesOf(wishId))
        assertEquals("两次受理两行账（被挡的也留痕）", 2, lines())
        assertEquals("只有 COUNTED 那行是凭据", 1, WishUsageLedger.countedKeys(root).size)
    }

    @Test
    fun `判据①_幂等跨重启（账上有凭据就不重算）`() {
        val p1 = pipeline()
        val (_, name) = grayWish(p1, "把工作区里的 .md 合并成一本")
        WishUsageWiring.recordInto(root, p1, WishUseEvent(WishUseSource.TOOL_HIT, name))

        // 进程重启：新流水线、同一工作区、同一灰度许愿（账上已有 COUNTED 凭据）
        val p2 = pipeline()
        val (wishId2, _) = grayWish(p2, "把工作区里的 .md 合并成一本")
        val again = WishUsageWiring.recordInto(root, p2, WishUseEvent(WishUseSource.TOOL_HIT, name))

        assertEquals("账上是凭据 ⇒ 重启后不重算", WishUseVerdict.DUPLICATE, again.verdict)
        assertEquals("新流水线的读数没被动", 1, p2.usesOf(wishId2))
    }

    // ── ② 手动不计 ──────────────────────────────────────────────────────

    @Test
    fun `判据②_手动补记不计真实`() {
        val p = pipeline()
        val (wishId, _) = grayWish(p, "把工作区里的 .md 合并成一本")
        val out = WishUsageWiring.recordInto(
            root, p, WishUseEvent(WishUseSource.TOOL_HIT, wishId, wishId = wishId, manual = true, detail = "我手点了一下"),
        )

        assertEquals(WishUseVerdict.MANUAL, out.verdict)
        assertFalse("手动不算真实使用", out.counted)
        assertEquals("uses 一动不动", 1, p.usesOf(wishId))
        val line = WishUsageLedger.readAll(root).single()
        assertTrue("账上要写明这是手动的", line.manual)
        assertEquals(WishUseVerdict.MANUAL, line.verdict)
        assertEquals("手动事件不进凭据", 0, WishUsageLedger.countedKeys(root).size)
    }

    // ── ③ 三类来源 ──────────────────────────────────────────────────────

    @Test
    fun `判据③_三类真实来源各算一次`() {
        val p = pipeline()
        val (wishTool, nameTool) = grayWish(p, "把工作区里的 .md 合并成一本")
        val (wishCard, nameCard) = grayWish(p, "把长文拆成三条微头条", WishProduct.CARD)
        val (wishLedger, nameLedger) = grayWish(p, "给我一套写周报的流程", WishProduct.CONTENT)

        val tool = WishUsageWiring.recordInto(root, p, WishUseEvent(WishUseSource.TOOL_HIT, nameTool, detail = "工具跑成"))
        val card = WishUsageWiring.recordInto(root, p, WishUseEvent(WishUseSource.CARD_ADOPTED, nameCard, detail = "采纳"))
        val ledger = WishUsageWiring.recordInto(
            root, p,
            WishUseEvent(WishUseSource.CONTENT_LEDGER, "20260101_许愿_1_${nameLedger}.md", detail = "进台账"),
        )

        assertEquals(WishUseVerdict.COUNTED, tool.verdict)
        assertEquals(WishUseVerdict.COUNTED, card.verdict)
        assertEquals("台账文件名含技能名 ⇒ 也认领得上", WishUseVerdict.COUNTED, ledger.verdict)
        assertEquals(wishTool, tool.wishId)
        assertEquals(wishCard, card.wishId)
        assertEquals(wishLedger, ledger.wishId)
        assertEquals(
            "三条许愿各自涨一次",
            listOf(2, 2, 2),
            listOf(p.usesOf(wishTool), p.usesOf(wishCard), p.usesOf(wishLedger)),
        )
        assertEquals("三次受理三行账", 3, lines())
    }

    @Test
    fun `判据③_同来源不同归属键分别算`() {
        val p = pipeline()
        val (wishId, name) = grayWish(p, "把工作区里的 .md 合并成一本")
        WishUsageWiring.recordInto(root, p, WishUseEvent(WishUseSource.TOOL_HIT, name))
        WishUsageWiring.recordInto(root, p, WishUseEvent(WishUseSource.CARD_ADOPTED, name))

        assertEquals("同一条许愿被两种方式用了 ⇒ 算两次", 3, p.usesOf(wishId))
    }

    // ── ④ 每次一行 ──────────────────────────────────────────────────────

    @Test
    fun `判据④_每次受理落一行账（含被挡的）`() {
        val p = pipeline()
        val (wishId, name) = grayWish(p, "把工作区里的 .md 合并成一本")
        WishUsageWiring.recordInto(root, p, WishUseEvent(WishUseSource.TOOL_HIT, name))
        WishUsageWiring.recordInto(root, p, WishUseEvent(WishUseSource.TOOL_HIT, name))
        WishUsageWiring.recordInto(root, p, WishUseEvent(WishUseSource.TOOL_HIT, wishId, wishId = wishId, manual = true))
        WishUsageWiring.recordInto(root, p, WishUseEvent(WishUseSource.TOOL_HIT, "不存在的技能"))
        // 未装配那条用一个**没算过**的键：否则先被幂等挡住，看不出"没流水线"这一态
        WishUsageWiring.recordInto(root, null, WishUseEvent(WishUseSource.TOOL_HIT, "另一个技能"))

        val all = WishUsageLedger.readAll(root)
        assertEquals("五次受理五行账", 5, all.size)
        assertEquals(
            "账上要能一眼看出每次为什么算/不算",
            listOf(
                WishUseVerdict.COUNTED,
                WishUseVerdict.DUPLICATE,
                WishUseVerdict.MANUAL,
                WishUseVerdict.UNOWNED,
                WishUseVerdict.UNWIRED,
            ),
            all.map { it.verdict },
        )
        assertEquals("五条里只有一条真算了", 1, all.count { it.verdict == WishUseVerdict.COUNTED })
    }

    // ── ⑤ 无主 / 未装配 ─────────────────────────────────────────────────

    @Test
    fun `判据⑤_归属对不上只留痕不计数`() {
        val p = pipeline()
        val (wishId, _) = grayWish(p, "把工作区里的 .md 合并成一本")
        val out = WishUsageWiring.recordInto(root, p, WishUseEvent(WishUseSource.TOOL_HIT, "别的技能"))

        assertEquals(WishUseVerdict.UNOWNED, out.verdict)
        assertFalse(out.counted)
        assertEquals(1, p.usesOf(wishId))
    }

    @Test
    fun `判据⑤_流水线未装配只留痕不计数`() {
        val out = WishUsageWiring.recordInto(root, null, WishUseEvent(WishUseSource.TOOL_HIT, "任何技能"))

        assertEquals(WishUseVerdict.UNWIRED, out.verdict)
        assertFalse(out.counted)
        assertEquals(1, lines())
        assertEquals(0, WishUsageLedger.countedKeys(root).size)
    }

    /** 流水线账（`_流水线.jsonl`）与本账各记各的：本批不往流水线账里塞使用行。 */
    @Test
    fun `使用账与流水线账分开`() {
        val p = pipeline()
        val (_, name) = grayWish(p, "把工作区里的 .md 合并成一本")
        val before = WishLedger.readAll(root).size
        WishUsageWiring.recordInto(root, p, WishUseEvent(WishUseSource.TOOL_HIT, name))

        assertEquals("流水线账一行没多", before, WishLedger.readAll(root).size)
        assertEquals("使用账多一行", 1, lines())
        assertTrue("使用账落在许愿目录里", WishUsageLedger.fileIn(root).isFile)
    }
}
