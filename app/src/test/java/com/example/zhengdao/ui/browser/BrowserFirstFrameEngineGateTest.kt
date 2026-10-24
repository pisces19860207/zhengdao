// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.ui.browser

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 玄览首帧门禁（第五十五批 · §B 77 审计 P1-7，2026-10-19）。
 *
 * 病（派工单 §1）：`BrowserScreen` 原来写 `val engine = remember { BrowserHolder.obtain(context) }`
 * —— `remember {}` 在**组合期**执行，`obtain` 里 `new WebView(app)`。而 WebView 构造是本仓唯一
 * 已知会**间歇卡死（≥100s）**的点，卡点在 Chromium 内部（App 侧修不了，见 BrowserTabManager.newTab
 * 的 §B 61 观测注释）。它落在首帧之前 ⇒ 首帧卡死 / ANR。
 *
 * 修法口径（派工单 §1 四条，逐条钉死）：
 * ① 组合期不得 obtain ⇒ 只能在 `LaunchedEffect`（或等价首帧后入口）里取（F1＋F2）；
 * ② 构造完成前页面区显示轻量占位、**不白屏**，构造完成后照常重组（F4）；
 * ③ `tabs()` / `activeTab()` 必须是可观察状态，且必须**在组合体里读**——否则延迟 obtain 后
 *   首个标签不会触发重组（F7）；
 * ④ 构造前 engine 为 null，所有使用点空安全，**不许 `!!` 硬取**（F3＋F5＋F6）。
 *
 * 房型同 [BrowserStartPageGateTest] / [WebViewEnginePostGateTest]：Compose + WebView 在 JVM 里
 * 起不来 ⇒ **剥注释后打真源码**，判据写成纯函数，正例负例共用同一份牙口。
 */
class BrowserFirstFrameEngineGateTest {

    private fun repoFile(rel: String): File = File(rel).let { if (it.isFile) it else File("app/$rel") }

    /** 剥注释源码（块注释 + 整行注释；行内尾注释不剥 ⇒ 本文件写源码时别在注释里造 `engine.`）。 */
    private fun code(rel: String): String = repoFile(rel).readText()
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private val screenRel = "src/main/java/com/example/zhengdao/ui/browser/BrowserScreen.kt"
    private val managerRel = "src/main/java/com/example/zhengdao/ui/browser/BrowserTabManager.kt"

    private fun screen(): String = code(screenRel)
    private fun screenBody(src: String = screen()): String {
        val at = src.indexOf("fun BrowserScreen(")
        assertTrue("BrowserScreen 里找不到 fun BrowserScreen(", at >= 0)
        return block(src, src.indexOf('{', at))
    }

