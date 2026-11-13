// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十七批 · 三段机检的行为夹具（判据①②③④⑥）。
//
// 夹具全是内存字符串：不建临时目录、不落盘、不跑 git —— 跑完水位不动（607 口径），
// 这也是判据⑤「运行前后 git status 一致」的第一层保证。
package com.example.zhengdao.baopu.inspect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaimEvidenceAuditTest {

    /** 金吾卫取证口的测试替身：每个读数都能单独造，便于逐条对拍。 */
    private class FakeEnv(
        override val roundStartMs: Long = 1_000L,
        override val hourOfDay: Int = 10,
        private val ledger: List<String> = emptyList(),
        private val commits: List<String> = emptyList(),
        private val paths: Set<String> = emptySet(),
        private val merged: Boolean = false,
        private val xml: TestXmlReading? = null,
        private val device: List<String> = emptyList(),
    ) : ClaimEvidenceEnv {
        override fun ledgerLines(): List<String> = ledger
        override fun commitShortIds(): List<String> = commits
        override fun gitShowPaths(): Set<String> = paths
        override fun mergedIntoMain(branch: String): Boolean = merged
        override fun testXml(): TestXmlReading? = xml
        override fun deviceEvidenceFiles(): List<String> = device
    }

    private fun xml(mtime: Long, failures: Int = 0) = TestXmlReading(
        path = "app/build/test-results/testDebugUnitTest/TEST-sample.xml",
        suites = 12,
        cases = 140,
        failures = failures,
        mtimeMs = mtime,
    )

    // ── 判据①：称 3 件、账上 2 条 ⇒ 报 1 缺口，且指「第几件、缺什么证据」 ──────

    @Test
    fun `C1_称三件账两条_报一条缺口并指第三件缺账行`() {
        val report = """
            - 已完成 甲模块
            - 已完成 乙模块
            - 已完成 丙模块
        """.trimIndent()
        val env = FakeEnv(ledger = listOf("账行：已完成 甲模块", "账行：已完成 乙模块"))
        val out = ClaimEvidenceAudit.audit(report, env)

        assertEquals("三件都要抽出来", 3, out.claims.size)
        assertEquals("只有一条对不上", 1, out.gaps.size)
        assertEquals("缺口必须指向第三件", 3, out.gaps[0].claimIndex)
        val line = out.gaps[0].render()
        assertTrue("缺口要写第几件：$line", line.contains("第 3 件"))
        assertTrue("缺口要写缺什么证据：$line", line.contains(EvidenceNeed.LEDGER_OR_COMMIT.cn))
        assertEquals("交右相的清单与缺口一一对应", 1, out.chancellorInput.size)
        assertEquals(line, out.chancellorInput[0])
    }

    @Test
    fun `C1b_commit短号也算已完成类的证据`() {
        val report = "- 已完成 丁模块"
        val env = FakeEnv(commits = listOf("a1b2c3d 已完成 丁模块"))
        assertEquals("账行没有但 commit 命中 ⇒ 不算缺口", 0, ClaimEvidenceAudit.audit(report, env).gaps.size)
    }

    @Test
    fun `C1c_三件全有账_零缺口`() {
        val report = """
            1. 已完成 甲模块
            2. 已完成 乙模块
            3. 已完成 丙模块
        """.trimIndent()
        val env = FakeEnv(ledger = listOf("甲模块 已收", "乙模块 已收", "丙模块 已收"))
        val out = ClaimEvidenceAudit.audit(report, env)
        assertEquals(3, out.claims.size)
        assertTrue("三条全对得上不该报缺口", out.gaps.isEmpty())
        assertTrue(out.chancellorInput.isEmpty())
    }

    // ── 判据②：含糊句不误报（负例） ──────────────────────────────────────────

    @Test
    fun `C2_含糊句不计入声称_也不报缺口`() {
        val report = """
            - 优化了一下
            - 稍微调整了相关逻辑
            - 整体上有所改进
            - 大概做了些事情
            - 完成了一些工作
            - 已完成 一些工作
            - 已加文件 相关改动
            - 已合并 若干分支
        """.trimIndent()
        val out = ClaimEvidenceAudit.audit(report, FakeEnv())
        assertTrue("含糊句一个都不该被当成声称：${out.claims}", out.claims.isEmpty())
        assertTrue("含糊句不该凭空生缺口：${out.gaps}", out.gaps.isEmpty())
        assertEquals(LeftAdvice.LEDGER_ONLY, out.leftAdvice)
    }

    @Test
    fun `C2b_模糊动词与明确动词同段_只抽明确的那条`() {
        val report = """
            - 优化了一下
            - 已加文件 `tools/check-a.py`
        """.trimIndent()
        val out = ClaimEvidenceAudit.audit(
            report,
            FakeEnv(paths = setOf("tools/check-a.py")),
        )
        assertEquals(1, out.claims.size)
        assertEquals(ClaimAction.FILE_ADDED, out.claims[0].action)
        assertTrue(out.gaps.isEmpty())
    }

    // ── 判据③：旧 XML（mtime 早于本轮）⇒ 报「证据过期」 ──────────────────────

    @Test
    fun `C3_XML的mtime早于本轮_报证据过期`() {
        val env = FakeEnv(roundStartMs = 1_000L, xml = xml(mtime = 500L))
        val out = ClaimEvidenceAudit.audit("- 测试全绿", env)
        assertEquals(1, out.gaps.size)
        assertEquals(GapReason.STALE, out.gaps[0].reason)
        assertTrue("必须点名证据过期：${out.gaps[0].render()}", out.gaps[0].render().contains("证据过期"))
        assertTrue(out.gaps[0].render().contains(EvidenceNeed.TEST_XML.cn))
    }

    @Test
    fun `C3b_本轮新XML_不报过期`() {
        val env = FakeEnv(roundStartMs = 1_000L, xml = xml(mtime = 1_500L))
        assertTrue("本轮 XML 该算数", ClaimEvidenceAudit.audit("- 测试全绿", env).gaps.isEmpty())
    }

    @Test
    fun `C3c_XML里有失败例_称全绿仍报缺口_没有XML也报缺口`() {
        val withFailures = FakeEnv(roundStartMs = 1_000L, xml = xml(mtime = 1_500L, failures = 2))
        assertEquals(1, ClaimEvidenceAudit.audit("- 测试全绿", withFailures).gaps.size)
        val noXml = FakeEnv(roundStartMs = 1_000L)
        assertEquals(1, ClaimEvidenceAudit.audit("- 测试全绿", noXml).gaps.size)
    }

    // ── 判据④：称「已合并」但未进 main ⇒ 报缺口 ──────────────────────────────

    @Test
    fun `C4_称已合并但没进main_报缺口并指merge_base`() {
        val out = ClaimEvidenceAudit.audit("- 已合并 feat/claim-evidence-audit", FakeEnv(merged = false))
        assertEquals(1, out.gaps.size)
        assertTrue(out.gaps[0].render().contains(EvidenceNeed.MERGE_BASE.cn))
        assertEquals(LeftAdvice.SUGGESTION_TAB, out.leftAdvice)
    }

    @Test
    fun `C4b_真是main祖先_不报缺口`() {
        val out = ClaimEvidenceAudit.audit("- 已合并 feat/claim-evidence-audit", FakeEnv(merged = true))
        assertTrue("merge-base 命中就不该报缺口", out.gaps.isEmpty())
        assertEquals(LeftAdvice.LEDGER_ONLY, out.leftAdvice)
    }

    // ── 找证据：五类证据映射的正反两面（金吾卫那一半） ──────────────────────

    @Test
    fun `E1_已加文件按git_show路径命中_不命中报缺口`() {
        val report = "- 已加文件 `app/src/main/A.kt`"
        val hit = FakeEnv(paths = setOf("app/src/main/A.kt"))
        assertTrue(ClaimEvidenceAudit.audit(report, hit).gaps.isEmpty())
        val miss = FakeEnv(paths = setOf("app/src/main/B.kt"))
        val gap = ClaimEvidenceAudit.audit(report, miss).gaps.single()
        assertTrue(gap.render().contains(EvidenceNeed.GIT_SHOW_PATH.cn))
    }

    @Test
    fun `E2_已删文件同样按路径对账`() {
        val report = "- 已删文件 `app/src/main/Old.kt`"
        assertEquals(0, ClaimEvidenceAudit.audit(report, FakeEnv(paths = setOf("app/src/main/Old.kt"))).gaps.size)
        assertEquals(1, ClaimEvidenceAudit.audit(report, FakeEnv()).gaps.size)
    }

    @Test
    fun `E3_真机验过按证据目录文件命中_不命中报缺口`() {
        val report = "- 真机验过 治理中心截图"
        val hit = FakeEnv(device = listOf("治理中心截图.png", "run_log.txt"))
        assertTrue(ClaimEvidenceAudit.audit(report, hit).gaps.isEmpty())
        val miss = FakeEnv(device = listOf("run_log.txt"))
        assertTrue(
            ClaimEvidenceAudit.audit(report, miss).gaps.single().render()
                .contains(EvidenceNeed.DEVICE_DIR.cn),
        )
    }

    @Test
    fun `E4_反引号点名时按文件名匹配_不被整句对象卡住`() {
        val report = "| 已加文件 | `tools/check-b.py` | 新增门禁 |"
        val env = FakeEnv(paths = setOf("tools/check-b.py"))
        val out = ClaimEvidenceAudit.audit(report, env)
        assertEquals(1, out.claims.size)
        assertEquals("`tools/check-b.py`", out.claims[0].target)
        assertTrue("反引号里的路径该按文件名命中", out.gaps.isEmpty())
    }

    // ── 判据⑥：右相三态不在机检里；左相判打扰的四象限 ──────────────────────

    @Test
    fun `C6_交右相的只是缺口清单_两处上报都点到`() {
        val out = ClaimEvidenceAudit.audit("- 已完成 甲模块", FakeEnv())
        assertEquals(2, out.handoffTo.size)
        assertTrue(out.handoffTo.contains(AuditOutcome.HANDOFF_RIGHT))
        assertTrue(out.handoffTo.contains(AuditOutcome.HANDOFF_LEFT))
        out.chancellorInput.forEach { line ->
            assertFalse("机检不许下合格结论：$line", line.contains("PASS"))
            assertFalse("机检不许下合格结论：$line", line.contains("FAIL"))
            assertFalse("机检不许下合格结论：$line", line.contains("UNVERIFIABLE"))
        }
    }

    @Test
    fun `C6b_缺口一且白天_建议页签行`() {
        assertEquals(LeftAdvice.SUGGESTION_TAB, ClaimEvidenceAudit.adviceOf(1, 10))
    }

    @Test
    fun `C6c_左相说别打扰_只落账`() {
        assertEquals(LeftAdvice.LEDGER_ONLY, ClaimEvidenceAudit.adviceOf(1, 10, leftAllowsDisturb = false))
    }

    @Test
    fun `C6d_深夜即使有缺口也不打扰`() {
        assertEquals(LeftAdvice.LEDGER_ONLY, ClaimEvidenceAudit.adviceOf(1, 3))
        assertEquals(LeftAdvice.SUGGESTION_TAB, ClaimEvidenceAudit.adviceOf(1, 6))
    }

    @Test
    fun `C6e_零缺口不打扰`() {
        assertEquals(LeftAdvice.LEDGER_ONLY, ClaimEvidenceAudit.adviceOf(0, 10))
    }

    @Test
    fun `C6f_端到端_左相否决时缺口仍在账上只是不进页签`() {
        val out = ClaimEvidenceAudit.audit(
            "- 已完成 甲模块",
            FakeEnv(hourOfDay = 14),
            leftAllowsDisturb = false,
        )
        assertEquals("左相否决不该把缺口洗掉", 1, out.gaps.size)
        assertEquals(1, out.chancellorInput.size)
        assertEquals(LeftAdvice.LEDGER_ONLY, out.leftAdvice)
    }
}
