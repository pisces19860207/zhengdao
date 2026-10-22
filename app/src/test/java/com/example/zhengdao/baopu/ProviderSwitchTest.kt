// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 刀 1 补丁单（2026-10-17 追加）的三条单测：
// ① 熔断自动切换（到线 + 有候选 ⇒ 切；到线 + 无候选 ⇒ ask_user；未到线 ⇒ 留在原通道）
// ② 换通道上限（防「两个坏通道来回切」的死循环）
// ③ 通道持久化的纯内核（自定义通道三件齐全才生效；预设填了 key 就带上）
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.agent.RunRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderSwitchTest {

    private val preset: Provider = Providers.all().first { !it.builtinFree }

    // ---------- ① 熔断自动切换：三态 ----------

    @Test
    fun 未到熔断线_一律留在原通道() {
        for (n in 0 until RunRules.CHANNEL_BREAK_THRESHOLD) {
            assertEquals(
                "连续失败 $n 次（未到 ${RunRules.CHANNEL_BREAK_THRESHOLD} 次线）不该切通道",
                RunRules.ChannelDecision.KeepCurrent,
                RunRules.channelDecision(n, preset),
            )
        }
    }

    @Test
    fun 到熔断线且有候选_切到候选() {
        val d = RunRules.channelDecision(RunRules.CHANNEL_BREAK_THRESHOLD, preset)
        assertTrue("到线且有候选必须是 SwitchTo，实际 $d", d is RunRules.ChannelDecision.SwitchTo)
        assertEquals(preset, (d as RunRules.ChannelDecision.SwitchTo).next)
    }

    @Test
    fun 到熔断线且无候选_停下问人而不抛异常() {
        assertEquals(
            "没有可切换的通道时必须走 BrokenAskUser（ask_user），不许抛异常",
            RunRules.ChannelDecision.BrokenAskUser,
            RunRules.channelDecision(RunRules.CHANNEL_BREAK_THRESHOLD, null),
        )
    }

    // ---------- ② 换通道上限 ----------

    @Test
    fun 换通道上限是2次_到顶后只能问人() {
        assertEquals("上限写死 2：换两次还不通就该停下来问人", 2, RunRules.MAX_CHANNEL_SWITCHES)
        // 到顶后调用方会把候选置空 —— 这里直接断言「候选为 null 时只能问人」这一条闸门。
        assertEquals(
            RunRules.ChannelDecision.BrokenAskUser,
            RunRules.channelDecision(RunRules.CHANNEL_BREAK_THRESHOLD * 10, null),
        )
    }

    // ---------- ③ 候选永不落回免费车道 ----------

    @Test
    fun 只给免费车道填了key_也不算候选() {
        val got = ProviderPrefs.fallbackFrom(
            currentId = preset.id,
            keyFor = { id -> if (id == Providers.OPENROUTER_FREE.id) "free-key" else null },
        )
        assertNull(
            "免费车道（第一百三十一批 ①＝OpenRouter 免费档）有 50 次/天的额度上限：" +
                "它是「没得选才用」的默认值，不是拿用户的免费额度去兜别的通道的故障：$got",
            got,
        )
    }

    @Test
    fun 预设填了key_它就是候选且带上这把key() {
        val got = ProviderPrefs.fallbackFrom(
            currentId = Providers.OPENROUTER_FREE.id,
            keyFor = { id -> if (id == preset.id) "sk-test" else null },
        )
        assertEquals(preset.id, got?.id)
        assertEquals("候选必须带上用户填的那把 key", "sk-test", got?.apiKey)
    }

    // ---------- ④ 持久化纯内核 ----------

    @Test
    fun 自定义通道三件齐全才生效() {
        val ok = ProviderPrefs.resolve(
            channelId = ProviderPrefs.CHANNEL_CUSTOM,
            keyFor = { null },
            customBase = "https://api.deepseek.com/v1",
            customModel = "deepseek-chat",
        )
        assertEquals(ProviderPrefs.CHANNEL_CUSTOM, ok.id)
        assertEquals("https://api.deepseek.com/v1", ok.baseUrl)
        assertEquals("deepseek-chat", ok.model)
    }

    @Test
    fun 自定义通道缺一件就退回免费车道() {
        val baseOnly = ProviderPrefs.resolve(
            channelId = ProviderPrefs.CHANNEL_CUSTOM,
            keyFor = { null },
            customBase = "https://api.deepseek.com/v1",
            customModel = "",
        )
        assertEquals("缺 model 不许当成自定义通道", Providers.OPENROUTER_FREE.id, baseOnly.id)

        val plainHttp = ProviderPrefs.resolve(
            channelId = ProviderPrefs.CHANNEL_CUSTOM,
            keyFor = { null },
            customBase = "http://example.com/v1",
            customModel = "m",
        )
        assertEquals("明文 http 非环回不许放行", Providers.OPENROUTER_FREE.id, plainHttp.id)
    }

    @Test
    fun 预设选择_填key就带上_没填就用预设原样() {
        val withKey = ProviderPrefs.resolve(
            channelId = preset.id,
            keyFor = { "sk-user" },
        )
        assertEquals("sk-user", withKey.apiKey)

        val noKey = ProviderPrefs.resolve(
            channelId = preset.id,
            keyFor = { null },
        )
        assertEquals("没填 key 时不许把预设自带的 apiKey 抹掉", preset.apiKey, noKey.apiKey)
    }

    @Test
    fun 未知通道号_退回免费车道() {
        val got = ProviderPrefs.resolve(channelId = "no-such-channel", keyFor = { "x" })
        assertEquals(Providers.OPENROUTER_FREE.id, got.id)
    }
}
