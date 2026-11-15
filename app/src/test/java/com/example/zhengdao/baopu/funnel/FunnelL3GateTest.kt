// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百四十七批（L3 轮）· L3 建议注入的**源级门禁**（每条自带拔缝负例）。
//
// 钉七件事：
//   G1 建议层零模型引用＋零写口——FunnelSuggest.kt 是纯判据＋内存信箱，
//      装配写口字样一个不许有（"建议只当提示"的自证）；
//   G2 判据②：引擎侧建议只从 extraNotes 进——tools 装配段零「Funnel」字样，
//      consume/readingLineOf 各恰一处（同款切片见 FunnelReadGateTest／FunnelL2GateTest，
//      三把闸同一口径：L3 的工具选择不读"建议"作为来源）；
//   G3 工单①：L2 壳四出口各落一次 publish（少一格＝那一轮的 L3 拿不到建议）；
//   G4 判据④：读数行五字段齐（建议组／实际装载工具数／注入段数／L2／耗时ms）；
//   G5 判据⑤承重：build 的 L1 优先与「只认 STATE_HIT 才吃 L2 的组」不许被拆
//      （拆了＝静默用 L2，负例当场红）；
//   G6 判据①承重：lineOf 的 60 字 fail-closed 不许被拆成截半句；
//   G7 判据③结构保证＋判据⑥零新依赖：建议层没有任何"吃用户文本"的入口
//      （截断/取子串字样出现即红），import 全在白名单里。
package com.example.zhengdao.baopu.funnel

