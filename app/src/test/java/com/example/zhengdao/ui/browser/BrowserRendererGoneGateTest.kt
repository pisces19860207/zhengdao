// 独立开发声明：本文件为本项目原创。
package com.example.zhengdao.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第一百七十二批 S2 · **渲染进程被回收**的验收门禁（L1/L2 的唯一硬断点）。
 *
 * 为什么要这一份：`WebViewClient.onRenderProcessGone` 不重写、或重写了却 `return false`，
 * 框架的默认处理是**终止应用进程**。玄览的承诺是「切回不重载」「前台不可见时页面活着」，
 * 这些都以"进程还在"为前提 —— 一次渲染进程回收就把 App 崩掉，比"重载一次"糟糕一个量级。
 *
 * 五条守卫（写法照 `BrowserRecycleGateTest`：读源码文本 + 花括号配平限定函数体，
 * 再加一条真行为用例钉住状态语义；纯文本门禁钉不到"标了状态却自动联网"这种语义）：
 * ① 引擎里确实重写了 `onRenderProcessGone` 且**返回 true**；
 * ② 委托把它转成「哪一颗标签」并交给管理器；
 * ③ 复位只废这一颗：不动 `activeId`、**不自动 loadUrl/reload**、必须换壳（死实例不可再用）；
 * ④ 界面在回收态画卡片并调 `reloadRecycled`（不是白屏）；
 * ⑤ 状态语义：回收态置真且停转圈、下次加载自动清掉。
 */
class BrowserRendererGoneGateTest {

    private fun src(rel: String): String {
        val f = File(rel).let { if (it.exists()) it else File("app/$rel") }
        assertTrue("找不到 $rel：${f.path}", f.isFile)
        return f.readText()
    }

    private fun engineSource() = src("src/main/java/com/example/zhengdao/browser/WebViewEngine.kt")

    private fun managerSource() = src("src/main/java/com/example/zhengdao/ui/browser/BrowserTabManager.kt")

    private fun screenSource() = src("src/main/java/com/example/zhengdao/ui/browser/BrowserScreen.kt")

    /** 取 `marker` 之后那个 `{ … }` 块的正文（按花括号配平），把断言限定在某个函数体内。 */
    private fun blockAfter(s: String, marker: String): String {
        val at = s.indexOf(marker)
        assertTrue("找不到标记 `$marker`", at >= 0)
        val open = s.indexOf('{', at)
        assertTrue("`$marker` 之后没有函数体 `{`", open > at)
        var depth = 0
        var i = open
        while (i < s.length) {
            when (s[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return s.substring(open + 1, i)
                }
            }
            i++
        }
        throw AssertionError("`$marker` 的花括号没配平")
    }

    // ── ① 引擎：重写了，而且必须 return true ──

    @Test
    fun `S2 引擎重写 onRenderProcessGone 且返回 true`() {
        val s = engineSource()
        assertTrue(
            "WebViewEngine 必须重写 onRenderProcessGone —— 不重写等于交给框架默认处理（杀 App 进程）",
            s.contains("override fun onRenderProcessGone("),
        )
        val body = blockAfter(s, "override fun onRenderProcessGone(")
        assertTrue(
            "必须 `return true`（声明已接管）—— 返回 false 会把应用进程交给系统杀掉",
            body.contains("return true"),
        )
        assertFalse(
            "onRenderProcessGone 体内不许出现 `return false`（那就是没接住）",
            body.contains("return false"),
        )
    }

    // ── ② 委托转成标签身份并交给管理器 ──

    @Test
    fun `S1 接线：切标签不再降级，回前台全量回升`() {
        val m = managerSource()
        val select = blockAfter(m, "fun selectTab(")
        assertFalse(
            "selectTab 里不许再出现 WAIVED / pauseAndWaive —— 判据 J1 的纯函数再对，" +
                "调用点把标签降级一样等于没修（L1「切回不重载」立刻失效）",
            select.contains("WAIVED") || select.contains("pauseAndWaive"),
        )
        assertTrue(
            "切到的那一颗必须按前台档升回来（resumeAndPromote）",
            select.contains("resumeAndPromote("),
        )

        val resume = blockAfter(m, "fun resumeAll(")
        assertTrue(
            "回前台必须**全量**回升（tabs.forEach），不是只救活跃那一颗",
            resume.contains("tabs.forEach"),
        )
        assertTrue(
            "resumeAll 必须把 appForeground 置真（优先级判据的数据源）",
            resume.contains("appForeground = true"),
        )

        val main = src("src/main/java/com/example/zhengdao/MainActivity.kt")
        assertTrue(
            "MainActivity 必须挂上 App 级前台跟踪（第一百七十二批 S4：跳到终端独立 Activity 时" +
                "本页也会 ON_PAUSE，在本页判前台/后台会把「用终端」误判成「退后台」）",
            main.contains("attachAppForegroundTracking("),
        )
        assertFalse(
            "MainActivity 不许再自己直调 pauseAll()/resumeAll() —— 前台真相只有一个来源",
            main.contains("BrowserTabManager.pauseAll()") ||
                main.contains("BrowserTabManager.resumeAll()"),
        )
        assertFalse(
            "旧入口 resumeActive() 不该再被调用（否则两份语义并存）",
            main.contains("BrowserTabManager.resumeActive()"),
        )

        // 唯一的驱动口：BrowserHolder.setAppForeground（写标记 + 暂停/恢复标签）
        val holder = src("src/main/java/com/example/zhengdao/ui/browser/BrowserHolder.kt")
        val setFg = blockAfter(holder, "fun setAppForeground(")
        assertTrue(
            "回前台必须走 resumeAll()、退后台必须走 pauseAll()（缺一半就是「只降不升」或反之）",
            setFg.contains("resumeAll()") && setFg.contains("pauseAll()"),
        )
        assertTrue(
            "前台跟踪必须挂在 attachAppForegroundTracking 里（Activity 生命周期回调）",
            holder.contains("attachAppForegroundTracking(") &&
                holder.contains("registerActivityLifecycleCallbacks("),
        )
    }

