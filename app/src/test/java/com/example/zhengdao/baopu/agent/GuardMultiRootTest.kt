// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：492 工单 A 段②「相对路径按根序解析；同名歧义 ⇒ 报错列候选」＋「RO 写拒」打在
// **管线第 ② 段**上的那半边（工具只认绝对路径，解析与拒全在守卫——所以判据必须打守卫）。
// 房型照 GuardPipelineTest 的 harness；判据 ④（单根 0 回归）用「缺省 extraRoots」对照钉住。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.RootMode
import com.example.zhengdao.baopu.WsRoot
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardMultiRootTest {

    private fun tmp(name: String): File = Files.createTempDirectory("ws492-guard-$name").toFile()

    private fun registry(): ToolRegistry = ToolRegistry().apply {
        register(ReadFileTool.spec())
        register(WriteFileTool.spec { java.io.File(".") }) // 根只关备份落点；本测试只走到 ② 段
    }

    private fun pipeline(root: File, extras: List<WsRoot>): GuardPipeline = GuardPipeline(
        registry = registry(),
        workspaceRoot = { root },
        rawDir = { null },
        extraRoots = { extras },
        runner = ToolRunner(Executors.newSingleThreadExecutor()),
        log = { },
        // 本文件钉的是 492 的 RO/多根语义＝**现状**行为；499 的档位闸按严格档钉住，
        // 免得标准档（缺省）的「根内 R1 不问」把"走到确认卡"那条断言改写掉。
        askTier = { AskTier.STRICT },
    )

    private fun call(name: String, args: JSONObject): ToolCall = ToolCall("c1", name, args)

    private fun readCall(path: String): ToolCall = call("read_file", JSONObject().put("path", path))

    private fun writeCall(path: String): ToolCall = call(
        "write_file",
        JSONObject().put("path", path).put("content", "x"),
    )

    // ── A②：多根解析（改写进绝对路径） ───────────────────────────────────────

    @Test
    fun `相对路径_只在一个附加根存在_改写成那个根的绝对路径`() {
        val root = tmp("primary")
        val ro = tmp("ro")
        File(ro, "99_速记/笔记.md").let { f -> f.parentFile.mkdirs(); f.writeText("内容") }
        val p = pipeline(root, listOf(WsRoot(ro, RootMode.RO, "资料")))
        val prepared = p.prepare(readCall("99_速记/笔记.md"))
        assertTrue("应放行（Ready）", prepared is GuardPipeline.Prepared.Ready)
        val rewritten = (prepared as GuardPipeline.Prepared.Ready).call.arguments.optString("path")
        assertEquals(File(ro, "99_速记/笔记.md").absolutePath, rewritten)
    }

    @Test
    fun `相对路径_多处同名_Failed_列候选_不许猜`() {
        val root = tmp("primary")
        val ro = tmp("ro")
        listOf(root, ro).forEach { d -> File(d, "99_速记").mkdirs() }
        val p = pipeline(root, listOf(WsRoot(ro, RootMode.RO, "资料")))
        val prepared = p.prepare(readCall("99_速记"))
        assertTrue("歧义是可重试的 Failed（不是 Rejected 终止本轮）", prepared is GuardPipeline.Prepared.Settled)
        val result = (prepared as GuardPipeline.Prepared.Settled).result
        assertTrue("歧义 ⇒ Result.Failed", result is GuardPipeline.Result.Failed)
        val msg = (result as GuardPipeline.Result.Failed).error
        assertTrue("候选要列得出两个根", msg.contains("99_速记") && msg.contains("资料") && msg.contains(root.name))
    }

    @Test
    fun `A4_单根缺省_行为与旧算式逐字一致`() {
        val root = tmp("primary")
        File(root, "在.md").writeText("x")
        val p = pipeline(root, emptyList()) // 缺省形状：没有附加根
        val hit = p.prepare(readCall("在.md"))
        assertTrue(hit is GuardPipeline.Prepared.Ready)
        assertEquals(
            "存在的相对路径 ⇒ 主根下",
            File(root, "在.md").absolutePath,
            (hit as GuardPipeline.Prepared.Ready).call.arguments.optString("path"),
        )
        val missing = p.prepare(readCall("不在.md"))
        assertTrue(missing is GuardPipeline.Prepared.Ready)
        assertEquals(
            "不存在的相对路径 ⇒ 主根（旧 File(root, raw)）",
            File(root, "不在.md").absolutePath,
            (missing as GuardPipeline.Prepared.Ready).call.arguments.optString("path"),
        )
    }

    // ── RO 写拒 ─────────────────────────────────────────────────────────────

    @Test
    fun `RO写拒_写目标落RO根_Rejected_且先于确认卡`() {
        val root = tmp("primary")
        val ro = tmp("ro")
        File(ro, "99_速记").mkdirs()
        val p = pipeline(root, listOf(WsRoot(ro, RootMode.RO, "资料")))
        val prepared = p.prepare(writeCall("99_速记/新.md"))
        assertTrue("RO 写拒是硬边界（Rejected）", prepared is GuardPipeline.Prepared.Settled)
        val result = (prepared as GuardPipeline.Prepared.Settled).result
        assertTrue("write_file 是 CONFIRM 工具——拒必须发生在确认卡之前（② 段）", result is GuardPipeline.Result.Rejected)
        assertTrue((result as GuardPipeline.Result.Rejected).reason.contains("只读根"))
    }

    @Test
    fun `RO写拒_RW附加根照常走到确认卡_读RO根照常放行`() {
        val root = tmp("primary")
        val rw = tmp("rw")
        val p = pipeline(root, listOf(WsRoot(rw, RootMode.RW, "可写")))
        // RW 附加根在清单里：写主根照样 ② 段放行 ⇒ 走到 ③ 的确认（NeedsConfirm）
        val write = p.prepare(writeCall("新文件.md"))
        assertTrue("写主根不受 RO 影响", write is GuardPipeline.Prepared.Settled)
        assertTrue(
            "write_file CONFIRM ⇒ NeedsConfirm",
            (write as GuardPipeline.Prepared.Settled).result is GuardPipeline.Result.NeedsConfirm,
        )
        // 读 RO 附加根：放行（RO 挡手不挡眼）
        val ro = tmp("ro2")
        File(ro, "资料.md").writeText("x")
        val read = pipeline(root, listOf(WsRoot(ro, RootMode.RO, "资料"))).prepare(readCall("资料.md"))
        assertTrue("RO 根里的文件读得到", read is GuardPipeline.Prepared.Ready)
    }
}
