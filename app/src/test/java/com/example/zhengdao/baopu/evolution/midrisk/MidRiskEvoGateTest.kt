// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百六十八批 B · 中风险域接线**源级门禁**（判据③"门禁红"的钉缝面；
// 房型照 568-A 的 LowRiskEvoGateTest：管线在 JVM 起不全，结构性事实用源码门禁钉）。
//
// 钉的事（缺一条就是"接了但没真接"）：
//   · 域①：ToolGroupGate 的未批准/同意两处真调 `MidRiskEvo.proposeThresholdShift`，
//     且生效阈值（effectiveThreshold）在提议**之前**读（限流用的值＝提议前的值）；
//   · 生效值读口：档位开着才用账上调整行（effectiveReaskThreshold 真读 currentCanaryBand）；
//   · 扫档：`EvolutionWiring.onRunSettled` 里真调 `MidRiskEvo.sweepOnSettle`（不是只 import）；
//   · 判据④：扫档里真过 589 的样本门（EvoSampleWiring.promote）；
//   · 区间：applyThreshold 里真调 intervalVerdict（出区间拒并记账的那半边）；
//   · 一键回滚：决策中心回滚 when 里真有 `threshold:` 前缀那一支（revertThreshold）。
//
// 判据③的"负例"也在这：每个缝点配一条**拔缝变异**——把真文件里的调用点从原文里
// 摘掉再跑同一把断言，必须红。门禁自己能红，才不是恒真摆设（428 的 N2 教训）。
package com.example.zhengdao.baopu.evolution.midrisk

