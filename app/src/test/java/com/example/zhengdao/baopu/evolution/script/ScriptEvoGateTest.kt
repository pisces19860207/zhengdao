// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百四十三批 · 脚本进灰度的**源级接线门禁**（判据④⑥"接了但没真接"那一面）。
//
// 钉四件事（拔掉任何一处，"脚本→候选→灰度"就断了，当场红）：
//   G1 回放收尾（`TeachReplay.recordAcceptance`）：**先落验收账、再走候选/回滚**——
//      顺序反了，候选判据读的是空账（永远 0 次成功），链看着接上了其实一句真话都算不出来；
//   G2 技能页转正（`BaopuChatController.runSkillRowAction` 的 PROMOTE 支）：脚本转正走
//      `ScriptEvo.promote`（过 568-A 的门），不是绕过门直接改状态头；
//   G3 一键收回（`revertSkillPromoteNow`）：脚本收回走 `ScriptEvo.revoke`（脚本保留）；
//   G4 `ScriptEvo.promote` 内部：**门在写盘之前**（判据⑥：进门才转正），且本模块不自判档位/红线
//      （`CanaryBand.`/`RedLines.` 一个都不许出现——判据⑥"不改 568-A 判定逻辑"的自证）。
//
// 每条都配**拔缝负例**：把真文件里那一行摘掉/挪位再跑同一把断言，必须红（428 的 N2 教训：
// 门禁自己能红才算门禁）。
package com.example.zhengdao.baopu.evolution.script

