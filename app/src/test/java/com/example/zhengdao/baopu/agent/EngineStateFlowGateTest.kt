// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百八十六批（v2.2.5 P0）：引擎状态**不许**再用 Compose State。
//
// 事故：v2.2.4 正式版冷启闪退（真机 3/3），异常是
// `IllegalStateException: Reading a state that was created after the snapshot was taken`
// —— 引擎的 `mutableStateOf(RunState)` 在**组合期/子组合**（Scaffold 的 SubcomposeLayout
// 在 measure 里起组合）被构造时，会绑定一个比主组合更晚的快照 id，主组合读它就抛。
// 修法：引擎状态改 `StateFlow`（不携带快照 id），UI 侧 `collectAsState()`。
// 本门禁钉住这件事，防有人"顺手改回 mutableStateOf"。
package com.example.zhengdao.baopu.agent

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineStateFlowGateTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.exists()) it else File("app/$rel") }

    private fun codeOnly(src: String): String =
        src.split("\n").joinToString("\n") { it.substringBefore("//") }
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")

    private val engine = "src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt"
    private val screen = "src/main/java/com/example/zhengdao/ui/baopu/BaopuScreen.kt"
    private val controller = "src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt"

    @Test
    fun `引擎状态是 StateFlow_不是 Compose State`() {
        val code = codeOnly(repoFile(engine).readText())
        assertTrue(
            "引擎状态必须是 MutableStateFlow（v2.2.5 冷启闪退的修法）",
            code.contains("MutableStateFlow(RunState.IDLE)"),
        )
        assertFalse(
            "引擎状态不许改回 mutableStateOf(RunState…)——会再次绑快照 id，冷启闪退",
            code.contains("mutableStateOf(RunState"),
        )
    }

    @Test
    fun `UI 侧用 collectAsState 收引擎状态`() {
        val screenCode = codeOnly(repoFile(screen).readText())
        assertTrue(
            "BaopuScreen 必须 collectAsState 收引擎状态，不许直接读 Compose State 的 .value",
            screenCode.contains("engineState.collectAsState()"),
        )
        val ctrl = codeOnly(repoFile(controller).readText())
        assertTrue(
            "controller 暴露的 engineState 必须是 StateFlow",
            ctrl.contains("StateFlow<RunState> get() = engine.state"),
        )
    }
}
