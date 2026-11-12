// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 依据的公开标准：JSON5 规范（json5.org）的一个**子集**——支持 // 与 /* */ 注释、
// 尾逗号、无引号键、单/双引号字符串、\uXXXX 与 \xXX 转义、十六进制与小数/指数；
// 不支持多行字符串、NaN/Infinity（小说资产文件用不到，遇到按坏文件报行号）。
package com.example.zhengdao.baopu.agent

/**
 * JSON5 子集解析器（609 硬要求①：坏文件必须报「第 N 行」，**不许静默空**）。
 *
 * 为什么手写而不用 org.json：JSONObject 只认严格 JSON（注释/尾逗号/单引号全炸），
 * 且报错不带行号；本仓零新依赖红线也不许引第三方 JSON5 库。
 *
 * 为什么每个节点带 [J5Node.start]/[J5Node.end] 原文偏移：写工具
 * （novel_timeline_append / novel_annotation_reply）做**文本级追加**——只在
 * 偏移处插入新元素，已有字节一个不动（对拍判据），缩进/注释/尾逗号全保留。
 *
 * 纯函数、零 Android，JVM 直测（NovelJson5Test）。
 */
internal object NovelJson5 {

    /** 语法树节点：[line] 从 1 起；[start]/[end] 是原文字符偏移（含头不含尾）。 */
    sealed class J5Node {
        abstract val line: Int
        abstract val start: Int
        abstract val end: Int
    }

    class J5Obj(
        val entries: List<Entry>,
        override val line: Int,
        override val start: Int,
        override val end: Int,
    ) : J5Node() {
        class Entry(val key: String, val value: J5Node)

        /** 同名键取**最后**一个（与 JSON 惯例一致）。 */
        operator fun get(key: String): J5Node? = entries.lastOrNull { it.key == key }?.value
    }

    class J5Arr(
        val items: List<J5Node>,
        override val line: Int,
        override val start: Int,
        override val end: Int,
    ) : J5Node()

    class J5Str(val value: String, override val line: Int, override val start: Int, override val end: Int) : J5Node()
    class J5Num(val value: Double, override val line: Int, override val start: Int, override val end: Int) : J5Node()
    class J5Bool(val value: Boolean, override val line: Int, override val start: Int, override val end: Int) : J5Node()
    class J5Null(override val line: Int, override val start: Int, override val end: Int) : J5Node()

    /** 解析失败：[line] 从 1 起；[text] 就是给用户/模型看的那句话。 */
    data class Error(val line: Int, val message: String) {
        val text: String get() = "第 $line 行：$message"
    }

    data class Result(val node: J5Node?, val error: Error?)

    fun parse(text: String): Result {
        val p = Parser(text)
        return try {
            Result(p.parseDocument(), null)
        } catch (e: ParseException) {
            Result(null, Error(e.line, e.message ?: "解析失败"))
        }
    }

    // ── 取值小助手（四个读工具共用；类型不对 ⇒ null，由调用方报行号） ──

    fun str(n: J5Node?): String? = (n as? J5Str)?.value
    fun obj(n: J5Node?): J5Obj? = n as? J5Obj
    fun arr(n: J5Node?): J5Arr? = n as? J5Arr
    fun int(n: J5Node?): Int? = (n as? J5Num)?.value?.toInt()

