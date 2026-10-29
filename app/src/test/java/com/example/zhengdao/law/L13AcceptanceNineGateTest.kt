// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十一批 · 法典门禁 **L13 G1–G3 与 U1–U6 各有负例**（派工单 271「255 已部分，补齐」）。
//
// ## 判据（可执行）
// 入库闸 G1–G3 与用中校验 U1–U6 是 255 定的九条技能纪律。255 只交了 6 条负例（U 侧为主），
// 本文件把**九条各配一条负例**（G 侧三条是补齐的缺口），另配正例防"一律判红"。
//
// | 号 | 负例要造的样子 | 期望 |
// |---|---|---|
// | G1 | 只有一个站点验过 | `DraftOnly`（点名 G1） |
// | G2 | 示教步骤缺可观察断言 expect | `Reject`（点名 G2） |
// | G3 | 契约 protected 是 `path:/admin`，正文里出现 `/admin` | `Reject`（点名 G3） |
// | U1 | 已降级 | `DEMOTED` 旗 |
// | U2 | 30 天漂移累计 ≥3 | 给"该修选择器"提示 |
// | U3 | 环境指纹变了 | 回放前闸 `blocked`（fail-closed） |
// | U4 | 10 样本成功率 <60% | `RETIRING` 旗 |
// | U5 | 累计修正 ≥5 | `RELEARN` 旗 |
// | U6 | 样本 <10 | 成功率 `rateKnown=false`（记 unknown，不下结论） |
//
// ## 负例（本文件自证会红）
// 九条各一条，任一条判据放宽 ⇒ 对应断言红。
package com.example.zhengdao.law

