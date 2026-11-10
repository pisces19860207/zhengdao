// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百八十六批 · 第二修（v2.2.6）：**引擎必须在进组合之前建好**。
//
// 事故：v2.2.4/v2.2.5 冷启闪退。retrace 定位到 `BaopuChatController.getRoundArtifacts` →
// `RunEngine.roundArtifacts.value`；根因是引擎里十几个 `mutableStateOf` 在"创建那一刻"绑快照 id，
// 而 Scaffold 的 SubcomposeLayout 会在 measure 阶段起子组合——引擎若在那层被首次取用，
// 主组合读它就抛 `Reading a state that was created after the snapshot was taken`。
// 修法：Application.onCreate 与 MainActivity.onCreate（setContent 之前）各预建一次。
// 本门禁钉住"预建"这件事，防有人删掉这两行（删了不一定立刻红，但线上会随机闪退）。
package com.example.zhengdao.baopu.agent

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class EnginePrebuiltGateTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.exists()) it else File("app/$rel") }

    private fun codeOnly(src: String): String =
        src.split("\n").joinToString("\n") { it.substringBefore("//") }
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")

    private val base = "src/main/java/com/example/zhengdao/"

    @Test
    fun `Application 里预建引擎_且在主组合之前`() {
        val app = codeOnly(repoFile(base + "ZhengdaoApp.kt").readText())
        assertTrue(
            "ZhengdaoApp.onCreate 必须预建引擎（BaopuRuntime.engine(this)）——"
                + "否则引擎可能落在 Scaffold 的子组合里，冷启随机闪退",
            app.contains("BaopuRuntime.engine(this)"),
        )
    }

    @Test
    fun `MainActivity 在 setContent 之前预建引擎`() {
        val main = codeOnly(repoFile(base + "MainActivity.kt").readText())
        val engine = main.indexOf("BaopuRuntime.engine(this)")
        val setContent = main.indexOf("setContent {")
        assertTrue("MainActivity 里找不到引擎预建", engine >= 0)
        assertTrue("MainActivity 里找不到 setContent", setContent >= 0)
        assertTrue(
            "引擎预建必须在 setContent **之前**（当前 engine=$engine / setContent=$setContent）",
            engine in 0 until setContent,
        )
    }
}
