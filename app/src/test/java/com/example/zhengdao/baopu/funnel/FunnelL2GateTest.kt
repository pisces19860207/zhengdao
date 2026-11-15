// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百四十七批（L2 轮）· L2 的**源级门禁**（判据④⑤⑥⑦的源码面，每条自带拔缝负例）。
//
// 钉七件事：
//   G1 判据层零模型引用——FunnelL2.kt 里模型侧字样一个不许有（便宜调用只活在壳的出口缝）；
//   G2 判据⑥：装配侧（RunEngine／ContextAssembler）零「Funnel」字样——L2 也不许接进装配；
//   G3 判据⑥：L2 壳零写口——不解锁组、不改 tools 数组、不碰上下文拼装、不落盘执行；
//   G4 硬规矩③④：judge 的闭集过滤与 margin 闸是**承重**的（拆了负例必须红）；
//   G5 工单⑥：接线顺序钉死——funnelReadTurn 里 L1 读数在前、L2 一枪在后（同一协程顺序），
//      previousRoute 取在 L1 更新之前；壳的账行必须真落 RunLog（不许只算不记）；
//   G6 判据⑦：零新依赖——L2 两主源的 import 只许落在白名单前缀里（org.json/java/kotlin/
//      kotlinx.coroutines/本仓包），出现外部新库字样当场红；
//   G7 硬规矩①：promptOf 必须走 cappedText（200 字截断）——拆了截断必须红。
package com.example.zhengdao.baopu.funnel

