package com.example.zhengdao.ui.browser

import android.content.Context
import android.webkit.WebView
import com.example.zhengdao.browser.PageSnapshot
import com.example.zhengdao.browser.SensitiveRedactor
import com.example.zhengdao.terminal.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/**
 * 玄览「导出给 agent」——把眼前这个标签的渲染结果，落成一组 guest 里能读的文件。
 *
 * 第⑲刀（「给 agent 的眼睛」第一版 · **只读**）。设计依据：台账 `docs/FEATURE-LEDGER.md`
 * §B-补-52（她 2026-10-14 拍板前挂起、第⑲刀派工单解禁）——结论③写明「真正的缝是用你已登录
 * 的那个会话读：`curl` 读不到登录后的页面，而证道自己就是 WebView 宿主」。
 *
 * ## 落点
 * [Store.root]（= `Download/证道`；`ProotLauncher.kt:533` 把它 bind 到 guest 的
 * `/opt/zhengdao`）。因此**不新开目录、不碰 `Store.kt`**，guest 里 `cat /opt/zhengdao/tab.txt`
 * 就是同一份文件。写盘走「临时名 + rename」（本项目的原子写口径），避免 agent 读到半截文件。
 *
 * ## 为什么绕开 [com.example.zhengdao.browser.WebViewEngine.evaluateJavascript]
 * 引擎那个入口有两条**刻意**的约束，都与本刀需求冲突：
 * 1. **返回值限长 64KB**（`WebViewEngine.MAX_RESULT_LENGTH`，§4.5 防 OOM）——本刀要求 tab.html
 *    上限 512KB；
 * 2. **返回值强制过 [SensitiveRedactor] 的 PII 打码**——它会把页面正文里形如手机号/卡号的长数字
 *    串整个换成 `***`。这正是它该做的（用户 2026-10-13 定调「快照里的密码/卡号/手机号强制脱敏」），
 *    但它同时也会把**订单号 / 文件哈希 / 页面 ID** 打成一片星号，让 agent 读不懂"这页面是干什么的"。
 *
 * 所以本导出走 [WebView] 的原生 `evaluateJavascript` 取原文，**脱敏改在 Kotlin 侧对整段文本
 * 一次性做**——顺序是「先摘凭据（[PageSnapshot.scrubSecrets]）→ 再打 PII
 * （[SensitiveRedactor.redactDom]）→ 然后才摘要」。这样：
 * - 摘要与落盘文件都建立在**已过滤**的文本上，没有任何"先摘要后过滤"的漏网路径；
 * - 脱敏对整段生效，不存在分块边界把一串手机号劈成两半而漏掉的窗口。
 *
 * ## 取原文为什么要 base64 + 分块
 * `document.documentElement.outerHTML` 可能几百 KB。`evaluateJavascript` 的返回值走 Binder
 * （约 1MB 事务上限），且 JS 字符串要 JSON 转义（中文一个字变 `\uXXXX` 六个字符）——直接整段
 * 返回在中文页面上很容易炸。这里让 JS 侧先 `btoa`（UTF-8 安全的 base64，纯 ASCII，JSON 转义
 * 后长度不变），再按 10 万字符一块分多次取回。JS 侧只编码一次、Kotlin 侧拼完再解一次。
 */
object BrowserPageExporter {

    /** 三个落盘文件名（派工单逐字）。 */
    const val FILE_TXT = "tab.txt"
    const val FILE_HTML = "tab.html"
    const val FILE_META = "meta.json"

    /** guest 侧看到的同一目录（提示文案用；真身在 [Store.GUEST_ROOT]）。 */
    const val GUEST_DIR = Store.GUEST_ROOT

    /** 每次 evaluateJavascript 取回多少 base64 字符（纯 ASCII，转义后不膨胀）。 */
    private const val CHUNK = 100_000

    /**
     * 一次导出的结果（给 UI 拼提示文案）。
     * @param dir 落盘目录（宿主路径）
     * @param nodes 可交互节点数
     */
    data class Outcome(
        val dir: String,
        val nodes: Int,
        val htmlBytes: Int,
        val txtBytes: Int,
        val truncated: Boolean,
    )

    /**
     * 把 [tab] 的当前页面导出到共享目录。**必须在主线程调用**（`evaluateJavascript` 要求 UI 线程）；
     * 写盘走 [Dispatchers.IO]。
     *
     * @param tabIndex 当前标签序号（1 起）
     * @param tabCount 标签总数
     * @throws IllegalStateException 取不到页面内容（脚本失败 / 页面还没加载 / 共享目录不可写）
     */
    suspend fun export(
        context: Context,
        tab: BrowserTabManager.Tab,
        tabIndex: Int,
        tabCount: Int,
    ): Outcome {
        val webView = tab.webView

        val b64Len = evalJs(webView, PREPARE_JS)?.trim('"')?.toIntOrNull() ?: -1
        if (b64Len < 0) throw IllegalStateException("页面脚本执行失败（网页可能禁止了脚本）")

        val sb = StringBuilder(if (b64Len > 0) b64Len else 16)
        var offset = 0
        while (offset < b64Len) {
            val end = minOf(offset + CHUNK, b64Len)
            val chunk = evalJs(webView, "window.__zdEyeB64.substring(${offset},${end})")
                ?: throw IllegalStateException("读取页面内容失败")
            sb.append(chunk.removeSurrounding("\""))
            offset = end
        }

        val rawHtml = if (b64Len == 0) "" else decodeBase64(sb.toString())
        if (rawHtml.isBlank()) throw IllegalStateException("页面还没加载出来")

        val meta = evalJs(webView, "window.__zdEyeMetaB64")
            ?.removeSurrounding("\"")
            ?.let { runCatching { JSONObject(decodeBase64(it)) }.getOrNull() }
        val url = meta?.optString("u").orEmpty()
        val jsTitle = meta?.optString("t").orEmpty()
        val fullLen = meta?.optInt("n", rawHtml.length) ?: rawHtml.length

        return withContext(Dispatchers.IO) {
            writeAll(context, rawHtml, fullLen, url, jsTitle, tabIndex, tabCount)
        }
    }