import com.example.zhengdao.GateBodies
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MidRiskEvoGateTest {

    private fun code(rel: String): String =
        File(rel).let { if (it.isFile) it else File("app/$rel") }.readText()
            .replace("\r\n", "\n")
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun toolGroupGate(): String =
        code("src/main/java/com/example/zhengdao/baopu/agent/ToolGroupGate.kt")

    private fun evolutionWiring(): String =
        code("src/main/java/com/example/zhengdao/baopu/evolution/EvolutionWiring.kt")

    private fun midRiskEvo(): String =
        code("src/main/java/com/example/zhengdao/baopu/evolution/midrisk/MidRiskEvo.kt")

    private fun chatController(): String =
        code("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt")

    // ── 域①：组闸两处真提议，且生效阈值在提议之前读 ──────────────────────────

    @Test
    fun `G1_未批准支路真提议且生效阈值先读`() {
        val body = GateBodies.functionBody(toolGroupGate(), "fun noteDeclined(")
        assertTrue("noteDeclined 不在了？", body != null)
        val base = body!!.indexOf("effectiveThreshold()")
        val propose = body.indexOf("MidRiskEvo.proposeThresholdShift(")
        assertTrue("提议不在未批准支路里（造了不接＝打回）", propose >= 0)
        assertTrue(
            "顺序必须是 生效阈值 → 限流 → 提议（限流用的值＝提议前的值）",
            base >= 0 && base < propose,
        )
    }

    @Test
    fun `G1负例_提议从真文件里拔掉_同一把断言必须红`() {
        val src = toolGroupGate()
        assertTrue("前提：真文件里有提议", src.contains("MidRiskEvo.proposeThresholdShift("))
        val mutated = src.replace("MidRiskEvo.proposeThresholdShift(", "MidRiskEvo.proposeRemoved(")
        val body = GateBodies.functionBody(mutated, "fun noteDeclined(")
        assertTrue(body != null)
        assertFalse("拔掉提议后必须红（判据③：自调直接生效＝门禁红）", body!!.contains("MidRiskEvo.proposeThresholdShift("))
    }

    @Test
    fun `G1b_同意支路也真提议_区间自调双向都在`() {
        val body = GateBodies.functionBody(toolGroupGate(), "fun noteApproved(")
        assertTrue("noteApproved 不在了？", body != null)
        assertTrue("同意支路没有提议（自调只剩单向＝假自调）", body!!.contains("MidRiskEvo.proposeThresholdShift("))
        assertTrue("无人应答支路同向提议", GateBodies.functionBody(toolGroupGate(), "fun noteAbandoned(")!!
            .contains("MidRiskEvo.proposeThresholdShift("))
    }

    // ── 生效值读口：档位开着才用账上调整行 ──────────────────────────────────

    @Test
    fun `G1c_生效值读口先问档再读调整行`() {
        val body = GateBodies.functionBody(midRiskEvo(), "fun effectiveReaskThreshold(")
        assertTrue("effectiveReaskThreshold 不在了？", body != null)
        val band = body!!.indexOf("currentCanaryBand(")
        val row = body.indexOf("currentThreshold(")
        assertTrue("读口不读档位（调整行绕过档位＝直接生效）", band >= 0)
        assertTrue("读口不读调整行", row >= 0)
        assertTrue("顺序必须是 先问档 → 再读行（OFF 收权）", band < row)
    }

    // ── 扫档：收工家族一行 ＋ 样本门真在扫档里 ────────────────────────────────

    @Test
    fun `G2_收工扫档真调MidRiskEvo`() {
        val body = GateBodies.functionBody(evolutionWiring(), "fun onRunSettled(")
        assertTrue("onRunSettled 不在了？", body != null)
        assertTrue("收工没扫中风险域档（MidRiskEvo.sweepOnSettle 不在 onRunSettled 里）", body!!.contains("MidRiskEvo.sweepOnSettle("))
    }

    @Test
    fun `G2负例_扫档行拔掉_断言必须红`() {
        val src = evolutionWiring()
        assertTrue(src.contains("MidRiskEvo.sweepOnSettle("))
        val mutated = src.replace("MidRiskEvo.sweepOnSettle(", "MidRiskEvo.sweepRemoved(")
        val body = GateBodies.functionBody(mutated, "fun onRunSettled(")
        assertTrue(body != null)
        assertFalse("拔掉扫档行后必须红", body!!.contains("MidRiskEvo.sweepOnSettle("))
    }

    @Test
    fun `G4_扫档真过589样本门_拒了压住升档方向`() {
        val body = GateBodies.functionBody(midRiskEvo(), "fun sweepOnSettle(")
        assertTrue("sweepOnSettle 不在了？", body != null)
        val promote = body!!.indexOf("EvoSampleWiring.promote(")
        val decide = body.indexOf("decideCanaryBand(")
        assertTrue("扫档没过样本门（样本不足也转正＝判据④红）", promote >= 0)
        assertTrue("样本门要在梯子之前（拒了才压得住）", promote < decide)
    }

    @Test
    fun `G4负例_样本门从扫档里拔掉_断言必须红`() {
        val src = midRiskEvo()
        val mutated = src.replace("EvoSampleWiring.promote(", "EvoSampleWiring.promoteRemoved(")
        val body = GateBodies.functionBody(mutated, "fun sweepOnSettle(")
        assertTrue(body != null)
        assertFalse("拔掉样本门后必须红（判据④：样本不足不许转正）", body!!.contains("EvoSampleWiring.promote("))
    }

    // ── 区间：applyThreshold 里真调 intervalVerdict，出区间先拒 ──────────────

    @Test
    fun `G5_区间终判在落行之前_拒并记账`() {
        val body = GateBodies.functionBody(midRiskEvo(), "private fun applyThreshold(")
        assertTrue("applyThreshold 不在了？", body != null)
        val verdict = body!!.indexOf("intervalVerdict(")
        val refuse = body.indexOf("recordAdvisor(")
        val apply = body.indexOf("applyThresholdChange(")
        val observe = body.indexOf("LowRiskEvo.observeRun(")
        assertTrue("区间终判不在落行路径里", verdict >= 0)
        assertTrue(
            "顺序必须是 区间终判 → (出区间记账/区间内落行) → 观察",
            verdict < apply && apply < observe && verdict < refuse,
        )
    }

    @Test
    fun `G5负例_区间终判拔掉_断言必须红`() {
        val src = midRiskEvo()
        val mutated = src.replace("val outOfInterval = intervalVerdict(proposed)", "val outOfInterval: String? = null")
        val body = GateBodies.functionBody(mutated, "private fun applyThreshold(")
        assertTrue(body != null)
        assertFalse("拔掉区间终判后必须红（阈值越已定区间＝红）", body!!.contains("intervalVerdict(proposed)"))
    }

    // ── 一键回滚：决策中心回滚 when 的 threshold 支 ──────────────────────────

    @Test
    fun `G3_回滚when有阈值行支_真调revertThreshold`() {
        val body = GateBodies.functionBody(chatController(), "fun rollbackDecisionNow(")
        assertTrue("rollbackDecisionNow 不在了？", body != null)
        assertTrue("阈值行没有回滚通道（revertThreshold 不在回滚 when 里）", body!!.contains("MidRiskEvo.revertThreshold("))
        assertTrue("只认 threshold: 前缀的调整行", body.contains("ConsequenceEvidence.THRESHOLD"))
    }

    @Test
    fun `G3负例_阈值回滚支拔掉_断言必须红`() {
        val src = chatController()
        assertTrue(src.contains("MidRiskEvo.revertThreshold("))
        val mutated = src.replace("MidRiskEvo.revertThreshold(", "MidRiskEvo.revertRemoved(")
        val body = GateBodies.functionBody(mutated, "fun rollbackDecisionNow(")
        assertTrue(body != null)
        assertFalse("拔掉阈值回滚支后必须红（判据②的一键回滚不许静默消失）", body!!.contains("MidRiskEvo.revertThreshold("))
    }

    // ── 域②：纠正出口在判档落账之后真调门，装配层真注入 ─────────────────────

    private fun mediatorWiring(): String =
        code("../policy/src/main/java/com/example/zhengdao/baopu/MediatorWiring.kt")

    private fun baopuRuntime(): String =
        code("src/main/java/com/example/zhengdao/baopu/BaopuRuntime.kt")

    @Test
    fun `G7_纠正出口在判档落账之后_候选不回流判档`() {
        val body = GateBodies.functionBody(mediatorWiring(), "fun onUserTurn(")
        assertTrue("onUserTurn 不在了？", body != null)
        val line = body!!.indexOf("companionLine(")
        val sink = body.indexOf("companionRuleSink")
        val judge = body.indexOf("CompanionJudge.judge(")
        assertTrue("候选出口不在纠正支路里（造了不接＝打回）", sink >= 0)
        assertTrue(
            "顺序必须是 判档 → 落账 → 候选出口（出口只搬运结论）",
            judge >= 0 && judge < line && line < sink,
        )
        assertTrue("出口判 correction 非空才交（自动换档不是纠正）", body.contains("correction != null"))
    }

    @Test
    fun `G7负例_候选出口从真文件里拔掉_同一把断言必须红`() {
        val src = mediatorWiring()
        assertTrue("前提：真文件里有候选出口", src.contains("companionRuleSink"))
        val mutated = src.replace("companionRuleSink", "companionSinkGone")
        val body = GateBodies.functionBody(mutated, "fun onUserTurn(")
        assertTrue(body != null)
        assertFalse("拔掉出口后必须红（判据⑤：生产调用点必须在）", body!!.contains("companionRuleSink"))
    }

    @Test
    fun `G8_装配层真把纠正出口接到中风险门`() {
        val src = baopuRuntime()
        assertTrue("装配层没注入候选出口（sink 没人听＝假接线）", src.contains("MediatorWiring.companionRuleSink ="))
        val wiring = src.indexOf("MediatorWiring.companionRuleSink =")
        val call = src.indexOf("MidRiskEvo.onCompanionCorrection(")
        assertTrue("出口没接到中风险门", call > wiring)
    }

    @Test
    fun `G8负例_装配层注入拔掉_断言必须红`() {
        val src = baopuRuntime()
        assertTrue(src.contains("MediatorWiring.companionRuleSink ="))
        val mutated = src.replace("MediatorWiring.companionRuleSink =", "MediatorWiring.companionRuleSinkDisabled =")
        assertFalse("拔掉装配注入后必须红（出口无人听＝假接线）", mutated.contains("MediatorWiring.companionRuleSink ="))
    }

    // ── 区间单一真相：中风险门不自带第二个区间 ────────────────────────────────

    @Test
    fun `G6_区间常量只引用Policy的拍板值_不另立第二个区间`() {
        val src = midRiskEvo()
        assertTrue("区间要引用 Policy 的拍板常量", src.contains("Policy.GROUP_REASK_MIN") && src.contains("Policy.GROUP_REASK_MAX"))
        assertFalse("不许另立区间字面量（1–10 的第二处真相＝漂移入口）", Regex("""in\s+\d+\.\.\d+""").containsMatchIn(src))
    }
}
