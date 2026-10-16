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
 * - `execJS` **不再 eval**：code 本来就是要执行的 JS 源码，直接作为表达式嵌入
 *   （[execJsScript]）——eval 在带 CSP 的页面上必被拦，直接嵌入的表达式不受影响；
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
 * `execJS` 的页面脚本：code 作为表达式**直接**嵌入，不经 eval（理由见文件头 ①）。
 * try/catch 保留旧契约：页面侧异常以 `ERR:` 前缀回给 agent，不抛到桥。
 */
internal fun execJsScript(code: String): String =
    "(function(){try{return (" + code + ")}catch(e){return 'ERR:'+e}})()"

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
