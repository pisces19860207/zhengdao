// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.bridge

import com.example.zhengdao.browser.PageSnapshot

/**
 * 第㊷刀：把值嵌进页面 JS 的唯一入口（台账 §B 顺序 73，一处根因、两个症状）。
 *
 * **根因**：[PageSnapshot.jsonEscape] 只做转义、**不加引号**——它的本职是拼 JSON
 * 响应字段（引号由调用方自己补）。第㊵刀复核发现桥里多处把它当「JS 字符串字面量」
 * 直接拼进页面代码 ⇒ 两个真机实证过的症状：
 * ① `execJs` 把 code 拼成 `String(eval(<code>))`——凡**返回字符串**的 code，其结果
 *    被当作源码**二次求值**；且二次 eval 落在页面上下文，被站点 CSP 的
 *    `unsafe-eval` 拦成 EvalError，无绕法。`document.title` 报 SyntaxError，
 *    `1+1` 碰巧能过（数字结果 eval 自身无害）。
 * ② `click`/`type`/`scroll` 拼 `document.querySelector(#btn)`——selector 成裸
 *    标识符，JS 语法错误 ⇒ click 23ms「假超时」、type 15s 断连。
 *
 * **修法**（本文件是唯一的嵌入通道）：
 * - 一切「值 → JS 代码」的嵌入走 [jsStringLiteral]（双引号 + [PageSnapshot.jsonEscape]
 *   转义 + 双引号）。jsonEscape 自身语义一字未动（BrowserCommands 拼 JSON 响应字段、
 *   浏览器导出器等处仍在复用）。
 * - `execJS` **不再 eval**：code 本来就是要执行的 JS 源码，**原样作为脚本**嵌进
 *   `try` 块（[execJsScript]）——eval 在带 CSP 的页面上必被拦，直接嵌入的 code 不受影响；
 *   顺带修掉旧写法把 code 按字符串字面量转义 ⇒ code 内含引号即源码语法错误的毛病。
 * - evaluateJavascript 的回调值本身就是 **JSON 字面量**：字符串结果带引号且内部
 *   已转义，按 JSON 字符串字面量规则去引号还原（[jsResultToString]），不再 eval、
 *   也不再「只剥引号不还原转义」（旧写法会把标题里的引号、正文里的换行带着
 *   反斜杠漏给调用方）。
 *
 * 本文件零 Android 依赖 ⇒ 纯函数单测直接覆盖（JsEmbedTest），不依赖真机。
 */

/** 带引号的 JS 字符串字面量：双引号 + [PageSnapshot.jsonEscape] 转义 + 双引号。 */
internal fun jsStringLiteral(s: String): String = "\"" + PageSnapshot.jsonEscape(s) + "\""

/**
 * `execJS` 的页面脚本：code **原样**作为**脚本**嵌进 `try{ … }catch(e){ 'ERR:'+e }`，
 * 结果取**完成值**（completion value）—— 表达式与语句序列因此**同一包装通吃**，
 * 且全程只求值一次、**无 eval**（理由见文件头 ①）。
 *
 * **为什么不再是 `return (<code>)`、也不再包 `(function(){…})()`**（第㊽刀 B，§B 顺序 77）：
 * ① `return ( … )` 的括号里只容得下**单个表达式**；含 `;` 的语句序列会让**整段脚本**
 *    成为**解析期**语法错误，`try/catch` 接不住解析期错误 ⇒ `evaluateJavascript` 回 null
 *    ⇒ 上层报「JS 执行超时或失败」（真机实测：`1+1` 正常、凡含分号的必挂）。
 * ② 把 code 放进**函数体**（`(function(){ try{ <code> }catch(e){ return 'ERR:'+e } })()`）
 *    同样不成立：在函数体里，**语句**的完成值会被丢弃、只有 `return` 才返回值（旧包装
 *    靠的正是那个 `return`）。实测 `(function(){try{1+1}catch(e){return 'ERR:'+e}})()` ⇒ `undefined`。
 *    （该写法在真机/引擎里同样挂多语句，且空 catch 会被 EmptyCatchGuardTest 判红——示例照
 *    实带上 ERR 分支。）
 * ③ 只有**脚本级**的 `try` 语句既保留完成值（规范：`try` 的完成值＝block 的完成值，
 *    catch 未执行时），又让 code 原样落地。⇒ 本包装。
 *
 * **规则**（判据进 JsEmbedTest 门禁三；不猜「字符串里有没有分号」——同一包装天然兼容）：
 * - code 是**表达式**时，完成值＝表达式的值（`1+1` → `2`，`document.title` → 标题）；
 * - code 是**语句序列**时，完成值＝**最后一条语句**的完成值（`var a=1; a+1` → `2`；
 *   末尾若是 `var` / `function` 声明这类无值语句，则完成值为 undefined，回调收 `null`，
 *   与旧契约「超时/失败归一 null」同形）；
 * - code 抛出的异常被 `catch` 接住并以 `ERR:` 前缀返回（旧契约，不断连）；
 * - code 末尾追加的换行，是让 code 自己以 `//` 行注释结尾时不会吃掉收尾的 `}`；
 * - 作用域：脚本级代码里 `var` 与函数声明落在**页面全局**（与浏览器执行一份脚本一致，
 *   也和第㊷刀及更早的函数体包装**不同**——旧包装把它们关在 IIFE 的局部作用域里）；
 *   `let`/`const` 留在本次脚本作用域内。
 * - **不支持的形态**（解析期错误，包装无解，旧写法同样挂，已在报告记档）：
 *   `return <值>`（`Illegal return statement`）、顶层 `await`（`await is only valid in
 *   async functions…`）。需要 `return` 包 `(function(){ … })()`；需要 `await` 包
 *   `(async()=>{ … })()`（其返回值在旧新两种包装下都只是 Promise，口径不变）。
 */
