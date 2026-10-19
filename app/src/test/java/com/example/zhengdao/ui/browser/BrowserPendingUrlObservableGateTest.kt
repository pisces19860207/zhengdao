// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.ui.browser

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外部打开入口门禁（第五十五批 · §B 77 审计 P1-8，2026-10-19）。
 *
 * 病（派工单 §2）：`BrowserHolder.pendingUrl` 是普通 `@Volatile var` —— 它**不是 Compose 可观察
 * 状态**，写入不触发重组；消费侧又是 `LaunchedEffect(Unit)`，只在首次组合跑一次。于是
 * 「用户已经停在玄览 Tab 时，从终端点一条链接」⇒ 组合不重跑 effect ⇒ `pendingUrl` 里的 URL
 * **整条丢失**（Volatile 只保证可见性，不保证重组，这两件事常被混为一谈）。
 *
 * 修法口径（派工单 §2 四条，逐条钉死）：
 * ① `pendingUrl` 改成可观察状态（`by mutableStateOf`），裸 `@Volatile var` 不许回来（S1＋S2）；
 * ② 消费改 keyed effect：组合体里读值 ⇒ 写入即重组 ⇒ `LaunchedEffect(值)` 重跑（S3＋S4）；
 * ③ 消费后置 null，且**先清槽再开标签**（S5）；key 变 null 后 effect 再跑一次是空操作，
 *   不得因此重复 `openExternal`（S6）；
 * ④ 外部写入方（`TerminalActivity` / `BaopuPreview`）调用签名一字不改，
 *   也不许新造第二套「待打开 URL」通道（S7＋S8）。
 *
 * 房型同 [BrowserEmptyStateSubmitGuardTest]：剥注释后打真源码，禁止"注释里声明已修"。
 */
class BrowserPendingUrlObservableGateTest {

    private fun repoFile(rel: String): File = File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun code(rel: String): String = repoFile(rel).readText()
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private val holderRel = "src/main/java/com/example/zhengdao/ui/browser/BrowserHolder.kt"
    private val screenRel = "src/main/java/com/example/zhengdao/ui/browser/BrowserScreen.kt"
    private val terminalRel = "src/main/java/com/example/zhengdao/TerminalActivity.kt"
    private val previewRel = "src/main/java/com/example/zhengdao/ui/baopu/BaopuPreview.kt"

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

    /** 取以 `key` 开头的那个块（正文含首尾花括号），找不到返回空串。 */
    private fun blockOf(src: String, key: String): String {
        val at = src.indexOf(key)
        return if (at < 0) "" else block(src, src.indexOf('{', at))
    }

    /** 把 remember / LaunchedEffect / DisposableEffect 的块挖掉 ⇒ 剩下的是「组合体本身」。 */
    private fun compositionBody(src: String): String {
        var rest = src
        listOf(
            Regex("remember\\s*\\{"),
            Regex("LaunchedEffect\\s*\\("),
            Regex("DisposableEffect\\s*\\("),
        ).forEach { header ->
            val sb = StringBuilder()
            var last = 0
            for (m in header.findAll(rest)) {
                val open = rest.indexOf('{', m.range.first)
                if (open < 0) continue
                val end = open + block(rest, open).length
                if (end > last) {
                    sb.append(rest, last, open)
                    last = end
                }
            }
            sb.append(rest.substring(last))
            rest = sb.toString()
        }
        return rest
    }

    // ── 判据 ──────────────────────────────────────────────────────────────

    /** 判据 S1：pendingUrl 是可观察状态（by mutableStateOf），不是裸 var。 */
    private fun pendingIsObservable(holder: String): Boolean =
        Regex("var\\s+pendingUrl\\s*:\\s*String\\?\\s+by\\s+mutableStateOf").containsMatchIn(holder) &&
            !Regex("var\\s+pendingUrl\\s*:\\s*String\\?\\s*=\\s*null").containsMatchIn(holder)