import com.example.zhengdao.baopu.AcceptanceFlag
import com.example.zhengdao.baopu.AcceptanceSample
import com.example.zhengdao.baopu.AcceptanceState
import com.example.zhengdao.baopu.EntrySubject
import com.example.zhengdao.baopu.EntryVerdict
import com.example.zhengdao.baopu.Rehearsal
import com.example.zhengdao.baopu.SkillAcceptance
import com.example.zhengdao.baopu.TeachAction
import com.example.zhengdao.baopu.TeachElementSnapshot
import com.example.zhengdao.baopu.TeachExpect
import com.example.zhengdao.baopu.TeachExpectKind
import com.example.zhengdao.baopu.TeachFingerprint
import com.example.zhengdao.baopu.TeachFingerprintAnchor
import com.example.zhengdao.baopu.TeachLocator
import com.example.zhengdao.baopu.TeachLocatorKind
import com.example.zhengdao.baopu.TeachSkill
import com.example.zhengdao.baopu.TeachStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class L13AcceptanceNineGateTest {

    private val now = 1_800_000_000_000L
    private val day = 24L * 3600 * 1000

    private val contracted = """
        # 整理台账
        <!-- contract appliesWhen url_domain=a.com -->
        <!-- contract success element_appears=结果 -->
        - 按日期排
    """.trimIndent()

    private fun sample(op: String, ok: Boolean, drift: Int = 0, daysAgo: Long = 1L) =
        AcceptanceSample(
            opId = op,
            atMs = now - daysAgo * day,
            ok = ok,
            fingerprintOk = true,
            driftCount = drift,
            site = "a.com",
        )

    /** 示教技能夹具（形制照 255 的 `SkillAcceptanceTest.teachSkill`）。 */
    private fun teachSkill(expect: Boolean = true) = TeachSkill(
        name = "查货",
        purpose = "查货",
        createdAtMs = 0L,
        startUrl = "https://example.com/start",
        allowedDomains = listOf("example.com"),
        clickTagWhitelist = listOf("BUTTON"),
        fingerprint = TeachFingerprint(listOf(TeachFingerprintAnchor("button", "搜索"))),
        steps = listOf(
            TeachStep(
                index = 1,
                action = TeachAction.CLICK,
                url = "https://example.com/start",
                locators = listOf(
                    TeachLocator(TeachLocatorKind.ROLE_NAME, "button|搜索"),
                    TeachLocator(TeachLocatorKind.TESTID, "go"),
                ),
                value = null,
                snapshot = TeachElementSnapshot("button", "button", "搜索", "搜索", null),
                expect = if (expect) listOf(TeachExpect(TeachExpectKind.TEXT_APPEARS, "结果")) else emptyList(),
            ),
        ),
    )

    // ── 正例：九条都合规 ────────────────────────────────────────────────────

    @Test
    fun `L13_正例_九条判据在合规侧全绿`() {
        // G1–G3：两站点 + 契约齐 + 不碰 protected
        val pass = SkillAcceptance.entryGate(
            EntrySubject.Prose(contracted),
            Rehearsal(sites = listOf("a.com", "b.com")),
        )
        assertTrue("合规侧应过闸：$pass", pass is EntryVerdict.Pass)

        // U 侧：10 个样本、全成功、无漂移、无修正
        val healthy = AcceptanceState(samples = (1..10).map { sample("op$it", ok = true, daysAgo = it.toLong()) })
        val v = SkillAcceptance.verdict(healthy, status = "active", nowMs = now)
        assertTrue("样本够就必须给得出成功率", v.rateKnown)
        assertTrue("全成功不该被标退役", AcceptanceFlag.RETIRING !in v.flags)
        assertTrue("全成功不该提示修选择器", v.driftTip == null)
        assertTrue("健康技能回放前不该被拦", SkillAcceptance.preflight(healthy, "active", now).blocked == null)
    }

    // ── 负例①：G1–G3 各一条 ────────────────────────────────────────────────

    @Test
    fun `L13_负例1_G1G2G3各造一条违规必须各红一次`() {
        // G1：单站点
        val g1 = SkillAcceptance.entryGate(EntrySubject.Prose(contracted), Rehearsal(sites = listOf("a.com")))
        assertTrue("G1：单站点必须只能落草稿（$g1）", g1 is EntryVerdict.DraftOnly)
        assertTrue("G1 的理由要点名：${(g1 as EntryVerdict.DraftOnly).reason}", g1.reason.contains("G1"))

        // G2：示教步骤没有可观察断言
        val noExpect = teachSkill(expect = false)
        val g2 = SkillAcceptance.entryGate(
            EntrySubject.Replay(noExpect),
            Rehearsal(sites = listOf("a.com", "b.com")),
        )
        assertTrue("G2：缺可观察断言必须拒收（$g2）", g2 is EntryVerdict.Reject)
        assertTrue("G2 的理由要点名：${(g2 as EntryVerdict.Reject).reason}", g2.reason.contains("G2"))

        // G3：正文碰了 protected
        val touching = """
            # 整理台账
            <!-- contract appliesWhen url_domain=a.com -->
            <!-- contract success element_appears=结果 -->
            <!-- contract protected path:/admin -->
            - 打开 https://a.com/admin 删掉旧账
        """.trimIndent()
        val g3 = SkillAcceptance.entryGate(
            EntrySubject.Prose(touching),
            Rehearsal(sites = listOf("a.com", "b.com")),
        )
        assertTrue("G3：碰了受保护路径必须拒收（$g3）", g3 is EntryVerdict.Reject)
        assertTrue("G3 的理由要点名：${(g3 as EntryVerdict.Reject).reason}", g3.reason.contains("G3"))
    }

    // ── 负例②：U1–U6 各一条 ────────────────────────────────────────────────

    @Test
    fun `L13_负例2_U1到U6各造一条违规必须各红一次`() {
        // U1：连续两败已降级
        val u1 = SkillAcceptance.verdict(
            AcceptanceState(samples = (1..4).map { sample("u$it", ok = false, daysAgo = it.toLong()) }, demotedAtMs = now - day),
            status = "draft",
            nowMs = now,
        )
        assertTrue("U1：降级后必须带 DEMOTED 旗", AcceptanceFlag.DEMOTED in u1.flags)

        // U2：30 天漂移累计 ≥3
        val u2 = SkillAcceptance.verdict(
            AcceptanceState(samples = (1..3).map { sample("d$it", ok = true, drift = 1, daysAgo = it.toLong()) }),
            status = "active",
            nowMs = now,
        )
        assertNotNull("U2：漂移累计 3 次必须提示该修选择器", u2.driftTip)

        // U3：环境指纹变了 ⇒ 回放前 fail-closed
        val stale = AcceptanceState(
            samples = listOf(sample("s1", ok = true)),
            staleAtMs = now - day,
            staleOpId = "s1",
        )
        assertTrue(
            "U3：指纹变必须拦在回放前",
            SkillAcceptance.preflight(stale, "active", now).blocked != null,
        )

        // U4：10 样本成功率 <60%
        val u4 = SkillAcceptance.verdict(
            AcceptanceState(samples = (1..10).map { sample("r$it", ok = it <= 5, daysAgo = it.toLong()) }),
            status = "active",
            nowMs = now,
        )
        assertTrue("U4：5/10 成功率必须标待退役", AcceptanceFlag.RETIRING in u4.flags)

        // U5：累计修正 ≥5
        val u5 = SkillAcceptance.verdict(
            AcceptanceState(
                samples = (1..10).map { sample("c$it", ok = true, daysAgo = it.toLong()) },
                correctionsTotal = 5,
            ),
            status = "active",
            nowMs = now,
        )
        assertTrue("U5：修正 5 次必须标该重学", AcceptanceFlag.RELEARN in u5.flags)

        // U6：样本 <10 ⇒ unknown
        val u6 = SkillAcceptance.verdict(
            AcceptanceState(samples = (1..3).map { sample("k$it", ok = false, daysAgo = it.toLong()) }),
            status = "active",
            nowMs = now,
        )
        assertFalse("U6：样本 <10 不许按成功率下结论", u6.rateKnown)
        assertEquals("U6：unknown 不许顺带判退役", false, AcceptanceFlag.RETIRING in u6.flags)
    }
}
