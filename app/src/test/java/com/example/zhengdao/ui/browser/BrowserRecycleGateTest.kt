// 独立开发声明：本文件为本项目原创。
package com.example.zhengdao.ui.browser

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * D-4（第⑱刀）· **WebView 回收出口**的验收门禁。
 *
 * 审计原话（行号已漂，按 `git grep BrowserHolder` 重定位）：`ui/browser/BrowserHolder.kt` 用
 * applicationContext 建单例 WebView，**全仓没有任何 destroy() 出口**，两个 Activity 的
 * onTrimMemory 也不碰它 ⇒ 内存压力下没人回收。
 *
 * 三条守卫（写法照 `ui/SettingsDestructiveConfirmGateTest.kt`：读源码文本做断言）：
 * ① [BrowserHolder] 里确实存在 `destroy()` 回收调用，且被 `releaseIfIdle()` 的守卫包住；
 * ② `MainActivity` / `TerminalActivity` 的 `onTrimMemory` 都调了回收入口；
 * ③ 没有退化成"每次 `obtain()` 都新建"（单例保活是刻意设计，改了会丢 cookie / 登录态）。
 *
 * 文本门禁的已知局限：注释里的同名字面量也能满足断言 —— 所以断言挑**成对出现才有意义**的
 * 形态（守卫语句 + 调用点），并额外用**运行期**用例钉"玄览在屏上时不回收"这条语义。
 * 真机读数（内存是否真的被系统回收、连点是否崩）归蓝总管采。
 */
class BrowserRecycleGateTest {

    private fun src(rel: String): String {
        val f = File(rel).let { if (it.exists()) it else File("app/$rel") }
        assertTrue("找不到 $rel：${f.path}", f.isFile)
        return f.readText()
    }

    private fun holderSource() = src("src/main/java/com/example/zhengdao/ui/browser/BrowserHolder.kt")

    private fun managerSource() = src("src/main/java/com/example/zhengdao/ui/browser/BrowserTabManager.kt")

    /**
     * 取 `marker` 之后那个 `{ … }` 块的正文（按花括号配平），用来把断言**限定在某个函数体内** ——
     * 否则"文件里出现过这个字面量"会被注释或别处的同名调用蒙混过去。
     */
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

    @After
    fun tearDown() {
        // object 单例跨用例串味：两个都还原。
        BrowserTabManager.resetForTest()
        BrowserHolder.onBrowserHidden()
    }

    // ── ① BrowserHolder 里确实存在 destroy() 回收调用 ──

    @Test
    fun `D-4 BrowserHolder 有受控回收出口且直达 destroy`() {
        val s = holderSource()

        assertTrue(
            "BrowserHolder 必须有受控回收出口 releaseIfIdle()（审计 D-4 的落点）",
            s.contains("fun releaseIfIdle("),
        )
        assertTrue(
            "BrowserHolder 里必须真的有 WebView.destroy() 调用 —— 否则等于没加出口",
            s.contains(".destroy()"),
        )
        val destroyFn = blockAfter(s, "private fun destroySafely(")
        assertTrue(
            "destroy() 必须落在销毁函数体内（回收路径直达销毁，不是在别处偶然出现）",
            destroyFn.contains(".destroy()"),
        )
        val releaseFn = blockAfter(s, "fun releaseIfIdle(): Boolean")
        assertTrue(
            "releaseIfIdle() 必须先判 isIdle() 再销毁 —— 绝不能无条件 destroy",
            releaseFn.contains("if (!isIdle()) return false"),
        )
        assertTrue(
            "releaseIfIdle() 必须真的销毁摘下来的实例（走 destroySafely）",
            releaseFn.contains("destroySafely("),
        )
        assertTrue(
            "回收必须摘空标签（否则留下已销毁实例的引用，下次 obtain 拿到死 WebView）",
            releaseFn.contains("detachAllForRelease("),
        )
    }

