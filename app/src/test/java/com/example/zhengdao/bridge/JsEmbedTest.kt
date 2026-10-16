// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.bridge

import com.example.zhengdao.browser.PageSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第㊷刀回归（台账 §B 顺序 73）：桥的「值 → JS 代码」嵌入必须带引号、不得二次 eval。
 *
 * 三层结构：
 * ① **纯函数正例**——[jsStringLiteral] / 各脚本构造器 / [jsResultToString]
 *    （JsEmbed.kt 零 Android 依赖，JVM 单测直接覆盖，不依赖真机）；
 * ② **源码级门禁**（正例打真源码）——剥注释后：桥源码（BrowserCommands + JsEmbed）
 *    零 `eval(`；每处 `document.querySelector(` 都在同一行带着 `jsStringLiteral(`；
 * ③ **负例自证**——把第㊵刀之前的旧形态（`String(eval(<code>))`、selector 裸拼
 *    jsonEscape）内嵌为回归串，断言判据**必然不通过**。真源码级演练（把引号助手
 *    临时改回裸拼 ⇒ 必红 ⇒ 还原复绿）留痕于第42刀报告。
 */
class JsEmbedTest {

    // ── ① 纯函数正例 ──────────────────────────────────────────────────────

    @Test
    fun `引号助手给值包上双引号`() {
        assertEquals("\"#btn\"", jsStringLiteral("#btn"))
    }

    @Test
    fun `引号助手转义内部引号与反斜杠`() {
        assertEquals("\"a\\\"b\\\\c\\nd\"", jsStringLiteral("a\"b\\c\nd"))
    }

    @Test
    fun `execJs 脚本不再 eval_code 原样作为表达式嵌入`() {
        val s = execJsScript("document.title")
        assertFalse("不得再 eval（页面 CSP unsafe-eval 会拦，真机实证）", s.contains("eval("))
        assertTrue(s.contains("return (document.title)"))
    }

    @Test
    fun `execJs 脚本对含引号的 code 保持源码原样`() {
        // 旧写法把 code 按字符串字面量转义 ⇒ code 内含引号即源码语法错误
        val s = execJsScript("document.getElementById(\"x\")")
        assertTrue(s.contains("(document.getElementById(\"x\"))"))
    }

    @Test
    fun `click 脚本里 selector 带引号`() {
        val s = clickJsScript("#btn")
        assertTrue("selector 必须以带引号的 JS 字符串字面量嵌入", s.contains("document.querySelector(\"#btn\")"))
    }

    @Test
    fun `type 脚本里 selector 与 text 都带引号`() {
        val s = typeJsScript("#inp", "he said \"hi\"")
        assertTrue(s.contains("document.querySelector(\"#inp\")"))
        assertTrue(s.contains("desc.set.call(el,\"he said \\\"hi\\\"\")"))
        assertTrue(s.contains("el.value=\"he said \\\"hi\\\"\""))
    }

    @Test
    fun `scroll 脚本里 selector 带引号`() {
        val s = scrollElementJsScript("#sec")
        assertTrue(s.contains("document.querySelector(\"#sec\")"))
    }

    @Test
    fun `字符串返回值按 JSON 字面量去引号并还原转义`() {
        assertEquals("PII Echo Test", jsResultToString("\"PII Echo Test\""))
        assertEquals("a\"b\nc", jsResultToString("\"a\\\"b\\nc\""))
        assertEquals("中", jsResultToString("\"\\u4e2d\""))
        assertEquals("C:\\path", jsResultToString("\"C:\\\\path\""))
    }

    @Test
    fun `非串与空结果保持旧契约`() {
        assertEquals("42", jsResultToString("42"))
        assertEquals("{\"a\":1}", jsResultToString("{\"a\":1}"))
        assertNull(jsResultToString(null))
        assertNull(jsResultToString(""))
        assertNull(jsResultToString("null"))
    }

