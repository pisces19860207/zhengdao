// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 2026-10-19 用户：「填好 base url 和密钥后应该能出现那家公司或者地址的所有模型才对吧？
// 然后选一个就好了啊。」⇒ 拉 `GET {base}/models`，解析 `data[].id` 给下拉选。
//
// 第一百三十一批 ③（2026-10-22）三件事：
// 1. **预设也拉**（原先只有自定义拉，预设摆硬编码老模型）；
// 2. **三种形状分别解析**：OpenAI 兼容 `data[].id` / Anthropic `data[].id + display_name` /
//    Gemini 原生 `models[].name`（带 `models/` 前缀）＋ `displayName`；
// 3. 顺手把**元数据**（上下文窗口、输出上限、人类名）一起带回来——
//    OpenRouter 是聚合端点、给全套（② 2026-10-22 搜索实锤：官方直连端点只给 ID 不给元数据），
//    所以显示名规则的第 1 级只有走 OpenRouter 时才真能命中。
//
// 纯函数与网络分开：URL 拼接/解析可 JVM 直测；拉取只在 UI 协程里跑，失败就退回手填。
package com.example.zhengdao.baopu

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * **模型清单**的形状（与 [LlmProtocol] 是两件事，别混）。
 *
 * [LlmProtocol] 管"对话怎么发"（只有两种形状，本批不许扩大 = 红线「不改 Provider 的安全校验」
 * 之外再加一条：不新增第三种请求形状）；本枚举只管"**列表端点长什么样**"，
 * 由 [ModelCatalog.shapeFor] 从接口地址**现推**，不落存储、不是第二真相。
 *
 * 为什么 Gemini 原生只在这里有、对话里没有：`generativelanguage.googleapis.com` 的清单
 * 是公开可解析的，但它的**对话**形状（`generateContent`、role/system 都不同）本仓没实现。
 * 所以 Gemini 原生这一档只服务"拉列表"，选它的模型当对话通道会失败——
 * 这一条写进 UI 的兜底话术与交付报告，不假装支持。
 */
internal enum class CatalogShape { OPENAI_COMPAT, ANTHROPIC, GEMINI_NATIVE }

/**
 * 清单里的一行（③：显示名与能力都要用它，只有 ID 不够）。
 *
 * 三个可空字段都**不许瞎补**：端点没给就是 null，上层按「—」显示（派工单：不编数）。
 */
internal data class ModelEntry(
    val id: String,
    val name: String? = null,
    val contextWindow: Int? = null,
    val maxOutput: Int? = null,
)

internal object ModelCatalog {

    /** OpenRouter 的清单**免 Key 公开**（② 同批搜索实锤）；免费档选模型靠它，不内置任何 Key。 */
    const val OPENROUTER_MODELS_URL = "https://openrouter.ai/api/v1/models"

    /** Gemini 原生清单要 Key，且 Key 走**查询参数**不是头（这是它与另两档最大的差别）。 */
    private const val GEMINI_KEY_PARAM = "key"

    /**
     * 从接口地址现推清单形状（纯函数）。
     *
     * 只认**域名/版本段**这两种硬特征，不认"用户说他是谁"：
     * - `anthropic.com` ⇒ Anthropic 形状（它的清单虽然也叫 `data[]`，但人类名在 `display_name`，
     *   且鉴权头完全不同：`x-api-key` + `anthropic-version`，不是 Bearer）；
     * - `generativelanguage.googleapis.com` 或地址里已经写了 `/v1beta` ⇒ Gemini 原生；
     * - 其余一律 OpenAI 兼容（DeepSeek、OpenAI、自建网关、OpenRouter 都是这一档）。
     */
    fun shapeFor(baseUrl: String): CatalogShape {
        val b = baseUrl.trim().lowercase()
        return when {
            b.contains("anthropic.com") -> CatalogShape.ANTHROPIC
            b.contains("generativelanguage.googleapis.com") || b.contains("/v1beta") ->
                CatalogShape.GEMINI_NATIVE
            else -> CatalogShape.OPENAI_COMPAT
        }
    }

