// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第二百一十八批 ② `search_web`：联网检索（只读，R0）。
//
// 判据（派工单 §1②，全在 [SearchWebPolicy] 纯函数里，负例逐条堵）：
//   1. **域名白名单**：只抓白名单域；设置页可配的口留给调用方（v1 用内置默认清单）；
//   2. **禁内网**：localhost / 私有段 IP / 无点主机名 / *.local ⇒ 一律拒绝（URL 必须是公网）；
//   3. **必须真实 URL**：结果链接从检索页的跳转参数里**解码**出来（uddg），不是编的；
//   4. **只取正文前 N 字**（默认 2000/条），不改页面、不登录、不提交；
//   5. **日志只记 query 的哈希**，不记原文（防隐私——派工单 §3「不在日志写下检索原文」）。
//
// 检索后端：DuckDuckGo HTML 版（`https://html.duckduckgo.com/html/?q=…`，无需 key、无新依赖，
// OkHttp 用现有的）；结果链接是 `//duckduckgo.com/l/?uddg=<URL编码的真实地址>`，解码即得真 URL。
// 失败/无结果 ⇒ 明说（负例：失败静默返回空）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.rootfs.RunLog
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.IDN
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** 每条结果（title/url/snippet/fetchedAt——派工单 §1② 固定四件）。 */
internal data class WebHit(
    val title: String,
    val url: String,
    val snippet: String,
    val fetchedAt: Long,
)

/** 白名单与内网判据（**纯函数**，JVM 直测；负例 2/3 的执行点）。 */
internal object SearchWebPolicy {

    /** 内网/本机地址样式（命中 ⇒ 不抓不测，报告"被排除"）。 */
    val PRIVATE_HOST: Regex = Regex(
        "^(localhost|127\\.\\d+\\.\\d+\\.\\d+|0\\.0\\.0\\.0|10\\.\\d+\\.\\d+\\.\\d+|" +
            "192\\.168\\.\\d+\\.\\d+|172\\.(1[6-9]|2[0-9]|3[01])\\.\\d+\\.\\d+|169\\.254\\.\\d+\\.\\d+|" +
            "::1|[a-z0-9-]*\\.local|[a-z0-9-]*\\.internal)$",
        RegexOption.IGNORE_CASE,
    )

    /** 默认白名单（v1 内置；设置页可配的口是 [allowed]，后续单接 UI）。 */
    val DEFAULT_ALLOWED = setOf(
        "duckduckgo.com", "html.duckduckgo.com",
        "wikipedia.org", "zh.wikipedia.org", "en.wikipedia.org",
        "github.com", "stackoverflow.com", "arxiv.org",
        "zhihu.com", "juejin.cn", "cnblogs.com", "csdn.net",
        "baike.baidu.com", "bing.com", "cn.bing.com",
    )

    /** 域名归一：小写、去末点、去 `www.`、IDN 转 punycode（`维基百科.org` 也能判）。 */
    fun normalizeHost(host: String): String = runCatching {
        IDN.toASCII(host.trim().trimEnd('.').removePrefix("www.").lowercase())
    }.getOrDefault(host.trim().trimEnd('.').removePrefix("www.").lowercase())

    /** 是不是内网/本机（**抓取前先判**，负例②的闸）。 */
    fun isPrivate(url: String): Boolean = runCatching {
        val u = URI(url)
        val host = u.host ?: return@runCatching true
        PRIVATE_HOST.matches(normalizeHost(host))
    }.getOrDefault(true)

    /** 域在不在白名单（主域后缀匹配：`en.wikipedia.org` 命中 `wikipedia.org`）。 */
    fun allowed(url: String, extraAllowed: Set<String> = emptySet(), excluded: Set<String> = emptySet()): Boolean {
        if (isPrivate(url)) return false
        val host = normalizeHost(runCatching { URI(url).host }.getOrDefault("") ?: return false)
        if (host.isEmpty()) return false
        if (excluded.any { host == it || host.endsWith(".$it") }) return false
        val pool = DEFAULT_ALLOWED + extraAllowed
        return pool.any { host == it || host.endsWith(".$it") }
    }

