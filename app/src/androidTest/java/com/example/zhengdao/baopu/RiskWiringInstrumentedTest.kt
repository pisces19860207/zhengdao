// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-10-26-第二百二十三批-分类器接线 §4「真机：三条路径各一张卡或日志」。
//
// 为什么必须上真机（JVM 已经全绿了）：JVM 上的「卡」只是一个 data class，**它有没有真的
// 变成屏幕上的字**只有真机能答；而且真机的 `org.json` 是插入序、文件系统是 ext4/f2fs，
// 与 JVM 的 HashMap/参考实现不是一回事。本文件把三条路径各走一遍，并把结论同时打到
// logcat（`ZD223` 标签）——报告里贴的就是这些行。
//
// 判据（一条测试一条路径）：
// ① R2（底档即 R2）⇒ 弹卡，卡上带原因码人话，执行器 0 次；
// ② R3（结算类点击）⇒ 弹卡，**执行器 0 次**（不可自动放行）；
// ③ 硬拒（密码框）⇒ `Rejected`（不是问），原因带 `sensitive_field`；
// ④ 卡面：把真实 Composable 渲染出来，断言那一行真上屏（这就是「一张卡」）。
package com.example.zhengdao.baopu

import android.content.Context
import android.util.Log
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.zhengdao.baopu.agent.AppendPersonaTool
import com.example.zhengdao.baopu.agent.BaopuToolCatalog
import com.example.zhengdao.baopu.agent.CompatChannelTools
import com.example.zhengdao.baopu.agent.ConfirmRequest
import com.example.zhengdao.baopu.agent.GuardPipeline
import com.example.zhengdao.baopu.agent.InMemoryToolPrefs
import com.example.zhengdao.baopu.agent.SkillTools
import com.example.zhengdao.baopu.agent.ToolCall
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.baopu.agent.ToolPermission
import com.example.zhengdao.baopu.agent.ToolRegistry
import com.example.zhengdao.baopu.agent.ToolRunner
import com.example.zhengdao.baopu.agent.ToolSpec
import com.example.zhengdao.baopu.agent.jsonObjectOf
import com.example.zhengdao.ui.baopu.BaopuConfirmCard
import com.example.zhengdao.ui.theme.ZhengdaoTheme
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RiskWiringInstrumentedTest {

    @get:Rule
    val compose = createComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val ran = AtomicInteger(0)

    /** 证据双写：logcat（HKS 可能吞掉）+ 应用私有文件（run-as 一定读得到）。 */
    private fun say(key: String, msg: String) {
        Log.i(TAG, "[$key] $msg")
        Evidence.write(ctx, key, msg)
    }

    @After
    fun cleanup() {
        RiskGate.resetForTest()
    }

    // ── 夹具 ─────────────────────────────────────────────────────────────

    private fun ws(name: String): File =
        File(ctx.cacheDir, "zd223-$name").apply { mkdirs() }

    private fun fake(name: String, perm: ToolPermission = ToolPermission.FREE, vararg keys: String): ToolSpec =
        ToolSpec(
            name = name,
            description = "第二百二十三批真机测试用的假工具",
            inputSchema = jsonObjectOf(
                "type" to "object",
                "properties" to JSONObject().also { p -> keys.forEach { p.put(it, JSONObject()) } },
            ),
            permission = perm,
        ) {
            ran.incrementAndGet()
            ToolOutcome.Ok("ran")
        }

    private fun table(vararg rows: Pair<String, ToolRule>): RiskTable =
        RiskTable.DEFAULT.copy(
            tools = RiskTable.DEFAULT.tools + rows,
            matrixVersion = RiskTable.DEFAULT_VERSION + 1,
        )

    private fun pipeline(root: File, tbl: RiskTable, outside: Boolean = false, vararg specs: ToolSpec): GuardPipeline {
        val reg = ToolRegistry(InMemoryToolPrefs())
        specs.forEach { reg.register(it) }
        return GuardPipeline(
            reg,
            { root },
            { null },
            ToolRunner(Executors.newSingleThreadExecutor()),
            { msg -> Log.i(TAG, "管线：$msg"); Evidence.write(ctx, "管线", msg) },
            { outside },
            tbl,
        )
    }

    private fun call(name: String, vararg args: Pair<String, Any?>): ToolCall {
        val json = JSONObject()
        args.forEach { (k, v) -> json.put(k, v) }
        return ToolCall("c-$name", name, json)
    }

    // ── ① R2：底档即 R2 ⇒ 弹卡 ───────────────────────────────────────────

    @Test
    fun r2_删一类动作_弹卡且不执行() {
        val root = ws("r2")
        val tbl = table("w223_del" to ToolRule(RiskClass.R2, deleteLike = true, writePathKeys = setOf("path")))
        val p = pipeline(root, tbl, false, fake("w223_del", ToolPermission.FREE, "path"))
        val r = p.execute(call("w223_del", "path" to File(root, "产物/a.md").absolutePath)) { _, _ -> null }
        say("①-R2", "路径 ⇒ ${r::class.simpleName}；原因行=${(r as? GuardPipeline.Result.NeedsConfirm)?.reasonLine}")
        assertTrue("R2 必须弹卡：$r", r is GuardPipeline.Result.NeedsConfirm)
        val card = r as GuardPipeline.Result.NeedsConfirm
        assertTrue("卡上必须有原因行：${card.reasonLine}", !card.reasonLine.isNullOrBlank())
        assertFalse("原因行必须单行", card.reasonLine!!.contains('\n'))
        assertTrue("原因行超 30 字：${card.reasonLine}", card.reasonLine!!.length <= ImpactLine.MAX_CHARS)
        assertEquals("弹卡 ⇒ 执行器 0 次", 0, ran.get())
    }

    // ── ② R3：结算类点击 ⇒ 弹卡且不可自动 ────────────────────────────────

    @Test
    fun r3_点结算_弹卡且执行器0次() {
        val root = ws("r3")
        val tbl = table("w223_pay" to ToolRule(RiskClass.R2, clickLike = true))
        val p = pipeline(root, tbl, false, fake("w223_pay", ToolPermission.FREE, "target"))
        val c = call("w223_pay", "target" to "结算")
        val v = RiskGate.judge(c, table = tbl)
        say("②-R3-判定", "：档=${v.risk} 决策=${v.decision} 主判据=${v.reasonCode.id}")
        assertEquals("底档 R2 + 高危词 ⇒ R3", RiskClass.R3, v.risk)
        val r = p.execute(c) { _, _ -> null }
        say("②-R3", "路径 ⇒ ${r::class.simpleName}；原因行=${(r as? GuardPipeline.Result.NeedsConfirm)?.reasonLine}")
        assertTrue("R3 必须弹卡：$r", r is GuardPipeline.Result.NeedsConfirm)
        assertEquals("点击的是提交类按钮", (r as GuardPipeline.Result.NeedsConfirm).reasonLine)
        assertEquals("R3 被自动放行了就是负例", 0, ran.get())
    }

    // ── ③ 硬拒：密码框 ⇒ 拒绝，不是问 ────────────────────────────────────

    @Test
    fun 硬拒_密码框_拒绝而不是问() {
        val root = ws("deny")
        val tbl = table("w223_secret" to ToolRule(RiskClass.R2, fieldKeys = setOf("target")))
        val p = pipeline(root, tbl, false, fake("w223_secret", ToolPermission.FREE, "target"))
        val r = p.execute(call("w223_secret", "target" to "input[type=password]")) { _, _ -> null }
        val reason = (r as? GuardPipeline.Result.Rejected)?.reason
        say("③-硬拒", "路径 ⇒ ${r::class.simpleName}；原文=$reason")
        assertTrue("密码框必须**拒绝**（不许降级成问）：$r", r is GuardPipeline.Result.Rejected)
        assertTrue("拒绝原文要带原因码：$reason", reason!!.contains("sensitive_field"))
        assertEquals("硬拒 ⇒ 一个字节都没执行", 0, ran.get())
    }

    // ── ④ 卡面：那一行真的上屏（这就是"一张卡"）──────────────────────────

    @Test
    fun 卡面_原因码那一行真的上屏() {
        val text = "会改工作区外或删改已有东西"
        val req = ConfirmRequest(
            callId = "c-card",
            tool = "delete_path",
            summary = "删除 1 个文件",
            argsJson = """{"path":"产物/a.md"}""",
            reasonLine = text,
        )
        // 卡面只认 `impactLine` 参数（220 的位置）；`reasonLine` 由控制器用 `RiskTell.cardLine`
        // 合成后传进来（`BaopuChatController.confirmImpactLine`）——这里照同一条链路走，
        // 不是绕过它：测的就是「矩阵给的原因 → 合成 → 参数 → 屏幕上那行字」。
        val line = RiskTell.cardLine(req.reasonLine, null)
        assertEquals("合成后就是原因本身（没有影响行时）", text, line)
        compose.setContent {
            ZhengdaoTheme {
                BaopuConfirmCard(request = req, onApprove = {}, onReject = {}, impactLine = line)
            }
        }
        compose.waitForIdle()
        // 华为/荣耀的 logcat 会把第三方 tag 吞掉（HKS），所以证据落文件、跑完 run-as 读。
        Evidence.write(ctx, "card_tree", compose.onRoot().printToString(maxDepth = 40))
        compose.onNodeWithText(line!!).assertExists()
        Evidence.write(ctx, "card_line", "OK 卡面含「$line」，整行字数=${line.length}（≤${ImpactLine.MAX_CHARS}）")
    }

    // ── ⑤ 真实工具表全覆盖（真机的 org.json 与 JVM 不是一套实现）──────────

    @Test
    fun 真表_53个注册工具在真机上每个都有档或显式豁免() {
        val root = ws("catalog")
        val files = { PersonaFiles(File(root, "global"), root) }
        val reg = ToolRegistry(InMemoryToolPrefs())
        BaopuToolCatalog.loadInto(reg) { root }
        reg.register(SkillTools.spec(workspaceRoot = { root }, personaFiles = files))
        reg.register(AppendPersonaTool.spec(personaFiles = files))
        reg.register(CompatChannelTools.readSpec(readFileEnabled = true))
        reg.register(CompatChannelTools.bashSpec())

        val names = reg.names()
        val missing = RiskMatrix.uncovered(names)
        say("⑤-全覆盖", "注册 ${names.size} 个工具；缺档=${missing}；bash 豁免=${RiskMatrix.isExempt("bash")}")
        assertEquals("注册表工具数变了：先更新 219 的表再回来", 53, names.size)
        assertEquals("这些真工具在真机上查不到档：$missing", emptyList<String>(), missing)
        // 每个工具都真跑一次 classify（真机 org.json 是插入序、与 JVM 的参考实现不同）
        val nulls = names.filter { RiskMatrix.baseOf(it) == null }
        assertEquals("只有 bash 允许空档（显式豁免）", listOf("bash"), nulls)
        names.forEach { name ->
            val v = RiskGate.judge(call(name))
            assertTrue("$name 判不出档", v.exempt || v.risk != null)
        }
        say("⑤-全覆盖", "53 个工具逐个 classify 完成，无异常、无空档（bash 走豁免）")
    }

    // ── ⑥ 越界那条卡（②段给的事实，不是矩阵猜的）────────────────────────

    @Test
    fun 越界_写工作区外_弹卡且原因行是目标在工作区外() {
        val parent = ws("outside-parent")
        val root = File(parent, "ws").apply { mkdirs() }
        val outside = File(parent, "别处/a.md").absolutePath
        val tbl = table("w223_note" to ToolRule(RiskClass.R1, writePathKeys = setOf("path")))
        val p = pipeline(root, tbl, outside = true, fake("w223_note", ToolPermission.FREE, "path"))
        val r = p.execute(call("w223_note", "path" to outside)) { _, _ -> null }
        val line = (r as? GuardPipeline.Result.NeedsConfirm)?.reasonLine
        say("⑥-越界", "路径 ⇒ ${r::class.simpleName}；原因行=$line")
        assertTrue("越界必须弹卡：$r", r is GuardPipeline.Result.NeedsConfirm)
        assertEquals("目标在工作区外", line)
        assertEquals("0 次", 0, ran.get())
    }

    private companion object {
        const val TAG = "ZD223"
    }

    /** 真机证据落点（每次 @Before 清空由测试自己控制；这里只追加）。 */
    internal object Evidence {
        const val FILE = "zd223-evidence.txt"

        fun write(ctx: Context, key: String, text: String) {
            runCatching {
                File(ctx.filesDir, FILE).appendText("[$key] $text\n", Charsets.UTF_8)
            }
        }
    }
}