    /** 判据 S2：pendingUrl 头上不得再挂 @Volatile（Volatile ≠ 可观察，留着就是误导）。 */
    private fun noVolatileOnPendingUrl(holderRaw: String): Boolean =
        !Regex("@Volatile\\s+(private\\s+)?var\\s+pendingUrl").containsMatchIn(holderRaw)

    /** 判据 S3：组合体里读 pendingUrl（不在 remember / effect 块内）——读在组合期才会登记依赖。 */
    private fun readInComposition(screen: String): Boolean =
        Regex("val\\s+pending\\s*=\\s*BrowserHolder\\.pendingUrl").containsMatchIn(compositionBody(screen))

    /** 判据 S4：消费 effect 以「值」为 key，不得再以 Unit 为 key。 */
    private fun consumedByKeEffect(screen: String): Boolean {
        val keyed = blockOf(screen, "LaunchedEffect(pending)")
        if (!keyed.contains("BrowserHolder.pendingUrl = null")) return false
        if (!keyed.contains("openExternal(context, pending)")) return false
        val unitBlocks = Regex("LaunchedEffect\\s*\\(\\s*Unit\\s*\\)").findAll(screen)
            .map { m -> block(screen, screen.indexOf('{', m.range.first)) }
            .toList()
        return unitBlocks.none { it.contains("BrowserHolder.pendingUrl") }
    }

    /** 判据 S5：先清槽、后开标签（顺序反了就有「开完才清、中途重组再开一次」的窗口）。 */
    private fun clearsBeforeOpening(screen: String): Boolean {
        val keyed = blockOf(screen, "LaunchedEffect(pending)")
        val clear = keyed.indexOf("BrowserHolder.pendingUrl = null")
        val open = keyed.indexOf("openExternal(context, pending)")
        return clear >= 0 && open >= 0 && clear < open
    }

    /** 判据 S6：null 只消费一次 —— 块内对 pendingUrl 的写只有一处，且 openExternal 只有一处。 */
    private fun consumesExactlyOnce(screen: String): Boolean {
        val keyed = blockOf(screen, "LaunchedEffect(pending)")
        return assertEquals2(1, Regex("BrowserHolder\\.pendingUrl\\s*=\\s*null").findAll(keyed).count()) &&
            assertEquals2(1, Regex("openExternal\\(").findAll(keyed).count()) &&
            assertEquals2(1, Regex("openExternal\\(").findAll(screen).count())
    }

    private fun assertEquals2(expected: Int, actual: Int): Boolean = expected == actual

    /** 判据 S7：外部写入方签名不变（仍是 `BrowserHolder.pendingUrl = …` 一行赋值）。 */
    private fun writersUnchanged(terminal: String, preview: String): Boolean =
        terminal.contains("BrowserHolder.pendingUrl = uri") &&
            preview.contains("BrowserHolder.pendingUrl = url")

    /** 判据 S8：没有第二条「待打开 URL」通道（holder 里 pending* 属性只有一个）。 */
    private fun noSecondChannel(holder: String): Boolean {
        val decls = Regex("(?:var|val)\\s+pending[A-Za-z]*").findAll(holder).map { it.value }.toList()
        val nullableUrlSlots = Regex("(?:var|val)\\s+(?!pendingUrl)[A-Za-z]*(?:Url|Uri)[A-Za-z]*\\s*[:=]")
            .findAll(holder).map { it.value }.toList()
        return decls.size == 1 && nullableUrlSlots.isEmpty()
    }

    // ── 正例：打真源码 ────────────────────────────────────────────────────

    @Test
    fun `P18_pendingUrl是可观察状态且不带Volatile`() {
        val holder = code(holderRel)
        assertTrue("pendingUrl 还是裸 var（或改成了别的不三的可观察形态）", pendingIsObservable(holder))
        assertTrue("@Volatile 仍挂在 pendingUrl 头上 —— Volatile 只给可见性，不给重组", noVolatileOnPendingUrl(holder))
    }

