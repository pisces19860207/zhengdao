// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百二十三批 · 法典门禁 **L33 宰相自决**（法典卷十一 L33；316 对账时是五条真缺口之一，本批落地）。
//
// 判据分三处生产件（复用，不复制一套）：
//   · 四类禁区判定＝`EmergencyGate.forbiddenCategoryRejection`（本批添在 315 的 EmergencyGate 上）；
//   · 「自决不得覆盖 requiresApproval」＝`EmergencyGate.requiresApproval`（恒 true，315 立）＋
//     `EmergencyWiring` 的 Opinion 投影（口子只许出自 law 层）；
//   · 「自决单必须落账」＝`SelfRulingLedger.note`（277 建：缺理由不落账、append-only、不设时间窗）。
//
// ## 判据（可执行）
//   ① 四类禁区（越授权／改制度／花钱／真假冲突）命中任一 ⇒ 自决必拒（拒因各异，必须上奏）；
//   ② 自决不得覆盖 requiresApproval：紧急卡这一位恒 true，自决路径撞上必拒；
//      源级钉死：wiring 只许投影 law 层的口径（不许本地写第二个判据，更不许写回 false）；
//   ③ 自决单必须落账：判了"不打扰" ⇒ 落单可回放（30 天后照样查得到）；缺理由／缺指向 ⇒ 不许落账。
//
// ## 负例（造违规即红，红在生产判定上）
// 四类禁区自决 ⇒ 拒；requiresApproval 被改写成 false／被本地口径覆盖 ⇒ 红；
// 自决单缺项还想落账 ⇒ note 返回 null（落不了账）。
package com.example.zhengdao.law

