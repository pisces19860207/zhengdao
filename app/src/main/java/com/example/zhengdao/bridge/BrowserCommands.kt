package com.example.zhengdao.bridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import com.example.zhengdao.browser.PageSnapshot
import com.example.zhengdao.browser.SensitiveRedactor
import com.example.zhengdao.browser.WebViewEngine
import com.example.zhengdao.terminal.Store
import com.example.zhengdao.ui.browser.BrowserTabManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 11 条命令的执行层（设计文档 §4.5「命令清单」）。
 *
 * ## 分层
 *
 * ```
 * BrowserAgentBridge（HTTP：验 token + 解析 body + 路由）
 *        │  解析 body → (命令名, 参数串)
 *        ▼
 * BridgePolicy（白名单 + 参数校验）   ← 纯函数，已单测
 *        ▼
 * BrowserCommands（本类：把参数变成 WebView / 标签操作，返回 JSON 字符串）
 *        ▼
 * BrowserTabManager / WebViewEngine / PageSnapshot / SensitiveRedactor
 * ```
 *
 * ## 为什么参数全是 String、返回全是 String
 *
 * ① **不引 org.json**：它在 `android.jar` 里是 stub，JVM 单测一碰就抛 `Stub!`
 *    （ERRATA E-130 同款坑）。body 的 JSON 解析放在 HTTP 层（要 Context 的那侧），
 *    本类只吃"已经取出来的字符串"。
 * ② 返回手写 JSON 而不是序列化库：字段就那几个，手写 + [PageSnapshot.jsonEscape]
 *    比引一个依赖划算（项目零新增依赖纪律）。
 *
 * ## 两条安全纪律（红线⑦，agent 不可绕）
 *
 * 1. **离开桥的数据一律过 [SensitiveRedactor]**：`execJS` 的返回值、`snapshot` 的正文与
 *    DOM 都要过一遍才进响应体。PII（手机号/卡号/身份证/密码框）被打成星号 ——
 *    agent 权限再高也拿不到原始隐私值（设计文档 §4.5 硬红线⑦）。
 * 2. **超长必截**：结果串截到 [BridgePolicy.MAX_RESULT_CHARS]，防 agent 一次拉爆内存。
 *
 * ## 线程约定
 *
 * WebView 的所有操作都必须在主线程。本类被 HTTP 工作线程调用，所以每条命令都
 * [onMain]：post 到主线程执行、用闩同步等结果（[EVAL_TIMEOUT_MS] 超时 ⇒ 当失败，
 * 不把桥的线程永久拖住）。
 * ⚠️ **不要在主线程内调本类**——那会自己等自己（死锁）。生产里调用方是 HTTP 线程。
 *
 * ## 一处对设计文档的有意偏离（截图落点）
 *
 * 设计文档 §4.5 写"输出 PNG 写入系统截图目录 `Pictures/Screenshots`"。
 * **这里改成共享目录 `Store.root()/screenshots`**（＝ Downloads/证道，proot 已 bind 到
 * guest `/opt/zhengdao/`）。理由：本桥的全部意义就是"给 guest 里的 agent 用"，
 * 而 `Pictures/Screenshots` 在 guest 里**读不到**（仅私有工作区档位下 `/sdcard` 根本不挂载）；
 * 落共享目录是 agent 唯一能稳定取到截图的路径。若将来要兼顾人看，可同时落两份。
 */
object BrowserCommands {

    private val main = Handler(Looper.getMainLooper())

    /** WebView 回调等待上限（毫秒）。页面 JS 卡死时不该把桥的线程全拖住。 */
    private const val EVAL_TIMEOUT_MS = 8_000L

    // ── 线程桥接 ───────────────────────────────────────────────────────────

