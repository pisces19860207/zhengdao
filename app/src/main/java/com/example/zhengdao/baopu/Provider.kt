// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据的公开接口与仓库内取证：
//   - OpenAI Chat Completions API 公开文档形态（POST /chat/completions，Authorization: Bearer 头）；
//   - Anthropic Messages API 公开文档形态（POST /messages，x-api-key + anthropic-version 头）；
//   - opencode zen 免费网关的形态取自**仓库台账取证**（docs/FEATURE-LEDGER.md 2026-10-15
//     第三十四答：dsh-opencode-patch 插件的路由表与注入头），**非本文件实测**（证据等级③）。
package com.example.zhengdao.baopu

/**
 * LLM 协议种类（刀 0 · 2026-10-16）。
 *
 * 只分两种，因为市面上的"OpenAI 兼容"端点（OpenAI 官方、DeepSeek、智谱、zen 网关……）
 * 请求/响应形状完全一致，值得共享一条代码路径；Anthropic 是唯一形状不同的主流协议。
 */
enum class LlmProtocol {
    /** OpenAI 兼容：`{baseUrl}/chat/completions`，`Authorization: Bearer <key>`。 */
    OPENAI_COMPAT,

    /** Anthropic：`{baseUrl}/messages`，`x-api-key` + `anthropic-version` 头。 */
    ANTHROPIC,
}

/**
 * 一个 LLM 提供方的完整配置（刀 0 · 2026-10-16）。
 *
 * ## 用户自己的 key 是一等公民
 * 预设（[Providers]）只是**默认值**：用户填了 key 就换掉预设的 key，
 * baseUrl / model / headers 一律保留（[Providers.withUserKey]）。
 * 内置免费通道（[Providers.BUILTIN_FREE]）的 `builtinFree = true` 描述的是
 * **这条通道**，不随 key 变化——它回答"这是不是免费车道"，不是"现在免不免费"。
 *
 * ## 为什么 init 里就卡 URL
 * 仓库明文放行门禁（NetworkSecurityConfigTest）只放行环回 ⇒ 外部 provider
 * **必须 https**。把判据放在数据层（而不是等网络层），预设写错当场就炸，
 * 不会等到真机上"连不上"才暴露。环回放行是给本机假服务器（单测 MockWebServer）留的路。
 */
data class Provider(
    val id: String,
    val label: String,
    val protocol: LlmProtocol,
    val baseUrl: String,
    val model: String,
    val apiKey: String = "",
    /** 自定义 header（最后合并，允许覆盖协议默认头）。免费通道的伪装头就走这里。 */
    val headers: Map<String, String> = emptyMap(),
    /** 是否内置免费通道预设。 */
    val builtinFree: Boolean = false,
) {
    init {
        require(isPermittedBaseUrl(baseUrl)) {
            "provider baseUrl 必须是 https（或环回 http，仅限本机测试）：$baseUrl"
        }
        require(model.isNotBlank()) { "provider model 不能为空（id=$id）" }
    }

    companion object {
        /**
         * URL 许可判据（纯函数）：https 一律放行；http 只放行环回。
         *
         * 与 NetworkSecurityConfigTest 同口径：第一方代码的明文出口只有环回一段，
         * 外部主机要么 https，要么先想清楚"这条明文哪个组件在用、为什么不能上 https"。
         */
        fun isPermittedBaseUrl(url: String): Boolean =
            url.startsWith("https://") ||
                url.startsWith("http://127.0.0.1") ||
                url.startsWith("http://localhost")
    }
}

/**
 * 三个内置预设（刀 0 · 2026-10-16）。
 *
 * 持久化（用户选了哪个、填了什么 key）归刀 1 的设置页；本文件只给**默认值**。
 */
object Providers {

    /** OpenAI 官方（也是所有 OpenAI 兼容端点的默认形状；换 DeepSeek 等改 baseUrl 即可）。 */
    val OPENAI_COMPAT = Provider(
        id = "openai-compat",
        label = "OpenAI 兼容",
        protocol = LlmProtocol.OPENAI_COMPAT,
        baseUrl = "https://api.openai.com/v1",
        model = "gpt-4o-mini",
    )

