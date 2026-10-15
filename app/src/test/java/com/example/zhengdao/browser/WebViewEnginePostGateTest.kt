// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WebViewEngine 投递路径源码门禁（第㊳刀，台账 §B 顺序 66 / ERRATA E-142）。
 *
 * ## 为什么值得一条门禁
 *
 * 第㊱刀起始页给空态加了「不挂 WebView」的守卫（视觉需要：共享 WebView 默认白底在深色下
 * 把起始页压到看不见）。该守卫本身没错，错在它和 `WebViewEngine` 的投递路径叠在一起：
 * 引擎所有外部调用走 `webView.post { }`，而 **未 attach 的 View**，`View.post` 不投主线程
 * Handler、只把 Runnable 压进 View 自己的 HandlerActionQueue、attach 时才执行 ⇒ 首次
 * loadUrl 无限期推迟 ⇒ onPageStarted 不回 ⇒ everLoaded 永不翻转 ⇒ WebView 永不挂载
 * （鸡生蛋死锁）。真机表象：桥 navigate 假成功、主页键/地址栏/必应 chip 全死、
 * execJS/click/type/scroll/snapshot 全 8s 超时、screenshot 只出 1×1 空白 PNG。
 *
 * 这个死锁**没有任何运行期测试会红**（编译通过、单测全绿、界面不崩，只是永远白屏），
 * 只有源码门禁拦得住：谁把投递路径改回 View 自身的 post（或删掉主 Looper Handler、
 * 或只改一半漏掉某个方法、或回退第㊱刀的 everLoaded 守卫），本门禁必须红。
 *
 * ## 判据钉什么（ERRATA E-133 的教训：钉实质，不钉局部变量名）
 *
 * ① 剥注释后 [WebViewEngine.kt] 不得出现 `webView.post`（首载路径不再依赖 View attach）；
 * ② 剥注释后仍持有 `private val main = Handler(Looper.getMainLooper())`（进程级主 Handler）；
 * ③ 七个外部调用（loadUrl / goBack / goForward / reload / stopLoading /
 *    evaluateJavascript / captureScreenshot）逐一经 `main.post` 投递（漏一个都不行）；
 * ④ [BrowserScreen.kt] 的空态判据仍引用 everLoaded（第㊱刀的守卫不许借机回退——
 *    本刀修的是投递路径，不是守卫本身）。
 *
 * ## 正例 + 负例自证
 *
 * 每条判据抽纯函数；正例打真源码，负例打「真源码退化一处的混合串」（模拟四种退化形态），
 * 断言判据在负例上**必然不通过**——证明判据有牙。另按项目传统做真源码级负例演练：
 * 临时把一处 `main.post` 改回 `webView.post`，本测试必须红，还原复绿（留痕于报告）。
 */
class WebViewEnginePostGateTest {

    // ── 源码读取（照 BrowserStartPageGateTest 的双路径兜底：cwd 可能是仓根或 app/） ──

    private fun readSource(relativeFromRoot: String, relativeFromApp: String, what: String): String {
        val roots = listOf(
            java.io.File(relativeFromRoot),
            java.io.File(relativeFromApp),
        )
        return roots.firstOrNull { it.isFile }?.readText()
            ?: error("找不到 $what（cwd=${java.io.File("").absolutePath}）")
    }

    private fun engineSource(): String = readSource(
        "app/src/main/java/com/example/zhengdao/browser/WebViewEngine.kt",
        "src/main/java/com/example/zhengdao/browser/WebViewEngine.kt",
        "WebViewEngine.kt",
    )

    private fun browserScreenSource(): String = readSource(
        "app/src/main/java/com/example/zhengdao/ui/browser/BrowserScreen.kt",
        "src/main/java/com/example/zhengdao/ui/browser/BrowserScreen.kt",
        "BrowserScreen.kt",
    )

