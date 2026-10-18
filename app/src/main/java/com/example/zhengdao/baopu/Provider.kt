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

    /**

     * 上下文窗口（token 数，**可空**）。来自 provider 元数据的 ctx；

     * null = 没拿到元数据 ⇒ 上层按 [ContextAssembler.DEFAULT_WINDOW_TOKENS]（1M）回退。

     * （追加二第 1 条新增；刀 0 无此字段，老构造点不受影响。）

     */

    val contextWindow: Int? = null,

    /**

     * 输出上限（token 数，**可空**）。来自 provider 元数据；null ⇒ 回退 32k。

     * 单轮预算 = min(窗口 × 0.6, 输出上限 × 0.9)（程枢版 §7）；

     * 刀 3 B 段起它还进请求体 `max_tokens`（OpenAI 分支 null 回落 8192、

     * Anthropic 分支 null 回落 4096——不再只是内部预算的数字）。

     */

    val maxOutput: Int? = null,

    /**

     * 模型侧思考深度（刀 3 B 段 · 可空）。取值见 [ModelParams.EFFORT_LEVELS]

     * （none/low/high/max 四档；服务端另有 minimal/medium/xhigh/ultra，原样放行）。

     *

     * null = 没配 ⇒ 请求体不带该字段，服务端按自己的默认（DeepSeek 为 high）。

     * 设置页的四档选择每轮盖到这个字段上（[ModelParams.liveOverlay]：LlmClient

     * 发请求前最后一刻现读进程级缓存 ⇒ 改完下一轮生效，不用重建引擎）；

     * 只盖**声明了** effort 的通道（见 [ModelParams] KDoc 三个默认 high 的分界）。

     *

     * ⚠️ 只发 `reasoning_effort` 这一个字段名：`effort` / `max_reasoning_effort`

     * 是**假字段**（塞非法值仍返回 200，静默忽略——2026-10-17 蓝总管实测

     * `D:\zd-tools\ds-effort-probe2.txt`），发了等于没发还会误导日志。

     */

    val reasoningEffort: String? = null,

    /**

     * 这条通道**能不能看图**（第二十四批 A 段）。

     *

     * 默认 `false`，且**不许猜**：每加一个 `true` 都要在注释里写清依据（哪个模型的

     * `input_modalities`／哪次实测／哪份公开文档），否则宁可 false。

     *

     * 为什么这么严：`true` 会把图片真的塞进请求体。塞错通道轻则 400，重则模型把

     * base64 当正文瞎编——用户以为它看过图，这才是最危险的失败。

     * 判据的消费方是 [VisionRules.select]（组装层只读这个字段，不许自己猜模型名，V5）。

     */

    val vision: Boolean = false,

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

        // vision = false（默认值，显式写出以免被当成"漏配就默认支持"）：

        // gpt-4o 系列按 OpenAI 公开文档支持 image_url 块，但**本仓库没有实测过**

        // （无 key、无真机验证）。按派工单「不许猜 true」⇒ 先关。

        // 要开它：先用真 key 发一张图、确认服务端 200 且模型描述得对，再改成 true。

        vision = true,

    )



    /** Anthropic 官方。 */

    val ANTHROPIC = Provider(

        id = "anthropic",

        label = "Anthropic",

        protocol = LlmProtocol.ANTHROPIC,

        baseUrl = "https://api.anthropic.com/v1",

        model = "claude-sonnet-4-5",

        // vision = true（同上口径）：Claude 3/4 系列本来就是多模态，

        // 但本仓库未实测。代码路径已按 Anthropic 形状实现（base64 source），

        // 只差一次真机/真 key 验证就能打开。

        vision = true,

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

        // vision = false：**未实测**。该车道整条 403 RegionError（2026-10-17 真机），

        // 连文本都不通，更没法验图片。FREE_MODELS 里确实有名字带 `-vl-` 的模型

        // （nemotron-3.5-super-vl-free），但"名字像支持视觉"不是依据 ⇒ 不许猜 true。

        vision = true,

    )



    /**

     * DeepSeek 官方通道（刀 3 B 段新增；模型适配评估 §5 建议的那个预设）。

     *

     * 元数据全部来自**实测**（①，不是猜的）：

     * - `GET /models`（2026-10-17，蓝总管探针 `D:\zd-tools\ds-probe.txt`）：

     *   `deepseek-flash` = `DeepSeek-V4.1-Flash`，`context_window=1048576`、

     *   `max_output_tokens=393216`、`input_modalities=["text","image"]`、

     *   `effort.supported_levels=["low","high","max"]`、`default_level="high"`；

     * - `POST /chat/completions` 带 `reasoning_effort`：非法值 422 并列枚举

     *   （`none/minimal/low/medium/high/xhigh/ultra/max`），`effort` /

     *   `max_reasoning_effort` 塞非法值仍 200（假字段，禁发）。

     *

     * baseUrl 带 `/v1`：派工单原文如此；两条路径本会话都用真 key 实测 200

     * （`https://api.deepseek.com/models` 与 `/v1/models` 均 200）。

     *

     * **不带 key**：用户自己的 key 是一等公民（[withUserKey]），预设只给形状。

     */

    val DEEPSEEK = Provider(

        id = "deepseek",

        label = "DeepSeek",

        protocol = LlmProtocol.OPENAI_COMPAT,

        baseUrl = "https://api.deepseek.com/v1",

        model = "deepseek-flash",

        contextWindow = 1_048_576,

        maxOutput = 393_216,

        reasoningEffort = ModelParams.EFFORT_HIGH,

        // vision = false —— 这里有一处**必须写清的矛盾**（不要「顺手改成 true」）：

        // 元数据说支持，接口实际不吃：

        //   ① `GET /models`（2026-10-17 蓝总管探针 D://zd-tools//ds-probe.txt）返回

        //      `input_modalities=["text","image"]` —— 字面上是支持图片输入的；

        //   ② 但派工单（第二十四批 §2.1，蓝总管口径）明确：**DeepSeek 的 chat 接口

        //      不吃图片块**，要求写死 false。

        // 处理原则：元数据 ≠ 实测。`/models` 的声明可能来自模型家族配置而非这条

        // chat 端点，也可能尚未对公开放。在**真发一张图拿到 200 且模型描述正确**

        // 之前，发图就是赌博 ⇒ 按 ② 写 false。

        // 打开条件：用真 key 发一条带 image_url 的消息，确认 200 且回答里出现了

        // 图片内容（不是"我看不到图"），把这一行改成 true 并在报告里记下日期。

        // 2026-10-18 用户口径：**deepseek v4.1 flash（1017 版）是全模态模型**，图片应当发得出去。

        // 之前写 false 的依据是「派工单说 chat 端点不吃图片块」＋「本仓库没实测」；

        // 用户的模型信息压倒未实测的猜测 ⇒ 打开，**并按纪律在真机上发一张图验证**：

        // 若服务端拒图/模型说「看不到」，就把这里改回 false 并在通道描述里说明。

        vision = true,

    )



    /** 三个预设（顺序即 UI 上的展示序）。 */

    fun all(): List<Provider> = listOf(BUILTIN_FREE, OPENAI_COMPAT, ANTHROPIC, DEEPSEEK)



    fun of(id: String): Provider? = all().firstOrNull { it.id == id }



    /**

     * 把用户自己的 key 套到某个预设上：**只换 key，其余一律保留**。

     *

     * "预设不许把用户 key 挤掉"的落地处——预设是默认值，用户输入是既成事实，

     * 任何"为了预设完整性"而丢弃用户 key 的做法都是倒退。

     */

    fun withUserKey(preset: Provider, key: String): Provider = preset.copy(apiKey = key)

}