    /**
     * 模型列表请求地址（OpenAI 兼容 / Anthropic 共用这一条拼法）：
     * base 尾部斜杠去掉；已含 `/v1` 就不再补；已经是 `/models` 就原样。
     *
     * Gemini 原生另走 [geminiModelsUrl]——它的版本段是 `/v1beta`，补 `/v1/models` 会 404。
     */
    fun modelsUrl(baseUrl: String): String {
        var b = baseUrl.trim().trimEnd('/')
        if (b.endsWith("/models")) return b
        return if (b.endsWith("/v1")) "$b/models" else "$b/v1/models"
    }

    /** 按形状选拼接函数（调用方只该记一个入口）。 */
    fun modelsUrl(baseUrl: String, shape: CatalogShape): String = when (shape) {
        CatalogShape.GEMINI_NATIVE -> geminiModelsUrl(baseUrl)
        else -> modelsUrl(baseUrl)
    }

    /**
     * Gemini 原生：`…/v1beta/models`。用户把 base 写成 `…/v1beta` 或 `…/v1` 都收；
     * 只写域名时补 `/v1beta`（它是 Gemini 的默认版本段，不是本仓猜的——`/v1` 在那台域名上不存在）。
     */
    fun geminiModelsUrl(baseUrl: String): String {
        val b = baseUrl.trim().trimEnd('/').removeSuffix("/models")
        return when {
            b.endsWith("/v1beta") || b.endsWith("/v1alpha") || b.endsWith("/v1") -> "$b/models"
            else -> "$b/v1beta/models"
        }
    }

