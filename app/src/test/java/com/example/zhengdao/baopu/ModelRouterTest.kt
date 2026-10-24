// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单《第九十三批 · 模型路由 v1》§2 的**能力过滤**部分（「做不到还不说 ⇒ 静默降级」；
// 「没有满足能力的模型 ⇒ 明确报错」）与第一百六十九批 A 案（删除 AUTO_ROUTE 自动排序路径）。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 能力过滤判据（[ModelRouter.missingOf] / [ModelRouter.satisfies]）用例。
 *
 * 第九十三批曾在这里钉排序与开关，开关从落地起就没有写入口（`setAutoRoute` 生产零调用、
 * key 私有 ⇒ 永远读到 false），第一百六十九批按派工单 A 案把整条路径删了；
 * 留下的只有「这条通道做不做得到这件事」——看图的 `VisionRouting` 正在用。
 */
class ModelRouterTest {

    private fun provider(
        id: String,
        model: String = "m-$id",
        vision: Boolean = false,
        window: Int? = null,
    ): Provider = Provider(
        id = id,
        label = "标签-$id",
        protocol = LlmProtocol.OPENAI_COMPAT,
        baseUrl = "https://routing-fixture.invalid/v1",
        model = model,
        // 哨兵：任何一条输出里出现它，就是「日志打印 API Key」那一条打回事由
        apiKey = SENTINEL_KEY,
        vision = vision,
        contextWindow = window,
    )

    private fun rowOf(
        p: Provider,
        tool: ModelCapabilityTable.Support = ModelCapabilityTable.Support.YES,
        thinking: ModelCapabilityTable.Support = ModelCapabilityTable.Support.YES,
        tiers: List<String> = TIERS,
    ): ModelCapabilityTable.Row = ModelCapabilityTable.Row(
        channelId = p.id,
        model = p.model,
        toolSchema = tool,
        thinking = thinking,
        thinkingTiers = tiers,
        inputPer1k = null,
        outputPer1k = null,
        currency = null,
        latencyP50Ms = null,
        latencyP90Ms = null,
        qualityTier = null,
        evidence = "测试夹具",
    )

    private fun candidate(p: Provider, row: ModelCapabilityTable.Row? = null): ModelRouter.Candidate =
        ModelRouter.Candidate(p, row ?: ModelCapabilityTable.unknownRow(p.id, p.model))

    // ── 能力过滤四例（§3） ────────────────────────────────────────────────

    @Test
    fun 能力过滤之一_要发图但候选看不了图_明确报缺不静默降级() {
        val a = provider("chan-a", vision = false)
        val b = provider("chan-b", vision = false)
        val need = ModelRouter.Need(vision = true)
        listOf(a, b).forEach { p ->
            val missing = ModelRouter.missingOf(candidate(p), need)
            assertTrue("看不了图要报缺：${p.id} ⇒ $missing", missing.any { it.contains("看图") })
            assertFalse("不许把不合格的说成「已满足」", ModelRouter.satisfies(candidate(p), need))
        }
    }

    @Test
    fun 能力过滤之一补_模型名里带vl不算依据_只看Provider_vision() {
        // 第二十四批 V5 的口径：能不能看图只读 Provider.vision，组装层不许猜模型名，路由同样不许。
        val named = provider("chan-vlnamed", model = "nemotron-3.5-super-vl-free", vision = false)
        assertFalse(
            "名字带 -vl- 而 vision=false 的候选必须被刷掉",
            ModelRouter.satisfies(candidate(named), ModelRouter.Need(vision = true)),
        )
    }

    @Test
    fun 能力过滤之二_要原生工具但schema是UNKNOWN的候选被刷掉而不是当成支持() {
        val unknown = provider("chan-unknown-tool")
        val yes = provider("chan-yes-tool")
        val need = ModelRouter.Need(tools = true)
        val unknownMissing = ModelRouter.missingOf(
            candidate(unknown, rowOf(unknown, tool = ModelCapabilityTable.Support.UNKNOWN)),
            need,
        )
        assertTrue("schema 没依据不等于支持：$unknownMissing", unknownMissing.any { it.contains("没有依据") })
        assertTrue(
            "有依据地支持才放行",
            ModelRouter.satisfies(candidate(yes, rowOf(yes, tool = ModelCapabilityTable.Support.YES)), need),
        )
    }

    @Test
    fun 能力过滤之三_窗口不够就报缺_窗口只按Provider声明算() {
        val small = provider("chan-small", window = 200_000)
        val big = provider("chan-big", window = 1_000_000)
        val need = ModelRouter.Need(contextTokens = 300_000)
        val missing = ModelRouter.missingOf(candidate(small), need)
        assertTrue("缺项里要给出要多少、这条多少：$missing", missing.any { it.contains("要 300000") && it.contains("200000") })
        assertTrue(ModelRouter.satisfies(candidate(big), need))
    }

