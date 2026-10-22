// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单《第一百三十一批 · 模型选择 P0》③ ＋ 11:51 补充节 §三，
// 上游《抱朴设置重构方案-2026-10-22》§1（命名）、§2（显示名四级规则）、§3（拉取规则）。
//
// 为什么单独一个文件（而不是塞进 ModelCatalog / ProviderPrefs）：
// 本仓无 Robolectric ⇒ 抽屉里每一条"该显示什么、该不该拉"的判据都必须能脱离
// Compose 与 Context 在 JVM 里算出来。这个文件就是那批判据：**零 Android、零 IO、零时间**。
// 网络留在 [ModelCatalog]，存储留在 [ProviderPrefs]，排版留在 `ui/baopu/BaopuChannelSheet.kt`。
package com.example.zhengdao.baopu

/**
 * 选模型抽屉的纯判据（第一百三十一批）。
 *
 * 命名纪律（补充节 §1）：面向用户**没有"通道"这个词**——这里产出的每一句话
 * 都说"模型 / 提供商 / 接口地址"。技术注释（KDoc）里仍说"通道"，那是协议语义，不改。
 */
internal object ModelPickerRules {

    // ── ① 免费档的入口（派工单给的两条 URL；跳玄览用，别在 UI 里各抄一份） ──

    /** 注册/拿 Key 的页面（用户在玄览里自己登录，App 不代填、不读剪贴板）。 */
    const val OPENROUTER_KEYS_URL = "https://openrouter.ai/settings/keys"

    /** 免费模型清单页（搜 `free` 那个交互的网页版）。 */
    const val OPENROUTER_FREE_MODELS_URL = "https://openrouter.ai/models?q=free"

    /** 没填 Key 时给的那句话（**不许出现 403 死路**——补充节：拉不到/没 key 时提示去注册填 Key）。 */
    const val NEED_FREE_KEY_HINT =
        "还没有 Key：去 OpenRouter 注册一个，把 Key 填在下面就能用（限额见下一行）。"

    /** 搜 `free` 的用法（② 重构方案 §2：这是"免费档选哪几个模型"的解法，不是可选项）。 */
    const val SEARCH_FREE_TIP = "在下面的搜索框里输入 free，就能筛出全部免费模型"

    /** 搜索框空结果的话（补充节 §一硬要求，原文一字不改）。 */
    const val SEARCH_EMPTY_NOTICE = "没有匹配的模型，可手填模型名"

    /**
     * 第一百三十六批：自定义 Anthropic 没有 `/v1/models`（Claude 那家不提供清单接口）⇒
     * 清单段不许硬拉，直接把「手填」这句话摆出来。
     */
    const val ANTHROPIC_MANUAL_MODEL_HINT = "该协议需手填模型名"

    // ── ③ 拉取时机 ────────────────────────────────────────────────────────

    /** 停手多久才拉（重构方案 §3「停手 0.6s」；沿用自定义那头的既有数值）。 */
    const val DEBOUNCE_MS = 600L

    /** 「这会儿不该发清单请求」的三种理由（枚举而不是句子：UI 要换措辞，判据不许跟着改字）。 */
    enum class PullBlock { NO_BASE_URL, NOT_HTTPS, NO_KEY }

    /**
     * 该不该发这一次清单请求（纯判据）。
     *
     * **不含"已有缓存"那一档**：缓存先行是调用方的事——它喂
     * `ModelCatalogCache.cached(baseUrl, neverCache)`，免费档传 true ⇒ 永远拿到空表 ⇒ 必然重拉；
     * 其余提供商拿到非空表 ⇒ 用 [shouldSkipFetch] 就地收尾，根本走不到这里。
     * 判据分两家（"能不能拉" vs "值不值得再拉"）是为了各自都能单独测。
     *
     * @param baseUrl 非空且 https 才有意义
     * @param hasKey 这条提供商此刻有没有 Key
     * @param openRouterPublic OpenRouter 的清单免 Key 公开（② 实锤），只有它能免 Key 拉
     * @return null = 该拉；非 null = 不拉的理由
     */
    fun pullBlock(
        baseUrl: String,
        hasKey: Boolean,
        openRouterPublic: Boolean = false,
    ): PullBlock? = when {
        baseUrl.isBlank() -> PullBlock.NO_BASE_URL
        !Provider.isPermittedBaseUrl(baseUrl) -> PullBlock.NOT_HTTPS
        !hasKey && !openRouterPublic -> PullBlock.NO_KEY
        else -> null
    }