import com.example.zhengdao.baopu.agent.SelfRulingLedger
import com.example.zhengdao.baopu.inspect.ActionCode
import com.example.zhengdao.baopu.inspect.EMERGENCY_MARKER
import com.example.zhengdao.baopu.inspect.LawRef
import com.example.zhengdao.baopu.inspect.Opinion
import com.example.zhengdao.baopu.inspect.Source
import com.example.zhengdao.baopu.inspect.Severity
import com.example.zhengdao.baopu.inspect.requiresApproval
import com.example.zhengdao.baopu.inspect.selfRulingRejection
import com.example.zhengdao.baopu.law.EmergencyGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class L33SelfRulingGateTest {

    private val t0 = 1_800_000_000_000L

    /** 追认队列是进程内单例：谁用谁还（账本状态不许跨用例污染）。 */
    @Before
    fun setUp() {
        SelfRulingLedger.resetForTest()
    }

    @After
    fun tearDown() {
        SelfRulingLedger.resetForTest()
    }

    private fun opinion(marker: Boolean, critical: Boolean, l21: Boolean) = Opinion(
        source = Source.CLASSIFIER,
        severity = if (critical) Severity.CRITICAL else Severity.MAJOR,
        gist = "越权改约",
        action = ActionCode.SUGGEST_PAUSE,
        lawRef = if (l21) LawRef.L21 else LawRef.L9,
        evidence = if (marker) listOf(EMERGENCY_MARKER, "risk:r1") else listOf("risk:r1"),
    )

    /** 自决路径（OpinionWiring／LearnWiring）上挂的两个生产宿主文件——自决单的落账口在这里。 */
    private val selfRulingSites = listOf(
        "baopu/agent/LearnWiring.kt",
        "baopu/agent/OpinionWiring.kt",
    )

    // ── 正例：四类不沾自决路照走；自决单落账可回放 ──────────────────────────────

    @Test
    fun `L33_正例_四类不沾自决路照走_自决单落账且三十天后仍可回放`() {
        // 四类都不沾 ⇒ null：宰相的自决权是真的（不许把自决门修成一道永远关死的门）
        assertNull(
            "四类不沾 ⇒ 自决路照走",
            EmergencyGate.forbiddenCategoryRejection(
                touchesGrantScope = false,
                touchesLaw = false,
                spendsMoney = false,
                truthConflict = false,
            ),
        )
        // 自决单必须落账：判了"不打扰"就落一条，理由在账上，30 天后（旧时刻）照样可回放
        val row = SelfRulingLedger.note(
            opinion = opinion(marker = false, critical = false, l21 = false),
            opinionId = "op:$t0:1",
            round = "r1",
            tool = "shell",
            why = "already_refused",
            reason = "同类已关 3 次，不再打扰",
            now = t0,
        )
        assertNotNull("自决单必须落得下来", row)
        val replayed = SelfRulingLedger.replay().first { it.id == row!!.id }
        assertEquals("落账的就是这条", "同类已关 3 次，不再打扰", replayed.reason)
        assertEquals("旧时刻的账照读（不设时间窗＝30 天可回放）", t0, replayed.atMs)
        assertTrue("自决单必须带指向（两本账靠它对齐）", replayed.opinionId.isNotBlank())
    }

    // ── 负例①：四类禁区自决必拒 ─────────────────────────────────────────────────

    @Test
    fun `L33_负例_四类禁区自决必拒且拒因各异`() {
        val grant = EmergencyGate.forbiddenCategoryRejection(touchesGrantScope = true, touchesLaw = false, spendsMoney = false, truthConflict = false)
        val law = EmergencyGate.forbiddenCategoryRejection(touchesGrantScope = false, touchesLaw = true, spendsMoney = false, truthConflict = false)
        val money = EmergencyGate.forbiddenCategoryRejection(touchesGrantScope = false, touchesLaw = false, spendsMoney = true, truthConflict = false)
        val truth = EmergencyGate.forbiddenCategoryRejection(touchesGrantScope = false, touchesLaw = false, spendsMoney = false, truthConflict = true)
        listOf(grant, law, money, truth).forEach { rejection ->
            assertNotNull("四类禁区自决必须拒", rejection)
        }
        assertEquals("四类拒因各不相同（不许一句话糊弄）", 4, setOf(grant, law, money, truth).size)
        assertTrue("越授权要点名授权", grant!!.contains("授权"))
        assertTrue("花钱要点名上奏", money!!.contains("上奏"))
    }

    // ── 负例②：自决不得覆盖 requiresApproval ────────────────────────────────────

    @Test
    fun `L33_负例_自决不得覆盖requiresApproval_行为面与源级面都钉`() {
        // 行为面：这一位恒 true，没有"特殊情况"
        assertTrue("requiresApproval(紧急卡) 恒 true", EmergencyGate.requiresApproval(isEmergencyCard = true))
        assertFalse("非紧急卡才 false（判据本身不虚设）", EmergencyGate.requiresApproval(isEmergencyCard = false))
        val emergency = opinion(marker = true, critical = true, l21 = true)
        assertTrue("生产投影：紧急卡必须人批", emergency.requiresApproval)
        assertNotNull("自决路径撞上紧急卡必须拒", emergency.selfRulingRejection())
        // 源级面：wiring 只许投影 law 层的口径；自决宿主与投影处都不许写回 false
        LawIo.assertNotBlind(LawIo.MAIN_ROOT, minFiles = 100, minLines = 10_000)
        val wiring = LawIo.stripComments(
            LawIo.read("src/main/java/com/example/zhengdao/baopu/inspect/EmergencyWiring.kt"),
        )
        assertTrue(
            "requiresApproval 的口径必须出自 EmergencyGate（本地写第二个判据＝覆盖法条）",
            wiring.contains("val Opinion.requiresApproval") && wiring.contains("EmergencyGate.requiresApproval("),
        )
        LawIo.sources().forEach { (path, src) ->
            if (path in selfRulingSites || path == "baopu/inspect/EmergencyWiring.kt") {
                val code = LawIo.stripComments(src)
                assertFalse(
                    "$path 里出现 requiresApproval = false：自决不许覆盖人批位",
                    Regex("""requiresApproval\s*=\s*false""").containsMatchIn(code),
                )
            }
        }
        // 落账口没被拆：两个自决宿主都得还挂着 SelfRulingLedger.note（判了不打扰必须落账）
        selfRulingSites.forEach { path ->
            val code = LawIo.stripComments(LawIo.read("src/main/java/com/example/zhengdao/$path"))
            assertTrue("$path 的自决落账口（SelfRulingLedger.note）不许拆", code.contains("SelfRulingLedger.note("))
        }
    }

    // ── 负例③：自决单缺项不许落账 ───────────────────────────────────────────────

    @Test
    fun `L33_负例_自决单缺理由或缺指向不许落账`() {
        val base = opinion(marker = false, critical = false, l21 = false)
        assertNull(
            "缺理由的自决单不许落账（没有理由的自决＝把 L33 变成空话）",
            SelfRulingLedger.note(base, opinionId = "op:1", round = "r1", tool = "shell", why = "no_law", reason = "   ", now = t0),
        )
        assertNull(
            "缺指向的自决单不许落账（两本账对不齐）",
            SelfRulingLedger.note(base, opinionId = " ", round = "r1", tool = "shell", why = "no_law", reason = "判为不必打扰", now = t0),
        )
        // 落不了的账必须真的没进账（不许"返回 null 但账上多一行"）
        assertTrue("拒收的账不许偷偷进账", SelfRulingLedger.replay().isEmpty())
    }
}