import com.example.zhengdao.GateBodies
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FunnelL3GateTest {

    private fun code(rel: String): String =
        File(rel).let { if (it.isFile) it else File("app/$rel") }.readText()
            .replace("\r\n", "\n")

    private fun funnelSuggest() = code("src/main/java/com/example/zhengdao/baopu/funnel/FunnelSuggest.kt")
    private fun funnelL2Read() = code("src/main/java/com/example/zhengdao/baopu/funnel/FunnelL2Read.kt")
    private fun runEngine() = code("src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt")

    private companion object {
        val MODEL_MARKS = listOf("LlmClient", "LlmEvent", "LlmMessage", "streamAsync", "streamRound", "RunEngine", "ModelRouter")
        val WRITE_MARKS = listOf("visibleTools(", "toolsJson", "assemble(", "setGroupVisible", "replaySkill(", "saveConfirmed(")
        val IMPORT_WHITELIST = listOf("org.json.", "java.", "javax.", "kotlin.", "kotlinx.coroutines.", "com.example.zhengdao.")
        const val NO_PUBLISH = "L2 壳的 publish 少于四处＝有一轮的 L3 拿不到建议（工单①）"
        const val NO_L1_FIRST = "build 的 L1 优先被拆（判据⑤）"
        const val NO_HIT_GUARD = "build 不再只认 STATE_HIT 才吃 L2 的组＝静默用 L2（判据⑤负例）"
        const val NO_FAIL_CLOSED = "lineOf 不再整行作废＝超 60 字硬塞半句（判据①）"
        const val TEXT_HUNGER = "建议层出现了吃用户文本的入口＝多意图保原文的构造性保证被拆（判据③）"
    }

    private fun countOf(haystack: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(haystack).count()

    // ── G1：建议层零模型引用＋零写口 ────────────────────────────────────────

    @Test
    fun `G1_FunnelSuggest零模型引用零写口`() {
        val src = funnelSuggest()
        val model = MODEL_MARKS.firstOrNull { src.contains(it) }
        assertTrue("G1 不过：建议层出现模型侧字样「$model」", model == null)
        val write = WRITE_MARKS.firstOrNull { src.contains(it) }
        assertTrue("G1 不过：建议层出现写口「$write」", write == null)
    }

    @Test
    fun `G1负例_建议层塞一个写口_必须红`() {
        val mutated = funnelSuggest().replace(
            "    private val pending = ConcurrentHashMap<String, Pending>()",
            "    private val pending = ConcurrentHashMap<String, Pending>()\n    private fun unlock() = ToolRegistry.setGroupVisible(",
        )
        assertTrue("写口必须被扫出", WRITE_MARKS.any { mutated.contains(it) })
    }

    // ── G2：引擎侧建议只从 extraNotes 进，tools 链零漏斗 ────────────────────

    /** tools 数组装配段（唯一链起点到快照调用）：判据②的承重切片（三把闸同一口径）。 */
    private fun toolsChainSlice(src: String): String? {
        val start = src.indexOf("val tools = when (provider.protocol)")
        if (start < 0) return null
        val end = src.indexOf("ToolDeclSnapshot.of(", start)
        return if (end < 0) null else src.substring(start, end)
    }

    private fun engineWireFailure(engine: String): String? {
        val slice = toolsChainSlice(engine) ?: return "tools 装配段切不到（锚点被挪＝门失效）"
        if (slice.contains("Funnel")) return "判据②：tools 装配段读到了漏斗字样"
        if (countOf(engine, "FunnelSuggest.consumeForTurn(") != 1) return "引擎侧消费口必须恰一处"
        if (countOf(engine, "FunnelSuggest.readingLineOf(") != 1) return "引擎侧读数口必须恰一处"
        if (!engine.contains("listOfNotNull(mcpAssembly?.notice, funnelSuggestion?.line)")) {
            return "建议行必须经 extraNotes 实参进上下文（注入缝被挪走）"
        }
        return null
    }

    @Test
    fun `G2_引擎侧注入缝与消费读数口形状钉死_tools链零漏斗`() {
        val why = engineWireFailure(runEngine())
        assertTrue("G2 不过：$why", why == null)
    }

    @Test
    fun `G2负例_把建议塞进tools链当唯一来源_必须红`() {
        val mutated = runEngine().replace(
            "val tools = when (provider.protocol) {",
            "val tools = when (provider.protocol) { /* FunnelSuggest 按建议组装配 tools */",
        )
        assertNotNull("突变必须保住锚点（否则这条负例是安慰剂）", toolsChainSlice(mutated))
        assertTrue("建议进 tools 链后断言必须红", engineWireFailure(mutated) != null)
    }

    @Test
    fun `G2负例_把消费口挪成两处_必须红`() {
        val engine = runEngine()
        val mutated = engine.replace(
            "val funnelExtraNotes = turnNotes + listOfNotNull(mcpAssembly?.notice, funnelSuggestion?.line)",
            "val funnelExtraNotes = turnNotes + listOfNotNull(mcpAssembly?.notice, funnelSuggestion?.line)\n" +
                "            val funnelAgain = com.example.zhengdao.baopu.funnel.FunnelSuggest.consumeForTurn(sessionId.toString())",
        )
        assertTrue("双消费口必须红（一格信箱只许一轮吃一次）", engineWireFailure(mutated) != null)
    }

    // ── G3：L2 壳四出口各落一次 publish ─────────────────────────────────────

    private fun publishFailure(src: String): String? {
        val body = GateBodies.functionBody(src, "suspend fun onUserTurn(") ?: return "onUserTurn 切不到"
        val n = Regex("FunnelSuggest\\.publish\\(").findAll(body).count()
        if (n != 4) return NO_PUBLISH
        if (!body.contains("if (d.undecided) FunnelSuggest.STATE_UNDECIDED else FunnelSuggest.STATE_HIT")) {
            return "正常出口没把 undecided 分出来＝读数会撒谎（判据⑤）"
        }
        return null
    }

    @Test
    fun `G3_L2壳四出口各publish一次`() {
        val why = publishFailure(funnelL2Read())
        assertTrue("G3 不过：$why", why == null)
    }

    @Test
    fun `G3负例_拔掉undecided出口的publish_必须红`() {
        val mutated = funnelL2Read().replace(
            "        val l2State = if (d.undecided) FunnelSuggest.STATE_UNDECIDED else FunnelSuggest.STATE_HIT\n" +
                "        FunnelSuggest.publish(sessionId, l1.route, d.group, l2State)\n",
            "",
        )
        assertTrue("拔掉一格 publish 必须红在「断供」上", publishFailure(mutated) != null)
    }

    // ── G4：读数行五字段齐 ──────────────────────────────────────────────────

    private fun readingFailure(src: String): String? {
        val body = GateBodies.functionBody(src, "fun readingLineOf(") ?: return "readingLineOf 切不到"
        for (mark in listOf("建议组=", "实际装载工具数=", "注入段数=", "L2=", "耗时ms=")) {
            if (!body.contains(mark)) return "读数行缺字段「$mark」"
        }
        return null
    }

    @Test
    fun `G4_读数行五字段齐`() {
        val why = readingFailure(funnelSuggest())
        assertTrue("G4 不过：$why", why == null)
    }

    @Test
    fun `G4负例_把注入段数字段拔掉_必须红`() {
        val mutated = funnelSuggest().replace("注入段数=\$segments ", "")
        assertTrue("缺字段必须红", readingFailure(mutated) != null)
    }

    // ── G5：build 的 L1 优先与 undecided 闸承重 ─────────────────────────────

    private fun buildFailure(src: String): String? {
        val body = GateBodies.functionBody(src, "fun build(") ?: return "build 切不到"
        if (!body.contains("fromL1 -> l1Route")) return NO_L1_FIRST
        if (!body.contains("l2State == STATE_HIT && l2Group != null && l2Group != FunnelL2Judge.UNDECIDED")) {
            return NO_HIT_GUARD
        }
        return null
    }

    @Test
    fun `G5_build的L1优先与只认命中组两道闸承重`() {
        val why = buildFailure(funnelSuggest())
        assertTrue("G5 不过：$why", why == null)
    }

    @Test
    fun `G5负例_把STATE_HIT闸拆成恒真_必须红`() {
        val src = funnelSuggest()
        assertTrue("前提：真文件里有那一格", src.contains("l2State == STATE_HIT && l2Group != null"))
        val mutated = src.replace("l2State == STATE_HIT && l2Group != null", "true && l2Group != null")
        assertNotNull("拆闸必须红在「静默用 L2」", buildFailure(mutated))
    }

    @Test
    fun `G5负例_把L1优先拆掉_必须红`() {
        val mutated = funnelSuggest().replace("fromL1 -> l1Route", "fromL1 -> null")
        assertNotNull("拆 L1 优先必须红", buildFailure(mutated))
    }

    // ── G6：60 字 fail-closed 承重 ──────────────────────────────────────────

    private fun failClosedFailure(src: String): String? {
        val body = GateBodies.functionBody(src, "fun lineOf(") ?: return "lineOf 切不到"
        return if (body.contains("text.length > MAX_LINE_CHARS) null else text")) null else NO_FAIL_CLOSED
    }

    @Test
    fun `G6_超60字整行作废承重`() {
        val why = failClosedFailure(funnelSuggest())
        assertTrue("G6 不过：$why", why == null)
    }

    @Test
    fun `G6负例_把整行作废改成照注入_必须红`() {
        val mutated = funnelSuggest().replace("text.length > MAX_LINE_CHARS) null else text", "true) null else text")
        assertNotNull("fail-closed 被拆必须红", failClosedFailure(mutated))
    }

    // ── G7：建议层不吃用户文本＋import 白名单 ───────────────────────────────

    private fun textHungerFailure(src: String): String? {
        val code = src.split("\n").joinToString("\n") { it.substringBefore("//") }
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        val marks = listOf("cappedText", ".take(", "substring(", "userText", "text: String")
        val hit = marks.firstOrNull { code.contains(it) }
        return if (hit == null) null else "$TEXT_HUNGER：「$hit」"
    }

    @Test
    fun `G7_建议层没有任何吃用户文本的入口`() {
        val why = textHungerFailure(funnelSuggest())
        assertTrue("G7 不过：$why", why == null)
    }

    @Test
    fun `G7负例_建议层加一个截用户文本的口子_必须红`() {
        val mutated = funnelSuggest().replace(
            "    fun publish(sessionId: String, l1Route: String, l2Group: String?, l2State: String) {",
            "    fun publish(sessionId: String, l1Route: String, l2Group: String?, l2State: String, userText: String) {",
        )
        assertNotNull("吃文本的入口必须被拦", textHungerFailure(mutated))
    }

    private fun importWhitelistFailure(src: String): String? {
        val bad = Regex("(?m)^import\\s+\\S+").findAll(src).map { it.value.removePrefix("import").trim() }
            .filterNot { name -> IMPORT_WHITELIST.any { prefix -> name.startsWith(prefix) } }
            .toList()
        return if (bad.isEmpty()) null else "判据⑥违例：出现白名单外依赖 ${bad.joinToString("、")}"
    }

    @Test
    fun `G7_L3主源的import全在白名单里`() {
        val why = importWhitelistFailure(funnelSuggest())
        assertTrue("不过：$why", why == null)
    }

    @Test
    fun `G7负例_塞一条外部库import_必须红`() {
        val mutated = funnelSuggest() + "\nimport okhttp3.OkHttpClient\n"
        assertNotNull("外部依赖必须被拦", importWhitelistFailure(mutated))
    }
}
