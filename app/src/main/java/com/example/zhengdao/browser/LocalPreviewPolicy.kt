// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单 2026-10-19 第五十九批 §5（玄览「本地预览」页签：只显示工作区里的文件，
// 默认断网、默认不跑脚本）＋ §7（许愿式编程 W6：抱朴能写页面，产物在玄览里**看**，
// App 不替它执行）。用户口径逐字：「只显示、不运行哦」。
//
// 为什么单开一个纯函数文件（而不是把判据写在 WebViewEngine / BrowserTabManager 里）：
// §5 的验收要「抓不到任何 outbound http(s)」「禁 JS 时脚本不跑」「越界路径拒」「非 HTML
// 按文本」「改过的内容是新的不是缓存」——这些**全是判据**，而 WebView 在 JVM 单测里造不出
// 来（android.webkit 是桩）。把判据收在零 Android 的本文件，单测直接打到真行为；
// 引擎侧只剩「照本文件的回答执行」那一层薄壳（由源码门禁 LocalPreviewTabTest 钉住）。
//
// 三条设计口径：
// 1. **只显示**：断网靠 [blocksResource]（资源级）+ [allowsNavigation]（导航级）两条通道
//    一起拦，不是靠「用户别点链接」；页面里写的 `<script>` 在默认档根本不会跑（JS 关）。
// 2. **一个字节都不碰上网页签**：正常标签仍走 `EngineSecurityConfig()` 零参构造
//    （[EngineSecurityConfigDefaultsTest] 钉默认值、LocalPreviewTabTest 钉「预览档与默认档
//    只差那几项」）；预览档由 [configFor] 单独产，只有预览标签用得上。
// 3. **内容整段注入**（`loadDataWithBaseURL`），不走 `file://`：② 台账早已定死
//    `allowFileAccess=false` 是红线（§4.4「文件访问禁用」行 +
//    [EngineSecurityConfigDefaultsTest]），开它换预览 = 拆安全默认值。
//    注入的代价是"页面里的相对资源（./a.css、./img.png）加载不出来"——本地预览是
//    **单文件预览**，这个取舍写在这里，不假装它能看整站。
package com.example.zhengdao.browser

/**
 * 本地预览页签的显示模式（§5.3「源码查看／渲染预览切换」）。
 *
 * 只有两个值，且**源码模式永远可用**：渲染模式是"尽力把 HTML 排版出来"，
 * 源码模式是"文件本来长什么样就显示什么"——后者是后者的兜底，所以 §5 验收里
 * 「非文本/非 HTML 按文本显示」不需要第三态（[defaultMode] 已经把非 HTML 判给 SOURCE）。
 */
enum class PreviewMode {
    /** 纯文本源码（等宽、自动换行、可复制）。 */
    SOURCE,

    /** 按 HTML 排版（把文件内容原样交给 WebView 渲染）。 */
    RENDER,
}

/**
 * 本地预览的判据本体（**零 Android**：全是 String/Boolean，JVM 直测）。
 */
object LocalPreviewPolicy {

    /**
     * 预览页签的"地址"用的假 scheme。
     *
     * 为什么要有它：页签地址栏/标签条得显示"这是哪个文件"，但那**不是一个能导航的 URL**
     * ——它进不了 [allowsNavigation]（scheme 不是 http/https ⇒ 引擎拒投），也不会被用户
     * 误当成网址。显示与可导航，分成两件事。
     */
    const val PREVIEW_SCHEME = "local-preview"

    /** 预览页签显示的地址（只给人看，不喂 WebView）。 */
    fun previewUrl(relPath: String): String = "$PREVIEW_SCHEME://$relPath"

    /** 是否预览假地址（[previewUrl] 的反向）。 */
    fun isPreviewUrl(url: String): Boolean = url.startsWith("$PREVIEW_SCHEME://")

    /**
     * 取 URL 的 scheme（小写；没有 scheme 返回 null）。
     *
     * 纯手写不 import `android.net.Uri`：本文件要在 JVM 单测里跑（引擎侧那套判据的牙口
     * 全在这里，一旦依赖 android.net 就全部测不到）。规则与 Uri 一致的够用子集：
     * 首个 `:` 之前、且不含 `/` `?` `#` 的那一段才是 scheme（否则
     * `cn.bing.com:8080/path` 的端口号会被错认成 scheme）。
     */
    fun schemeOf(url: String): String? {
        val cut = url.indexOf(':')
        if (cut <= 0) return null
        val head = url.substring(0, cut)
        if (head.any { it == '/' || it == '?' || it == '#' }) return null
        return head.lowercase()
    }

    /** 是否走网络（http/https）——断网档要拦的就是这一条通道。 */
    fun isNetwork(url: String): Boolean = schemeOf(url) in NETWORK_SCHEMES

    /**
     * **资源级**拦不拦（`shouldInterceptRequest` 用）。两档分开判，但**判据只在这里**：
     *
     * - 断网档（预览页签）：除 [OFFLINE_SAFE_SCHEMES] 外一律换空响应。
     *   这一档比派工单 §5.2 那句「拦掉所有 http/https」**更严**：`ws://`、`wss://`、
     *   `ftp://`、`file://` 同样出不了去（它们也是出站通道，只放行内数据不算断网）。
     *   同时放过 `data:` —— 单文件页面里内联的 base64 图片很常见，
     *   把它也拦了会渲染出一张"看着像坏掉"的图，那种假象比省下一条规则更坑人。
     * - 上网档（正常页签）：**逐字节等于改前的行为**——原来写的是
     *   `scheme in setOf("http","https")`，这里返回它的反面 `!isNetwork(url)`，
     *   同一张白名单、同一个判据（§5 第一条验收：正常标签一个字节都不改）。
     */
    fun blocksResource(url: String, blockAllNetwork: Boolean): Boolean =
        if (blockAllNetwork) schemeOf(url) !in OFFLINE_SAFE_SCHEMES else !isNetwork(url)

