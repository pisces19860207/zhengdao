// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百二十八批 · 金吾卫喂入口通电的判据测试（派工单五判据）：
//   ① Result → StepResultCode 纯映射：五种结果各一测（超时如实归 FAILED——闭集没有超时值）；
//   ② observe 有生产调用点：源级扫 GuardPipeline.kt（剥注释）＋ ZhengdaoApp 装配次序；
//   ③ 装配失败 ⇒ UNAVAILABLE（目录建不了／账读不了两个形态；喂入口 no-op——
//      「没巡查」≠「巡查没问题」）；
//   ④ 模拟进程重启：attempts 不归零（resetForTest 只清内存不删盘，三轮到交人线）；
//   ⑤ 勘验来源（GapSource 第三家）只留位：主源码零字样＋行为面喂遍五结果全是金吾卫来源。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.inspect.JinwuPatrol
import com.example.zhengdao.baopu.inspect.StepResultCode
import com.example.zhengdao.baopu.verify.GapFeedback
import com.example.zhengdao.baopu.verify.GapKind
import com.example.zhengdao.baopu.verify.GapSource
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JinwuObserveTest {

    private val tempRoots = mutableListOf<File>()

    @After
    fun cleanup() {
        JinwuObserve.resetForTest()
        JinwuPatrol.resetForTest()
        GapFeedback.resetForTest()
        tempRoots.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun newTempDir(): File =
        Files.createTempDirectory("jinwu-observe").toFile().also { tempRoots += it }

    private fun registry(): ToolRegistry = ToolRegistry().apply { register(ReadFileTool.spec()) }

    /** 同一个 call 反复喂（同 id／同参数摘要）——T2「同一动作重复失败」要的就是这个。 */
    private fun call(): ToolCall = ToolCall("c1", "read_file", JSONObject().put("path", "a.md"))

    private fun attachFresh(dir: File = newTempDir()): File {
        JinwuObserve.attachAt(dir)
        return dir
    }

    // ── 判据①：映射纯函数（五种结果各一测） ─────────────────────────────────

    @Test
    fun `映射1_Done为DONE`() {
        val r = GuardPipeline.Result.Done(call(), "围栏文本", spilled = false)
        assertEquals(StepResultCode.DONE, JinwuObserve.resultCodeOf(r, schemaInvalid = false))
    }

    @Test
    fun `映射2_普通失败为FAILED`() {
        val r = GuardPipeline.Result.Failed(call(), "工具「read_file」执行失败")
        assertEquals(StepResultCode.FAILED, JinwuObserve.resultCodeOf(r, schemaInvalid = false))
    }

    @Test
    fun `映射3_超时如实归FAILED_闭集没有TIMEOUT不虚设`() {
        // 超时在管线里就是 Result.Failed（ToolRunner.timeoutText 原文）——映射不另开口
        val timeout = ToolRunner.timeoutText("read_file", 1_000)
        val r = GuardPipeline.Result.Failed(call(), timeout)
        assertEquals(StepResultCode.FAILED, JinwuObserve.resultCodeOf(r, schemaInvalid = false))
    }

    @Test
    fun `映射4_被拒为REJECTED`() {
        val r = GuardPipeline.Result.Rejected(call(), "路径被拒：在系统目录里")
        assertEquals(StepResultCode.REJECTED, JinwuObserve.resultCodeOf(r, schemaInvalid = false))
    }

    @Test
    fun `映射5_schema不符为SCHEMA_INVALID`() {
        val r = GuardPipeline.Result.Failed(call(), "参数与 schema 不符")
        assertEquals(StepResultCode.SCHEMA_INVALID, JinwuObserve.resultCodeOf(r, schemaInvalid = true))
    }

    @Test
    fun `映射6_需确认为NEEDS_CONFIRM_闭集全覆盖`() {
        val r = GuardPipeline.Result.NeedsConfirm(call(), "R2 必问")
        assertEquals(StepResultCode.NEEDS_CONFIRM, JinwuObserve.resultCodeOf(r, schemaInvalid = false))
    }

    // ── 喂入口端到端（映射真的接进了采样） ───────────────────────────────────

    @Test
    fun `端到端_schema错触发T1进板_来源是金吾卫`() {
        attachFresh()
        val reg = registry()
        val bad = ToolCall("c1", "read_file", JSONObject()) // 缺 required path ⇒ validateCall 非 null
        JinwuObserve.observe(GuardPipeline.Result.Failed(bad, "参数错"), bad, reg)
        val pending = GapFeedback.pending()
        assertEquals(1, pending.size)
        assertEquals(GapSource.JINWU, pending.first().source)
        assertEquals(GapKind.SCHEMA_VIOLATION, pending.first().kind)
    }

    @Test
    fun `端到端_普通失败不触发T1_不虚报schema违规`() {
        attachFresh()
        val reg = registry()
        // 这条 Failed 带的 call 过得了 schema 校验（path 在）——映射必须归 FAILED 而非 SCHEMA_INVALID
        JinwuObserve.observe(GuardPipeline.Result.Failed(call(), "工具失败"), call(), reg)
        assertTrue("普通失败被虚报成 schema 违规", GapFeedback.pending().isEmpty())
    }

    @Test
    fun `端到端_被拒与需确认与成功_只记步不产差距`() {
        attachFresh()
        val reg = registry()
        JinwuObserve.observe(GuardPipeline.Result.Rejected(call(), "拒"), call(), reg)
        JinwuObserve.observe(GuardPipeline.Result.NeedsConfirm(call()), call(), reg)
        JinwuObserve.observe(GuardPipeline.Result.Done(call(), "好", spilled = false), call(), reg)
        assertTrue(GapFeedback.pending().isEmpty())
    }

    // ── 判据③：装配失败 ⇒ UNAVAILABLE ──────────────────────────────────────

    @Test
    fun `装配失败_目录建不了_UNAVAILABLE且喂入口no-op`() {
        val blocker = File(newTempDir(), "占位文件").apply { writeText("我是一个文件，不是目录") }
        JinwuObserve.attachAt(blocker) // 文件当目录 ⇒ mkdirs 失败
        assertFalse(JinwuObserve.isAvailable)
        assertNotNull(JinwuObserve.unavailableReasonOrNull)
        // 喂了也不进板：观测层没装配就不许装作在巡查
        JinwuObserve.observe(GuardPipeline.Result.Failed(call(), "失败"), call(), registry())
        assertTrue(GapFeedback.pending().isEmpty())
    }

    @Test
    fun `装配失败_账读不了_UNAVAILABLE且wiring账记一行`() {
        val dir = newTempDir()
        File(dir, "gap-attempts.json").writeText("这不是json")
        JinwuObserve.attachAt(dir)
        assertFalse("坏账静默吞掉＝续跑不可靠却装绿", JinwuObserve.isAvailable)
        assertTrue(File(dir, "wiring.jsonl").readText().contains("unavailable"))
    }

    @Test
    fun `装配成功_available且wiring账记available`() {
        val dir = attachFresh()
        assertTrue(JinwuObserve.isAvailable)
        assertEquals(null, JinwuObserve.unavailableReasonOrNull)
        assertTrue(File(dir, "wiring.jsonl").readText().contains("\"available\""))
    }

    @Test
    fun `装配幂等_重复attach不重记`() {
        val dir = attachFresh()
        JinwuObserve.attachAt(newTempDir()) // 第二次装配被忽略
        assertTrue(File(dir, "wiring.jsonl").readLines().size == 1)
    }

    // ── 判据④：模拟进程重启，attempts 不归零 ────────────────────────────────

    @Test
    fun `重启续跑_三轮attempts接续到交人线`() {
        val dir = newTempDir()
        val reg = registry()
        // 进程 A：同一动作失败×2 ⇒ T2 触发一次 ⇒ 板 attempts=1、磁盘账=1
        JinwuObserve.attachAt(dir)
        repeat(2) { JinwuObserve.observe(GuardPipeline.Result.Failed(call(), "工具失败"), call(), reg) }
        val key = GapFeedback.pending().single().gapKey
        assertEquals(1, GapFeedback.pending().single { it.gapKey == key }.attempts)

        // 模拟进程死：内存全清，磁盘账还在（resetForTest 只清内存不删盘）
        JinwuObserve.resetForTest()
        JinwuPatrol.resetForTest()
        GapFeedback.resetForTest()

        // 进程 B：再失败×2 ⇒ 金吾卫眼里这是新进程的第 2 次失败（触发）；
        // 磁盘账=2、板空 ⇒ 喂历史总数 ⇒ 板 attempts=2（不归零）
        JinwuObserve.attachAt(dir)
        repeat(2) { JinwuObserve.observe(GuardPipeline.Result.Failed(call(), "工具失败"), call(), reg) }
        assertEquals(2, GapFeedback.pending().single { it.gapKey == key }.attempts)

        // 进程 C：同样再来一轮 ⇒ 板 attempts=3 ＝ 交人线（GapFeedback 的「第 3 次交人」到得了）
        JinwuObserve.resetForTest()
        JinwuPatrol.resetForTest()
        GapFeedback.resetForTest()
        JinwuObserve.attachAt(dir)
        repeat(2) { JinwuObserve.observe(GuardPipeline.Result.Failed(call(), "工具失败"), call(), reg) }
        val item = GapFeedback.pending().single { it.gapKey == key }
        assertEquals(GapFeedback.HAND_OVER_ATTEMPTS, item.attempts)

        // 磁盘账本身也在盘上（跨进程续跑的物证）
        assertEquals("{\"$key\":3}", File(dir, "gap-attempts.json").readText())
    }

    // ── 判据⑤：勘验来源只留位——无产出 ─────────────────────────────────────

    @Test
    fun `勘验留位_源级_喂入口主源码零勘验字样`() {
        // 留位＝连分支都没有：接线层出现那条来源的字样就是越权实现
        listOf("agent/JinwuObserve.kt", "agent/GuardPipeline.kt").forEach { rel ->
            val src = File("src/main/java/com/example/zhengdao/baopu/$rel").readText(Charsets.UTF_8)
            assertFalse("勘验来源在 $rel 出现（只留位不实现）", src.contains("KANLI"))
        }
    }

    @Test
    fun `勘验留位_行为_喂遍五种结果_板上来源全是金吾卫`() {
        attachFresh()
        val reg = registry()
        JinwuObserve.observe(GuardPipeline.Result.Done(call(), "好", spilled = false), call(), reg)
        JinwuObserve.observe(GuardPipeline.Result.Failed(call(), "失败"), call(), reg)
        JinwuObserve.observe(GuardPipeline.Result.Rejected(call(), "拒"), call(), reg)
        JinwuObserve.observe(GuardPipeline.Result.NeedsConfirm(call()), call(), reg)
        JinwuObserve.observe(
            GuardPipeline.Result.Failed(ToolCall("c2", "read_file", JSONObject()), "参数错"),
            ToolCall("c2", "read_file", JSONObject()),
            reg,
        )
        val pending = GapFeedback.pending()
        assertTrue("板上该有 schema 违规一条（喂入口真的在工作）", pending.isNotEmpty())
        pending.forEach { item ->
            assertEquals("板上混进了勘验来源的条目", GapSource.JINWU, item.source)
        }
    }

    // ── 判据②：observe 有生产调用点（源级门禁） ─────────────────────────────

    @Test
    fun `源级_管线里有金吾卫喂入口_删了即红`() {
        val src = code(
            File("src/main/java/com/example/zhengdao/baopu/agent/GuardPipeline.kt")
                .readText(Charsets.UTF_8),
        )
        val sites = Regex("""JinwuObserve\s*\.\s*observe\s*\(""").findAll(src).count()
        assertTrue(
            "管线里的喂入口只剩 $sites 处（Settled 收口＋执行收口至少各一）——313 巡查又断电了",
            sites >= 2,
        )
    }

    @Test
    fun `源级_装配在AppForeground之后_派工单钉的次序`() {
        val src = File("src/main/java/com/example/zhengdao/ZhengdaoApp.kt").readText(Charsets.UTF_8)
        val foreground = src.indexOf("AppForeground.install(")
        val attach = src.indexOf("JinwuObserve.attach(")
        assertTrue("ZhengdaoApp 没装配金吾卫喂入口", attach >= 0)
        assertTrue("装配必须晚于 AppForeground.install", foreground in 0 until attach)
    }

    /** 剥注释的代码行（源级扫描口径：注释里说「调了」不算调了）。 */
    private fun code(text: String): String = text
        .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        .replace(Regex("(?m)//.*$"), " ")
}
