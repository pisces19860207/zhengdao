// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.bridge

import com.example.zhengdao.browser.PageSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 第㊷刀回归（台账 §B 顺序 73）＋ 第㊽刀 B 段新增（§B 顺序 77：execJS 多语句必挂）。
 *
 * 四层结构：
 * ① **纯函数正例**——[jsStringLiteral] / 各脚本构造器 / [jsResultToString]
 *    （JsEmbed.kt 零 Android 依赖，JVM 单测直接覆盖，不依赖真机）；
 * ② **源码级门禁**（正例打真源码）——剥注释后：桥源码（BrowserCommands + JsEmbed）
 *    零 `eval(`；每处 `document.querySelector(` 都在同一行带着 `jsStringLiteral(`；
 *    [execJsScript] 不得再产出 `return (`（第㊽刀 B 的形态判据，见门禁三）；
 * ③ **负例自证**——把旧形态（`String(eval(<code>))`、selector 裸拼 jsonEscape、
 *    `return (<code>)` 单表达式包装）内嵌为回归串，断言判据**必然不通过**；
 * ④ **JS 引擎求值自证**（第㊽刀 B）——把 [execJsScript] **现场产出**的脚本交给真 JS
 *    引擎（`node`）**求值**（不只是解析：表达式、语句序列、多行、引号、行注释、异常、
 *    不支持形态一一取值断言），钉住「包装形态在真引擎里的可求值性与取值语义」；
 *    `node` 缺席时 assume 跳过（CI ubuntu-latest 与开发机都有 node）。
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
    fun `execJs 脚本不再 eval_code 原样嵌进 try 块`() {
        val s = execJsScript("document.title")
        assertFalse("不得再 eval（页面 CSP unsafe-eval 会拦，真机实证）", s.contains("eval("))
        assertTrue("code 应原样落在 try 块里", s.contains("try{document.title" + "\n}"))
        assertFalse("第㊽刀 B：不得再包 IIFE —— 函数体里完成值会被丢弃", s.startsWith("(function()"))
    }

    @Test
    fun `execJs 脚本对含引号的 code 保持源码原样`() {
        // 旧写法把 code 按字符串字面量转义 ⇒ code 内含引号即源码语法错误
        val s = execJsScript("document.getElementById(\"x\")")
        assertTrue(s.contains("try{document.getElementById(\"x\")" + "\n}"))
    }

    @Test
    fun `execJs 脚本对表达式与语句序列产出同一种包装形态`() {
        val expr = execJsScript("1+1")
        val stmts = execJsScript("var a=1; a+1")
        assertTrue("包装体以脚本级 try 开头", expr.startsWith("try{"))
        assertFalse("第㊽刀 B：不得再回到 return (…) 单表达式包装", expr.contains("return ("))
        assertFalse("语句序列也必须落在同一个 try 块里", stmts.contains("return ("))
        assertTrue(stmts.contains("try{var a=1; a+1" + "\n}"))
        assertTrue("异常仍归一 ERR 前缀（旧契约）", stmts.endsWith("\n}catch(e){'ERR:'+e}"))
    }

    @Test
    fun `execJs 脚本给 code 末尾留换行以免行注释吃掉收尾大括号`() {
        val s = execJsScript("1+1 // 说明")
        assertTrue("code 与收尾大括号之间必须有换行", s.endsWith("1+1 // 说明" + "\n}catch(e){'ERR:'+e}"))
    }

    @Test
    fun `execJs 包装是脚本级而非 IIFE 作用域`() {
        val s = execJsScript("var a=1")
        assertFalse("IIFE 会把 var 关进局部作用域", s.contains("(function()"))
        // 代价记录：脚本级 `return` 是解析期错误（旧包装允许）—— 属「不支持形态」，见 KDoc 与报告
        assertTrue("包装不再为 code 提供函数作用域", s.startsWith("try{var a=1"))
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

    /**
     * 判据③（第㊽刀 B）：[execJsScript] 的函数体里不得再出现 `return (`。
     *
     * 只看**真源码**里 execJsScript 那一段（从签名到下一个 `internal fun`），因此
     * 「某个测试里恰好写了 return (…)」不会误伤，而「把包装退回单表达式」必被判红。
     */
    private fun gateThreeNoReturnParen(sources: List<String>): Boolean {
        val body = sources.mapNotNull { bodyOf(it, "internal fun execJsScript(") }.firstOrNull() ?: return false
        return !Regex("return\\s*\\(").containsMatchIn(body)
    }

    /** 取函数体：从函数签名到下一个顶层函数（源码已剥注释）。 */
    private fun bodyOf(src: String, signature: String): String? {
        val i = src.indexOf(signature)
        if (i < 0) return null
        val next = src.indexOf("\ninternal fun ", i + 1)
        return src.substring(i, if (next < 0) src.length else next)
    }

    @Test
    fun `门禁一_桥源码零 eval`() {
        assertTrue(noEvalAnywhere(listOf(stripped(commandsSource()), stripped(jsEmbedSource()))))
    }

    @Test
    fun `门禁二_每处 querySelector 都带引号嵌入`() {
        assertTrue(everySelectorQuoted(listOf(stripped(commandsSource()), stripped(jsEmbedSource()))))
    }

    @Test
    fun `门禁三_execJs 包装不再用单表达式 return 括号`() {
        assertTrue(
            "第㊽刀 B：包装必须是 try{code} 完成值形态，不得回到 return (…)（多语句必挂）",
            gateThreeNoReturnParen(listOf(stripped(jsEmbedSource()))),
        )
    }

    // ── ③ 负例自证（旧形态 ⇒ 判据必不通过） ──────────────────────────────

    /** 第㊵刀之前的旧 execJs 拼装（台账 §B 73 症状①）。 */
    private val oldExecJsSplice =
        "val script = \"(function(){try{return String(eval(\${PageSnapshot.jsonEscape(code)}))}\" + " +
            "\"catch(e){return 'ERR:'+e}})()\""

    /** 第㊵刀之前的旧 click 拼装（台账 §B 73 症状②）。 */
    private val oldClickSplice =
        "val js = \"(function(){var el=document.querySelector(\${PageSnapshot.jsonEscape(selector!!)});\" +"

    /** 第㊽刀 B 之前的旧 execJs 包装（台账 §B 77：单表达式 `return (…)`）。 */
    private val oldExecJsReturnParenSplice =
        "internal fun execJsScript(code: String): String =\n" +
            "    \"(function(){try{return (\" + code + \")}catch(e){return 'ERR:'+e}})()\""

    /** 第㊽刀 B 之前的旧包装本身（单表达式 `return (…)`），供负例演练与 JS 引擎对照使用。 */
    private val oldExecJsReturnParen: (String) -> String =
        { code -> "(function(){try{return (" + code + ")}catch(e){return 'ERR:'+e}})()" }

    /** 第㊷刀的函数体包装形态（本次评估过、因完成值被丢弃而否掉的解法）。 */
    private val functionBodyForm: (String) -> String =
        { code -> "(function(){try{" + code + "\n}catch(e){return 'ERR:'+e}})()" }

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

    @Test
    fun `负例五_旧单表达式包装必须不过门禁三`() {
        assertFalse(gateThreeNoReturnParen(listOf(stripped(oldExecJsReturnParenSplice))))
    }

    @Test
    fun `负例六_把包装退回单表达式则形态判据必红`() {
        val old = oldExecJsReturnParen("var a=1; a+1")
        assertTrue("旧形态正是 return (…) 单表达式", old.contains("return (var a=1; a+1)"))
        assertFalse("形态正例（try{code 换行}）对旧形态必然不成立", old.contains("try{var a=1; a+1" + "\n}"))
    }

    // ── ④ JS 引擎规范化自证（第㊽刀 B） ───────────────────────────────────

    private class JsProbe(val source: String, val output: String, val exitCode: Int) {
        /** 取回被测脚本的求值结果（标记之后的第一个非空行）。 */
        fun value(): String {
            val i = output.indexOf(NODE_MARKER)
            return if (i < 0) output.trim() else output.substring(i + NODE_MARKER.length).trim()
        }
    }

    /**
     * 把一串 JS 源文本交给 node 求值；返回源码与合并输出（含非 0 退出的错误文本）。
     *
     * 探针形态与 `WebView.evaluateJavascript` 同构：**脚本以自身完成值为结果**。
     * 被测脚本原样写进 `case.js`，探针只用 `vm.runInThisContext` 读它并取完成值——
     * 与 Chromium 执行一份经典脚本同语义；**不包函数、不包 `String(…)`**（包了就是
     * 换一种求值语义：实测 `(function(){1+1})()` 与 `String(1+1; 2)` 都会失真）。
     */
    private fun probeWithNode(scriptText: String, preamble: String = ""): JsProbe {
        val exe = nodeExecutable() ?: run {
            assumeTrue("本机/CI 无 node，跳过 JS 引擎自证（形态判据仍由门禁三与 ① 正例覆盖）", false)
            throw AssertionError("unreachable")
        }
        val dir = java.nio.file.Files.createTempDirectory("zd-execjs-jsprobe").toFile()
        try {
            // 被测脚本原样落到 case.js（零转义层：任何转义助手本身都可能引入假象），
            // probe.js 只用 vm.runInThisContext 把它**当脚本**求值并打印完成值 ——
            // 与 Chromium 执行一份经典脚本（evaluateJavascript 的实现路径）同语义。
            java.io.File(dir, "case.js").writeText(preamble + "\n" + scriptText + "\n")
            val jsFile = java.io.File(dir, "probe.js")
            jsFile.writeText(
                "const fs=require('fs'),vm=require('vm'),path=require('path');\n" +
                    "let v;\n" +
                    "try{v=vm.runInThisContext(fs.readFileSync(path.join(__dirname,'case.js'),'utf8'))}\n" +
                    "catch(e){process.stderr.write(String((e&&e.stack)||e));process.exit(1)}\n" +
                    "process.stdout.write('" + NODE_MARKER + "'+String(v));\n",
            )
            val p = ProcessBuilder(exe, jsFile.absolutePath).redirectErrorStream(true).start()
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
            val done = p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)
            return JsProbe(scriptText, out, if (done) p.exitValue() else -1)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** 可用的 node 可执行名；缺席（或跑不起来）返回 null，由调用方 assume 跳过。结果缓存一次。 */
    private fun nodeExecutable(): String? {
        if (nodeProbeResolved) return nodeProbeExe
        nodeProbeResolved = true
        val exe = if (java.io.File.separatorChar == '\\') "node.exe" else "node"
        nodeProbeExe = try {
            val p = ProcessBuilder(exe, "--version").redirectErrorStream(true).start()
            p.inputStream.readBytes()
            val ok = p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0
            if (ok) exe else null
        } catch (_: Exception) {
            null
        }
        return nodeProbeExe
    }

    /** 挂在页面全局上的 document 替身（node 里没有 DOM，取值路径与真 WebView 一致）。 */
    private val domPreamble = "globalThis.document={title:'zd-title'};"

    @Test
    fun `JS 引擎下派工单三种形态求值正确`() {
        assertEquals("2", probeWithNode(execJsScript("1+1")).value())
        assertEquals("2", probeWithNode(execJsScript("var a=1; a+1")).value())
        assertEquals("zd-title", probeWithNode(execJsScript("document.title"), domPreamble).value())
    }

    @Test
    fun `JS 引擎下含引号与多行的 code 求值正确`() {
        assertEquals("3", probeWithNode(execJsScript("var a=1;\nvar b=2;\na+b")).value())
        assertEquals(
            "HE SAID \"HI\"",
            probeWithNode(execJsScript("\"he said \\\"hi\\\"\".toUpperCase()")).value(),
        )
        assertEquals("1", probeWithNode(execJsScript("({a:1,\n b:2}).a")).value())
    }

    @Test
    fun `JS 引擎下 code 以行注释结尾仍可求值`() {
        assertEquals("2", probeWithNode(execJsScript("1+1 // 说明")).value())
    }

    @Test
    fun `JS 引擎下末尾无值语句归一 undefined_对应上层契约的 null`() {
        assertEquals("undefined", probeWithNode(execJsScript("var a=1")).value())
        assertEquals("undefined", probeWithNode(execJsScript("let b=1")).value())
        // console.log 也是无值语句（node 的 console 打印会落在标记之前，不影响取值）
        assertEquals("undefined", probeWithNode(execJsScript("console.log('zd-probe-side-effect')")).value())
    }

    @Test
    fun `JS 引擎下页面异常仍回 ERR 前缀`() {
        assertTrue(
            "页面侧异常必须归一 ERR: 前缀（旧契约）",
            probeWithNode(execJsScript("throw new Error('boom')")).value().startsWith("ERR:"),
        )
        assertTrue(
            "运行时错误同样归一",
            probeWithNode(execJsScript("document.title"), domPreamble + "globalThis.document=null;").value().startsWith("ERR:"),
        )
    }

    @Test
    fun `JS 引擎下不支持形态仍是解析期错误_与包装无关`() {
        val ret = probeWithNode(execJsScript("return 42"))
        assertTrue("脚本级 return 是解析期错误", ret.exitCode != 0)
        assertTrue(ret.output.contains("Illegal return statement"))
        val aw = probeWithNode(execJsScript("await 1"))
        assertTrue("顶层 await 是解析期错误", aw.exitCode != 0)
        assertTrue(aw.output.contains("await is only valid in async functions"))
        // 第㊷刀的函数体包装：return 合法（本就在函数里），await 照样解析期挂
        assertEquals("42", probeWithNode(functionBodyForm("return 42")).value())
        assertTrue(probeWithNode(functionBodyForm("await 1")).exitCode != 0)
    }

    @Test
    fun `JS 引擎下脚本级 var 落在页面全局_与旧函数体包装不同`() {
        val scopeProbe = "var zdGlobalProbe=7; String(globalThis.zdGlobalProbe)"
        assertEquals(
            "脚本级包装：var 落在页面全局",
            "7",
            probeWithNode(execJsScript(scopeProbe)).value(),
        )
        assertEquals(
            "第㊷刀的函数体包装：var 被关进 IIFE 局部作用域",
            "undefined",
            probeWithNode(functionBodyForm(scopeProbe)).value(),
        )
    }

    @Test
    fun `负例四_JS 引擎下旧包装对多语句必挂而新包装可求值`() {
        val code = "var a=1; a+1"
        val control = probeWithNode(oldExecJsReturnParen("1+1"))
        assertEquals("探针本身必须有效（旧包装对单个表达式照样求值）", "2", control.value())
        val old = probeWithNode(oldExecJsReturnParen(code))
        assertTrue("旧包装对多语句必须非 0 退出（解析期 SyntaxError）", old.exitCode != 0)
        assertTrue("错误文本必须是解析期语法错误", old.output.contains("SyntaxError"))
        assertEquals("新包装对同一 code 正常求值", "2", probeWithNode(execJsScript(code)).value())
    }

    @Test
    fun `负例七_JS 引擎下函数体包装的语句完成值被丢弃`() {
        // 函数体包装（「把 code 放进函数体」的另一种直觉解法）在真引擎里返回 undefined ⇒
        // 它不算修好：本条即该直觉解法的反例
        val functionBodyForm = "(function(){try{1+1\n}catch(e){}})()"
        val p = probeWithNode(functionBodyForm)
        assertEquals("函数体里语句的完成值被丢弃", "undefined", p.value())
        assertEquals("同一 code 在脚本级包装下可取到值", "2", probeWithNode(execJsScript("1+1")).value())
    }

    private companion object {
        /** node 探针的输出标记：把被测脚本的求值结果从其它输出里摘出来。 */
        const val NODE_MARKER = "__ZD_PROBE__"

        /** node 可执行名探测结果（一次进程探测，复用给所有探针）。 */
        var nodeProbeResolved = false
        var nodeProbeExe: String? = null
    }
}
