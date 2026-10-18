// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.baopu.kernel

import com.example.zhengdao.baopu.SegmentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 内核守卫（第二十六批 · 26-A）：钉住「四处真相源收成一处」这件事，别让它悄悄漂回去。
 *
 * 六组判据（26-A）＋ 三组（26-B）：
 * ① **内核不带 Android/I/O**（扫**整个内核包**，新加文件自动被管）；
 * ② **七条边界都在**（Data／Exec／Tools／Auth／Budget／Egress／Gate）；
 * ③ **唯一来源**：被搬进内核的常量，主源码里不许再出现「字面量定义」；
 * ④ **委托存在**：`RunRules` 与 `ContextAssembler` 的常量是引用内核，不是各写一份；
 * ⑤ **值没变**（纯重构）：几个关键数逐一对齐（16 步／3 次失败／15 分钟／0.85／120k）；
 * ⑥ **停止权那一族六个数齐全**（用户补的第 4 条边界）；
 * ⑦ **压缩红线只有一处来源**（26-B 把 `NEVER_COMPRESS` 整块搬进内核）；
 * ⑧ **spill 阈值也搬进内核**（工具边界那一族）；
 * ⑨ **内核项变更留痕**：`[kernel] …` 的格式与设置页接线。
 */
class KernelGuardTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.exists()) it else File("app/$rel") }

    private fun kernel() = repoFile("src/main/java/com/example/zhengdao/baopu/kernel/BaopuKernel.kt").readText()
    private fun rules() = repoFile("src/main/java/com/example/zhengdao/baopu/agent/RunRules.kt").readText()
    private fun asm() = repoFile("src/main/java/com/example/zhengdao/baopu/ContextAssembler.kt").readText()
    private fun guard() = repoFile("src/main/java/com/example/zhengdao/baopu/agent/GuardPipeline.kt").readText()

    /** 内核包里**每一个**文件（K1 必须扫全包：新加一个文件就自动被管住）。 */
    private fun kernelFiles(): List<File> {
        val dir = repoFile("src/main/java/com/example/zhengdao/baopu/kernel")
        require(dir.isDirectory) { "内核包目录没找到：${dir.absolutePath}" }
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".kt") }!!.sortedBy { it.name }
    }

    @Test
    fun `K1_内核不带Android与IO`() {
        val files = kernelFiles()
        assertTrue("内核包至少要有一个文件", files.isNotEmpty())
        files.forEach { f ->
            val src = f.readText()
            listOf("import android.", "import androidx.", "import java.io.", "import java.net.").forEach {
                assertFalse("内核里不许出现 $it（只装判据，不装实现）：${f.name}", src.contains(it))
            }
        }
    }

    @Test
    fun `K2_七条边界都在且不空`() {
        val src = kernel()
        listOf("object Data", "object Exec", "object Tools", "object Auth", "object Budget", "object Egress", "object Gate")
            .forEach { assertTrue("缺边界：$it", src.contains(it)) }
        // 两个有真值的组必须有常量；其余组允许先只有 KDoc（26-B 搬实现时补齐）
        assertTrue("Budget 组必须有常量", src.contains("const val MAX_STEPS"))
        assertTrue("Auth 组必须有常量", src.contains("const val CONFIRM_TIMEOUT_MS"))
    }

    @Test
    fun `K3_唯一来源_主源码不许再写字面量定义`() {
        val duplicates = listOf(
            "const val MAX_STEPS = 16",
            "const val MAX_TOOL_FAILURES = 3",
            "const val CONFIRM_TIMEOUT_MS = 15 * 60 * 1000L",
            "const val COMPRESS_ABS_MAX_TOKENS = 120_000",
            "const val COMPRESS_THRESHOLD = 0.85",
        )
        val rulesSrc = rules()
        val asmSrc = asm()
        duplicates.forEach { literal ->
            assertFalse("RunRules 里不许再写「$literal」（应引用 Kernel）", rulesSrc.contains(literal))
            assertFalse("ContextAssembler 里不许再写「$literal」（应引用 Kernel）", asmSrc.contains(literal))
        }
    }

    @Test
    fun `K4_委托存在_RunRules与ContextAssembler都引用内核`() {
        assertTrue("RunRules 引内核", rules().contains("import com.example.zhengdao.baopu.kernel.BaopuKernel"))
        assertTrue("RunRules 委托 MAX_STEPS", rules().contains("= BaopuKernel.Budget.MAX_STEPS"))
        assertTrue("RunRules 委托确认超时", rules().contains("= BaopuKernel.Auth.CONFIRM_TIMEOUT_MS"))
        val delegated = Regex("= BaopuKernel\\.").findAll(rules()).count()
        assertTrue("RunRules 里委托常量的处数 = $delegated，至少 11 个才算把这一族搬进去", delegated >= 11)
        assertTrue("ContextAssembler 引内核", asm().contains("import com.example.zhengdao.baopu.kernel.BaopuKernel"))
        assertTrue("压缩阈值必须委托", asm().contains("COMPRESS_THRESHOLD = BaopuKernel.Budget.COMPRESS_THRESHOLD"))
    }

    @Test
    fun `K5_值没变_纯重构`() {
        assertEquals("16 步刹车", 16, BaopuKernel.Budget.MAX_STEPS)
        assertEquals("3 次工具失败", 3, BaopuKernel.Budget.MAX_TOOL_FAILURES)
        assertEquals("自修 1 次", 1, BaopuKernel.Budget.MAX_VERIFY_REPAIRS)
        assertEquals("熔断 3 次", 3, BaopuKernel.Budget.CHANNEL_BREAK_THRESHOLD)
        assertEquals("确认超时 15 分钟", 15 * 60 * 1000L, BaopuKernel.Auth.CONFIRM_TIMEOUT_MS)
        assertEquals("压缩阈值 0.85", 0.85, BaopuKernel.Budget.COMPRESS_THRESHOLD, 1e-9)
        assertEquals("压缩绝对上限 120k", 120_000, BaopuKernel.Budget.COMPRESS_ABS_MAX_TOKENS)
        assertEquals("可压条数下限 60", 60, BaopuKernel.Budget.COMPRESS_OLD_MESSAGE_MIN)
        assertEquals("单次压缩上限 200", 200, BaopuKernel.Budget.COMPRESS_MAX_MESSAGES_PER_RUN)
    }

    @Test
    fun `K6_停止权六个数齐全`() {
        val b = BaopuKernel.Budget
        // 编译期就保证它们都在：数量型常量逐一取值，少一个就编译不过
        val six = listOf(
            b.MAX_STEPS, b.MAX_TOOL_FAILURES, b.MAX_VERIFY_REPAIRS,
            b.CHANNEL_BREAK_THRESHOLD,
            BaopuKernel.Auth.CONFIRM_TIMEOUT_MS.toInt(),
            BaopuKernel.Auth.CONFIRM_POLL_MS.toInt(),
        )
        assertEquals("停止/预算族应恰有 6 个基本数（步数/失败/自修/熔断/超时/轮询）", 6, six.size)
    }

    // ── 26-B：压缩红线搬家 ＋ spill 委托 ＋ 内核项留痕 ─────────────────────────

    @Test
    fun `K7_压缩红线只有一处来源_且正是那五段`() {
        // ① 内容对：五段永不压，压缩/历史/本轮输入必须能压
        val red = BaopuKernel.Budget.NEVER_COMPRESS
        assertEquals(
            "压缩红线就是人设三层＋硬事实＋记忆这五段（少一个＝悄悄放开压缩）",
            setOf(
                SegmentKind.PERSONA_A, SegmentKind.PERSONA_B, SegmentKind.PERSONA_C,
                SegmentKind.HARD_FACTS, SegmentKind.MEMORY,
            ),
            red,
        )
        listOf(SegmentKind.COMPRESSION, SegmentKind.HISTORY, SegmentKind.CURRENT).forEach {
            assertFalse("$it 不属于压缩红线（它就是要被压的那部分）", it in red)
        }
        // ② 来源唯一：ContextAssembler 里只剩委托，不许再自己列一遍段名
        //    （注意：本文件里正常的段构造当然还会出现 SegmentKind.X，所以只钉「集合再建一份」）
        val asmSrc = asm()
        assertTrue("ContextAssembler 必须委托内核", asmSrc.contains("= BaopuKernel.Budget.NEVER_COMPRESS"))
        assertFalse("ContextAssembler 里不许再自己 setOf 一份红线", asmSrc.contains("NEVER_COMPRESS = setOf("))
        val dup = Regex("""NEVER_COMPRESS[^\n]*setOf\(""").findAll(kernel() + asmSrc).count()
        assertTrue("压缩红线的 setOf 全仓只许有一份，实际 $dup 份", dup == 1)
    }

    @Test
    fun `K8_spill阈值也搬进内核且值没变`() {
        assertEquals("落盘阈值 4000", 4_000, BaopuKernel.Tools.SPILL_THRESHOLD_CHARS)
        assertEquals("摘要 2000", 2_000, BaopuKernel.Tools.SPILL_SNIPPET_CHARS)
        val g = guard()
        assertTrue("GuardPipeline 委托落盘阈值", g.contains("SPILL_THRESHOLD_CHARS = BaopuKernel.Tools.SPILL_THRESHOLD_CHARS"))
        assertTrue("GuardPipeline 委托摘要长度", g.contains("SPILL_SNIPPET_CHARS = BaopuKernel.Tools.SPILL_SNIPPET_CHARS"))
        assertFalse("GuardPipeline 里不许再写字面量 4_000", g.contains("SPILL_THRESHOLD_CHARS = 4_000"))
    }

    @Test
    fun `K9_内核项变更留痕_格式与接线`() {
        // ① 格式：`[kernel] 工作区改为 <新>（原 <旧>）`
        val line = KernelAudit.workspace("/storage/a/证道", "/storage/a/旧")
        assertEquals("[kernel] 工作区改为 /storage/a/证道（原 /storage/a/旧）", line)
        assertTrue("必须以 [kernel] 开头（grep 锚）", line.startsWith(KernelAudit.TAG))
        assertEquals("`[kernel] 通道改为 x（原 y）`", "[kernel] 通道改为 x（原 y）", KernelAudit.channel("x", "y"))
        // 值里带换行不许把一条留痕劈成两行
        assertFalse("留痕必须单行", KernelAudit.workspace("a\nb", "c").contains("\n"))
        // ② 接线：设置页改工作区时必须写这一行
        val ui = repoFile("src/main/java/com/example/zhengdao/ui/baopu/BaopuSettingsScreen.kt").readText()
        assertTrue("设置页要调 RunLog.log(KernelAudit.…)", ui.contains("RunLog.log(KernelAudit.workspace(after, before))"))
        assertTrue("设置页要有「改这里会记日志」的小字", ui.contains("R.string.baopu_ws_log_note"))
        assertTrue("三个入口都要留痕", Regex("logWs\\(before\\)").findAll(ui).count() >= 3)
    }
}
