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

    // ── 以下并入（原 319 批「L33 准入分条」；同一法条号只许一个归属文件）────────

    // ── 1. 行为面：统一准入的三格 ────────────────────────────────────────────────

    @Test
    fun `L33_正例_能力检查过且非紧急_自决路照走`() {
        assertNull(
            "查过能力且非紧急 ⇒ 准入：不许把自决门修成永远关死的门（L33 的自决权是真的）",
            EmergencyGate.selfRulingAdmission(capabilityChecked = true, requiresApproval = false),
        )
    }

    @Test
    fun `L33_负例_缺能力检查的路径一律拒_不许把没查当可以`() {
        // 判据①（派工单点名的负例）：没查能力 ⇒ fail-closed，**不许默认允许**——
        // 即使这条意见不是紧急卡也拒：缺检查的路径连「它放行的是什么」都说不清。
        val nonEmergency = EmergencyGate.selfRulingAdmission(capabilityChecked = false, requiresApproval = false)
        assertNotNull("没查能力 ⇒ 拒（非紧急也拒）", nonEmergency)
        val emergency = EmergencyGate.selfRulingAdmission(capabilityChecked = false, requiresApproval = true)
        assertNotNull("没查能力 ⇒ 拒（紧急卡更拒）", emergency)
        assertTrue(
            "拒因要点名「没查」这件事本身",
            nonEmergency!!.contains("没有能力检查") && nonEmergency.contains("没查"),
        )
    }

    @Test
    fun `L33_负例_查过能力撞紧急卡仍拒且拒因唯一出自law`() {
        val admission = EmergencyGate.selfRulingAdmission(capabilityChecked = true, requiresApproval = true)
        assertNotNull("紧急卡必须人批：查过能力也一样拒", admission)
        assertEquals(
            "拒因必须与 selfRulingRejection 逐字相同（拒因唯一出自 law/，不许第二个口径）",
            EmergencyGate.selfRulingRejection(isEmergencyCard = true),
            admission,
        )
        assertFalse(
            "拒因不许是空的（防的是「拒了但不留话」）",
            admission.isNullOrBlank(),
        )
    }

    // ── 2. 源级面：两处生产自决点的能力账 ────────────────────────────────────────

    /** 一处自决点的能力账（一行一档：结论＋证据锚；锚在源里必须找得到，漂了即红）。 */
    private data class SelfRulingSite(
        val path: String,
        val capability: String,
        val evidence: String,
    )

    /**
     * 两处生产自决点的能力账（319 在基线 8061da8b 上逐处查证，判定原文见交付报告 §2）。
     *
     * 「有/无能力」判的是**这条路径拦不拦得住紧急卡**（派工单 §一.1 的原文口径）；
     * capability 一栏不许写空话——每个结论都带一个源级可查的证据锚。
     */
    private val SITES = listOf(
        SelfRulingSite(
            path = "baopu/agent/LearnWiring.kt",
            capability = "无能力放行：压下落单前必过 LearnPolicy.mustAsk（critical‖L7‖L8），紧急卡必含 critical ⇒ 拦得住" +
                "（间接防线——marker/L21 不在 mustAsk 里，防线本体由本门禁的 mustAsk 段看着）",
            evidence = "LearnPolicy.mustAsk(",
        ),
        SelfRulingSite(
            path = "baopu/agent/OpinionWiring.kt",
            capability = "有能力放行（判定面）：onOpinion 通用入口＋alreadyRefused ⇒ SKIP ⇒ 落单，紧急卡在这条路上不出自决禁区" +
                "＝半缺口挂账；生产触发面暂无 marker:EMERGENCY 产出方（全仓 grep 证实），接线归持有该文件的批次",
            evidence = "OpinionWhy.ALREADY_REFUSED",
        ),
    )

    @Test
    fun `L33_源级_两处自决点能力账逐条钉住`() {
        LawIo.assertNotBlind(LawIo.MAIN_ROOT, minFiles = 100, minLines = 10_000)
        SITES.forEach { site ->
            val code = LawIo.stripComments(LawIo.read("src/main/java/com/example/zhengdao/${site.path}"))
            assertTrue(
                "${site.path} 的能力账证据锚不见了：${site.evidence}（自决路径搬家/改名 ⇒ 先重查能力再更这本账）",
                code.contains(site.evidence),
            )
            assertTrue("能力账的结论不许是空话：${site.path}", site.capability.contains("能力"))
        }
        // 落账口没被拆：两处自决点都还得挂着 SelfRulingLedger.note（能力账盯的是活路径，不是死代码）
        SITES.forEach { site ->
            val code = LawIo.stripComments(LawIo.read("src/main/java/com/example/zhengdao/${site.path}"))
            assertTrue("${site.path} 的自决落账口（SelfRulingLedger.note）不许拆", code.contains("SelfRulingLedger.note("))
        }
    }

    @Test
    fun `L33_源级_LearnWiring的必问集防线必须在落单口之前且仍含critical`() {
        val code = LawIo.stripComments(LawIo.read("src/main/java/com/example/zhengdao/baopu/agent/LearnWiring.kt"))
        val defense = code.indexOf(MUST_ASK_DEF)
        val slip = code.indexOf("SelfRulingLedger.note(")
        assertTrue("LearnWiring 的必问集定义（fun mustAsk）不见了：无能力放行的结论作废，先重查能力再更账", defense >= 0)
        assertTrue("LearnWiring 的自决落单口不见了", slip >= 0)
        assertTrue(
            "必问集防线必须排在落单口之前（压下分支先过 mustAsk 再谈落单）：" +
                "防线被挪到落单之后＝紧急卡到得了落单口，能力结论从「无」翻「有」，本账作废",
            defense < slip,
        )
        // 防线本体（mustAsk 定义往下的行窗）必须仍含 critical：315 判「无能力」靠的就是
        // 紧急卡必含 critical 这条间接条件——它被拿掉，防线静默失效，这里当场红。
        val window = code.substring(defense, minOf(defense + 400, code.length))
        assertTrue(
            "mustAsk 的必问集不再含 critical：紧急卡的间接防线没了（315 的「无能力」结论作废）——" +
                "先重查能力、把 L21 补进必问集或更本账，不许静默漂",
            window.contains("Severity.CRITICAL"),
        )
    }

    @Test
    fun `L33_源级_OpinionWiring不许自造紧急拒因`() {
        // 拒因唯一出自 law/：自决宿主不许把「紧急卡必须人批」那句抄进自己家——
        // 接线批要引用就调 EmergencyGate.selfRulingAdmission / Opinion.selfRulingRejection。
        val forbidden = "紧急卡必须人批"
        SITES.forEach { site ->
            val code = LawIo.stripComments(LawIo.read("src/main/java/com/example/zhengdao/${site.path}"))
            assertFalse(
                "${site.path} 里出现了自造的紧急拒因（「$forbidden」只许住在 law/）：第二个口径＝改法",
                code.contains(forbidden),
            )
        }
    }

    @Test
    fun `L33_源级_统一入口已立在law层`() {
        val gate = LawIo.stripComments(LawIo.read("src/main/java/com/example/zhengdao/baopu/law/EmergencyGate.kt"))
        assertTrue(
            "统一准入口（selfRulingAdmission）不在 law/ 了：两处自决点的收敛会失去唯一拒因出处",
            gate.contains("fun selfRulingAdmission("),
        )
        assertTrue(
            "准入口的 fail-closed 分支不见了：不许把「没能力检查」当默认允许",
            gate.contains("capabilityChecked"),
        )
    }
}


/** LearnWiring.kt 里必问集定义的起始锚（fun mustAsk）：防线本体的行窗从这里往下数。 */
private const val MUST_ASK_DEF = "fun mustAsk(opinion: Opinion): Boolean"
