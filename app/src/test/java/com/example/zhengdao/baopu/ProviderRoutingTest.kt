// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：第一百六十九批 A 案（AUTO_ROUTE 死开关整条移除）与第六十四批「回退复用一套状态」。
// 这里跑的是 `ProviderPrefs` 那半边（SP 薄壳的 Context-free 内核）：
// 名单规则从老的 `fallbackFrom` 原样抽进 `routeCandidates`，抽之前/之后必须同序同值；
// `fallbackFrom` 现在**只有一条路径**：取名单第一个（第一百六十九批起不再读任何开关）。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回退候选的判据（[ProviderPrefs.routeCandidates] / [ProviderPrefs.fallbackFrom]）。
 *
 * 两条口径：
 * 1. **唯一路径**：`fallbackFrom` 的结果恒等于名单第一个，与刀 1 补丁 / 第六十四批时代逐元素一致；
 * 2. **名单只有一套规则**：排除当前通道、排除内置免费、预设要有 Key、自定义兜底。
 */
class ProviderRoutingTest {

    private val presetA = Providers.OPENAI_COMPAT
    private val presetB = Providers.ANTHROPIC
    private val presetD = Providers.DEEPSEEK

    /** keyFor 夹具：给谁填了 key 就写进 map，其余返回 null（与 SP 的 user_key_<id> 同形状）。 */
    private fun keys(vararg ids: String): (String) -> String? = { id -> if (id in ids) "sk-user-$id" else null }

    private val customBase = "https://api.deepseek.com/v1"
    private val customModel = "deepseek-chat"

    // ── 1. 默认关＝行为不变 ───────────────────────────────────────────────

    @Test
    fun 不传开关参数时取名单第一个_与抽表前逐元素同值() {
        val k = keys(presetA.id, presetB.id, presetD.id)
        val list = ProviderPrefs.routeCandidates(currentId = presetD.id, keyFor = k)
        val old = ProviderPrefs.fallbackFrom(currentId = presetD.id, keyFor = k)
        assertNotNull(old)
        assertEquals("默认关 ⇒ 就是名单第一个", list.first(), old)
        assertEquals(
            "Providers.all() 的预设序里第一个可用候选是 openai-compat（内置免费被排除）",
            presetA.id, old!!.id,
        )
        assertEquals("用户填的那把 key 必须带上", "sk-user-${presetA.id}", old.apiKey)
        assertEquals(
            "同一组输入重复调用必须同值（没有开关能改变那条路）",
            old, ProviderPrefs.fallbackFrom(currentId = presetD.id, keyFor = k),
        )
    }

    @Test
    fun 默认关时四个既有场景一个没变() {
        // 与 ProviderSwitchTest 的三条老判据同源，这里从「名单」这一侧重说一遍，
        // 证明抽函数没改规则：到线有候选 ⇒ 切；无候选 ⇒ null（问人）；只有内置免费的 key ⇒ 仍无候选。
        assertEquals(
            presetA.id,
            ProviderPrefs.fallbackFrom(currentId = presetD.id, keyFor = keys(presetA.id))?.id,
        )
        assertNull(
            "一个预设 key 都没有 ⇒ 名单空、null（调用方 ask_user）",
            ProviderPrefs.fallbackFrom(currentId = presetD.id, keyFor = keys()),
        )
        assertNull(
            "只给免费车道填 key 也不算候选（第一百三十一批 ①：免费档有 50 次/天额度，" +
                "不许拿它兜别的通道的故障）",
            ProviderPrefs.fallbackFrom(
                currentId = presetD.id,
                keyFor = { id -> if (id == Providers.OPENROUTER_FREE.id) "free-key" else null },
            ),
        )
        assertEquals(
            "排除当前通道：熔断的就是 deepseek，切回去等于没切",
            presetB.id,
            ProviderPrefs.fallbackFrom(currentId = presetD.id, keyFor = keys(presetD.id, presetB.id))?.id,
        )
    }

    // ── 2. 名单规则（抽出来后仍是一套） ───────────────────────────────────

    @Test
    fun 自定义通道排在名单末尾_三件不齐就不进名单() {
        val ok = ProviderPrefs.routeCandidates(
            currentId = presetD.id,
            keyFor = { id -> if (id == ProviderPrefs.CHANNEL_CUSTOM) "sk-custom" else null },
            customBase = customBase,
            customModel = customModel,
        )
        assertEquals("没有预设候选时只有自定义一条", listOf(ProviderPrefs.CHANNEL_CUSTOM), ok.map { it.id })
        assertEquals("自定义通道用的是用户自填的 model", customModel, ok.first().model)

        val short = ProviderPrefs.routeCandidates(
            currentId = presetD.id,
            keyFor = { null },
            customBase = customBase,
            customModel = "",
        )
        assertTrue("缺 model ⇒ 三件不齐 ⇒ 不进名单", short.isEmpty())

        val plain = ProviderPrefs.routeCandidates(
            currentId = presetD.id,
            keyFor = { null },
            customBase = "http://example.com/v1",
            customModel = customModel,
        )
        assertTrue("明文 http 非环回不许放行", plain.isEmpty())

        val httpOk = ProviderPrefs.routeCandidates(
            currentId = presetD.id,
            keyFor = { null },
            customBase = "http://127.0.0.1:8080/v1",
            customModel = customModel,
        )
        assertEquals("环回 http 是单测假服务器的路 ⇒ 放行", 1, httpOk.size)
    }

    @Test
    fun 当前是自定义通道时名单只剩填了key的预设_且不给自己当候选() {
        val list = ProviderPrefs.routeCandidates(
            currentId = ProviderPrefs.CHANNEL_CUSTOM,
            keyFor = { "k" },
            customBase = customBase,
            customModel = customModel,
        )
        assertEquals(
            "名单＝Providers.all() 里除免费车道外的全部（第一百三十一批 ① 之后含 OpenRouter 正式档），" +
                "顺序照预设序；currentId 是 custom ⇒ 自定义不进自己的名单",
            Providers.all().filter { !it.builtinFree }.map { it.id },
            list.map { it.id },
        )
        assertFalse("名单里不许出现当前通道自己", list.any { it.id == ProviderPrefs.CHANNEL_CUSTOM })
    }

    // ── 3. 当前通道的解析不受回退影响 ─────────────────────────────────────

    @Test
    fun 解析当前通道与回退名单是两套独立判据() {
        val cur = ProviderPrefs.resolve(channelId = presetD.id, keyFor = keys(presetD.id))
        assertEquals(presetD.id, cur.id)
        assertEquals("sk-user-${presetD.id}", cur.apiKey)
        // resolve 的入参表里没有任何路由参数（有就是「现在是谁」会被回退改）：
        // 这条结构性事实由 ModelRoutingGateTest 的源码门禁钉死，这里只钉行为。
        assertEquals(
            "同一个 channelId 重复解析必须同值",
            cur,
            ProviderPrefs.resolve(channelId = presetD.id, keyFor = keys(presetD.id)),
        )
    }
}