    /** query 摘要（**哈希进日志，不记原文**——判据 §3 的执行点）。 */
    fun queryHash(query: String): String =
        MessageDigest.getInstance("SHA-256").digest(query.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(12)

    /** 日志一行的**唯一**拼法（纯函数可测：测试断言它只含哈希，不含检索原文）。 */
    fun queryLogLine(query: String, site: String?, n: Int): String =
        "search_web: qsha=${queryHash(query)} site=${site ?: "-"} n=$n" 

    /** 结果条数上限（参数 maxRows 同款：默认 5，上限 10）。 */
    const val DEFAULT_RESULTS = 5
    const val MAX_RESULTS = 10

    /** 每条正文字数上限（派工单 §1②：默认 2000/条）。 */
    const val BODY_MAX_CHARS = 2000
}

/** DuckDuckGo HTML 版的结果解析（纯字符串，JVM 直测）。 */
internal object SearchWebParse {

    /**
     * 从 HTML 里抽结果：`<a class="result__a" href="//duckduckgo.com/l/?uddg=<enc>&…">标题</a>`
     * ＋邻近的 `result__snippet`。真实 URL 从 `uddg` **解码**（判据③：不许编 URL）。
     */
    fun hits(html: String, limit: Int, nowMs: Long): List<WebHit> {
        val out = mutableListOf<WebHit>()
        val linkRe = Regex("""class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val snipRe = Regex("""class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val snippets = snipRe.findAll(html).map { stripTags(it.groupValues[1]) }.toList()
        for ((i, m) in linkRe.findAll(html).withIndex().take(limit)) {
            val real = realUrl(m.groupValues[1]) ?: continue
            val title = stripTags(m.groupValues[2]).ifBlank { continue }
            out += WebHit(
                title = title,
                url = real,
                snippet = snippets.getOrNull(i).orEmpty().take(500),
                fetchedAt = nowMs,
            )
        }
        return out
    }

    /** 跳转链接 ⇒ 真实 URL（`//duckduckgo.com/l/?uddg=<enc>&…`；直链原样）。 */
    fun realUrl(href: String): String? = runCatching {
        val h = href.trim()
        when {
            h.startsWith("//") -> "https:$h"
            h.startsWith("http") -> h
            else -> return null
        }
    }.getOrNull()?.let { full ->
        runCatching {
            val u = URI(full)
            if (u.host?.endsWith("duckduckgo.com") == true) {
                val uddg = u.rawQuery?.split('&')?.firstOrNull { it.startsWith("uddg=") }?.substring(5)
                uddg?.let { URLDecoder.decode(it, "UTF-8") }
            } else {
                full
            }
        }.getOrNull()
    }

    private fun stripTags(s: String): String =
        s.replace(Regex("<[^>]*>"), "").replace("&amp;", "&").replace("&quot;", "\"")
            .replace("&#x27;", "'").replace("&lt;", "<").replace("&gt;", ">").trim()
}

internal object SearchWebTool {

    const val ARG_QUERY = "query"
    const val ARG_SITE = "site"
    const val ARG_MAX_RESULTS = "maxResults"
    const val ARG_BODY_CHARS = "bodyChars"

    /** 正文抓取的 OkHttp 超时（网络类：20s 同 BROWSER_MS）。 */
    private const val NET_TIMEOUT_S = 20L

    fun spec(): ToolSpec = ToolSpec(
        name = "search_web",
        description = "联网检索（只读）：按关键词搜公开网页，返回标题、真实链接、摘要与时间。" +
            "只抓白名单里的公开站点（禁内网/本机地址），日志只记检索词的哈希不记原文。" +
            "（这个工具在 WEBSEARCH 组：看不到就先 capabilities 解锁）",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_QUERY to jsonObjectOf("type" to "string", "description" to "检索词"),
                ARG_SITE to jsonObjectOf("type" to "string", "description" to "可选：限定域名（如 wikipedia.org；必须在白名单内）"),
                ARG_MAX_RESULTS to jsonObjectOf("type" to "integer", "description" to "结果条数（缺省 5，上限 10）"),
                ARG_BODY_CHARS to jsonObjectOf("type" to "integer", "description" to "可选：抓第一条结果正文前 N 字（缺省不抓；上限 2000）"),
            ),
            "required" to jsonArrayOf(ARG_QUERY),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.FREE,
        riskTier = "R0", // 只读（走网络；日志只记检索词哈希）
        readOnly = true,
        parallelSafe = true,
        timeoutMs = ToolTimeout.BROWSER_MS,
    ) { args -> run(args, System.currentTimeMillis()) }

