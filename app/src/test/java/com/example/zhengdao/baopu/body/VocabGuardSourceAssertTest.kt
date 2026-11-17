// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百六十九批 · vocab_guard 源码断言（派工单判据「不调模型（源码断言）」的机器钉法）。
// 口径：剥掉注释后扫代码——注释里说"不做什么"不算证据，代码里"没有"才算。
// 禁词＝模型/网络/时钟/随机/写盘口；只读两份源码文本，零写盘。
package com.example.zhengdao.baopu.body

import com.example.zhengdao.SrcRoots
import org.junit.Assert.assertTrue
import org.junit.Test

class VocabGuardSourceAssertTest {

    private val mainFiles = listOf(
        "app/src/main/java/com/example/zhengdao/baopu/body/AnatomyZones.kt",
        "app/src/main/java/com/example/zhengdao/baopu/body/VocabGuard.kt",
    )

    /** 禁词：模型/网络＋时钟/随机（确定性）＋写盘口（只报不改写、零副作用）。 */
    private val forbidden = listOf(
        "Provider", "Http", "http", "URL", "OkHttp", "Retrofit", "java.net",
        "Llm", "llm", "Prompt", "prompt", "apiKey", "completion", "ChatClient",
        "Random", "random", "nanoTime", "currentTimeMillis", "Instant", "LocalDate", "Thread",
        "writeText", "FileWriter", "OutputStream", "PrintWriter", "mkdir", "delete",
    )

    @Test
    fun `剥注释后不含模型网络时钟随机写盘禁词`() {
        val code = mainFiles.joinToString("\n") { codeOf(it) }
        for (token in forbidden) {
            assertTrue("vocab_guard 源码（剥注释后）不得出现 $token", token !in code)
        }
    }

    @Test
    fun `注入网络调用必被咬住_闸有牙自检`() {
        val injected = source(mainFiles[1]) + "\nval probe = okhttp3.OkHttpClient()\n"
        val code = stripComments(injected)
        assertTrue("注入 OkHttp 后闸必须咬中", forbidden.any { it in code })
    }

    private fun source(rel: String): String = SrcRoots.find(rel).readText()

    private fun codeOf(rel: String): String = stripComments(source(rel))

    /** 行注释/块注释整段换空格（换行保留）；字符串原样保留——禁词藏字符串里也算数。 */
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
            when {
                two == "/*" -> { depth++; out.append("  "); i += 2 }
                two == "*/" -> { depth--; out.append("  "); i += 2 }
                else -> { out.append(if (src[i] == '\n') '\n' else ' '); i++ }
            }
        }
        return i
    }

    private fun copyString(src: String, start: Int, out: StringBuilder): Int {
        var i = start + 1
        out.append('"')
        while (i < src.length) {
            val c = src[i]
            out.append(c)
            if (c == '\\' && i + 1 < src.length) { out.append(src[i + 1]); i += 2 }
            else if (c == '"') return i + 1
            else i++
        }
        return i
    }
}