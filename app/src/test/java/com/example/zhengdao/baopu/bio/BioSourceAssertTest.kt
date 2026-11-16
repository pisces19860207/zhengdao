// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百七十五批 · 六体读数**源码断言**（派工单判据②③⑤的机器钉法）。
// 断言口径：剥掉注释后扫代码——注释里说"不做什么"不算证据，代码里"没有"才算。
// 本文件只读 app/src/main 下 bio 包六份源码文本（SrcRoots 定位），零写盘。
package com.example.zhengdao.baopu.bio

import com.example.zhengdao.SrcRoots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BioSourceAssertTest {

    private val mainFiles = listOf(
        "app/src/main/java/com/example/zhengdao/baopu/bio/BioAnalog.kt",
        "app/src/main/java/com/example/zhengdao/baopu/bio/BioEvent.kt",
        "app/src/main/java/com/example/zhengdao/baopu/bio/DangerWords.kt",
        "app/src/main/java/com/example/zhengdao/baopu/bio/Amygdala.kt",
        "app/src/main/java/com/example/zhengdao/baopu/bio/Insula.kt",
        "app/src/main/java/com/example/zhengdao/baopu/bio/Thalamus.kt",
    )

    private val organFiles = listOf(
        "app/src/main/java/com/example/zhengdao/baopu/bio/Amygdala.kt",
        "app/src/main/java/com/example/zhengdao/baopu/bio/Insula.kt",
        "app/src/main/java/com/example/zhengdao/baopu/bio/Thalamus.kt",
    )

    /** 六件闭集（626 定稿）：标记件名必须落在这里，冒出第七件就是红。 */
    private val sixOrgans = setOf("杏仁核", "海马", "前额叶", "纹状体", "岛叶", "丘脑")

    /** 禁词：判定类型（判据②）＋权威账（判据③）＋模型/网络调用（判据⑤）。 */
    private val forbidden = listOf(
        "RiskDecision", "PROCEED", "REJECT", "Proceed", "Reject", "proceed", "reject",
        "BaopuDb", "run_log", "execSQL", "Ledger",
        "Provider", "Http", "http", "URL", "OkHttp", "Retrofit", "java.net",
    )

    // ── 判据②③⑤：剥注释后的六份源码里，禁词一个都不许出现 ──

    @Test
    fun noJudgementNoLedgerNoModelInCode() {
        val code = mainFiles.joinToString("\n") { codeOf(it) }
        for (token in forbidden) {
            assertTrue("六体读数源码（剥注释后）不得出现 $token", token !in code)
        }
    }

    // ── 判据①（belt＋护腕，CI 闸之外再钉一道）：三件各恰有一处登记标记，件名在闭集 ──

    @Test
    fun eachOrganCarriesExactlyOneRegisteredMarker() {
        val at = "@" + "BioAnalog"
        val marks = mapOf(
            "app/src/main/java/com/example/zhengdao/baopu/bio/Amygdala.kt" to "杏仁核",
            "app/src/main/java/com/example/zhengdao/baopu/bio/Insula.kt" to "岛叶",
            "app/src/main/java/com/example/zhengdao/baopu/bio/Thalamus.kt" to "丘脑",
        )
        for ((rel, organ) in marks) {
            assertTrue("$organ 必须在六件闭集", organ in sixOrgans)
            val needle = "$at(\"$organ\")"
            assertEquals("$rel 应恰有一处登记标记", 1, source(rel).split(needle).size - 1)
        }
    }

    // ── 行数预算（派工单：每件 ≤150 行）──

    @Test
    fun eachOrganStaysWithinLineBudget() {
        for (rel in organFiles) {
            val lines = SrcRoots.find(rel).readLines().size
            assertTrue("$rel 共 $lines 行，超 150 行预算", lines <= 150)
        }
    }

    // ── 词表卫生：默认表三组都非空且无空白词（分档的原料不能烂）──

    @Test
    fun defaultWordListIsSane() {
        val words = DangerWords.DEFAULT
        assertTrue(words.danger.isNotEmpty() && words.danger.all { it.isNotBlank() })
        assertTrue(words.negation.isNotEmpty() && words.noveltyMarks.isNotEmpty())
    }

    // ── 剥注释（与 tools/check-bio-analog.py 同款思路的简化版）：──
    // 行注释/块注释（可嵌套）整段换空格；字符串原样保留——禁词藏在字符串里也算数。

    private fun source(rel: String): String = SrcRoots.find(rel).readText()

    private fun codeOf(rel: String): String = stripComments(source(rel))

    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            val two = if (i + 1 < src.length) src.substring(i, i + 2) else ""
            when {
                two == "//" -> i = skipLineComment(src, i, out)
                two == "/*" -> i = skipBlockComment(src, i, out)
                c == '"' || c == '\'' -> i = copyString(src, i, out)
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    /** 行注释：到换行为止全换空格（换行保留），返回换行下标。 */
    private fun skipLineComment(src: String, start: Int, out: StringBuilder): Int {
        var i = start + 2
        out.append("  ")
        while (i < src.length && src[i] != '\n') { out.append(' '); i++ }
        return i
    }

    /** 块注释（可嵌套）：整段换空格（换行保留），返回结束下标。 */
    private fun skipBlockComment(src: String, start: Int, out: StringBuilder): Int {
        var i = start + 2
        var depth = 1
        out.append("  ")
        while (i < src.length && depth > 0) {
            val two = if (i + 1 < src.length) src.substring(i, i + 2) else ""
            when {
                two == "/*" -> { depth++; out.append("  "); i += 2 }
                two == "*/" -> { depth--; out.append("  "); i += 2 }
                else -> { out.append(if (src[i] == '\n') '\n' else ' '); i++ }
            }
        }
        return i
    }

    /** 字符串原样拷贝（转义跟进），返回结束下标。 */
    private fun copyString(src: String, start: Int, out: StringBuilder): Int {
        val quote = src[start]
        var i = start + 1
        out.append(quote)
        while (i < src.length) {
            val c = src[i]
            out.append(c)
            if (c == '\\' && i + 1 < src.length) { out.append(src[i + 1]); i += 2 }
            else if (c == quote) return i + 1
            else i++
        }
        return i
    }
}
