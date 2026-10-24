// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百七十批 B · MCP 真入口的**防退化门禁**（源码级）。
//
// 为什么需要这一份：本批的靶心不是「功能没写」，而是「库层写好了、生产路径没接」——
// 那种问题跑什么都绿（库里测试全过），只有把入口接回去才暴露。所以这里钉住的是
// 「不许退回假入口」的几条负例：一旦有人把面板改回 controller.send、把 mcp() 改回
// 每次新建注册表、或者把落盘挪进 BaopuDb，这几条会立刻红。
//
// 证据等级说明：这几条是②（代码原文），不是①（真跑）。①在 McpEntryTest（管理器 ↔ 落盘）
// 与 McpToolSourceTest（连接 → 注册 → 确认卡）里。
package com.example.zhengdao.baopu.mcp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class McpEntryWiringTest {

    /** Gradle 单测的 cwd 是 app 模块目录（同 CoroutineCrashHandlerTest 的读法）。 */
    private fun src(rel: String): String =
        File("src/main/java/com/example/zhengdao/$rel").readText()

    @Test
    fun `负例_面板不再把连接塞进对话`() {
        val sheet = src("ui/baopu/BaopuMcpSheet.kt")
        assertFalse("MCP 面板不许再 controller.send（那是假入口）", sheet.contains("controller.send"))
        assertFalse("不许再拼那句聊天指令", sheet.contains("连接 MCP 服务"))
    }

    @Test
    fun `面板直接走管理器这五件事`() {
        val sheet = src("ui/baopu/BaopuMcpSheet.kt")
        listOf(
            "addServer(",
            "connect(",
            "restoreFromStore(",
            "disconnect(",
            "removeServer(",
            "snapshot(",
        ).forEach { call ->
            assertTrue("面板缺 $call（真入口少一环）", sheet.contains(call))
        }
    }

    @Test
    fun `负例_屏幕不再给面板传 onConnect`() {
        val screen = src("ui/baopu/BaopuScreen.kt")
        val start = screen.indexOf("BaopuMcpSheet(")
        assertTrue("找不到面板调用点", start > 0)
        val segment = screen.substring(start, minOf(start + 300, screen.length))
        assertFalse("onConnect 已废弃（面板自己连）", segment.contains("onConnect"))
    }

    @Test
    fun `负例_全仓不许再出现旧假入口文案`() {
        val hits = File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("连接 MCP 服务") }
            .map { it.name }
            .toList()
        assertTrue("旧假入口文案还在：$hits", hits.isEmpty())
    }

    @Test
    fun `mcp 入口绑引擎那张表_不再每次新建`() {
        val runtime = src("baopu/BaopuRuntime.kt")
        val start = runtime.indexOf("fun mcp(context: Context)")
        assertTrue("找不到 mcp(context) 入口", start > 0)
        val end = runtime.indexOf("private fun mcpSourceFor", start)
        assertTrue(end > start)
        val body = runtime.substring(start, end)
        assertTrue("必须取引擎正在用的那张表（engineRegistry）", body.contains("engineRegistry"))
        assertFalse(
            "不许再每次调用都新建一张注册表（工具会注册到临时表，模型看不见）",
            body.contains("registry(appCtx, provider(appCtx))"),
        )
    }

    @Test
    fun `负例_落盘不许碰数据库版本`() {
        listOf("baopu/mcp/McpServerStore.kt", "baopu/mcp/McpServerPrefs.kt").forEach { rel ->
            val text = src(rel)
            // 判据钉「代码形状」而不是任意字样：注释里可以解释为什么不用数据库，
            // 但 import / 构造 / VERSION 引用出现一处就算越界。
            assertFalse("$rel 不许 import 数据库", Regex("import[^\\n]*BaopuDb").containsMatchIn(text))
            assertFalse("$rel 不许构造数据库", text.contains("BaopuDb("))
            assertFalse("$rel 不许引用数据库版本号", text.contains("BaopuDb.VERSION"))
        }
    }

    @Test
    fun `负例_落盘不许写请求头`() {
        val store = src("baopu/mcp/McpServerStore.kt")
        // encode 里只许出现白名单四个字段；出现 "headers" 就说明把可能带 key 的头写进 prefs 了。
        val encode = store.substring(store.indexOf("private fun encode(config: McpServerConfig)"))
            .substringBefore("private fun decode")
        assertFalse("encode 里不许出现 headers", encode.contains("headers"))
        assertFalse("encode 里不许写 Authorization", encode.contains("Authorization"))
    }
}
