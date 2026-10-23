// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

// 第一百五十三批门禁：预设 vs 聚合的模型选择形态、发送/停止单按钮三态、
// 以及 hermes 新 pinned uv 的自动修复（用户 2026-10-23 一次交办）。
package com.example.zhengdao.baopu

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelComposerPolishGateTest {

    private fun src(rel: String): String =
        File(rel).let { if (it.isFile) it else File("app/$rel") }.readText()

    private val sheet get() = src("src/main/java/com/example/zhengdao/ui/baopu/BaopuChannelSheet.kt")
    private val composer get() = src("src/main/java/com/example/zhengdao/ui/baopu/BaopuComposer.kt")
    private val installer get() = src("src/main/java/com/example/zhengdao/ui/AgentInstaller.kt")
    private val rules get() = src("src/main/java/com/example/zhengdao/baopu/ModelPickerRules.kt")

    @Test
    fun `预设只剩一条 OpenRouter_且换成 Gemini`() {
        val ids = Providers.all().map { it.id }
        assertEquals(1, ids.count { it.startsWith("openrouter") })
        assertTrue("Gemini 预设必须在", "gemini" in ids)
        val g = Providers.all().first { it.id == "gemini" }
        assertEquals(LlmProtocol.GEMINI_NATIVE, g.protocol)
        assertTrue("Gemini 走原生 /v1beta", g.baseUrl.endsWith("/v1beta"))
        assertTrue("Gemini 预设不许内置 Key", g.apiKey.isEmpty())
    }

    @Test
    fun `聚合与预设的判定`() {
        assertTrue("OpenRouter 免费档是聚合服务", ModelPickerRules.isAggregate(Providers.OPENROUTER_FREE))
        Providers.all().filter { it.id != Providers.OPENROUTER_FREE.id }.forEach {
            assertFalse("$it 是预设，不该按聚合处理", ModelPickerRules.isAggregate(it))
        }
        assertTrue("规则本身存在", rules.contains("fun isAggregate(provider: Provider)"))
    }

    @Test
    fun `搜索框只在聚合服务出现`() {
        assertTrue("搜索框必须挂在 aggregate 判定下", sheet.contains("if (aggregate) {"))
        assertTrue("搜索标签仍在", sheet.contains("搜索模型（输入 free 只看免费）"))
        assertTrue("聚合服务要有注册跳转", sheet.contains("SmallAction(\"注册\")"))
        assertTrue("跳转只走玄览，且去注册页", sheet.contains("openInXuanlan(ModelPickerRules.OPENROUTER_SIGNUP_URL)"))
    }

    @Test
    fun `发送停止只留一个按钮三态`() {
        assertTrue("跑动无草稿显示停止", composer.contains("if (busy && !canSend)"))
        assertTrue("跑动有草稿显示发送·排队", composer.contains("R.string.baopu_send_queue"))
        // 旧形态（停止与发送并排）必须消失：停止的 TextButton 不许与发送 Button 同级常驻。
        val stopIdx = composer.indexOf("onClick = { onStop() }")
        val sendIdx = composer.indexOf("R.string.baopu_send_queue")
        assertTrue("停止与发送必须在同一个 if/else 两侧", stopIdx in 0 until sendIdx)
    }

    @Test
    fun `hermes 新 pinned uv 失败后自动修复并重跑`() {
        assertTrue("必须识别 uv.real", installer.contains("uv.real"))
        assertTrue("必须写 copy 包装器", installer.contains("export UV_LINK_MODE=copy"))
        assertTrue("必须自动重跑一次", installer.contains("首次安装失败，uv 已修复，自动重跑一次"))
        assertTrue("修复块只在 hermes", installer.contains("if (agent.id == \"hermes\")"))
    }
}