    @Test
    fun `D-4 回收只在 TRIM_COMPLETE 档触发且'是否可回收'判据正确`() {
        val s = holderSource()
        assertTrue(
            "必须给 Activity 提供 onTrimMemory(level) 入口",
            s.contains("fun onTrimMemory(level: Int)"),
        )
        val trimFn = blockAfter(s, "fun onTrimMemory(level: Int)")
        assertTrue(
            "只在 TRIM_MEMORY_COMPLETE（进程即将被回收）档才真回收",
            trimFn.contains("TRIM_MEMORY_COMPLETE"),
        )
        assertTrue(
            "onTrimMemory 必须把 level 一路传到 releaseIfIdle（经 isIdle 守卫）",
            trimFn.contains("releaseIfIdle()"),
        )
        assertTrue(
            "'没有活动标签需要保活'的判据交给纯函数 canReleaseForMemory。" +
                "第一百七十二批 S4 起把「App 不在前台」也写进判据（L2 是必保承诺，" +
                "不能只靠「前台一般到不了 TRIM_COMPLETE 档」这条经验兜着）",
            s.contains("canReleaseForMemory(") &&
                s.contains("browserOnScreen = browserOnScreen") &&
                s.contains("appForeground = appForeground") &&
                s.contains("tabCount = BrowserTabManager.count()"),
        )

        // manager 侧必须提供"只摘不销毁"的回收口（单一 destroy 出口留在 BrowserHolder）
        val m = managerSource()
        assertTrue("BrowserTabManager 必须提供 detachAllForRelease()", m.contains("fun detachAllForRelease("))
        val detach = blockAfter(m, "fun detachAllForRelease(")
        assertFalse(
            "detachAllForRelease 只负责摘，不许自己 destroy（destroy 出口只允许一个，在 BrowserHolder）",
            detach.contains(".destroy()"),
        )
        assertTrue("摘完必须清空标签列表", detach.contains("tabs.clear()"))
        assertTrue("摘完必须把活跃 id 置空（否则活跃标签指向已摘实例）", detach.contains("activeId = null"))
    }

    // ── ② 两个 Activity 的 onTrimMemory 都调了回收入口 ──

    @Test
    fun `D-4 两个 Activity 的 onTrimMemory 都调了回收出口`() {
        for (rel in listOf(
            "src/main/java/com/example/zhengdao/MainActivity.kt",
            "src/main/java/com/example/zhengdao/TerminalActivity.kt",
        )) {
            val body = blockAfter(src(rel), "override fun onTrimMemory(level: Int)")
            assertTrue(
                "$rel：onTrimMemory 里必须调用 BrowserHolder.onTrimMemory(level) —— " +
                    "否则系统内存压力下没人回收玄览 WebView（D-4 原样复发）",
                body.contains("BrowserHolder.onTrimMemory(level)"),
            )
        }
    }

    // ── ③ 没有变成"每次 obtain() 都新建" ──

    @Test
    fun `D-4 没有退化成每次 obtain 都新建 WebView`() {
        val m = managerSource()
        val obtain = blockAfter(m, "fun obtain(context: Context): WebViewEngine")

        val reuseIdx = obtain.indexOf("activeEngine()?.let { return it }")
        assertTrue(
            "obtain() 必须先返回已有活跃引擎（单例保活是刻意的：改了会丢 cookie / 登录态）",
            reuseIdx >= 0,
        )
        val newIdx = obtain.indexOf("newTab(")
        assertTrue(
            "obtain() 里'先复用已有的'必须早于'新建'（顺序一颠倒就退化成每次新建）",
            newIdx < 0 || reuseIdx < newIdx,
        )
        assertFalse(
            "obtain() 不许顺手清空/回收标签（那会把刻意保活的实例丢掉）",
            obtain.contains("detachAllForRelease") || obtain.contains("releaseIfIdle"),
        )

        // WebView 实例只允许在 newTab 里 new 出来（唯一构图点）
        val newTab = blockAfter(m, "fun newTab(context: Context, url: String? = null): Int?")
        assertTrue("WebView 实例必须在 newTab 里创建", newTab.contains("WebView("))
    }

    // ── 运行期：玄览在屏上时不回收（文本门禁钉不到的那条语义） ──

    @Test
    fun `D-4 玄览显示中不回收_离线且无标签时也不误报成功`() {
        BrowserTabManager.resetForTest()

        BrowserHolder.onBrowserShown()
        assertFalse(
            "玄览正显示在屏幕上时不许回收（挂载中的 WebView destroy 是未定义行为）",
            BrowserHolder.releaseIfIdle(),
        )

        BrowserHolder.onBrowserHidden()
        assertFalse(
            "没有标签时没有可回收对象，releaseIfIdle 不该报成功",
            BrowserHolder.releaseIfIdle(),
        )
        assertFalse(
            "isIdle() 在'有标签才可回收'这一半上为假（此处 0 标签）",
            BrowserHolder.isIdle(),
        )

        // 档位语义：轻档不动 WebView，COMPLETE 档才尝试回收；两者都不得抛。
        BrowserHolder.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        BrowserHolder.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }
}
