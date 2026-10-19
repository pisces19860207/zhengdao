// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：docs/格式层规格-三套卡与酒馆PNG-2026-10-19.md §2.1（输入只给字节）、
// §5.6（UTF-8 严格解码、BOM 保留）、§16.2（键集合与键序不许变）。
//
// 为什么要自己扫一遍键序：本仓测试跑在 JVM 上（依赖 `org.json:json`），
// 这一版的 `JSONObject` 用 HashMap 存键 ⇒ `keys()` **不保序**；而 Android 自带
// 那份用 LinkedHashMap（保序）。两套行为不一致，如果判据依赖 `keys()`，
// 「测试绿、真机红」就会发生。⇒ 键序一律从**文本**里扫，两套环境同一结果。
package com.example.zhengdao.baopu.card

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 编解码与 JSON 只读辅助（纯函数，零 Android 依赖）。
 *
 * 三条硬规矩（规格 §5.6／§16.2）：
 * 1. UTF-8 一律**严格**（`CodingErrorAction.REPORT`），坏字节返回 null 而不是 `�`；
 * 2. BOM **保留**为 U+FEFF（往返判据比的是「你存进去的那串字符」）；
 * 3. 键序从文本扫，不依赖 `JSONObject.keys()`。
 */
internal object CardJson {

    /** BOM（U+FEFF）。 */
    const val BOM = '\uFEFF'

    /** 严格 UTF-8 解码；解码失败返回 null（调用方据此报错，不许替换成 `�`）。 */
    fun decodeUtf8Strict(bytes: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }

    /** 严格 UTF-8 编码；遇到孤立代理项返回 null（不许静默变 `?`）。 */
    fun encodeUtf8Strict(text: String): ByteArray? = try {
        val bb = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(text))
        ByteArray(bb.remaining()).also { bb.get(it) }
    } catch (e: CharacterCodingException) {
        null
    }

    /** 顶层键（按文本顺序，最多 [limit] 个）。不是合法对象 ⇒ 空列表。 */
    fun topLevelKeys(text: String, limit: Int = 20): List<String> {
        val s = withoutBom(text)
        var i = skipWs(s, 0)
        if (i >= s.length || s[i] != '{') return emptyList()
        i++
        val keys = ArrayList<String>()
        while (i < s.length && keys.size < limit) {
            i = skipWs(s, i)
            if (i >= s.length || s[i] == '}') break
            if (s[i] != '"') break
            val end = skipString(s, i)
            if (end < 0) break
            keys += unescapeKey(s.substring(i + 1, end - 1))
            i = skipWs(s, end)
            if (i >= s.length || s[i] != ':') break
            i = skipWs(s, i + 1)
            i = skipValue(s, i)
            i = skipWs(s, i)
            if (i < s.length && s[i] == ',') { i++; continue }
            break
        }
        return keys
    }

    /** 去掉开头的 BOM（**只给解析用**；导出/往返不许调它）。 */
    fun withoutBom(text: String): String =
        if (text.isNotEmpty() && text[0] == BOM) text.substring(1) else text

    /** 解析成 JSON 对象；不是对象或语法坏 ⇒ null。 */
    fun parseObject(text: String): JSONObject? = try {
        val v = JSONTokener(withoutBom(text)).nextValue()
        v as? JSONObject
    } catch (e: Exception) {          // org.json 抛 JSONException；越界等也一并吞
        null
    }

    // ── 取值：**先判类型再取**（org.json 的 optInt 会把字符串 "3" 也吃成 3） ──

    fun intOf(o: JSONObject?, key: String): Int? {
        val v = o?.opt(key) ?: return null
        return v as? Int
    }

    fun longOf(o: JSONObject?, key: String): Long? {
        val v = o?.opt(key) ?: return null
        return v as? Long
    }

    fun numberAsInt(o: JSONObject?, key: String): Int? {
        val v = o?.opt(key) ?: return null
        return when (v) {
            is Int -> v
            is Long -> if (v in Int.MIN_VALUE..Int.MAX_VALUE) v.toInt() else null
            else -> null
        }
    }

    fun boolOf(o: JSONObject?, key: String): Boolean? = o?.opt(key) as? Boolean

    fun stringOf(o: JSONObject?, key: String): String? = o?.opt(key) as? String

    /** `null` 与「缺键」分开：返回 true 表示**键在、值是 JSON null**。 */
    fun isJsonNull(o: JSONObject?, key: String): Boolean =
        o != null && o.has(key) && o.opt(key) === JSONObject.NULL

    fun hasNonNull(o: JSONObject?, key: String): Boolean =
        o != null && o.has(key) && o.opt(key) !== JSONObject.NULL

    fun arrayOf(o: JSONObject?, key: String): JSONArray? = o?.opt(key) as? JSONArray

    fun objectOf(o: JSONObject?, key: String): JSONObject? = o?.opt(key) as? JSONObject

    // ── 文本扫描 ─────────────────────────────────────────────────────────

    private fun skipWs(s: String, from: Int): Int {
        var i = from
        while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        return i
    }

    /** @return 结束引号之后的下标；找不到 ⇒ -1。 */
    private fun skipString(s: String, start: Int): Int {
        var i = start + 1
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i += 2
                '"' -> return i + 1
                else -> i++
            }
        }
        return -1
    }

    /** 跳过一个值（字符串／对象／数组／标量），返回其后的下标。 */
    private fun skipValue(s: String, start: Int): Int {
        if (start >= s.length) return start
        when (s[start]) {
            '"' -> return skipString(s, start).let { if (it < 0) s.length else it }
            '{', '[' -> {
                var depth = 0
                var i = start
                while (i < s.length) {
                    when (s[i]) {
                        '"' -> {
                            val e = skipString(s, i)
                            if (e < 0) return s.length
                            i = e
                        }
                        '{', '[' -> { depth++; i++ }
                        '}', ']' -> {
                            depth--
                            i++
                            if (depth == 0) return i
                        }
                        else -> i++
                    }
                }
                return s.length
            }
            else -> {
                var i = start
                while (i < s.length && s[i] != ',' && s[i] != '}' && s[i] != ']') i++
                return i
            }
        }
    }

    /** 只在键名上做最小的反转义（`\"`、`\\`、`\/`、`\uXXXX`）。 */
    private fun unescapeKey(raw: String): String {
        if (!raw.contains('\\')) return raw
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '\\' || i + 1 >= raw.length) { sb.append(c); i++; continue }
            when (val n = raw[i + 1]) {
                '"', '\\', '/' -> { sb.append(n); i += 2 }
                'u' -> {
                    val hex = raw.substring(i + 2, minOf(i + 6, raw.length))
                    val code = hex.toIntOrNull(16)
                    if (code == null) { sb.append(c); i++ } else { sb.append(code.toChar()); i += 6 }
                }
                else -> { sb.append(n); i += 2 }
            }
        }
        return sb.toString()
    }
}