    /** 手头的清单够不够顶这一轮（③「拉取中缓存先行」；force = 用户点了刷新）。 */
    fun shouldSkipFetch(cached: List<ModelEntry>, force: Boolean = false): Boolean =
        !force && cached.isNotEmpty()

    /**
     * 「为什么没拉」的人话（补充节 §一与派工单 ① 的落点：**不许出现 403 死路**）。
     *
     * 免费档没 Key ⇒ 说的是"去 OpenRouter 注册填 Key"＋跳转（[NEED_FREE_KEY_HINT]）；
     * 话里每一句都带下一步能干什么，没有一句停在"当前不可用"。
     * null = 没被挡住 ⇒ UI 什么都不用说。
     */
    fun pullHint(block: PullBlock?, freeLane: Boolean): String? = when {
        block == null -> null
        block == PullBlock.NO_KEY && freeLane -> NEED_FREE_KEY_HINT
        block == PullBlock.NO_KEY -> "填上这把模型的 Key 才拉得出清单；也可以直接在下面手填模型名。"
        block == PullBlock.NO_BASE_URL -> "先填接口地址，清单会自动拉出来；也可以直接手填模型名。"
        block == PullBlock.NOT_HTTPS -> "接口地址要 https 的；也可以直接手填模型名。"
        else -> null
    }

    /** 该提供商的清单是不是免 Key 公开端点（只有 OpenRouter 系）。 */
    fun isOpenRouterPublicCatalog(baseUrl: String): Boolean =
        baseUrl.trim().lowercase().contains("openrouter.ai")

    // ── 显示名四级规则（重构方案 §2；UI 只显示名，ID 只出现在手填框与错误日志里） ──

    /**
     * 第 2 级「人工常见表」。
     *
     * 为什么要有它：美化函数造不出 `GPT-4o` 这种**产品定名**（它会写 "GPT 4o"），
     * 而这几条是用户张口就来的名字，维护成本几乎为零。
     * 只收"美化函数推不出来"的那些，别把能推的也塞进来（那会变成第二真相表）。
     */
    val CURATED_NAMES: Map<String, String> = mapOf(
        "claude-opus-4-5" to "Claude Opus 4.5",
        "gpt-4o" to "GPT-4o",
        "gemini-2.5-pro" to "Gemini 2.5 Pro",
        "deepseek-chat" to "DeepSeek Chat",
        "deepseek-reasoner" to "DeepSeek Reasoner",
    )

    /** 第 3 级里的"已知提供商映射"（重构方案点名的四个）＋大小写固定的写法。 */
    private val PROVIDER_WORDS: Map<String, String> = mapOf(
        "openai" to "OpenAI",
        "anthropic" to "Anthropic",
        "google" to "Google",
        "moonshot" to "Moonshot",
        "deepseek" to "DeepSeek",
    )

    /** 第 3 级里的缩写：整词命中 ⇒ 全大写（`glm`→GLM、`vl`→VL）。 */
    private val ACRONYMS: Set<String> = setOf("glm", "gpt", "vl", "ocr", "api", "ai", "tts", "r1")

    /** 8 位纯数字段＝发布日期（`-20250929`），显示名里不配占位。 */
    private val DATE_SEGMENT = Regex("^\\d{8}$")