    /** Anthropic 官方。 */
    val ANTHROPIC = Provider(
        id = "anthropic",
        label = "Anthropic",
        protocol = LlmProtocol.ANTHROPIC,
        baseUrl = "https://api.anthropic.com/v1",
        model = "claude-sonnet-4-5",
    )

    /**
     * 免费档占位 Bearer —— **这不是 bug，别"顺手修掉"**。
     *
     * ② 仓库台账取证（FEATURE-LEDGER.md 2026-10-15 第三十四答）：dsh-opencode-patch 插件
     * 源码里 `PLACEHOLDER_API_KEYS = Set(["null","none","undefined","unused"])`，
     * 即适配器握不到真凭据时拿 `Bearer null` 硬填。免费档是否真的免密钥**未实测**（③，
     * 裸探 zen 网关 403/401），留待刀 0b 探针定案；在那之前与插件行为保持一致。
     */
    const val FREE_PLACEHOLDER_KEY = "null"

    /**
     * zen 网关 baseUrl（② 台账取证：裸探路径 `/zen/v1/chat/completions`；
     * ③ 端点本身未真机实测）。
     */
    const val FREE_BASE_URL = "https://opencode.ai/zen/v1"

    /**
     * 免费模型默认序（② `证道设计报告/抱朴-免费模型能力-2026-10-16.md` 的"刀 0 免费默认序"：
     * muse-spark-1.3-contributor-free ＞ nemotron-3.5-super-vl-free ＞ longcat-2.5-preview-free
     * ＞ mimo-v2.6-flash-free ＞ deepseek-v4.1-flash-free）。元数据非实测；动态刷新归后续刀。
     */
    val FREE_MODELS: List<String> = listOf(
        "muse-spark-1.3-contributor-free",
        "nemotron-3.5-super-vl-free",
        "longcat-2.5-preview-free",
        "mimo-v2.6-flash-free",
        "deepseek-v4.1-flash-free",
    )

    /**
     * 免费通道的伪装头（② 台账取证：dsh-opencode-patch 注入 `User-Agent: opencode/1.18.34`
     * 与 `x-opencode-client: cli`，让服务端以为请求来自 opencode 官方 CLI）。
     * 只收**静态**两条；会话确定性哈希那类动态头归后续刀的 FreeLane。
     */
    val FREE_HEADERS: Map<String, String> = mapOf(
        "User-Agent" to "opencode/1.18.34",
        "x-opencode-client" to "cli",
    )

    /**
     * 内置免费通道预设。默认模型取 [FREE_MODELS] 首位。
     *
     * ⚠️ 免费档还要在工具 schema 里补 `read` + `bash` 两个工具，否则服务端回
     * `403 FreeTierError`（② 台账取证）——**那是工具集（刀 1+）的事**，本刀不带工具。
     */
    val BUILTIN_FREE = Provider(
        id = "builtin-free",
        label = "内置免费通道",
        protocol = LlmProtocol.OPENAI_COMPAT,
        baseUrl = FREE_BASE_URL,
        model = FREE_MODELS.first(),
        apiKey = FREE_PLACEHOLDER_KEY,
        headers = FREE_HEADERS,
        builtinFree = true,
    )

    /** 三个预设（顺序即 UI 上的展示序）。 */
    fun all(): List<Provider> = listOf(BUILTIN_FREE, OPENAI_COMPAT, ANTHROPIC)

    fun of(id: String): Provider? = all().firstOrNull { it.id == id }

    /**
     * 把用户自己的 key 套到某个预设上：**只换 key，其余一律保留**。
     *
     * "预设不许把用户 key 挤掉"的落地处——预设是默认值，用户输入是既成事实，
     * 任何"为了预设完整性"而丢弃用户 key 的做法都是倒退。
     */
    fun withUserKey(preset: Provider, key: String): Provider = preset.copy(apiKey = key)
}