    @Test
    fun `P18_组合体读值且消费effect以值为key`() {
        val screen = code(screenRel)
        assertTrue("pendingUrl 没在组合体里读（读在 remember/effect 里 ⇒ 写入不会触发重组）", readInComposition(screen))
        assertTrue(
            "消费没改成 keyed effect，或 LaunchedEffect(Unit) 里仍在消费 pendingUrl（只消费一次的根因）",
            consumedByKeEffect(screen),
        )
    }

    @Test
    fun `P18_先清槽再开标签`() {
        assertTrue("清槽与 openExternal 的顺序反了或缺失", clearsBeforeOpening(code(screenRel)))
    }

    @Test
    fun `P18_一条URL只消费一次`() {
        assertTrue(
            "pendingUrl 置 null 或 openExternal 出现多于一处 —— key 变 null 后再跑一次就会重复开标签",
            consumesExactlyOnce(code(screenRel)),
        )
    }

    @Test
    fun `P18_写入方签名不变且没有第二套通道`() {
        assertTrue(
            "外部写入方（终端链接 / 抱朴预览卡）不再写 BrowserHolder.pendingUrl —— B 路径被改道了",
            writersUnchanged(code(terminalRel), code(previewRel)),
        )
        assertTrue("BrowserHolder 里冒出第二个 pending*／*Url 槽位 —— 新造了第二套待打开通道", noSecondChannel(code(holderRel)))
    }

    // ── 负例自证：打内嵌反例，证明判据在退化形态上必不通过 ────────────────

    @Test
    fun `负例1_pendingUrl改回裸Volatilevar时判据S1S2必拦`() {
        val holder = code(holderRel)
        val 反例 = holder.replace(
            Regex(
                "var\\s+pendingUrl\\s*:\\s*String\\?\\s+by\\s+mutableStateOf(?:<[^>]*>)?\\s*\\(\\s*null\\s*\\)",
            ),
            "@Volatile var pendingUrl: String? = null",
        )
        assertTrue("反例没造出来（源码形态与预期不符）", 反例 != holder)
        assertFalse(pendingIsObservable(反例))
        assertFalse(noVolatileOnPendingUrl(反例))
    }

    @Test
    fun `负例2_消费改回LaunchedEffectUnit时判据S4必拦`() {
        val screen = code(screenRel)
        val 反例 = screen
            .replace("LaunchedEffect(pending)", "LaunchedEffect(Unit)")
            .replace("val pending = BrowserHolder.pendingUrl\n", "")
        assertTrue("反例没造出来（源码形态与预期不符）", 反例 != screen)
        assertFalse(consumedByKeEffect(反例))
    }

    @Test
    fun `负例3_先开标签后清槽时判据S5必拦`() {
        val screen = code(screenRel)
        val keyed = blockOf(screen, "LaunchedEffect(pending)")
        val swapped = keyed
            .replace("BrowserHolder.pendingUrl = null", "\u0000")
            .replace("BrowserTabManager.openExternal(context, pending)", "BrowserTabManager.openExternal(context, pending); BrowserHolder.pendingUrl = null")
            .replace("\u0000", "BrowserTabManager.openExternal(context, pending)")
        assertTrue("反例没造出来（源码形态与预期不符）", swapped != keyed)
        val 反例 = screen.replace(keyed, swapped)
        assertFalse(clearsBeforeOpening(反例))
    }

    @Test
    fun `负例4_新造第二条待打开槽位时判据S8必拦`() {
        val holder = code(holderRel)
        val 反例 = holder.replace(
            Regex("var\\s+pendingUrl"),
            "var pendingUrl2: String? = null\n    var pendingUrl",
        )
        assertTrue("反例没造出来（源码形态与预期不符）", 反例 != holder)
        assertFalse(noSecondChannel(反例))
    }
}
