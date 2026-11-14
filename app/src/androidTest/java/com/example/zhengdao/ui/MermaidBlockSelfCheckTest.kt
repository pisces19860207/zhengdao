// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百三十七批 · **「不点按钮＝0 次离屏任务」**的真机自检。
//
// 判据⑤不是"看代码里没调用"就能算数的：Compose 的副作用一旦写错（比如把渲染塞进
// LaunchedEffect / produceState），界面上一模一样，但每滚过一次就悄悄起一个 WebView。
// 所以这里用真点击验两件事：
//   ① 卡片渲染出来后**只等不动**，离屏调用计数必须还是 0；
//   ② 点一次「渲染成图」⇒ 计数变成 1，并且按钮下面出现「已存到 <路径>」。
//
// 跑法同 MermaidRenderOnDeviceTest（adb install ×2 ＋ am instrument -e class …）。
package com.example.zhengdao.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.zhengdao.mermaid.MermaidFence
import com.example.zhengdao.mermaid.MermaidRenderer
import com.example.zhengdao.ui.baopu.BaopuMessageList
import com.example.zhengdao.ui.baopu.BaopuMermaidBlock
import com.example.zhengdao.ui.baopu.BaopuUiMessage
import com.example.zhengdao.ui.baopu.BaopuUiPart
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MermaidBlockSelfCheckTest {

    @get:Rule
    val compose = createComposeRule()

    private val code = "flowchart LR\n  点击 --> 出图"

    private fun diagram(): MermaidFence.Segment.Diagram =
        MermaidFence.split("```mermaid\n$code\n```").segments[0] as MermaidFence.Segment.Diagram

    @Test
    fun 不点按钮时零次离屏任务_点一次才出一张图() {
        MermaidRenderer.resetInvocationCount()

        compose.setContent {
            MaterialTheme {
                BaopuMermaidBlock(diagram(), key = "selfcheck")
            }
        }

        // ① 默认只显示代码＋按钮
        compose.onNodeWithText("渲染成图").assertExists()
        compose.waitForIdle()
        Thread.sleep(800)
        assertEquals("没点按钮就起了离屏任务——渲染被挂到副作用上了", 0, MermaidRenderer.invocationCount())

        // ② 点一次：计数变 1，并最终出现「已存到 …」
        compose.onNodeWithText("渲染成图").performClick()
        compose.waitUntil(timeoutMillis = 60_000) {
            MermaidRenderer.invocationCount() == 1
        }
        compose.waitUntil(timeoutMillis = 60_000) {
            compose.onAllNodesWithText("已存到", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        assertEquals("点一次只该起一次离屏任务", 1, MermaidRenderer.invocationCount())

        val saved = compose.onAllNodesWithText("已存到", substring = true).fetchSemanticsNodes()
        assertEquals(1, saved.size)

        // 真出了一张 PNG（不是只有一行字）
        val ctx = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(com.example.zhengdao.baopu.BaopuWorkspace.hostDir(ctx), "产物/图表")
        val pngs = dir.listFiles()?.filter { it.name.endsWith(".png") } ?: emptyList()
        assertEquals(true, pngs.isNotEmpty())
    }

    /**
     * 判据③在**消息列表**这一层的落点：助手消息里带 ```mermaid 围栏 ⇒
     * 默认显示代码块＋按钮、不自动渲染（0 次离屏任务）。
     */
    @Test
    fun 助手消息里的mermaid块显示代码与按钮且不自动渲染() {
        MermaidRenderer.resetInvocationCount()

        val text = "先看这段：\n\n```mermaid\n" + code + "\n```\n\n要图就点按钮。"
        val msg = BaopuUiMessage(
            id = 7L,
            role = "assistant",
            parts = listOf(BaopuUiPart(kind = "text", text = text)),
        )

        compose.setContent {
            MaterialTheme {
                BaopuMessageList(messages = listOf(msg))
            }
        }

        // 代码本体照样看得见（不是被吞掉）
        compose.onNodeWithText(code, substring = true).assertExists()
        // 按钮在
        compose.onNodeWithText("渲染成图").assertExists()
        compose.waitForIdle()
        Thread.sleep(800)
        assertEquals("列表渲染时就偷偷出图了", 0, MermaidRenderer.invocationCount())
    }
}
