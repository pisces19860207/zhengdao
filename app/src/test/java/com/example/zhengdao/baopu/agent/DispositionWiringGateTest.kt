// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百二十七批 ①的**接线门禁**（房型照 384-A 的 ValueGateWiringGateTest：
// 管线在 JVM 起不全，结构性事实用源码门禁钉）。
//
// 钉三件事，缺一条就是"接了但没真接"：
//   ① 硬拒分支真调 `DispositionWiring.onMatrixReject(...)`（不是只 import）；
//   ② 调用点**紧跟 noteDeviation、在 return Rejected 之前**（372 名册钉的缝点位置）；
//   ③ 管线原有的拒绝行为没被顶掉（noteDeviation 与拒绝返回逐字还在，本批只加不减）。
package com.example.zhengdao.baopu.agent

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class DispositionWiringGateTest {

    private fun code(rel: String): String =
        File(rel).let { if (it.isFile) it else File("app/$rel") }.readText()
            .replace("\r\n", "\n")
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun pipeline(): String = code("src/main/java/com/example/zhengdao/baopu/agent/GuardPipeline.kt")

    @Test
    fun `G1_硬拒分支真调处置门`() {
        val src = pipeline()
        assertTrue(
            "硬拒分支没调 DispositionGate（DispositionWiring.onMatrixReject 不在管线里）",
            src.contains("DispositionWiring.onMatrixReject("),
        )
    }

    @Test
    fun `G2_调用点紧跟noteDeviation且在拒绝返回之前`() {
        val src = pipeline()
        val branch = src.indexOf("if (verdict.rejected)")
        val note = src.indexOf("noteDeviation(")
        val call = src.indexOf("DispositionWiring.onMatrixReject(")
        val ret = src.indexOf("return Prepared.Settled(Result.Rejected(effectiveCall, RiskTell.rejectText(verdict)))")
        assertTrue("缝点（verdict.rejected 分支）不在了？", branch >= 0)
        assertTrue(
            "顺序不对：必须是 分支 → noteDeviation → onMatrixReject → return（372 钉的位置）",
            branch < note && note < call && call < ret,
        )
    }

    @Test
    fun `G3_管线原有拒绝行为没被顶掉`() {
        val src = pipeline()
        assertTrue("偏差账那行被动了？", src.contains("gate = GATE_RISK_MATRIX"))
        assertTrue(
            "拒绝返回被动了？",
            src.contains("return Prepared.Settled(Result.Rejected(effectiveCall, RiskTell.rejectText(verdict)))"),
        )
    }
}
