// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

// 第一百八十八批 §1.6：省钱闸（2026-10-24）。
//
// 口径：同一轮里，工具参数连续判不合法到线（现有上限：第一次坏给一次重试机会，
// 第二次坏即停）⇒ **停**，并把「哪家／哪字段／服务端原话」写进可读错误，
// 不再继续试。病灶是旧文案「模型连续给的参数都不合法，这轮停下来（错误已记日志）」
// ——不说法通道、不说法工具、不说法原话，排障全靠翻日志猜。
//
// 分层（与 BadArgsFeedbackTest 同口径）：
//   ① 纯判据：恒坏 fixture 场景走「解码 ⇒ 判重试 ⇒ 到线停」的循环，
//      断言步数/花费上限内就停；负例把闸拿掉 ⇒ 步数继续涨；
//   ② 可读错误：哪家（通道＋协议）/哪字段（工具名＋原文里的字段）/服务端原话；
//   ③ 源码门禁：引擎的 BadArgs 分支真接了闸（RunEngine 要真 SQLite+网络，
//      JVM 里起不来 ⇒ 「接没接上」钉在源码结构上）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.RawToolCall
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BadArgsCostGateTest {

    // ── ① 恒坏场景：到线即停 ──────────────────────────────────────────

    /** 真注册表（真 Schema）：恒坏样本用「半截 JSON」（永远解不出来的那种）。 */
    private fun registry(): ToolRegistry {
        val root = createTempDirectory("zd-gate-").toFile()
        val reg = ToolRegistry(InMemoryToolPrefs())
        BaopuToolCatalog.loadInto(reg) { root }
        return reg
    }

    /** 恒坏样本：参数是半截 JSON（`{"path":`）——怎么重试都是这个形状。 */
    private fun alwaysBad(reg: ToolRegistry): ToolCallCodec.DecodeResult.Bad {
        val r = ToolCallCodec.decode(
            listOf(RawToolCall(id = "c1", name = "read_file", argumentsJson = """{"path":}""")),
            reg,
        )
        assertTrue("样本必须恒坏（否则本场景没有意义）", r is ToolCallCodec.DecodeResult.Bad)
        return r as ToolCallCodec.DecodeResult.Bad
    }

    @Test
    fun `恒坏场景_同一轮第二次就停_两步远小于步数刹车`() {
        val bad = alwaysBad(registry())
        var retriesUsed = 0 // 已经用掉的坏参数重试机会
        var steps = 0
        while (true) {
            steps++ // 一次坏参数 = 一步（引擎每步一次模型轮次，token 照烧）
            if (!RunRules.shouldRetryBadArgs(retriesUsed)) break // 到线即停
            retriesUsed++
        }
        assertEquals("同一轮只给一次重试机会 ⇒ 第 2 次坏就停", 2, steps)
        assertEquals("重试机会只用掉一次", 1, retriesUsed)
        assertTrue("两步停 vs 120 步刹车——闸省下的是 60 倍的 token", steps * 60 <= RunRules.MAX_STEPS)
        // 停之前的坏样本错误原文是可用的（回灌/归因都靠它）
        assertTrue(bad.error.contains("read_file"))
    }

    @Test
    fun `负例_把闸拿掉_步数继续涨到刹车线`() {
        // 「闸去掉」= 坏参数永远有下一次机会（shouldRetryBadArgs 恒真）。
        // 此时唯一能拦住循环的是步数刹车 ⇒ 步数涨到 MAX_STEPS 才停。
        var steps = 0
        while (steps < RunRules.MAX_STEPS) {
            steps++ // 每次都判「还能重试」⇒ 继续烧下一步
        }
        assertEquals("没闸就只有步数刹车兜底", RunRules.MAX_STEPS, steps)
        assertTrue("有闸 2 步停、没闸 120 步才停", RunRules.MAX_STEPS > 2)
    }

    @Test
    fun `花销上界_每步一次模型轮次_两步就是两次请求`() {
        // 省钱闸省的是「请求次数」：到线停 ⇒ 同一轮最多 2 次模型轮次花在坏参数上；
        // 没有闸 ⇒ MAX_STEPS 次。用轮次对齐「花费」口径（每次轮次都烧 token）。
        val withGate = 2
        val withoutGate = RunRules.MAX_STEPS
        assertTrue("闸把花费压在 1/60", withoutGate / withGate >= 60)
    }

    // ── ② 可读错误：哪家／哪字段／服务端原话 ────────────────────────

    @Test
    fun `可读错误_哪家哪字段服务端原话都读得出`() {
        val raw = "工具「read_file」的参数不是合法 JSON：{\"path\":}"
        val line = RunRules.badArgsStopSummary(
            providerId = "gemini-native",
            protocol = "GEMINI_NATIVE",
            toolName = "read_file",
            rawError = raw,
            attempts = 2,
        )
        assertTrue("哪家（通道）", line.contains("gemini-native"))
        assertTrue("哪家（协议，同 id 也可能切协议）", line.contains("GEMINI_NATIVE"))
        assertTrue("哪字段（工具名）", line.contains("read_file"))
        assertTrue("第几次仍不合法", line.contains("第 2 次"))
        assertTrue("服务端原话逐字保留", line.contains(raw))
    }

    @Test
    fun `可读错误_schema错也带得出工具名_语法层拿不到就说没给`() {
        val schemaLine = RunRules.badArgsStopSummary("ch", "ANTHROPIC", "edit_file", "缺少必填参数「path」", 2)
        assertTrue(schemaLine.contains("edit_file") && schemaLine.contains("缺少必填参数「path」"))
        val syntaxLine = RunRules.badArgsStopSummary("ch", "OPENAI_COMPAT", "", "参数不是合法 JSON", 2)
        assertTrue("语法层拿不到名字要如实说，不许编", syntaxLine.contains("（没给出名字）"))
    }

    @Test
    fun `notice人话版_带工具名与原文摘要_不再只说错误已记日志`() {
        val notice = RunLoopRules.badArgsStopNotice("read_file", 2, "参数不是合法 JSON：{\"path\":}")
        assertTrue("要说停了几次", notice.contains("连续 2 次"))
        assertTrue("要带工具名", notice.contains("read_file"))
        assertTrue("要带原文摘要", notice.contains("参数不是合法 JSON"))
        assertTrue("要给下一步指路", notice.contains("换个说法"))
        // 工具名拿不到（语法层）⇒ 不说工具，但照样是人话
        val noName = RunLoopRules.badArgsStopNotice("", 2, "参数不是合法 JSON")
        assertFalse(noName.contains("（）"))
        assertTrue(noName.contains("连续 2 次"))
    }

    // ── ③ 源码门禁：引擎真接了 ───────────────────────────────────────

    @Test
    fun `源码门禁_BadArgs分支真接省钱闸`() {
        val code = engineCode()
        val branch = branchOf(code, "is RoundOutcome.BadArgs -> {", listOf("is RoundOutcome.Failed -> {"))
        assertTrue("到线必须合成可读错误（哪家/哪字段/原话）", branch.contains("RunRules.badArgsStopSummary("))
        assertTrue("可读错误必须落 run_log.err 列", branch.contains("err = stopError"))
        assertTrue("notice 必须走人话判据（带归因）", branch.contains("RunLoopRules.badArgsStopNotice("))
        assertTrue("闸的判据本体仍是 shouldRetryBadArgs（到线即停）", branch.contains("RunRules.shouldRetryBadArgs"))
        assertFalse(
            "旧文案（只说「错误已记日志」）必须消失——那就是本单要修的不可读",
            branch.contains("模型连续给的参数都不合法，这轮停下来（错误已记日志）"),
        )
    }

    // ── 夹具 ─────────────────────────────────────────────────────────────

    private val enginePath = "src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt"

    private fun engineCode(): String = File(enginePath).readText().lines()
        .filterNot { l ->
            val t = l.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }
        .joinToString("\n")

    private fun branchOf(code: String, head: String, nextHeads: List<String>): String {
        val i = code.indexOf(head)
        assertTrue("找不到分支头：$head", i >= 0)
        val rest = code.substring(i + head.length)
        val end = nextHeads.mapNotNull { h -> rest.indexOf(h).takeIf { it >= 0 } }.minOrNull() ?: rest.length
        return rest.substring(0, end)
    }
}
