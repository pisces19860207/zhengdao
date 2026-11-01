// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百一十九批 · **自决路径的能力账门禁**（派工单 §一「查能力并收敛」的判据本体；
// 行为面钉 EmergencyGate.selfRulingAdmission，源级面钉两处生产自决点的能力账）。
//
// ## 背景（315 自报偏差①的收口）
// 生产里走「自决」（落 SelfRulingLedger 自决单）的路径一共两处（点名册见
// EmergencyWiringGateTest，本文件不重钉名册、只钉**能力账**）：
//   · `agent/OpinionWiring.kt`：判定面**有**能力放行紧急卡（onOpinion 通用入口＋
//     alreadyRefused ⇒ SKIP ⇒ 落单）＝半缺口挂账，接线归持有该文件的批次；
//   · `agent/LearnWiring.kt`：**无**能力放行（压下前必过 mustAsk，紧急卡必含 critical ⇒ 拦得住）——
//     但这条防线是**间接**的（靠 critical，marker/L21 都不在 mustAsk 里），315 指出它没有门禁看着。
// 两处文件都在 319 租约外（agent/ 禁区），按派工单 §一.2 以**门禁断言收敛**：
// 统一入口 `EmergencyGate.selfRulingAdmission` 已立在 law/（拒因与资格只有这一个出处），
// 本文件把两处的能力账逐条钉住——防线漂了、半缺口易位了、有人自造拒因了，当场红。
//
// ## 判据（可执行）
//   ① **缺能力检查的路径被拒**：capabilityChecked=false ⇒ 一律拒（fail-closed，
//      不许把「没查」当「可以」）——即使意见不是紧急卡也拒；
//   ② 查过能力且撞上紧急卡 ⇒ 拒，且拒因与 `EmergencyGate.selfRulingRejection` 逐字相同
//      （拒因唯一出自 law/，不许第二个口径）；
//   ③ 查过能力且非紧急 ⇒ null（自决路照走——不许把自决门修成永远关死的门）；
//   ④ 源级：两处自决点的能力账逐条钉住（见 [SITES]），LearnWiring 的必问集防线
//      必须在落单口**之前**且仍含 critical；
//   ⑤ 源级：自决宿主不许自造紧急拒因（「紧急卡必须人批」那句只许住在 law/）。
package com.example.zhengdao.law

import com.example.zhengdao.baopu.law.EmergencyGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class L33SelfRulingAdmissionGateTest {

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
