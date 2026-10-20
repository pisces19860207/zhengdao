// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单《第九十三批 · 模型路由 v1》§2（策略：输入＝能力＋偏好，输出＝选中＋理由＋备选链；
// 默认行为不变；没有满足能力的模型 ⇒ 明确报错，不静默降级）与 §3（正例＋负例：
// 能力过滤四例＋无满足报错；成本/延迟排序；UNKNOWN 不参与排序；默认不开＝当前通道）。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 路由判据本体（[ModelRouter]）用例。
 *
 * 排序类用例必须喂夹具行：本仓那张表今天**没有一格的成本/延迟/质量有依据**（派工单「拿不到就标
 * UNKNOWN，不猜」），所以「排得出序」这条路在生产数据上今天走不到——走不到不等于判据可以不对，
 * 用夹具把它钉死，后续批填进真数就直接生效。[plan] 的 `rowFor` 接缝就是为这件事开的
 * （默认值＝查唯一那张表，生产调用方一个都不传）。
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
        in1k: Double? = null,
        out1k: Double? = null,
        currency: String? = null,
        p50: Long? = null,
        p90: Long? = null,
        quality: Int? = null,
    ): ModelCapabilityTable.Row = ModelCapabilityTable.Row(
        channelId = p.id,
        model = p.model,
        toolSchema = tool,
        thinking = thinking,
        thinkingTiers = tiers,
        inputPer1k = in1k,
        outputPer1k = out1k,
        currency = currency,
        latencyP50Ms = p50,
        latencyP90Ms = p90,
        qualityTier = quality,
        evidence = "测试夹具",
    )

    /** 用夹具行跑一次决策（rows 按通道 id 取；没给的通道 ⇒ 全 UNKNOWN 空行）。 */
    private fun plan(
        candidates: List<Provider>,
        current: Provider,
        auto: Boolean = true,
        preference: ModelRouter.Preference? = ModelRouter.Preference.CHEAP,
        need: ModelRouter.Need = ModelRouter.Need(),
        rows: Map<String, ModelCapabilityTable.Row>,
    ): ModelRouter.Plan = ModelRouter.plan(
        autoEnabled = auto,
        preference = preference,
        need = need,
        current = current,
        candidates = candidates,
        rowFor = { p -> rows[p.id] ?: ModelCapabilityTable.unknownRow(p.id, p.model) },
    )

    // ── 默认不开＝当前通道（§2 第一条，也是 §5 打回第一条的正面防线） ────────

    @Test
    fun 默认不开路由_更便宜的候选不算数_仍走当前通道() {
        val cur = provider("chan-cur")
        val cheap = provider("chan-cheap")
        val p = plan(
            candidates = listOf(cheap),
            current = cur,
            auto = false,
            rows = mapOf("chan-cheap" to rowOf(cheap, in1k = 0.01, out1k = 0.02, currency = "CNY")),
        )
        assertTrue("AUTO_ROUTE 关着必须回 Passthrough，实际 $p", p is ModelRouter.Plan.Passthrough)
        assertEquals("结果必须就是当前通道本身", cur, (p as ModelRouter.Plan.Passthrough).current)
        assertTrue("理由要说清是没开，不是没得选：${p.reason}", p.reason.contains("AUTO_ROUTE 未开启"))
    }

    @Test
    fun 不开路由时也不做能力审判_默认行为一个字节不变() {
        // 开着 auto 才谈能力过滤；关着的时候本轮该发什么还由既有通道决定（V5 的 vision 判据在组装层，
        // 不在这台路由上）。这一例钉的是「开关关掉 ⇒ 没有任何新判据生效」。
        val cur = provider("chan-cur", vision = false)
        val p = plan(
            candidates = listOf(cur),
            current = cur,
            auto = false,
            need = ModelRouter.Need(vision = true),
            rows = emptyMap(),
        )
        assertTrue("关着的时候连能力不满足都不许报 NoMatch，实际 $p", p is ModelRouter.Plan.Passthrough)
    }

    // ── 能力过滤四例（§3） ────────────────────────────────────────────────

    @Test
    fun 能力过滤之一_要发图但候选都看不了图_明确报缺不静默降级() {
        val cur = provider("chan-cur")
        val a = provider("chan-a", vision = false)
        val b = provider("chan-b", vision = false)
        val p = plan(listOf(a, b), cur, need = ModelRouter.Need(vision = true), rows = emptyMap())
        assertTrue("必须 NoMatch，实际 $p", p is ModelRouter.Plan.NoMatch)
        val nm = p as ModelRouter.Plan.NoMatch
        assertTrue("缺项里要点名看图：${nm.missing}", nm.missing.any { it.contains("看图") })
        assertFalse("不许把不合格的说成「已满足」：${nm.notice()}", nm.notice().contains("满足能力要求"))
    }

    @Test
    fun 能力过滤之一补_模型名里带vl不算依据_只看Provider_vision() {
        // 第二十四批 V5 的口径：能不能看图只读 Provider.vision，组装层不许猜模型名，路由同样不许。
        val cur = provider("chan-cur")
        val named = provider("chan-vlnamed", model = "nemotron-3.5-super-vl-free", vision = false)
        val p = plan(listOf(named), cur, need = ModelRouter.Need(vision = true), rows = emptyMap())
        assertTrue("名字带 -vl- 而 vision=false 的候选必须被刷掉，实际 $p", p is ModelRouter.Plan.NoMatch)
    }

    @Test
    fun 能力过滤之二_要原生工具但schema是UNKNOWN的候选被刷掉而不是当成支持() {
        val cur = provider("chan-cur")
        val unknown = provider("chan-unknown-tool")
        val yes = provider("chan-yes-tool")
        // 过滤例也要走到 Routed ⇒ 夹具得给排序那一维一点依据（两条同价 ⇒ 同分落回候选序，
        // 谁被选中只由能力决定）。生产表今天全 UNKNOWN ⇒ Routed 分支在生产数据上走不到，见交付报告。
        val rows = mapOf(
            "chan-unknown-tool" to rowOf(unknown, tool = ModelCapabilityTable.Support.UNKNOWN, in1k = 1.0, out1k = 2.0, currency = "CNY"),
            "chan-yes-tool" to rowOf(yes, tool = ModelCapabilityTable.Support.YES, in1k = 1.0, out1k = 2.0, currency = "CNY"),
        )
        val p = plan(listOf(unknown, yes), cur, need = ModelRouter.Need(tools = true), rows = rows)
        assertTrue("必须选有依据的那条，实际 $p", p is ModelRouter.Plan.Routed)
        assertEquals("chan-yes-tool", (p as ModelRouter.Plan.Routed).picked.id)
        // 全部 UNKNOWN ⇒ 一条都不许选
        val all = plan(listOf(unknown), cur, need = ModelRouter.Need(tools = true), rows = rows)
        assertTrue("schema 没依据不等于支持，实际 $all", all is ModelRouter.Plan.NoMatch)
        assertTrue("缺项要说清是没依据：${(all as ModelRouter.Plan.NoMatch).notice()}", all.notice().contains("没有依据"))
    }

    @Test
    fun 能力过滤之三_窗口不够就不选_窗口只按Provider声明算() {
        val cur = provider("chan-cur")
        val small = provider("chan-small", window = 200_000)
        val big = provider("chan-big", window = 1_000_000)
        val need = ModelRouter.Need(contextTokens = 300_000)
        val rows = mapOf(
            // 同价：选中谁只由「窗口够不够」决定，不由价格决定 —— 这例只钉过滤
            "chan-small" to rowOf(small, in1k = 1.0, out1k = 2.0, currency = "CNY"),
            "chan-big" to rowOf(big, in1k = 1.0, out1k = 2.0, currency = "CNY"),
        )

        val p = plan(listOf(small, big), cur, need = need, rows = rows)
        assertTrue("实际 $p", p is ModelRouter.Plan.Routed)
        assertEquals("chan-big", (p as ModelRouter.Plan.Routed).picked.id)
        val none = plan(listOf(small), cur, need = need, rows = rows)
        assertTrue("实际 $none", none is ModelRouter.Plan.NoMatch)
        assertTrue(
            "缺项里要给出要多少、这条多少：${(none as ModelRouter.Plan.NoMatch).notice()}",
            none.notice().contains("要 300000") && none.notice().contains("200000"),
        )
    }

    @Test
    fun 能力过滤之三补_没声明窗口复用既有1M回退_路由不自带第二套回退() {
        val p = provider("chan-null-window", window = null)
        val c = ModelRouter.Candidate(p, ModelCapabilityTable.unknownRow(p.id, p.model))
        assertEquals(
            "窗口回退只许住在 ContextAssembler.ContextBudgets.windowOf 那一处",
            ContextAssembler.ContextBudgets.windowOf(p),
            c.windowTokens,
        )
        assertEquals(1_048_576, c.windowTokens)
    }

    @Test
    fun 能力过滤之四_要特定思考档位_清单没依据或不含该档都算不满足() {
        val cur = provider("chan-cur")
        val noTiers = provider("chan-no-tiers")
        val otherTiers = provider("chan-other-tiers")
        val right = provider("chan-right")
        val rows = mapOf(
            "chan-no-tiers" to rowOf(noTiers, thinking = ModelCapabilityTable.Support.YES, tiers = emptyList(), in1k = 1.0, out1k = 2.0, currency = "CNY"),
            "chan-other-tiers" to rowOf(otherTiers, thinking = ModelCapabilityTable.Support.YES, tiers = listOf("low"), in1k = 1.0, out1k = 2.0, currency = "CNY"),
            "chan-right" to rowOf(right, thinking = ModelCapabilityTable.Support.YES, tiers = TIERS, in1k = 1.0, out1k = 2.0, currency = "CNY"),
        )
        val need = ModelRouter.Need(thinking = "high")
        val p = plan(listOf(noTiers, otherTiers, right), cur, need = need, rows = rows)
        assertTrue("实际 $p", p is ModelRouter.Plan.Routed)
        assertEquals("chan-right", (p as ModelRouter.Plan.Routed).picked.id)

        val unknown = plan(listOf(noTiers), cur, need = need, rows = rows)
        assertTrue(unknown is ModelRouter.Plan.NoMatch)
        assertTrue(
            "「会思考但档位清单没依据」要单独说清：${(unknown as ModelRouter.Plan.NoMatch).notice()}",
            unknown.notice().contains("档位清单没有依据"),
        )
        val missing = plan(listOf(otherTiers), cur, need = need, rows = rows)
        assertTrue(missing is ModelRouter.Plan.NoMatch)
        assertTrue(
            "清单里没有这一档要报出清单：${(missing as ModelRouter.Plan.NoMatch).notice()}",
            missing.notice().contains("清单只有 low"),
        )
        val never = plan(listOf(provider("chan-no-think")), cur, need = need, rows = mapOf(
            "chan-no-think" to rowOf(provider("chan-no-think"), thinking = ModelCapabilityTable.Support.NO),
        ))
        assertTrue(never is ModelRouter.Plan.NoMatch)
        assertTrue(
            "有依据地不支持要这么说：${(never as ModelRouter.Plan.NoMatch).notice()}",
            never.notice().contains("不认思考字段"),
        )
    }

    @Test
    fun 四条能力全缺时报错把缺项列全() {
        val cur = provider("chan-cur")
        val bad = provider("chan-bad", vision = false, window = 1000)
        val p = plan(
            listOf(bad), cur,
            need = ModelRouter.Need(vision = true, tools = true, contextTokens = 900_000, thinking = "max"),
            rows = mapOf("chan-bad" to rowOf(bad, tool = ModelCapabilityTable.Support.NO, thinking = ModelCapabilityTable.Support.NO)),
        )
        assertTrue("实际 $p", p is ModelRouter.Plan.NoMatch)
        val nm = p as ModelRouter.Plan.NoMatch
        assertEquals("看图/工具/窗口/思考四条都要点到", 4, nm.missing.size)
    }

    @Test
    fun 没有满足能力的模型时报NoMatch_绝不回落到不合格的那条() {
        val cur = provider("chan-cur")
        val bad = provider("chan-bad", vision = false)
        val p = plan(listOf(bad), cur, need = ModelRouter.Need(vision = true), rows = emptyMap())
        assertNotEquals("不许静默降级：不能因为「只有它」就选它", ModelRouter.Plan.Routed::class.java, p.javaClass)
        assertTrue(p is ModelRouter.Plan.NoMatch)
        assertTrue("理由要点明全部不合格：${(p as ModelRouter.Plan.NoMatch).reason}", p.reason.contains("全部不合格"))
    }

    // ── 排序（§3「成本/延迟排序，UNKNOWN 不参与排序、不乱猜」） ──────────────

    @Test
    fun 省钱优先_输入加输出合计最低的先上_其余按序进备选链() {
        val cur = provider("chan-cur")
        val a = provider("chan-a")
        val b = provider("chan-b")
        val c = provider("chan-c")
        val rows = mapOf(
            "chan-a" to rowOf(a, in1k = 2.0, out1k = 2.0, currency = "CNY"),
            "chan-b" to rowOf(b, in1k = 0.5, out1k = 0.5, currency = "CNY"),
            "chan-c" to rowOf(c, in1k = 1.0, out1k = 9.0, currency = "CNY"),
        )
        val p = plan(listOf(a, b, c), cur, rows = rows)
        assertTrue("实际 $p", p is ModelRouter.Plan.Routed)
        val routed = p as ModelRouter.Plan.Routed
        assertEquals("合计价最低的 b（1.0）先上", "chan-b", routed.picked.id)
        assertEquals(
            "备选链的序就是建议序：a(4.0) 之后才是 c(10.0)",
            listOf("chan-a", "chan-c"),
            routed.alternates.map { it.id },
        )
        assertTrue(
            "理由要带上排序维与被选者的行：${routed.reason}",
            routed.reason.contains("按成本") && routed.reason.contains("chan-b"),
        )
    }

    @Test
    fun 低延迟优先_P50最低的先上() {
        val cur = provider("chan-cur")
        val slow = provider("chan-slow")
        val fast = provider("chan-fast")
        val rows = mapOf(
            "chan-slow" to rowOf(slow, p50 = 4000L, p90 = 9000L),
            "chan-fast" to rowOf(fast, p50 = 800L, p90 = 1500L),
        )
        val p = plan(listOf(slow, fast), cur, preference = ModelRouter.Preference.FAST, rows = rows)
        assertTrue("实际 $p", p is ModelRouter.Plan.Routed)
        assertEquals("chan-fast", (p as ModelRouter.Plan.Routed).picked.id)
        assertTrue("理由要点名延迟维：${p.reason}", p.reason.contains("按延迟"))
    }

    @Test
    fun 质量优先_质量档数字小的先上() {
        val cur = provider("chan-cur")
        val mid = provider("chan-mid")
        val top = provider("chan-top")
        val rows = mapOf("chan-mid" to rowOf(mid, quality = 3), "chan-top" to rowOf(top, quality = 1))
        val p = plan(listOf(mid, top), cur, preference = ModelRouter.Preference.QUALITY, rows = rows)
        assertTrue("实际 $p", p is ModelRouter.Plan.Routed)
        assertEquals("chan-top", (p as ModelRouter.Plan.Routed).picked.id)
    }

    @Test
    fun UNKNOWN不参与排序_不当最便宜也不被淘汰_且写进理由() {
        val cur = provider("chan-cur")
        val priced = provider("chan-priced")
        val noPrice = provider("chan-no-price")
        val rows = mapOf("chan-priced" to rowOf(priced, in1k = 5.0, out1k = 5.0, currency = "CNY"))
        val p = plan(listOf(priced, noPrice), cur, rows = rows)
        assertTrue("实际 $p", p is ModelRouter.Plan.Routed)
        val routed = p as ModelRouter.Plan.Routed
        assertEquals(
            "有依据的那条先上；没依据的不能因为「看不见价」被当成便宜排前面",
            "chan-priced", routed.picked.id,
        )
        assertEquals("没依据的进 unranked（不是被淘汰）", listOf("chan-no-price"), routed.unranked.map { it.id })
        assertTrue(
            "理由必须把「这几条没参与比较」讲给用户看：${routed.notice()}",
            routed.notice().contains("未参与排序") && routed.notice().contains("chan-no-price"),
        )
    }

    @Test
    fun 那一维全表都没依据时排不出序_不硬选() {
        // 生产表今天就是这个形状：成本/延迟/质量三列全 UNKNOWN ⇒ 开了 auto 也只该报「排不出」
        val cur = provider("chan-cur")
        val a = provider("chan-a")
        val b = provider("chan-b")
        ModelRouter.Preference.entries.forEach { pref ->
            val p = plan(listOf(a, b), cur, preference = pref, rows = emptyMap())
            assertTrue("$pref 这一维全无依据时必须 NoMatch（不硬选），实际 $p", p is ModelRouter.Plan.NoMatch)
            val nm = p as ModelRouter.Plan.NoMatch
            assertTrue("$pref 的理由要说不硬选：${nm.notice()}", nm.notice().contains("不硬选"))
            assertTrue(nm.notice().contains("UNKNOWN"))
        }
    }

    @Test
    fun 同价时按名单序落定_不引入字典序这种假依据() {
        val cur = provider("chan-cur")
        val z = provider("zzz-last-alphabetically")
        val a = provider("aaa-first-alphabetically")
        val rows = mapOf(
            z.id to rowOf(z, in1k = 1.0, out1k = 1.0, currency = "CNY"),
            a.id to rowOf(a, in1k = 1.0, out1k = 1.0, currency = "CNY"),
        )
        val p = plan(listOf(z, a), cur, rows = rows)
        assertTrue("实际 $p", p is ModelRouter.Plan.Routed)
        assertEquals(
            "同分 ⇒ 谁在名单里靠前谁上（名单序＝调用方那套排除规则给的序），不许按 id 字典序",
            z.id, (p as ModelRouter.Plan.Routed).picked.id,
        )
    }

    @Test
    fun 只有P50没有P90也照样排_不拿缺失当最慢() {
        val cur = provider("chan-cur")
        val noP90 = provider("chan-no-p90")
        val both = provider("chan-both")
        val rows = mapOf(
            "chan-no-p90" to rowOf(noP90, p50 = 1000L),
            "chan-both" to rowOf(both, p50 = 2000L, p90 = 3000L),
        )
        val p = plan(listOf(both, noP90), cur, preference = ModelRouter.Preference.FAST, rows = rows)
        assertTrue("实际 $p", p is ModelRouter.Plan.Routed)
        assertEquals(
            "P50 才是延迟维的比较键；P90 缺失不许变成惩罚项",
            "chan-no-p90", (p as ModelRouter.Plan.Routed).picked.id,
        )
    }

    @Test
    fun rank返回的pair第二个元素就是未参与比较的候选() {
        val a = provider("chan-a")
        val b = provider("chan-b")
        val ca = ModelRouter.Candidate(a, rowOf(a, in1k = 1.0, out1k = 1.0, currency = "CNY"))
        val cb = ModelRouter.Candidate(b, rowOf(b))
        val (ranked, unranked) = ModelRouter.rank(listOf(ca, cb), ModelRouter.Preference.CHEAP)
        assertEquals(listOf(ca), ranked)
        assertEquals(listOf(cb), unranked)
        assertFalse("夹具行必须有价可排", ca.costKnown.not())
        assertEquals("1.0+1.0", 2.0, ca.pricePer1k!!, 0.0001)
        assertNullish("没依据的候选没有合计价", cb.pricePer1k)
    }

    // ── 输入解析与输出纪律 ────────────────────────────────────────────────

    @Test
    fun 偏好token往返_脏值空串null都不算偏好() {
        ModelRouter.Preference.entries.forEach { pref ->
            val k = ModelRouter.keyOf(pref)
            assertEquals("存的必须是小写 token，不是枚举 ordinal", k, k.lowercase())
            assertEquals(pref, ModelRouter.preferenceOf(k))
            assertEquals("大小写不敏感", pref, ModelRouter.preferenceOf(k.uppercase()))
        }
        listOf(null, "", "   ", "auto", "便宜", "cheap-ish").forEach { raw ->
            assertNullish("脏偏好 [$raw] 不许被猜成某一档", ModelRouter.preferenceOf(raw))
        }
    }

    @Test
    fun 开着auto但没有偏好_不猜一个偏好_报NoMatch() {
        val cur = provider("chan-cur")
        val a = provider("chan-a")
        val p = plan(listOf(a), cur, preference = null, rows = mapOf("chan-a" to rowOf(a, in1k = 1.0, out1k = 1.0, currency = "CNY")))
        assertTrue("实际 $p", p is ModelRouter.Plan.NoMatch)
        assertTrue("理由要点名「没选偏好」：${(p as ModelRouter.Plan.NoMatch).reason}", p.reason.contains("没有选偏好"))
    }

    @Test
    fun 候选名单为空时报错说清是名单空() {
        val cur = provider("chan-cur")
        val p = plan(emptyList(), cur, rows = emptyMap())
        assertTrue("实际 $p", p is ModelRouter.Plan.NoMatch)
        assertTrue("理由要点名候选为空：${(p as ModelRouter.Plan.NoMatch).reason}", p.reason.contains("候选为空"))
    }

    @Test
    fun 任何一条输出都不许出现APIKey() {
        val cur = provider("chan-cur")
        val cheap = provider("chan-cheap")
        val blind = provider("chan-blind", vision = false)
        val rows = mapOf(
            "chan-cheap" to rowOf(cheap, in1k = 1.0, out1k = 2.0, currency = "CNY"),
            "chan-blind" to rowOf(blind, tool = ModelCapabilityTable.Support.UNKNOWN),
        )
        val outputs = mutableListOf<String>()
        outputs += plan(listOf(cheap, blind), cur, auto = false, rows = rows).notice()
        outputs += plan(listOf(cheap, blind), cur, preference = null, rows = rows).notice()
        outputs += plan(listOf(cheap, blind), cur, need = ModelRouter.Need(vision = true, tools = true), rows = rows).notice()
        outputs += plan(listOf(blind), cur, rows = mapOf("chan-blind" to rowOf(blind))).notice()
        val routed = plan(listOf(cheap, blind), cur, rows = rows) as ModelRouter.Plan.Routed
        outputs += routed.notice()
        outputs += routed.reason
        outputs += ModelRouter.Preference.entries.map { ModelRouter.cnName(it) }
        outputs.forEach { s ->
            assertFalse("路由的任何话术都不许带 Key（哨兵泄漏）：$s", s.contains(SENTINEL_KEY))
            assertFalse("也不许带 apiKey 这个字段名：$s", s.contains("apiKey"))
        }
        // 直接内插一个 Provider 对象就会漏（data class 的 toString 带 apiKey）——这里证明本文件没走那条路
        assertTrue("哨兵必须真的进过候选（不然这条用例是空的）", cheap.apiKey == SENTINEL_KEY)

        assertTrue("对照：Provider 是 data class，toString() 会带出 apiKey ⇒ 门禁禁止这种写法", cur.toString().contains(SENTINEL_KEY))
    }

    @Test
    fun 备选链只收排过序的_未参与排序的另列不混进链() {
        val cur = provider("chan-cur")
        val a = provider("chan-a")
        val b = provider("chan-b")
        val c = provider("chan-c")
        val rows = mapOf(
            "chan-a" to rowOf(a, in1k = 1.0, out1k = 1.0, currency = "CNY"),
            "chan-b" to rowOf(b, in1k = 3.0, out1k = 3.0, currency = "CNY"),
            // c 没有价 ⇒ 只能进 unranked，不许混进备选链
        )
        val p = plan(listOf(a, b, c), cur, rows = rows) as ModelRouter.Plan.Routed
        assertEquals(listOf("chan-a"), listOf(p.picked.id))
        assertEquals("备选链只有 b", listOf("chan-b"), p.alternates.map { it.id })
        assertEquals("没依据的 c 单列", listOf("chan-c"), p.unranked.map { it.id })
        assertFalse("理由里备选链不许把 c 也算进去", p.reason.contains("→ chan-c"))
    }

    @Test
    fun 三档偏好各有一句人话_没有第四种说法() {
        assertEquals("成本（每 1k token 合计价）", ModelRouter.cnName(ModelRouter.Preference.CHEAP))
        assertEquals("延迟（P50）", ModelRouter.cnName(ModelRouter.Preference.FAST))
        assertEquals("质量档", ModelRouter.cnName(ModelRouter.Preference.QUALITY))
        assertEquals("偏好只有三档（派工单 §2 原文：省钱/低延迟/质量）", 3, ModelRouter.Preference.entries.size)
    }

    @Test
    fun 空需求时任何候选都算满足_缺省值不是要求() {
        val cur = provider("chan-cur")
        val a = provider("chan-a", vision = false)
        val c = ModelRouter.Candidate(a, ModelCapabilityTable.unknownRow(a.id, a.model))
        assertTrue("Need() 的缺省 = 这项没要求，不是这项不满足", ModelRouter.satisfies(c, ModelRouter.Need()))
        assertTrue(ModelRouter.missingOf(c, ModelRouter.Need()).isEmpty())
    }

    private fun assertNullish(label: String, value: Any?) {
        assertTrue("$label（实际 $value）", value == null)
    }

    private companion object {
        const val SENTINEL_KEY = "sk-SENTINEL-不许出现在任何输出"
        val TIERS = listOf("low", "high", "max")
    }
}
