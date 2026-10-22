// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单《第一百三十一批 · 模型选择 P0》③（动态拉取/显示名）＋ 11:51 补充节 §一（搜索框）
// 与 §三（顶栏公司名、接口地址中间省略、副行「公司 · 上下文」）。
//
// 为什么全在这里测：[ModelPickerRules] 是"零 Android、零 IO、零时间"的判据内核（本仓无 Robolectric），
// 抽屉只排版、不判事。这里每条断言都对应补充节里的一句硬要求。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPickerRulesTest {

    private fun entry(
        id: String,
        name: String? = null,
        window: Int? = null,
        maxOut: Int? = null,
    ) = ModelEntry(id = id, name = name, contextWindow = window, maxOutput = maxOut)

    // ── 1. ① 免费档的入口（跳转链接与话术；不许出现 403 死路） ────────────────

    @Test
    fun 两条跳转链接按派工单原文钉住() {
        assertEquals("https://openrouter.ai/settings/keys", ModelPickerRules.OPENROUTER_KEYS_URL)
        assertEquals("https://openrouter.ai/models?q=free", ModelPickerRules.OPENROUTER_FREE_MODELS_URL)
    }

    @Test
    fun 没Key时说的是去注册填Key_不是当前不可用() {
        val hint = ModelPickerRules.NEED_FREE_KEY_HINT
        assertTrue(hint.contains("OpenRouter"))
        assertTrue("要给下一步动作", hint.contains("注册") && hint.contains("Key"))
        assertFalse("红线：不许出现 403 死路字样", hint.contains("403"))
        assertTrue("搜 free 的用法是解法不是可选项",
            ModelPickerRules.SEARCH_FREE_TIP.contains("free") && ModelPickerRules.SEARCH_FREE_TIP.contains("搜索框"))
    }

    @Test
    fun 空结果那句话一字不改() {
        // 补充节 §一原文：「没有匹配的模型，可手填模型名」
        assertEquals("没有匹配的模型，可手填模型名", ModelPickerRules.SEARCH_EMPTY_NOTICE)
    }

    @Test
    fun 停手六百毫秒才拉() {
        // 重构方案 §3「停手 0.6s」；改这个数等于改交互，必须先看派工单
        assertEquals(600L, ModelPickerRules.DEBOUNCE_MS)
    }

    // ── 2. 该不该发这一次清单请求 ────────────────────────────────────────────

    @Test
    fun 没地址与非https与没Key各归一档() {
        assertEquals(ModelPickerRules.PullBlock.NO_BASE_URL, ModelPickerRules.pullBlock("", hasKey = true))
        assertEquals(ModelPickerRules.PullBlock.NO_BASE_URL,
            ModelPickerRules.pullBlock("   ", hasKey = false, openRouterPublic = true))
        assertEquals("明文 http 非环回 ⇒ 安全校验说了算（本单不改它）",
            ModelPickerRules.PullBlock.NOT_HTTPS,
            ModelPickerRules.pullBlock("http://example.com/v1", hasKey = true))
        assertEquals(ModelPickerRules.PullBlock.NO_KEY,
            ModelPickerRules.pullBlock("https://api.deepseek.com/v1", hasKey = false))
        assertNull(ModelPickerRules.pullBlock("https://api.deepseek.com/v1", hasKey = true))
        assertNull("环回 http 是单测假服务器的路 ⇒ 放行",
            ModelPickerRules.pullBlock("http://127.0.0.1:8080/v1", hasKey = true))
    }

    @Test
    fun OpenRouter的清单免Key公开_只有它能免() {
        assertTrue(ModelPickerRules.isOpenRouterPublicCatalog("https://openrouter.ai/api/v1"))
        assertFalse(ModelPickerRules.isOpenRouterPublicCatalog("https://api.deepseek.com/v1"))
        assertNull(
            "免费档没 Key 也要能拉清单（③：OpenRouter 免费档不缓存、每次重拉）",
            ModelPickerRules.pullBlock(
                baseUrl = "https://openrouter.ai/api/v1",
                hasKey = false,
                openRouterPublic = true,
            ),
        )
    }

    @Test
    fun 每一条挡回去的理由都带下一步() {
        ModelPickerRules.PullBlock.values().forEach { block ->
            listOf(true, false).forEach { free ->
                val text = ModelPickerRules.pullHint(block, free)
                assertNotNull("$block（free=$free）不许静默：要有一句人话", text)
                val t = text!!
                assertTrue("$t 要说下一步（手填或注册）", t.contains("手填") || t.contains("Key"))
                assertFalse("$t 不许把用户堵死在 403", t.contains("403") || t.contains("不可用"))
            }
        }
        assertNull("没被挡住 ⇒ 什么都不用说", ModelPickerRules.pullHint(null, freeLane = true))
        assertEquals(ModelPickerRules.NEED_FREE_KEY_HINT,
            ModelPickerRules.pullHint(ModelPickerRules.PullBlock.NO_KEY, freeLane = true))
    }

    @Test
    fun 有缓存就这一轮不用再拉_force例外() {
        val one = listOf(entry("a"))
        assertTrue(ModelPickerRules.shouldSkipFetch(one))
        assertFalse(ModelPickerRules.shouldSkipFetch(one, force = true))
        assertFalse("空表没什么可先行的", ModelPickerRules.shouldSkipFetch(emptyList()))
    }

    // ── 3. 显示名四级规则（重构方案 §2） ─────────────────────────────────────

    @Test
    fun 第1级_端点给的人类名直接用() {
        assertEquals(
            "NVIDIA: Nemotron 3.5 Super VL (free)",
            ModelPickerRules.displayName(
                "nvidia/nemotron-3.5-super-vl:free",
                "NVIDIA: Nemotron 3.5 Super VL (free)",
            ),
        )
        // 空串与纯空白不算"端点给了名字"（③：端点没给就是没给，不许当成给了）
        assertEquals("DeepSeek Chat", ModelPickerRules.displayName("deepseek-chat", "   "))
    }

    @Test
    fun 第2级_人工表认识产品定名() {
        assertEquals("GPT-4o", ModelPickerRules.displayName("gpt-4o"))
        assertEquals("DeepSeek Reasoner", ModelPickerRules.displayName("deepseek-reasoner"))
        assertEquals("Claude Opus 4.5", ModelPickerRules.displayName("claude-opus-4-5"))
        // 表是大小写不敏感查的（端点偶尔写 GPT-4O）
        assertEquals("GPT-4o", ModelPickerRules.displayName("GPT-4O"))
    }

    @Test
    fun 第3级_美化函数的确定规则() {
        // 相邻纯数字段 ⇒ 点连（4 5 → 4.5）
        assertEquals("Claude Sonnet 4.5", ModelPickerRules.displayName("claude-sonnet-4-5"))
        // 不按点切：3.5 是一个版本号，切开就成了"3 5"
        assertEquals("Nvidia Nemotron 3.5 Super VL Free",
            ModelPickerRules.displayName("nvidia/nemotron-3.5-super-vl:free"))
        // 缩写整词全大写；提供商映射认得的按产品写法
        assertEquals("GLM 5.3", ModelPickerRules.displayName("glm-5.3"))
        // 连字符规则只在**首词是缩写、第二词是纯数字**时触发（KDoc 写死的这一种形态）
        assertEquals("GPT-6 Astra", ModelPickerRules.displayName("gpt-6-astra"))
        assertEquals("厂商段不是缩写 ⇒ 不触发连字符",
            "OpenAI GPT 6 Astra", ModelPickerRules.displayName("openai/gpt-6-astra"))
        // 8 位纯数字段＝发布日期，不配占显示名
        assertEquals("Kimi K2", ModelPickerRules.prettify("kimi-k2-20250929"))
    }

    @Test
    fun 第4级_实在认不出来才让ID露面() {
        // 没有任何可美化的词 ⇒ 原样回吐（这是 ID 允许露面的唯一一级）
        assertEquals("…§§…", ModelPickerRules.displayName("…§§…"))
        assertEquals("", ModelPickerRules.prettify(""))
    }

    @Test
    fun 人工表每条都比原样回吐更有人样() {
        // 表是第 2 级，作用是把**产品定名**（GPT-4o）钉住；它只覆盖"叫什么"，
        // 不覆盖能力/窗口——所以它不是第二真相，但表里也不许塞"原样回吐"的条目（那是白占位）。
        assertTrue("表别长大：只收美化函数推不出来的产品定名",
            ModelPickerRules.CURATED_NAMES.size <= 10)
        ModelPickerRules.CURATED_NAMES.forEach { (id, human) ->
            assertEquals("键一律小写（查表按小写比）", id.lowercase().trim(), id)
            assertTrue("「$id」这条与 ID 一模一样 ⇒ 白占位，删掉", human != id)
            assertTrue("「$id」的值要有实际美化（含空格或大小写变化）",
                human.any { c -> c.isWhitespace() } || human != human.lowercase())
        }
    }

    // ── 4. 搜索框（输入即过滤；同时比 ID 与显示名） ───────────────────────────

    @Test
    fun 搜free筛出全部免费模型() {
        val all = listOf(
            entry("nvidia/nemotron-3.5-super-vl:free"),
            entry("deepseek/deepseek-v4.1-flash"),
            entry("meta-llama/llama-3.3-70b-instruct:free", "Llama 3.3 70B (free)"),
        )
        val free = ModelPickerRules.filter("free", all)
        assertEquals(listOf("nvidia/nemotron-3.5-super-vl:free", "meta-llama/llama-3.3-70b-instruct:free"),
            free.map { it.id })
        assertEquals("保持原序，不重排", 2, free.size)
    }

    @Test
    fun 搜显示名也能命中_因为界面上摆的就是名字() {
        val all = listOf(entry("nvidia/nemotron-3.5-super-vl:free"))
        assertTrue(ModelPickerRules.matches("Nemotron", all[0]))
        assertTrue("大小写不敏感", ModelPickerRules.matches("NVIDIA nemotron 3.5", all[0]))
        assertFalse(ModelPickerRules.matches("sonnet", all[0]))
        assertEquals("空词＝全表", all, ModelPickerRules.filter("   ", all))
        assertTrue("搜不中就是空表 ⇒ 抽屉那句『没有匹配的模型，可手填模型名』",
            ModelPickerRules.filter("zzz-no-such-model", all).isEmpty())
    }

    // ── 5. 顶栏与抽屉里的三段小字 ────────────────────────────────────────────

    @Test
    fun 上下文角标按截断换算_没有元数据就空串() {
        assertEquals("1M", ModelPickerRules.contextBadge(1_048_576))
        assertEquals("1M", ModelPickerRules.contextBadge(1_000_000))
        assertEquals("1.5M", ModelPickerRules.contextBadge(1_500_000))
        assertEquals("32K", ModelPickerRules.contextBadge(32_768))
        assertEquals("131K", ModelPickerRules.contextBadge(131_072))
        assertEquals("1K", ModelPickerRules.contextBadge(1_000))
        assertEquals("999", ModelPickerRules.contextBadge(999))
        assertEquals("没声明 ⇒ 空串（不编数）", "", ModelPickerRules.contextBadge(null))
    }

    @Test
    fun 能力行缺哪头哪头写未声明() {
        assertEquals("上下文 1M · 输出 131K", ModelPickerRules.capabilityLine(1_048_576, 131_072))
        assertEquals("上下文 1M · 输出 未声明", ModelPickerRules.capabilityLine(1_048_576, null))
        assertEquals("上下文 未声明 · 输出 未声明", ModelPickerRules.capabilityLine(null, null))
        assertEquals("未声明", ModelPickerRules.UNDECLARED)
    }

    @Test
    fun 副行是公司加上下文_ID不上这一行() {
        val withVendor = entry("nvidia/nemotron-3.5-super-vl:free", window = 1_000_000)
        assertEquals("Nvidia · 上下文 1M", ModelPickerRules.modelSubLine(withVendor, "OpenRouter"))
        val noVendor = entry("deepseek-chat", window = 128_000)
        assertEquals("自家端点没写厂商前缀 ⇒ 回退提供商名",
            "DeepSeek · 上下文 128K", ModelPickerRules.modelSubLine(noVendor, "DeepSeek"))
        assertFalse(ModelPickerRules.modelSubLine(withVendor, "OpenRouter").contains("nemotron"))
        assertEquals("Nvidia", ModelPickerRules.vendorOf("nvidia/nemotron-3.5-super-vl:free"))
        assertNull(ModelPickerRules.vendorOf("deepseek-chat"))
        assertNull("斜杠打头没有厂商段", ModelPickerRules.vendorOf("/x"))
    }

    @Test
    fun 接口地址中间省略保头保尾() {
        assertEquals("短的不动它", "https://api.deepseek.com/v1",
            ModelPickerRules.middleEllipsis("https://api.deepseek.com/v1", 34))
        val long = "https://generativelanguage.googleapis.com/v1beta"
        val cut = ModelPickerRules.middleEllipsis(long, 34)
        assertEquals("头 2/3 ＋省略号＋尾 1/3 ⇒ 正好 maxChars", 34, cut.length)
        assertTrue(cut.contains("…"))
        // 实际读数（头 22 + … + 尾 11）：域名尾巴与路径都在，中间那段被折掉。
        // 这条钉的是**算法**，不是"看着顺眼"——改算法就得回来看补充节 §三那句「保留 host」。
        assertEquals("https://generativelang….com/v1beta", cut)
        assertTrue("尾部保留（路径/域名尾巴是用户认地址的地方）", cut.endsWith("/v1beta"))
        assertEquals("maxChars<=1 ⇒ 不许裁成只剩省略号", long, ModelPickerRules.middleEllipsis(long, 1))
        assertEquals("单字符也照样返回整串", "abc", ModelPickerRules.middleEllipsis("abc", 1))
    }

    @Test
    fun 顶栏只放公司名_免费二字归抽屉() {
        assertEquals("OpenRouter", ModelPickerRules.companyOf(Providers.OPENROUTER_FREE))
        assertEquals("OpenRouter", ModelPickerRules.companyOf(Providers.OPENROUTER))
        assertEquals("DeepSeek", ModelPickerRules.companyOf(Providers.DEEPSEEK))
        assertEquals("OpenAI", ModelPickerRules.companyOf(Providers.OPENAI_COMPAT))
        assertEquals("Anthropic", ModelPickerRules.companyOf(Providers.ANTHROPIC))
        Providers.all().forEach { p ->
            val c = ModelPickerRules.companyOf(p)
            assertTrue("$c 不许带'免费'（那一格只放公司）", !c.contains("免费"))
            assertTrue("$c 不许是空串", c.isNotBlank())
            assertTrue("$c 不许是 URL", !c.contains("://"))
        }
    }

    @Test
    fun 自定义的公司名从host美化() {
        assertEquals("DeepSeek", ModelPickerRules.companyOfHost("https://api.deepseek.com/v1"))
        assertEquals("OpenRouter", ModelPickerRules.companyOfHost("https://openrouter.ai/api/v1"))
        assertEquals("OpenAI", ModelPickerRules.companyOfHost("https://api.openai.com/v1"))
        assertEquals("Anthropic", ModelPickerRules.companyOfHost("https://api.anthropic.com/v1"))
        assertEquals("认不出来的公司不猜 ⇒ 首字母大写那一段", "Myhost",
            ModelPickerRules.companyOfHost("https://myhost.example.cn/v1"))
        assertEquals("自定义", ModelPickerRules.companyOfHost(""))
        val custom = Provider(
            id = ProviderPrefs.CHANNEL_CUSTOM, label = "自定义通道",
            protocol = LlmProtocol.OPENAI_COMPAT, baseUrl = "https://api.deepseek.com/v1",
            model = "deepseek-chat", apiKey = "sk-user",
        )
        assertEquals("DeepSeek", ModelPickerRules.companyOf(custom))
    }

    // ── 6. 预设上选定的模型怎么落存储 ────────────────────────────────────────

    @Test
    fun 覆盖格编解码往返() {
        val o = ModelPickerRules.ModelOverride("deepseek/deepseek-v4.1-flash", 1_048_576, 65_536)
        val text = ModelPickerRules.encodeOverride(o)
        assertEquals(o, ModelPickerRules.decodeOverride(text))
        // 元数据没给 ⇒ 只存名字，不补数
        val bare = ModelPickerRules.ModelOverride("gpt-4o")
        assertEquals(bare, ModelPickerRules.decodeOverride(ModelPickerRules.encodeOverride(bare)))
    }

    @Test
    fun 空与脏的覆盖一律当作没覆盖() {
        assertEquals("null ⇒ 空串（薄壳据此删格）", "", ModelPickerRules.encodeOverride(null))
        assertEquals("", ModelPickerRules.encodeOverride(ModelPickerRules.ModelOverride("   ")))
        assertNull(ModelPickerRules.decodeOverride(null))
        assertNull(ModelPickerRules.decodeOverride(""))
        assertNull("脏 JSON ⇒ null，不猜模型名", ModelPickerRules.decodeOverride("{not json"))
        assertNull(ModelPickerRules.decodeOverride("""{"m":"  "}"""))
        assertNull("0 或负数＝没声明，不许折成可用值",
            ModelPickerRules.decodeOverride("""{"m":"x","c":0,"o":-1}""")?.contextWindow)
        assertEquals("x", ModelPickerRules.decodeOverride("""{"m":"x","c":0,"o":-1}""")!!.id)
    }

    @Test
    fun 换了地址旧缓存就作废() {
        assertTrue(ModelPickerRules.cacheUsable("https://a/v1", "https://a/v1"))
        assertTrue("首尾空格不算换地址", ModelPickerRules.cacheUsable("https://a/v1 ", " https://a/v1"))
        assertFalse(ModelPickerRules.cacheUsable(null, "https://a/v1"))
        assertFalse(ModelPickerRules.cacheUsable("", "https://a/v1"))
        assertFalse("换了提供商 ⇒ 旧表不许顶新表",
            ModelPickerRules.cacheUsable("https://a/v1", "https://b/v1"))
    }
}