    // ── 落盘 ──────────────────────────────────────────────────────────────

    private fun writeAll(
        context: Context,
        rawHtml: String,
        fullLen: Int,
        url: String,
        jsTitle: String,
        tabIndex: Int,
        tabCount: Int,
    ): Outcome {
        val root = Store.root(context)
        if (!root.isDirectory && !root.mkdirs()) throw IllegalStateException("共享目录不可写")

        // 明文禁项（先摘凭据）+ 用户定调的 PII 打码（后打手机号/卡号/密码框）——顺序不可换：
        // scrubSecrets 处理的是"键名像凭据"的结构，redactDom 处理的是"值像隐私"的内容。
        val clean = SensitiveRedactor().redactDom(PageSnapshot.scrubSecrets(rawHtml))
        val (html, byteCut) = PageSnapshot.clampHtmlBytes(clean)
        val summary = PageSnapshot.summarize(html)

        val exportedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val title = jsTitle.ifBlank { summary.title }
        val htmlBytes = html.toByteArray(Charsets.UTF_8).size
        // JS 侧按字符预截过（fullLen 是截断前的真实长度），Kotlin 侧再按字节卡一次
        val htmlTruncated = byteCut || fullLen > rawHtml.length

        val txt = PageSnapshot.renderTxt(summary, url, exportedAt, tabIndex, tabCount)
        val metaJson = PageSnapshot.renderMetaJson(
            url = url,
            title = title,
            exportedAt = exportedAt,
            tabIndex = tabIndex,
            tabCount = tabCount,
            htmlTruncated = htmlTruncated,
            htmlBytes = htmlBytes,
            textTruncated = summary.textTruncated,
            nodes = summary.nodesTotal,
        )

        writeAtomic(File(root, FILE_HTML), html)
        writeAtomic(File(root, FILE_TXT), txt)
        writeAtomic(File(root, FILE_META), metaJson)
        return Outcome(
            dir = root.absolutePath,
            nodes = summary.nodesTotal,
            htmlBytes = htmlBytes,
            txtBytes = txt.toByteArray(Charsets.UTF_8).size,
            truncated = htmlTruncated || summary.textTruncated,
        )
    }

    /** 原子写：先写 `<名字>.part` 再改名（失败清掉半截，正式名不受污染）。 */
    private fun writeAtomic(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".part")
        try {
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } catch (t: Throwable) {
            runCatching { tmp.delete() }
            throw t
        }
    }

    // ── JS 侧 ─────────────────────────────────────────────────────────────

    private suspend fun evalJs(webView: WebView, script: String): String? =
        suspendCancellableCoroutine { cont ->
            runCatching {
                webView.evaluateJavascript(script) { value ->
                    if (cont.isActive) {
                        cont.resume(if (value == null || value == "null") null else value)
                    }
                }
            }.onFailure {
                // 调用线程不对 / WebView 已销毁等：当成"取不到"，让上层给人话文案
                if (cont.isActive) cont.resume(null)
            }
        }

    private fun decodeBase64(s: String): String =
        String(Base64.getDecoder().decode(s), Charsets.UTF_8)

    /**
     * 取 DOM 的准备工作（在页面里跑一次）：
     * 1. 取 `document.documentElement.outerHTML`，按 [PageSnapshot.JS_CHAR_LIMIT] 先截一刀
     *    （把传输量钉死在 512K 字符内）；
     * 2. base64（UTF-8 安全：`TextEncoder` → 逐字节 `String.fromCharCode` → `btoa`）；
     * 3. 顺带把 `location.href` / `document.title` / 截断前的真实长度打包成一份 base64 元信息。
     *
     * 返回 base64 串的长度（Kotlin 侧据此分块取），失败返回 -1。
     * 刻意不读 cookie / localStorage / sessionStorage —— 本刀明文禁项，一行都不碰。
     */
    private val PREPARE_JS = """
        (function(){
        try{
        var d=document;var e=d.documentElement;var h=e?e.outerHTML:'';if(h==null){h='';}
        window.__zdEyeLen=h.length;
        var LIM=524288;
        if(h.length>LIM){h=h.substring(0,LIM);}
        function b64(s){try{var b=new TextEncoder().encode(s);var o='';var i=0;var n=b.length;for(;i<n;i++){o+=String.fromCharCode(b[i]);}return btoa(o);}catch(x){return '';}}
        window.__zdEyeB64=b64(h);
        window.__zdEyeMetaB64=b64(JSON.stringify({u:location.href,t:d.title||'',n:window.__zdEyeLen}));
        return window.__zdEyeB64.length;
        }catch(x){return -1;}
        })()
    """.trimIndent()
}
