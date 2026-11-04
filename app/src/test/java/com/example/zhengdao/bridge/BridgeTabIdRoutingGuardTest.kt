// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.bridge

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 命令层 `tabId` 路由门禁（㊽-C ＝ §B 78，2026-10-16）。
 *
 * 背景（workbuddy 第㉟刀真机验收报告 §7 N4，P3）：`status` / `snapshot` / `execJS` / `click` /
 * `type` / `scroll` / `navigate` 七条命令**一律操作活跃标签** —— 传入的 `tabId` 被静默
 * 忽略，实测 `tabId=1` 的 `click` 点在了 tab2 上，调用方从回执里看不出任何异常。
 *
 * 修复口径：`tabId` 缺省/空白 → 活跃标签（老调用方零改动，靠默认参数保证）；给了 `tabId` →
 * 按 id 精确路由；不是数字、或找不到该标签 → **明确回错误 JSON**（不许再静默落到活跃标签）。
 *
 * 本文件是**源码级门禁**（`BrowserCommands` 依赖 WebView/Compose 状态，JVM 里起不来）：
 * 读源码剥注释后断言 —— 注释里写一句"我支持 tabId"不算数。
 * （383 批：原读 HTTP 桥 dispatch 透传的两条判据随桥删除移除；路由本体在
 * BrowserCommands 默认参数与 target() 里，判据照旧。）
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
    private val managerRel = "src/main/java/com/example/zhengdao/ui/browser/BrowserTabManager.kt"

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
    // 228 批 B：B-2 导航三件与 B-3 动作三件同规入册（带 tabId 就必须能路由）
    @Test
    fun `门禁_B补78_带 tabId 的命令都收 tabId 且有默认值`() {
        val src = code(commandsRel)
        val signatures = listOf(
            "fun status(tabId: String? = null): String",
            "fun snapshot(mode: String?, selector: String?, tabId: String? = null): String",
            "fun execJs(code: String?, tabId: String? = null): String",
            "fun click(selector: String?, tabId: String? = null): String",
            "fun type(selector: String?, text: String?, tabId: String? = null): String",
            "fun scroll(x: String?, y: String?, selector: String?, tabId: String? = null): String",
            "fun navigate(url: String?, tabId: String? = null): String",
            "internal fun goBack(tabId: String? = null): String",
            "internal fun goForward(tabId: String? = null): String",
            "internal fun reload(tabId: String? = null): String",
            "internal fun selectOption(target: String?, value: String?, tabId: String? = null): String",
            "internal fun hover(target: String?, tabId: String? = null): String",
            "internal fun scrollIntoView(target: String?, tabId: String? = null): String",
        )
        signatures.forEach { sig ->
            assertTrue("签名缺 tabId 或缺默认值：$sig", src.contains(sig))
        }
    }

    // ③ 带命令体都经 target(tabId) 路由；"直接抓活跃标签"只许留在两处既有位置
    @Test
    fun `门禁_B补78_命令都经 target 路由`() {
        val src = code(commandsRel)
        val signatures = listOf(
            "fun status(tabId: String? = null): String {",
            "fun snapshot(mode: String?, selector: String?, tabId: String? = null): String {",
            "fun execJs(code: String?, tabId: String? = null): String {",
            "fun click(selector: String?, tabId: String? = null): String {",
            "fun type(selector: String?, text: String?, tabId: String? = null): String {",
            "fun scroll(x: String?, y: String?, selector: String?, tabId: String? = null): String {",
            "fun navigate(url: String?, tabId: String? = null): String {",
            // 第一百七十七批：坐标四件＋等待＋两条带校验的写命令，全部同样按 tabId 路由
            // （新增命令若绕开 target() 就会点到别的标签上——这正是 B-补-78 的老病）
            "internal fun tap(cssX: Int, cssY: Int, tabId: String? = null): String {",
            "internal fun longPress(cssX: Int, cssY: Int, durationMs: Int, tabId: String? = null): String {",
            "internal fun keyPress(key: String, tabId: String? = null): String {",
            "internal fun await(ms: Int, selector: String?, tabId: String? = null): String {",
            "internal fun clickChecked(selector: String, tabId: String? = null): String {",
            "internal fun typeChecked(selector: String, text: String, tabId: String? = null): String {",
            // 228 A-3：stable 等待也是带 tabId 路由的命令，登记入册
            "internal fun awaitStable(ms: Int, tabId: String? = null): String {",
            // 228 批 B-2/B-3：导航三件与动作三件，同规入册（必须块体——表达式体会让
            // 花括号配平抓错块）
            "internal fun goBack(tabId: String? = null): String {",
            "internal fun goForward(tabId: String? = null): String {",
            "internal fun reload(tabId: String? = null): String {",
            "internal fun selectOption(target: String?, value: String?, tabId: String? = null): String {",
            "internal fun hover(target: String?, tabId: String? = null): String {",
            "internal fun scrollIntoView(target: String?, tabId: String? = null): String {",
            // 262 批：证据快照同为带 tabId 路由的命令，登记入册（新增命令别忘了本表）
            "fun evidenceSnapshot(tabId: String? = null): EvidenceCapture {",
        )
        signatures.forEach { sig ->
            assertTrue("源码里找不到命令：$sig", src.contains(sig))
            assertTrue("$sig 没有经 target(tabId) 路由", functionBody(sig).contains("target(tabId)"))
        }
        // swipe 的签名跨行，单独按前缀找（同上：必须过 target 路由）
        assertTrue("swipe 丢了 target 路由", functionBody("internal fun swipe(").contains("target(tabId)"))
        // 直接抓活跃标签的位置恰 2 处：target() 的缺省分支 ＋ 截图自有的解析
        // （截图按 tabId 路由，但它要先拿到标签才能问页面几何，这一处从 4-B 起就在）。
        // 228 批 B-4 的 tabsList 也不直调 activeTab()：活跃 id 走 target(null)（+3 里记账）。
        assertEquals(
            "BrowserTabManager.activeTab() 应恰 2 处（target 缺省分支 / 截图既有解析）",
            2,
            Regex("BrowserTabManager\\.activeTab\\(\\)").findAll(src).count(),
        )
        // 计数与名册同步：1 处定义 ＋ 全部命令各一处（新增命令别忘了登记本表）
        // +3 = 定义 ＋ swipe（签名跨行按前缀找）＋ tabsList 的 target(null)——它列全部标签
        // 不带 tabId，但「哪颗是活跃」也要问 target，这一处刻意记账在案（改掉必须动本行）。
        assertEquals(
            "target( 出现应恰 ${signatures.size + 3} 处（1 定义 + swipe + tabsList + ${signatures.size} 条命令）",
            signatures.size + 3,
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

    // ⑥ 命令侧立账可追溯（383 批：原「dispatch 旧形态不许回潮」半条随桥删除；
    // 这里保留命令侧的取证来源注释钉，防有人把这段历史连注释一起抹掉）
    @Test
    fun `门禁_B补78_命令侧立账可追溯`() {
        val raw = original(commandsRel)
        assertTrue("命令侧缺 ㊽-C 立账注释", raw.contains("㊽-C ＝ §B 78"))
        assertTrue("命令侧缺 N4 取证来源", raw.contains("N4"))
    }
}
