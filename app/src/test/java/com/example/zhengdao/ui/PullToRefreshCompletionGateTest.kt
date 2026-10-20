// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第六十八批 §3 的源码门禁：下拉刷新不再用 350ms 假装完成。
 *
 * 判据（先剥注释再断言）：
 * ① `delay(350)` 与「onRefresh 里无条件置 false」都不得再出现；
 * ② spinner 由加载 effect 的真实结果收尾：IO 读完/镜像写完 → DONE；抛异常 → FAILED；
 *    取消单独 rethrow（不能误报失败）；
 * ③ 10s 安全超时是第三个出口，覆盖「effect 永远不回来」；
 * ④ `stateTick++` 这条唯一刷新路径保留，没有第二条刷新通道；
 * ⑤ 纯函数 `ptrEnd` 的优先级：失败 > 超时 > 完成。
 *
 * 负例：把 350ms 假完成改回去 ⇒ ① 红。
 */
class PullToRefreshCompletionGateTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun src(rel: String): String {
        val f = repoFile(rel)
        assertTrue("找不到 $rel：${f.path}", f.isFile)
        return f.readText()
    }

    /** 只剥 `//` 与块注释（保留字符串字面量、保留换行以稳住定位逻辑）。 */
    private fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' && text.startsWith("\"\"\"", i) -> {
                    val end = text.indexOf("\"\"\"", i + 3).let { if (it < 0) text.length else it + 3 }
                    out.append(text, i, end)
                    i = end
                }
                c == '"' || c == '\'' -> {
                    val quote = c
                    out.append(c)
                    i++
                    while (i < text.length) {
                        val d = text[i]
                        out.append(d)
                        i++
                        if (d == '\\' && i < text.length) { out.append(text[i]); i++; continue }
                        if (d == quote) break
                    }
                }
                c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                    while (i < text.length && text[i] != '\n') i++
                }
                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
                    i = (i + 2).coerceAtMost(text.length)
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun home(): String = stripComments(src("src/main/java/com/example/zhengdao/ui/HomeScreen.kt"))

    private fun onRefreshBlock(): String {
        val s = home()
        val start = s.indexOf("onRefresh = {")
        val end = s.indexOf("},", start)
        assertTrue("找不到 onRefresh", start >= 0)
        assertTrue("找不到 onRefresh 的收尾", end > start)
        return s.substring(start, end + 2)
    }

    private fun pollEffect(): String {
        val s = home()
        val start = s.indexOf("LaunchedEffect(agents, stateTick)")
        val end = s.indexOf("val lifecycleOwner =", start)
        assertTrue("找不到常驻轮询 effect", start >= 0)
        assertTrue("找不到轮询 effect 的结束边界", end > start)
        return s.substring(start, end)
    }

    private fun timeoutEffect(): String {
        val s = home()
        val start = s.indexOf("LaunchedEffect(ptrRefreshing)")
        val end = s.indexOf("androidx.compose.runtime.LaunchedEffect(agents, stateTick)", start)
        assertTrue("找不到 10s 安全超时 effect", start >= 0)
        assertTrue("找不到超时 effect 的结束边界", end > start)
        return s.substring(start, end)
    }

    @Test
    fun `不得再出现350ms假完成`() {
        val s = home()
        assertFalse("HomeScreen 里不许再有 delay(350)", s.contains("delay(350)"))
        val pull = onRefreshBlock()
        assertFalse("onRefresh 里不许再起定时器", pull.contains("delay("))
        assertFalse("onRefresh 里不许再无条件收起 spinner", pull.contains("ptrRefreshing = false"))
        assertFalse("onRefresh 里不许另起一条刷新通道（launch）", pull.contains("launch"))
    }

    @Test
    fun `完成由加载effect收尾`() {
        val poll = pollEffect()
        assertTrue("加载完成后要问「这次是不是下拉触发的」", poll.contains("if (ptrRefreshing)"))
        assertTrue("收尾必须走统一出口", poll.contains("endPtrRefresh("))
        assertTrue("失败要按失败出口收尾", poll.contains("ptrEnd(loadFailed = failure != null)"))
        assertTrue("加载异常要落进 Result.failure，不许静默", poll.contains("Result.failure(t)"))
        assertTrue("协程取消必须单独 rethrow", poll.contains("catch (ce: CancellationException)"))
        assertTrue("失败收尾必须带原因（不静默）", poll.contains("it.message ?: it.javaClass.simpleName"))
    }

    @Test
    fun `十秒安全超时是第三出口`() {
        val s = home()
        assertTrue("安全超时必须是 10 秒", s.contains("internal const val PTR_TIMEOUT_MS = 10_000L"))
        val timeout = timeoutEffect()
        assertTrue("超时出口必须用 PTR_TIMEOUT_MS", timeout.contains("delay(PTR_TIMEOUT_MS)"))
        assertTrue(
            "超时后只在仍转圈时收尾（正常完成先到就不重复提示）",
            timeout.contains("if (ptrRefreshing) endPtrRefresh(ptrEnd(timedOut = true))"),
        )
        assertTrue("失败出口要有错误提示", s.contains("R.string.home_refresh_failed"))
        assertTrue("超时出口要有错误提示", s.contains("R.string.home_refresh_timeout"))
        assertEquals(
            "ptrRefreshing 的 false 只该有统一出口一处（三个出口共用，防漏收/重复收）",
            1,
            Regex(Regex.escape("ptrRefreshing = false")).findAll(s).count(),
        )
    }

    @Test
    fun `stateTick单一路径保留`() {
        val pull = onRefreshBlock()
        assertTrue("下拉先亮 spinner", pull.contains("ptrRefreshing = true"))
        assertTrue("仍走 stateTick++ 触发重载", pull.contains("stateTick++"))
        assertTrue("转圈状态仍接在 PullToRefreshBox 上", home().contains("isRefreshing = ptrRefreshing"))
    }

    @Test
    fun `退出条件纯函数_失败优先于超时`() {
        assertEquals(PtrEnd.DONE, ptrEnd())
        assertEquals(PtrEnd.FAILED, ptrEnd(loadFailed = true))
        assertEquals(PtrEnd.TIMED_OUT, ptrEnd(timedOut = true))
        assertEquals(PtrEnd.FAILED, ptrEnd(loadFailed = true, timedOut = true))
        assertEquals(10_000L, PTR_TIMEOUT_MS)
    }

    @Test
    fun `刷新文案中英都在且非空`() {
        val zh = repoFile("src/main/res/values/strings.xml").readText()
        val en = repoFile("src/main/res/values-en/strings.xml").readText()
        for (k in listOf("home_refresh_failed", "home_refresh_timeout")) {
            val marker = "name=\"$k\">"
            assertTrue("中文缺 `$k`", zh.contains(marker))
            assertTrue("英文缺 `$k`", en.contains(marker))
            assertFalse("中文 `$k` 是空串", zh.contains(marker + "</string>"))
            assertFalse("英文 `$k` 是空串", en.contains(marker + "</string>"))
        }
    }
}
