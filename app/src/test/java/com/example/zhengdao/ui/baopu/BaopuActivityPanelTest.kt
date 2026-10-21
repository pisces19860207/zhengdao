// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第一百零六批 §3（测试 · 活动面板）＋ 设计 v1.5 §3.12。
//
// 两层判据：
// - **纯函数层**（JVM 直测）：类型映射 `activityItemOf`、≤20 条切分 `activityStream`、
//   目标短描述 `shortTarget` / `toolTargets`、显隐 `activityPanelVisible`；
// - **源码级门禁**（本仓无 Robolectric，Compose 渲染跑不起来）：收起/展开接线、
//   轮尾位置、触控常量，以及派工单 §3 点名的四条负例靶心——
//   ① 活动必须切玄览（不许把明细做成浏览器页）② 第二 WebView ③ 新造 RunState
//   ④ 保留「收起详情」浮标（在 `BaopuCollapseAffordanceTest` 反向钉住）。
package com.example.zhengdao.ui.baopu

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BaopuActivityPanelTest {

    // ── 造数 ──────────────────────────────────────────────────────────────

    private fun row(
        tool: String,
        ok: Boolean = true,
        latencyMs: Long = 1_000,
        err: String = "",
        at: Long = 1_000,
    ) = BaopuStepRow(tool = tool, ok = ok, latencyMs = latencyMs, err = err, createdAt = at)

    private fun msg(id: Long, role: String, at: Long, vararg parts: BaopuUiPart) =
        BaopuUiMessage(id = id, role = role, parts = parts.toList(), createdAt = at)

    private fun toolPart(tool: String, text: String) = BaopuUiPart(kind = "tool", text = text, tool = tool)

    private fun source(name: String): String {
        val roots = listOf(
            File("app/src/main/java/com/example/zhengdao/ui/baopu"),
            File("src/main/java/com/example/zhengdao/ui/baopu"),
        )
        val dir = roots.firstOrNull { it.isDirectory }
            ?: error("找不到 ui/baopu 目录（cwd=${File("").absolutePath}）")
        val f = File(dir, name)
        assertTrue("找不到 $name：${f.path}", f.isFile)
        return f.readText()
    }

    private fun code(name: String): String = source(name)
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun engine(): String {
        val f = File("src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt")
        val file = if (f.isFile) f else File("app/src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt")
        assertTrue("找不到 RunEngine.kt", file.isFile)
        return file.readText()
    }

    // ── 类型映射（设计 §3.12 表） ────────────────────────────────────────

    @Test
    fun `类型映射_真工具名是工具`() {
        val item = activityItemOf(row("read_file", latencyMs = 1_500), target = "整理/第一章.md")
        assertEquals(ActivityKind.TOOL, item.kind)
        assertEquals("read_file", item.tool)
        assertEquals("1.5 秒的读数原样带出", 1_500L, item.latencyMs)
        assertEquals("目标短描述照传", "整理/第一章.md", item.target)
    }

    @Test
    fun `类型映射_compression前缀是压缩`() {
        assertEquals(ActivityKind.COMPRESSION, activityItemOf(row("compression_ok"), "").kind)
        assertEquals(ActivityKind.COMPRESSION, activityItemOf(row("compression_failed"), "").kind)
    }

    @Test
    fun `类型映射_ok为0是失败_且优先于压缩归类`() {
        val item = activityItemOf(
            row("compression_failed", ok = false, err = "上下文超时"),
            "",
        )
        assertEquals("一条失败的压缩留痕也要先说失败（原因比归类重要）", ActivityKind.FAILED, item.kind)
        assertEquals("失败原因原样带出（引擎层已人话化）", "上下文超时", item.err)
    }

    // ── 活动流：最新在上、≤20 条、还有 N 条 ─────────────────────────────

    @Test
    fun `活动流_最新在上`() {
        val stream = activityStream(
            listOf(row("a", at = 1_000), row("b", at = 2_000), row("c", at = 3_000)),
        )
        assertEquals(listOf("c", "b", "a"), stream.shown.map { it.tool })
        assertEquals("三条都装得下 ⇒ 没有折叠", 0, stream.more)
    }

    @Test
    fun `活动流_最多20条_多的折成还有N条`() {
        val rows = (1..25).map { row("tool_$it", at = it * 1_000L) }
        val stream = activityStream(rows)
        assertEquals("上限 20 条", 20, stream.shown.size)
        assertEquals("最新那条在最前", "tool_25", stream.shown.first().tool)
        assertEquals("折掉 5 条", 5, stream.more)
        assertEquals("常量与判据同一口径", 20, ACTIVITY_STREAM_MAX)
    }

    @Test
    fun `活动流_正在等确认时插流首`() {
        val stream = activityStream(
            rows = listOf(row("read_file", at = 1_000)),
            targets = mapOf("write_file" to "整理/第七章.md"),
            awaitConfirmTool = "write_file",
        )
        assertEquals(2, stream.shown.size)
        assertEquals("等确认排最前（它是「现在」）", ActivityKind.AWAIT_CONFIRM, stream.shown[0].kind)
        assertEquals("被确认的工具名", "write_file", stream.shown[0].tool)
        assertEquals("目标短描述按工具名取", "整理/第七章.md", stream.shown[0].target)
    }

    @Test
    fun `活动流_没有等确认就不插占位行`() {
        val stream = activityStream(listOf(row("read_file")), awaitConfirmTool = null)
        assertEquals(1, stream.shown.size)
        assertEquals(ActivityKind.TOOL, stream.shown[0].kind)
        val blank = activityStream(listOf(row("read_file")), awaitConfirmTool = "  ")
        assertEquals("空白工具名不当等确认", 1, blank.shown.size)
    }

    // ── 目标短描述 ──────────────────────────────────────────────────────

    @Test
    fun `目标短描述_压成一行并截断`() {
        assertEquals("换行压成空格", "第一行 第二行", shortTarget("第一行\n第二行"))
        assertEquals("首尾空白去掉", "干活", shortTarget("  干活  "))
        assertEquals("40 字以内原样", "甲".repeat(40), shortTarget("甲".repeat(40)))
        assertEquals("超 40 字省略", "甲".repeat(40) + "…", shortTarget("甲".repeat(41)))
    }

    @Test
    fun `目标短描述_按工具名取该轮消息里的摘要`() {
        val messages = listOf(
            msg(1, "assistant", 1_000, toolPart("read_file", "结果：读完 12 行")),
            msg(2, "assistant", 2_000, toolPart("write_file", "结果：写入 3KB")),
            msg(3, "assistant", 3_000, toolPart("read_file", "结果：又读一次")),
        )
        val targets = toolTargets(messages)
        assertEquals("同名工具取第一条", "结果：读完 12 行", targets["read_file"])
        assertEquals("结果：写入 3KB", targets["write_file"])
        assertNull("没有的工具不给编造", targets["grep"])
    }

    @Test
    fun `目标短描述_空摘要不占位`() {
        val messages = listOf(msg(1, "assistant", 1_000, toolPart("read_file", "   ")))
        assertTrue(toolTargets(messages).isEmpty())
    }

    // ── 显隐 ────────────────────────────────────────────────────────────

    @Test
    fun `显隐_纯聊天轮不出`() {
        assertFalse(
            "没有活动行 ⇒ 面板不出现",
            activityPanelVisible(hasActivity = false, running = true, isCurrent = true, collapsed = false),
        )
    }

    @Test
    fun `显隐_运行中的当前轮出_即使轮收起`() {
        assertTrue(
            "跑着的时候收起轮也要看见「正在：…」",
            activityPanelVisible(hasActivity = true, running = true, isCurrent = true, collapsed = true),
        )
    }

    @Test
    fun `显隐_完成的历史轮收起时不重复挂一行`() {
        assertFalse(
            "收起的完成轮已有摘要行，再来一行就是重复",
            activityPanelVisible(hasActivity = true, running = false, isCurrent = false, collapsed = true),
        )
        assertTrue(
            "展开该轮即可回看活动流",
            activityPanelVisible(hasActivity = true, running = false, isCurrent = false, collapsed = false),
        )
    }

    // ── 源码门禁：位置、接线、触控 ───────────────────────────────────────

    @Test
    fun `收起展开接线_点整行翻转`() {
        val src = code("BaopuActivityPanel.kt")
        assertTrue("点整行展开/收起（不另设按钮）", src.contains("clickable { open = !open }"))
        assertTrue("展开态渲染活动流", src.contains("stream.shown.forEach"))
        assertTrue("折叠条数要说出来（还有 N 条）", src.contains("R.string.baopu_activity_more"))
    }

    @Test
    fun `收起行与条目行的触控规格`() {
        val src = code("BaopuActivityPanel.kt")
        assertTrue("视觉高 40dp", src.contains("ACTIVITY_HEAD_VISUAL_DP = 40"))
        assertTrue("热区 ≥48dp", src.contains("ACTIVITY_ROW_TOUCH_DP = 48"))
    }

    @Test
    fun `失败行给可读原因_不展示堆栈`() {
        val src = code("BaopuActivityPanel.kt")
        assertTrue("失败行单独一支渲染原因", src.contains("ActivityKind.FAILED ->"))
        assertTrue("原因走 strings.xml（中英都给）", src.contains("R.string.baopu_activity_reason"))
        assertFalse("不许把堆栈贴进面板", src.contains("stackTraceToString"))
    }

    @Test
    fun `不新造RunState七态`() {
        val src = engine()
        val m = Regex("enum class RunState \\{([^}]*)\\}").find(src)
        assertTrue("找不到 RunState 枚举（判据失效会让本测试静默通过）", m != null)
        val entries = m!!.groupValues[1].split(",").map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(
            "本批只准用现有七态；新增一个枚举值 ⇒ 本判据红",
            listOf("IDLE", "THINKING", "ACTING", "AWAIT_CONFIRM", "COMPRESSING", "ABORTED", "DONE"),
            entries,
        )
        // 面板只用现有状态，不定义自己的状态机
        val panel = code("BaopuActivityPanel.kt")
        assertFalse("面板不许自造状态类型", panel.contains("enum class ActivityRunState"))
        assertTrue(
            "状态文案复用 stateLabel（七态人话唯一真源）",
            panel.contains("stateLabel(state)"),
        )
    }

    @Test
    fun `不新开WebView_不走玄览页`() {
        val panel = code("BaopuActivityPanel.kt")
        assertFalse("活动面板不许起 WebView", panel.contains("WebView"))
        assertFalse("不许引 webkit", panel.contains("android.webkit"))
        assertFalse("不许走浏览器页", panel.contains("玄览"))
        assertFalse("不许新造浏览器活动组件", panel.contains("BrowserActivity"))
    }

    @Test
    fun `不改内核SQL_不新造事件源`() {
        val f = File("app/src/main/java/com/example/zhengdao/baopu/BaopuDb.kt")
            .let { if (it.isFile) it else File("src/main/java/com/example/zhengdao/baopu/BaopuDb.kt") }
        assertTrue("找不到 BaopuDb.kt", f.isFile)
        val db = f.readText()
        val idx = db.indexOf("RUN_LOG_STEPS_SELECT")
        assertTrue("找不到 RUN_LOG_STEPS_SELECT（判据失效会让本测试静默通过）", idx >= 0)
        val block = db.substring(idx, minOf(db.length, idx + 400))
        assertTrue("SELECT 仍按 tool != '' 过滤（思考/完成不进流）", block.contains("tool != ''"))
        assertFalse("本批不许给 run_log 加 state 列", block.contains("state"))
        assertFalse(
            "本批不许在面板文件里写迁移语句（表结构归内核，108 红线）",
            code("BaopuActivityPanel.kt").contains("ALTER TABLE"),
        )
    }

    @Test
    fun `面板只挂在轮尾_不在屏幕固定层`() {
        val list = code("BaopuMessageList.kt")
        val lambdaIdx = list.indexOf("val activityPanel: @Composable () -> Unit")
        val panelIdx = list.indexOf("BaopuActivityPanel(")
        assertTrue("两个落点都要在（索引失效会让本判据静默通过）", lambdaIdx > 0 && panelIdx > lambdaIdx)
        val forEachIdx = list.indexOf("round.messages.forEach { msg ->")
        val callIdx = list.indexOf("activityPanel()", forEachIdx)
        assertTrue(
            "面板在轮内消息之后（轮尾）",
            forEachIdx > 0 && callIdx > forEachIdx,
        )
        val screen = code("BaopuScreen.kt")
        assertFalse(
            "屏幕层不许直接摆面板（那会变成固定区、遮输入框）",
            screen.contains("BaopuActivityPanel("),
        )
        assertTrue(
            "屏幕层把引擎三件传给列表（状态/步号/待确认工具）",
            screen.contains("awaitConfirmTool = controller.confirmCard?.tool"),
        )
    }

    @Test
    fun `面板不抢决策卡槽`() {
        val src = code("BaopuActivityPanel.kt")
        assertFalse(
            "面板不许碰决策卡/单卡槽（不同层、不同职责）",
            src.contains("DecisionCard") || src.contains("bottomCards"),
        )
        // 收起行用的是列表内的 surfaceVariant，不是卡槽的 primaryContainer
        assertTrue(
            "收起行是正文内联件（surfaceVariant 底）",
            src.contains("MaterialTheme.colorScheme.surfaceVariant"),
        )
    }
}
