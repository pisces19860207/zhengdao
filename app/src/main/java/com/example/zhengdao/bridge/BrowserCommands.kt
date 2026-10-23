// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

package com.example.zhengdao.bridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withScale
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
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
 *
 * 刀 4（2026-10-18）给 `screenshot` 新增的字段同批核定，判据不变（都不是页面/标签元数据）：
 * `hostPath`（宿主绝对路径，§2.3b(b) 补的）、`guestPathScope`（固定一句说明文案）、
 * `mode`（`viewport`/`fullpage`/`element` 三个定值）、`width`·`height`·`scale`（整数与比例）、
 * `note`（**本刀自己写的降级说明**，内容受控、不含页面文本）。
 * ⚠️ 其中 `hostPath` 带的是**本机绝对路径**（`/storage/emulated/0/Download/证道/...`）——
 * 它是设备路径不是用户隐私值，但**别学它**：路径里唯一可能带隐私的是用户名，而本 App 的
 * 共享目录名由 [Store.root] 固定拼，不含用户名。
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
        if (!done) {
            // §B 79（第七十一批④）：超时必须落日志——与 onMain 的超时日志（:145 段）同款，
            // 两处不许漂移。command 取脚本前 80 字（压成单行）作指纹，整段脚本不进日志。
            runCatching {
                RunLog.log(
                    "浏览器桥：JS 回调超时（>${EVAL_TIMEOUT_MS}ms 未返回，" +
                        "command=${script.take(80).replace(Regex("\\s+"), " ")}）——按契约归一为 null",
                )
            }
            return null
        }
        return jsResultToString(box[0])
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
     * **刀 4 §2.4：`selector` 不再静默。** 给了 selector 就只取**那个元素子树**
     * （页面侧 `document.querySelector(sel).outerHTML`），取完仍走下面同一条
     * `scrubSecrets` → `redactDom` → `summarize` 管线（脱敏口径与整页逐字一致）；
     * 元素不存在 ⇒ 明确回「找不到元素：<selector>」（文案与 `click`/`type` 同一口径），
     * **绝不**退化成整页 DOM。旧写法只把 selector 过一遍 `checkSelector` 就丢掉、
     * 回包却是整页 —— 那是接线审计 §2 点名的「假过滤」（派工单 §1 事实 B）。
     *
     * ㊽-C（§B 78）：`tabId` 给了就按 id 路由（快照只读，路由错标签同样属于"静默答非所问"）。
     */
    fun snapshot(mode: String?, selector: String?, tabId: String? = null): String {
        val v = BridgePolicy.checkSnapshotMode(mode)
        if (v is BridgePolicy.Verdict.Deny) return err(v.reason)
        // 空白 selector 视同"没给"（与 screenshot 同口径），非空白必须先过 checkSelector
        val sel = selector?.trim()?.takeIf { it.isNotEmpty() }
        sel?.let {
            val sv = BridgePolicy.checkSelector(it)
            if (sv is BridgePolicy.Verdict.Deny) return err(sv.reason)
        }
        val t = target(tabId)
        val tab = t.tab ?: return err(t.error ?: "没有活跃标签")
        val m = mode?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: "text"
        // 刀 4 §2.4：selector 决定取整页还是取子树，两条路共用下面同一条脱敏管线
        val html = if (sel == null) {
            fetchOuterHtml(tab.webView) ?: return err("取不到页面 DOM（页面可能还在加载）")
        } else {
            when (val sub = evalJs(tab.webView, subtreeJsScript(sel))) {
                null -> return err("执行超时或失败")
                "NOT_FOUND" -> return err("找不到元素：$sel")
                else -> sub
            }
        }
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
     * `screenshot`：对活跃（或指定）标签截屏，PNG 落**共享目录**（见类 KDoc 的偏离说明）。
     *
     * ## 刀 4 三条规格一次做完（§2.2 / §2.3 / §2.3b）
     *
     * | 参数 | 行为 | 回执 |
     * |---|---|---|
     * | 都不给 | **可视区**：整页位图按页面自己的滚动量裁出视口那一块 | `mode":"viewport"` |
     * | `fullPage ∈ {"1","true"}` | **整页**：`capturePicture()` 路线（不依赖台前）；超出 [MAX_SHOT_WIDTH]/[MAX_SHOT_HEIGHT] 按比例缩 | `mode":"fullpage"` ＋ `width`/`height`/`scale` |
     * | `selector` 非空白 | **只截那个元素**（与 fullPage 同时给时元素优先——范围更窄的那个才是调用方要的）；矩形＝`getBoundingClientRect()` ＋ 滚动补偿 | `mode":"element"` ＋ 元素尺寸 |
     *
     * 三条共同守住的底线（台账 §E「浏览器截图的两处缺陷」**第 (a) 条**，本刀销）：
     * 旧实现用 `webView.width/height` ＋ `webView.draw(Canvas)`，页面**不在台前/未布局**时
     * 宽高为 0，被 `coerceAtLeast(1)` 兜成一张 **1×1（99 B）的"成功"回执**；硬件加速的
     * WebView 用软件 Canvas `draw()` 也常画出空白。现在：几何问页面（CSS px）、像素走
     * 整页 Picture，两条路都取不到就**如实报失败**，**绝不**再拿 1×1 冒充成功（J11a）。
     *
     * **第 (b) 条（回执只给 guest 路径）本刀只销桥路这一半**：回执同时给
     * `file`（相对共享目录）与 `hostPath`（宿主绝对路径），`guestPath` 保留但用
     * `guestPathScope` 注明只给终端 guest。抱朴工具读哪个字段在 `BrowserTools.kt` /
     * `BaopuBrowserWiring.kt`（红线）⇒ **抱朴侧真修好没有，留 4-B 接线时验**。
     *
     * 元素不存在 ⇒ `NOT_FOUND` 口径报错（**不许**回一张整页图充数）；元素宽或高为 0 ⇒
     * 明说「元素没有尺寸」。
     */
    fun screenshot(context: Context, tabId: String?, fullPage: String?, selector: String? = null): String {
        val sel = selector?.trim()?.takeIf { it.isNotEmpty() }
        sel?.let {
            val v = BridgePolicy.checkSelector(it)
            if (v is BridgePolicy.Verdict.Deny) return err(v.reason)
        }
        val tab = if (tabId.isNullOrBlank()) {
            BrowserTabManager.activeTab()
        } else {
            val id = tabId.trim().toIntOrNull() ?: return err("screenshot 的 tabId 不是数字")
            BrowserTabManager.tabs().firstOrNull { it.id == id }
        } ?: return err("没有这个标签")
        val mode = shotMode(fullPage, sel)
        // 几何问页面（CSS px）：页面不在台前也有数——这是 §2.3b(a) 的关键一步
        val bounds: ShotBounds? = when (mode) {
            ShotMode.FULL_PAGE -> null
            ShotMode.VIEWPORT -> ShotBounds.parse(evalJs(tab.webView, viewportBoundsJsScript()))
            ShotMode.ELEMENT -> {
                val s = sel ?: return err("screenshot 缺 selector")
                when (val raw = evalJs(tab.webView, elementBoundsJsScript(s))) {
                    null -> return err("执行超时或失败")
                    "NOT_FOUND" -> return err("找不到元素：$s")
                    else -> ShotBounds.parse(raw)
                }
            }
        }
        if (mode == ShotMode.ELEMENT && bounds != null && (bounds.w <= 0.0 || bounds.h <= 0.0)) {
            return err("元素没有尺寸：$sel（${whole(bounds.w)}×${whole(bounds.h)} CSS px）")
        }
        if (mode != ShotMode.FULL_PAGE && bounds == null) {
            return err("取不到页面几何（页面可能还没绘制）")
        }
        var note: String? = null
        val shot = captureShot(tab.webView, bounds)
            ?: captureViewportDraw(tab.webView)?.also {
                note = if (mode == ShotMode.FULL_PAGE) {
                    "页面过高/未取到整页布局，已退化为可视区"
                } else {
                    "整页位图未取到（capturePicture 返回空），已退化为未裁剪的可视区"
                }
            }
            ?: return err("截屏失败（页面还没绘制：整页位图与可视区都没取到）")
        // 退化到可视区时，mode 要如实改成 viewport（不许挂着 element/fullpage 的名义回一张别的图）
        val effectiveMode = if (note == null) mode else ShotMode.VIEWPORT
        val dir = File(Store.root(context), "screenshots").apply { mkdirs() }
        val name = "bridge-${System.currentTimeMillis()}.png"
        return runCatching { File(dir, name).writeBytes(shot.bytes) }.fold(
            onSuccess = {
                screenshotJson(
                    file = "screenshots/$name",
                    hostPath = File(dir, name).absolutePath,
                    guestPath = "/opt/zhengdao/screenshots/$name",
                    bytes = shot.bytes.size,
                    mode = effectiveMode.wire,
                    width = shot.width,
                    height = shot.height,
                    scale = shot.scale,
                    note = note,
                )
            },
            onFailure = { err("写截图失败：${it.message}") },
        )
    }

    // ── WebView 桥接小工具 ─────────────────────────────────────────────────

    /** 取渲染后 DOM（与第⑲刀导出器同一条路径：原生 evaluateJavascript）。 */
    private fun fetchOuterHtml(webView: WebView): String? =
        evalJs(webView, "document.documentElement.outerHTML")

    /**
     * `snapshot(selector)` 的子树脚本（刀 4 §2.4）：命中回 `outerHTML`，未命中回
     * `NOT_FOUND`（与 `click`/`type` 同一哨兵）。selector 必须走 [jsStringLiteral]——
     * 第㊷刀的教训：裸拼会让 `input[name=q]` 变成 JS 语法错误。
     */
    private fun subtreeJsScript(selector: String): String =
        "(function(){var el=document.querySelector(" + jsStringLiteral(selector) + ");" +
            "if(!el)return 'NOT_FOUND';return el.outerHTML})()"

    /**
     * 可视区几何脚本：`scrollX|scrollY|clientWidth|clientHeight|scrollWidth|scrollHeight`。
     * 用页面自己的滚动量与布局视口尺寸，不看 `webView.width/height` ⇒ 页面不在台前也有数。
     */
    private fun viewportBoundsJsScript(): String =
        "(function(){var d=document.documentElement;" +
            "var sx=window.scrollX||window.pageXOffset||0,sy=window.scrollY||window.pageYOffset||0;" +
            "return [sx,sy,d.clientWidth,d.clientHeight,d.scrollWidth,d.scrollHeight].join('|')})()"

    /**
     * 元素几何脚本（§2.3；判据 J12 钉这条）：`getBoundingClientRect()` **＋ `scrollX`/`scrollY`
     * 滚动补偿**（rect 是视口坐标，加上滚动量才落到文档坐标），后附页面内容尺寸，
     * 未命中回 `NOT_FOUND`（**不许**退化成整页）。
     *
     * 已知局限（写进报告，不当"已验"）：`position:fixed` 的元素在滚动补偿下会落到
     * 「当前滚动位置对应的文档坐标」，与它在纸面上的固定位置不同——本刀不做特判。
     */
    private fun elementBoundsJsScript(selector: String): String =
        "(function(){var d=document.documentElement;" +
            "var e=document.querySelector(" + jsStringLiteral(selector) + ");" +
            "if(!e)return 'NOT_FOUND';" +
            "var r=e.getBoundingClientRect();" +
            "var sx=window.scrollX||window.pageXOffset||0,sy=window.scrollY||window.pageYOffset||0;" +
            "return [r.left+sx,r.top+sy,r.width,r.height,d.scrollWidth,d.scrollHeight].join('|')})()"

    // ── 截图的纯判据层（internal ⇒ JVM 单测直接钉，不必为 WebView 起真机） ────

    /** 截图模式（回执里 `mode` 的三取值）。 */
    internal enum class ShotMode(val wire: String) {
        VIEWPORT("viewport"),
        FULL_PAGE("fullpage"),
        ELEMENT("element"),
    }

    /**
     * 模式判定（J6 的行为面）：`selector` 非空白 ⇒ [ShotMode.ELEMENT]（**元素优先**）；
     * 否则 `fullPage` 去空白转小写后 ∈ {"1","true"} ⇒ [ShotMode.FULL_PAGE]；其余 ⇒
     * [ShotMode.VIEWPORT]。`param()` 把 JSON 的 `true` 取出来就是字符串 `"true"` ⇒ 两种写法都认。
     */
    internal fun shotMode(fullPage: String?, selector: String?): ShotMode {
        if (!selector.isNullOrBlank()) return ShotMode.ELEMENT
        val f = fullPage?.trim()?.lowercase()
        return if (f == "1" || f == "true") ShotMode.FULL_PAGE else ShotMode.VIEWPORT
    }

    /**
     * 一次截图的像素读数。[viaPicture] 只在日志/排查里有意义（回执走 `mode`＋`note`）。
     */
    internal class Shot(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
        val scale: Float,
        val viaPicture: Boolean,
    )

    /** 页面几何（CSS px）：`x|y|w|h|contentWidth|contentHeight`。 */
    internal class ShotBounds(
        val x: Double,
        val y: Double,
        val w: Double,
        val h: Double,
        val contentWidth: Double,
        val contentHeight: Double,
    ) {
        companion object {
            /** 六个数一段都不能缺、不能不是数 ⇒ 宁缺勿猜（返回 null 让调用方如实报错）。 */
            fun parse(raw: String?): ShotBounds? {
                val parts = raw?.split('|') ?: return null
                if (parts.size != 6) return null
                val nums = DoubleArray(6)
                for (i in 0 until 6) {
                    val v = parts[i].trim().toDoubleOrNull() ?: return null
                    if (v.isNaN() || v.isInfinite()) return null
                    nums[i] = v
                }
                return ShotBounds(nums[0], nums[1], nums[2], nums[3], nums[4], nums[5])
            }
        }
    }

    /** 裁剪框（已在输出位图像素系里）。 */
    internal class CropBox(val left: Int, val top: Int, val width: Int, val height: Int)

    /** 整页位图上限（§2.2 原文：高 ≤ 20000、宽 ≤ 4096）。 */
    internal const val MAX_SHOT_HEIGHT = 20_000
    internal const val MAX_SHOT_WIDTH = 4_096

    /**
     * **本刀加的一条**（对派工单的偏离，写进报告）：总像素 ≤ 1200 万。
     * 理由：20000×4096 的 ARGB_8888 位图是 327 MB，真机上必 OOM 把整个 App 打挂——
     * 崩掉比"多缩一点"糟得多。超这条时同样按比例缩，并在回执里如实报实际尺寸与缩放比。
     */
    internal const val MAX_SHOT_PIXELS = 12_000_000

    /**
     * 按比例缩到三条上限之内，返回缩放比（1f＝不用缩）。
     * 面积约束开平方（宽高各缩 √ 倍正好把面积缩到位）。
     */
    internal fun fitScale(w: Int, h: Int): Float {
        if (w <= 0 || h <= 0) return 1f
        var s = 1.0
        if (w > MAX_SHOT_WIDTH) s = minOf(s, MAX_SHOT_WIDTH.toDouble() / w)
        if (h > MAX_SHOT_HEIGHT) s = minOf(s, MAX_SHOT_HEIGHT.toDouble() / h)
        val pixels = w.toDouble() * h.toDouble()
        if (pixels > MAX_SHOT_PIXELS) s = minOf(s, sqrt(MAX_SHOT_PIXELS / pixels))
        return s.toFloat()
    }

    /**
     * 把「CSS px 的矩形」映射进「输出位图像素」并裁到位图边界内（J7/J12 的算术面）。
     *
     * 映射比 = 位图尺寸 / 页面内容尺寸——两边量的是**同一份内容**，所以这个比值把
     * devicePixelRatio 与页面缩放一并吃掉，Kotlin 侧不必猜密度。
     * 返回 null ⇒ 与内容区无交集（或内容尺寸缺失）：调用方如实报错，**不许**拿整页图充数。
     */
    internal fun cropOf(b: ShotBounds, picWidth: Int, picHeight: Int, scale: Float): CropBox? {
        if (picWidth <= 0 || picHeight <= 0) return null
        if (b.contentWidth <= 0.0 || b.contentHeight <= 0.0) return null
        val outW = maxOf(1, (picWidth * scale).roundToInt())
        val outH = maxOf(1, (picHeight * scale).roundToInt())
        val sx = picWidth.toDouble() / b.contentWidth
        val sy = picHeight.toDouble() / b.contentHeight
        val rawLeft = (b.x * sx * scale).roundToInt()
        val rawTop = (b.y * sy * scale).roundToInt()
        val rawW = (b.w * sx * scale).roundToInt()
        val rawH = (b.h * sy * scale).roundToInt()
        if (rawW <= 0 || rawH <= 0) return null
        // 交集算法（不是"夹住"算法）：越界的那一侧直接砍掉，两轴都砍没了就是 null。
        // 先把矩形夹进边界再算尺寸会让一个完全跑到位图外的元素压成边角上一像素的框——
        // 那等于 §2.3b(a) 骂的「拿一张假图冒充成功」的算术版本，宁可报错。
        val left = maxOf(0, rawLeft)
        val top = maxOf(0, rawTop)
        val right = minOf(outW, rawLeft + rawW)
        val bottom = minOf(outH, rawTop + rawH)
        if (right <= left || bottom <= top) return null
        return CropBox(left, top, right - left, bottom - top)
    }

    /** `guestPath` 的用途限定语（§2.3b(b)：留着，但要说明它只给谁）。 */
    internal const val GUEST_PATH_SCOPE =
        "guestPath 只给终端 guest（proot bind 的 /opt/zhengdao）；本机与抱朴请用 hostPath"

    /**
     * `screenshot` 的回执组装（**无 Android 内核**，与第48刀A 的 [statusJson] 同款做法
     * ⇒ JVM 单测直接喂值断言）。字段级脱敏边界见类 KDoc 的表（本函数所有字段都刻意不过管线）。
     */
    internal fun screenshotJson(
        file: String,
        hostPath: String,
        guestPath: String,
        bytes: Int,
        mode: String,
        width: Int,
        height: Int,
        scale: Float,
        note: String?,
    ): String {
        val kv = mutableListOf(
            "file" to file,
            "hostPath" to hostPath,
            "guestPath" to guestPath,
            "guestPathScope" to GUEST_PATH_SCOPE,
            "bytes" to bytes.toString(),
            "mode" to mode,
            "width" to width.toString(),
            "height" to height.toString(),
            "scale" to scaleText(scale),
        )
        if (note != null) kv.add("note" to note)
        return ok(*kv.toTypedArray())
    }

    /** 缩放比文案：三位小数定宽（不用 `String.format` —— 避免跟着设备 locale 换数字字形）。 */
    private fun scaleText(scale: Float): String {
        val thousandths = (scale * 1000f).roundToInt().coerceIn(0, 1000)
        return buildString {
            append(thousandths / 1000)
            append('.')
            append((thousandths % 1000).toString().padStart(3, '0'))
        }
    }

    /** CSS px 读数打进文案（去小数，报错文案里够用）。 */
    private fun whole(v: Double): String = v.roundToInt().toString()

    // ── 取像素（两条路线：整页 Picture 优先，可视区 draw 只当降级） ──────────

    /**
     * 整页 Picture 路线（§2.2 ＋ §2.3b(a)）：`capturePicture()` 取整页（**不依赖台前**），
     * 按 [fitScale] 缩，给了 [bounds]（视口或元素）就只画那个框。
     *
     * 画框的做法是「建目标尺寸的位图 ＋ 平移缩放过画布 ＋ `picture.draw`」，
     * 不是「先整页位图再 createBitmap 裁」——局部截图时前者少一次全页内存分配（327 MB
     * 那种量级）。`Picture` 是 display list，跟着画布变换走，所以平移量要除以缩放比。
     *
     * 拿不到（Picture 空 / 尺寸 0 / 框算不出来 / 抛异常）⇒ null，由调用方走降级或如实报错。
     */
    @Suppress("DEPRECATION")
    private fun captureShot(webView: WebView, bounds: ShotBounds?): Shot? = onMain {
        val picture = runCatching { webView.capturePicture() }.getOrNull()
        val picW = picture?.width ?: 0
        val picH = picture?.height ?: 0
        if (picture == null || picW <= 0 || picH <= 0) {
            return@onMain null
        }
        val scale = fitScale(picW, picH)
        val box = if (bounds == null) null else cropOf(bounds, picW, picH, scale)
        if (bounds != null && box == null) return@onMain null
        val outW = maxOf(1, (picW * scale).roundToInt())
        val outH = maxOf(1, (picH * scale).roundToInt())
        val w = box?.width ?: outW
        val h = box?.height ?: outH
        if (w <= 0 || h <= 0) return@onMain null
        val bitmap = createBitmap(w, h)
        val canvas = Canvas(bitmap)
        canvas.withScale(scale, scale) {
            if (box != null) translate(-box.left.toFloat() / scale, -box.top.toFloat() / scale)
            picture.draw(this)
        }
        Shot(encodePng(bitmap), w, h, scale, viaPicture = true)
    }.value

    /**
     * 可视区降级路线（`webView.draw(Canvas)`）。**只在页面已布局**（宽高都 > 0）时才走。
     *
     * §2.3b(a) 点名的 1×1 假成功就出在旧写法这里：`webView.width.coerceAtLeast(1)` 把
     * 「没布局」兜成「1×1 的成功回执」。现在未布局直接 null ⇒ 调用方如实报失败。
     * 硬件加速的 WebView 用软件 Canvas `draw()` 仍可能画出空白，所以这条**只当降级**，
     * 回执里 `mode` 改回 viewport、`note` 写明退化的原因。
     */
    private fun captureViewportDraw(webView: WebView): Shot? = onMain {
        val w = webView.width
        val h = webView.height
        if (w <= 0 || h <= 0) {
            return@onMain null
        }
        val bitmap = createBitmap(w, h)
        webView.draw(Canvas(bitmap))
        Shot(encodePng(bitmap), w, h, 1f, viaPicture = false)
    }.value

    /** 位图 → PNG 字节，顺手 recycle（调用方都在主线程块里，位图用完即弃）。 */
    private fun encodePng(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }
}