    /** 生成标准 JSON 字符串字面量（写工具拼新元素用；中文原样、控制字符转义）。 */
    fun quote(s: String): String = buildString {
        append('"')
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c.code < 32 -> append("\\u").append("%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    private class ParseException(val line: Int, message: String) : Exception(message)

    private class Parser(private val text: String) {
        private var i = 0
        private var line = 1

        fun parseDocument(): J5Node {
            val node = parseValue()
            skipWs()
            if (i < text.length) fail("JSON 结束后还有多余内容")
            return node
        }

        private fun fail(msg: String): Nothing = throw ParseException(line, msg)

        private fun peek(): Char = if (i < text.length) text[i] else 0.toChar()

        private fun advance(): Char {
            val c = text[i]
            i++
            if (c == '\n') line++
            return c
        }

        private fun skipWs() {
            while (i < text.length) {
                val c = text[i]
                when {
                    c == ' ' || c == '\t' || c == '\r' || c == '\n' -> advance()
                    c == '/' && i + 1 < text.length && text[i + 1] == '/' -> skipLineComment()
                    c == '/' && i + 1 < text.length && text[i + 1] == '*' -> skipBlockComment()
                    else -> return
                }
            }
        }

        private fun skipLineComment() {
            while (i < text.length && text[i] != '\n') advance()
        }

        private fun skipBlockComment() {
            val startLine = line
            advance()
            advance()
            while (true) {
                if (i >= text.length) throw ParseException(startLine, "块注释没闭合（/* 缺 */）")
                if (text[i] == '*' && i + 1 < text.length && text[i + 1] == '/') {
                    advance()
                    advance()
                    return
                }
                advance()
            }
        }

        private fun parseValue(): J5Node {
            skipWs()
            val c = peek()
            return when {
                c == '{' -> parseObj()
                c == '[' -> parseArr()
                c == '"' || c == '\'' -> parseString()
                c == 't' || c == 'f' || c == 'n' -> parseKeyword()
                c == '-' || c == '+' || c == '.' || c in '0'..'9' -> parseNumber()
                i >= text.length -> fail("文件到这里就没了，还缺一个值")
                else -> fail("不认识的值开头「$c」")
            }
        }

        private fun parseObj(): J5Obj {
            val startLine = line
            val start = i
            advance()
            val entries = mutableListOf<J5Obj.Entry>()
            skipWs()
            if (peek() == '}') {
                advance()
                return J5Obj(entries, startLine, start, i)
            }
            while (true) {
                skipWs()
                val key = parseKey()
                skipWs()
                if (peek() != ':') fail("键「$key」后面缺冒号")
                advance()
                entries += J5Obj.Entry(key, parseValue())
                skipWs()
                when (peek()) {
                    ',' -> {
                        advance()
                        skipWs()
                        if (peek() == '}') {
                            advance()
                            return J5Obj(entries, startLine, start, i)
                        }
                    }
                    '}' -> {
                        advance()
                        return J5Obj(entries, startLine, start, i)
                    }
                    else -> fail("对象里缺逗号或右括号")
                }
            }
        }

        private fun parseArr(): J5Arr {
            val startLine = line
            val start = i
            advance()
            val items = mutableListOf<J5Node>()
            skipWs()
            if (peek() == ']') {
                advance()
                return J5Arr(items, startLine, start, i)
            }
            while (true) {
                items += parseValue()
                skipWs()
                when (peek()) {
                    ',' -> {
                        advance()
                        skipWs()
                        if (peek() == ']') {
                            advance()
                            return J5Arr(items, startLine, start, i)
                        }
                    }
                    ']' -> {
                        advance()
                        return J5Arr(items, startLine, start, i)
                    }
                    else -> fail("数组里缺逗号或右括号")
                }
            }
        }

        private fun parseKey(): String {
            val c = peek()
            if (c == '"' || c == '\'') return parseString().value
            if (!c.isLetter() && c != '_' && c != '$') fail("这里应该是键名（无引号键只许字母/数字/_/$）")
            val s = i
            while (peek().isLetterOrDigit() || peek() == '_' || peek() == '$') advance()
            return text.substring(s, i)
        }

        private fun parseString(): J5Str {
            val startLine = line
            val start = i
            val quote = advance()
            val sb = StringBuilder()
            while (true) {
                if (i >= text.length) fail("字符串没闭合（缺 $quote）")
                val c = text[i]
                if (c == '\n') fail("字符串里不能直接换行（要换行请写 \\n）")
                if (c.code < 32 && c != '\t') fail("字符串里有控制字符（要写请转义）")
                advance()
                when {
                    c == quote -> return J5Str(sb.toString(), startLine, start, i)
                    c == '\\' -> sb.append(readEscape())
                    else -> sb.append(c)
                }
            }
        }

        /** 读一个转义（反斜杠已消费）；行 continuation（\+换行）返回空串。 */
        private fun readEscape(): String {
            if (i >= text.length) fail("转义没写完")
            if (peek() == '\n' || peek() == '\r') return readLineContinuation()
            return when (val c = advance()) {
                'n' -> "\n"
                't' -> "\t"
                'r' -> "\r"
                'b' -> 8.toChar().toString()
                'f' -> 12.toChar().toString()
                'u' -> readHexEscape(4)
                'x' -> readHexEscape(2)
                '\\', '/', '"', '\'' -> c.toString()
                else -> fail("不认识的转义：\\$c")
            }
        }

        private fun readLineContinuation(): String {
            if (peek() == '\r') advance()
            if (peek() == '\n') advance()
            return ""
        }

        private fun readHexEscape(n: Int): String {
            var v = 0
            repeat(n) {
                val d = hexDigit(peek()) ?: fail("转义里的十六进制位不合法：「${peek()}」")
                advance()
                v = v * 16 + d
            }
            return v.toChar().toString()
        }

        private fun hexDigit(c: Char): Int? = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> null
        }

        private fun parseKeyword(): J5Node {
            val startLine = line
            val start = i
            val word = when {
                text.startsWith("true", i) -> "true"
                text.startsWith("false", i) -> "false"
                text.startsWith("null", i) -> "null"
                else -> fail("不认识的值（只支持 true/false/null）")
            }
            repeat(word.length) { advance() }
            val next = peek()
            if (next.isLetterOrDigit() || next == '_' || next == '$') fail("不认识的值")
            return when (word) {
                "true" -> J5Bool(true, startLine, start, i)
                "false" -> J5Bool(false, startLine, start, i)
                else -> J5Null(startLine, start, i)
            }
        }

        private fun parseNumber(): J5Num {
            val startLine = line
            val start = i
            if (peek() == '-' || peek() == '+') advance()
            val value = if (text.startsWith("0x", i) || text.startsWith("0X", i)) {
                advance()
                advance()
                readHexNumber()
            } else {
                readDecimal()
            }
            return J5Num(value, startLine, start, i)
        }

        private fun readHexNumber(): Double {
            val s = i
            while (hexDigit(peek()) != null) advance()
            if (i == s) fail("十六进制数一位都没有")
            val raw = text.substring(s, i)
            return raw.toLongOrNull(16)?.toDouble() ?: fail("十六进制数太大：0x$raw")
        }

        private fun readDecimal(): Double {
            val s = i
            while (peek() in '0'..'9') advance()
            if (peek() == '.') {
                advance()
                while (peek() in '0'..'9') advance()
            }
            if (i == s || (i == s + 1 && text[s] == '.')) fail("数一位都没有")
            if (peek() == 'e' || peek() == 'E') {
                advance()
                if (peek() == '-' || peek() == '+') advance()
                val es = i
                while (peek() in '0'..'9') advance()
                if (i == es) fail("指数一位都没有")
            }
            val raw = text.substring(s, i)
            return raw.toDoubleOrNull() ?: fail("不是合法的数：$raw")
        }
    }
}