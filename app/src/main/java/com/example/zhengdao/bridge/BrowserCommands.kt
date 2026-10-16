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
import com.example.zhengdao.rootfs.RunLog
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
 *    DOM、`status`/`navigate` 的 url、`status`/`snapshot` 的 title 都要过一遍才进响应体。
 *    PII（手机号/卡号/身份证/密码框/敏感键值）被打成星号 —— agent 权限再高也拿不到
 *    原始隐私值（设计文档 §4.5 硬红线⑦）。
 * 2. **超长必截**：结果串截到 [BridgePolicy.MAX_RESULT_CHARS]，防 agent 一次拉爆内存。
 *
 * ## 脱敏边界（第48刀A 逐字段核定，2026-10-16）
 *
 * 判据＝「这个字段是不是页面/标签元数据」：**是 ⇒ 必须过脱敏管线；不是 ⇒ 刻意不过**
 * （下文两张清单）。旧版曾把「title 不过管线」写成一句已声明边界，结果后来读代码的人
 * 看见「已声明」就不再核（第48刀A 的教训）⇒ 边界必须**逐字段列全**，不许一句带过。
 *
 * | 响应体字段 | 出口 | 管线 |
 * |---|---|---|
 * | `url` | `status` / `navigate` 回执 | [SensitiveRedactor.redactUrl] |
 * | `title` | `status` / `snapshot` 标题位 | [SensitiveRedactor.redactTabTitle]（redactUrl 两段式 ＋ & 终止键值模式） |
 * | snapshot 正文 | `text` / `nodes` / `dom` | `scrubSecrets`→[SensitiveRedactor.redactDom] |
 * | execJS 返回值 | `result` | `redactText`→[SensitiveRedactor.redactPairs] |
 *
 * **刻意不进脱敏管线的字段（判据＝非页面/标签元数据，PII 无处栖身）**：
 * `loading`（布尔）/ `tabId` / `tabCount`（整数）/ `screenshot` 的 `file`·`bytes`·`guestPath`
 * （本机路径与字节数）/ `click`·`type`·`scroll` 回执里的 `selector`（agent 自带参数的原样
 * 回声，脱了反而破坏与请求的对应关系）。
 * 全类扫描结论（第48刀A 任务③）：BrowserTabState 其余字段（`blockedMsg` / `everLoaded`）
 * 没有任何一条进桥的响应体。**若将来新增出口（如 `tabs` 列表命令）带 tab 元数据，
 * 必须补进上表** —— 门禁 `BridgeTitleRedactTest` 判据 C 按行扫响应体里的
 * `"title" to` / `summary.title`，漏脱敏直接红。
 *
 * ## 线程约定
 *
 * WebView 的所有操作都必须在主线程。本类被 HTTP 工作线程调用，所以每条命令都
 * [onMain]：post 到主线程执行、用闩同步等结果（[EVAL_TIMEOUT_MS] 超时 ⇒ 当失败，
 * 不把桥的线程永久拖住）。
 * ⚠️ **不要在主线程内调本类**——那会自己等自己（死锁）。生产里调用方是 HTTP 线程。
 *
 * 第㉓刀修正：navigate / newTab / activeTab / closeTab 四条控制命令原先在 HTTP
 * 工作线程**直调** [BrowserTabManager] / WebViewEngine（其 KDoc 明写全部公开方法
 * 限定 UI 线程）——newTab 建 WebView 必抛 Looper 崩溃、closeTab 的 destroy 被
 * checkThread 拦下再被吞（静默泄漏）。现已与读命令同轨：manager/engine 交互全部
 * 经 [onMain] 落主线程，主线程异常以 JSON 错误回给 agent（不再有 ClassCastException
 * 被 socket 静默吞掉的路径）。形态门禁见 BrowserBridgeGateTest 的「主线程亲和」。
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

    /**
     * 主线程执行结果（第㉓刀：类型安全出口）。
     *
     * 旧实现的返回值是 `box[0] as T?` —— 主线程操作抛异常时 box 里装的是 Throwable，
     * 调用方按 T 取就吃 ClassCastException，agent 侧只看到 socket 静默关闭。
     * 现在 value / failure 恰好一个非空，调用方必须显式处理失败（4 条控制命令
     * 把它转成 JSON 错误回给 agent）。
     */
    private class MainResult<out T> private constructor(val value: T?, val failure: Throwable?) {
        companion object {
            fun <T> of(value: T?): MainResult<T> = MainResult(value, null)
            fun failed(t: Throwable): MainResult<Nothing> = MainResult(null, t)
        }
    }

    /** 在主线程跑 [block]，阻塞等它返回；超时/异常/误用都以 [MainResult.failure] 回报。 */
    private fun <T> onMain(block: () -> T): MainResult<T> {
        // 自己等自己会死锁，见类 KDoc —— 旧实现静默返回 null，现在当失败显式回报
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return MainResult.failed(IllegalStateException("onMain 不能在主线程内调用（会自己等自己，死锁）"))
        }
        val box = arrayOfNulls<Any>(1)
        val latch = CountDownLatch(1)
        main.post {
            runCatching { block() }.fold(
                onSuccess = { box[0] = MainResult.of(it) },
                onFailure = { box[0] = MainResult.failed(it) },
            )
            latch.countDown()
        }
        val done = runCatching { latch.await(EVAL_TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!done) {
            // §B 61：超时必须落日志——此前超时只对 agent 回 JSON，RunLog 里无痕，
            // 「主线程卡在 WebView 构造」只能靠 before/after 探针间接推。
            runCatching {
                RunLog.log(
                    "浏览器桥：主线程执行超时（>${EVAL_TIMEOUT_MS}ms 未返回，" +
                        "thread=${Thread.currentThread().name}）——按契约回超时错误 JSON",
                )
            }
            return MainResult.failed(java.util.concurrent.TimeoutException("主线程执行超过 ${EVAL_TIMEOUT_MS}ms 未返回"))
        }
        @Suppress("UNCHECKED_CAST")
        return box[0] as MainResult<T>
    }

    /**
     * 在主线程跑 `evaluateJavascript` 并同步拿**字符串化后的**结果。
     *
     * 归一化（第㊷刀）：回调值本身就是 JSON 字面量——字符串结果带引号且内部已转义，
     * 由 [jsResultToString] 去引号并**还原转义**（旧写法只剥引号，标题里的引号、
     * outerHTML 里的换行会带着反斜杠漏出去）；`null`/未回调归一成 null。
     *
     * 第㊵刀（P1 修复）：**闩等待在调用线程，主线程只负责发起**。旧写法把
     * `latch.await` 放在 [onMain] 投到主线程的块内——`evaluateJavascript` 的结果
     * 回调必须由主线程执行，而主线程被 `await` 占着 ⇒ 自己等自己，8 秒必超时
     * （真机实证：execJS 连 `1+1` 都超时、snapshot 报「取不到页面 DOM」、
     * click/type/scroll 全废）。现在：主线程块只调 `evaluateJavascript` 注册回调
     * （不阻塞、立即返回），调用线程（HTTP 工作线程）`latch.await` 等回调写结果。
     * 超时/误用（主线程内调本类）仍归一到 null ⇒ 调用方既有文案「执行超时或失败」
     * 不变，「超时也回 JSON 不断连」的契约不回退。
     */
    private fun evalJs(webView: WebView, script: String): String? {
        val latch = CountDownLatch(1)
        val box = arrayOfNulls<String>(1)
        // 主线程只发起；onMain 的失败（主线程内误用 / 主线程繁忙未执行）按超时归一
        val started = onMain {
            webView.evaluateJavascript(script) { box[0] = it; latch.countDown() }
        }
        if (started.failure != null) return null
        val done = runCatching { latch.await(EVAL_TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        return if (done) jsResultToString(box[0]) else null
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

    /**
     * [target] 的解析结果：要么拿到标签（[tab] 非空），要么带一条错误 JSON 文案（[error] 非空）。
     * 两者必有一个非空 —— 调用方用 `t.tab ?: return err(t.error ?: "没有活跃标签")` 取值。
     */
    private class TabTarget(val tab: BrowserTabManager.Tab?, val error: String?)

    /**
     * 解析命令的目标标签（㊽-C ＝ §B 78，2026-10-16）。
     *
     * 背景（真机实证 N4）：2026-10-16 前 `status` / `snapshot` / `execJS` / `click` / `type` /
     * `scroll` / `navigate` 七条命令**一律操作活跃标签** —— body 里带的 `tabId` 被静默忽略，
     * `tabId=1` 的 `click` 实际点在了 tab2，agent 无从察觉（回执里也没有任何异常）。
     * 现在：
     * - `tabId` 缺省 / 空白 → 活跃标签（与既有行为一致，老调用方零改动）；
     * - 给了 `tabId` → **按 id 精确路由**（[BrowserTabManager.tabById]）；不是数字、或找不到
     *   该标签 → 明确回错误 JSON，文案口径沿用 `activeTab` / `closeTab` 的「没有这个标签：$id」。
     */
    private fun target(tabId: String?): TabTarget {
        if (tabId.isNullOrBlank()) {
            val t = BrowserTabManager.activeTab() ?: return TabTarget(null, "没有活跃标签")
            return TabTarget(t, null)
        }
        val id = tabId.trim().toIntOrNull()
            ?: return TabTarget(null, "tabId 不是数字：${tabId.trim()}")
        val t = BrowserTabManager.tabById(id)
            ?: return TabTarget(null, "没有这个标签：$id（当前 ${BrowserTabManager.count()} 个）")
        return TabTarget(t, null)
    }

    // ── 命令 ───────────────────────────────────────────────────────────────

    /**
     * `status`：活跃（或 `tabId` 指定）标签的 url/title/loading + 标签总数。
     * ㊽-C（§B 78）：`tabId` 给了就按 id 路由，不再静默落到活跃标签。
     */
    fun status(tabId: String? = null): String {
        val t = target(tabId)
        val tab = t.tab ?: return err(t.error ?: "没有活跃标签")
        val st = tab.state
        return statusJson(tab.id, st.url, st.title, st.loading, BrowserTabManager.count())
    }

    /**
     * `status` 的响应体组装（第48刀A 抽出的无 Android 内核：JVM 单测直接喂值断言回包，
     * 不必为 WebView 起真机；脱敏口径与 [status] 逐字一致）。
     *
     * 字段级脱敏边界全表见类 KDoc「脱敏边界」段 —— **新增字段先进表再落码**。
     */
    internal fun statusJson(
        tabId: Int,
        url: String,
        title: String,
        loading: Boolean,
        tabCount: Int,
    ): String = ok(
        // 第㊵刀（P2）：url 过 redactUrl——真机实证过 query 里的 PII（email/phone/id/card）
        // 曾被原样带回 agent。title 过 [SensitiveRedactor.redactTabTitle]（第48刀A 扩边界：
        // 标题既是自由文本、又可能形如 URL 或裸键值对，真机实证过手机号从 title 明文带出；
        // 管线判据见该函数 KDoc）。
        "url" to SensitiveRedactor().redactUrl(url),
        "title" to SensitiveRedactor().redactTabTitle(title),
        "loading" to loading.toString(),
        "tabId" to tabId.toString(),
        "tabCount" to tabCount.toString(),
    )

    /**
     * `snapshot`：mode=text → 可访问性摘要（复用第⑲刀 [PageSnapshot.summarize]）；
     * mode=dom → 脱敏后的 DOM；mode=full → 摘要 + DOM 一起。
     *
     * 第⑲刀做的"眼睛"（只读导出）在这里直接复用 —— 同一份代码服务两个入口
     * （用户点眼睛手动导出 / agent 经桥拉取），不必写第二遍。
     *
     * selector 参数**预留**（将来"按元素取快照"用），当前忽略但校验其合法性。
     *
     * ㊽-C（§B 78）：`tabId` 给了就按 id 路由（快照只读，路由错标签同样属于"静默答非所问"）。
     */
    fun snapshot(mode: String?, selector: String?, tabId: String? = null): String {
        val v = BridgePolicy.checkSnapshotMode(mode)
        if (v is BridgePolicy.Verdict.Deny) return err(v.reason)
        selector?.let { s ->
            val sv = BridgePolicy.checkSelector(s)
            if (sv is BridgePolicy.Verdict.Deny) return err(sv.reason)
        }
        val t = target(tabId)
        val tab = t.tab ?: return err(t.error ?: "没有活跃标签")
        val m = mode?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: "text"
        val html = fetchOuterHtml(tab.webView) ?: return err("取不到页面 DOM（页面可能还在加载）")
        // 管线与第⑲刀落盘侧一致：先摘凭据 → 再打 PII
        val clean = SensitiveRedactor().redactDom(PageSnapshot.scrubSecrets(html))
        val summary = PageSnapshot.summarize(clean)
        // 标题位显式兜底 redactTabTitle：正文已过 redactDom（title 在 DOM 里顺带打码），
        // 这里按「桥边界」再统一一遍——幂等，且将来 summarize 的取法变了也不漏。
        val head = "\"title\":\"${PageSnapshot.jsonEscape(SensitiveRedactor().redactTabTitle(summary.title))}\"," +
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
     * 第㊷刀：脚本由 [execJsScript] 构造——**不再 eval**（第㊽刀 B，§B 77：code
     * **原样作为脚本**嵌进脚本级 `try`，表达式与含分号的语句序列同一包装通吃；
     * 旧写法 `return ( … )` 只容得下单个表达式，多语句是解析期语法错误）。
     * 旧写法 `String(eval(<code>))` 把返回字符串的 code 结果当源码二次求值，且
     * eval 被站点 CSP `unsafe-eval` 拦成 EvalError（真机实证，台账 §B 73）。
     *
     * 返回值**必须**过 [SensitiveRedactor]（红线⑦）：agent 不能用它读原始隐私值绕过脱敏。
     * 第㊵刀（P3）：管线补 [SensitiveRedactor.redactPairs]——`document.cookie` 这类
     * `sessionid=…` 键值对返回，redactText 的模式（号码类）不命中，靠本步打码。
     *
     * ㊽-C（§B 78）：`tabId` 给了就按 id 路由 —— 在错误的标签上执行 JS 是最危险的一种
     * 「静默答非所问」（agent 会拿错页面的结果继续推理）。
     */
    fun execJs(code: String?, tabId: String? = null): String {
        if (code.isNullOrBlank()) return err("execJS 缺 code")
        val t = target(tabId)
        val tab = t.tab ?: return err(t.error ?: "没有活跃标签")
        val script = execJsScript(code)
        val result = evalJs(tab.webView, script) ?: return err("JS 执行超时或失败")
        val redacted = SensitiveRedactor().run { redactPairs(redactText(result)) }
        return okRaw("\"result\":\"${PageSnapshot.jsonEscape(clamp(redacted))}\"")
    }

    /** `click`：点一个元素（selector 已被 [BridgePolicy.checkSelector] 拦过引号/反斜杠）。㊽-C（§B 78）：`tabId` 可路由。 */
    fun click(selector: String?, tabId: String? = null): String {
        val v = BridgePolicy.checkSelector(selector)
        if (v is BridgePolicy.Verdict.Deny) return err(v.reason)
        val t = target(tabId)
        val tab = t.tab ?: return err(t.error ?: "没有活跃标签")
        // 第㊷刀：selector 经 jsStringLiteral 带引号嵌入——旧写法裸拼 ⇒
        // document.querySelector(#btn) 语法错误，click 23ms「假超时」（真机实证）。
        val js = clickJsScript(selector!!)
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
     * text 经 [jsStringLiteral]（第㊷刀：双引号 + jsonEscape 转义 + 双引号）变成
     * 真正的 JS 字符串字面量再嵌入 ⇒ 引号/换行/反斜杠都不会逃逸（这是"任意文本
     * 能安全进 JS"的正路，别用字符串拼接）。旧写法少了引号包裹——selector 与
     * text 拼出来都是裸标识符/裸值，语法错误 ⇒ type 15s 断连（真机实证）。
     * 走原生 value setter 而不是 `el.value=`：React/Vue 受控组件监听的是原型链上的
     * setter，直接赋值不触发它们的 onChange（页面就像没收到输入）。
     */
    fun type(selector: String?, text: String?, tabId: String? = null): String {
        val v = BridgePolicy.checkSelector(selector)
        if (v is BridgePolicy.Verdict.Deny) return err(v.reason)
        if (text == null) return err("type 缺 text")
        val t = target(tabId)
        val tab = t.tab ?: return err(t.error ?: "没有活跃标签")
        val js = typeJsScript(selector!!, text)
        return when (val r = evalJs(tab.webView, js)) {
            "OK" -> ok("typed" to selector)
            "NOT_FOUND" -> err("找不到元素：$selector")
            null -> err("执行超时或失败")
            else -> err("意外结果：$r")
        }
    }

    /** `scroll`：按坐标（x,y）或按元素（selector 滚进视口）。㊽-C（§B 78）：`tabId` 可路由。 */
    fun scroll(x: String?, y: String?, selector: String?, tabId: String? = null): String {
        val t = target(tabId)
        val tab = t.tab ?: return err(t.error ?: "没有活跃标签")
        val js = if (!selector.isNullOrBlank()) {
            val v = BridgePolicy.checkSelector(selector)
            if (v is BridgePolicy.Verdict.Deny) return err(v.reason)
            scrollElementJsScript(selector)
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

    /**
     * `navigate`：加载任意 http/https（危险 scheme 由浏览器本身拦；桥这侧也显式拒）。
     * ㊽-C（§B 78）：`tabId` 给了就把 URL 加载进**那个**标签，不再静默落到活跃标签。
     */
    fun navigate(url: String?, tabId: String? = null): String {
        val v = BridgePolicy.checkNavigateUrl(url)
        if (v is BridgePolicy.Verdict.Deny) return err(v.reason)
        val u = url!!.trim()
        // 第㉓刀：manager/engine 全部限定 UI 线程（BrowserTabManager KDoc 原文），
        // 原先在 HTTP 工作线程直调 —— 现经 onMain 落主线程，失败回 JSON 错误。
        val r = onMain {
            val t = target(tabId)
            val tab = t.tab
            if (tab == null) {
                err(t.error ?: "没有活跃标签")
            } else {
                tab.engine.loadUrl(u)
                // 第㊵刀（P2）：回执的 url 与 status 同口径过 redactUrl
                ok("navigating" to SensitiveRedactor().redactUrl(url))
            }
        }
        return r.value
            ?: r.failure?.let { err("navigate 执行失败：${it.message}") }
            ?: err("执行超时或失败")
    }

    /** `newTab`：开标签（url 可省＝空标签）；返回新标签 id。 */
    fun newTab(context: Context, url: String?): String {
        url?.let {
            val v = BridgePolicy.checkNavigateUrl(it)
            if (v is BridgePolicy.Verdict.Deny) return err(v.reason)
        }
        // 第㉓刀：newTab 会在主线程创建 WebView —— 原先在无 Looper 的工作线程直调，
        // 必抛「Can't create handler inside thread that has not called Looper.prepare()」。
        val r = onMain {
            val id = BrowserTabManager.newTab(context, url?.trim())
            if (id == null) {
                err("开标签失败（可能已达上限 ${com.example.zhengdao.browser.BrowserTabPolicy.MAX_TABS}）")
            } else {
                ok("tabId" to id.toString(), "tabCount" to BrowserTabManager.count().toString())
            }
        }
        return r.value
            ?: r.failure?.let { err("newTab 执行失败：${it.message}") }
            ?: err("执行超时或失败")
    }

    /** `activeTab`：切换 agent 操作的活跃标签。 */
    fun activeTab(tabId: String?): String {
        val id = tabId?.trim()?.toIntOrNull() ?: return err("activeTab 缺 tabId（数字）")
        // 第㉓刀：tabs() 读与 selectTab 写都是 Compose snapshot 状态（限主线程），
        // 查找与切换一并搬进 onMain，语义与原「none → 报没有这个标签」一致。
        val r = onMain {
            if (BrowserTabManager.tabs().none { it.id == id }) return@onMain err("没有这个标签：$id")
            BrowserTabManager.selectTab(id)
            ok("tabId" to id.toString(), "tabCount" to BrowserTabManager.count().toString())
        }
        return r.value
            ?: r.failure?.let { err("activeTab 执行失败：${it.message}") }
            ?: err("执行超时或失败")
    }

    /** `closeTab`：关标签（最后一个标签关不掉——WebView 宿主至少要有一个）。 */
    fun closeTab(tabId: String?): String {
        val id = tabId?.trim()?.toIntOrNull() ?: return err("closeTab 缺 tabId（数字）")
        // 第㉓刀：closeTab 里的 WebView.destroy 限主线程 —— 原先在工作线程直调被
        // checkThread 拦下再被 runCatching 吞掉（静默泄漏）。查找、上限校验与关闭
        // 一并搬进 onMain，三条业务错误文案逐字保持。
        val r = onMain {
            if (BrowserTabManager.tabs().none { it.id == id }) {
                return@onMain err("没有这个标签：$id")
            }
            if (BrowserTabManager.count() <= 1) {
                return@onMain err("只剩一个标签，不能关（WebView 宿主至少要有一个）")
            }
            BrowserTabManager.closeTab(id)
            ok("closed" to id.toString(), "tabCount" to BrowserTabManager.count().toString())
        }
        return r.value
            ?: r.failure?.let { err("closeTab 执行失败：${it.message}") }
            ?: err("执行超时或失败")
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
    }.value // 失败/超时 ⇒ null，调用方维持既有文案「截屏失败」
}
