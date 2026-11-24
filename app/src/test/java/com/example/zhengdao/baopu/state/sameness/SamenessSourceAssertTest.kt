// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
package com.example.zhengdao.baopu.state.sameness

import com.example.zhengdao.SrcRoots
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SamenessSourceAssertTest {

    private val path = "app/src/main/java/com/example/zhengdao/baopu/state/sameness/Sameness.kt"

    private fun code(): String = stripComments(SrcRoots.find(path).readText())

    @Test
    fun `源码闸_三件套与847默认值齐`() {
        val text = code()
        listOf(
            "fun diffItems(",
            "fun samenessIndex(",
            "fun sourceNote(",
            "SAMENESS_SCORE_TABLE",
            "DiffKind.ABSENT",
            "UNSEEN_INDEX_THRESHOLD = 40",
            "UNSEEN_MIN_DIFFERENCES = 2",
            "index < UNSEEN_INDEX_THRESHOLD",
            "distinguishableCount >= UNSEEN_MIN_DIFFERENCES",
            "fun unseenCondition(a: CastingCard, b: CastingCard)",
            "fun observe(a: CastingCard, b: CastingCard)",
        ).forEach { token -> assertTrue("必须保留 $token", text.contains(token)) }
        listOf("indexThreshold: Int", "differenceThreshold: Int").forEach { token ->
            assertFalse("847 条件不许开放自定义阈值 $token", text.contains(token))
        }
    }

    @Test
    fun `源码闸_只提示不裁决`() {
        val text = code()
        listOf("合格", "不合格", "PASS", "FAIL", "verdict", "qualified", "unqualified").forEach { token ->
            assertFalse("不许返回裁决词 $token", text.contains(token))
        }
    }

    @Test
    fun `源码闸_纯规则禁模型统计与连续公式`() {
        val text = code()
        listOf(
            "模型", "统计", "average", "cosine", "ngram", "n-gram", "neural", "tensorflow",
            "pytorch", "sklearn", "probability", "variance", "stdDev", "Math.", "pow(", "sqrt(",
            "exp(", "log(", " / ", " * ",
        ).forEach { token -> assertFalse("不许用模型或统计公式 $token", text.contains(token)) }
    }

    @Test
    fun `源码闸_禁图像人脸与渲染`() {
        val text = code().lowercase()
        listOf("image", "bitmap", "face-detect", "facedetect", "opencv", "camera", "photo", "人脸", "图像", "render", "canvas", "compose")
            .forEach { token -> assertFalse("不许出现图像或渲染依赖 $token", text.contains(token)) }
    }

    @Test
    fun `源码闸_离线无IO无随机无时钟`() {
        val text = code()
        listOf(
            "java.io", "java.net", "java.nio", "readText", "writeText", "File(", "Files.",
            "URL(", "Http", "Socket", "Random", "currentTimeMillis", "nanoTime", "System.current",
            "getenv", "Thread.sleep",
        ).forEach { token -> assertFalse("纯函数层不许出现 $token", text.contains(token)) }
    }

    @Test
    fun `源码闸_零装配零落库`() {
        val text = code()
        listOf("RunEngine", "Wiring", "Assembler", "ZhengdaoApp", "attach(", "Context", "RiskMatrix", "insertRunLog", "BaopuSql")
            .forEach { token -> assertFalse("本单不许装配或落库 $token", text.contains(token)) }
    }

    @Test
    fun `源码闸_缺省语义与817来源引用在位`() {
        val text = code()
        listOf(
            "CastingRefs.CARD_FIELDS",
            "CastingRefs.field",
            "RefSource.NONE",
            "ref.words()",
            "values.distinct().sorted()",
            "kind = if (partial)",
            "distinguishable = !partial",
        ).forEach { token -> assertTrue("必须保留 $token", text.contains(token)) }
    }

    @Test
    fun `闸自检_注释不供电字符串供电`() {
        val commented = stripComments("// 合格 模型 image\nval ok = 1")
        assertFalse(commented.contains("合格"))
        assertFalse(commented.contains("模型"))
        assertFalse(commented.contains("image"))
        assertTrue(stripComments("val x = \"合格\"").contains("合格"))
        assertTrue(stripComments("val x = Bitmap.createBitmap(1, 1)").lowercase().contains("bitmap"))
    }

    @Test
    fun `闸自检_模型与连续公式注入必被咬`() {
        val text = stripComments("val score = average + cosine / 2")
        listOf("average", "cosine", " / ").forEach { token -> assertTrue(token, text.contains(token)) }
    }

    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val two = if (i + 1 < src.length) src.substring(i, i + 2) else ""
            when {
                two == "//" -> i = skipLine(src, i, out)
                two == "/*" -> i = skipBlock(src, i, out)
                src[i] == '"' -> i = copyString(src, i, out)
                else -> { out.append(src[i]); i++ }
            }
        }
        return out.toString()
    }

    private fun skipLine(src: String, start: Int, out: StringBuilder): Int {
        var i = start + 2
        out.append("  ")
        while (i < src.length && src[i] != '\n') { out.append(' '); i++ }
        return i
    }

    private fun skipBlock(src: String, start: Int, out: StringBuilder): Int {
        var i = start + 2
        var depth = 1
        out.append("  ")
        while (i < src.length && depth > 0) {
            val two = if (i + 1 < src.length) src.substring(i, i + 2) else ""
            if (two == "/*") { depth++; out.append("  "); i += 2 }
            else if (two == "*/") { depth--; out.append("  "); i += 2 }
            else { if (src[i] != '\n') out.append(' ') else out.append('\n'); i++ }
        }
        return i
    }

    private fun copyString(src: String, start: Int, out: StringBuilder): Int {
        var i = start
        out.append(src[i]); i++
        while (i < src.length) {
            out.append(src[i])
            if (src[i] == '\\' && i + 1 < src.length) { out.append(src[i + 1]); i += 2; continue }
            if (src[i] == '"') { i++; break }
            i++
        }
        return i
    }
}