    /**
     * 检索（判据层与抓取层分开：[SearchWebPolicy]/[SearchWebParse] 纯函数可测；
     * 本函数只做 HTTP 与拼装）。
     *
     * @param fetchBody 调用方注入抓正文口（测试注入假页；生产真 OkHttp）
     */
    fun run(args: JSONObject, nowMs: Long, fetchBody: (String, Int) -> String? = ::fetchBody): ToolOutcome {
        val query = args.optString(ARG_QUERY).ifBlank { return ToolOutcome.Failed("缺 query（检索词不能为空）") }
        val site = args.optString(ARG_SITE).ifBlank { null }
        val maxResults = args.optInt(ARG_MAX_RESULTS, SearchWebPolicy.DEFAULT_RESULTS)
            .coerceIn(1, SearchWebPolicy.MAX_RESULTS)
        val bodyChars = args.optInt(ARG_BODY_CHARS, 0).coerceIn(0, SearchWebPolicy.BODY_MAX_CHARS)

        // site 限定域本身也要在白名单（负例③：无白名单直接抓任意域 ⇒ 拒）
        if (site != null) {
            val siteUrl = "https://${SearchWebPolicy.normalizeHost(site)}/"
            if (!SearchWebPolicy.allowed(siteUrl)) {
                return ToolOutcome.Failed(
                    "site「$site」不在白名单里（只抓公开站点；内网与本机地址一律拒绝）",
                )
            }
        }
        // 检索本身打 DDG；日志只记哈希（判据：不在日志写下检索原文）
        val q = if (site != null) "$query site:$site" else query
        RunLog.log(SearchWebPolicy.queryLogLine(query, site, maxResults))
        val html = httpGet("https://html.duckduckgo.com/html/?q=" + java.net.URLEncoder.encode(q, "UTF-8"))
            ?: return ToolOutcome.Failed("检索页取不到（网络不可用或被拦）——不是没有结果，是没抓到")
        val hits = SearchWebParse.hits(html, maxResults, nowMs)
        if (hits.isEmpty()) {
            return ToolOutcome.Failed("检索页抓到了但没有可解析的结果（页面形状变了或词太生僻）——如实说，不编链接")
        }
        val arr = JSONArray()
        hits.forEach { h ->
            arr.put(
                JSONObject()
                    .put("title", h.title)
                    .put("url", h.url)
                    .put("snippet", h.snippet)
                    .put("fetchedAt", h.fetchedAt),
            )
        }
        val out = JSONObject().put("results", arr)
        if (bodyChars > 0) {
            val first = hits.first().url
            if (!SearchWebPolicy.allowed(first)) {
                out.put("body", "第一条链接不在白名单（${URI(first).host}），正文未抓")
            } else {
                out.put("body", fetchBody(first, bodyChars) ?: "正文抓不到（取回失败）")
            }
        }
        return ToolOutcome.Ok(out.toString(2))
    }

    private fun httpGet(url: String): String? = runCatching {
        val client = OkHttpClient.Builder()
            .connectTimeout(NET_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(NET_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.string()
        }
    }.getOrNull()

    /** 抓正文前 N 字（去 script/style/标签；不改页面、不登录、不提交）。 */
    private fun fetchBody(url: String, maxChars: Int): String? {
        val html = httpGet(url) ?: return null
        val text = html
            .replace(Regex("(?is)<(script|style|noscript)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("<[^>]*>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return text.take(maxChars).ifBlank { null }
    }
}
