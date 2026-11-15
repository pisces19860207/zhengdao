// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百四十七批 · 能力路由漏斗 L1 的**源级门禁**（判据①③⑤的源码面＋每条拔缝负例）。
//
// 钉六件事（拔掉任何一处，"L1 只判不装、不夺 L3 判定权"就断了）：
//   G1 判据①：funnel 包两个主源**零模型引用**——模型类名字样一个不许出现（纯判据的自证）；
//   G2 判据⑤：装配侧（RunEngine／ContextAssembler）**不含本包字样**——本单只记读数，
//      谁把漏斗接进装配链，这条当场红（装配改动是后续单的事）；
//   G3 判据⑤：funnel 主源**没有写口**——可见性链、tools 数组、回放执行、落盘写口全禁；
//   G4 判据③：judge 收尾**必过闭集校验**（"route in legal"那一格不许被拆）；
//   G5 判据④：send 是**唯一接线点**——controller 的 send 体里必须有 funnelReadTurn 那一行，
//      且壳必须真的落 RunLog（readingLine 进 log，不许只算不记）；
//   G6 判据⑤正面：L3 的工具数组仍走**唯一可见性链**（toolsJson 双出口字样还在引擎里）。
package com.example.zhengdao.baopu.funnel

import com.example.zhengdao.GateBodies
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FunnelReadGateTest {

    private fun code(rel: String): String =
        File(rel).let { if (it.isFile) it else File("app/$rel") }.readText()
            .replace("\r\n", "\n")

    private fun funnelL1() = code("src/main/java/com/example/zhengdao/baopu/funnel/FunnelL1.kt")
    private fun funnelRead() = code("src/main/java/com/example/zhengdao/baopu/funnel/FunnelRead.kt")
    private fun runEngine() = code("src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt")
    private fun contextAssembler() = code("src/main/java/com/example/zhengdao/baopu/ContextAssembler.kt")
    private fun chatController() = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt")

    private companion object {
        val MODEL_MARKS = listOf("LlmClient", "ModelRouter", "RunEngine", "streamRound", "BaopuChannel", "LlmProtocol")
        val WRITE_MARKS = listOf("visibleTools(", "toolsJson", "assemble(", "replaySkill(", "saveConfirmed(", "setGroupVisible")
        const val NO_LEGAL = "judge 收尾没过闭集（route in legal 被拆了＝组外名字也能当有效路由）"
        const val NO_WIRE = "send 里没接 funnelReadTurn＝每轮一行断供（判据④）"
        const val NO_LOG = "壳没把读数行落 RunLog＝只算不记（判据④）"
        const val L3_TAKEN = "装配侧出现了漏斗字样＝本单红线「不改现有装配」（判据⑤）"
        const val L3_FREE = "L3 工具数组不再走 toolsJson 唯一链＝判定权被动（判据⑤）"
    }

    // ── G1：判据层零模型引用 ────────────────────────────────────────────────

    private fun modelFreeFailure(src: String, who: String): String? {
        val hit = MODEL_MARKS.firstOrNull { src.contains(it) }
        return if (hit == null) null else "$who 引用了模型侧字样「$hit」——L1 必须零模型（判据①）"
    }

    @Test
    fun `G1_funnel两主源零模型引用`() {
        val a = modelFreeFailure(funnelL1(), "FunnelL1")
        assertTrue("G1 不过：$a", a == null)
        val b = modelFreeFailure(funnelRead(), "FunnelRead")
        assertTrue("G1 不过：$b", b == null)
    }

    @Test
    fun `G1负例_往判据层塞一个模型类名_必须红`() {
        val mutated = funnelL1() + "\n// 有人手滑引了 LlmClient\n"
        assertTrue("塞进模型引用后断言必须红", modelFreeFailure(mutated, "FunnelL1") != null)
    }

    // ── G2：装配侧不含漏斗（本单只记读数） ─────────────────────────────────

    @Test
    fun `G2_RunEngine与ContextAssembler不含漏斗字样`() {
        assertTrue(L3_TAKEN, !runEngine().contains("Funnel") && !contextAssembler().contains("Funnel"))
    }

    @Test
    fun `G2负例_把漏斗接进引擎_必须红`() {
        val mutated = runEngine().replace("class RunEngine", "class RunEngine /* FunnelRead.judge */")
        assertTrue("接进装配后断言必须红", mutated.contains("Funnel"))
    }

    // ── G3：funnel 主源没有写口 ─────────────────────────────────────────────

    private fun writeMarkFailure(src: String): String? =
        WRITE_MARKS.firstOrNull { src.contains(it) }

    @Test
    fun `G3_funnel主源没有任何装配写口`() {
        assertTrue("FunnelL1 出现写口 ${writeMarkFailure(funnelL1())}", writeMarkFailure(funnelL1()) == null)
        assertTrue("FunnelRead 出现写口 ${writeMarkFailure(funnelRead())}", writeMarkFailure(funnelRead()) == null)
    }

    @Test
    fun `G3负例_壳里偷偷解锁组_必须红`() {
        val mutated = funnelRead().replace(
            "val inputs = countInputs()",
            "val inputs = countInputs()\n        ToolRegistry.visibleTools(",
        )
        assertTrue("解锁动作被扫出才算门禁", writeMarkFailure(mutated) != null)
    }

    // ── G4：judge 收尾必过闭集 ──────────────────────────────────────────────

    private fun legalCheckFailure(src: String): String? {
        val body = GateBodies.functionBody(src, "fun judge(") ?: return "judge 切不到"
        return if (body.contains("hit.route in legal")) null else NO_LEGAL
    }

    @Test
    fun `G4_judge收尾有闭集校验`() {
        val why = legalCheckFailure(funnelL1())
        assertTrue("G4 不过：$why", why == null)
    }

    @Test
    fun `G4负例_把闭集校验拆成恒真_必须红`() {
        val src = funnelL1()
        assertTrue("前提：真文件里有那一格", src.contains("if (hit.route in legal) hit else miss("))
        val mutated = src.replace("if (hit.route in legal) hit else miss(", "if (true) hit else miss(")
        assertNotNull("拆掉校验必须红在「闭集被拆」这一条", legalCheckFailure(mutated))
    }

    // ── G5：send 唯一接线＋读数真落 RunLog ──────────────────────────────────

    private fun wireFailure(src: String): String? {
        val body = GateBodies.functionBody(src, "fun send(") ?: return "send 切不到"
        if (!body.contains("funnelReadTurn(text, attachments)")) return NO_WIRE
        return null
    }

    @Test
    fun `G5_send里接了每轮读数`() {
        val why = wireFailure(chatController())
        assertTrue("G5 不过：$why", why == null)
    }

    @Test
    fun `G5负例_把接线那一行拔掉_必须红`() {
        val src = chatController()
        val mutated = src.replace("funnelReadTurn(text, attachments)", "// 拔线")
        assertTrue("拔线必须红在「断供」上", wireFailure(mutated) == NO_WIRE)
    }

    private fun logFailure(src: String): String? {
        val body = GateBodies.functionBody(src, "suspend fun onUserTurn(") ?: return "onUserTurn 切不到"
        return if (body.contains("RunLog.log(readingLine(")) null else NO_LOG
    }

    @Test
    fun `G5_壳的读数行真落RunLog`() {
        val why = logFailure(funnelRead())
        assertTrue("G5 不过：$why", why == null)
    }

    @Test
    fun `G5负例_把落日志那一行拔掉_必须红`() {
        val mutated = funnelRead().replace("RunLog.log(readingLine(", "/* 只算不记 */ (")
        assertTrue("拔 log 必须红在「只算不记」上", logFailure(mutated) == NO_LOG)
    }

    // ── G6：L3 判定权正面钉（工具数组仍走唯一链） ───────────────────────────

    @Test
    fun `G6_引擎侧tools数组仍走toolsJson唯一链`() {
        val engine = runEngine()
        assertTrue(L3_FREE, engine.contains("toolsJsonOpenAi(sessionId)"))
        assertTrue(L3_FREE, engine.contains("toolsJsonAnthropic(sessionId)"))
    }

    @Test
    fun `读数行四个字段名都在`() {
        val body = GateBodies.functionBody(funnelRead(), "fun readingLine(")
        assertNotNull("readingLine 切不到", body)
        for (mark in listOf("route=", "命中=", "建议装载工具数=", "预计注入段数=")) {
            assertTrue("读数行缺字段「$mark」", body!!.contains(mark))
        }
    }
}