    /**
     * 第 3 级「美化函数」（确定规则，逐条可测）：
     * 分词（`-` `/` `:` 空格；**不按 `.` 切**——`5.3` 是一个版本号，切开就变成"5 3"）
     * → 丢 8 位日期段 → 提供商映射 → 缩写全大写 → 其余词首字母大写
     * → **相邻纯数字段用点连接**（`opus 5 5`→Opus 5.5）
     * → **缩写词后面紧跟纯数字段用连字符**（`gpt 6 astra`→GPT-6 Astra；
     *   `glm 5.3` 不触发，因为 `5.3` 不是纯数字段 ⇒ "GLM 5.3"）。
     */
    fun prettify(id: String): String {
        val raw = id.trim()
        if (raw.isEmpty()) return raw
        val parts = raw.split(Regex("[-/:\\s]+")).filter { it.isNotEmpty() && !DATE_SEGMENT.matches(it) }
        if (parts.isEmpty()) return raw
        val words = parts.map { token ->
            val lower = token.lowercase()
            when {
                lower in PROVIDER_WORDS -> PROVIDER_WORDS.getValue(lower)
                lower in ACRONYMS -> lower.uppercase()
                token.all { it.isDigit() } -> token
                else -> token.replaceFirstChar { c -> c.uppercaseChar() }
            }
        }
        // 相邻纯数字段 ⇒ 点连（`5` `5` → `5.5`）
        val merged = mutableListOf<String>()
        var i = 0
        while (i < words.size) {
            if (words[i].all { c -> c.isDigit() }) {
                var j = i
                val run = StringBuilder(words[i])
                while (j + 1 < words.size && words[j + 1].all { c -> c.isDigit() }) {
                    j++
                    run.append('.').append(words[j])
                }
                merged += run.toString()
                i = j + 1
            } else {
                merged += words[i]
                i++
            }
        }
        // 缩写 + 纯数字 ⇒ 连字符（GPT-6 Astra）。只对**首词是缩写、第二词是纯数字**这一种形态生效，
        // 别处不猜（`glm-5.3` 的第二段是 `5.3`，不是纯数字 ⇒ 不触发 ⇒ "GLM 5.3"）。
        val head = merged.firstOrNull().orEmpty()
        val second = merged.getOrNull(1).orEmpty()
        val acronymHead = ACRONYMS.any { it.uppercase() == head }
        return if (acronymHead && second.isNotEmpty() && second.all { c -> c.isDigit() }) {
            (listOf("$head-$second") + merged.drop(2)).joinToString(" ")
        } else {
            merged.joinToString(" ")
        }
    }

    /**
     * 显示名（四级按序取，取到即止）：
     * 1. 端点给的人类名（OpenRouter `name`／Anthropic `display_name`／Gemini `displayName`）；
     * 2. [CURATED_NAMES]；
     * 3. [prettify]；
     * 4. 原始 ID（**只有这一种情况允许 ID 露面**）。
     */
    fun displayName(id: String, catalogName: String? = null): String {
        catalogName?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        CURATED_NAMES[id.trim().lowercase()]?.let { return it }
        val pretty = prettify(id)
        // 美化函数什么都没认出来（原样回吐）⇒ 走第 4 级，但仍然是同一个串，不必特判
        return if (pretty.isBlank()) id else pretty
    }

    /** 显示名（清单行形态：ModelEntry 直接进来）。 */
    fun displayName(entry: ModelEntry): String = displayName(entry.id, entry.name)

    // ── 搜索框（补充节 §一：输入即过滤；这是"免费档选哪几个模型"的解法） ──────

