// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu.agent

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「单一真相」源码门禁（第六十四批派工单 §4）。
 *
 * 判据原文：
 * - 「当前通道/模型」只能有一个真相（ProviderPrefs 现值），资源机只读它、
 *   写也只经它；**禁止在资源机里另存一份 currentModel**；
 * - 源码门禁：全仓「当前模型」字段只有一处；资源机文件里不许出现第二个赋值点。
 *
 * 为什么用源码形状门禁而不是行为用例：要钉的正是「不存在第二份真相」这种
 * 结构性事实（行为用例只能证明某条路径对，证明不了别处没有暗仓）——
 * 与 CompressionSummaryShadowGateTest / StateLifetimeGateTest 同款门禁范式。
 */
class ChannelSwitchTruthGateTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.exists()) it else File("app/$rel") }

    private fun read(rel: String): String = repoFile(rel).readText()

    private fun codeOnly(src: String): String =
        src.split("\n").joinToString("\n") { it.substringBefore("//") }
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")

    private val fsmFile = "src/main/java/com/example/zhengdao/baopu/agent/ChannelSwitchFsm.kt"
    private val engineFile = "src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt"

    // ── G1：资源机不经手存储 ────────────────────────────────────────────

    @Test
    fun `资源机文件不引用ProviderPrefs`() {
        // 只查**代码**：KDoc 里提 ProviderPrefs 是声明纪律（"真相在那边"），
        // 代码里出现才是第二入口。
        val code = codeOnly(read(fsmFile))
        assertFalse(
            "资源机必须是纯判据：存储（ProviderPrefs）只该出现在装配层，" +
                "资源机里出现它就是第二入口",
            code.contains("ProviderPrefs"),
        )
        assertFalse("资源机不许碰 SharedPreferences", code.contains("SharedPreferences"))
        assertFalse("资源机不许持 context", code.contains("Context"))
    }

    // ── G2：资源机里没有「当前」字段 ───────────────────────────────────

    @Test
    fun `资源机文件没有当前模型字段_也没有第二个赋值点`() {
        val code = codeOnly(read(fsmFile))
        assertFalse("不许另存 currentModel", code.contains("currentModel"))
        assertFalse("不许另存 currentChannel", code.contains("currentChannel"))
        assertFalse(
            "不许有 var current 形态的「当前通道」字段（請求負荷 requestedTarget 例外，见 KDoc）",
            Regex("var\\s+current\\w*\\s*[:=]").containsMatchIn(code),
        )
    }

    // ── G3：引擎的 provider 写入口只有两处 ─────────────────────────────

    @Test
    fun `RunEngine的provider赋值点恰为两处_熔断层与安全点各一`() {
        val code = codeOnly(read(engineFile))
        val writes = Regex("(?<![.=!<>])provider\\s*=\\s*[^=]").findAll(code).count()
        assertEquals(
            "provider 字段只许两处写：①熔断降级（channelFallback 后）②安全点切换" +
                "（drainChannelSwitch 成功分支）。多一处就是暗仓。",
            2,
            writes,
        )
    }

    // ── G4：三个安全点一个不许少 ───────────────────────────────────────

    @Test
    fun `三个安全点都接了线_且只经drainChannelSwitch一个漏斗`() {
        val engine = read(engineFile)
        val calls = Regex("drainChannelSwitch\\(SafePoint\\.(\\w+)\\)").findAll(engine)
            .map { it.groupValues[1] }.toList()
        assertEquals(
            "派工单 §2 只认三处：两步之间 / 出确认卡之前 / 本轮 Settled 之后。" +
                "少一处＝切换时机被猜，多一处＝自己造安全点。",
            listOf("StepBoundary", "BeforeConfirmCard", "AfterSettled").sorted(),
            calls.sorted(),
        )
        val funnels = Regex("channelSwitch\\.atSafePoint\\(").findAll(engine).count()
        assertEquals(
            "三个接线点必须收口在 drainChannelSwitch 一个漏斗里（成功才写 provider）",
            1,
            funnels,
        )
    }

    // ── G5：全仓没有第二份「当前模型」字段 ─────────────────────────────

    @Test
    fun `全仓生产代码没有第二个当前模型字段`() {
        val root = repoFile("src/main/java")
        val hits = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            val code = codeOnly(f.readText())
            if (Regex("(var|val)\\s+currentModel\\b").containsMatchIn(code)) {
                hits += f.path
            }
        }
        assertEquals(
            "「当前模型」只许 ProviderPrefs 一份（SP 里的 custom_model）。" +
                "出现结构体字段就是第二真相。",
            emptyList<String>(),
            hits,
        )
    }

    // ── G6：切换的写路径只经注入效应 ──────────────────────────────────

    @Test
    fun `引擎的切换效应经switchChannel注入_不在引擎里拼SP`() {
        val engine = read(engineFile)
        assertTrue(
            "效应必须是注入的 lambda（装配层写 Prefs）",
            engine.contains("private val switchChannel: (String, String) -> Provider?"),
        )
        assertFalse(
            "引擎不许自己开 SharedPreferences（Key 不进引擎，同 channelFallback 纪律）",
            engine.contains("getSharedPreferences"),
        )
    }
}
