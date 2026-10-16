// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.bridge

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 桥命令 `tabId` 路由门禁（㊽-C ＝ §B 78，2026-10-16）。
 *
 * 背景（workbuddy 第㉟刀真机验收报告 §7 N4，P3）：`status` / `snapshot` / `execJS` / `click` /
 * `type` / `scroll` / `navigate` 七条命令**一律操作活跃标签** —— body 里带的 `tabId` 被静默
 * 忽略，实测 `tabId=1` 的 `click` 点在了 tab2 上，agent 从回执里看不出任何异常。
 *
 * 修复口径：`tabId` 缺省/空白 → 活跃标签（老调用方零改动，靠默认参数保证）；给了 `tabId` →
 * 按 id 精确路由；不是数字、或找不到该标签 → **明确回错误 JSON**（不许再静默落到活跃标签）。
 *
 * 本文件是**源码级门禁**（`BrowserCommands` 依赖 WebView/Compose 状态，JVM 里起不来）：
 * 读源码剥注释后断言，口径与 [BrowserBridgeGateTest] / `LargeFontWrapGuardTest` 一致 ——
 * 注释里写一句"我支持 tabId"不算数。
 */
class BridgeTabIdRoutingGuardTest {

    // ── 基础设施（工作目录 = app/，与仓库其它源码门禁一致） ────────────────

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.exists()) it else File("app/$rel") }

    /** 剥注释：先多行再整行单行 —— 只剥注释，不碰字符串里的 `//`。 */
    private fun code(rel: String): String {
        val f = repoFile(rel)
        assertTrue("找不到 $rel（cwd=${File("").absolutePath}）", f.isFile)
        return f.readText()
            .replace("\r\n", "\n")
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .replace(Regex("(?m)^[ \\t]*//.*$"), "")
    }

    private fun original(rel: String): String = repoFile(rel).readText().replace("\r\n", "\n")

    private val commandsRel = "src/main/java/com/example/zhengdao/bridge/BrowserCommands.kt"
    private val bridgeRel = "src/main/java/com/example/zhengdao/bridge/BrowserAgentBridge.kt"
    private val managerRel = "src/main/java/com/example/zhengdao/ui/browser/BrowserTabManager.kt"

    /** dispatch 里某个命令分支的调用文本（到下一个分支为止）。 */
    private fun dispatchBranch(cmd: String): String {
        val src = code(bridgeRel)
        val start = src.indexOf("BridgePolicy.Command.$cmd ->")
        if (start < 0) return ""
        val rest = src.substring(start)
        val next = rest.indexOf("BridgePolicy.Command.", 1)
        return if (next > 0) rest.substring(0, next) else rest
    }

    /** 抽出某个函数的正文（按花括号配平，从签名起）。 */
    private fun functionBody(signature: String): String {
        val src = code(commandsRel)
        val start = src.indexOf(signature)
        if (start < 0) return ""
        val open = src.indexOf('{', start)
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(open, i + 1)
                }
            }
            i++
        }
        return src.substring(open)
    }

    // ① 七条命令都收 tabId，且都有默认值（老调用方零改动的判据）
    @Test
    fun `门禁_B补78_七条命令都收 tabId 且有默认值`() {
        val src = code(commandsRel)
        val signatures = listOf(
            "fun status(tabId: String? = null): String",
            "fun snapshot(mode: String?, selector: String?, tabId: String? = null): String",
            "fun execJs(code: String?, tabId: String? = null): String",
            "fun click(selector: String?, tabId: String? = null): String",
            "fun type(selector: String?, text: String?, tabId: String? = null): String",
            "fun scroll(x: String?, y: String?, selector: String?, tabId: String? = null): String",
            "fun navigate(url: String?, tabId: String? = null): String",
        )
        signatures.forEach { sig ->
            assertTrue("签名缺 tabId 或缺默认值：$sig", src.contains(sig))
        }
    }

    // ② dispatch 七条都透传 body 里的 tabId（吞掉 tabId 的旧形态就是 N4 本身）
    @Test
    fun `门禁_B补78_dispatch 七条都透传 param_body_tabId`() {
        val wire = listOf("STATUS", "SNAPSHOT", "EXEC_JS", "CLICK", "TYPE", "SCROLL", "NAVIGATE")
        wire.forEach { cmd ->
            val branch = dispatchBranch(cmd)
            assertTrue("dispatch 里找不到 $cmd 分支", branch.isNotEmpty())
            assertTrue(
                "$cmd 分支没有透传 param(body, \"tabId\") —— tabId 会被静默忽略（N4）",
                branch.contains("param(body, \"tabId\")"),
            )
        }
    }

    // ③ 七条命令体都经 target(tabId) 路由；"直接抓活跃标签"只许留在两处既有位置
    @Test
    fun `门禁_B补78_七条命令都经 target 路由`() {
        val src = code(commandsRel)
        val signatures = listOf(
            "fun status(tabId: String? = null): String {",
            "fun snapshot(mode: String?, selector: String?, tabId: String? = null): String {",
            "fun execJs(code: String?, tabId: String? = null): String {",
            "fun click(selector: String?, tabId: String? = null): String {",
            "fun type(selector: String?, text: String?, tabId: String? = null): String {",
            "fun scroll(x: String?, y: String?, selector: String?, tabId: String? = null): String {",
            "fun navigate(url: String?, tabId: String? = null): String {",
        )
        signatures.forEach { sig ->
            assertTrue("源码里找不到命令：$sig", src.contains(sig))
            assertTrue("$sig 没有经 target(tabId) 路由", functionBody(sig).contains("target(tabId)"))
        }
        // 直接抓活跃标签的位置恰 2 处：target() 的缺省分支 ＋ screenshot 自有的解析
        // （screenshot 本来就按 tabId 路由，本次未动它，文案也不许跟着改）。
        assertEquals(
            "BrowserTabManager.activeTab() 应恰 2 处（target 缺省分支 / screenshot 既有解析）",
            2,
            Regex("BrowserTabManager\\.activeTab\\(\\)").findAll(src).count(),
        )
        assertEquals(
            "target( 出现应恰 8 处（1 处定义 + 7 条命令）",
            8,
            Regex("target\\(").findAll(src).count(),
        )
    }

    // ④ target() 三支判据齐全，且失败都给明确错误文案
    @Test
    fun `门禁_B补78_target 三支判据齐全且都给明确错误`() {
        val src = code(commandsRel)
        assertTrue("缺「缺省/空白 → 活跃标签」分支", src.contains("if (tabId.isNullOrBlank()) {"))
        assertTrue(
            "缺「不是数字」的明确错误",
            src.contains("\"tabId 不是数字：\${tabId.trim()}\""),
        )
        assertTrue(
            "缺「找不到该标签」的明确错误（带当前标签数）",
            src.contains("\"没有这个标签：\$id（当前 \${BrowserTabManager.count()} 个）\""),
        )
    }

    // ⑤ 标签查找是纯查询（不碰活跃态），且只此一处实现
    @Test
    fun `门禁_B补78_tabById 是纯查询`() {
        val src = code(managerRel)
        assertTrue(
            "BrowserTabManager 缺纯查询 tabById",
            src.contains("fun tabById(id: Int): Tab? = tabs.firstOrNull { it.id == id }"),
        )
    }

    // ⑥ 旧形态不许回潮 ＋ 立账可追溯
    @Test
    fun `门禁_B补78_无参旧形态不许回潮且立账可追溯`() {
        val src = code(bridgeRel)
        listOf("status()", "execJs(param(body, \"code\"))", "click(param(body, \"selector\"))").forEach {
            assertFalse(
                "dispatch 回到无 tabId 的旧形态：$it",
                src.contains("-> BrowserCommands.$it"),
            )
        }
        val raw = original(commandsRel)
        assertTrue("命令侧缺 ㊽-C 立账注释", raw.contains("㊽-C ＝ §B 78"))
        assertTrue("命令侧缺 N4 取证来源", raw.contains("N4"))
    }
}
