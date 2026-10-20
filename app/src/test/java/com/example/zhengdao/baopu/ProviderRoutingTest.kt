// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单《第九十三批 · 模型路由 v1》§2「默认行为不变：不显式开 AUTO_ROUTE 时仍走当前通道」
// 「回退：复用 64 的切换/能力差异，不新建第二套状态」与 §3「默认不开＝当前通道」。
// 这里跑的是 `ProviderPrefs` 那半边（SP 薄壳的 Context-free 内核）：
// 名单规则从老的 `fallbackFrom` 原样抽进 `routeCandidates`，抽之前/之后必须同序同值。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 路由开关接进回退链路之后的判据（[ProviderPrefs.routeCandidates] / [ProviderPrefs.fallbackFrom]）。
 *
 * 三条口径要分开钉：
 * 1. **默认关**：不传 autoRoute 参数时，`fallbackFrom` 的结果＝名单第一个，与刀 1 补丁 /
 *    第六十四批时代逐元素一致（这条是「不默认改变当前通道」的行为证据）；
 * 2. **显式开**：开了以后走的确实是排序那条路——今天本仓成本/延迟/质量全表 UNKNOWN，
 *    所以「开了」的可见结果必须是 null（调用方 ask_user），**而不是静默挑一个**；
 * 3. **名单只有一套规则**：熔断与路由吃同一份候选（排除当前、排除内置免费、预设要有 Key、
 *    自定义兜底），不许长成两套。
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
            "显式传 autoRoute=false 与不传必须一模一样（默认值就是关）",
            old, ProviderPrefs.fallbackFrom(currentId = presetD.id, keyFor = k, autoRoute = false),
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
            "只给内置免费通道填 key 也不算候选（2026-10-17 真机整条 403 RegionError）",
            ProviderPrefs.fallbackFrom(
                currentId = presetD.id,
                keyFor = { id -> if (id == Providers.BUILTIN_FREE.id) "free-key" else null },
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
            "预设都没填 key（这里 keyFor 给的是万能 key，但 currentId 是 custom ⇒ 只剩预设）",
            listOf(presetA.id, presetB.id, presetD.id),
            list.map { it.id },
        )
        assertFalse("名单里不许出现当前通道自己", list.any { it.id == ProviderPrefs.CHANNEL_CUSTOM })
    }

    // ── 3. 显式开路由 ────────────────────────────────────────────────────

    @Test
    fun 开着路由但本仓没有成本依据_返回null_不静默挑一个() {
        val k = keys(presetA.id, presetB.id, presetD.id)
        assertNull(
            "AUTO_ROUTE 开着，但成本这一维全表 UNKNOWN ⇒ 排不出序、宁可不选（派工单「不静默降级」）",
            ProviderPrefs.fallbackFrom(
                currentId = presetD.id,
                keyFor = k,
                autoRoute = true,
                preference = ModelRouter.Preference.CHEAP,
            ),
        )
        assertNotNull(
            "同一组输入，关着开关必须照旧给出候选（这条对照证明上面那个 null 真的是路由造成的）",
            ProviderPrefs.fallbackFrom(currentId = presetD.id, keyFor = k, autoRoute = false),
        )
        ModelRouter.Preference.entries.forEach { pref ->
            assertNull(
                "$pref 这一维今天同样全表无依据 ⇒ 都必须 null",
                ProviderPrefs.fallbackFrom(
                    currentId = presetD.id,
                    keyFor = k,
                    autoRoute = true,
                    preference = pref,
                ),
            )
        }
    }

    @Test
    fun 开着路由但没选偏好_也是null_不猜一个偏好() {
        val k = keys(presetA.id, presetB.id)
        assertNull(
            "开了开关却没选偏好 ⇒ 不替用户猜（派工单「不乱猜」）",
            ProviderPrefs.fallbackFrom(currentId = presetD.id, keyFor = k, autoRoute = true, preference = null),
        )
    }

    @Test
    fun 开着路由且能力要求满足不了_同样是null而不是降级() {
        val k = keys(presetA.id, presetB.id, presetD.id)
        assertNull(
            "要求 8M 窗口：名单里没一条够 ⇒ null（不许「就近选一个」）",
            ProviderPrefs.fallbackFrom(
                currentId = presetD.id,
                keyFor = k,
                autoRoute = true,
                preference = ModelRouter.Preference.FAST,
                need = ModelRouter.Need(contextTokens = 8_000_000),
            ),
        )
    }

    // ── 4. 开关与偏好的存储判据（薄壳的内核那一半） ─────────────────────────

    @Test
    fun 开关内核_null与false都是关_只有true是开() {
        assertFalse("没存过（null）⇒ 关", ProviderPrefs.autoRouteEnabled(null as Boolean?))
        assertFalse(ProviderPrefs.autoRouteEnabled(false))
        assertTrue("显式存了 true 才算开", ProviderPrefs.autoRouteEnabled(true))
    }

    @Test
    fun 偏好脏值存不成档位() {
        ModelRouter.Preference.entries.forEach { p ->
            assertEquals("存出去的 token 必须能原样认回来", p, ModelRouter.preferenceOf(ModelRouter.keyOf(p)))
        }
        assertEquals(
            "用户手滑多打了个空格不改语义：trim 之后算合法",
            ModelRouter.Preference.CHEAP,
            ModelRouter.preferenceOf(" cheap "),
        )
        listOf<String?>("", "   ", "auto", "省钱", "cheapest", "CHEAP-ish", null).forEach { raw ->
            assertNull("脏偏好 [$raw] ⇒ null（不猜默认）", ModelRouter.preferenceOf(raw))
        }
    }

    @Test
    fun 路由开关不改解析结果_current与名单是两套独立判据() {
        // auto 开关只影响「下一个是谁」，不影响「现在是谁」：resolve 的返回值跟 auto 无关
        val cur = ProviderPrefs.resolve(channelId = presetD.id, keyFor = keys(presetD.id))
        assertEquals(presetD.id, cur.id)
        assertEquals("sk-user-${presetD.id}", cur.apiKey)
        // resolve 的入参表里没有 autoRoute / preference / need（有就是「现在是谁」会被路由改）：
        // 这条结构性事实由 ModelRoutingGateTest G7 用源码门禁钉死，这里只钉行为。
        assertEquals(
            "同一个 channelId：auto 开关不参与解析 ⇒ 结果与开关无关",
            cur,
            ProviderPrefs.resolve(channelId = presetD.id, keyFor = keys(presetD.id)),
        )
    }
}
