// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百六十三批 · 偏差账的**源级接线门禁**（判据④"接口存在且被调用"的本体；
// 行为判据见 DeviationLedgerTest）。照 EmergencyWiringGateTest / LawSourceWiringGateTest 的口径：
// 只认代码行、注释不算、只扫生产——把收工读数那一句从 settle() 里悄悄拆掉，
// 行为测试照样绿（没偏差账时读数本来就是 0 条），只有盯着生产源码才看得见。
//
// 钉三件事：
//   1. RunEngine 里 `reportDeviationCount()` 的**调用**在代码行上（剥了注释还在）；
//   2. 它在 `reflectOnSettled()` 之前——那边会把 roundRunId 摘空，顺序反了读数恒空跑；
//   3. 全仓只有一个调用点（不许散播：收工读一次，别处要读数走自己的口）。
package com.example.zhengdao.baopu.inspect

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviationLedgerWiringGateTest {

    /** Gradle 单测的工作目录是 `app/`，仓库根取法与 UiD4DrawerAboveTopBarTest 同款。 */
    private fun repoFile(rel: String): File = File(rel).let { if (it.exists()) it else File("app/$rel") }

    private val runEngine: String by lazy {
        val f = repoFile("src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt")
        require(f.isFile) { "RunEngine.kt 没找到：${f.absolutePath}" }
        codeOnly(f.readText())
    }

    /** 剥注释（行注释＋块注释，KDoc 属块注释）；剥完的才是"代码行"。 */
    private fun codeOnly(src: String): String {
        val out = StringBuilder()
        var i = 0
        var inBlock = false
        var inString = false
        while (i < src.length) {
            val two = if (i + 1 < src.length) src.substring(i, i + 2) else ""
            if (inBlock) {
                if (two == "*/") { inBlock = false; i += 2 } else i += 1
                continue
            }
            if (inString) {
                when (src[i]) {
                    '\\' -> i += 2
                    '"' -> { inString = false; i += 1 }
                    else -> i += 1
                }
                continue
            }
            when (two) {
                "/*" -> { inBlock = true; i += 2 }
                "//" -> { while (i < src.length && src[i] != '\n') i += 1 }
                "\"" -> { inString = true; out.append(src[i]); i += 1 }
                else -> { out.append(src[i]); i += 1 }
            }
        }
        return out.toString()
    }

    @Test
    fun `收工读数调用在代码行上`() {
        assertTrue(
            "settle() 里的 reportDeviationCount() 调用被拆了（判据④失效）",
            runEngine.contains("        reportDeviationCount()"),
        )
    }

    @Test
    fun `读数在反思之前_摘空runId前`() {
        val call = runEngine.indexOf("        reportDeviationCount()")
        val reflect = runEngine.indexOf("reflectOnSettled()")
        assertTrue("调用点没找到", call >= 0)
        assertTrue("reflectOnSettled 没找到", reflect >= 0)
        assertTrue(
            "reportDeviationCount 必须在 reflectOnSettled 之前（那边会把 roundRunId 摘空）",
            call < reflect,
        )
    }

    @Test
    fun `全仓只有一个调用点`() {
        // 定义一次（private fun）＋ 调用一次＝代码里恰好两处
        val hits = runEngine.split("\n").count { it.contains("reportDeviationCount") }
        assertEquals("RunEngine 里应恰好定义＋调用各一次", 2, hits)
    }
}
