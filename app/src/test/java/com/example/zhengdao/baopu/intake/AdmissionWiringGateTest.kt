// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百二十七批 ②③的**接线门禁**（房型照 384-A 的 ValueGateWiringGateTest）。
//
// 钉四件事，缺一条就是"接了但没真接"：
//   ① 管线真调 `AdmissionWiring.onExternalToolCall(...)`（不是只 import）；
//   ② 调用点锁在 **MCP 组条件内**——App 自身内部调用（非 MCP）不进准入（判据②）；
//   ③ **画像侧不反向依赖**：CapabilityProfile 不 import law／不引用 AdmissionGate／
//      AdmissionFlow（依赖方向只能是 准入 → 画像，派工单判据③的箭头不许倒）；
//   ④ 装配层真挂载（BaopuRuntime 冷启动 install——没挂载的接线是哑的）。
package com.example.zhengdao.baopu.intake

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdmissionWiringGateTest {

    private fun code(rel: String): String =
        File(rel).let { if (it.isFile) it else File("app/$rel") }.readText()
            .replace("\r\n", "\n")
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun pipeline(): String = code("src/main/java/com/example/zhengdao/baopu/agent/GuardPipeline.kt")

    private fun profile(): String = code("src/main/java/com/example/zhengdao/baopu/policy/CapabilityProfile.kt")

    @Test
    fun `G1_管线真调准入接线口`() {
        assertTrue(
            "管线没调 AdmissionWiring.onExternalToolCall（门还是没人敲门）",
            pipeline().contains("AdmissionWiring.onExternalToolCall("),
        )
    }

    @Test
    fun `G2_调用点锁在MCP组条件内_App内部调用不进准入`() {
        val src = pipeline()
        val joined = Regex(
            """if \(spec\.group == ToolGroup\.MCP\) \{\s*AdmissionWiring\.onExternalToolCall\(""",
        )
        assertTrue(
            "准入调用没锁在 MCP 组条件里（App 内部调用会被卷进准入＝判据②被破）",
            joined.containsMatchIn(src),
        )
    }

    @Test
    fun `G3_画像侧不反向依赖law与准入`() {
        val src = profile()
        assertFalse("画像 import 了 law（依赖方向倒了）", src.contains("baopu.law"))
        assertFalse("画像引用了准入门（依赖方向倒了）", src.contains("AdmissionGate"))
        assertFalse("画像引用了准入流程（依赖方向倒了）", src.contains("AdmissionFlow"))
    }

    @Test
    fun `G4_装配层真挂载_冷启动install`() {
        val runtime = code("src/main/java/com/example/zhengdao/baopu/BaopuRuntime.kt")
        assertTrue(
            "BaopuRuntime 没挂 AdmissionWiring（没挂载的接线是哑的）",
            runtime.contains("AdmissionWiring.install(File(appCtx.filesDir, BaopuKernel.Data.DIR_GLOBAL_MEMORY))"),
        )
    }
}
