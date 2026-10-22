// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。

//

// 依据的公开接口与仓库内取证：

//   - OpenAI Chat Completions API 公开文档形态（POST /chat/completions，Authorization: Bearer 头）；

//   - Anthropic Messages API 公开文档形态（POST /messages，x-api-key + anthropic-version 头）；

//   - OpenRouter 的 baseUrl 与免费档清单取自**本会话实测**（第一百三十一批 ①：2026-10-22

//     `GET https://openrouter.ai/api/v1/models` 未带 Key 返回 200，共 468 个模型、

//     其中 18 个以 `:free` 结尾）；逐条 id／context_length 见

//     证道设计报告/模型选择P0-交付-2026-10-22.md §三。

//   - 历史：曾内置一条 opencode zen 免费车道（整条 403 RegionError，2026-10-17 真机实测），

//     第一百三十一批 ① 按派工单**整条删除**——删干净，不留占位 Key、不留伪装头。

package com.example.zhengdao.baopu



/**

 * LLM 协议种类（刀 0 · 2026-10-16）。

 *

 * 只分两种，因为市面上的"OpenAI 兼容"端点（OpenAI 官方、DeepSeek、智谱、OpenRouter……）

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

 * 免费车道（[Providers.OPENROUTER_FREE]）的 `builtinFree = true` 描述的是

 * **这条通道**，不随 key 变化——它回答"这是不是免费车道"，不是"现在免不免费"。

 * 第一百三十一批 ① 起免费车道**同样要用户自己的 Key**（OpenRouter 注册后填），

 * 所以这个标志不再隐含"免密钥可用"，只用于路由排除与限额提示两处判据。

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

    /** 自定义 header（最后合并，允许覆盖协议默认头）。第一百三十一批 ① 起预设一律不带。 */

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

 * 内置预设（刀 0 · 2026-10-16 起；第一百三十一批 ① 换免费车道、③ 增 OpenRouter）。

 *

 * 持久化（用户选了哪个、填了什么 key）归刀 1 的设置页；本文件只给**默认值**，

 * 且**不含任何 Key**（红线：不内置 key、App 不兜底）。

 */

object Providers {



    /** OpenAI 官方（也是所有 OpenAI 兼容端点的默认形状；换 DeepSeek 等改 baseUrl 即可）。 */

