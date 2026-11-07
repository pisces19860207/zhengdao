// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百三十六批 B · 预读回执闸：**打真管线**（判据①②⑤ 的执行侧证据）。
//
// 为什么 `PreReadGateTest` 不够、这一本必须存在：判据写的是"在 GuardPipeline 执行前拦"，
// 那一句只有对着真管线才成立。板子与判据单独测绿了，只证明算得对；接线位置错了
// （挂在 ③ 之后／挂在执行之后）那边照样全绿，而盘上的文件已经写出去了。
//
// 三条最贵的断言：
//   ① 拦住 ⇒ `Failed`（不是 Rejected，也不是"记账放行"），且**盘上一个字节都没多**；
//   ② 交了 ⇒ `Done`，且**盘上真的有了那个文件**（"放行"不等于"执行了"，两头都要看）；
//   ③ 只读工具在未交回执时照走（判据⑤）——这条要是绿不了，就是"只拦写"变成了"全拦"。
package com.example.zhengdao.baopu.preread

import com.example.zhengdao.baopu.RiskClass
import com.example.zhengdao.baopu.RiskTable
import com.example.zhengdao.baopu.ToolRule
import com.example.zhengdao.baopu.agent.DeviationWiring
import com.example.zhengdao.baopu.agent.GuardPipeline
import com.example.zhengdao.baopu.agent.ListDirTool
import com.example.zhengdao.baopu.agent.ReadFileTool
import com.example.zhengdao.baopu.agent.ToolCall
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.baopu.agent.ToolPermission
import com.example.zhengdao.baopu.agent.ToolRegistry
import com.example.zhengdao.baopu.agent.ToolRunner
import com.example.zhengdao.baopu.agent.ToolSpec
import com.example.zhengdao.baopu.agent.jsonArrayOf
import com.example.zhengdao.baopu.agent.jsonObjectOf
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreReadPipelineTest {

    private fun tmp(name: String): File = Files.createTempDirectory("baopu-preread-$name").toFile()

    /**
     * 一个**真的落盘**的假写工具（不用 `write_file` 是为了把"写没写成"这一步的变量
     * 收到最少：围栏、spill、备份区都不参与，只剩盘上有没有这个文件）。
     * 219 的表上没有它的行 ⇒ 会 fail-closed 必问，所以夹具里给它配一行 R0（与
     * `GuardPipelineTest.执行失败_Failed带原文` 同一手法）。
     */
    private fun registry(root: File): ToolRegistry = ToolRegistry().apply {
        register(ReadFileTool.spec())
        register(ListDirTool.spec { root })
        register(
            ToolSpec(
                name = "probe_write",
                description = "预读闸用的落盘探针（把 path 写成文件）",
                inputSchema = jsonObjectOf(
                    "type" to "object",
                    "properties" to jsonObjectOf("path" to jsonObjectOf("type" to "string")),
                    "required" to jsonArrayOf("path"),
                ),
                permission = ToolPermission.FREE,
                readOnly = false, // 派工单口径：写工具（默认就是 false，这里写明是为读代码的人）
            ) { args ->
                File(args.getString("path")).writeText("落了")
                ToolOutcome.Ok("写了")
            },
        )
        check(root.isDirectory)
    }

    private fun table(): RiskTable =
        RiskTable.DEFAULT.copy(tools = RiskTable.DEFAULT.tools + ("probe_write" to ToolRule(RiskClass.R0)))

    private fun pipeline(root: File, log: (String) -> Unit = {}): GuardPipeline =
        GuardPipeline(registry(root), { root }, { null }, ToolRunner(Executors.newSingleThreadExecutor()), log, riskTable = table())

    private fun call(path: String) = ToolCall("c1", "probe_write", jsonObjectOf("path" to path))

    private fun order(taskKey: String, require: Boolean) = ReadFirstOrder(
        taskKey = taskKey,
        items = listOf(ReadFirstItem("GuardPipeline.kt:441", ReadFirstKind.FILE_LINE, "闸的落点")),
        requireReceipt = require,
        title = "预读闸直测",
    )

    @After
    fun tearDown() {
        PreReadBoard.resetForTest()
        DeviationWiring.installRun("", "")
    }

    // ── 判据①：无回执 ⇒ 报错拦住，且真的没执行 ──────────────────────────────

    @Test
    fun `未交回执_写工具Failed_盘上没这个文件`() {
        val root = tmp("block")
        DeviationWiring.installRun("run-b", "t-b")
        assertEquals(emptyList<Any>(), PreReadWiring.attachOrder(order("t-b", require = true)))
        val r = pipeline(root).execute(call("a.md"), { _, _ -> null })
        assertTrue("必须是 Failed（报错拦住），不是记账放行：$r", r is GuardPipeline.Result.Failed)
        val failed = r as GuardPipeline.Result.Failed
        assertTrue("给模型的必须是那句可操作提示：${failed.error}", failed.error.contains(BLOCK_HEAD))
        assertFalse("拦住＝真的没执行，盘上不许有文件", File(root, "a.md").exists())
    }

    @Test
    fun `交了回执_同一笔再走_放行且真的写了`() {
        val root = tmp("pass")
        DeviationWiring.installRun("run-p", "t-p")
        assertEquals(emptyList<Any>(), PreReadWiring.attachOrder(order("t-p", require = true)))
        val target = File(root, "a.md")
        assertTrue("先确认拦着", pipeline(root).execute(call("a.md"), { _, _ -> null }) is GuardPipeline.Result.Failed)
        assertEquals(emptyList<Any>(), PreReadWiring.submitReceipt(
            PreReadReceipt("t-p", readLines = listOf("GuardPipeline.kt:441"), understanding = "闸在②后③前", plannedFiles = listOf("a.md")),
        ))
        val r = pipeline(root).execute(call("a.md"), { _, _ -> null })
        assertTrue("交了就该放行：$r", r is GuardPipeline.Result.Done)
        assertTrue("放行还不够，盘上得真有这个文件", target.isFile && target.readText() == "落了")
    }

    // ── 判据⑤：只拦写不拦读 ─────────────────────────────────────────────────

    @Test
    fun `未交回执_读工具照走`() {
        val root = tmp("readonly")
        File(root, "a.md").writeText("内容")
        DeviationWiring.installRun("run-r", "t-r")
        assertEquals(emptyList<Any>(), PreReadWiring.attachOrder(order("t-r", require = true)))
        val r = pipeline(root).execute(ToolCall("c2", "read_file", jsonObjectOf("path" to "a.md")), { _, _ -> null })
        assertTrue("判据⑤：只读工具永不拦（读不到内容就没法写回执）：$r", r is GuardPipeline.Result.Done)
        assertTrue((r as GuardPipeline.Result.Done).wrapped.contains("内容"))
        val l = pipeline(root).execute(ToolCall("c3", "list_dir", jsonObjectOf("path" to ".")), { _, _ -> null })
        assertTrue("列目录也是只读：$l", l is GuardPipeline.Result.Done)
    }

    @Test
    fun `无清单的任务_写工具直行_主流程没被拦死`() {
        // 态①的回归（裁定原话：否则连"删一个临时文件"都要先写回执，等于把主流程卡死）。
        val root = tmp("off")
        DeviationWiring.installRun("run-o", "t-o")
        val r = pipeline(root).execute(call("a.md"), { _, _ -> null })
        assertTrue("没附单子就拦，是本批最坏的失败：$r", r is GuardPipeline.Result.Done)
        assertTrue(File(root, "a.md").isFile)
    }

    @Test
    fun `有清单没要求_放行但收工落对照`() {
        val root = tmp("notify")
        DeviationWiring.installRun("run-n", "t-n")
        assertEquals(emptyList<Any>(), PreReadWiring.attachOrder(order("t-n", require = false)))
        val r = pipeline(root).execute(call("a.md"), { _, _ -> null })
        assertTrue("态②仍可写：$r", r is GuardPipeline.Result.Done)
        assertEquals(
            emptyList<Any>(),
            PreReadWiring.submitReceipt(PreReadReceipt("t-n", readLines = listOf("别的.kt"), understanding = "没读那条", plannedFiles = listOf("a.md"))),
        )
        // 态②收工那一条：不拦、但"读了没"必须落账（裁定②的第二个半句）
        assertNotNull(PreReadWiring.closeout(listOf("a.md")))
        val close = PreReadLedger.rowsFor("t-n").last { it.kind == PreReadEventKind.CLOSEOUT }
        assertTrue("收工行该写「未读到」那条清单：${close.detail}", close.detail.any { it.contains("未读到") })
    }

    // ── 源级反钉：闸必须在②之后、③之前 ─────────────────────────────────────
    //
    // 为什么这一条要钉**源码位置**而不是行为：本批红线是「W3 压/放闸语义零改写」
    // （`OpinionWiring`）＋「被拦的调用不进判定」（不进 `RiskGate.judge` 就不加计频）。
    // 这两件事都是"顺序"，顺序一旦被后来的人挪动了行，行为测试只会变成
    // "多调了一次判定"——那种差别在断言里看不出来，只能钉字。

    private fun source(rel: String): String {
        val roots = listOf(File("app/src/main/java"), File("src/main/java"))
        val root = roots.firstOrNull { it.isDirectory }
            ?: error("找不到 app 源码根（cwd=${File("").absolutePath}）")
        val f = File(root, rel)
        assertTrue("找不到源文件：${f.path}", f.isFile)
        return f.readText()
    }

    @Test
    fun `源级闸_预读在路径守卫之后_判定与W3之前`() {
        val src = source("com/example/zhengdao/baopu/agent/GuardPipeline.kt")
        // 517：guardPath 带上能力类（声明边界闸要按能力类查表）——形状改了，次序闸的口径不变。
        val pathGuard = src.indexOf("guardPath(call, spec.name, capability)")
        val preread = src.indexOf("PreReadWiring.beforeToolCall(")
        val judge = src.indexOf("RiskGate.judge(")
        val w3 = src.indexOf("OpinionWiring.onVerdict(")
        assertTrue("四格都得在 prepareImpl 里找到", pathGuard > 0 && preread > 0 && judge > 0 && w3 > 0)
        assertTrue("预读必须在 ② 之后（系统/凭据目录的硬拒不许被放软）", preread > pathGuard)
        assertTrue("预读必须在 ③ 判定之前（被拦的不进 RiskGate）", preread < judge)
        assertTrue("预读必须在 W3 之前（被拦的不碰 OpinionWiring：红线零改写）", preread < w3)
    }

    @Test
    fun `源级闸_拦的是写工具_真值取 readOnly`() {
        val src = source("com/example/zhengdao/baopu/agent/GuardPipeline.kt")
        // 判据⑤的口径只有一份：与 287 围栏/⑤ 段入账同一个真值 `!spec.readOnly`。
        // 写成"按工具名猜"就是第二套真相，工具名一改就漏（287 那本账的教训）。
        assertTrue("拦写的那一句必须用 !spec.readOnly", Regex("""beforeToolCall\([^)]*!spec\.readOnly""").containsMatchIn(src))
        // 拦住给的是 Failed（可重试、原文回灌），不是 Rejected（终止本轮）
        val block = Regex("""PreReadWiring\.beforeToolCall[\s\S]{0,220}?Result\.Failed\(""").find(src)
        assertTrue("拦住必须回 Failed（裁定：报错拦住、交完还能重试）：$block", block != null)
        assertFalse("拦住不许回 Rejected", Regex("""PreReadWiring\.beforeToolCall[\s\S]{0,220}?Result\.Rejected\(""").containsMatchIn(src))
    }

    @Test
    fun `源级闸_长程判定复用274_不许第二套`() {
        val board = source("com/example/zhengdao/baopu/preread/PreReadBoard.kt")
        assertTrue("必须调 274 的 isLongTask", board.contains("PlanPledgeRules.isLongTask("))
        assertFalse("不许自己写步数阈值（那就是第二套长程判定）", Regex("""items?\.size\s*>=\s*\d+""").containsMatchIn(board))
        // 收工对照挂在 242 的 touched 口径上，不在引擎里另起一份清单
        val seam = source("com/example/zhengdao/baopu/verify/RunContractWiring.kt")
        assertTrue("收工对照必须吃 closeRun 的那份 touched", seam.contains("PreReadWiring.closeout(touched)"))
    }
}