    /** 从 `openBraceIndex`（指向 `{`）起取花括号配平的块正文（含首尾花括号）。 */
    private fun block(src: String, openBraceIndex: Int): String {
        if (openBraceIndex < 0) return ""
        var depth = 0
        var i = openBraceIndex
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(openBraceIndex, i + 1)
                }
            }
            i++
        }
        return src.substring(openBraceIndex)
    }

    /** 某个头部（如 `remember {`）之后的所有块正文。 */
    private fun blocks(src: String, header: Regex): List<String> =
        header.findAll(src).map { m -> block(src, src.indexOf('{', m.range.first)) }.toList()

    /** 把某个头部的所有块从源码里挖掉（用于「这类代码只能出现在守卫块内」）。 */
    private fun withoutBlocks(src: String, header: Regex): String {
        val sb = StringBuilder()
        var last = 0
        for (m in header.findAll(src)) {
            val open = src.indexOf('{', m.range.first)
            if (open < 0) continue
            val b = block(src, open)
            val end = open + b.length
            if (end > last) {
                sb.append(src, last, open)
                last = end
            }
        }
        sb.append(src.substring(last))
        return sb.toString()
    }

    // ── 判据纯函数（正例负例共用同一份牙口） ──────────────────────────────

    /** 判据 F1：`BrowserHolder.obtain(` 不得出现在任何 `remember {}` 里（组合期不构造）。 */
    private fun noObtainInRemember(src: String): Boolean =
        blocks(src, Regex("remember\\s*\\{")).none { it.contains("BrowserHolder.obtain") }

    /** 判据 F2：obtain 必须落在 `LaunchedEffect` 块内（首帧后入口）。 */
    private fun obtainInLaunchedEffect(src: String): Boolean =
        blocks(src, Regex("LaunchedEffect\\s*\\(")).any { it.contains("BrowserHolder.obtain(context)") }

    /** 判据 F3：engine 是「可空 + 从活跃标签派生」，旧的 remember 形态不许回来。 */
    private fun engineIsNullableDerived(src: String): Boolean =
        Regex("val\\s+engine\\s*:\\s*WebViewEngine\\?\\s*=\\s*active\\?\\.engine").containsMatchIn(src) &&
            !Regex("val\\s+engine\\s*=\\s*remember").containsMatchIn(src)

    /** 判据 F4：构造前占位存在、且占位不是白屏（自带主题底色 + 进度件、不挂 WebView）。 */
    private fun placeholderBranch(src: String): Boolean {
        val warming = blocks(src, Regex("if\\s*\\(\\s*!engineReady\\s*\\)\\s*\\{"))
            .firstOrNull { it.contains("BrowserWarmupPlaceholder(") } ?: return false
        if (warming.contains("AndroidView(")) return false
        val readyFlag = Regex(
            "var\\s+engineReady\\s+by\\s+remember\\s*\\{\\s*mutableStateOf\\s*\\(\\s*BrowserTabManager\\.count\\(\\)\\s*>\\s*0",
        ).containsMatchIn(src)
        val afterStartPage = Regex(
            "\\}\\s*else\\s+if\\s*\\(\\s*\\(*\\s*active\\s*==\\s*null\\s*\\|\\|\\s*!active\\.state\\.everLoaded",
        ).containsMatchIn(src)
        val def = blocks(src, Regex("private fun BrowserWarmupPlaceholder\\s*\\(")).firstOrNull() ?: return false
        return readyFlag && afterStartPage &&
            def.contains("MaterialTheme.colorScheme.background") &&
            def.contains("CircularProgressIndicator(")
    }

    /** 判据 F5：BrowserScreen 组合体内对 engine 的**直取**只能出现在 `if (engine != null)` 守卫块内。 */
    private fun directEngineUseOnlyGuarded(body: String): Boolean {
        val rest = withoutBlocks(body, Regex("if\\s*\\(\\s*engine\\s*!=\\s*null\\s*\\)\\s*\\{"))
        return !Regex("engine\\.[A-Za-z]").containsMatchIn(rest) && !Regex("engine\\s*!!").containsMatchIn(rest)
    }

    /** 判据 F6：全文不得有 `!!` 硬取（派工单 §1 ④）。 */
    private fun noDoubleBang(src: String): Boolean = !src.contains("!!")

    /**
     * 判据 F7：延迟 obtain 之后首标签必须能触发重组 ——
     * ① 状态源可观察（BrowserTabManager 的 tabs 是 SnapshotStateList、activeId 是 mutableStateOf）；
     * ② BrowserScreen 必须在**组合体**里读这两个入口（不在 remember / LaunchedEffect / DisposableEffect 块内）。
     */
    private fun tabsObservableAndReadInComposition(manager: String, body: String): Boolean {
        val stateSource = Regex("val\\s+tabs\\s*:\\s*SnapshotStateList<Tab>\\s*=\\s*mutableStateListOf\\s*\\(\\s*\\)")
            .containsMatchIn(manager) &&
            Regex("var\\s+activeId\\s+by\\s+mutableStateOf\\s*<\\s*Int\\?\\s*>\\s*\\(\\s*null\\s*\\)").containsMatchIn(manager)
        val readOutright = withoutBlocks(
            body,
            Regex("(remember|LaunchedEffect|DisposableEffect)\\s*\\("),
        )
        return stateSource &&
            readOutright.contains("BrowserTabManager.tabs()") &&
            readOutright.contains("BrowserTabManager.activeTab()")
    }

    /** 判据 F8：本刀不许顺手改掉既有成果（提交唯一入口 / 归一化唯一路径 / 空态判据）。 */
    private fun existingContractsKept(src: String): Boolean =
        Regex("tryNavigate\\(context,").findAll(src).count() == 2 &&
            Regex("normalizeInput\\(input\\)").findAll(src).count() == 1 &&
            Regex("active\\s*==\\s*null\\s*\\|\\|\\s*!active\\.state\\.everLoaded").containsMatchIn(src) &&
            src.contains("mutableStateOf(active?.state?.url ?: \"\")")

    // ── 正例：打真源码 ────────────────────────────────────────────────────

    @Test
    fun `P17_组合期不再构造WebView`() {
        val src = screen()
        assertTrue("obtain 又回到 remember{} 里了 —— 那就是组合期 new WebView（首帧卡死风险本体）", noObtainInRemember(src))
        assertTrue("obtain 没有落在 LaunchedEffect（或等价首帧后入口）里", obtainInLaunchedEffect(src))
    }

    @Test
    fun `P17_engine可空且从活跃标签派生`() {
        assertTrue(
            "engine 必须是「可空 + 读活跃标签」的 val：构造前为 null，切到已销毁标签也拿不到死引擎",
            engineIsNullableDerived(screen()),
        )
    }

    @Test
    fun `P17_构造前有占位且不是白屏`() {
        assertTrue(
            "占位不成立：要有 !engineReady 分支 ＋ BrowserWarmupPlaceholder（主题底色＋进度件、不挂 WebView）" +
                "＋ 起始页分支仍走 else if(active == null || !everLoaded)",
            placeholderBranch(screen()),
        )
    }

    @Test
    fun `P17_engine直取只能在非空守卫内`() {
        assertTrue(
            "组合体里有未守卫的 engine.xxx —— 构造前 engine 为 null，那就是当场 NPE",
            directEngineUseOnlyGuarded(screenBody()),
        )
        assertTrue("BrowserScreen 不许出现 !!（派工单 §1 ④）", noDoubleBang(screen()))
    }

    @Test
    fun `P17_首标签靠可观察状态触发重组`() {
        assertTrue(
            "可观察性断链：BrowserTabManager 的 tabs/activeId 必须是 snapshot state，" +
                "且 BrowserScreen 要在组合体里读 tabs()/activeTab()（否则延迟 obtain 后首标签不出现）",
            tabsObservableAndReadInComposition(code(managerRel), screenBody()),
        )
    }

    @Test
    fun `P17_既有成果未被顺手改动`() {
        assertTrue(
            "提交唯一入口 / 归一化唯一路径 / 空态判据 / urlInput 回填 被本刀改动了",
            existingContractsKept(screen()),
        )
    }

    // ── 负例自证：打内嵌反例，证明判据在退化形态上必不通过 ────────────────

    @Test
    fun `负例1_obtain改回remember时判据F1必拦`() {
        val 反例 = screen().replace(
            "val engine: WebViewEngine? = active?.engine",
            "val engine = remember { BrowserHolder.obtain(context) }",
        )
        assertTrue("反例没造出来（源码形态与预期不符）：$反例", 反例 != screen())
        assertFalse(noObtainInRemember(反例))
    }

    @Test
    fun `负例2_未守卫直取engine时判据F5必拦`() {
        val 反例 = screen().replace("canForward = engine?.canGoForward() == true", "canForward = engine.canGoForward()")
        assertTrue("反例没造出来（源码形态与预期不符）", 反例 != screen())
        assertFalse(directEngineUseOnlyGuarded(反例))
    }

    @Test
    fun `负例3_删掉占位分支时判据F4必拦`() {
        val src = screen()
        val 反例 = src.replace("BrowserWarmupPlaceholder(", "Text(")
        assertTrue("反例没造出来（源码形态与预期不符）", 反例 != src)
        assertFalse(placeholderBranch(反例))
    }

    @Test
    fun `负例4_tabs改成普通List时判据F7必拦`() {
        val 反例 = code(managerRel).replace(
            "private val tabs: SnapshotStateList<Tab> = mutableStateListOf()",
            "private val tabs: MutableList<Tab> = mutableListOf()",
        )
        assertTrue("反例没造出来（源码形态与预期不符）", 反例 != code(managerRel))
        assertFalse(tabsObservableAndReadInComposition(反例, screenBody()))
    }
}