import com.example.zhengdao.GateBodies
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FunnelL2GateTest {

    private fun code(rel: String): String =
        File(rel).let { if (it.isFile) it else File("app/$rel") }.readText()
            .replace("\r\n", "\n")

    private fun funnelL2() = code("src/main/java/com/example/zhengdao/baopu/funnel/FunnelL2.kt")
    private fun funnelL2Read() = code("src/main/java/com/example/zhengdao/baopu/funnel/FunnelL2Read.kt")
    private fun runEngine() = code("src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt")
    private fun contextAssembler() = code("src/main/java/com/example/zhengdao/baopu/ContextAssembler.kt")
    private fun chatController() = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt")

    private companion object {
        val MODEL_MARKS = listOf("LlmClient", "LlmEvent", "LlmMessage", "streamAsync", "RunEngine", "ModelRouter")
        val WRITE_MARKS = listOf("visibleTools(", "toolsJson", "assemble(", "setGroupVisible", "replaySkill(", "saveConfirmed(")
        val IMPORT_WHITELIST = listOf("org.json.", "java.", "javax.", "kotlin.", "kotlinx.coroutines.", "com.example.zhengdao.")
        const val NO_FILTER = "judge 的闭集过滤被拆（组外名字也能当结果＝判据④负例）"
        const val NO_MARGIN = "judge 的 margin 闸被拆（差 0.01 也硬选＝工单③）"
        const val NO_WIRE = "funnelReadTurn 里 L2 那一枪不见了（工单⑥接线断供）"
        const val ORDER_BAD = "顺序不对：previousRoute 必须取在 L1 读数更新之前"
        const val NO_LOG = "壳没把账行落 RunLog＝只算不记（工单⑥）"
        const val NO_CAP = "promptOf 不走 cappedText（200 字硬上限没了＝工单①）"
    }

    // ── G1：判据层零模型引用 ────────────────────────────────────────────────

    @Test
    fun `G1_L2判据层零模型引用`() {
        val hit = MODEL_MARKS.firstOrNull { funnelL2().contains(it) }
        assertTrue("G1 不过：判据层出现模型侧字样「$hit」", hit == null)
    }

    @Test
    fun `G1负例_判据层塞进模型出口_必须红`() {
        val mutated = funnelL2() + "\n// 手滑：LlmClient 不该出现在判据层\n"
        assertTrue(MODEL_MARKS.any { mutated.contains(it) })
    }

    // ── G2：装配侧零漏斗（L2 也不许接进装配） ──────────────────────────────

    @Test
    fun `G2_RunEngine与ContextAssembler不含漏斗字样`() {
        assertTrue("判据⑥：装配侧出现漏斗字样", !runEngine().contains("Funnel") && !contextAssembler().contains("Funnel"))
    }

    @Test
    fun `G2负例_把L2接进引擎_必须红`() {
        val mutated = runEngine().replace("class RunEngine", "class RunEngine /* FunnelL2Read.onUserTurn */")
        assertTrue("接进装配后断言必须红", mutated.contains("Funnel"))
    }

    // ── G3：L2 壳零写口 ─────────────────────────────────────────────────────

    @Test
    fun `G3_L2壳没有任何装配写口`() {
        val hit = WRITE_MARKS.firstOrNull { funnelL2Read().contains(it) }
        assertTrue("G3 不过：壳出现写口「$hit」", hit == null)
    }

    @Test
    fun `G3负例_壳里偷偷改可见性链_必须红`() {
        val mutated = funnelL2Read().replace(
            "val l1 = FunnelL1Rules.judge(",
            "ToolRegistry.visibleTools(\n        val l1 = FunnelL1Rules.judge(",
        )
        assertTrue("写口必须被扫出", WRITE_MARKS.any { mutated.contains(it) })
    }

    // ── G4：闭集过滤与 margin 闸承重 ───────────────────────────────────────

    private fun judgeGateFailure(src: String): String? {
        val body = GateBodies.functionBody(src, "fun judge(") ?: return "judge 切不到"
        if (!body.contains("it in CANDIDATES")) return NO_FILTER
        if (!body.contains("top.value - runnerUp.value < MARGIN_MIN")) return NO_MARGIN
        return null
    }

    @Test
    fun `G4_judge带承重的闭集过滤与margin闸`() {
        val why = judgeGateFailure(funnelL2())
        assertTrue("G4 不过：$why", why == null)
    }

    @Test
    fun `G4负例_拆掉闭集过滤_必须红`() {
        val src = funnelL2()
        assertTrue("前提：真文件里有那一格", src.contains("scores.filterKeys { it in CANDIDATES }"))
        val mutated = src.replace("scores.filterKeys { it in CANDIDATES }", "scores")
        assertNotNull("拆过滤必须红在「闭集被拆」", judgeGateFailure(mutated))
    }

    @Test
    fun `G4负例_拆掉margin闸_必须红`() {
        val src = funnelL2()
        assertTrue("前提：真文件里有 margin 那一格", src.contains("< MARGIN_MIN"))
        val mutated = src.replace("top.value - runnerUp.value < MARGIN_MIN", "false")
        assertNotNull("拆 margin 必须红在「margin 被拆」", judgeGateFailure(mutated))
    }

    // ── G5：接线顺序＋账行真落 RunLog ──────────────────────────────────────

    private fun wireFailure(src: String): String? {
        val body = GateBodies.functionBody(src, "private fun funnelReadTurn(") ?: return "funnelReadTurn 切不到"
        val l1 = body.indexOf("FunnelRead.onUserTurn(")
        val l2 = body.indexOf("FunnelL2Read.onUserTurn(")
        val prev = body.indexOf("FunnelRead.snapshot()[sid]")
        if (l2 < 0) return NO_WIRE
        if (l1 < 0) return "L1 读数那一行不见了（L2 轮不许吃掉 L1）"
        if (prev < 0 || prev > l1) return ORDER_BAD
        if (l1 > l2) return "顺序不对：L2 一枪必须排在 L1 读数之后（同一协程顺序）"
        return null
    }

    @Test
    fun `G5_接线顺序_L1在前L2在后_previousRoute取在更新前`() {
        val why = wireFailure(chatController())
        assertTrue("G5 不过：$why", why == null)
    }

    @Test
    fun `G5负例_把L2那一枪拔掉_必须红`() {
        val mutated = chatController().replace(
            "com.example.zhengdao.baopu.funnel.FunnelL2Read.onUserTurn(sid, text, hasImage, workspaceRoot, prevRoute, provider)",
            "// 拔线",
        )
        assertTrue("拔线必须红在「断供」上", wireFailure(mutated) == NO_WIRE)
    }

    private fun logFailure(src: String): String? {
        val body = GateBodies.functionBody(src, "suspend fun onUserTurn(") ?: return "onUserTurn 切不到"
        val logs = Regex("RunLog\\.log\\(logLineOf\\(").findAll(body).count()
        // 四条落账一条不许少：跳过(L1命中)／无通道回退／调用失败回退／正常判定。
        return if (logs >= 4) null else NO_LOG
    }

    @Test
    fun `G5_壳的账行四态都真落RunLog`() {
        val why = logFailure(funnelL2Read())
        assertTrue("G5 不过：$why", why == null)
    }

    @Test
    fun `G5负例_把正常态的落账拔掉_必须红`() {
        val mutated = funnelL2Read().replace(
            "RunLog.log(logLineOf(sessionId, l1.route, d.group, if (d.undecided) d.reason else \"—\", cost))",
            "// 只算不记",
        )
        assertNotNull("拔掉一条落账必须红", logFailure(mutated))
    }

    // ── G6：零新依赖（import 白名单） ──────────────────────────────────────

    private fun importWhitelistFailure(src: String): String? {
        val bad = Regex("(?m)^import\\s+\\S+").findAll(src).map { it.value.removePrefix("import").trim() }
            .filterNot { name -> IMPORT_WHITELIST.any { prefix -> name.startsWith(prefix) } }
            .toList()
        return if (bad.isEmpty()) null else "判据⑦违例：出现白名单外依赖 ${bad.joinToString("、")}"
    }

    @Test
    fun `G6_L2两主源的import全在白名单里`() {
        val a = importWhitelistFailure(funnelL2())
        assertTrue("G6 不过：$a", a == null)
        val b = importWhitelistFailure(funnelL2Read())
        assertTrue("G6 不过：$b", b == null)
    }

    @Test
    fun `G6负例_塞一条外部库import_必须红`() {
        val mutated = funnelL2() + "\nimport okhttp3.OkHttpClient\n"
        assertNotNull("外部依赖必须被拦", importWhitelistFailure(mutated))
    }

    // ── G7：200 字截断承重 ─────────────────────────────────────────────────

    private fun capFailure(src: String): String? {
        val body = GateBodies.functionBody(src, "fun promptOf(") ?: return "promptOf 切不到"
        return if (body.contains("cappedText(")) null else NO_CAP
    }

    @Test
    fun `G7_prompt必须走200字截断`() {
        val why = capFailure(funnelL2())
        assertTrue("G7 不过：$why", why == null)
    }

    @Test
    fun `G7负例_把截断换成原文_必须红`() {
        val mutated = funnelL2().replace("append(cappedText(text))", "append(text)")
        assertNotNull("不截断必须红", capFailure(mutated))
    }

    // ── G8：工单⑤的不崩总闸承重（guard＝runCatching） ─────────────────────

    private fun guardFailure(src: String): String? {
        val body = GateBodies.functionBody(src, "suspend fun onUserTurn(") ?: return "onUserTurn 切不到"
        if (!body.contains("guard {")) return "一枪没走 guard＝保护被拆（工单⑤）"
        val g = GateBodies.functionBody(src, "fun <T> guard(") ?: return "guard 切不到"
        return if (g.contains("runCatching { block() }")) null else "guard 不再兜异常（拆了整轮会崩）"
    }

    @Test
    fun `G8_壳的不崩总闸承重`() {
        val why = guardFailure(funnelL2Read())
        assertTrue("G8 不过：$why", why == null)
    }

    @Test
    fun `G8负例_把guard拆成直放_必须红`() {
        val mutated = funnelL2Read().replace("= runCatching { block() }", "= Result.success(block())")
        assertNotNull("拆保护必须红在「guard 被拆」", guardFailure(mutated))
    }
}