internal fun execJsScript(code: String): String =
    "try{" + code + "\n}catch(e){'ERR:'+e}"

/** `click` 的页面脚本：selector 必须经 [jsStringLiteral] 带引号嵌入。 */
internal fun clickJsScript(selector: String): String =
    "(function(){var el=document.querySelector(" + jsStringLiteral(selector) + ");" +
        "if(!el)return 'NOT_FOUND';el.click();return 'OK'})()"

/**
 * `type` 的页面脚本：selector 与 text 都经 [jsStringLiteral] 带引号嵌入；
 * 走原生 value setter 而不是 `el.value=`：React/Vue 受控组件监听的是原型链上的
 * setter，直接赋值不触发它们的 onChange（页面就像没收到输入）。
 */
internal fun typeJsScript(selector: String, text: String): String =
    "(function(){var el=document.querySelector(" + jsStringLiteral(selector) + ");" +
        "if(!el)return 'NOT_FOUND';" +
        "var desc=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el),'value');" +
        "if(desc&&desc.set)desc.set.call(el," + jsStringLiteral(text) + ");" +
        "else el.value=" + jsStringLiteral(text) + ";" +
        "el.dispatchEvent(new Event('input',{bubbles:true}));" +
        "el.dispatchEvent(new Event('change',{bubbles:true}));" +
        "return 'OK'})()"

/** `scroll` 按元素滚进视口的页面脚本：selector 同样带引号嵌入。 */
internal fun scrollElementJsScript(selector: String): String =
    "(function(){var el=document.querySelector(" + jsStringLiteral(selector) + ");" +
        "if(!el)return 'NOT_FOUND';el.scrollIntoView({behavior:'instant',block:'center'});return 'OK'})()"

/**
 * evaluateJavascript 回调值 → 页面求值结果的字符串形态。
 *
 * 回调值是 JSON 字面量：`null`/空 ⇒ null（保持旧契约「超时/失败归一 null」）；
 * 数字、对象等非串结果原样（本来就是 JSON 文本）；字符串结果去引号并**还原
 * JSON 转义**（第㊷刀：旧 normalizeJsResult 只剥引号，`\"`、`\n` 会带着反斜杠
 * 漏进结果——页面标题含引号、snapshot 的 outerHTML 多行文本都中招）。
 */
internal fun jsResultToString(raw: String?): String? {
    if (raw.isNullOrEmpty() || raw == "null") return null
    if (raw.length >= 2 && raw.first() == '"' && raw.last() == '"') {
        return unescapeJsonString(raw.substring(1, raw.length - 1))
    }
    return raw
}

/**
 * 还原 JSON 字符串字面量的转义序列（`\"` `\\` `\/` `\b` `\f` `\n` `\r` `\t`
 * `\uXXXX`）。宽容策略：畸形 `\u` 与未知转义不抛、按字面保留——evaluateJavascript
 * 产出的是合法 JSON，这条分支只是防御。
 */
internal fun unescapeJsonString(s: String): String {
    if (!s.contains('\\')) return s
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c != '\\' || i + 1 >= s.length) {
            sb.append(c)
            i++
            continue
        }
        when (val e = s[i + 1]) {
            '"' -> sb.append('"')
            '\\' -> sb.append('\\')
            '/' -> sb.append('/')
            'b' -> sb.append('\b')
            'f' -> sb.append('\u000C')
            'n' -> sb.append('\n')
            'r' -> sb.append('\r')
            't' -> sb.append('\t')
            'u' -> {
                val hex = s.substring(i + 2, (i + 6).coerceAtMost(s.length))
                val code = hex.toIntOrNull(16)
                if (hex.length == 4 && code != null && code >= 0) {
                    sb.append(code.toChar())
                    i += 6
                    continue
                }
                sb.append(c)
            }
            else -> sb.append(e)
        }
        i += 2
    }
    return sb.toString()
}