    @Test
    fun `S1 接线：降级只发生在退后台入口`() {
        val m = managerSource()
        val pause = blockAfter(m, "fun pauseAll(")
        assertTrue("退后台必须把 appForeground 置假", pause.contains("appForeground = false"))
        assertTrue("退后台必须给每颗标签上档（applyRendererPriority）", pause.contains("applyRendererPriority("))

        // 全文件里 WAIVED 档只允许由 policy 决定，管理器代码里不得硬写
        // （注释行要剔掉：类头 KDoc 讲历史时提到过这个常量名，那不是代码）
        val codeOnly = m.lines()
            .filterNot { line ->
                val t = line.trimStart()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            .joinToString("\n")
        val waiveLiteral = Regex("RENDERER_PRIORITY_WAIVED").findAll(codeOnly).count()
        assertEquals(
            "管理器代码里不许硬写 WAIVED 档（判据只允许住在 BrowserTabPolicy.rendererPriority）",
            0,
            waiveLiteral,
        )
    }

    @Test
    fun `S2 委托把回收事件按标签交给管理器`() {
        val m = managerSource()
        assertTrue(
            "per-tab 委托必须实现 onRenderProcessGone()（引擎只上报，不知道标签表）",
            m.contains("override fun onRenderProcessGone()"),
        )
        val body = blockAfter(m, "override fun onRenderProcessGone()")
        assertTrue(
            "委托必须把 `tab.id` 交给 markRendererGone —— 只废这一颗，别把表清掉",
            body.contains("markRendererGone(") && body.contains("tab.id"),
        )
    }

    // ── ③ 复位只废这一颗、不自动联网、必须换壳 ──

    @Test
    fun `S2 复位只废这一颗且不自动加载`() {
        val m = managerSource()
        val body = blockAfter(m, "fun markRendererGone(")

        assertTrue("必须标回收态（否则界面画白页，用户以为坏了）", body.contains("onRendererGone()"))
        assertFalse(
            "复位不许动 activeId —— 活跃标签必须仍然活跃（用户什么都没点）",
            body.contains("activeId ="),
        )
        assertFalse(
            "复位不许自动加载（loadUrl / reload / loadDataWithBaseURL 一个都不行）：点了才联网",
            body.contains("loadUrl(") || body.contains("reload(") || body.contains("loadDataWithBaseURL("),
        )
        assertTrue(
            "死实例不可再用（官方口径）⇒ 必须换壳重建",
            body.contains("reviveTab("),
        )
        assertTrue(
            "旧实例必须销毁，且走统一的三步销毁（摘挂载 → about:blank → destroy）",
            body.contains("destroySafely("),
        )
        assertFalse(
            "不许在管理器里直接调 WebView.destroy()（单一销毁出口纪律，见 BrowserRecycleGateTest）",
            body.contains(".destroy()"),
        )
    }

    @Test
    fun `S2 换壳保留同 id 与同一份状态`() {
        val m = managerSource()
        val revive = blockAfter(m, "private fun reviveTab(")
        assertTrue("新壳必须沿用旧 id（标签条位置/活跃态都不动）", revive.contains("dead.id"))
        assertTrue("新壳必须接住旧 state（页面地址/标题/预览身份不能丢）", revive.contains("dead.state"))
        assertTrue(
            "预览页签被回收后要按预览档重建（断网 + 默认禁 JS），不能因为回收就降级成上网页签",
            revive.contains("LocalPreviewPolicy.configFor("),
        )
    }

    // ── ④ 界面：回收态画卡片并调 reloadRecycled（不是白屏） ──

    @Test
    fun `S2 回收态界面画卡片且重载走 reloadRecycled`() {
        val s = screenSource()
        assertTrue(
            "BrowserScreen 必须识别 recycled 态并让卡片顶上来",
            s.contains("BrowserRecycledCard(") && s.contains("recycled"),
        )
        assertTrue(
            "卡片的重载动作必须走 BrowserTabManager.reloadRecycled（点了才加载的唯一入口）",
            s.contains("BrowserTabManager.reloadRecycled("),
        )
        assertFalse(
            "卡片不许直接 loadUrl —— 恢复逻辑（预览页签走注入、上网页签走地址）只在管理器里有判据",
            s.contains("onReload = { BrowserTabManager.activeEngine()"),
        )
    }

    // ── ⑤ 状态语义（真行为，不是字面量） ──

    @Test
    fun `S2 回收态语义_置真停转圈_下次加载自动清掉`() {
        val st = BrowserTabState()
        assertFalse("初始不该是回收态", st.recycled)

        st.loading = true
        st.onRendererGone()
        assertTrue("被回收后必须置回收态（界面据此画卡片）", st.recycled)
        assertFalse("被回收后转圈必须停（页面已经不转了）", st.loading)

        st.onPageStarted("https://example.com/a")
        assertFalse(
            "只要有加载真的开始了，回收态必须自动清掉 —— 否则卡片会挂在活页面上面",
            st.recycled,
        )
    }
}
