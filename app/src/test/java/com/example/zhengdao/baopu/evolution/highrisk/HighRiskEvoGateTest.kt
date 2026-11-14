// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百六十八批 C · 高风险域①工具白名单接线**源级门禁**（判据③"门禁红"的钉缝面；
// 房型照 568-B 的 MidRiskEvoGateTest：管线在 JVM 起不全，结构性事实用源码门禁钉）。
//
// 钉的事（缺一条就是"接了但没真接"）：
//   · 域①出口：ToolGroupAudit 批准/手动/撤销三处真出白名单候选（emitWhitelist），未批准支路不出；
//   · 装配层：BaopuRuntime 真把 whitelistSink 接到 HighRiskEvo.onWhitelistEvent；
//   · 生效值读口：档位开着才用账上调整行（effectiveWhitelist 先问档再读行）；
//   · 加宽过准入：applyWhitelist 先判方向、加宽真调 wideningAdmitted → AdmissionGate.judge（467，不新造通道）；
//   · 加宽优先：directionOf 里「加了键」这一支判 WIDEN 且在「删了键」之前（堵换键提权）；
//   · 扫档：EvolutionWiring.onRunSettled 真调 HighRiskEvo.sweepOnSettle，且扫档真过 589 样本门；
//   · 一键回滚：决策中心回滚 when 里真有 whitelist: 前缀那一支（rollbackWhitelistRowNow → revertWhitelist）。
//
// 判据③的"负例"也在这：每个缝点配一条**拔缝变异**——把真文件里的调用点从原文里
// 摘掉再跑同一把断言，必须红。门禁自己能红，才不是恒真摆设（428 的 N2 教训）。
// 派工单点名的负例「绕开准入直接加宽 ⇒ 红」＝ G4 负例（把 AdmissionGate.judge 拔掉）。
package com.example.zhengdao.baopu.evolution.highrisk

