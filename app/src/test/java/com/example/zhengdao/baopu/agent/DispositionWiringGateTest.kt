// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百二十七批 ①的**接线门禁**（房型照 384-A 的 ValueGateWiringGateTest：
// 管线在 JVM 起不全，结构性事实用源码门禁钉）。
//
// 钉三件事，缺一条就是"接了但没真接"：
//   ① 硬拒分支真调 `DispositionWiring.onMatrixReject(...)`（不是只 import）；
//   ② 调用点**紧跟 noteDeviation、在 return Rejected 之前**（372 名册钉的缝点位置）；
//   ③ 管线原有的拒绝行为没被顶掉（偏差账仍记 GATE_RISK_MATRIX、仍走 RiskTell 拒话返回）。
// 第五百五十七批去脆化：不再整句钉字面 return／`if (verdict.rejected)`——分支用
// 正则锚（局部名随便改）＋括号配对切块（GateBodies），返回只钉调用形态；
// 偏差账钉「常量在实参段里」，named/positional、参数序随便换。保护的不变量没变：
// 处置门物理接在硬拒分支的缝点上，顺序与拒绝行为不动。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.GateBodies
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DispositionWiringGateTest {

    private fun code(rel: String): String =
        File(rel).let { if (it.isFile) it else File("app/$rel") }.readText()
            .replace("\r\n", "\n")
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun pipeline(): String = code("src/main/java/com/example/zhengdao/baopu/agent/GuardPipeline.kt")

    /** 硬拒分支体：锚不写局部名（verdict 改名照样切），括号配对取块、不吃块外。 */
    private fun rejectBranch(src: String): String {
        val body = GateBodies.blockBody(src, Regex("""if\s*\(\s*\w+\.rejected\s*\)"""))
        assertTrue("缝点（rejected 分支）不在了？", body != null)
        return body!!
    }

    private val rejectReturn = Regex("""return\s+Prepared\.Settled\(\s*Result\.Rejected\(""")

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
        val branch = rejectBranch(pipeline())
        val note = branch.indexOf("noteDeviation(")
        val call = branch.indexOf("DispositionWiring.onMatrixReject(")
        val ret = rejectReturn.find(branch)?.range?.first ?: -1
        assertTrue("noteDeviation 不在硬拒分支里", note >= 0)
        assertTrue("onMatrixReject 不在硬拒分支里", call >= 0)
        assertTrue("拒绝返回不在硬拒分支里", ret >= 0)
        assertTrue(
            "顺序不对：必须是 noteDeviation → onMatrixReject → return（372 钉的位置）",
            note < call && call < ret,
        )
    }

    @Test
    fun `G3_管线原有拒绝行为没被顶掉`() {
        val branch = rejectBranch(pipeline())
        val noteArgs = GateBodies.callArgs(branch, "noteDeviation(")
        assertEquals("硬拒分支里 noteDeviation 应恰一处", 1, noteArgs.size)
        assertTrue("偏差账没记 GATE_RISK_MATRIX", noteArgs[0].contains("GATE_RISK_MATRIX"))
        assertTrue("拒绝返回形态被顶掉", rejectReturn.containsMatchIn(branch))
        assertTrue("拒话不再出自 RiskTell.rejectText", branch.contains("RiskTell.rejectText("))
    }
}
