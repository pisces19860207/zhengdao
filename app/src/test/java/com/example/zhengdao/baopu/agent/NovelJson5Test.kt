// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百零九批判据①②的解析器侧：JSON5 子集解析 ＋ 坏文件报「第 N 行」（不许静默空）。
// 纯字符串进、语法树出，不碰盘 ⇒ 无临时目录、无平台分流。
package com.example.zhengdao.baopu.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelJson5Test {

    private fun ok(text: String): NovelJson5.J5Node {
        val r = NovelJson5.parse(text)
        assertNull("不该报错：${r.error?.text}", r.error)
        return r.node!!
    }

    private fun err(text: String): NovelJson5.Error {
        val r = NovelJson5.parse(text)
        assertNotNull("必须报错（不许静默空）：$text", r.error)
        return r.error!!
    }

    // ── 基本形态 ────────────────────────────────────────────────────────

    @Test
    fun `对象_数组_字符串_数_布尔_null`() {
        val root = NovelJson5.obj(ok("""{"a":1,"b":[true,false,null],"c":"x"}"""))!!
        assertEquals(1.0, (root["a"] as NovelJson5.J5Num).value, 0.0)
        val arr = NovelJson5.arr(root["b"])!!
        assertEquals(3, arr.items.size)
        assertTrue((arr.items[0] as NovelJson5.J5Bool).value)
        assertTrue(arr.items[2] is NovelJson5.J5Null)
        assertEquals("x", NovelJson5.str(root["c"]))
    }

    @Test
    fun `JSON5特性_注释_尾逗号_无引号键_单引号_十六进制_指数`() {
        val text = """
            // 头注释
            {
              relations: [ // 行内注释
                {from:'张三', to:"李四", kind:0x10, note:'师徒',}, /* 块注释 */
              ],
              n: +1.5e2,
              m: .5,
            }
        """.trimIndent()
        val root = NovelJson5.obj(ok(text))!!
        val rel = NovelJson5.obj(NovelJson5.arr(root["relations"])!!.items[0])!!
        assertEquals("张三", NovelJson5.str(rel["from"]))
        assertEquals(16.0, (rel["kind"] as NovelJson5.J5Num).value, 0.0)
        assertEquals(150.0, (root["n"] as NovelJson5.J5Num).value, 0.0)
        assertEquals(0.5, (root["m"] as NovelJson5.J5Num).value, 0.0)
    }

    @Test
    fun `转义_换行_引号_Unicode_十六进制_行续`() {
        val root = NovelJson5.obj(ok("""{"s":"a\nb\tc\"d\\eA\x42"}"""))!!
        assertEquals("a\nb\tc\"d\\eAB", NovelJson5.str(root["s"]))
        // 行 continuation：反斜杠+换行 ⇒ 什么都不产出
        val cont = NovelJson5.obj(ok("{\"s\":\"a\\\nb\"}"))!!
        assertEquals("ab", NovelJson5.str(cont["s"]))
    }

    @Test
    fun `同名键_后者覆盖前者`() {
        val root = NovelJson5.obj(ok("""{"a":1,"a":2}"""))!!
        assertEquals(2.0, (root["a"] as NovelJson5.J5Num).value, 0.0)
    }

    // ── 判据②：坏文件报「第 N 行」 ──────────────────────────────────────

    @Test
    fun `缺逗号_报到具体行`() {
        val e = err("{\n  \"a\": 1\n  \"b\": 2\n}")
        assertEquals(3, e.line)
        assertTrue(e.text.startsWith("第 3 行："))
    }

    @Test
    fun `字符串未闭合_报起始行`() {
        val e = err("{\n  \"a\": \"没闭合\n}")
        assertEquals(2, e.line)
    }

    @Test
    fun `字符串直接换行_报错`() {
        val e = err("{\"a\": \"x\ny\"}")
        assertEquals(1, e.line)
        assertTrue(e.message.contains("换行"))
    }

    @Test
    fun `块注释未闭合_报起始行`() {
        val e = err("{\n  /* 没闭合\n  \"a\": 1\n}")
        assertEquals(2, e.line)
    }

    @Test
    fun `空文件_也是错_不许静默空`() {
        val e = err("   \n  ")
        assertEquals(2, e.line)
    }

    @Test
    fun `多余内容_报错`() {
        val e = err("{} {}")
        assertTrue(e.message.contains("多余"))
    }

    @Test
    fun `坏转义_坏十六进制_缺冒号_都报行`() {
        assertEquals(1, err("{\"a\":\"\\q\"}").line)
        assertEquals(1, err("{\"a\":\"\\u12G4\"}").line)
        assertEquals(2, err("{\n  \"a\" 1\n}").line)
        assertEquals(1, err("[1, 0x]").line)
        assertEquals(1, err("[1e]").line)
    }

    // ── 偏移（写工具文本级追加的依据） ──────────────────────────────────

    @Test
    fun `节点偏移_指回原文`() {
        val text = "{\n  \"events\": [\n    {\"name\":\"出生\"}\n  ]\n}"
        val root = NovelJson5.obj(ok(text))!!
        val arr = NovelJson5.arr(root["events"])!!
        assertEquals('[', text[arr.start])
        assertEquals(']', text[arr.end - 1])
        val item = arr.items[0]
        assertEquals("{\"name\":\"出生\"}", text.substring(item.start, item.end))
        assertEquals(3, item.line)
    }

    @Test
    fun `quote_中文原样_控制字符转义`() {
        assertEquals("\"出生\"", NovelJson5.quote("出生"))
        assertEquals("\"a\\\"b\\\\c\"", NovelJson5.quote("a\"b\\c"))
        assertEquals("\"a\\nb\"", NovelJson5.quote("a\nb"))
    }
}