    /**
     * 去注释（**必须做**，NavTransitionGateTest 第 19 刀同款教训）：本测试文件与
     * WebViewEngine 的 KDoc 里到处写着 `webView.post` 这个词——不剥注释就是自己拦自己。
     * **凡断言，先剥注释再断言**（正例负例同此）。
     */
    private fun stripped(src: String): String = src
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)//.*$"), "")

    // ── 判据纯函数（正例负例共用同一份牙口） ──

    /** 判据 A：剥注释后不得出现 View 自身的 post（首载路径不再依赖 attach）。 */
    private fun noViewPost(src: String): Boolean = !src.contains("webView.post")

    /** 判据 B：剥注释后持有进程级主 Looper Handler 字段。 */
    private fun hasMainLooperHandler(src: String): Boolean =
        Regex("private\\s+val\\s+main\\s*=\\s*Handler\\(Looper\\.getMainLooper\\(\\)\\)")
            .containsMatchIn(src)

    /** 七个外部调用（同步读状态的 canGoBack/canGoForward 不在列——它们不投递）。 */
    private val postedMethods = listOf(
        "loadUrl", "goBack", "goForward", "reload", "stopLoading",
        "evaluateJavascript", "captureScreenshot",
    )

    /** 取 `override fun <name>(` 起、到下一个 `override fun`（或 EOF）止的方法体。 */
    private fun methodBody(src: String, name: String): String {
        val start = src.indexOf("fun $name(")
        if (start < 0) return ""
        val next = src.indexOf("override fun", start + 1)
        val end = if (next < 0) src.length else next
        return src.substring(start, end)
    }

    /** 判据 C：七个外部调用逐一经 main.post 投递。 */
    private fun everyMethodPostsToMain(src: String): Boolean =
        postedMethods.all { methodBody(src, it).contains("main.post") }

    /**
     * 判据 D：BrowserScreen 的 AndroidView 调用点之前的守卫条件仍引用 everLoaded
     * （第㊱刀的守卫不许借机回退——本刀修的是投递路径，不是守卫本身；
     * 守卫条件的精确形态由 BrowserStartPageGateTest 判据 B 钉住，此处只防「删守卫」）。
     */
    private fun everLoadedGuardKept(src: String): Boolean {
        val androidViewAt = src.indexOf("AndroidView(")
        return androidViewAt >= 0 && src.substring(0, androidViewAt).contains("everLoaded")
    }

    // ── 正例（打真源码） ──

    @Test
    fun `剥注释后 WebViewEngine 不再出现 webView 自身 post`() {
        assertTrue(noViewPost(stripped(engineSource())))
    }

    @Test
    fun `剥注释后持有主 Looper Handler 字段`() {
        assertTrue(hasMainLooperHandler(stripped(engineSource())))
    }

    @Test
    fun `七个外部调用逐一经 main post 投递`() {
        assertTrue(everyMethodPostsToMain(stripped(engineSource())))
    }

    @Test
    fun `BrowserScreen 空态判据仍引用 everLoaded`() {
        assertTrue(everLoadedGuardKept(stripped(browserScreenSource())))
    }

    // ── 负例（真源码退化一处，判据必须不通过——证明有牙） ──

    @Test
    fun `负例_把一处投递改回 webView post 必须不过判据A`() {
        val regressed = stripped(engineSource())
            .replaceFirst(Regex("main\\.post \\{"), "webView.post {")
        assertFalse(noViewPost(regressed))
    }

    @Test
    fun `负例_删掉主 Handler 字段必须不过判据B`() {
        val regressed = stripped(engineSource())
            .replaceFirst(
                Regex("private\\s+val\\s+main\\s*=\\s*Handler\\(Looper\\.getMainLooper\\(\\)\\)"),
                "",
            )
        assertFalse(hasMainLooperHandler(regressed))
    }

    @Test
    fun `负例_漏改某个方法必须不过判据C`() {
        // 模拟「只改一半」：goBack 退回裸调用（不投递）
        val regressed = stripped(engineSource())
            .replaceFirst(
                Regex("main\\.post \\{ if \\(webView\\.canGoBack\\(\\)\\) webView\\.goBack\\(\\) \\}"),
                "if (webView.canGoBack()) webView.goBack()",
            )
        assertFalse(everyMethodPostsToMain(regressed))
    }

    @Test
    fun `负例_回退 everLoaded 守卫必须不过判据D`() {
        val regressed = stripped(browserScreenSource())
            .replace("everLoaded", "true")
        assertFalse(everLoadedGuardKept(regressed))
    }
}