    /**
     * 鉴权怎么带（纯函数，返回"要不要往 URL 上挂 key"＋"要带哪些头"）。
     *
     * Gemini 的 Key 在**查询参数**里 ⇒ 它可能进访问日志，这一条是形状决定的，不是本仓选择；
     * 另两档走头。Key 原文只在这一次请求里出现，永不进 RunLog、永不进库（红线）。
     */
    fun authFor(shape: CatalogShape, apiKey: String): CatalogAuth = when (shape) {
        CatalogShape.OPENAI_COMPAT -> CatalogAuth(
            queryKey = null,
            headers = if (apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $apiKey"),
        )
        CatalogShape.ANTHROPIC -> CatalogAuth(
            queryKey = null,
            // 版本号从对话层那份常量转手（[LlmWire.ANTHROPIC_VERSION]）——清单与对话必须同版本，
            // 抄两个字面量就是给自己埋第二真相。
            headers = buildMap {
                put("anthropic-version", LlmWire.ANTHROPIC_VERSION)
                if (apiKey.isNotBlank()) put("x-api-key", apiKey)
            },
        )
        CatalogShape.GEMINI_NATIVE -> CatalogAuth(
            queryKey = apiKey.takeIf { it.isNotBlank() },
            headers = emptyMap(),
        )
    }

    /** 清单请求的鉴权形态（见 [authFor]）。 */
    internal data class CatalogAuth(val queryKey: String?, val headers: Map<String, String>)

    /**
     * 解析 OpenAI 兼容（以及 Anthropic）的 `/models` 响应：都是 `{"data":[{"id":"…"}]}`。
     * 也容忍顶层直接是数组的野路子。坏 JSON 返回空表，不抛。
     *
     * 第一百三十一批 ③：这是 [parseEntries] 的**薄适配器**（老调用点与既有单测照旧只吃 ID 表）。
     */
    fun parseModelIds(json: String): List<String> = parseEntries(json, CatalogShape.OPENAI_COMPAT).map { it.id }

    /** 同上，按形状。 */
    fun parseModelIds(json: String, shape: CatalogShape): List<String> = parseEntries(json, shape).map { it.id }

    /**
     * 三种形状一次解析（③ 的核心判据；纯函数，JVM 喂假 JSON 直测）。
     *
     * - OpenAI 兼容 / Anthropic：`data[].id`，人类名 OpenAI 没有、Anthropic 在 `display_name`；
     * - Gemini 原生：`models[].name` 带 `models/` 前缀（**必须剥**，否则发给它的 model 名 404），
     *   人类名在 `displayName`，窗口在 `inputTokenLimit`/`outputTokenLimit`。
     *
     * 排序照旧（按 ID）；去重照旧。坏 JSON ⇒ 空表不抛（拉不到就退回手填）。
     */
    fun parseEntries(json: String, shape: CatalogShape): List<ModelEntry> {
        val out = mutableListOf<ModelEntry>()
        fun intake(o: org.json.JSONObject?) {
            if (o == null) return
            val rawId = when (shape) {
                CatalogShape.GEMINI_NATIVE -> o.optString("name").ifBlank { o.optString("id") }
                else -> o.optString("id").ifBlank { o.optString("name") }
            }.trim()
            if (rawId.isBlank()) return
            // Gemini 的资源名是 "models/gemini-2.5-pro" 形态：API 要的是后半段。
            val id = if (shape == CatalogShape.GEMINI_NATIVE) rawId.substringAfterLast('/') else rawId
            val name = when (shape) {
                CatalogShape.GEMINI_NATIVE -> o.optString("displayName")
                CatalogShape.ANTHROPIC -> o.optString("display_name")
                else -> o.optString("name")
            }.trim().takeIf { it.isNotEmpty() && it != id }
            out += ModelEntry(
                id = id,
                name = name,
                // OpenRouter：context_length / max_completion_tokens（顶层或 top_provider 里都见过）。
                contextWindow = o.optIntOr("context_length")
                    ?: o.optJSONObject("top_provider").optIntOr("context_length")
                    ?: o.optIntOr("inputTokenLimit"),
                maxOutput = o.optIntOr("max_completion_tokens")
                    ?: o.optJSONObject("top_provider").optIntOr("max_completion_tokens")
                    ?: o.optIntOr("outputTokenLimit"),
            )
        }
        // OpenAI / Anthropic 在 `data[]`，Gemini 在 `models[]`；野路子（顶层就是数组）也收。
        runCatching {
            val o = org.json.JSONObject(json)
            val arr = o.optJSONArray("data") ?: o.optJSONArray("models")
            if (arr != null) for (i in 0 until arr.length()) intake(arr.optJSONObject(i))
        }
        if (out.isEmpty()) {
            runCatching {
                val arr = org.json.JSONArray(json)
                for (i in 0 until arr.length()) intake(arr.optJSONObject(i))
            }
        }
        return out.distinctBy { it.id }.sortedBy { it.id }
    }

    /** `int` 字段取值：没有／不是数字／≤0 都算"没声明"（null），**不折成 0**。 */
    private fun org.json.JSONObject?.optIntOr(field: String): Int? {
        val o = this ?: return null
        if (!o.has(field) || o.isNull(field)) return null
        val v = o.optInt(field, 0)
        return if (v > 0) v else null
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 阻塞式拉取（调用方放 IO 协程）；返回 (models, error)。
     *
     * 第一百三十一批 ③：这是 [fetchEntries] 的薄适配器——自定义那头的老调用点与既有单测
     * 只吃 ID 表，签名一行不动。
     */
    fun fetch(baseUrl: String, apiKey: String): Pair<List<String>, String?> =
        fetchEntries(baseUrl, apiKey).let { it.first.map { e -> e.id } to it.second }

    /** 阻塞式拉取（带元数据）。形状默认从地址现推；调用方可显式指定（单测/以后加档）。 */
    fun fetchEntries(
        baseUrl: String,
        apiKey: String,
        shape: CatalogShape = shapeFor(baseUrl),
    ): Pair<List<ModelEntry>, String?> {
        val request = runCatching {
            val auth = authFor(shape, apiKey.trim())
            val target = modelsUrl(baseUrl, shape) +
                (auth.queryKey?.let { "?$GEMINI_KEY_PARAM=$it" } ?: "")
            val builder = Request.Builder().url(target).header("Accept", "application/json").get()
            auth.headers.forEach { (k, v) -> builder.header(k, v) }
            builder.build()
        }.getOrElse { return emptyList<ModelEntry>() to "接口地址不合法：${it.message}" }
        return runCatching {
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    // 错误原文可能带地址（不带 Key：Key 在头里或查询参数里，响应体不会回显它）。
                    return@use emptyList<ModelEntry>() to "HTTP ${resp.code}：${body.take(120)}"
                }
                val entries = parseEntries(body, shape)
                entries to (if (entries.isEmpty()) "返回里没有模型" else null)
            }
        }.getOrElse { e -> emptyList<ModelEntry>() to (e.message ?: e.javaClass.simpleName) }
    }
}
