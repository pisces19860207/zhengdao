// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百一十九批 · RunEngine／闸侧的**源码门禁**（同款范式见 QuietWiringGateTest：
// RunEngine 在 JVM 里起不来，接线用源码形状钉）。
//
// 钉四件事：
//   G1 那句"验卡"真接进了轮次入口，而且排在补问之后（不许越过队首先冒出来）；
//   G2 引擎弹自检卡走的是 `askUser(mustAsk = true)`——人亲口要验的东西，调解器的预算压不掉；
//   G3 豁免放行的那张要落账（"照常弹"平时不记账，豁免必须记账）；
//   G4 **免打扰整体没被放宽**：QuietMode 的判卡格仍是"法条格放行 + 其余看法定授权"，
//      没有任何"夜里宽松一点"的旁路。
package com.example.zhengdao.baopu.agent

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietVerifyWiringGateTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.exists()) it else File("app/$rel") }

    private fun read(rel: String): String = repoFile(rel).readText()

    /** 去注释的源码（结构性断言用；免得门禁被改注释绕过）。 */
    private fun codeOnly(src: String): String =
        src.split("\n").joinToString("\n") { it.substringBefore("//") }
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")

    private val engineRel = "src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt"
    private val gateRel = "src/main/java/com/example/zhengdao/baopu/agent/QuietCardGate.kt"
    private val modeRel = "src/main/java/com/example/zhengdao/baopu/agent/QuietMode.kt"

    private fun slice(src: String, start: String, end: String): String {
        val s = src.indexOf(start)
        require(s >= 0) { "找不到切片起点：$start" }
        val e = src.indexOf(end, s + start.length)
        require(e > s) { "找不到切片终点：$end" }
        return src.substring(s, e)
    }

    // ── G1：那句"验卡"接在轮次入口，且排在补问之后 ────────────────────────

    @Test
    fun `G1_验卡接进轮次入口_排在按序补问之后`() {
        val entry = slice(
            codeOnly(read(engineRel)),
            "userText?.let { learnSignals += SkillRefine.learnMarkersIn(it) }",
            "val workspacePath",
        )
        assertTrue("轮次入口要认这句话", entry.contains("noteQuietVerify(sessionId, it)"))
        assertTrue("先把压着的按序放完", entry.contains("drainQuietQueue()"))
        assertTrue(
            "验卡排在补问之后（同一张卡不许越过队首先冒出来）",
            entry.indexOf("drainQuietQueue()") < entry.indexOf("noteQuietVerify("),
        )
    }

    // ── G2：弹卡走 mustAsk 让步格 ───────────────────────────────────────

    @Test
    fun `G2_自检卡走askUser的必问让步格_调解器预算压不掉`() {
        val body = slice(codeOnly(read(engineRel)), "private fun noteQuietVerify(", "fun stop(")
        assertTrue("必须有这一句才认得出验卡", body.contains("QuietVerify.triggerOf(text)"))
        assertTrue("先落账再弹（569 那晚账上只有『因免打扰延后』）", body.contains("QuietVerify.armLine("))
        assertTrue(
            "弹卡要走 askUser 的必问让步格",
            body.contains("askUser(QuietVerify.request(trigger), mustAsk = true)"),
        )
    }

    // ── G3：豁免放行要落账 ──────────────────────────────────────────────

    @Test
    fun `G3_豁免放行那一张要落账_照常弹仍然不记账`() {
        val body = slice(codeOnly(read(engineRel)), "private fun quietDefer(", "private fun drainQuietQueue(")
        assertTrue(
            "豁免的账行不许被丢掉（免打扰期里凭空多一张卡，事后没人说得清凭什么）",
            body.contains("if (outcome.line.isNotEmpty()) log(outcome.line)"),
        )
        assertTrue("放行照样立即收手，不压", body.contains("return false"))
    }

    // ── G4：免打扰整体没被放宽 ─────────────────────────────────────────

    @Test
    fun `G4_QuietMode判卡格一个字没放宽`() {
        val body = slice(codeOnly(read(modeRel)), "fun cardAllowed(", "fun decide(")
        assertTrue("法条格仍恒放行（那不是打扰）", body.contains("if (!cls.quietSuppresses) return true"))
        assertTrue("其余仍一律看法定授权（没有任何『夜里宽松一点』的旁路）", body.contains("return !active(grant, nowMs)"))
        assertFalse("判卡格不许出现验卡／自检之类的旁路", body.contains("selfCheck"))
    }

    @Test
    fun `G5_闸里的豁免三条件都在_标_非锁屏_一段静默一张`() {
        val src = codeOnly(read(gateRel))
        val exempt = slice(src, "private fun exemptSelfCheck(", "fun takeNext(")
        assertTrue("凭据只有那个标", exempt.contains("request.selfCheck"))
        assertTrue("离场锁屏不算豁免", exempt.contains("awayLocked"))
        assertTrue("上限钉在限额对象里，不许调用处各写一把", exempt.contains("QuietGateLimits.MAX_SELF_CHECK"))
        val onAsk = slice(src, "fun onAsk(", "private fun exemptSelfCheck(")
        assertTrue(
            "豁免必须在静默判定之后（先问『现在静不静』，再问『这张放不放』）",
            onAsk.indexOf("if (!hushed(") < onAsk.indexOf("exemptSelfCheck("),
        )
    }
}
