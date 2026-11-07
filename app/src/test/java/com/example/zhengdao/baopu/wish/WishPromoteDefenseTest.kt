// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百二十六批 · 转正/回滚的**失败防御**判据靶（派工单：`WishPipeline.kt:127-134` 实核
// `ports.doPromote(ctx)` 的 Boolean 被忽略 ⇒ 写盘失败仍报成功）。
//   ① 转正写盘失败 ⇒ 不落 PROMOTED、落 PROMOTE_FAILED、保持灰度、返回失败态；
//   ② 转正写成 ⇒ 照旧 PROMOTED ＋ Promoted；
//   ③ 回滚写盘失败 ⇒ 落 ROLLBACK_FAILED（不是 ROLLED_BACK）、阶段钉在失败态、返回需人工的失败态；
//   ④ 回滚写成 ⇒ 照旧 ROLLED_BACK。
package com.example.zhengdao.baopu.wish

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class WishPromoteDefenseTest {

    private lateinit var root: File
    private var promoteOk = true
    private var rollbackOk = true
    private var promoteCalls = 0
    private var rollbackCalls = 0

    @Before
    fun setUp() {
        root = File.createTempFile("zd-w526", "").let { it.delete(); it.mkdirs(); it }
        promoteOk = true
        rollbackOk = true
        promoteCalls = 0
        rollbackCalls = 0
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun ports(checkPassed: Boolean = true) = WishPorts(
        generateBody = { "安全的技能正文：把 md 合并成一本" },
        writeDraft = { _, _ -> true },
        runRehearsal = { RehearsalReading(ok = true, realUntouched = true, note = "全通") },
        collectCheck = { if (checkPassed) SelfCheckVerdict.Pass("机械判定通过") else SelfCheckVerdict.Fail("自检不达标") },
        doPromote = { promoteCalls++; promoteOk },
        doRollback = { _, _ -> rollbackCalls++; rollbackOk },
    )

    private fun pipeline(checkPassed: Boolean = true) = WishPipeline(root, ports(checkPassed)) { 1_000_000L }

    private fun submitAndConfirm(p: WishPipeline): String {
        val out = p.submit("把工作区里的 .md 合并成一本", WishProduct.SKILL)
        val wishId = (out as WishOutcome.AwaitingConfirmation).wishId
        p.confirm(wishId, out.spec.candidates.map { it.id })
        return wishId
    }

    private fun stagesOf(wishId: String): List<WishStage> =
        WishLedger.readAll(root).filter { it.wishId == wishId }.map { it.stage }

    /** 推到第 3 次使用（达阈值 ⇒ 触发转正）。灰度首进 uses=1，所以再记两次。 */
    private fun useToThreshold(p: WishPipeline, wishId: String): WishOutcome {
        p.recordUse(wishId)
        return p.recordUse(wishId)
    }

    @Test
    fun `判据1_转正写盘失败_不落 PROMOTED 且保持灰度`() {
        promoteOk = false
        val p = pipeline()
        val wishId = submitAndConfirm(p)
        assertEquals("首进灰度 uses=1", 1, p.usesOf(wishId))

        val third = useToThreshold(p, wishId)
        assertTrue("要返回失败态，实际：$third", third is WishOutcome.PromoteFailed)
        assertEquals("转正确实被调了一次（不是没试）", 1, promoteCalls)

        val stages = stagesOf(wishId)
        assertFalse("写盘失败不许记 PROMOTED，实际：$stages", stages.contains(WishStage.PROMOTED))
        assertTrue("要落一行失败账，实际：$stages", stages.contains(WishStage.PROMOTE_FAILED))
        assertEquals("阶段保持灰度（不倒退到更早的态）", WishStage.GRAY, p.contextOf(wishId)!!.stage)
        assertEquals("读数不倒退：uses 仍是 3", 3, p.usesOf(wishId))
        assertEquals("盘上还是灰度 ⇒ 还能被认领", wishId, p.grayWishFor(wishId))
    }

    @Test
    fun `判据2_转正写成_照旧报已转正`() {
        val p = pipeline()
        val wishId = submitAndConfirm(p)
        val third = useToThreshold(p, wishId)

        assertTrue("要返回已转正，实际：$third", third is WishOutcome.Promoted)
        val stages = stagesOf(wishId)
        assertTrue("要落 PROMOTED，实际：$stages", stages.contains(WishStage.PROMOTED))
        assertFalse("写成时不该有失败账", stages.contains(WishStage.PROMOTE_FAILED))
        assertEquals(WishStage.PROMOTED, p.contextOf(wishId)!!.stage)
        assertEquals("转正后不再被灰度认领", null, p.grayWishFor(wishId))
    }

    @Test
    fun `判据3_回滚写盘失败_升级需人工且不落 ROLLED_BACK`() {
        rollbackOk = false
        val p = pipeline(checkPassed = false)
        val wishId = submitAndConfirm(p)

        val stages = stagesOf(wishId)
        assertFalse("回滚没成不许记 ROLLED_BACK，实际：$stages", stages.contains(WishStage.ROLLED_BACK))
        assertTrue("要落 ROLLBACK_FAILED，实际：$stages", stages.contains(WishStage.ROLLBACK_FAILED))
        assertEquals("回滚确实被调过一次", 1, rollbackCalls)
        assertEquals("阶段钉在失败态", WishStage.ROLLBACK_FAILED, p.contextOf(wishId)!!.stage)

        // 出口类型带 needsHuman（不许被当"已回滚"收场）
        val again = p.recordUse(wishId)
        assertTrue("失败态之后不许冒出一个'已回滚'：$again", again !is WishOutcome.RolledBack)
    }

    @Test
    fun `判据4_回滚写成_照旧报已回滚`() {
        val p = pipeline(checkPassed = false)
        val wishId = submitAndConfirm(p)

        val stages = stagesOf(wishId)
        assertTrue("要落 ROLLED_BACK，实际：$stages", stages.contains(WishStage.ROLLED_BACK))
        assertFalse("写成时不该有失败账", stages.contains(WishStage.ROLLBACK_FAILED))
        assertEquals(WishStage.ROLLED_BACK, p.contextOf(wishId)!!.stage)
    }
}
