// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百一十三批（2026-11-12）· **工具层路径解析只有这一处**。
//
// 由来（569 真机取证，缺陷 §五-3）：`sheet_read` 收的是管线**已改写过的绝对路径**，
// 却又 `File(root, rel)` 拼了一次 ⇒ 真目标变成 `<工作区>/storage/emulated/0/…`，
// 于是「同目录 .md/.txt 读得到、.xlsx 一律不存在」，报错里回的却是那条**看起来对**的绝对路径。
// 根因不是可见性/归属账，是**二次拼接**。
//
// 三条口径（派工单 §一①）：
// 1. 相对路径 ⇒ 工作区根下；
// 2. 绝对路径（`/…`、盘符）⇒ 原样，**不再拼根**（幂等：已绝对再解析一次还是它）；
// 3. `file://` 前缀 ⇒ 去掉 scheme（含 `localhost`）并做百分号解码（`%20` 是空格）。
//
// `content://` 等外域 scheme **不认**（派工单：另立单）——[foreignScheme] 只负责
// 把它**识别出来**并对模型说人话，绝不当成相对路径拼进工作区（那会造出 `工作区/content:/…`
// 这种假路径，下一个读的人会以为文件真的不在）。
package com.example.zhengdao.baopu

import java.io.File
import java.net.URLDecoder

/**
 * 「用户/模型给的路径文字」→「盘上的 File」的唯一解析口（**纯函数**，JVM 直测）。
 *
 * 为什么必须是纯函数且只有一处：管线第 ② 段（`GuardPipeline.guardPath`）与工具自身
 * （绕过管线直调时）都要解析同一条路径；两处各写一套，就会出现「管线认、工具不认」
 * 或反过来——569 那一单正是这么来的。
 */
internal object UserPath {

    /** 唯一认得的 scheme 前缀（大小写不敏感）。 */
    private const val FILE_SCHEME = "file:"

    /** `/C:/x` 与 `C:/x` 两种形态的「盘符开头」判断（Windows 上的 file URI 归一后会落到这里）。 */
    private fun isDrivePath(text: String): Boolean {
        val at = if (text.startsWith("/")) 1 else 0
        return text.length >= at + 2 && text[at].isLetter() && text[at + 1] == ':'
    }

    /**
     * 不认的 scheme（`content://`、`http://` …）⇒ 返回 scheme 名；纯路径或 `file://` ⇒ null。
     *
     * 为什么先判盘符：Windows 上 `C:/x` 里的 `C:` 长得像 scheme，但长度 1 的字母是盘符。
     */
    fun foreignScheme(raw: String): String? {
        val text = raw.trim()
        val mark = text.indexOf("://")
        if (mark <= 0) return null
        val head = text.substring(0, mark).lowercase()
        if (head.length == 1 && head[0] in 'a'..'z') return null
        return if (head == "file") null else head
    }

    /**
     * 归一：去 `file:` 前缀（`file:///`／`file://localhost/`／`file:/` 三种写法都认）、
     * 百分号解码、去首尾空白；其余原样。
     *
     * 为什么认三种写法：`File.toURI()` 产出的是 `file:/C:/x`（单个斜杠），
     * 而人/模型手打的多半是 `file:///storage/x`——只认一种就会「同一路径两种命运」。
     * Windows 盘符形态（`/C:/x`）去掉前导斜杠（Android 上不存在这种形态，判一下无副作用）。
     */
    fun normalize(raw: String): String {
        val text = raw.trim()
        if (!text.lowercase().startsWith(FILE_SCHEME)) return text
        var rest = text.substring(FILE_SCHEME.length)
        // `//` 之后要么是空 host（`file:///x` 剥完还剩 `/x`），要么是 host（`file://localhost/x`），
        // 要么是盘符（`file://C:/x`，Windows 上 `"file://" + absPath` 就长这样）。
        if (rest.startsWith("//")) rest = rest.substring(2)
        if (!rest.startsWith("/") && !isDrivePath(rest)) {
            val slash = rest.indexOf('/')
            rest = if (slash >= 0) rest.substring(slash) else "/"
        }
        if (isDrivePath(rest) && rest.startsWith("/")) rest = rest.substring(1)
        if (!rest.startsWith("/") && !isDrivePath(rest)) rest = "/$rest"
        return percentDecode(rest)
    }

    /** 绝对性判据（与 `WorkspaceRootRules` 同一口径：`/…` 与平台绝对形态都算）。 */
    fun isAbsolute(text: String): Boolean = text.startsWith("/") || File(text).isAbsolute

    /**
     * 解析成 [File]：绝对原样（**绝不拼根**），相对落到 [root] 下。
     *
     * 幂等性是这个函数的契约：`resolve(root, resolve(root, p).absolutePath) == File(解析结果)`。
     * 管线把参数改写成绝对路径之后，工具再用一次这个函数不会把路径越拼越长——569 的修法就是它。
     */
    fun resolve(root: File, raw: String): File {
        val text = normalize(raw)
        return if (isAbsolute(text)) File(text) else File(root, text)
    }

    /**
     * 百分号解码（`%20` → 空格，中文的 `%E4%B8%AD` 也认）。
     *
     * 为什么不用裸 `URLDecoder`：它把 `+` 当空格，而 `+` 在文件名里是合法字符（「1+1.txt」）。
     * 这里先把 `+` 转义掉再交给 JDK，等价于「只解 %XX」。解不动就原样返回（宁可路径带
     * 百分号让人看见，也不许抛异常把整次调用打断）。
     */
    private fun percentDecode(text: String): String =
        if ('%' !in text) text
        else runCatching { URLDecoder.decode(text.replace("+", "%2B"), "UTF-8") }.getOrDefault(text)
}
