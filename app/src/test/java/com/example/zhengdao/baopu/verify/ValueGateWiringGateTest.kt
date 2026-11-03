// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百八十四批 · 上半 ①的**接线门禁**（房型照 378 的 RunContractWiringGateTest：
// 引擎在 JVM 起不来，结构性事实用源码门禁钉）。
//
// 钉三件事，缺一条就是"接了但没真接"：
//   ① 收尾链真调 `ValueGateWiring.findingsOf(...)`（不是只 import）；
//   ② 它和 340 的 `ArtifactVerifier.check(...)` 是**同一份 findings**（FAIL 才进得了
//      「按清单修一次」那条既有分支——派工单纪律④：FAIL 不许私自降级）；
//   ③ 340 那几行没被顶掉（收尾闸本体逐字还在，本批只加不减）。
package com.example.zhengdao.baopu.verify

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ValueGateWiringGateTest {

    private fun code(rel: String): String =
        File(rel).let { if (it.isFile) it else File("app/$rel") }.readText()
            .replace("\r\n", "\n")
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun engine(): String = code("src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt")

    @Test
    fun `G1_收尾链真调值级对账`() {
        val src = engine()
        assertTrue(
            "收尾链没调 ValueCheck（ValueGateWiring.findingsOf 不在引擎里）",
            src.contains("ValueGateWiring.findingsOf(outcome.text, spillRoot(), verifyTouched)"),
        )
    }

    @Test
    fun `G2_与收尾闸同一份findings_FAIL才走得了按清单修一次`() {
        val src = engine()
        // 同一句表达式：ArtifactVerifier.check(...) ＋ ValueGateWiring.findingsOf(...)
        val joined = Regex(
            """ArtifactVerifier\.check\(spillRoot\(\), verifyTouched\)\s*\+\s*ValueGateWiring\.findingsOf\(""",
        )
        assertTrue(
            "值级对账的 findings 没并进收尾闸那份——FAIL 会被丢掉（等于私自降级）",
            joined.containsMatchIn(src),
        )
        assertTrue(
            "「按清单修一次」分支没吃到合并后的 findings",
            src.contains("if (findings.isNotEmpty() && verifyRepairs < RunRules.MAX_VERIFY_REPAIRS)"),
        )
        assertTrue(
            "修复提示没从合并后的 findings 渲染",
            src.contains("val report = ArtifactVerifier.render(findings)"),
        )
    }

    @Test
    fun `G3_收尾闸本体没被顶掉_340的check仍在`() {
        val src = engine()
        assertTrue("340 的收尾闸被删了？", src.contains("ArtifactVerifier.check(spillRoot(), verifyTouched)"))
        assertTrue("收尾闸的「空产物 ⇒ 空 findings」分支被改了", src.contains("val findings = if (verifyTouched.isEmpty())"))
    }
}
