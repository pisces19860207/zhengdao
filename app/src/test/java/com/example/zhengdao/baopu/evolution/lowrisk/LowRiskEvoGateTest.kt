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
//
// 第六百零五批：撤掉「绕开块级切片」的绕行。568 当时在 SkillTools 上切不准块
// （587 已修：RAW 态回填串内换行 ⇒ code 空间与原文逐行对齐），只能把 G1 的序断言
// 改钉「剥注释后的全文 indexOf」。本批 G1 回到块级切片，形态从「全文」收到「块内」，
// 判据反而更严（见 G1 段注释与判据③负例）。
package com.example.zhengdao.baopu.evolution.lowrisk

import com.example.zhengdao.GateBodies
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    private companion object {
        /** 门切不到时 G1 的那条理由：三处负例都断言到**这一条**上，防止"红在别的理由"。 */
        const val NO_BRANCH = "门不在 `when (val v = LowRiskEvo.gate(…))` 支路里"
    }

    // ── 技能域：门在免批写之前、观察账在写之后 ──────────────────────────────
    //
    // 587 已修（RAW 态回填串内换行），此处用块级切片：SkillTools 头部虽有跨行原始串
    // （DESC 的 """ 块），GateBodies 仍能按 code 空间行号切回原文——568 当时因尺子整体
    // 前移改钉「剥注释后的全文」，那条绕行本单撤掉。
    // 块内比全文严在三处：①行尾注释里写 `LowRiskEvo.gate(` 不算调用（旧尺子只剥整行注释，
    // 会被它骗过）；②同名调用长在别的函数里，也缝不到这条断言上；③顺序只在免批写支路内比。

    /** 免批写支路（`runAction`）：块级切片，不拿全文 indexOf 当尺子。 */
    private fun autoWriteBody(src: String): String? =
        GateBodies.functionBody(src, "fun runAction(")

    /**
     * G1 的唯一一把尺：全部成立返回 null，否则返回第一条不成立的理由。
     * 真跑与四条变异共用这一份判据——两把尺会漂移（557 的教训；428 的 N2 同型）。
     */
    private fun g1Failure(src: String): String? {
        val body = autoWriteBody(src) ?: return "免批写支路（runAction）切不到"
        // 切片准不准先看首末行：587 的毛病就是整体前移／被伪括号提前截断，
        // 那时首行会变成别的行、末行也不再是真闭括号（只判 contains 会放过这种偏）。
        val head = body.lines().first().trim()
        if (!head.startsWith("private fun runAction(")) return "免批写支路切片首行不对（整体前移？）：$head"
        if (body.lines().last().trim() != "}") return "免批写支路切片末行不是真闭括号（被伪括号截断？）"
        val branch = gateBranchOn(src) ?: return NO_BRANCH
        if (!branch.lines().first().trim().startsWith("when (val")) return "when 支路切片首行不对：${branch.lines().first()}"
        if (!branch.contains("LowRiskEvo.gate(")) return "门不在 when 支路里"
        if (!branch.contains("LowRiskEvo.recordRefusal(")) return "红线拒没记账（recordRefusal 不在支路里）"
        if (branch.split("return ToolOutcome.Rejected").size - 1 != 2) {
            return "拒的两支（红线／灰度不到）必须各自 return 挡回去"
        }
        val gate = body.indexOf("LowRiskEvo.gate(")
        val write = body.indexOf("SkillAutoWrite.tryAutoWrite(")
        val observe = body.indexOf("LowRiskEvo.observeRun(")
        if (gate < 0) return "门不在免批写支路里"
        if (write < 0) return "免批写不在免批写支路里"
        if (observe < 0) return "观察账不在免批写支路里"
        if (!(gate < write && write < observe)) return "顺序不是 门 → 免批写 → 观察账"
        return null
    }

    @Test
    fun `G1_技能域_免批写支路真调门且顺序是门_写_观察`() {
        val src = skillTools()
        val why = g1Failure(src)
        assertTrue("G1 判据不过（块级切片：SkillTools 含跨行原始串也要切得准）：$why", why == null)
        assertTrue("拒行不落账（recordRefusal 只记账）", src.contains("LowRiskEvo.recordRefusal("))
        assertTrue("拒的支路要挡回去（Rejected）", src.contains("ToolOutcome.Rejected"))
    }

    @Test
    fun `G1负例_把门从真文件里拔掉_同一把尺必须红`() {
        val src = skillTools()
        assertTrue("前提：真文件里有门", src.contains("LowRiskEvo.gate("))
        val mutated = src.replace("LowRiskEvo.gate(", "LowRiskEvo.gateRemoved(")
        assertFalse("拔门后门字面量应当消失", mutated.contains("LowRiskEvo.gate("))
        assertTrue("拔门后块级锚必须切不到（判据③：直接生效＝门禁红）", gateBranchOn(mutated) == null)
        assertEquals("拔门必须红在「切不到支路」这一条上", NO_BRANCH, g1Failure(mutated))
    }

    /** 判据②之一：门挪到 `when` 分支之外（条件表达式不再是门）⇒ 块级锚必须切不到。 */
    @Test
    fun `G1变异_门挪到when分支之外_块级锚必须切不到`() {
        val header =
            "when (val v = LowRiskEvo.gate(CanaryCapability.REFLECTION_AUTO_EDIT, evoCandidate)) {"
        val mutated = skillTools().replace(
            header,
            "val v = LowRiskEvo.gate(CanaryCapability.REFLECTION_AUTO_EDIT, evoCandidate)\n" +
                "                when (v) {",
        )
        assertTrue("前提：门调用本身还在（只是不在 when 头里）", mutated.contains("LowRiskEvo.gate("))
        assertTrue("门出了 when 头 ⇒ 块级锚必须切不到", gateBranchOn(mutated) == null)
        assertEquals("门出 when 头必须红在「切不到支路」这一条上", NO_BRANCH, g1Failure(mutated))
    }

    /** 判据②之二：整段门支路搬到免批写之后（真搬：拿尺子切下来的那段原文剪贴）⇒ 顺序断言必须红。 */
    @Test
    fun `G1变异_门挪到免批写之后_顺序断言必须红`() {
        val src = skillTools()
        val branch = gateBranchOn(src)
        assertNotNull("前提：门支路切得到", branch)
        val writeLine = "val a = SkillAutoWrite.tryAutoWrite(proposal, root, globalRoot, pf, stamp())"
        val moved = src.replace(branch!!, "").replace(writeLine, writeLine + "\n" + branch)
        assertTrue("前提：门还在（换了个位置）", moved.contains("LowRiskEvo.gate("))
        assertNotNull("块级锚仍切得到——本变异要红在顺序上，不是红在锚上", gateBranchOn(moved))
        assertEquals("顺序变了 ⇒ 同一把尺必须红在顺序上", "顺序不是 门 → 免批写 → 观察账", g1Failure(moved))
    }

    /**
     * 判据③：门只写在**行尾注释**里 ⇒ 不许被当成调用。
     *
     * 这条正是「块内 vs 全文」的分水岭：`code()` 只剥**整行**的双斜杠注释（行首空白之后才算），
     * 行尾注释原样留着 ⇒ 旧的「剥注释后的全文 indexOf」会被它骗过（真调用已经没了，
     * 旧尺子仍报有门）；GateBodies 走 CodeText 词法，行尾注释一并清掉 ⇒ 切不到。
     */
    @Test
    fun `G1负例_门只写在行尾注释里_不许被当成调用`() {
        val header =
            "when (val v = LowRiskEvo.gate(CanaryCapability.REFLECTION_AUTO_EDIT, evoCandidate)) {"
        val mutated = skillTools().replace(header, "when (val v = LowRiskVerdict.Allow) { // LowRiskEvo.gate(")
        assertTrue(
            "前提：字面量还在行尾注释里（旧尺子这一行会判「有门」＝被骗）",
            mutated.indexOf("LowRiskEvo.gate(") >= 0,
        )
        assertTrue("块级切片不许把注释当调用", gateBranchOn(mutated) == null)
        assertEquals("注释里的名字必须红在「切不到支路」这一条上", NO_BRANCH, g1Failure(mutated))
    }

    /** 顺序的另一条边：观察账挪到免批写之前（「先记账后动手」的假接线）⇒ 顺序断言必须红。 */
    @Test
    fun `G1负例_观察账挪到写之前_顺序断言必须红`() {
        val src = skillTools()
        val call = GateBodies.callArgs(src, "LowRiskEvo.observeRun(").single()
        val writeLine = "val a = SkillAutoWrite.tryAutoWrite(proposal, root, globalRoot, pf, stamp())"
        val mutated = src.replace(call, "").replace(writeLine, call + "\n" + writeLine)
        assertTrue("前提：观察账调用还在（换了个位置）", mutated.contains("LowRiskEvo.observeRun("))
        assertEquals("顺序变了 ⇒ 同一把尺必须红在顺序上", "顺序不是 门 → 免批写 → 观察账", g1Failure(mutated))
    }

    // ── 提示词尾部：注入点先问档、新内容落观察账 ────────────────────────────
    //
    // RunEngine 的调用点已有既有源级钉（CriteriaPitPackTest「落点是system不是user」：
    // RunEngine 必须保持 `CriteriaPitPack.systemSuffix()` 原样）——所以门只许在
    // systemSuffix 内部，RunEngine 一行不动。587 已修 RAW 回填 ⇒ GateBodies 照切，
    // 不再靠「这份文件碰巧没有原始串」这个前提。

    private fun pitPack(): String =
        code("src/main/java/com/example/zhengdao/baopu/CriteriaPitPack.kt")

    @Test
    fun `G4_提示词尾部_systemSuffix先问档_新内容落观察账_关闭不发`() {
        val body = GateBodies.functionBody(pitPack(), "fun systemSuffix(")
        assertTrue("systemSuffix 不在了？", body != null)
        val gate = body!!.indexOf("LowRiskEvo.gate(")
        val input = body.indexOf("val input = snapshot()")
        val observe = body.indexOf("LowRiskEvo.observeRun(")
        assertTrue("门不在注入点里", gate >= 0)
        assertTrue("观察账不在注入点里", observe >= 0)
        assertTrue("顺序必须是 门 → 取数渲染 → 观察账（档关闭时不取数不记观察）", gate < input && input < observe)
        assertTrue("档关闭不发（返回空串）", body.contains("return \"\""))
        assertTrue("红线拒要记账", body.contains("LowRiskEvo.recordRefusal("))
    }

    @Test
    fun `G4负例_把门从注入点拔掉_同一把断言必须红`() {
        val src = pitPack()
        assertTrue("前提：注入点里有门", src.contains("LowRiskEvo.gate("))
        val mutated = src.replace("LowRiskEvo.gate(", "LowRiskEvo.gateRemoved(")
        val body = GateBodies.functionBody(mutated, "fun systemSuffix(")
        assertTrue(body != null)
        assertFalse("拔门后必须红（判据③：尾段直接生效＝门禁红）", body!!.contains("LowRiskEvo.gate("))
    }

    // ── 记忆组织：自动收编先问档、结局落观察账 ──────────────────────────────

    private fun memoryWiring(): String =
        code("src/main/java/com/example/zhengdao/baopu/memoryimport/FreeTextMemoryWiring.kt")

    @Test
    fun `G5_记忆组织_autoRun先问档_结局落观察_手动入口不受档`() {
        val body = GateBodies.functionBody(memoryWiring(), "fun autoRun(")
        assertTrue("autoRun 不在了？", body != null)
        val gate = body!!.indexOf("LowRiskEvo.gate(")
        val run = body.indexOf("runOnce(appCtx, TRIGGER_COLD_START)")
        val observe = body.indexOf("LowRiskEvo.observeRun(")
        assertTrue("门不在 autoRun 里", gate >= 0)
        assertTrue("观察账不在 autoRun 里", observe >= 0)
        assertTrue("顺序必须是 门 → 收编 → 观察账", gate < run && run < observe)
        assertTrue("红线拒要记账", body.contains("LowRiskEvo.recordRefusal("))
        // 手动广播（runNow→runOnce）不问档：档位管自动化，不管人的手
        val manual = GateBodies.functionBody(memoryWiring(), "fun runNow(")
        assertTrue("runNow 不在了？", manual != null)
        assertFalse("手动入口不许被档位管", manual!!.contains("LowRiskEvo.gate("))
    }

    @Test
    fun `G5负例_把门从autoRun拔掉_同一把断言必须红`() {
        val src = memoryWiring()
        assertTrue("前提：autoRun 里有门", src.contains("LowRiskEvo.gate("))
        val mutated = src.replace("LowRiskEvo.gate(", "LowRiskEvo.gateRemoved(")
        val body = GateBodies.functionBody(mutated, "fun autoRun(")
        assertTrue(body != null)
        assertFalse("拔门后必须红（判据③：收编直接生效＝门禁红）", body!!.contains("LowRiskEvo.gate("))
    }

    // ── 调度降频：自动档先问档（急停在前）、跑完落观察账 ────────────────────

    private fun nocturnalAuto(): String =
        code("src/main/java/com/example/zhengdao/baopu/inspect/NocturnalAuto.kt")

    @Test
    fun `G6_调度降频_runDue档位在急停之后机会条件之前_跑完落观察_手动不受档`() {
        val body = GateBodies.functionBody(nocturnalAuto(), "fun runDue(")
        assertTrue("runDue 不在了？", body != null)
        val brake = body!!.indexOf("MaintBrake.blocked(")
        val gate = body.indexOf("LowRiskEvo.gate(")
        val opp = body.indexOf("Opportunistic.decide(")
        val observe = body.indexOf("LowRiskEvo.observeRun(")
        assertTrue("门不在 runDue 里", gate >= 0)
        assertTrue("观察账不在 runDue 里", observe >= 0)
        assertTrue(
            "顺序必须是 急停（479 不动）→ 档位 → 机会条件（511 不动）→ 观察",
            brake < gate && gate < opp && opp < observe,
        )
        assertTrue("档位只管自动（MANUAL 不问档）", body.contains("AskSource.MANUAL"))
        assertTrue("红线拒要记账", body.contains("LowRiskEvo.recordRefusal("))
    }

    @Test
    fun `G6负例_把门从runDue拔掉_同一把断言必须红`() {
        val src = nocturnalAuto()
        assertTrue("前提：runDue 里有门", src.contains("LowRiskEvo.gate("))
        val mutated = src.replace("LowRiskEvo.gate(", "LowRiskEvo.gateRemoved(")
        val body = GateBodies.functionBody(mutated, "fun runDue(")
        assertTrue(body != null)
        assertFalse("拔门后必须红（判据③：自动档直接生效＝门禁红）", body!!.contains("LowRiskEvo.gate("))
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