    /** 在主线程跑 [block]，阻塞等它返回（超时/异常 ⇒ null）。 */
    private fun <T> onMain(block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null // 自己等自己会死锁，见类 KDoc
        val box = arrayOfNulls<Any>(1)
        val latch = CountDownLatch(1)
        main.post {
            runCatching { box[0] = block() }.onFailure { box[0] = it }
            latch.countDown()
        }
        runCatching { latch.await(EVAL_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        @Suppress("UNCHECKED_CAST")
        return box[0] as T?
    }

    /**
     * 在主线程跑 `evaluateJavascript` 并同步拿**字符串化后的**结果。
     *
     * 归一化：JS 返回的字符串带一层引号（`"abc"`）、`null`/未回调都归一成 null。
     */
    private fun evalJs(webView: WebView, script: String): String? = onMain {
        val latch = CountDownLatch(1)
        val box = arrayOfNulls<String>(1)
        webView.evaluateJavascript(script) { box[0] = it; latch.countDown() }
        runCatching { latch.await(EVAL_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        normalizeJsResult(box[0])
    }

    private fun normalizeJsResult(raw: String?): String? {
        if (raw.isNullOrEmpty() || raw == "null") return null
        // evaluateJavascript 对字符串结果会包一层双引号（且内部引号已转义）
        return if (raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            raw.substring(1, raw.length - 1)
        } else {
            raw
        }
    }

    // ── JSON 小工具 ────────────────────────────────────────────────────────

    private fun ok(vararg kv: Pair<String, String>): String {
        val body = kv.joinToString(",") { (k, v) -> "\"$k\":\"${PageSnapshot.jsonEscape(v)}\"" }
        return "{\"ok\":true,$body}"
    }

    private fun okRaw(body: String): String = "{\"ok\":true,$body}"

    private fun err(msg: String): String = "{\"ok\":false,\"error\":\"${PageSnapshot.jsonEscape(msg)}\"}"

    /** 截到上限并标注（设计文档 §4.5：结果字符串化且限长）。 */
    private fun clamp(s: String): String =
        if (s.length <= BridgePolicy.MAX_RESULT_CHARS) s
        else s.take(BridgePolicy.MAX_RESULT_CHARS) + "…[截断，原长 ${s.length} 字符，上限 ${BridgePolicy.MAX_RESULT_CHARS}]"

    // ── 命令 ───────────────────────────────────────────────────────────────

    /** `status`：活跃标签的 url/title/loading + 标签总数。 */
    fun status(): String {
        val tab = BrowserTabManager.activeTab() ?: return err("没有活跃标签")
        val st = tab.state
        return ok(
            "url" to st.url,
            "title" to st.title,
            "loading" to st.loading.toString(),
            "tabId" to tab.id.toString(),
            "tabCount" to BrowserTabManager.count().toString(),
        )
    }

    /**
     * `snapshot`：mode=text → 可访问性摘要（复用第⑲刀 [PageSnapshot.summarize]）；
     * mode=dom → 脱敏后的 DOM；mode=full → 摘要 + DOM 一起。
     *
     * 第⑲刀做的"眼睛"（只读导出）在这里直接复用 —— 同一份代码服务两个入口
     * （用户点眼睛手动导出 / agent 经桥拉取），不必写第二遍。
     *
     * selector 参数**预留**（将来"按元素取快照"用），当前忽略但校验其合法性。
     */
    fun snapshot(mode: String?, selector: String?): String {
        val v = BridgePolicy.checkSnapshotMode(mode)
        if (v is BridgePolicy.Verdict.Deny) return err(v.reason)
        selector?.let { s ->
            val sv = BridgePolicy.checkSelector(s)
            if (sv is BridgePolicy.Verdict.Deny) return err(sv.reason)
        }
        val tab = BrowserTabManager.activeTab() ?: return err("没有活跃标签")
        val m = mode?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: "text"
        val html = fetchOuterHtml(tab.webView) ?: return err("取不到页面 DOM（页面可能还在加载）")
        // 管线与第⑲刀落盘侧一致：先摘凭据 → 再打 PII
        val clean = SensitiveRedactor().redactDom(PageSnapshot.scrubSecrets(html))
        val summary = PageSnapshot.summarize(clean)
        val head = "\"title\":\"${PageSnapshot.jsonEscape(summary.title)}\"," +
            "\"text\":\"${PageSnapshot.jsonEscape(clamp(summary.text))}\"," +
            "\"nodes\":${renderNodes(summary)}"
        return when (m) {
            "dom" -> okRaw("\"dom\":\"${PageSnapshot.jsonEscape(clamp(clean))}\"")
            "full" -> okRaw("$head,\"dom\":\"${PageSnapshot.jsonEscape(clamp(clean))}\"")
            else -> okRaw(head)
        }
    }

    private fun renderNodes(s: PageSnapshot.Summary): String =
        s.nodes.joinToString(",", "[", "]") { n ->
            "{\"role\":\"${PageSnapshot.jsonEscape(n.role)}\"," +
                "\"name\":\"${PageSnapshot.jsonEscape(n.name)}\"," +
                "\"value\":\"${PageSnapshot.jsonEscape(n.value)}\"," +
                "\"states\":\"${PageSnapshot.jsonEscape(n.states)}\"}"
        }

    /**
     * `execJS`：页面内 JS 自由执行（高权限区，含读 cookie/localStorage）。
     *
     * 返回值**必须**过 [SensitiveRedactor]（红线⑦）：agent 不能用它读原始隐私值绕过脱敏。
     */
    fun execJs(code: String?): String {
        if (code.isNullOrBlank()) return err("execJS 缺 code")
        val tab = BrowserTabManager.activeTab() ?: return err("没有活跃标签")
        val script = "(function(){try{return String(eval(${PageSnapshot.jsonEscape(code)}))}catch(e){return 'ERR:'+e}})()"
        val result = evalJs(tab.webView, script) ?: return err("JS 执行超时或失败")
        val redacted = SensitiveRedactor().redactText(result)
        return okRaw("\"result\":\"${PageSnapshot.jsonEscape(clamp(redacted))}\"")
    }

    /** `click`：点一个元素（selector 已被 [BridgePolicy.checkSelector] 拦过引号/反斜杠）。 */
    fun click(selector: String?): String {
        val v = BridgePolicy.checkSelector(selector)
        if (v is BridgePolicy.Verdict.Deny) return err(v.reason)
        val tab = BrowserTabManager.activeTab() ?: return err("没有活跃标签")
        val js = "(function(){var el=document.querySelector(${PageSnapshot.jsonEscape(selector!!)});" +
            "if(!el)return 'NOT_FOUND';el.click();return 'OK'})()"
        return when (val r = evalJs(tab.webView, js)) {
            "OK" -> ok("clicked" to selector)
            "NOT_FOUND" -> err("找不到元素：$selector")
            null -> err("执行超时或失败")
            else -> err("意外结果：$r")
        }
    }

    /**
     * `type`：往任意输入框写文本（**含密码框** —— 高权限区，设计文档明确放开）。
     *
     * text 经 [PageSnapshot.jsonEscape] 变成 JS 字符串字面量再嵌入 ⇒ 引号/换行/反斜杠
     * 都不会逃逸（这是"任意文本能安全进 JS"的正路，别用字符串拼接）。
     * 走原生 value setter 而不是 `el.value=`：React/Vue 受控组件监听的是原型链上的
     * setter，直接赋值不触发它们的 onChange（页面就像没收到输入）。
     */
    fun type(selector: String?, text: String?): String {
        val v = BridgePolicy.checkSelector(selector)
        if (v is BridgePolicy.Verdict.Deny) return err(v.reason)
        if (text == null) return err("type 缺 text")
        val tab = BrowserTabManager.activeTab() ?: return err("没有活跃标签")
        val js = "(function(){var el=document.querySelector(${PageSnapshot.jsonEscape(selector!!)});" +
            "if(!el)return 'NOT_FOUND';" +
            "var desc=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el),'value');" +
            "if(desc&&desc.set)desc.set.call(el,${PageSnapshot.jsonEscape(text)});" +
            "else el.value=${PageSnapshot.jsonEscape(text)};" +
            "el.dispatchEvent(new Event('input',{bubbles:true}));" +
            "el.dispatchEvent(new Event('change',{bubbles:true}));" +
            "return 'OK'})()"
        return when (val r = evalJs(tab.webView, js)) {
            "OK" -> ok("typed" to selector)
            "NOT_FOUND" -> err("找不到元素：$selector")
            null -> err("执行超时或失败")
            else -> err("意外结果：$r")
        }
    }

    /** `scroll`：按坐标（x,y）或按元素（selector 滚进视口）。 */
    fun scroll(x: String?, y: String?, selector: String?): String {
        val tab = BrowserTabManager.activeTab() ?: return err("没有活跃标签")
        val js = if (!selector.isNullOrBlank()) {
            val v = BridgePolicy.checkSelector(selector)
            if (v is BridgePolicy.Verdict.Deny) return err(v.reason)
            "(function(){var el=document.querySelector(${PageSnapshot.jsonEscape(selector)});" +
                "if(!el)return 'NOT_FOUND';el.scrollIntoView({behavior:'instant',block:'center'});return 'OK'})()"
        } else {
            val xi = x?.trim()?.toIntOrNull() ?: 0
            val yi = y?.trim()?.toIntOrNull() ?: 0
            "(function(){window.scrollTo($xi,$yi);return 'OK'})()"
        }
        return when (val r = evalJs(tab.webView, js)) {
            "OK" -> ok("scrolled" to (selector ?: "$x,$y"))
            "NOT_FOUND" -> err("找不到元素：$selector")
            null -> err("执行超时或失败")
            else -> err("意外结果：$r")
        }
    }

    /** `navigate`：加载任意 http/https（危险 scheme 由浏览器本身拦；桥这侧也显式拒）。 */
    fun navigate(url: String?): String {
        val v = BridgePolicy.checkNavigateUrl(url)
        if (v is BridgePolicy.Verdict.Deny) return err(v.reason)
        val tab = BrowserTabManager.activeTab() ?: return err("没有活跃标签")
        tab.engine.loadUrl(url!!.trim())
        return ok("navigating" to url)
    }

    /** `newTab`：开标签（url 可省＝空标签）；返回新标签 id。 */
    fun newTab(context: Context, url: String?): String {
        url?.let {
            val v = BridgePolicy.checkNavigateUrl(it)
            if (v is BridgePolicy.Verdict.Deny) return err(v.reason)
        }
        val id = BrowserTabManager.newTab(context, url?.trim())
            ?: return err("开标签失败（可能已达上限 ${com.example.zhengdao.browser.BrowserTabPolicy.MAX_TABS}）")
        return ok("tabId" to id.toString(), "tabCount" to BrowserTabManager.count().toString())
    }

    /** `activeTab`：切换 agent 操作的活跃标签。 */
    fun activeTab(tabId: String?): String {
        val id = tabId?.trim()?.toIntOrNull() ?: return err("activeTab 缺 tabId（数字）")
        if (BrowserTabManager.tabs().none { it.id == id }) return err("没有这个标签：$id")
        BrowserTabManager.selectTab(id)
        return ok("tabId" to id.toString(), "tabCount" to BrowserTabManager.count().toString())
    }

    /** `closeTab`：关标签（最后一个标签关不掉——WebView 宿主至少要有一个）。 */
    fun closeTab(tabId: String?): String {
        val id = tabId?.trim()?.toIntOrNull() ?: return err("closeTab 缺 tabId（数字）")
        if (BrowserTabManager.tabs().none { it.id == id }) return err("没有这个标签：$id")
        if (BrowserTabManager.count() <= 1) return err("只剩一个标签，不能关（WebView 宿主至少要有一个）")
        BrowserTabManager.closeTab(id)
        return ok("closed" to id.toString(), "tabCount" to BrowserTabManager.count().toString())
    }

    /**
     * `screenshot`：对活跃（或指定）标签截屏，PNG 落**共享目录**（见类 KDoc 的偏离说明），
     * 返回相对路径（agent 从 guest `/opt/zhengdao/screenshots/<file>` 读）。
     */
    fun screenshot(context: Context, tabId: String?, fullPage: String?): String {
        val tab = if (tabId.isNullOrBlank()) {
            BrowserTabManager.activeTab()
        } else {
            val id = tabId.trim().toIntOrNull() ?: return err("screenshot 的 tabId 不是数字")
            BrowserTabManager.tabs().firstOrNull { it.id == id }
        } ?: return err("没有这个标签")
        val bytes = captureScreenshot(tab.webView) ?: return err("截屏失败（页面可能还没绘制）")
        val dir = File(Store.root(context), "screenshots").apply { mkdirs() }
        val name = "bridge-${System.currentTimeMillis()}.png"
        return runCatching { File(dir, name).writeBytes(bytes) }.fold(
            onSuccess = {
                ok(
                    "file" to "screenshots/$name",
                    "bytes" to bytes.size.toString(),
                    "guestPath" to "/opt/zhengdao/screenshots/$name",
                )
            },
            onFailure = { err("写截图失败：${it.message}") },
        )
    }

    // ── WebView 桥接小工具 ─────────────────────────────────────────────────

    /** 取渲染后 DOM（与第⑲刀导出器同一条路径：原生 evaluateJavascript）。 */
    private fun fetchOuterHtml(webView: WebView): String? =
        evalJs(webView, "document.documentElement.outerHTML")

    private fun captureScreenshot(webView: WebView): ByteArray? = onMain {
        val w = webView.width.coerceAtLeast(1)
        val h = webView.height.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        webView.draw(Canvas(bitmap))
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        out.toByteArray()
    }
}
