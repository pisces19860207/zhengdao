// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百二十五批（追加）· 「内容进台账」这条腿接上之后的判据靶：
//   ③-1 转正写台账 **不算**一次使用（转正不冒充使用，账上 0 行）；
//   ③-2 同一篇内容连记两次台账 ⇒ 只算一次（幂等按内容、不按文件名），且**不导致转正**；
//   ③-3 已转正的许愿再记台账 ⇒ 不再计（灰度期之外不认领）；
//   ② 接线只在 UI 动作处（BaopuLongformCard），**不在** store 层（BaopuLongformStore）与转正口（WishIOWiring）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.wish.RehearsalReading
import com.example.zhengdao.baopu.wish.SelfCheckVerdict
import com.example.zhengdao.baopu.wish.WishOutcome
import com.example.zhengdao.baopu.wish.WishPipeline
import com.example.zhengdao.baopu.wish.WishPorts
import com.example.zhengdao.baopu.wish.WishProduct
import com.example.zhengdao.baopu.wish.WishUsageLedger
import com.example.zhengdao.baopu.wish.WishUseEvent
import com.example.zhengdao.baopu.wish.WishUseSource
import com.example.zhengdao.baopu.wish.WishUseVerdict
import com.example.zhengdao.baopu.wish.WishWiring
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WishContentLedgerTest {

    private lateinit var root: File
    private var promoteCalls = 0

    @Before
    fun setUp() {
        root = File.createTempFile("zd-w525c", "").let { it.delete(); it.mkdirs(); it }
        promoteCalls = 0
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun ports() = WishPorts(
        generateBody = { "安全的正文：把想法写成三条微头条" },
        writeDraft = { _, _ -> true },
        runRehearsal = { RehearsalReading(ok = true, realUntouched = true, note = "全通") },
        collectCheck = { SelfCheckVerdict.Pass("机械判定通过") },
        doPromote = { promoteCalls++; true },
        doRollback = { _, _ -> true },
    )

    private fun pipeline() = WishPipeline(root, ports()) { 1_000_000L }

    private fun grayWish(p: WishPipeline, text: String, product: WishProduct): Pair<String, String> {
        val out = p.submit(text, product)
        val wishId = (out as WishOutcome.AwaitingConfirmation).wishId
        p.confirm(wishId, out.spec.candidates.map { it.id })
        return wishId to (WishWiring.skillName(text) ?: text)
    }

    /** 模拟 UI 那一下：同一篇稿的两次「记台账」会给两个文件名（序号+1），内容不变。 */
    private fun ledgerEvent(fileName: String, content: String) = WishUseEvent(
        source = WishUseSource.CONTENT_LEDGER,
        target = fileName,
        detail = "记台账",
        dedupeKey = "content:" + content.hashCode().toString(16),
    )

    // ── ③-1 转正写台账不算一次使用 ───────────────────────────────────────

    @Test
    fun `判据③_转正写台账不算一次使用`() {
        val p = pipeline()
        val (wishId, _) = grayWish(p, "把工作区里的 .md 合并成一本", WishProduct.SKILL)

        p.recordUse(wishId)
        val promoted = p.recordUse(wishId)

        assertTrue("三次使用要转正：$promoted", promoted is WishOutcome.Promoted)
        assertEquals("转正确实发生了一次", 1, promoteCalls)
        assertEquals("转正不冒充使用：使用账 0 行", 0, WishUsageLedger.readAll(root).size)
        assertEquals("uses 就是三次使用本身", 3, p.usesOf(wishId))
    }

    // ── ③-2 同一篇内容连记两次 ⇒ 只算一次、不转正 ─────────────────────────

    @Test
    fun `判据③_同一篇内容连记两次台账只算一次且不转正`() {
        val p = pipeline()
        val (wishId, name) = grayWish(p, "给我一套写周报的流程", WishProduct.CONTENT)
        val content = "标题：许愿稿\n依据：人设第 1 条\n这一篇是同一篇稿。"

        val first = WishUsageWiring.recordInto(root, p, ledgerEvent("20260101_许愿_1_${name}.md", content))
        val second = WishUsageWiring.recordInto(root, p, ledgerEvent("20260101_许愿_2_${name}.md", content))

        assertEquals("第一次记台账要算一次", WishUseVerdict.COUNTED, first.verdict)
        assertEquals("同一篇内容再记一次 ⇒ 挡住（文件名变了也不算新使用）", WishUseVerdict.DUPLICATE, second.verdict)
        assertEquals("uses 只涨一次", 2, p.usesOf(wishId))
        assertEquals("两次都没到阈值 ⇒ 不转正（promote 一次都没被调）", 0, promoteCalls)
        assertTrue("uses 停在 2，离阈值还差一次", second.uses < 3)
        assertEquals("两次受理两行账", 2, WishUsageLedger.readAll(root).size)
    }

    // ── ③-3 已转正后再记台账不计 ─────────────────────────────────────────

    @Test
    fun `判据③_已转正的许愿再记台账不再计`() {
        val p = pipeline()
        val (wishId, name) = grayWish(p, "给我一套写周报的流程", WishProduct.CONTENT)
        p.recordUse(wishId)
        p.recordUse(wishId)
        assertTrue("先到转正", promoteCalls == 1)

        val after = WishUsageWiring.recordInto(root, p, ledgerEvent("20260101_许愿_1_${name}.md", "转正之后再记一次"))

        assertEquals("转正后不认领 ⇒ 只留痕", WishUseVerdict.UNOWNED, after.verdict)
        assertFalse(after.counted)
        assertEquals("uses 不再涨", 3, p.usesOf(wishId))
    }

    // ── ② 接线位置（源级：只许在 UI 动作处）──────────────────────────────

    @Test
    fun `判据②_记台账的接线只在UI动作处_不在store层与转正口`() {
        val card = repoText("src/main/java/com/example/zhengdao/ui/baopu/BaopuLongformCard.kt")
        assertTrue("UI 的「记台账」动作处要真调 onContentLedger", card.contains("WishUsageWiring"))
        assertTrue("要传内容（幂等按内容去重）", card.contains("onContentLedger("))

        val store = repoText("src/main/java/com/example/zhengdao/ui/baopu/BaopuLongformStore.kt")
        assertFalse("store 层不许接（appendLedger 被转正口共用，会把转正误记成使用）", store.contains("onContentLedger"))

        val promotePort = repoText("src/main/java/com/example/zhengdao/baopu/agent/WishIOWiring.kt")
        assertFalse("转正口不许接（转正不冒充使用）", promotePort.contains("onContentLedger"))
    }

    private fun repoText(rel: String): String =
        File(rel).let { if (it.isFile) it else File("app/$rel") }.readText()
}
