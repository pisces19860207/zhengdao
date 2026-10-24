// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十六批 · 生产脚本烟雾测试：BrowserTeachDom 用的 JS（TeachScripts/
// TeachRecorderJs）在 JVM 里没法真跑（要 WebView），这里钉**形状**：
// 关键片段在、值都走转义嵌入、没有 eval、含引号的值进不了选择器。
// 语义行为的等价性由 FakeDom（TeachFakeDom）与执行器判据测试覆盖。
package com.example.zhengdao.baopu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeachScriptsTest {

    @Test
    fun `scan脚本_十档都有对应查询片段`() {
        assertTrue(TeachScripts.scan(TeachLocator(TeachLocatorKind.TESTID, "q")).contains("[data-testid="))
        assertTrue(TeachScripts.scan(TeachLocator(TeachLocatorKind.ARIA_LABEL, "搜索")).contains("[aria-label="))
        assertTrue(TeachScripts.scan(TeachLocator(TeachLocatorKind.ELEM_ID, "kw")).contains("[id="))
        assertTrue(TeachScripts.scan(TeachLocator(TeachLocatorKind.INPUT_NAME, "kw")).contains("[name="))
        assertTrue(TeachScripts.scan(TeachLocator(TeachLocatorKind.PLACEHOLDER, "输入")).contains("[placeholder="))
        assertTrue(TeachScripts.scan(TeachLocator(TeachLocatorKind.ROLE_NAME, "button|搜索")).contains("querySelectorAll('*')"))
        assertTrue(TeachScripts.scan(TeachLocator(TeachLocatorKind.TEXT, "搜索")).contains("zlT(el)==\"搜索\""))
        assertTrue(TeachScripts.scan(TeachLocator(TeachLocatorKind.CSS_CLASS, "btn.primary")).contains(".btn.primary"))
        assertTrue(TeachScripts.scan(TeachLocator(TeachLocatorKind.XPATH, "//button")).contains("document.evaluate"))
        // 统一出口：可见性过滤＋≤5 个＋JSON 返回
        listOf(
            TeachLocator(TeachLocatorKind.TESTID, "q"),
            TeachLocator(TeachLocatorKind.ROLE_NAME, "button|搜索"),
        ).forEach { loc ->
            val s = TeachScripts.scan(loc)
            assertTrue(s.contains("zlV"))
            assertTrue(s.contains("slice(0,5)"))
            assertTrue(s.contains("JSON.stringify"))
        }
    }

    @Test
    fun `act脚本_唯一命中才动手_动作各就各位`() {
        val click = TeachScripts.act(TeachLocator(TeachLocatorKind.TEXT, "查询"), TeachAction.CLICK, null)
        assertTrue(click.contains("els.length!=1"))
        assertTrue(click.contains("el.click()"))
        val type = TeachScripts.act(TeachLocator(TeachLocatorKind.ELEM_ID, "q"), TeachAction.TYPE, "WB12345678")
        assertTrue(type.contains("getOwnPropertyDescriptor"))
        assertTrue(type.contains("dispatchEvent(new Event('input'"))
        val select = TeachScripts.act(TeachLocator(TeachLocatorKind.INPUT_NAME, "s"), TeachAction.SELECT, "2")
        assertTrue(select.contains("el.value=\"2\""))
        val scroll = TeachScripts.act(TeachLocator(TeachLocatorKind.CSS_CLASS, "list"), TeachAction.SCROLL, null)
        assertTrue(scroll.contains("scrollIntoView"))
    }

    @Test
    fun `findBySnapshot脚本_带最佳锚计算`() {
        val s = TeachScripts.findBySnapshot(TeachElementSnapshot("BUTTON", "button", "查询", "查询", null))
        assertTrue(s.contains("zlBest"))
        assertTrue(s.contains("zlU"))
        assertTrue(s.contains("\"查询\""))
    }

    @Test
    fun `指纹与摘要脚本_取表头与按钮`() {
        assertTrue(TeachScripts.HEADERS_AND_BUTTONS.contains("querySelectorAll('th')"))
        assertTrue(TeachScripts.HEADERS_AND_BUTTONS.contains("zlV"))
        assertTrue(TeachScripts.DOM_SUMMARY.contains("document.title"))
        assertTrue(TeachScripts.CURRENT_URL.contains("location.href"))
        assertTrue(TeachScripts.ROW_COUNT.contains("table tr"))
    }

    @Test
    fun `录制注入_监听齐全_队列在window`() {
        val inject = TeachRecorderJs.INJECT
        assertTrue(inject.contains("window.__zlTeach"))
        assertTrue(inject.contains("addEventListener('click'"))
        assertTrue(inject.contains("addEventListener('change'"))
        assertTrue(inject.contains("addEventListener('scroll'"))
        // 只记录不干预：capture 旁听，不 stopPropagation、不 preventDefault
        assertFalse(inject.contains("stopPropagation"))
        assertFalse(inject.contains("preventDefault"))
        val facts = TeachRecorderJs.FACTS
        assertTrue(facts.contains("querySelectorAll('th')"))
        assertTrue(facts.contains("location.href"))
    }

    @Test
    fun `安全_所有脚本无eval_引号值进不了选择器`() {
        val all = listOf(
            TeachScripts.scan(TeachLocator(TeachLocatorKind.TESTID, "q")),
            TeachScripts.act(TeachLocator(TeachLocatorKind.TEXT, "查询"), TeachAction.CLICK, null),
            TeachScripts.findBySnapshot(TeachElementSnapshot("BUTTON", "button", "查询", "查询", null)),
            TeachScripts.HEADERS_AND_BUTTONS,
            TeachScripts.CURRENT_URL,
            TeachScripts.ROW_COUNT,
            TeachScripts.DOM_SUMMARY,
            TeachRecorderJs.INJECT,
            TeachRecorderJs.FACTS,
        )
        all.forEach { assertFalse("脚本不许用 eval：${it.take(60)}", it.contains("eval(")) }

        // 值里带引号/反斜杠 ⇒ CSS 属性档直接短路成不可能值（运行时 0 命中），不拼进选择器
        val evil = TeachLocator(TeachLocatorKind.TESTID, "x']alert(1)//")
        val script = TeachScripts.scan(evil)
        assertFalse(script.contains("\"x']alert(1)//\""))
        assertTrue(script.contains("SKIP-IMPOSSIBLE"))
    }

    @Test
    fun `textPresent_文本走jsStringLiteral转义`() {
        val s = TeachScripts.textPresent("查询 \"引号\" 与 \\ 反斜杠")
        // 嵌入的一定是转义后的字面量（\u0022 之类），不是裸引号裸反斜杠
        assertTrue(s.contains("\\\\"))
    }
}
