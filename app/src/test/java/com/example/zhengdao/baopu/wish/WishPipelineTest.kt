// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 第五百一十批 · 判据②③④＋全流程集成测试。
// ② 草稿不进生产（转正前 promote 未被调用）
// ③ 真文件 hash 不变（realUntouched=false 时不放行）＋全程落账
// ④ 失败重试上限 3：第 4 次停并报人
package com.example.zhengdao.baopu.wish

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class WishPipelineTest {

    private lateinit var root: File
    private var promoted = false
    private var rolledBack = false
    private var draftWritten = false
    private var generateCount = 0

    @Before
    fun setUp() {
        root = File.createTempFile("zd-wish", "").let { it.delete(); it.mkdirs(); it }
        promoted = false
        rolledBack = false
        draftWritten = false
        generateCount = 0
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun happyPorts() = WishPorts(
        generateBody = { generateCount++; "安全的技能正文：把 md 合并成一本" },
        writeDraft = { _, _ -> draftWritten = true; true },
        runRehearsal = { RehearsalReading(ok = true, realUntouched = true, note = "全通") },
        collectCheck = { SelfCheckVerdict.Pass("机械判定通过") },
        doPromote = { promoted = true; true },
        doRollback = { _, _ -> rolledBack = true; true },
    )

    private fun pipeline(ports: WishPorts = happyPorts()) =
        WishPipeline(root, ports) { 1_000_000L }

    private fun submitAndConfirm(p: WishPipeline): String {
        val out = p.submit("把工作区里的 .md 合并成一本", WishProduct.SKILL)
        assertTrue(out is WishOutcome.AwaitingConfirmation)
        val wishId = (out as WishOutcome.AwaitingConfirmation).wishId
        val spec = out.spec
        val confirmed = p.confirm(wishId, spec.candidates.map { it.id })
        assertNotNull(confirmed)
        return wishId
    }

    // ── 判据②：草稿不进生产 ──

    @Test
    fun 判据2_草稿落了但未转正() {
        val p = pipeline()
        val wishId = submitAndConfirm(p)
        assertTrue("草稿已写", draftWritten)
        assertFalse("转正前 promote 未被调用", promoted)
        val ctx = p.contextOf(wishId)
        assertNotNull(ctx)
        assertTrue("阶段是灰度不是已转正", ctx!!.stage == WishStage.GRAY)
    }

    @Test
    fun 判据2_灰度用满才转正() {
        val p = pipeline()
        val out = p.submit("做一个合并工具", WishProduct.SKILL)
        val wishId = (out as WishOutcome.AwaitingConfirmation).wishId
        p.confirm(wishId, out.spec.candidates.map { it.id })
        assertFalse("第一次不转正", promoted)
        p.recordUse(wishId)
        assertFalse("第二次不转正", promoted)
        val third = p.recordUse(wishId)
        assertTrue("第三次转正", third is WishOutcome.Promoted)
        assertTrue(promoted)
    }

    // ── 判据③：真文件不变＋全程落账 ──

    @Test
    fun 判据3_真文件被改时不放行() {
        val ports = happyPorts().copy(
            runRehearsal = { RehearsalReading(ok = true, realUntouched = false, note = "真文件被改") },
        )
        val p = pipeline(ports)
        val out = p.submit("合并 md", WishProduct.SKILL)
        val wishId = (out as WishOutcome.AwaitingConfirmation).wishId
        val result = p.confirm(wishId, out.spec.candidates.map { it.id })
        assertTrue("真文件被改 ⇒ 停", result is WishOutcome.Halted)
        assertFalse(promoted)
    }

    @Test
    fun 判据3_全程落账() {
        val p = pipeline()
        val wishId = submitAndConfirm(p)
        val entries = WishLedger.readByWish(root, wishId)
        assertTrue("至少有许愿/规格/草稿/试跑/自检/灰度账", entries.size >= 5)
        val stages = entries.map { it.stage }.toSet()
        assertTrue(stages.contains(WishStage.SPEC_PENDING))
        assertTrue(stages.contains(WishStage.REHEARSAL))
        assertTrue(stages.contains(WishStage.SELF_CHECK))
        assertTrue(stages.contains(WishStage.GRAY))
        assertTrue("每条账有证据", entries.all { it.evidence.isNotBlank() })
    }

    // ── 判据④：失败重试上限 3 ──

    @Test
    fun 判据4_连续三次失败停并报人() {
        val ports = happyPorts().copy(
            runRehearsal = { RehearsalReading(ok = false, realUntouched = true, note = "步骤失败") },
        )
        val p = pipeline(ports)
        val out = p.submit("合并 md", WishProduct.SKILL)
        val wishId = (out as WishOutcome.AwaitingConfirmation).wishId
        val result = p.confirm(wishId, out.spec.candidates.map { it.id })
        assertTrue("第 4 次停", result is WishOutcome.Halted)
        assertEquals(3, (result as WishOutcome.Halted).failures)
        assertEquals("只试了 3 次", 3, generateCount)
        assertFalse(promoted)
    }

    @Test
    fun 判据4_失败后成功则继续() {
        var attempts = 0
        val ports = happyPorts().copy(
            runRehearsal = {
                attempts++
                if (attempts < 3) RehearsalReading(false, true, "第 $attempts 次失败")
                else RehearsalReading(true, true, "第 $attempts 次成功")
            },
        )
        val p = pipeline(ports)
        val out = p.submit("合并 md", WishProduct.SKILL)
        val wishId = (out as WishOutcome.AwaitingConfirmation).wishId
        val result = p.confirm(wishId, out.spec.candidates.map { it.id })
        assertTrue("第 3 次成功 ⇒ 进灰度", result is WishOutcome.InGray)
        assertEquals(3, attempts)
    }

    // ── 红线：生成期就拦 ──

    @Test
    fun 红线_许愿含危险直接拒() {
        val p = pipeline()
        val result = p.submit("帮我删原件然后转账", WishProduct.SKILL)
        assertTrue(result is WishOutcome.Rejected)
        assertFalse("草稿没写", draftWritten)
    }

    @Test
    fun 红线_草稿含危险拒生成() {
        val ports = happyPorts().copy(
            generateBody = { "步骤：删原件，不留备份" },
        )
        val p = pipeline(ports)
        val out = p.submit("合并 md", WishProduct.SKILL)
        val wishId = (out as WishOutcome.AwaitingConfirmation).wishId
        val result = p.confirm(wishId, out.spec.candidates.map { it.id })
        assertTrue("草稿含危险 ⇒ 拒生成", result is WishOutcome.Rejected)
        assertFalse("草稿没落盘", draftWritten)
    }

    // ── 判据⑤集成：模型自评被拦 ──

    @Test
    fun 判据5_模型自评通过被拦并回滚() {
        val ports = happyPorts().copy(
            collectCheck = { SelfCheckVerdict.ModelPassBlocked("模型说：通过") },
        )
        val p = pipeline(ports)
        val out = p.submit("合并 md", WishProduct.SKILL)
        val wishId = (out as WishOutcome.AwaitingConfirmation).wishId
        val result = p.confirm(wishId, out.spec.candidates.map { it.id })
        assertTrue("模型自评被拦 ⇒ 回滚", result is WishOutcome.RolledBack)
        assertTrue(rolledBack)
        val entries = WishLedger.readByWish(root, wishId)
        assertTrue("被拦记了账", entries.any { it.detail.contains("模型自评被拦") })
    }

    // ── 四类产物各走一遍 ──

    @Test
    fun 四类产物都能走通流水() {
        WishProduct.entries.forEach { product ->
            setUp()
            val p = pipeline()
            val out = p.submit("测试 $product", product)
            assertTrue("$product 出规格", out is WishOutcome.AwaitingConfirmation)
            val wishId = (out as WishOutcome.AwaitingConfirmation).wishId
            val result = p.confirm(wishId, out.spec.candidates.map { it.id })
            assertTrue("$product 进灰度", result is WishOutcome.InGray)
        }
    }

    // ── 账本形状 ──

    @Test
    fun 账本缺证据不落() {
        val entry = WishLedgerEntry("w", WishStage.WISH, WishProduct.SKILL, "", "evidence", 1L)
        assertFalse(WishLedger.record(root, entry))
        val entry2 = WishLedgerEntry("w", WishStage.WISH, WishProduct.SKILL, "detail", "", 1L)
        assertFalse(WishLedger.record(root, entry2))
    }

    @Test
    fun 未确认规格不能进草稿() {
        val p = pipeline()
        val out = p.submit("合并 md", WishProduct.SKILL)
        val wishId = (out as WishOutcome.AwaitingConfirmation).wishId
        val result = p.confirm(wishId, emptyList())
        assertTrue(result is WishOutcome.Rejected)
        assertFalse(draftWritten)
    }
}