    /**
     * 一条模型是否命中搜索词。
     *
     * **同时比 ID 与显示名**：`:free` 后缀只在 ID 里（`nvidia/nemotron-3.5-super-vl:free`），
     * 只比显示名就搜不出免费模型；而 UI 上显示的是名字，所以两边都得能命中。
     */
    fun matches(query: String, entry: ModelEntry): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        return entry.id.lowercase().contains(q) || displayName(entry).lowercase().contains(q)
    }

    /** 过滤（保持原序；空词＝全表）。 */
    fun filter(query: String, entries: List<ModelEntry>): List<ModelEntry> =
        if (query.isBlank()) entries else entries.filter { matches(query, it) }

    // ── 顶栏/抽屉要显示的两小段 ────────────────────────────────────────────

    /**
     * 上下文角标（补充节：公司名后跟 10sp 小字 `1M`；没有元数据 ⇒ **空串**，不编）。
     *
     * 单位换算按"截断"不四舍五入：1_048_576 → 1M、32_768 → 32K（重构方案给的读数就是这两个）。
     */
    fun contextBadge(tokens: Int?): String {
        val v = tokens ?: return ""
        return when {
            v >= 1_000_000 -> {
                val tenth = v / 100_000 // 截断到 0.1M
                if (tenth % 10 == 0) "${tenth / 10}M" else "${tenth / 10}.${tenth % 10}M"
            }
            v >= 1_000 -> "${v / 1_000}K"
            else -> v.toString()
        }
    }

    /** 能力标识行（重构方案 §2「不要具体数值，只要 1M 这种」；缺哪头哪头写「—」）。 */
    fun capabilityLine(contextWindow: Int?, maxOutput: Int?): String =
        "上下文 ${contextBadge(contextWindow).ifEmpty { UNDECLARED }} · " +
            "输出 ${contextBadge(maxOutput).ifEmpty { UNDECLARED }}"

    /** 「没声明」的显示字（与设置页 `baopu_params_undeclared` 同口径）。 */
    const val UNDECLARED = "未声明"

    /**
     * 模型 ID 里的厂商段（OpenRouter 的 ID 语法：`nvidia/nemotron-3.5-super-vl:free` → NVIDIA 系）。
     *
     * 只认**斜杠前面那一段**（这是端点事实，② 实测清单里每一条都这么写），没有斜杠 ⇒ null，
     * 由调用方回退到提供商名——DeepSeek 自家端点上的 `deepseek-chat` 不带厂商前缀，
     * 那是它本来就没写，不是本仓漏拉。
     */
    fun vendorOf(id: String): String? {
        val raw = id.trim()
        val slash = raw.indexOf('/')
        if (slash <= 0) return null
        return prettify(raw.substring(0, slash)).ifBlank { null }
    }

    /**
     * 模型行副行（补充节 §三：`公司 · 上下文`，13sp 次墨）。
     *
     * 上下文没声明 ⇒ 「未声明」（与设置页同口径，**不许编数**）；这一行里**没有模型 ID**。
     */
    fun modelSubLine(entry: ModelEntry, company: String): String =
        (vendorOf(entry.id) ?: company) +
            " · 上下文 " + contextBadge(entry.contextWindow).ifEmpty { UNDECLARED }

    /**
     * 接口地址**中间省略**（补充节 §三：尾部省略会把最有辨识度的域名吃掉）。
     *
     * 保留头 2/3、尾 1/3，中间一个 `…`。纯字符计算 ⇒ 字号/字体怎么变都不破这条判据
     * （真要按像素裁是排版层的事，本批不做——没有像素测量设施）。
     */
    fun middleEllipsis(text: String, maxChars: Int): String {
        val t = text.trim()
        if (maxChars <= 1 || t.length <= maxChars) return t
        // 至少要给头尾各留一位，否则"省略"就变成"只剩省略号"
        val head = ((maxChars - 1) * 2 / 3).coerceAtLeast(1)
        val tail = (maxChars - 1 - head).coerceAtLeast(1)
        return t.take(head) + "…" + t.takeLast(tail)
    }

    /**
     * 公司名（顶栏那一颗；补充节：预设直接用预设名，自定义取 host 美化）。
     *
     * 预设名要砍掉"免费"这半截——顶栏那一格只放**公司**，型号与"免费"都归跑马灯/抽屉。
     */
    fun companyOf(provider: Provider): String {
        if (provider.id == ProviderPrefs.CHANNEL_CUSTOM) return companyOfHost(provider.baseUrl)
        return provider.label.removeSuffix(" 免费").trim()
    }

    /**
     * 从接口地址美化出公司名：`api.deepseek.com` → DeepSeek。
     *
     * 判据只有一条：**去掉 host 前缀噪音后取第一段**，再查提供商映射。
     * 认不出来 ⇒ 首字母大写的那一段（不猜公司，也不显示整串地址——那是地址不是名字）。
     */
    fun companyOfHost(baseUrl: String): String {
        val host = baseUrl.trim()
            .removePrefix("https://").removePrefix("http://")
            .substringBefore('/').substringBefore(':')
        if (host.isEmpty()) return "自定义"
        val labels = host.split('.').filter { it.isNotBlank() }
        val meaningful = labels.filterNot { it == "api" || it == "www" || it == "com" || it == "cn" }
        val probe = (meaningful.ifEmpty { labels }).joinToString(".") { it.lowercase() }
        PROVIDER_WORDS.entries.firstOrNull { probe.contains(it.key) }?.let { return it.value }
        if (probe.contains("openrouter")) return "OpenRouter"
        if (probe.contains("moonshot")) return "Moonshot"
        val first = (meaningful.ifEmpty { labels }).firstOrNull().orEmpty()
        return first.replaceFirstChar { c -> c.uppercaseChar() }.ifEmpty { "自定义" }
    }

    // ── 预设模型的覆盖存储（③「预设也拉」之后，选一个模型要能落下来） ──────────

    /**
     * 某个预设被用户选定的模型（连同那一刻拿到的元数据）。
     *
     * 三个字段一起存是刻意的：只存模型名而留着**上一个模型**的窗口数＝假数据
     * （ContextBudget 会照那个数裁上下文）。端点没给元数据 ⇒ null ⇒ 上层按"未声明"走，
     * 与自定义通道同一条回退路（[Provider.contextWindow] 的既有口径）。
     */
    data class ModelOverride(val id: String, val contextWindow: Int? = null, val maxOutput: Int? = null)

    /** 编码：`{"m":"…","c":123,"o":456}`；null/空 id ⇒ 空串（薄壳据此**删格**）。 */
    fun encodeOverride(override: ModelOverride?): String {
        val o = override ?: return ""
        if (o.id.isBlank()) return ""
        return org.json.JSONObject().apply {
            put("m", o.id)
            o.contextWindow?.let { put("c", it) }
            o.maxOutput?.let { put("o", it) }
        }.toString()
    }

    /** 解码：脏数据 ⇒ null（当作"没覆盖"，**不猜模型名**）。 */
    fun decodeOverride(stored: String?): ModelOverride? {
        val text = stored?.trim().orEmpty()
        if (text.isEmpty()) return null
        return runCatching {
            val o = org.json.JSONObject(text)
            val id = o.optString("m").trim()
            if (id.isEmpty()) return null
            ModelOverride(
                id = id,
                contextWindow = if (o.has("c") && !o.isNull("c")) o.optInt("c").takeIf { it > 0 } else null,
                maxOutput = if (o.has("o") && !o.isNull("o")) o.optInt("o").takeIf { it > 0 } else null,
            )
        }.getOrNull()
    }

    /**
     * 缓存里的一批条目该不该丢（③「拉取中缓存先行」的另一半：换了提供商/地址就不能拿旧表）。
     *
     * 判据是"这份缓存是为哪个地址拉的"——地址变了（含自定义改了 host）⇒ 旧表作废。
     */
    fun cacheUsable(cachedBase: String?, baseUrl: String): Boolean =
        !cachedBase.isNullOrBlank() && cachedBase.trim() == baseUrl.trim()
}