    val OPENAI_COMPAT = Provider(

        id = "openai-compat",

        label = "OpenAI",

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

     * OpenRouter 预设（第一百三十一批 ③：预设也要能动态拉模型，本文件先给形状）。

     *

     * ① 实测（2026-10-22 本会话）：`GET https://openrouter.ai/api/v1/models` 未带 Key 即 200，

     * 返回 468 个模型；默认模型 `deepseek/deepseek-v4.1-flash` 在其中，

     * `context_length=1048576`。

     *

     * **不带 Key**：用户自己注册、自己填（红线：不内置、不兜底、不预填、不读剪贴板）。

     */

    val OPENROUTER = Provider(

        id = "openrouter",

        label = "OpenRouter",

        protocol = LlmProtocol.OPENAI_COMPAT,

        baseUrl = "https://openrouter.ai/api/v1",

        model = "deepseek/deepseek-v4.1-flash",

        contextWindow = 1_048_576,

        // vision = true（第一百三十一批 ① + 2026-10-18 用户口径）。

        // ① 实测台账：本会话抓 `GET https://openrouter.ai/api/v1/models`（未带 Key 即 200，

        // 468 条）里 `deepseek/deepseek-v4.1-flash` 的

        // `architecture.input_modalities = ["text", "image"]`。

        // 用户口径：「除非是文本模型，不然肯定要打开」＋「纯文本模型自己就会说看不见」

        // ⇒ 不在本地替模型判断能不能看图，图照发；端上认不认由它自己回答。

        // 纪律没被绕过：真机发图验证属**未验项**（本单红线不跑真机）。若端上拒图或模型

        // 答「看不到」，就把这里改回 false 并在交付报告里记下日期。

        vision = true,

    )





    /**

     * OpenRouter 免费档：第一百三十一批 ① 之后**新用户开箱的第一条车道**，

     * 替换整条 403 的 opencode zen 免费通道。

     *

     * 口径（派工单 ①）：**App 不兜底**——这条预设不带 Key，用户自己去

     * `https://openrouter.ai/settings/keys` 注册拿 Key 填进来；没填 Key 时界面说的是

     * 「去 OpenRouter 注册填 Key」＋跳转，**不是**一条 403 死路。

     *

     * 限额（① 官方口径，2026-10-22 两处交叉核对）：未充值 **20 次/分钟、50 次/天**，

     * 余额满 $10 起 1000 次/天；超额回 429。界面必须把这句话显示出来（[FREE_QUOTA_HINT]）。

     *

     * 模型清单**不硬编码**：免费档每次现拉、不缓存（派工单 ③），所以这里只放一条

     * ① 实测到的 `:free` 模型当默认值，其余由选模型抽屉列给用户点。

     */

    val OPENROUTER_FREE = Provider(

        id = "openrouter-free",

        label = "OpenRouter 免费",

        protocol = LlmProtocol.OPENAI_COMPAT,

        baseUrl = "https://openrouter.ai/api/v1",

        // ① 实测（2026-10-22）：18 条 `:free` 里有 nvidia/nemotron-3.5-super-vl:free，

        // context_length=1000000、top_provider.max_completion_tokens=131072；与被删掉的

        // zen 菜单里那条 nemotron-3.5-super-vl-free 是同一批模型的延续。

        model = "nvidia/nemotron-3.5-super-vl:free",

        contextWindow = 1_000_000,

        maxOutput = 131_072,

        // apiKey 用默认值（空串）= **本仓库不内置任何 Key**（负例用例钉的就是这一条）。

        builtinFree = true,

        // vision = true（2026-10-18 用户口径，见 LlmWireVisionTest V5）：

        // 「除非是文本模型，不然肯定要打开」「纯文本模型自己就会说看不见」⇒ 不在本地替模型

        // 判断能不能看图，图照发。① 实测这条 `:free` 的 input_modalities 含 image/video，

        // 它本来就不是文本模型。

        vision = true,

    )





    /**

     * 免费档限额的界面文案（派工单 ①「免费限额 UI 明示」；数字是 ① 官方口径，不编）。

     *

     * 为什么放在数据层：选模型抽屉与设置页两处都要说同一件事，写两遍就会漂成两个数

     * （第二真相）。UI 只显示，不自己拼数字。

     */

    const val FREE_QUOTA_HINT =

        "OpenRouter 免费档限额：未充值 20 次/分钟、50 次/天；余额满 \$10 后 1000 次/天。" +

            "超额会返回 429，稍后自动恢复，不阻塞继续使用。"





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



    /**

     * 预设清单（顺序即 UI 上的展示序，与《抱朴设置重构方案》§2 抽屉树一致）。

     *

     * 第一百三十一批 ①：删掉整条 403 的内置 zen 免费车道；③：新增 OpenRouter。

     * 自定义通道不在此列——它不是预设，见 [ProviderPrefs.CHANNEL_CUSTOM]。

     */

    fun all(): List<Provider> =

        listOf(OPENAI_COMPAT, DEEPSEEK, ANTHROPIC, OPENROUTER, OPENROUTER_FREE)



    fun of(id: String): Provider? = all().firstOrNull { it.id == id }



    /**

     * 把用户自己的 key 套到某个预设上：**只换 key，其余一律保留**。

     *

     * "预设不许把用户 key 挤掉"的落地处——预设是默认值，用户输入是既成事实，

     * 任何"为了预设完整性"而丢弃用户 key 的做法都是倒退。

     */

    fun withUserKey(preset: Provider, key: String): Provider = preset.copy(apiKey = key)

}

