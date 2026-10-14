// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * §B 顺序 2（第六批 · 状态寿命与手感硬伤）里**可钉门禁**那两条的验收。
 *
 * 本刀先做 P2-1 / P2-4 两条（都能一行级修 + 门禁）；P2-2（草稿）、P2-3（切 Tab 会话）
 * 需要设计（SaveableStateHolder / 状态提升），P2-5（三处删除二次确认）要逐个看交互，
 * 都不适合和这两条混在一个提交里。
 *
 * 为什么用文本门禁而不是 Compose UI 测试：项目当前 Compose UI 测试为 0（P4-5），
 * 搭第一个要先加 `ui-test-junit4` 依赖、定规则，不该塞进本刀（建议单独一刀）。
 * 门禁证明的是"没人把修法退回去"，不是"转屏一定不跳"——后者归真机复验。
 */
class StateLifetimeGateTest {

    private fun src(rel: String): String {
        val f = File(rel).let { if (it.exists()) it else File("app/$rel") }
        assertTrue("找不到 $rel：${f.path}", f.isFile)
        return f.readText()
    }

    /**
     * 去掉注释后的源码（R-24，2026-10-14 审计第 2 期）。
     *
     * 旧的 `src(...).contains(...)` 有个洞：注释里写一句 `var tab by … rememberSaveable`
     * 就能让门禁变绿（"门禁绿、功能死"）；反过来，注释里出现 `var tab by remember {` 的示例
     * 还会让 `assertFalse` 误报。判据换成"**代码里**真的这么写"，只剥 `//` 与 `/* */`，
     * 字符串字面量保留。
     */
    private fun code(rel: String): String = stripComments(src(rel))

    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == '"' && src.startsWith("\"\"\"", i) -> { // 原始字符串 """..."""
                    val end = src.indexOf("\"\"\"", i + 3).let { if (it < 0) src.length else it + 3 }
                    out.append(src, i, end)
                    i = end
                }
                c == '"' || c == '\'' -> { // 普通字符串 / 字符字面量（保留）
                    val quote = c
                    out.append(c)
                    i++
                    while (i < src.length) {
                        val d = src[i]
                        out.append(d)
                        i++
                        if (d == '\\' && i < src.length) { out.append(src[i]); i++; continue }
                        if (d == quote) break
                    }
                }
                c == '/' && i + 1 < src.length && src[i + 1] == '/' -> {
                    while (i < src.length && src[i] != '\n') i++
                }
                c == '/' && i + 1 < src.length && src[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) i++
                    i = (i + 2).coerceAtMost(src.length)
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /** P2-1：转屏跳回丹房 —— tab 状态必须跨配置变更存活。 */
    @Test
    fun `MainActivity_tab 状态必须 rememberSaveable`() {
        val s = code("src/main/java/com/example/zhengdao/MainActivity.kt")
        assertTrue(
            "tab 必须用 rememberSaveable（remember 不跨转屏/深浅色/字体大小 ⇒ 一转屏就被打回初始值）",
            Regex("""var\s+tab\s+by\s+androidx\.compose\.runtime\.saveable\.rememberSaveable""").containsMatchIn(s),
        )
        assertFalse(
            "不该再出现 `var tab by remember {`（那就是 P2-1 本身）",
            Regex("""var\s+tab\s+by\s+remember\s*[{<]""").containsMatchIn(s),
        )
    }

    /** P2-4：欢迎页不能滚 —— 小屏/大字号下内容被裁。 */
    @Test
    fun `WelcomeScreen_必须可滚`() {
        val s = code("src/main/java/com/example/zhengdao/ui/WelcomeScreen.kt")
        assertTrue(
            "欢迎页根 Column 必须加 verticalScroll（否则内容比屏高时被裁，看不到开始按钮）",
            s.contains("verticalScroll(")
        )
        assertTrue(
            "滚动状态要 rememberScrollState()（每次重组新建 state 会丢滚动位置）",
            s.contains("rememberScrollState()")
        )
    }
}
