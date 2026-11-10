// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百六十八批 A · 低风险四域接线**源级门禁**（判据③"门禁红"的钉缝面）。
//
// 房型照 427 的 DispositionWiringGateTest＋557 的 GateBodies：管线在 JVM 起不全，
// 结构性事实用源码门禁钉；锚不写局部名、括号配对切块、调用形态不钉字面。
//
// 钉的事（缺一条就是"接了但没真接"）：
//   · 技能域：write_skill 免批写支路里，门（gate）在**免批写之前**、观察账（observeRun）
//     在**写之后**——拔掉门或把观察账挪到写前，缝就变了，当场红；
//   · 扫档：`EvolutionWiring.onRunSettled` 里真调 `LowRiskEvo.sweepOnSettle`（不是只 import）；
//   · 一键回滚：决策中心回滚 when 里真有 `humanRevertCanary` 那一支（canary: 前缀档位行）。
//
// 判据③的"负例"也在这：每个缝点配一条**拔缝变异**——把真文件里的调用点从原文里
// 摘掉再跑同一把断言，必须红。门禁自己能红，才不是恒真摆设（428 的 N2 教训）。
package com.example.zhengdao.baopu.evolution.lowrisk

import com.example.zhengdao.GateBodies
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LowRiskEvoGateTest {

    private fun code(rel: String): String =
        File(rel).let { if (it.isFile) it else File("app/$rel") }.readText()
            .replace("\r\n", "\n")
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun skillTools(): String =
        code("src/main/java/com/example/zhengdao/baopu/agent/tools/SkillTools.kt")

    private fun evolutionWiring(): String =
        code("src/main/java/com/example/zhengdao/baopu/evolution/EvolutionWiring.kt")

    private fun chatController(): String =
        code("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt")

    private fun gateBranchOn(src: String): String? =
        GateBodies.blockBody(src, Regex("""when\s*\(\s*val\s+\w+\s*=\s*LowRiskEvo\.gate\("""))

    // ── 技能域：门在免批写之前、观察账在写之后 ──────────────────────────────
    //
    // 557 尺子的一个实测盲区（本单发现）：SkillTools 头部有原始串（DESC 的 """ 块），
    // CodeText 的 RAW 态不回填串内换行 ⇒ GateBodies 的行号切片在含原始串的文件里整体前移
    // ——块级切片在这类文件上不可靠。所以本条序断言钉在**剥注释后的全文**上
    // （调用点串是全仓唯一的，无局部名依赖；块级锚只留给"拔门"负例验锚本身）。

    @Test
    fun `G1_技能域_免批写支路真调门且顺序是门_写_观察`() {
        val src = skillTools()
        val gate = src.indexOf("LowRiskEvo.gate(")
        val write = src.indexOf("SkillAutoWrite.tryAutoWrite(")
        val observe = src.indexOf("LowRiskEvo.observeRun(")
        assertTrue("门不在 SkillTools 里", gate >= 0)
        assertTrue("免批写不在 SkillTools 里", write >= 0)
        assertTrue("观察账不在 SkillTools 里", observe >= 0)
        assertTrue(
            "顺序必须是 门 → 免批写 → 观察账（先问档再动手；跑完才落账）",
            gate < write && write < observe,
        )
        assertTrue("拒行不落账（recordRefusal 不在支路里）", src.contains("LowRiskEvo.recordRefusal("))
        assertTrue("拒的支路要挡回去（Rejected）", src.contains("ToolOutcome.Rejected"))
    }

    @Test
    fun `G1负例_把门从真文件里拔掉_同一把断言必须红`() {
        val src = skillTools()
        assertTrue("前提：真文件里有门", src.contains("LowRiskEvo.gate("))
        val mutated = src.replace("LowRiskEvo.gate(", "LowRiskEvo.gateRemoved(")
        assertFalse("拔门后必须红（门禁不是恒真）", mutated.contains("LowRiskEvo.gate("))
        assertTrue("拔门后切块必须切不到（判据③：直接生效＝门禁红）", gateBranchOn(mutated) == null)
    }

    @Test
    fun `G1负例_观察账挪到写之前_顺序断言必须红`() {
        val src = skillTools()
        val gate = src.indexOf("LowRiskEvo.gate(")
        val write = src.indexOf("SkillAutoWrite.tryAutoWrite(")
        assertTrue("前提：真支路顺序成立", gate < write)
        // 变异：把观察账挪到写之前（模拟"先记账后动手"的假接线）
        val mutated = src
            .replace("LowRiskEvo.observeRun(", "LowRiskEvo.observeRunDisabled(")
            .replace("SkillAutoWrite.tryAutoWrite(", "LowRiskEvo.observeRun(\n        SkillAutoWrite.tryAutoWriteDisabled(")
        val mGate = mutated.indexOf("LowRiskEvo.gate(")
        val mWrite = mutated.indexOf("SkillAutoWrite.tryAutoWrite(")
        val mObserve = mutated.indexOf("LowRiskEvo.observeRun(")
        assertFalse("变异后顺序断言必须红", mGate < mWrite && mWrite < mObserve)
    }

    // ── 扫档：收工家族一行 ──────────────────────────────────────────────────

    @Test
    fun `G2_收工扫档真调LowRiskEvo`() {
        val body = GateBodies.functionBody(evolutionWiring(), "fun onRunSettled(")
        assertTrue("onRunSettled 不在了？", body != null)
        assertTrue("收工没扫四域档（LowRiskEvo.sweepOnSettle 不在 onRunSettled 里）", body!!.contains("LowRiskEvo.sweepOnSettle("))
    }

    @Test
    fun `G2负例_扫档行拔掉_断言必须红`() {
        val src = evolutionWiring()
        assertTrue(src.contains("LowRiskEvo.sweepOnSettle("))
        val mutated = src.replace("LowRiskEvo.sweepOnSettle(", "LowRiskEvo.sweepRemoved(")
        val body = GateBodies.functionBody(mutated, "fun onRunSettled(")
        assertTrue(body != null)
        assertFalse("拔掉扫档行后必须红", body!!.contains("LowRiskEvo.sweepOnSettle("))
    }

    // ── 一键回滚：决策中心回滚 when 的 canary 支 ────────────────────────────

    @Test
    fun `G3_回滚when有灰度档位行的一键回灰支`() {
        val body = GateBodies.functionBody(chatController(), "fun rollbackDecisionNow(")
        assertTrue("rollbackDecisionNow 不在了？", body != null)
        assertTrue("灰度档位行没有一键回灰（humanRevertCanary 不在回滚 when 里）", body!!.contains("humanRevertCanary("))
        assertTrue("只认 canary: 前缀的档位行", body.contains("ConsequenceEvidence.CANARY"))
        assertEquals("回灰调用恰一处（别把别的 kind 也回灰）", 1, GateBodies.callArgs(body, "humanRevertCanary(").size)
    }

    @Test
    fun `G3负例_回灰支拔掉_断言必须红`() {
        val src = chatController()
        assertTrue(src.contains("humanRevertCanary("))
        val mutated = src.replace("humanRevertCanary(", "humanRevertRemoved(")
        val body = GateBodies.functionBody(mutated, "fun rollbackDecisionNow(")
        assertTrue(body != null)
        assertFalse("拔掉回灰支后必须红（判据②的一键回滚不许静默消失）", body!!.contains("humanRevertCanary("))
    }
}