    /**
     * **导航级**放不放（`loadUrl` 与 `shouldOverrideUrlLoading` 用）。
     *
     * 三档一起判：scheme 必须是 http/https（原红线不变），且预览档一律拒
     * ⇒「脚本自己 location.href 跳转」「用户点页面里的链接」两条路都出不了本页。
     * 内容注入走 [document] + `loadDataWithBaseURL`，**不经过**这一条（否则自锁）。
     */
    fun allowsNavigation(url: String, blockAllNetwork: Boolean): Boolean =
        isNetwork(url) && !blockAllNetwork

    /**
     * 预览档的引擎配置（§5.2 + §5.3）。
     *
     * 与默认档（= 上网页签那一档）的**差集只有四项**，逐项说清为什么：
     * - [EngineSecurityConfig.javaScriptEnabled] = [jsSandbox]：默认档开（浏览器核心能力），
     *   预览默认关（「不执行、不跑脚本」）；用户显式开沙箱时才带脚本，**仍断网**；
     * - [EngineSecurityConfig.blockAllNetwork] = true：预览页签出不了网（§5.2 的硬拦）；
     * - [EngineSecurityConfig.domStorageEnabled] = false：预览页不该留下 localStorage 痕迹
     *   （它是"看文件"，不是"逛网站"；默认档为 true 是页面基本功）；
     * - [EngineSecurityConfig.databaseEnabled] = false / WebSQL 同理，默认档已是 false，这里显式写出来。
     *
     * 其余（文件访问三连、Safe Browsing、混合内容、第三方 cookie、JS 接口白名单）**照默认档
     * 的加固值不动**——不是省事，是不让"预览"成为放宽红线的入口。
     *
     * @param jsSandbox §5.3 的可选「JS 沙箱」开关：true = 这个页签允许脚本跑（仍断网）。
     */
    fun configFor(jsSandbox: Boolean): EngineSecurityConfig = EngineSecurityConfig(
        javaScriptEnabled = jsSandbox,
        blockAllNetwork = true,
        domStorageEnabled = false,
        databaseEnabled = false,
    )

    /**
     * 是否"按 HTML 排版来看"（§5 验收「非文本/非 HTML 按文本显示」的那条分界）。
     *
     * 只认 .html/.htm：Markdown 也算"非 HTML"。理由不是偷懒——渲染 .md 需要转换器
     * （新依赖）或页面内脚本（与"默认不跑脚本"自相矛盾）。.md 在预览里看源码是诚实的，
     * 假装排版好了才是坑。
     */
    fun isHtmlLike(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in HTML_SUFFIXES

    /** 打开时的默认模式：HTML 先排版看，其余先当源码看（用户随时可切）。 */
    fun defaultMode(fileName: String): PreviewMode =
        if (isHtmlLike(fileName)) PreviewMode.RENDER else PreviewMode.SOURCE

    /**
     * 交给 WebView 的文档（渲染档原样、源码档包一层壳）。
     *
     * - [PreviewMode.RENDER]：**逐字返回文件内容**，不加 meta、不改编码、不注入任何东西。
     *   页面自带的 viewport/charset 声明就是它的原样表现；编码由调用侧
     *   `loadDataWithBaseURL(..., "utf-8", ...)` 声明（文件内容进来前已由
     *   [com.example.zhengdao.baopu.agent.TextDecode] 解成文本）。
     * - [PreviewMode.SOURCE]：转义后塞进 `<pre>`——**转义必须做**，否则一段 HTML 源码
     *   在源码模式下会被当成标记解析，"看源码"就变成"看渲染"，两态互相吞掉。
     */
    fun document(fileName: String, text: String, mode: PreviewMode): String = when (mode) {
        PreviewMode.RENDER -> text
        PreviewMode.SOURCE -> sourceDocument(fileName, text)
    }

    /** 源码档的壳（一个容器，不改内容：文件名写在头一行，正文整段转义）。 */
    private fun sourceDocument(fileName: String, text: String): String =
        "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
            "<title>" + escapeHtml(fileName) + "</title>" +
            "<style>" +
            // 深色友好：不写死白底黑字（第㊱刀那条对比度教训）——跟随系统 prefers-color-scheme。
            "html,body{margin:0;padding:0;background:#fff;color:#111}" +
            "@media (prefers-color-scheme:dark){html,body{background:#111;color:#eee}}" +
            "pre{margin:0;padding:12px;white-space:pre-wrap;word-break:break-word;" +
            "font:13px/1.55 ui-monospace,Menlo,Consolas,monospace}" +
            "</style></head><body><pre>" + escapeHtml(text) + "</pre></body></html>"

    /**
     * HTML 文本节点转义（[sourceDocument] 用；纯函数，单测直接打）。
     *
     * 顺序只有一条：`&` 必须第一个换——后换会把已换好的 `&lt;` 再换一层（`&amp;lt;`）。
     */
    fun escapeHtml(raw: String): String {
        val sb = StringBuilder(raw.length + 16)
        for (c in raw) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&#39;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private val NETWORK_SCHEMES = setOf("http", "https")

    /**
     * 断网档唯一放过的两类 scheme：内容都在 URL 自己肚子里，一个包都不出。
     * `about:blank` 是注入通道（[WebViewEngine.loadLocalDocument] 的 baseURL）；
     * `data:` 是页面内联资源。除此之外的任何 scheme 都算"想出去"。
     */
    private val OFFLINE_SAFE_SCHEMES = setOf("about", "data")
    private val HTML_SUFFIXES = setOf("html", "htm")
}
