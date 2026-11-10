// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.RiskClass
import com.example.zhengdao.baopu.RiskTable
import com.example.zhengdao.baopu.ToolRule
import com.example.zhengdao.baopu.safety.SafetyHooks
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors
import com.example.zhengdao.testkit.TempSandboxPool
import org.junit.After

/**
 * 504：监督缝**在管线上**的钉子——
 * · 停机期（intercept 非 null）任何调用在 ① 之前就被拒；
 * · 写动作真要执行前 beforeRun 入库（拿到 opId 与目标路径）；
 * · 收口 afterRun 拿到工具结果（起居注行/关键通知的原料）。
 * 拦截拒绝（Rejected）不进 afterRun——没执行的动作不是"做过的事"，账上不冒行。
 */
class GuardPipelineSafetyTest {
    // 573：临时目录统一走 testkit/TempSandbox（经 TempSandboxPool 登记，@After 收口）；断言零改动。
    @After
    fun zdCleanSandboxes() = TempSandboxPool.clean()


    /** 记账用假缝（三口都收，intercept 可拨）。 */
    private class RecordingHooks : SafetyHooks {
        var interceptReason: String? = null
        var stashOpId: String? = null
        var stashPaths: List<String> = emptyList()
        var afterTool: String? = null
        var afterOk: Boolean? = null
        var afterCalls: Int = 0

        override fun intercept(toolName: String): String? = interceptReason

        override fun beforeRun(tool: String, opId: String, paths: List<String>) {
            stashOpId = opId
            stashPaths = paths
        }

        override fun afterRun(
            tool: String,
            readOnly: Boolean,
            riskTier: String,
            opId: String,
            paths: List<String>,
            ok: Boolean,
            toolRejected: Boolean,
            brief: String,
        ) {
            afterCalls++
            afterTool = tool
            afterOk = ok
        }
    }

    private fun tmp(): File = TempSandboxPool.create("safety-pipeline")

    private fun call(name: String, path: String): ToolCall =
        ToolCall("c-1", name, JSONObject().put("path", path))

    private fun registry(): ToolRegistry = ToolRegistry().apply {
        register(
            ToolSpec(
                name = "w",
                description = "假写工具（R0 直行，测监督缝）",
                inputSchema = jsonObjectOf(
                    "type" to "object",
                    "properties" to jsonObjectOf("path" to jsonObjectOf("type" to "string")),
                    "required" to jsonArrayOf("path"),
                ),
                permission = ToolPermission.FREE,
                riskTier = "R0",
                readOnly = false,
            ) { ToolOutcome.Ok("写了") },
        )
    }

    private fun pipeline(root: File, hooks: SafetyHooks): GuardPipeline = GuardPipeline(
        registry(),
        { root },
        { null },
        ToolRunner(Executors.newSingleThreadExecutor()),
        {},
        riskTable = RiskTable.DEFAULT.copy(tools = RiskTable.DEFAULT.tools + ("w" to ToolRule(RiskClass.R0))),
        supervision = hooks,
    )

    @Test
    fun `停机期_调用在管线最前被拒`() {
        val root = tmp()
        val hooks = RecordingHooks().apply { interceptReason = "已停机（软停），人工解除前不执行任何新动作" }
        val r = pipeline(root, hooks).execute(call("w", "x.txt"), { _, _ -> null })
        assertTrue(r is GuardPipeline.Result.Rejected)
        assertTrue((r as GuardPipeline.Result.Rejected).reason.contains("已停机"))
        assertEquals("拒了的调用没入库", null, hooks.stashOpId)
        assertEquals("拒了的调用没收口（没发生的事没有行）", 0, hooks.afterCalls)
    }

    @Test
    fun `写动作_执行前入库_收口拿到结果`() {
        val root = tmp()
        val hooks = RecordingHooks()
        val target = File(root, "x.txt")
        target.writeText("旧")
        val r = pipeline(root, hooks).execute(call("w", target.absolutePath), { _, _ -> null })
        assertTrue(r is GuardPipeline.Result.Done)
        assertEquals("c-1", hooks.stashOpId)
        assertTrue("入库路径含目标文件", hooks.stashPaths.any { it.endsWith("x.txt") })
        assertEquals("w", hooks.afterTool)
        assertEquals(true, hooks.afterOk)
        assertEquals("一次执行只收一次口", 1, hooks.afterCalls)
    }
}