import com.example.zhengdao.GateBodies
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptEvoGateTest {

    private fun code(rel: String): String =
        File(rel).let { if (it.isFile) it else File("app/$rel") }.readText()
            .replace("\r\n", "\n")

    private fun teachReplay() = code("src/main/java/com/example/zhengdao/baopu/TeachReplay.kt")
    private fun chatController() = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt")
    private fun scriptEvo() = code("src/main/java/com/example/zhengdao/baopu/evolution/script/ScriptEvo.kt")
    private fun lowRiskGate() = code("src/main/java/com/example/zhengdao/baopu/evolution/lowrisk/LowRiskGate.kt")
    private fun lowRiskEvo() = code("src/main/java/com/example/zhengdao/baopu/evolution/lowrisk/LowRiskEvo.kt")

    private companion object {
        const val NO_SETTLE = "回放收尾没接候选链（ScriptEvo.onReplaySettled 不在 recordAcceptance 里）"
        const val ORDER_BAD = "顺序不对：必须先是验收账（recordRun）再是候选/回滚（onReplaySettled）"
        const val NO_PROMOTE = "脚本转正没走 ScriptEvo.promote（绕过了 568-A 的门）"
        const val NO_REVOKE = "脚本收回没走 ScriptEvo.revoke（收回没记账／可能动到脚本）"
        const val NO_GATE = "转正前没问 568-A 的门（LowRiskEvo.gate 不在 promote 里）"
        const val GATE_LATE = "门在写盘之后：进没进灰度都不影响转正（判据⑥）"
        const val SELF_JUDGE = "本模块自判档位/红线（判据⑥：判定逻辑只许在 568-A）"
    }

    // ── G1：先验收账，再候选 ────────────────────────────────────────────────

    private fun g1Failure(src: String): String? {
        val body = GateBodies.functionBody(src, "fun recordAcceptance(")
            ?: return "recordAcceptance 切不到（方法被改名/挪走了？）"
        val settle = body.indexOf("ScriptEvo.onReplaySettled(")
        if (settle < 0) return NO_SETTLE
        val run = body.indexOf("TeachReplayStore.recordRun(")
        if (run < 0) return "验收账那一行不见了（recordRun 不在 recordAcceptance 里）"
        if (run > settle) return ORDER_BAD
        return null
    }

    @Test
    fun `G1_回放收尾先落验收账再走候选链`() {
        val why = g1Failure(teachReplay())
        assertTrue("G1 不过：$why", why == null)
    }

    @Test
    fun `G1负例_把候选链那一行拔掉_断言必须红`() {
        val src = teachReplay()
        assertTrue("前提：真文件里有那一行", src.contains("ScriptEvo.onReplaySettled("))
        val mutated = src.replace("ScriptEvo.onReplaySettled(", "ScriptEvo.settleRemoved(")
        assertEquals("拔缝必须红在「没接」这一条上", NO_SETTLE, g1Failure(mutated))
    }

    @Test
    fun `G1负例_把候选链挪到验收账之前_顺序断言必须红`() {
        val src = teachReplay()
        val call = GateBodies.callArgs(src, "ScriptEvo.onReplaySettled(").firstOrNull()
        assertNotNull("前提：切得到那条调用", call)
        val mutated = src.replaceFirst(call!!, "")
            .replace("TeachReplayStore.recordRun(", call + "\n        TeachReplayStore.recordRun(")
        assertTrue("前提：两个字面量都还在", mutated.contains("ScriptEvo.onReplaySettled("))
        assertEquals("顺序反了必须红在顺序上", ORDER_BAD, g1Failure(mutated))
    }

    // ── G2：技能页转正走链 ──────────────────────────────────────────────────

    private fun promoteBranchFailure(src: String): String? {
        val body = GateBodies.functionBody(src, "fun runSkillRowAction(")
            ?: return "runSkillRowAction 切不到"
        if (!body.contains("SkillRowAction.PROMOTE")) return "转正支不见了"
        if (!body.contains("ScriptEvo.promote(")) return NO_PROMOTE
        return null
    }

    @Test
    fun `G2_技能页转正_脚本走ScriptEvo_promote`() {
        val why = promoteBranchFailure(chatController())
        assertTrue("G2 不过：$why", why == null)
    }

    @Test
    fun `G2负例_把脚本转正那一行拔掉_断言必须红`() {
        val src = chatController()
        assertTrue(src.contains("ScriptEvo.promote("))
        val mutated = src.replace("ScriptEvo.promote(", "ScriptEvo.promoteRemoved(")
        assertEquals("拔缝必须红在「绕过门」这一条上", NO_PROMOTE, promoteBranchFailure(mutated))
    }

    // ── G3：一键收回走链 ────────────────────────────────────────────────────

    private fun revokeFailure(src: String): String? {
        val body = GateBodies.functionBody(src, "fun revertSkillPromoteNow(")
            ?: return "revertSkillPromoteNow 切不到"
        return if (body.contains("ScriptEvo.revoke(")) null else NO_REVOKE
    }

    @Test
    fun `G3_一键收回_脚本走ScriptEvo_revoke`() {
        val why = revokeFailure(chatController())
        assertTrue("G3 不过：$why", why == null)
    }

    @Test
    fun `G3负例_把收回那一行拔掉_断言必须红`() {
        val src = chatController()
        assertTrue(src.contains("ScriptEvo.revoke("))
        val mutated = src.replace("ScriptEvo.revoke(", "ScriptEvo.revokeRemoved(")
        assertEquals("拔缝必须红在「没走收回」这一条上", NO_REVOKE, revokeFailure(mutated))
    }

    // ── G4：门在写盘之前，且本模块不自判 ────────────────────────────────────

    private fun g4Failure(src: String): String? {
        if (src.contains("CanaryBand.") || src.contains("RedLines.")) return SELF_JUDGE
        val body = GateBodies.functionBody(src, "fun promote(")
            ?: return "ScriptEvo.promote 切不到"
        val gate = body.indexOf("LowRiskEvo.gate(")
        if (gate < 0) return NO_GATE
        val write = body.indexOf("writeStatus(")
        if (write < 0) return "promote 里没有写盘（writeStatus 不在）"
        if (gate > write) return GATE_LATE
        // 门的三态必须都处理：放行之外的两种都要挡回去（不然"拒"只是打印了一句话）
        if (!body.contains("LowRiskVerdict.Refuse")) return "红线拒没在 promote 里挡回去"
        if (!body.contains("LowRiskVerdict.Hold")) return "灰度不到没在 promote 里挡回去"
        return null
    }

    @Test
    fun `G4_转正前先过568A的门_且不自判档位红线`() {
        val why = g4Failure(scriptEvo())
        assertTrue("G4 不过：$why", why == null)
    }

    @Test
    fun `G4负例_把门拔掉_断言必须红`() {
        val src = scriptEvo()
        assertTrue(src.contains("LowRiskEvo.gate("))
        val mutated = src.replace("LowRiskEvo.gate(", "LowRiskEvo.gateRemoved(")
        assertEquals("拔门必须红在「没问门」这一条上", NO_GATE, g4Failure(mutated))
    }

    @Test
    fun `G4负例_门挪到写盘之后_顺序断言必须红`() {
        val src = scriptEvo()
        val body = GateBodies.functionBody(src, "fun promote(")
        assertNotNull("前提：promote 切得到", body)
        val gateBlock = GateBodies.blockBody(
            body!!,
            Regex("""when\s*\(\s*val\s+\w+\s*=\s*LowRiskEvo\.gate\("""),
        )
        assertNotNull("前提：门的三态块切得到", gateBlock)
        val mutatedBody = body.replace(gateBlock!!, "")
            .replace("return ScriptEvoOutcome.ok(", gateBlock + "\n        return ScriptEvoOutcome.ok(")
        val mutated = src.replace(body, mutatedBody)
        assertTrue("前提：门还在（换了个位置）", mutated.contains("LowRiskEvo.gate("))
        assertEquals("门挪到写盘之后必须红在顺序上", GATE_LATE, g4Failure(mutated))
    }

    @Test
    fun `G4负例_本模块自己判档位_断言必须红`() {
        val src = scriptEvo()
        val mutated = src.replace(
            "private val CAP = CanaryCapability.REFLECTION_AUTO_EDIT",
            "private val CAP = CanaryCapability.REFLECTION_AUTO_EDIT\n    private val BAND = CanaryBand.CANARY",
        )
        assertTrue("前提：真文件里没有 CanaryBand.", !src.contains("CanaryBand."))
        assertEquals("自判档位必须红在「判据外溢」这一条上", SELF_JUDGE, g4Failure(mutated))
    }

    // ── 判据⑥：568-A 的判定逻辑一行没动（结构自证）─────────────────────────────

    @Test
    fun `判据6_568A的门仍是红线_档位_深度三段且只由LowRiskGate出结论`() {
        val admit = GateBodies.functionBody(lowRiskGate(), "fun admit(")
        assertNotNull("LowRiskGate.admit 切不到", admit)
        val red = admit!!.indexOf("RedLines.hit(")
        val band = admit.indexOf("CanaryBand.OFF")
        val deep = admit.indexOf("EvolutionDepth.DEEP")
        assertTrue("三段顺序必须是 红线 → 档位 → 深度", red >= 0 && red < band && band < deep)
        val gate = GateBodies.functionBody(lowRiskEvo(), "fun gate(")
        assertNotNull("LowRiskEvo.gate 切不到", gate)
        assertTrue("四域的门仍是转手 LowRiskGate.admit", gate!!.contains("LowRiskGate.admit("))
    }
}
