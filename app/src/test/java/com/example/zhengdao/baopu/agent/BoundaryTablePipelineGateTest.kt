// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.RiskClass
import com.example.zhengdao.baopu.RiskTable
import com.example.zhengdao.baopu.ToolRule
import com.example.zhengdao.baopu.escalate.BoundaryRow
import com.example.zhengdao.baopu.escalate.BoundaryTable
import com.example.zhengdao.baopu.escalate.CapabilityKind
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors

/**
 * 蓝总管验收补（2026-11-08）：**边界声明表在管线上真的拦得住写**。
 *
 * 为什么补：517 原有 `BoundaryTableGateTest` 全部打在纯函数 `BoundaryTable.checkWrite` 上，
 * 而 `GuardPipeline.writeRedLines` 那一段"拒了就 return"的接线**没有任何行为用例**。
 * 我把 `if (!ruling.allowed)` 临时改成恒假（表拒了也放行）⇒ **全部测试仍绿**——
 * 也就是说「边界表拒 ⇒ 写被拒」这条判据当时没被钉住，只被"纯函数会说不"钉住。
 *
 * 本用例走真管线：`write_file`（EDIT 组，非只读 ⇒ 能力 FILE_WRITE）→ 表把可写根置空
 * ⇒ 期望 `Result.Rejected` 且理由是「不可写」。
 */
class BoundaryTablePipelineGateTest {

    /**
     * **必须收尾**：`BoundaryTable` 是进程级单例，装了不卸会把「可写根」钉在临时目录上，
     * 后面任何在本 JVM 里跑管线写用例的测试类都会被我这条表连坐（我第一次没清，
     * 全量一跑 26 例红）。`resetForTest()` 回到"未装表"态（判定恒放行）。
     */
    @After
    fun tearDown() {
        BoundaryTable.resetForTest()
    }

    private fun tmp(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    private fun registry(): ToolRegistry = ToolRegistry().apply {
        register(
            ToolSpec(
                name = "write_file",
                description = "假写工具（组由 ToolGroupTable 盖章为 EDIT）",
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

    private fun pipeline(root: File): GuardPipeline = GuardPipeline(
        registry(),
        { root },
        { null },
        ToolRunner(Executors.newSingleThreadExecutor()),
        {},
        riskTable = RiskTable.DEFAULT.copy(tools = RiskTable.DEFAULT.tools + ("write_file" to ToolRule(RiskClass.R0))),
    )

    /** 装一份表：FILE_WRITE 的可写根由入参决定；FILE_READ 给工作区（读不受影响）。 */
    private fun installTable(root: File, writeRoots: List<String>) {
        val rows = listOf(
            BoundaryRow(CapabilityKind.FILE_WRITE, writableRoots = writeRoots),
            BoundaryRow(CapabilityKind.FILE_READ, writableRoots = listOf(BoundaryTable.ROOT_WORKSPACE)),
        )
        File(root, BoundaryTable.FILE_NAME).writeText(BoundaryTable.toJson(rows).toString(), Charsets.UTF_8)
        BoundaryTable.install(root) { mapOf(BoundaryTable.ROOT_WORKSPACE to listOf(root)) }
    }

    @Test
    fun `边界表可写根置空_管线必须拒写而不是照写`() {
        val root = tmp("zd517-pipe-deny-")
        installTable(root, writeRoots = emptyList())
        val call = ToolCall("c1", "write_file", JSONObject().put("path", File(root, "note.md").absolutePath))
        val r = pipeline(root).execute(call, { _, _ -> null })
        assertTrue("表拒绝时管线必须 Rejected，实际：$r", r is GuardPipeline.Result.Rejected)
        assertTrue(
            "拒因要来自边界表（不可写）：${(r as GuardPipeline.Result.Rejected).reason}",
            r.reason.contains("不可写"),
        )
        assertTrue("没写盘", !File(root, "note.md").exists())
    }

    @Test
    fun `同一张表把可写根给回来_同一条写要放行`() {
        val root = tmp("zd517-pipe-allow-")
        installTable(root, writeRoots = listOf(BoundaryTable.ROOT_WORKSPACE))
        val call = ToolCall("c2", "write_file", JSONObject().put("path", File(root, "note.md").absolutePath))
        val r = pipeline(root).execute(call, { _, _ -> null })
        assertTrue("表放行时不该被拒，实际：$r", r !is GuardPipeline.Result.Rejected)
    }
}
