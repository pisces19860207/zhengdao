// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 2026-10-19 用户：「填好 base url 和密钥后应该能出现那家公司或者地址的所有模型才对吧？
// 然后选一个就好了啊。」⇒ 拉 `GET {base}/models`，解析 `data[].id` 给下拉选。
//
// 纯函数与网络分开：URL 拼接/解析可 JVM 直测；拉取只在 UI 协程里跑，失败就退回手填。
package com.example.zhengdao.baopu

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

internal object ModelCatalog {

    /** 模型列表请求地址：base 尾部斜杠去掉；已含 `/v1` 就不再补。 */
    fun modelsUrl(baseUrl: String): String {
        var b = baseUrl.trim().trimEnd('/')
        if (b.endsWith("/models")) return b
        return if (b.endsWith("/v1")) "$b/models" else "$b/v1/models"
    }

    /**
     * 解析 OpenAI 兼容（以及 Anthropic）的 `/models` 响应：都是 `{"data":[{"id":"…"}]}`。
     * 也容忍顶层直接是数组的野路子。坏 JSON 返回空表，不抛。
     */
    fun parseModelIds(json: String): List<String> {
        val ids = mutableListOf<String>()
        fun readArray(arr: org.json.JSONArray?) {
            if (arr == null) return
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").ifBlank { o.optString("name") }
                if (id.isNotBlank()) ids += id
            }
        }
        runCatching {
            readArray(org.json.JSONObject(json).optJSONArray("data"))
        }
        if (ids.isEmpty()) {
            runCatching { readArray(org.json.JSONArray(json)) }
        }
        return ids.distinct().sorted()
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** 阻塞式拉取（调用方放 IO 协程）；返回 (models, error)。 */
    fun fetch(baseUrl: String, apiKey: String): Pair<List<String>, String?> {
        val url = modelsUrl(baseUrl)
        return runCatching {
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${apiKey.trim()}")
                .header("Accept", "application/json")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@use emptyList<String>() to "HTTP ${resp.code}：${body.take(120)}"
                }
                val ids = parseModelIds(body)
                ids to (if (ids.isEmpty()) "返回里没有模型 id" else null)
            }
        }.getOrElse { e -> emptyList<String>() to (e.message ?: e.javaClass.simpleName) }
    }
}
