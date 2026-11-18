// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第五百二十批 · 快探**源码断言**（判据①⑤＋红线，与 675/694 的 BioSourceAssertTest 同思路）。
// 断言口径：剥掉注释后扫代码——注释里说"不做什么"不算证据，代码里"没有"才算。
// 只读 app/src/main 下 amygdala 包五份源码文本（SrcRoots 定位），零写盘。
package com.example.zhengdao.baopu.amygdala

import com.example.zhengdao.SrcRoots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmygdalaSourceAssertTest {

    private val files = listOf(
        "app/src/main/java/com/example/zhengdao/baopu/amygdala/ProbeAccount.kt" to 110,
        "app/src/main/java/com/example/zhengdao/baopu/amygdala/AmygdalaState.kt" to 180,
        "app/src/main/java/com/example/zhengdao/baopu/amygdala/BioAnalogGate.kt" to 70,
        "app/src/main/java/com/example/zhengdao/baopu/amygdala/AmygdalaProbe.kt" to 250,
        "app/src/main/java/com/example/zhengdao/baopu/amygdala/AmygdalaProbeGate.kt" to 90,
    )
    private val probeFile = files[3].first
    private val stateFile = files[1].first
    private val accountFile = files[0].first

    private val pkgFiles get() = files.map { it.first }

    /** 禁词：判定值（判据⑤）＋授权档（判据⑤）＋权威账（红线）＋模型/网络（判据①）＋词表（红线：不许自造第二套判断）。 */
    private val forbidden = listOf(
        "RiskDecision", "PROCEED", "Proceed", "proceed",
        "AskTier", "AskGovernance",
        "BaopuDb", "run_log", "execSQL", "Ledger",
        "Provider", "Http", "http", "URL", "OkHttp", "Retrofit", "java.net",
        "DangerWords", "JSONObject", "JSONArray",
    )

    // ── 判据⑤＋红线：剥注释后，判定/授权/账/模型/词表禁词一个都不许出现 ──

    @Test
    fun noJudgementNoLedgerNoModelInCode() {
        val code = pkgFiles.joinToString("\n") { codeOf(it) }
        for (token in forbidden) {
            assertTrue("快探源码（剥注释后）不得出现 $token", token !in code)
        }
        // REJECT 的唯一合法出现是轨迹状态 TraceStatus.REJECTED（输入流枚举）——去掉它之后不得再有 REJECT。
        val withoutStatus = code.replace("REJECTED", "")
        assertTrue("快探不得产出/拒绝任何判定值（REJECTED 状态枚举除外）", "REJECT" !in withoutStatus)
    }

    // ── 红线：只消费 675 的读数——消费证据在场，自造判断的证据缺席 ──

    @Test
    fun probeConsumes675ReadingAndNothingElse() {
        val code = codeOf(probeFile)
        assertTrue("快探必须调用 675 的 Amygdala.salience（有状态半消费无状态读数）", "Amygdala.salience" in code)
        assertTrue("salience 公式的文本分量必须吃 675 的 threat 档", ".threat" in code)
        assertTrue("salience 公式必须吃 675 的 novelty 档", ".novelty" in code)
        assertTrue("喂给 675 的输入必须经 BioEvent", "BioEvent(" in code)
        assertFalse("快探不得触碰词表（词表是 675 的地盘）", "DangerWords" in code)
    }

    /** 输入流＝474 轨迹的采集（TraceRow 直接进 ProbeInput），不另造事件总线。 */
    @Test
    fun inputStreamIs474TraceRow() {
        val code = codeOf(probeFile)
        assertTrue("ProbeInput 必须直接装 474 的 TraceRow", "TraceRow" in code)
        assertTrue("事故判据必须读轨迹状态", "TraceStatus" in code)
    }

    // ── 登记闸的腰带：快探主件恰有一处登记标记，件名＝杏仁核 ──

    @Test
    fun probeCarriesExactlyOneRegisteredMarker() {
        val at = "@" + "BioAnalog"
        val needle = "$at(\"杏仁核\")"
        assertEquals("AmygdalaProbe.kt 应恰有一处登记标记", 1, codeOf(probeFile).split(needle).size - 1)
    }

    // ── 行数预算（520 自定：状态半各件上限，超了即红）──

    @Test
    fun eachFileStaysWithinLineBudget() {
        for ((path, budget) in files) {
            val lines = SrcRoots.find(path).readLines().size
            assertTrue("$path 共 $lines 行，超 $budget 行预算", lines <= budget)
        }
    }

    // ── 确定性的物理钉：状态机零时钟零随机（时钟一律显式 nowMs 入参）──

    @Test
    fun stateHasNoClockAndNoRandom() {
        val code = codeOf(stateFile)
        assertFalse("状态机不得自己取钟（判据③时间夹具靠显式 nowMs）", "System." in code)
        assertFalse("状态机不得有随机", "Random" in code)
    }

    /** 探针账不许有 IO（本批内存账；落库/落文件归接线单，源码上钉死）。 */
    @Test
    fun accountHasNoIo() {
        val code = codeOf(accountFile)
        assertFalse("账不得有文件 IO", "java.io" in code)
        assertFalse("账不得开文件", "File(" in code)
    }

    // ── 剥注释（行/块注释换空格；字符串原样保留）──

    private fun codeOf(rel: String): String = stripComments(SrcRoots.find(rel).readText())

    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val two = if (i + 1 < src.length) src.substring(i, i + 2) else ""
            when {
                two == "//" -> {
                    while (i < src.length && src[i] != '\n') {
                        out.append(' ')
                        i++
                    }
                }
                two == "/*" -> {
                    var depth = 1
                    out.append("  ")
                    i += 2
                    while (i < src.length && depth > 0) {
                        val pair = if (i + 1 < src.length) src.substring(i, i + 2) else ""
                        when {
                            pair == "/*" -> {
                                depth++
                                out.append("  ")
                                i += 2
                            }
                            pair == "*/" -> {
                                depth--
                                out.append("  ")
                                i += 2
                            }
                            else -> {
                                out.append(if (src[i] == '\n') '\n' else ' ')
                                i++
                            }
                        }
                    }
                }
                else -> {
                    out.append(src[i])
                    i++
                }
            }
        }
        return out.toString()
    }
}
