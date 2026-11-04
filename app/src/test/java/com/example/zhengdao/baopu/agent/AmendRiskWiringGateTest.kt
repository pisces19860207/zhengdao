// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百一十七批 · 判据④的源级门禁（房型照 360 的 AmendContractWiringGateTest）：
// 「判定只许调 RiskMatrix，amend 链路里不许有第二套判断」——grep 为证，钉在源码上。
//
// 扫的是**剥掉注释之后**的代码：KDoc 里解释口径的话不算证据，真调用才算。
package com.example.zhengdao.baopu.agent

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmendRiskWiringGateTest {

    private fun code(rel: String): String =
        File(rel).let { if (it.isFile) it else File("app/$rel") }.readText()
            .replace("\r\n", "\n")
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun risk(): String = code("src/main/java/com/example/zhengdao/baopu/plan/AmendRisk.kt")
    private fun wiring(): String = code("src/main/java/com/example/zhengdao/baopu/agent/AmendRiskWiring.kt")
    private fun engine(): String = code("src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt")
    private fun controller(): String = code("src/main/java/com/example/zhengdao/baopu/agent/PlanController.kt")
    private fun board(): String = code("src/main/java/com/example/zhengdao/baopu/plan/PlanPledgeBoard.kt")

    /** 某个函数体（从声明到下一个 `private fun` / 文件尾；够用就够，不求精确解析）。 */
    private fun bodyOf(src: String, decl: String): String {
        val at = src.indexOf(decl)
        if (at < 0) return ""
        val rest = src.substring(at + decl.length)
        val end = rest.indexOf("private fun ").takeIf { it > 0 } ?: rest.length
        return rest.substring(0, end)
    }

    private fun occurrences(src: String, needle: String): Int = src.split(needle).size - 1

    // ── 判据④：唯一口径 ──────────────────────────────────────────────────────

    @Test
    fun `G1_重算只调矩阵本体_旧参数新参数各一次_没有第三处判定`() {
        val src = risk()
        assertEquals(
            "旧参数＋新参数各判一次，且都必须走 RiskMatrix.classify",
            2,
            occurrences(src, "RiskMatrix.classify("),
        )
        assertEquals(
            "amend 判定层里出现了别处来的 classify（第二套判定）",
            occurrences(src, "RiskMatrix.classify("),
            occurrences(src, "classify("),
        )
    }

    @Test
    fun `G2_amend链路四个文件零风险字面量_零第二套判定特征`() {
        val files = mapOf(
            "AmendRisk.kt" to risk(),
            "AmendRiskWiring.kt" to wiring(),
            "RunEngine.kt" to engine(),
            "PlanController.kt" to controller(),
        )
        val forbidden = listOf(
            "RiskClass.R", "RiskDecision.", "deleteLike", "maxDeleteTargetsPerCall",
            "writePathKeys", "empty_trash", "sensitiveText", "ALWAYS_ASK",
        )
        forbidden.forEach { needle ->
            files.forEach { (name, src) ->
                assertFalse(
                    "$name 里出现了自行判风险的字面量「$needle」——档位只能由 RiskMatrix 给",
                    src.contains(needle),
                )
            }
        }
    }

    @Test
    fun `G3_接线层只把三态翻成动作_三条分支齐全`() {
        val src = wiring()
        assertTrue("升档分支不见了", src.contains("AmendRiskShift.UP ->"))
        assertTrue("降档分支不见了", src.contains("AmendRiskShift.DOWN ->"))
        assertTrue("档位不变分支不见了", src.contains("AmendRiskShift.SAME ->"))
        assertTrue("不变档那支必须真的什么都不做", src.contains("AmendRiskShift.SAME -> AmendRiskOutcome()"))
        assertTrue("接线层不许自己调矩阵判定", !src.contains("RiskMatrix.classify("))
    }

    // ── 真接线：改约落地 ⇒ 下一次动作重算 ───────────────────────────────────

    @Test
    fun `G4_改约落地挂基线_下一次动作消费基线_同一次不消费`() {
        val src = engine()
        assertTrue(
            "360 的透传口没在改约落地处置基线（起单处是唯一落地信号）",
            bodyOf(src, "private fun reissueOrderForAmend").contains("amendRiskBaseline = lastActionCall"),
        )
        val gate = bodyOf(src, "private fun amendGate(")
        assertTrue(
            "基线「何时被消费」没走纯函数（改回内联字段判断就退回不可测的 private 状态机）",
            gate.contains("AmendRiskBaseline.step(call, amendRiskBaseline)"),
        )
        assertTrue(
            "重算闸没接进串行调用链",
            gate.contains("amendRiskGate(call, riskStep.taken)"),
        )
        assertFalse(
            "handleSerialCall 不许为本单多一个分支：它圈复杂度基线 15，多一个 ?.let 就顶破阈值（detekt +1）",
            bodyOf(src, "private fun handleSerialCall(").contains("amendRiskGate"),
        )
    }

    @Test
    fun `G5_重算闸只搬运不判档_三态动作真执行`() {
        val src = engine()
        val gate = bodyOf(src, "private fun amendRiskGate(")
        assertTrue("重算闸不见了", gate.isNotEmpty())
        assertTrue("没调接线层重算", gate.contains("AmendRiskWiring.onAmendLanded("))
        assertTrue("旧/新参数没成对传进去", gate.contains("AmendRiskProbe(baseline, call)"))
        assertFalse("引擎里不许出现第二套判定", gate.contains("RiskMatrix.classify("))
        val impl = bodyOf(src, "private val amendRiskGateImpl")
        assertTrue("真执行口不见了", impl.isNotEmpty())
        assertTrue("升档没真弹重新授权卡", impl.contains("askUser(AmendRiskCards.reauthorizeRequest("))
        assertTrue("降档没真落变更账", impl.contains("PlanPledgeBoard.noteRiskDowngrade("))
    }

    @Test
    fun `G6_降档落的是343那本变更账_不是新开第三本`() {
        val note = bodyOf(board(), "fun noteRiskDowngrade(")
        assertTrue("落账口不见了", note.isNotEmpty())
        assertTrue("没复用 343 的 appendChange（会变成第三本账）", note.contains("appendChange(row)"))
        assertTrue(
            "落账口的事件键不是 417 的降档事件",
            wiring().contains("const val EVENT = \"amend_risk_downgrade\""),
        )
    }

    @Test
    fun `G7_360的透传口原样还在_本单没改它的接线`() {
        val src = engine()
        assertTrue(
            "360 的 onAmend 接线被本单覆盖了（分工：它管透传，本单管档位跟着变）",
            src.contains("onAmend = { requirement -> reissueOrderForAmend(requirement) }"),
        )
        assertTrue("360 的旧上限退场仍在", controller().contains("retireContractForAmend() +"))
    }

}