    @Test
    fun 能力过滤之三补_没声明窗口复用既有1M回退_路由不自带第二套回退() {
        val p = provider("chan-null-window", window = null)
        val c = candidate(p)
        assertEquals(
            "窗口回退只许住在 ContextAssembler.ContextBudgets.windowOf 那一处",
            ContextAssembler.ContextBudgets.windowOf(p),
            c.windowTokens,
        )
        assertEquals(1_048_576, c.windowTokens)
    }

    @Test
    fun 能力过滤之四_要特定思考档位_清单没依据或不含该档都算不满足() {
        val noTiers = provider("chan-no-tiers")
        val otherTiers = provider("chan-other-tiers")
        val right = provider("chan-right")
        val noThink = provider("chan-no-think")
        val need = ModelRouter.Need(thinking = "high")

        val unknown = ModelRouter.missingOf(
            candidate(noTiers, rowOf(noTiers, thinking = ModelCapabilityTable.Support.YES, tiers = emptyList())),
            need,
        )
        assertTrue("「会思考但档位清单没依据」要单独说清：$unknown", unknown.any { it.contains("档位清单没有依据") })

        val missing = ModelRouter.missingOf(
            candidate(otherTiers, rowOf(otherTiers, thinking = ModelCapabilityTable.Support.YES, tiers = listOf("low"))),
            need,
        )
        assertTrue("清单里没有这一档要报出清单：$missing", missing.any { it.contains("清单只有 low") })

        val never = ModelRouter.missingOf(
            candidate(noThink, rowOf(noThink, thinking = ModelCapabilityTable.Support.NO)),
            need,
        )
        assertTrue("有依据地不支持要这么说：$never", never.any { it.contains("不认思考字段") })

        assertTrue(ModelRouter.satisfies(candidate(right, rowOf(right, thinking = ModelCapabilityTable.Support.YES, tiers = TIERS)), need))
    }

    @Test
    fun 四条能力全缺时报错把缺项列全() {
        val bad = provider("chan-bad", vision = false, window = 1000)
        val missing = ModelRouter.missingOf(
            candidate(bad, rowOf(bad, tool = ModelCapabilityTable.Support.NO, thinking = ModelCapabilityTable.Support.NO)),
            ModelRouter.Need(vision = true, tools = true, contextTokens = 900_000, thinking = "max"),
        )
        assertEquals("看图/工具/窗口/思考四条都要点到", 4, missing.size)
    }

    @Test
    fun 空需求时任何候选都算满足_缺省值不是要求() {
        val a = provider("chan-a", vision = false)
        val c = candidate(a)
        assertTrue("Need() 的缺省 = 这项没要求，不是这项不满足", ModelRouter.satisfies(c, ModelRouter.Need()))
        assertTrue(ModelRouter.missingOf(c, ModelRouter.Need()).isEmpty())
    }

    // ── 输出纪律 ─────────────────────────────────────────────────────────

    @Test
    fun 缺项话术只许出现通道id与模型名_不许出现APIKey() {
        val blind = provider("chan-blind", vision = false)
        val unknownTool = provider("chan-unknown-tool")
        val outputs = mutableListOf<String>()
        outputs += ModelRouter.missingOf(candidate(blind), ModelRouter.Need(vision = true))
        outputs += ModelRouter.missingOf(
            candidate(unknownTool, rowOf(unknownTool, tool = ModelCapabilityTable.Support.UNKNOWN)),
            ModelRouter.Need(tools = true),
        )
        outputs += ModelRouter.missingOf(
            candidate(blind, rowOf(blind, thinking = ModelCapabilityTable.Support.NO)),
            ModelRouter.Need(thinking = "high", contextTokens = 1),
        )
        outputs.forEach { s ->
            assertFalse("路由的任何话术都不许带 Key（哨兵泄漏）：$s", s.contains(SENTINEL_KEY))
            assertFalse("也不许带 apiKey 这个字段名：$s", s.contains("apiKey"))
        }
        // 直接内插一个 Provider 对象就会漏（data class 的 toString 带 apiKey）——这里证明本文件没走那条路
        assertTrue("哨兵必须真的进过候选（不然这条用例是空的）", blind.apiKey == SENTINEL_KEY)
        assertTrue("对照：Provider 是 data class，toString() 会带出 apiKey ⇒ 门禁禁止这种写法", blind.toString().contains(SENTINEL_KEY))
    }

    private companion object {
        const val SENTINEL_KEY = "sk-SENTINEL-不许出现在任何输出"
        val TIERS = listOf("low", "high", "max")
    }
}