    // ── ② 源码级门禁（正例打真源码） ──────────────────────────────────────

    private fun readSource(relativeFromRoot: String, relativeFromApp: String, what: String): String {
        val roots = listOf(java.io.File(relativeFromRoot), java.io.File(relativeFromApp))
        return roots.firstOrNull { it.isFile }?.readText()
            ?: error("找不到 $what（cwd=${java.io.File("").absolutePath}）")
    }

    private fun commandsSource(): String = readSource(
        "app/src/main/java/com/example/zhengdao/bridge/BrowserCommands.kt",
        "src/main/java/com/example/zhengdao/bridge/BrowserCommands.kt",
        "BrowserCommands.kt",
    )

    private fun jsEmbedSource(): String = readSource(
        "app/src/main/java/com/example/zhengdao/bridge/JsEmbed.kt",
        "src/main/java/com/example/zhengdao/bridge/JsEmbed.kt",
        "JsEmbed.kt",
    )

    /** 去注释（先剥再断言，否则 KDoc 里提一嘴就算自己拦自己）。 */
    private fun stripped(src: String): String = src
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)//.*$"), "")

    /** 判据①：桥源码零 eval —— execJS 结果不得被二次求值（`String(eval(` 一族全禁）。 */
    private fun noEvalAnywhere(sources: List<String>): Boolean =
        sources.none { it.contains("eval(") }

    /** 判据②：每处 querySelector 嵌入都同一行带着引号助手（裸拼 ⇒ 不通过）。 */
    private fun everySelectorQuoted(sources: List<String>): Boolean =
        sources.flatMap { it.lines() }
            .filter { it.contains("document.querySelector(") }
            .all { it.contains("jsStringLiteral(") }

    @Test
    fun `门禁一_桥源码零 eval`() {
        assertTrue(noEvalAnywhere(listOf(stripped(commandsSource()), stripped(jsEmbedSource()))))
    }

    @Test
    fun `门禁二_每处 querySelector 都带引号嵌入`() {
        assertTrue(everySelectorQuoted(listOf(stripped(commandsSource()), stripped(jsEmbedSource()))))
    }

    // ── ③ 负例自证（旧形态 ⇒ 判据必不通过） ──────────────────────────────

    /** 第㊵刀之前的旧 execJs 拼装（台账 §B 73 症状①）。 */
    private val oldExecJsSplice =
        "val script = \"(function(){try{return String(eval(\${PageSnapshot.jsonEscape(code)}))}\" + " +
            "\"catch(e){return 'ERR:'+e}})()\""

    /** 第㊵刀之前的旧 click 拼装（台账 §B 73 症状②）。 */
    private val oldClickSplice =
        "val js = \"(function(){var el=document.querySelector(\${PageSnapshot.jsonEscape(selector!!)});\" +"

    @Test
    fun `负例一_旧 execJs 裸拼必须不过门禁一`() {
        assertFalse(noEvalAnywhere(listOf(stripped(oldExecJsSplice))))
    }

    @Test
    fun `负例二_selector 裸拼 jsonEscape 必须不过门禁二`() {
        assertFalse(everySelectorQuoted(listOf(stripped(oldClickSplice))))
    }

    @Test
    fun `负例三_把引号助手改回裸拼则拼不出带引号的字面量`() {
        // 模拟「引号助手退化成裸 jsonEscape」：产出里 selector 不带引号 ⇒
        // 正例判据（contains "document.querySelector(\"#btn\")"）必然失败
        val bare = "(function(){var el=document.querySelector(" +
            PageSnapshot.jsonEscape("#btn") + ");if(!el)return 'NOT_FOUND';el.click();return 'OK'})()"
        assertFalse(bare.contains("document.querySelector(\"#btn\")"))
        assertTrue("裸拼产出正是缺陷形态：#btn 成了裸标识符", bare.contains("document.querySelector(#btn)"))
    }
}