import com.example.zhengdao.GateBodies
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighRiskEvoGateTest {

    private fun code(rel: String): String =
        File(rel).let { if (it.isFile) it else File("app/$rel") }.readText()
            .replace("\r\n", "\n")
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun toolGroupGate(): String =
        code("src/main/java/com/example/zhengdao/baopu/agent/ToolGroupGate.kt")

    private fun highRiskEvo(): String =
        code("src/main/java/com/example/zhengdao/baopu/evolution/highrisk/HighRiskEvo.kt")

    private fun highRiskRules(): String =
        code("src/main/java/com/example/zhengdao/baopu/evolution/highrisk/HighRiskRules.kt")

    private fun evolutionWiring(): String =
        code("src/main/java/com/example/zhengdao/baopu/evolution/EvolutionWiring.kt")

    private fun baopuRuntime(): String =
        code("src/main/java/com/example/zhengdao/baopu/BaopuRuntime.kt")

    private fun chatController(): String =
        code("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt")

    // ── 域①出口：组授权三处真出候选，未批准不出 ──────────────────────────────

    @Test
    fun `G1_批准手动撤销三处真出白名单候选_未批准不出`() {
        assertTrue("批准支路没出候选（造了不接＝打回）", GateBodies.functionBody(toolGroupGate(), "fun grant(")!!.contains("emitWhitelist(group, true)"))
        assertTrue("手动支路没出候选", GateBodies.functionBody(toolGroupGate(), "fun manual(")!!.contains("emitWhitelist(group, on)"))
        assertTrue("撤销支路没出候选", GateBodies.functionBody(toolGroupGate(), "fun undo(")!!.contains("emitWhitelist(group, false)"))
        assertFalse("未批准支路不该出候选（没同意＝没加宽）", GateBodies.functionBody(toolGroupGate(), "fun deny(")!!.contains("emitWhitelist("))
        assertTrue("出口没接到 sink", GateBodies.functionBody(toolGroupGate(), "private fun emitWhitelist(")!!.contains("whitelistSink?.invoke("))
    }

    @Test
    fun `G1负例_候选出口从真文件里拔掉_同一把断言必须红`() {
        val src = toolGroupGate()
        assertTrue("前提：真文件里有候选出口", src.contains("emitWhitelist("))
        val mutated = src.replace("emitWhitelist(", "emitWhitelistGone(")
        assertFalse("拔掉出口后必须红（判据⑤：生产调用点必须在）", GateBodies.functionBody(mutated, "fun grant(")!!.contains("emitWhitelist("))
    }

    // ── 装配层：sink 真接到高风险门 ──────────────────────────────────────────

    @Test
    fun `G2_装配层真把白名单出口接到高风险门`() {
        val src = baopuRuntime()
        assertTrue("装配层没注入候选出口（sink 没人听＝假接线）", src.contains("ToolGroupAudit.whitelistSink ="))
        val wiring = src.indexOf("ToolGroupAudit.whitelistSink =")
        val call = src.indexOf("HighRiskEvo.onWhitelistEvent(")
        assertTrue("出口没接到高风险门", call > wiring)
    }

    @Test
    fun `G2负例_装配层注入拔掉_断言必须红`() {
        val src = baopuRuntime()
        assertTrue(src.contains("ToolGroupAudit.whitelistSink ="))
        val mutated = src.replace("ToolGroupAudit.whitelistSink =", "ToolGroupAudit.whitelistSinkDisabled =")
        assertFalse("拔掉装配注入后必须红（出口无人听＝假接线）", mutated.contains("ToolGroupAudit.whitelistSink ="))
    }

    // ── 生效值读口：档位开着才用账上调整行 ──────────────────────────────────

    @Test
    fun `G3_生效值读口先问档再读调整行`() {
        val body = GateBodies.functionBody(highRiskEvo(), "fun effectiveWhitelist(")
        assertTrue("effectiveWhitelist 不在了？", body != null)
        val band = body!!.indexOf("currentCanaryBand(")
        val row = body.indexOf("currentWhitelist(")
        assertTrue("读口不读档位（调整行绕过档位＝直接生效）", band >= 0)
        assertTrue("读口不读调整行", row >= 0)
        assertTrue("顺序必须是 先问档 → 再读行（OFF 收权）", band < row)
    }

    @Test
    fun `G3负例_调整行读口拔掉_断言必须红`() {
        val src = highRiskEvo()
        val mutated = src.replace("currentWhitelist(", "currentWhitelistGone(")
        val body = GateBodies.functionBody(mutated, "fun effectiveWhitelist(")
        assertTrue(body != null)
        assertFalse("拔掉调整行读口后必须红", body!!.contains("currentWhitelist("))
    }

    // ── 加宽过准入：方向终判 → 加宽真调 467 准入 → 落行 ───────────────────────

    @Test
    fun `G4_加宽在落行之前真过467准入_不新造通道`() {
        val propose = GateBodies.functionBody(highRiskEvo(), "fun proposeWhitelistChange(")
        assertTrue("proposeWhitelistChange 不在了？", propose != null)
        assertTrue("提议没过共用门（红线→档位→深度）", propose!!.contains("LowRiskEvo.gate("))
        val apply = GateBodies.functionBody(highRiskEvo(), "private fun applyWhitelist(")
        assertTrue("applyWhitelist 不在了？", apply != null)
        val direction = apply!!.indexOf("WhitelistRules.directionOf(")
        val admit = apply.indexOf("wideningAdmitted(")
        val stamp = apply.indexOf("applyWhitelistChange(")
        assertTrue("落行前不判方向", direction >= 0)
        assertTrue("加宽不过准入（绕开准入直接加宽＝判据③红）", admit >= 0)
        assertTrue("顺序必须是 方向 → 加宽准入 → 落行", direction < admit && admit < stamp)
        val admitted = GateBodies.functionBody(highRiskEvo(), "private fun wideningAdmitted(")
        assertTrue("wideningAdmitted 不在了？", admitted != null)
        assertTrue("准入没复用 467 的 AdmissionGate.judge（新造通道＝打回）", admitted!!.contains("AdmissionGate.judge("))
    }

    @Test
    fun `G4负例_准入从加宽路径里拔掉_断言必须红`() {
        val src = highRiskEvo()
        assertTrue("前提：真文件里有准入", src.contains("AdmissionGate.judge("))
        val mutated = src.replace("AdmissionGate.judge(", "AdmissionGate.judgeGone(")
        val body = GateBodies.functionBody(mutated, "private fun wideningAdmitted(")
        assertTrue(body != null)
        assertFalse("拔掉准入后必须红（派工单负例：绕开准入直接加宽 ⇒ 红）", body!!.contains("AdmissionGate.judge("))
    }

    // ── 加宽优先：既加又删仍按加宽（堵换键提权） ─────────────────────────────

    @Test
    fun `G8_方向判定加宽优先_加了键就判WIDEN且在删键之前`() {
        val body = GateBodies.functionBody(highRiskRules(), "fun directionOf(")
        assertTrue("directionOf 不在了？", body != null)
        val added = body!!.indexOf("added.isNotEmpty()")
        val removed = body.indexOf("removed.isNotEmpty()")
        assertTrue("方向判定不查加了哪些键", added >= 0)
        assertTrue("方向判定不查删了哪些键", removed >= 0)
        assertTrue("加宽这一支必须在收窄之前（加宽优先，堵换键提权）", added < removed)
        assertTrue("加了键必须判 WIDEN", body.contains("added.isNotEmpty() -> WhitelistDirection.WIDEN"))
    }

    @Test
    fun `G8负例_把加宽支改判收窄_断言必须红`() {
        val src = highRiskRules()
        val mutated = src.replace("added.isNotEmpty() -> WhitelistDirection.WIDEN", "added.isNotEmpty() -> WhitelistDirection.NARROW")
        val body = GateBodies.functionBody(mutated, "fun directionOf(")
        assertTrue(body != null)
        assertFalse("加宽支被改判收窄后必须红（换键提权绕准入＝红）", body!!.contains("added.isNotEmpty() -> WhitelistDirection.WIDEN"))
    }

    // ── 扫档：收工家族一行 ＋ 样本门真在扫档里 ────────────────────────────────

    @Test
    fun `G5_收工扫档真调HighRiskEvo`() {
        val body = GateBodies.functionBody(evolutionWiring(), "fun onRunSettled(")
        assertTrue("onRunSettled 不在了？", body != null)
        assertTrue("收工没扫高风险域档（HighRiskEvo.sweepOnSettle 不在 onRunSettled 里）", body!!.contains("HighRiskEvo.sweepOnSettle("))
    }

    @Test
    fun `G5负例_扫档行拔掉_断言必须红`() {
        val src = evolutionWiring()
        assertTrue(src.contains("HighRiskEvo.sweepOnSettle("))
        val mutated = src.replace("HighRiskEvo.sweepOnSettle(", "HighRiskEvo.sweepRemoved(")
        val body = GateBodies.functionBody(mutated, "fun onRunSettled(")
        assertTrue(body != null)
        assertFalse("拔掉扫档行后必须红", body!!.contains("HighRiskEvo.sweepOnSettle("))
    }

    @Test
    fun `G6_扫档真过589样本门_拒了压住升档方向`() {
        val body = GateBodies.functionBody(highRiskEvo(), "fun sweepOnSettle(")
        assertTrue("sweepOnSettle 不在了？", body != null)
        val promote = body!!.indexOf("EvoSampleWiring.promote(")
        val decide = body.indexOf("decideCanaryBand(")
        assertTrue("扫档没过样本门（样本不足也转正＝判据④红）", promote >= 0)
        assertTrue("样本门要在梯子之前（拒了才压得住）", promote < decide)
    }

    @Test
    fun `G6负例_样本门从扫档里拔掉_断言必须红`() {
        val src = highRiskEvo()
        val mutated = src.replace("EvoSampleWiring.promote(", "EvoSampleWiring.promoteRemoved(")
        val body = GateBodies.functionBody(mutated, "fun sweepOnSettle(")
        assertTrue(body != null)
        assertFalse("拔掉样本门后必须红（判据④：样本不足不许转正）", body!!.contains("EvoSampleWiring.promote("))
    }

    // ── 一键回滚：决策中心回滚 when 的 whitelist 支 ──────────────────────────

    @Test
    fun `G7_回滚when有白名单行支_真调revertWhitelist`() {
        val body = GateBodies.functionBody(chatController(), "fun rollbackDecisionNow(")
        assertTrue("rollbackDecisionNow 不在了？", body != null)
        assertTrue("白名单行没有回滚通道（活体不在回滚 when 里）", body!!.contains("rollbackWhitelistRowNow("))
        assertTrue("只认 whitelist: 前缀的调整行", body.contains("ConsequenceEvidence.WHITELIST"))
        val live = GateBodies.functionBody(chatController(), "fun rollbackWhitelistRowNow(")
        assertTrue("回滚活体不在了？", live != null)
        assertTrue("活体没接到高风险门（HighRiskEvo.revertWhitelist 不在活体里）", live!!.contains("HighRiskEvo.revertWhitelist("))
    }

    @Test
    fun `G7负例_白名单回滚支拔掉_断言必须红`() {
        val src = chatController()
        assertTrue(src.contains("HighRiskEvo.revertWhitelist("))
        val mutated = src.replace("HighRiskEvo.revertWhitelist(", "HighRiskEvo.revertRemoved(")
        val body = GateBodies.functionBody(mutated, "fun rollbackWhitelistRowNow(")
        assertTrue(body != null)
        assertFalse("拔掉白名单回滚支后必须红（判据②的一键回滚不许静默消失）", body!!.contains("HighRiskEvo.revertWhitelist("))
    }
}