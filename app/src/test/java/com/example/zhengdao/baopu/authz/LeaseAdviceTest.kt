// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百六十七批 · 自进化只出"默认值建议"（定点 U5）：样本门槛、窗口、绝不自动生成租约。
package com.example.zhengdao.baopu.authz

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LeaseAdviceTest {

    private lateinit var root: File
    private val day = 86_400_000L
    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        root = Files.createTempDirectory("baopu467-advice").toFile()
        LeaseLedger.installRoot { root }
    }

    @After
    fun tearDown() {
        LeaseStore.resetForTest()
        root.deleteRecursively()
    }

    private fun grantAt(at: Long, cls: String = "files/delete") {
        LeaseStore.grant(
            LeaseOffer(cls, LeaseScope(LeaseScope.Kind.WORKSPACE, root.path), 10, 3_600_000L),
            1L,
            "c-$at",
            at,
        )
    }

    @Test
    fun `样本不足_不开口`() {
        repeat(LeaseAdviceRules.MIN_SAMPLE - 1) { grantAt(now - it * day) }
        assertTrue(LeaseAdviceRules.suggest(now).isEmpty())
    }

    @Test
    fun `够样本_出一条_建议值夹在天花板内`() {
        repeat(LeaseAdviceRules.MIN_SAMPLE) { grantAt(now - it * day) }
        val list = LeaseAdviceRules.suggest(now)
        assertEquals(1, list.size)
        val a = list.first()
        assertEquals("files/delete", a.cls)
        assertEquals(LeaseAdviceRules.MIN_SAMPLE, a.grants)
        assertTrue("次数不许超天花板", a.suggestCap in 1..LeaseLimits.MAX_CAP)
        assertTrue("时窗不许超天花板", a.suggestTtlMs in 1..LeaseLimits.MAX_TTL_MS)
        assertTrue("话里要给出建议数与现值", LeaseAdviceRules.line(a).contains("建议默认改成"))
    }

    @Test
    fun `窗口外的授予不算样本`() {
        repeat(LeaseAdviceRules.MIN_SAMPLE) { grantAt(now - (LeaseAdviceRules.WINDOW_DAYS + 2) * day) }
        assertTrue(LeaseAdviceRules.suggest(now).isEmpty())
    }

    @Test
    fun `出建议不落租约_不新增授予行`() {
        repeat(LeaseAdviceRules.MIN_SAMPLE) { grantAt(now - it * day) }
        val grantsBefore = LeaseLedger.replay().count { it.optString("kind") == LeaseKinds.GRANT }
        val activeBefore = LeaseStore.active(1L).size
        LeaseAdviceRules.note(now)
        assertEquals("不许自己授租约", grantsBefore, LeaseLedger.replay().count { it.optString("kind") == LeaseKinds.GRANT })
        assertEquals("内存里也不许多", activeBefore, LeaseStore.active(1L).size)
        assertTrue("建议落的是 advice 行", LeaseLedger.replay().any { it.optString("kind") == LeaseKinds.ADVICE })
    }
}
