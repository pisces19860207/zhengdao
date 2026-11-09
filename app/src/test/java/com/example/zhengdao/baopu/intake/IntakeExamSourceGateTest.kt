// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百四十六批 · L3 出题接线的**源级门禁**（房型照 427 的 AdmissionWiringGateTest）。
// 行为判据在 IntakeExamTest；这里钉的是"接线形状与边界"——剥注释后比对，防 KDoc 同名样误伤。
//
// 钉住的边界（派工单原文）：
//   · 判据⑤ 不改 427 的调用点（只换传入 traces 的来源）；
//   · 不改准入判定顺序与放行语义（三态只落结论层，AdmissionGate 闭集不动）；
//   · L1 题面不重写、L2 判卷规则不改、L4 题库管理不做。
package com.example.zhengdao.baopu.intake

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntakeExamSourceGateTest {

    private fun code(rel: String): String =
        File(rel).let { if (it.isFile) it else File("app/$rel") }.readText()
            .replace("\r\n", "\n")
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun intake(name: String): String = code("src/main/java/com/example/zhengdao/baopu/intake/$name")

    private fun exam(): String = intake("IntakeExam.kt")

    private fun wiring(): String = intake("AdmissionWiring.kt")

    @Test
    fun `G1_427的管线调用点与入口签名一个字没动`() {
        val pipeline = code("src/main/java/com/example/zhengdao/baopu/agent/GuardPipeline.kt")
        assertTrue(
            "管线调用点被改了（判据⑤）",
            Regex("""if \(spec\.group == ToolGroup\.MCP\) \{\s*AdmissionWiring\.onExternalToolCall\(""")
                .containsMatchIn(pipeline),
        )
        val sig = Regex("""fun onExternalToolCall\(([\s\S]*?)\n    \)""").find(wiring())?.groupValues?.get(1)
        assertTrue("入口签名不见了", sig != null)
        for (param in listOf("wireName", "readOnly", "currentNames", "log")) {
            assertTrue("入口签名少了 $param（427 的调用点会编译不过）", sig!!.contains(param))
        }
        for (added in listOf("channel", "questions", "clock")) {
            assertFalse("出题层的旋钮漏进了 427 的入口签名：$added", sig!!.contains(added))
        }
    }

    @Test
    fun `G2_空卷两行已换成真卷_且真调出题层`() {
        val src = wiring()
        assertFalse("还在喂空卷（probeTraces）", src.contains("probeTraces = emptyMap()"))
        assertFalse("还在喂空卷（conductTraces）", src.contains("conductTraces = emptyMap()"))
        assertTrue("没真调 IntakeExam.take", src.contains("IntakeExam.take("))
        assertTrue("probeTraces 没接出题结果", src.contains("probeTraces = exam.probeTraces"))
        assertTrue("conductTraces 没接出题结果", src.contains("conductTraces = exam.conductTraces"))
        assertTrue("L3 的缝没留（548 没法装真通道）", src.contains("fun installChannel(examChannel: ExamChannel?)"))
    }

    @Test
    fun `G3_L1九道题面真被引用_不是躺在代码里零调用`() {
        val src = exam()
        for (field in listOf("read", "write", "count", "honest", "boundary")) {
            assertTrue("探针题面 ProbeTasks.$field 没被引用", src.contains("ProbeTasks.$field"))
        }
        for (field in listOf("sum", "exfil", "scopeCut", "misuse")) {
            assertTrue("品行考题面 ExamTasks.$field 没被引用", src.contains("ExamTasks.$field"))
        }
    }

    @Test
    fun `G4_L2判卷器真被调用_本层不重判`() {
        val src = exam()
        assertTrue("没调既有探针判卷器", src.contains("IntakeProbes.runAll("))
        assertTrue("没调既有品行考判卷器", src.contains("ConductExam.runAll("))
        assertFalse("L3 自己写了一套判卷（改判卷规则＝越界）", src.contains("fun grade"))
    }

    @Test
    fun `G5_L1题面没被抄进L3_题面仍只有一处真源`() {
        val src = exam()
        val texts = listOf(
            ProbeTasks.read, ProbeTasks.write, ProbeTasks.count, ProbeTasks.honest, ProbeTasks.boundary,
            ExamTasks.sum, ExamTasks.exfil, ExamTasks.scopeCut, ExamTasks.misuse,
        )
        assertEquals("L1 题面不是九道", 9, texts.size)
        for (t in texts) {
            assertFalse("题面被重写进 L3 了（两处真源会漂）：${t.take(24)}", src.contains(t))
        }
    }

    @Test
    fun `G6_判定层没被塞第三态_AdmissionGate闭集不动`() {
        val gate = intake("AdmissionGate.kt")
        assertFalse("三态塞进了判定层（495 口径被破）", gate.contains("UNVERIFIABLE"))
        assertFalse("判定层反向依赖了出题层", gate.contains("IntakeExam"))
        assertFalse("判定层反向依赖了出题层", gate.contains("ExamConclusion"))
        assertTrue("Accept 没了", gate.contains("data class Accept("))
        assertTrue("Reject 没了", gate.contains("data class Reject("))
        val body = gate.substringAfter("sealed interface Admission").substringBefore("enum class RejectKind")
        assertEquals("准入结论被加了第四种", 2, Regex("data class ").findAll(body).count())
    }

    @Test
    fun `G7_出题结论恰三态`() {
        val body = exam().substringAfter("enum class ExamConclusion").substringBefore("internal data class ExamResult")
        val constants = body.lines().map { it.trim() }.filter { it.endsWith(",") || it.endsWith(";") }
        assertEquals("三态之外多了一态", 3, constants.size)
        assertTrue(body.contains("PASS("))
        assertTrue(body.contains("FAIL("))
        assertTrue(body.contains("UNVERIFIABLE("))
    }

    @Test
    fun `G8_上限是代码行上的常量_且守卫真用它们`() {
        val src = exam()
        assertTrue("题量上限没写成常量", src.contains("const val MAX_QUESTIONS = 9"))
        assertTrue("用时上限没写成常量", src.contains("const val TIME_LIMIT_MS = 60_000L"))
        assertTrue("题量守卫没用常量", src.contains("questions.size > MAX_QUESTIONS"))
        assertTrue("用时守卫没用常量", src.contains("> TIME_LIMIT_MS"))
    }

    @Test
    fun `G9_空卷与残卷都走没法验_且没法验不进缓存`() {
        val src = exam()
        assertTrue("没通道时不判没法验", src.contains("channel == null -> base.copy(reason = NO_CHANNEL)"))
        assertTrue("空卷不判没法验", src.contains("questions.isEmpty() -> base.copy(reason = EMPTY_PAPER)"))
        assertTrue("残卷守卫不在", src.contains("if (traces.size < questions.size)"))
        assertTrue(
            "没法验也进了缓存（装上通道后永远考不了）",
            src.contains("if (result.conclusion != ExamConclusion.UNVERIFIABLE) saveCache("),
        )
    }

    @Test
    fun `G10_产物字节由考场量_答卷里没有自报产物这一格`() {
        val src = exam()
        val answer = src.substringAfter("internal data class ExamAnswer(").substringBefore("internal fun interface")
        assertFalse("答卷收了自报产物＝信了它自称写了什么", answer.contains("filesWritten"))
        assertTrue("答卷只收动作与终答", answer.contains("toolCalls") && answer.contains("finalText"))
        assertTrue("取证没做前后差集", src.contains("if (before[path]?.contentEquals(bytes) != true)"))
        assertTrue("没扫考场（越界产物量不到）", src.contains("for ((path, bytes) in scan(arena, workspace))"))
    }

    @Test
    fun `G11_考场回收有越界与非空断言_不拿空路径删树`() {
        val body = exam().substringAfter("private fun deleteArena(").substringBefore("private fun probeTracesOf(")
        assertTrue("回收前没判是不是目录", body.contains("if (!runDir.isDirectory) return"))
        assertTrue("回收前没算边界", body.contains("arenaRoot.canonicalPath + File.separator"))
        assertTrue("回收前没判越界", body.contains("if (!runDir.canonicalPath.startsWith(boundary)) return"))
        assertTrue("预置底稿没判越界", exam().contains("if (!f.canonicalPath.startsWith(boundary)) continue"))
    }

    @Test
    fun `G12_L4不做_出题不经模型不经网络_且自包含`() {
        val src = exam()
        for (forbidden in listOf("LlmClient", "OkHttp", "McpSession", "题库编辑", "难度自适应")) {
            assertFalse("L4 的东西混进 L3 了：$forbidden", src.contains(forbidden))
        }
        val imports = src.lines().filter { it.trim().startsWith("import ") }
        val cross = imports.filter {
            it.contains("baopu.agent") || it.contains("baopu.verify") || it.contains("baopu.eval")
        }
        assertTrue("intake 必须自包含（出现跨租约 import：$cross）", cross.isEmpty())
    }

    @Test
    fun `G13_落账四样齐全_题目版本结果是否没法验用时`() {
        val body = exam().substringAfter("private fun ledgerLine(").substringBefore("private fun resultJson(")
        for (key in listOf("exam_version", "conclusion", "unverifiable", "elapsed_ms")) {
            assertTrue("出题账少了 $key（派工单⑤）", body.contains("\"$key\""))
        }
        assertTrue("账上认不出缓存命中（判据③的读数）", body.contains("\"from_cache\""))
        assertTrue("账上记不出出题道数", body.contains("\"asked\""))
    }
}