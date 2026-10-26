// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

package com.example.zhengdao.bridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.WebView
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withScale
import com.example.zhengdao.baopu.MediaOwnership
import com.example.zhengdao.baopu.MediaPublish
import com.example.zhengdao.baopu.MediaPublisher
import com.example.zhengdao.browser.PageFingerprint
import com.example.zhengdao.browser.PageSnapshot
import com.example.zhengdao.browser.SensitiveRedactor
import com.example.zhengdao.browser.TapGeometry
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
 * | snapshot 头部 `url` | `snapshot` 回包头（228 批 A-2，头部第一字段） | [SensitiveRedactor.redactUrl] |
 * | `title` | `status` / `snapshot` 标题位 | [SensitiveRedactor.redactTabTitle]（redactUrl 两段式 ＋ & 终止键值模式） |
 * | snapshot 正文 | `text` / `nodes` / `dom` | `scrubSecrets`→[SensitiveRedactor.redactDom] |
 * | execJS 返回值 | `result` | `redactText`→[SensitiveRedactor.redactPairs] |
 * | `tabs` 列表的 `url` / `title`（228 批 B） | `tabs` 回包 | redactUrl / redactTabTitle —— tab 元数据第一次成列表出口，判据不变 |
 *
 * **刻意不进脱敏管线的字段（判据＝非页面/标签元数据，PII 无处栖身）**：
 * `loading`（布尔）/ `tabId` / `tabCount`（整数）/ `screenshot` 的 `file`·`bytes`·`guestPath`
 * （本机路径与字节数）/ `click`·`type`·`scroll` 回执里的 `selector`（agent 自带参数的原样
 * 回声，脱了反而破坏与请求的对应关系）。
 * 228 批 B 同批核定（判据不变）：snapshot 节点几何 `x`/`y`/`inViewport`（整数与布尔，
 * 由 [rectInViewport] 从矩形与视口尺寸现算，不含页面文本；节点定位子 `ref` 的定位链
 * 由 SAFE_TAG 白名单字符构成，同样不含文本）。`select` 回执的 `value` 是**页面内容**
 * （选中的选项值），照红线⑦过 redactText→redactPairs，不进本清单。
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
 * **这里改成共享目录 `Store.root()/截屏`**（＝ Downloads/证道，proot 已 bind 到
 * guest `/opt/zhengdao/`）。理由：本桥的全部意义就是"给 guest 里的 agent 用"，
 * 而 `Pictures/Screenshots` 在 guest 里**读不到**（仅私有工作区档位下 `/sdcard` 根本不挂载）；
 * 落共享目录是 agent 唯一能稳定取到截图的路径。若将来要兼顾人看，可同时落两份。
 */
object BrowserCommands {

    private val main = Handler(Looper.getMainLooper())

    /** WebView 回调等待上限（毫秒）。页面 JS 卡死时不该把桥的线程全拖住。 */
    private const val EVAL_TIMEOUT_MS = 8_000L

    // ── 第一百七十七批：手势与等待的数值口径 ────────────────────────────────
    // 双保险：工具层也会夹一次（工具 schema 写死同样的上限），这里再夹是防内部调用绕过。

    /** 等待上限（毫秒）：与工具 schema 的 `ms` 上限一致。 */
    private const val MAX_WAIT_MS = 5_000

    /** 等元素的轮询间隔。 */
    private const val WAIT_POLL_MS = 200L

    /** 写操作后等页面反应的一小段（导航/重渲染是异步的；不睡就会误报「没变化」）。 */
    private const val NAV_SETTLE_MS = 120L

    /** 按住时长的上下限。 */
    private const val MIN_PRESS_MS = 50
    private const val MAX_PRESS_MS = 3_000

    /** 滑动时长下限（再短就不是滑动，是一次点击）。 */
    private const val MIN_SWIPE_MS = 60

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
        // 刀 4 §2.4：selector 决定取整页还是取子树，两条路共用下面同一条脱敏管线。
        // 228 批 A：眼状态与 DOM 原文**同一次 JS 执行**取出——快照正文与锚定它的
        // nonce/epoch 之间不许有缝（中间有缝 ⇒ 页面在两步之间变了而锚还是旧的）。
        // 228 批 B-1：同一次执行里再带几何探针（真可见性＋视口坐标）——原子性口径不变。
        val fetched = evalJs(
            tab.webView,
            eyeFetchJsScript(
                if (sel == null) "document.documentElement.outerHTML" else subtreeJsScript(sel),
                if (sel == null) "document.documentElement" else "document.querySelector(" + jsStringLiteral(sel) + ")",
            ),
        ) ?: return err(if (sel == null) "取不到页面 DOM（页面可能还在加载）" else "执行超时或失败")
        val ef = parseEyeFetch(fetched)
            ?: return err(if (sel == null) "取不到页面 DOM（页面可能还在加载）" else "执行超时或失败")
        val eye = ef.probe
        val html = ef.html
        if (sel != null && html == "NOT_FOUND") return err("找不到元素：$sel")
        if (html.isEmpty()) return err("取不到页面 DOM（页面可能还在加载）")
        // 管线与第⑲刀落盘侧一致：先摘凭据 → 再打 PII（红线：几何是 JS 算的，
        // 但**脱敏不前移也不省略**——两道仍在这里，几何只补数值与布尔）
        val clean = SensitiveRedactor().redactDom(PageSnapshot.scrubSecrets(html))
        val parsed = PageSnapshot.summarize(clean)
        // 228 批 B-1：几何套用——JS 判不可见（含 class 里的 display:none/opacity:0/零尺寸/
        // visibility:hidden）的节点整行剔除；命中节点附视口坐标与 inViewport（Kotlin 现算）。
        // 探针缺席（眼没装上/子树根没了/超条数上限）⇒ 原样列出，不假装有坐标。
        val summary = ef.geo?.let { applyGeometry(parsed, it) } ?: parsed
        // 228 批 A-2：头部 url 行——PageFingerprint 的 location.href，离桥过 redactUrl（口径与 status 同）
        val fp = fingerprintOf(tab)
        // 228 批 A-1：ref 句柄——眼装上了（能锚定）才发；重新快照即换代，旧 ref 随旧本整体作废
        val entries = if (eye == null) emptyMap() else refEntriesOf(summary.nodes, sel)
        installRefBook(
            tab.id,
            if (eye == null || entries.isEmpty()) null else RefBook(tab.id, eye.nonce, eye.epoch, entries),
        )
        // 标题位显式兜底 redactTabTitle：正文已过 redactDom（title 在 DOM 里顺带打码），
        // 这里按「桥边界」再统一一遍——幂等，且将来 summarize 的取法变了也不漏。
        val head = snapshotHeadJson(
            url = fp?.url ?: "",
            title = SensitiveRedactor().redactTabTitle(summary.title),
            text = clamp(summary.text),
            nodesJson = renderNodes(summary),
        )
        return when (m) {
            "dom" -> okRaw("\"dom\":\"${PageSnapshot.jsonEscape(clamp(clean))}\"")
            "full" -> okRaw("$head,\"dom\":\"${PageSnapshot.jsonEscape(clamp(clean))}\"")
            else -> okRaw(head)
        }
    }

    private fun renderNodes(s: PageSnapshot.Summary): String =
        s.nodes.mapIndexed { i, n ->
            // 228 批 A-1：有定位子的节点带 ref 句柄（编号即 nodes 数组序，e 从 1 起）
            val ref = if (n.path.isNotEmpty()) ",\"ref\":\"e${i + 1}\"" else ""
            // 228 批 B：几何读数（数值与布尔，刻意不过脱敏管线——判据见类 KDoc 边界表）。
            // 没有读数的节点不带这三个键：如实缺席，不伪造 (0,0)。
            val geo = if (n.x != null && n.y != null) {
                ",\"x\":${n.x},\"y\":${n.y},\"inViewport\":${n.inViewport}"
            } else ""
            "{\"role\":\"${PageSnapshot.jsonEscape(n.role)}\"," +
                "\"name\":\"${PageSnapshot.jsonEscape(n.name)}\"," +
                "\"value\":\"${PageSnapshot.jsonEscape(n.value)}\"," +
                "\"states\":\"${PageSnapshot.jsonEscape(n.states)}\"$ref$geo}"
        }.joinToString(",", "[", "]")

    /**
     * snapshot 回包头（**无 Android 内核**，第48刀A statusJson 同款做法 ⇒ JVM 单测直喂）。
     *
     * 228 批 A-2 起 url 是头部第一个字段：来自 PageFingerprint 的 `location.href`，
     * 离桥必须过 [SensitiveRedactor.redactUrl]（口径与 status/navigate 同——真机实证过
     * query 里的 PII 曾被原样带回）。字段级脱敏边界见类 KDoc 的表（**新增字段先进表再落码**）。
     */
    internal fun snapshotHeadJson(url: String, title: String, text: String, nodesJson: String): String =
        "\"url\":\"${PageSnapshot.jsonEscape(SensitiveRedactor().redactUrl(url))}\"," +
            "\"title\":\"${PageSnapshot.jsonEscape(SensitiveRedactor().redactTabTitle(title))}\"," +
            "\"text\":\"${PageSnapshot.jsonEscape(text)}\"," +
            "\"nodes\":$nodesJson"

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

    /**
     * `click`：点一个元素。target 两可（228 批 A-1）：快照给的 ref 句柄（`@e3`）或
     * CSS selector——`@` 开头一律走句柄路（见 [resolveRef]，形状不对也是拒），
     * 其余走 [BridgePolicy.checkSelector]（拦引号/反斜杠）。㊽-C（§B 78）：`tabId` 可路由。
     */
    fun click(selector: String?, tabId: String? = null): String {
        // 228：@ 句柄不走 CSS 校验（checkSelector 会把 @e3 当非法 CSS 拒掉）；CSS 路的
        // 校验保持在取标签**之前**——无标签时引号 selector 也要先吃拒，不能漏成「没有活跃标签」。
        val isRef = selector != null && selector.startsWith("@")
        if (!isRef) {
            val v = BridgePolicy.checkSelector(selector)
            if (v is BridgePolicy.Verdict.Deny) return err(v.reason)
        }
        val t = target(tabId)
        val tab = t.tab ?: return err(t.error ?: "没有活跃标签")
        return if (selector != null && selector.startsWith("@")) {
            when (val ro = resolveRef(tab, selector)) {
                is RefOut.Use -> clickRun(tab, selector, ro.locator)
                is RefOut.Deny -> err(ro.msg)
                is RefOut.Css -> err("句柄解析异常：$selector") // 不可达（@ 开头 resolveRef 不回 Css），穷尽性兜底
            }
        } else {
            clickRun(tab, selector!!, selector)
        }
    }

    private fun clickRun(tab: BrowserTabManager.Tab, echo: String, eff: String): String {
        // 第㊷刀：selector 经 jsStringLiteral 带引号嵌入——旧写法裸拼 ⇒
        // document.querySelector(#btn) 语法错误，click 23ms「假超时」（真机实证）。
        return when (val r = evalJs(tab.webView, clickJsScript(eff))) {
            // 回声保留 agent 原文（CSS 或 @e3）；实际点的是解析后的定位子
            "OK" -> ok("clicked" to echo)
            "NOT_FOUND" -> err("找不到元素：$echo")
            null -> err("执行超时或失败")
            else -> err("意外结果：$r")
        }
    }

    /**
     * `type`：往任意输入框写文本（**含密码框** —— 高权限区，设计文档明确放开）。
     * target 两可（228 批 A-1）：快照 ref 句柄（`@e3`）或 CSS（判路规则同 [click]）。
     *
     * text 经 [jsStringLiteral]（第㊷刀：双引号 + jsonEscape 转义 + 双引号）变成
     * 真正的 JS 字符串字面量再嵌入 ⇒ 引号/换行/反斜杠都不会逃逸（这是"任意文本
     * 能安全进 JS"的正路，别用字符串拼接）。旧写法少了引号包裹——selector 与
     * text 拼出来都是裸标识符/裸值，语法错误 ⇒ type 15s 断连（真机实证）。
     * 走原生 value setter 而不是 `el.value=`：React/Vue 受控组件监听的是原型链上的
     * setter，直接赋值不触发它们的 onChange（页面就像没收到输入）。
     */
    fun type(selector: String?, text: String?, tabId: String? = null): String {
        // 判路规则同 [click]（228）：@ 句柄不走 CSS 校验；CSS 校验与 text 检查都在取标签之前
        val isRef = selector != null && selector.startsWith("@")
        if (!isRef) {
            val v = BridgePolicy.checkSelector(selector)
            if (v is BridgePolicy.Verdict.Deny) return err(v.reason)
        }
        if (text == null) return err("type 缺 text")
        val t = target(tabId)
        val tab = t.tab ?: return err(t.error ?: "没有活跃标签")
        return if (selector != null && selector.startsWith("@")) {
            when (val ro = resolveRef(tab, selector)) {
                is RefOut.Use -> typeRun(tab, selector, ro.locator, text)
                is RefOut.Deny -> err(ro.msg)
                is RefOut.Css -> err("句柄解析异常：$selector") // 不可达（@ 开头 resolveRef 不回 Css），穷尽性兜底
            }
        } else {
            typeRun(tab, selector!!, selector, text)
        }
    }

    private fun typeRun(tab: BrowserTabManager.Tab, echo: String, eff: String, text: String): String =
        when (val r = evalJs(tab.webView, typeJsScript(eff, text))) {
            // 回声保留 agent 原文（CSS 或 @e3）；实际写的是解析后的定位子
            "OK" -> ok("typed" to echo)
            "NOT_FOUND" -> err("找不到元素：$echo")
            null -> err("执行超时或失败")
            else -> err("意外结果：$r")
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

    // ── 228 批 B-3：动作补齐三件（select / hover / scroll_into_view）──────────
    //
    // target 判路规则与 [click]（228 批 A-1）逐字一致：@ 句柄走 resolveRef（形状不对
    // 也是拒，绝不落回 CSS 去查一个非法选择器），其余一切走 [BridgePolicy.checkSelector]
    // （校验保持在取标签**之前**——无标签时引号 selector 也要先吃拒）。

    /**
     * `select`：下拉框选值（`value` 可传 option 的 value 或可见文本，先 value 后文本）。
     *
     * 选中态**同时更新 property 与 `selected` 属性**（脚本侧做，理由见 [selectJsScript]）
     * ——判据「选中后 value 反射到快照」靠属性这一半：Kotlin 解析器读的是 HTML 属性。
     * 回执的 `value` 是选中的选项值＝**页面内容** ⇒ 过 redactText→redactPairs（红线⑦）；
     * `selected` 回声保留 agent 原文（与 click/type 同口径）。
     */
    internal fun selectOption(target: String?, value: String?, tabId: String? = null): String {
        val isRef = target != null && target.startsWith("@")
        if (!isRef) {
            val v = BridgePolicy.checkSelector(target)
            if (v is BridgePolicy.Verdict.Deny) return err(v.reason)
        }
        if (value == null) return err("select 缺 value")
        val t = target(tabId)
        val tab = t.tab ?: return err(t.error ?: "没有活跃标签")
        return if (isRef) {
            when (val ro = resolveRef(tab, target)) {
                is RefOut.Use -> selectRun(tab, target, ro.locator, value)
                is RefOut.Deny -> err(ro.msg)
                is RefOut.Css -> err("句柄解析异常：$target") // 不可达（@ 开头 resolveRef 不回 Css），穷尽性兜底
            }
        } else {
            selectRun(tab, target!!, target, value)
        }
    }

    private fun selectRun(tab: BrowserTabManager.Tab, echo: String, eff: String, value: String): String {
        val before = fingerprintOf(tab)
        return when (val r = evalJs(tab.webView, selectJsScript(eff, value))) {
            "NOT_FOUND" -> err("找不到元素：$echo")
            "NOT_SELECT" -> err("目标不是下拉框：$echo")
            null -> err("执行超时或失败")
            else -> {
                if (!r.startsWith("OK:")) {
                    return err("没有这个选项：${r.removePrefix("NO_OPTION:")}（$echo）")
                }
                val chosen = r.removePrefix("OK:")
                Thread.sleep(NAV_SETTLE_MS)
                val after = fingerprintOf(tab)
                val still = when (evalJs(tab.webView, PageFingerprint.targetExistsScript(jsStringLiteral(eff)))) {
                    "YES" -> true
                    "NO" -> false
                    else -> null
                }
                // 选中的值是页面内容 ⇒ 过脱敏（红线⑦同款管线，execJS 的 result 同路）
                val redacted = SensitiveRedactor().run { redactPairs(redactText(chosen)) }
                ok(
                    "selected" to echo,
                    "value" to redacted,
                    "check" to PageFingerprint.diff(before, after, still),
                )
            }
        }
    }

    /** `hover`：悬停（派 mouseover/mouseenter/mousemove），展开菜单/tooltip 后再快照。回执带前后校验。 */
    internal fun hover(target: String?, tabId: String? = null): String {
        val isRef = target != null && target.startsWith("@")
        if (!isRef) {
            val v = BridgePolicy.checkSelector(target)
            if (v is BridgePolicy.Verdict.Deny) return err(v.reason)
        }
        val t = target(tabId)
        val tab = t.tab ?: return err(t.error ?: "没有活跃标签")
        return if (isRef) {
            when (val ro = resolveRef(tab, target)) {
                is RefOut.Use -> hoverRun(tab, target, ro.locator)
                is RefOut.Deny -> err(ro.msg)
                is RefOut.Css -> err("句柄解析异常：$target") // 不可达（@ 开头 resolveRef 不回 Css），穷尽性兜底
            }
        } else {
            hoverRun(tab, target!!, target)
        }
    }

    private fun hoverRun(tab: BrowserTabManager.Tab, echo: String, eff: String): String {
        val before = fingerprintOf(tab)
        return when (val r = evalJs(tab.webView, hoverJsScript(eff))) {
            "OK" -> {
                Thread.sleep(NAV_SETTLE_MS)
                val after = fingerprintOf(tab)
                val still = when (evalJs(tab.webView, PageFingerprint.targetExistsScript(jsStringLiteral(eff)))) {
                    "YES" -> true
                    "NO" -> false
                    else -> null
                }
                ok("hovered" to echo, "check" to PageFingerprint.diff(before, after, still))
            }
            "NOT_FOUND" -> err("找不到元素：$echo")
            null -> err("执行超时或失败")
            else -> err("意外结果：$r")
        }
    }

    /**
     * `scroll_into_view`：把元素滚进视口中央（复用 [scrollElementJsScript]，与 `scroll`
     * 的元素路同一脚本——不新写滚动逻辑）。只读（滚动同 [scroll]，不动页面内容）。
     */
    internal fun scrollIntoView(target: String?, tabId: String? = null): String {
        val isRef = target != null && target.startsWith("@")
        if (!isRef) {
            val v = BridgePolicy.checkSelector(target)
            if (v is BridgePolicy.Verdict.Deny) return err(v.reason)
        }
        val t = target(tabId)
        val tab = t.tab ?: return err(t.error ?: "没有活跃标签")
        val eff: String
        if (isRef) {
            when (val ro = resolveRef(tab, target)) {
                is RefOut.Use -> eff = ro.locator
                is RefOut.Deny -> return err(ro.msg)
                is RefOut.Css -> return err("句柄解析异常：$target") // 不可达，穷尽性兜底
            }
        } else {
            eff = target!!
        }
        return when (val r = evalJs(tab.webView, scrollElementJsScript(eff))) {
            "OK" -> ok("scrolled_to" to target!!)
            "NOT_FOUND" -> err("找不到元素：$target")
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

    // ── 228 批 B-2：导航三件（走 BrowserEngine 既有能力，不新造 WebView 控制器） ──
    //
    // 派工单 §2 B2 的三条口径：① 能力走 [com.example.zhengdao.browser.BrowserEngine]
    // 既有的 goBack/goForward/reload（WebViewEngine 早已实现）；② 发起到完成是异步的
    // ——回执只说「已发起」，新页面用 status/snapshot 确认（头部 url 来自
    // PageFingerprint 的 location.href，导航完成后新快照自然读到新地址）；
    // ③ 没有历史时明说（canGoBack/canGoForward 先问），不许假装成功。

    /** `back`：后退一步（没有可后退的历史 ⇒ 明确回错误）。 */
    internal fun goBack(tabId: String? = null): String {
        val t = target(tabId)
        val tab = t.tab ?: return err(t.error ?: "没有活跃标签")
        return navRun(tab, "后退", "back", { eng ->
            if (eng.canGoBack()) null else "没有可后退的历史（引擎报告不可后退）"
        }, { it.goBack() })
    }

    /** `forward`：前进一步（没有可前进的历史 ⇒ 明确回错误）。 */
    internal fun goForward(tabId: String? = null): String {
        val t = target(tabId)
        val tab = t.tab ?: return err(t.error ?: "没有活跃标签")
        return navRun(tab, "前进", "forward", { eng ->
            if (eng.canGoForward()) null else "没有可前进的历史（引擎报告不可前进）"
        }, { it.goForward() })
    }

    /** `reload`：重新加载当前页（首帧未加载完时 reload 也无害，不设前置）。 */
    internal fun reload(tabId: String? = null): String {
        val t = target(tabId)
        val tab = t.tab ?: return err(t.error ?: "没有活跃标签")
        return navRun(tab, "重新加载", "reload", { null }, { it.reload() })
    }

    /** 导航三件的公共外壳：guard 先问引擎 → 主线程执行 → 回执「已发起」。 */
    private fun navRun(
        tab: BrowserTabManager.Tab,
        what: String,
        wire: String,
        guard: (com.example.zhengdao.browser.BrowserEngine) -> String?,
        act: (com.example.zhengdao.browser.BrowserEngine) -> Unit,
    ): String {
        val r = onMain {
            guard(tab.engine)?.let { return@onMain err(it) }
            act(tab.engine)
            ok(wire to "issued")
        }
        return r.value
            ?: r.failure?.let { err("$what 执行失败：${it.message}") }
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
     * `tabs`：列出全部标签（228 批 B-4）——id/是否活跃/url/title ＋ 总数与活跃 id。
     *
     * 脱敏与 [status] 同一道：url 过 redactUrl、title 过 redactTabTitle（两道都在 Kotlin，
     * 桥边界处）。活跃 id 从 [target] 的缺省分支取——路由闸钉住 `BrowserTabManager.activeTab()`
     * 在本文件恰 2 处，这里**不得**新增第三处直调。tabs()/activeId 都是 Compose 快照状态
     * （限主线程读），整段进 onMain。
     */
    internal fun tabsList(): String {
        val r = onMain {
            val activeId = target(null).tab?.id
            val items = BrowserTabManager.tabs().joinToString(",") { t ->
                val st = t.state
                val url = SensitiveRedactor().redactUrl(st.url)
                val title = SensitiveRedactor().redactTabTitle(st.title)
                """{"id":${t.id},"active":${t.id == activeId},""" +
                    """"url":"${PageSnapshot.jsonEscape(url)}",""" +
                    """"title":"${PageSnapshot.jsonEscape(title)}"}"""
            }
            okRaw("\"tabs\":[$items],\"tabCount\":${BrowserTabManager.count()},\"activeId\":${activeId ?: "null"}")
        }
        return r.value
            ?: r.failure?.let { err("tabs 执行失败：${it.message}") }
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
    /**
     * 一次截图的落盘结果（第一百七十七批：**JSON 与 ref 两个出口共用这一份**）。
     *
     * 为什么要有 ref：抱朴侧不需要 JSON 文本，它要的是「文件在哪」——好把像素读进模型
     * （`VisionFiles.load`）并带上相对路径。桥侧（终端 guest）仍要 JSON 形状，一个字不改。
     */
    internal data class ShotRef(
        val file: String,
        val hostPath: String,
        val guestPath: String,
        val bytes: Int,
        val mode: String,
        val width: Int,
        val height: Int,
        val scale: Float,
        val note: String?,
    )

    /** 截图结局：要么拿到落盘引用，要么一句可读错误（不抛、不回 1×1 假成功）。 */
    internal sealed interface ShotCapture {
        data class Ok(val ref: ShotRef) : ShotCapture
        data class Bad(val error: String) : ShotCapture
    }

    /**
     * 截图目录名（第一百七十七批用户口径：**`证道/截屏/`**）。
     *
     * 为什么是固定公共区而不是工作区：`Store.PUBLIC_ROOT` 的 KDoc 明写「App 自己的东西
     * 刻意不跟随工作区」（换工作区不该让截图失联）；为什么抱朴也能读：`VisionFiles.load`
     * 本批加了 `extraRoot`，把 `Store.root()` 认作第二个允许的根。
     * **不进相册**：这里零 MediaStore——相册可见的 `Pictures/证道/截屏` 属第一百七十五批。
     */
    internal const val SHOT_DIR = "截屏"

    /** 截图目录（保证存在）。 */
    internal fun shotDir(context: Context): File = File(Store.root(context), SHOT_DIR).apply { mkdirs() }

    /** 两条说明合成一条（旧的在前；空串不占位）。199 起「退化说明 ＋ 相册说明」要同时出现。 */
    private fun joinNote(a: String?, b: String?): String? =
        listOfNotNull(a?.takeIf { it.isNotBlank() }, b?.takeIf { it.isNotBlank() })
            .joinToString("；")
            .ifBlank { null }

    /**
     * 核对图文件名（占用就 `-2`、`-3`）。
     *
     * 199 把文件名从「毫秒时间戳」换成 `fileStamp()`（相册那条支路要同一个名字口径），
     * 于是同一秒内连截两张会撞名——撞名就加序号，**不许覆盖**（前一张可能正被读图管线用着）。
     */
    private fun freeShotName(dir: File, base: String): String {
        if (!File(dir, "$base.png").exists()) return "$base.png"
        var n = 2
        while (true) {
            val next = "$base-$n.png"
            if (!File(dir, next).exists()) return next
            n++
        }
    }

    /**
     * 截一张图并落盘（**唯一实现**：[screenshot] 与抱朴网关共用）。
     *
     * 与旧 [screenshot] 的行为逐字一致（几何问页面、两条截图路线、退化如实标注、
     * 失败给原因），差别只有两个：① 返回 [ShotCapture] 而不是 JSON；② 落盘目录是
     * [SHOT_DIR]（`截屏`）而不是旧的 `screenshots`。
     */
    internal fun captureShotRef(
        context: Context,
        tabId: String?,
        fullPage: String?,
        selector: String? = null,
        /**
         * 第一百九十九批：这张图**给谁看**。
         *
         * `false`（默认）＝模型核对图：落 `证道/截屏/`（177 现状，`VisionFiles` 要回读），
         * **零 MediaStore**（一次任务几十张，全刷进相册就是把用户相册占满）。
         * `true` ＝用户可见截屏：走 `MediaStore` 进 `Pictures/证道/截屏/`（`IS_PENDING`
         * 事务式发布），相册能看到；**不双写**——只落相册那一处（设计 §5.2「一份图只落一处」）。
         * 相册通道不可用 ⇒ 退回文件落盘并在 `note` 里如实说明（不假装进了相册）。
         */
        album: Boolean = false,
    ): ShotCapture {
        val sel = selector?.trim()?.takeIf { it.isNotEmpty() }
        sel?.let {
            val v = BridgePolicy.checkSelector(it)
            if (v is BridgePolicy.Verdict.Deny) return ShotCapture.Bad(v.reason)
        }
        val tab = if (tabId.isNullOrBlank()) {
            BrowserTabManager.activeTab()
        } else {
            val id = tabId.trim().toIntOrNull() ?: return ShotCapture.Bad("screenshot 的 tabId 不是数字")
            BrowserTabManager.tabs().firstOrNull { it.id == id }
        } ?: return ShotCapture.Bad("没有这个标签")
        val mode = shotMode(fullPage, sel)
        // 几何问页面（CSS px）：页面不在台前也有数——这是 §2.3b(a) 的关键一步
        val bounds: ShotBounds? = when (mode) {
            ShotMode.FULL_PAGE -> null
            ShotMode.VIEWPORT -> ShotBounds.parse(evalJs(tab.webView, viewportBoundsJsScript()))
            ShotMode.ELEMENT -> {
                val s = sel ?: return ShotCapture.Bad("screenshot 缺 selector")
                when (val raw = evalJs(tab.webView, elementBoundsJsScript(s))) {
                    null -> return ShotCapture.Bad("执行超时或失败")
                    "NOT_FOUND" -> return ShotCapture.Bad("找不到元素：$s")
                    else -> ShotBounds.parse(raw)
                }
            }
        }
        if (mode == ShotMode.ELEMENT && bounds != null && (bounds.w <= 0.0 || bounds.h <= 0.0)) {
            return ShotCapture.Bad("元素没有尺寸：$sel（${whole(bounds.w)}×${whole(bounds.h)} CSS px）")
        }
        if (mode != ShotMode.FULL_PAGE && bounds == null) {
            return ShotCapture.Bad("取不到页面几何（页面可能还没绘制）")
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
            ?: return ShotCapture.Bad("截屏失败（页面还没绘制：整页位图与可视区都没取到）")
        // 退化到可视区时，mode 要如实改成 viewport（不许挂着 element/fullpage 的名义回一张别的图）
        val effectiveMode = if (note == null) mode else ShotMode.VIEWPORT
        val stamp = com.example.zhengdao.util.TimeFormats.fileStamp()
        val name = "证道截屏-$stamp"

        if (album) {
            // 人看的这张：只走相册（不做 File 落盘，避免同一张图两份、归属两条互打）
            when (val pub = MediaPublisher.publish(
                ctx = context,
                bytes = shot.bytes,
                name = name,
                mime = "image/png",
                source = MediaOwnership.SOURCE_SCREENSHOT,
                ext = "png",
            )) {
                is MediaPublish.Ok -> return ShotCapture.Ok(
                    ShotRef(
                        file = pub.entry.relPath,
                        hostPath = "",
                        guestPath = "",
                        bytes = shot.bytes.size,
                        mode = effectiveMode.wire,
                        width = shot.width,
                        height = shot.height,
                        scale = shot.scale,
                        note = joinNote(
                            note,
                            if (pub.ledgerRecorded) "已存进相册（Pictures/证道/截屏/）"
                            else "已存进相册，但归属清单没写成（删的时候会再问一次）",
                        ),
                    ),
                )

                is MediaPublish.Duplicate -> return ShotCapture.Ok(
                    ShotRef(
                        file = pub.entry.relPath,
                        hostPath = "",
                        guestPath = "",
                        bytes = shot.bytes.size,
                        mode = effectiveMode.wire,
                        width = shot.width,
                        height = shot.height,
                        scale = shot.scale,
                        note = joinNote(note, "同一张图此前已进相册（没重复入库）"),
                    ),
                )

                is MediaPublish.Unavailable -> {
                    // 相册不可用：**退回 177 的文件落盘**，并如实说清这张不在相册里
                    note = joinNote(note, "相册没写成（${pub.reason}），先存 证道/截屏/（不进相册）")
                }
            }
        }

        val dir = shotDir(context)
        // 同一秒内连截两张：名字撞了要加序号，**绝不覆盖**前一张（前一张可能正被模型读）
        val fileName = freeShotName(dir, "bridge-$stamp")
        val out = File(dir, fileName)
        return runCatching { out.writeBytes(shot.bytes) }.fold(
            onSuccess = {
                ShotCapture.Ok(
                    ShotRef(
                        file = "$SHOT_DIR/$fileName",
                        hostPath = out.absolutePath,
                        guestPath = "/opt/zhengdao/$SHOT_DIR/$fileName",
                        bytes = shot.bytes.size,
                        mode = effectiveMode.wire,
                        width = shot.width,
                        height = shot.height,
                        scale = shot.scale,
                        note = note,
                    ),
                )
            },
            onFailure = { ShotCapture.Bad("写截图失败：${it.message}") },
        )
    }

    fun screenshot(context: Context, tabId: String?, fullPage: String?, selector: String? = null): String =
        when (val c = captureShotRef(context, tabId, fullPage, selector)) {
            is ShotCapture.Ok -> screenshotJson(
                file = c.ref.file,
                hostPath = c.ref.hostPath,
                guestPath = c.ref.guestPath,
                bytes = c.ref.bytes,
                mode = c.ref.mode,
                width = c.ref.width,
                height = c.ref.height,
                scale = c.ref.scale,
                note = c.ref.note,
            )
            is ShotCapture.Bad -> err(c.error)
        }

    // ── 坐标操作四件 ＋ 等待（第一百七十七批 S2） ────────────────────────────
    //
    // 为什么走 `dispatchTouchEvent` 而不是页面里的 JS 合成事件：canvas/游戏/自定义手势
    // 页面只认真实触摸流，JS 派的 MouseEvent 到不了它们；而 MotionEvent 走的是 WebView
    // 自己的命中测试与手势识别，与手指点在屏幕上同一条路。
    // 为什么坐标先换算：模型看截图说的是 CSS px（视口坐标），视图是物理像素；
    // 换算判据在 `TapGeometry`（纯函数、JVM 直测）。pinch 缩放过的页面本批不保证，已挂账。

    /** 触摸注入（主线程；返回页面是否接收）。 */
    private fun touch(webView: WebView, action: Int, x: Int, y: Int, downTime: Long): Boolean {
        val r = onMain {
            val now = SystemClock.uptimeMillis()
            val ev = MotionEvent.obtain(downTime, now, action, x.toFloat(), y.toFloat(), 0)
            val handled = webView.dispatchTouchEvent(ev)
            ev.recycle()
            handled
        }
        return r.value == true
    }

    /** 页面几何（CSS 视口尺寸 ＋ 视图像素尺寸）——两者都不为正 ⇒ null（页面还没绘制）。 */
    private class ViewGeometry(val cssW: Int, val cssH: Int, val viewW: Int, val viewH: Int)

    private fun geometryOf(tab: BrowserTabManager.Tab): ViewGeometry? {
        val raw = evalJs(tab.webView, viewportBoundsJsScript()) ?: return null
        val p = raw.split('|')
        if (p.size < 4) return null
        val cssW = p[2].trim().toIntOrNull() ?: return null
        val cssH = p[3].trim().toIntOrNull() ?: return null
        val viewW = tab.webView.width
        val viewH = tab.webView.height
        if (cssW <= 0 || cssH <= 0 || viewW <= 0 || viewH <= 0) return null
        return ViewGeometry(cssW, cssH, viewW, viewH)
    }

    private fun fingerprintOf(tab: BrowserTabManager.Tab): PageFingerprint.Fp? =
        PageFingerprint.parse(evalJs(tab.webView, PageFingerprint.SCRIPT))

    /** 坐标越界时的统一人话（带上当前视口尺寸，模型据此决定先滚还是重点）。 */
    private fun outOfViewport(cssX: Int, cssY: Int, geo: ViewGeometry): String =
        "坐标 (${cssX},${cssY}) 不在视口里（当前视口 ${geo.cssW}×${geo.cssH} CSS px）；" +
            "先 browser_scroll，或按最新截图重新取点"

    /**
     * 点一下（CSS px 视口坐标）。回执带 S3 校验行。
     */
    internal fun tap(cssX: Int, cssY: Int, tabId: String? = null): String {
        val t = target(tabId)
        val tab = t.tab ?: return err(t.error ?: "没有活跃标签")
        val geo = geometryOf(tab) ?: return err("取不到页面几何（页面可能还没绘制）")
        if (!TapGeometry.insideViewport(cssX, cssY, geo.cssW, geo.cssH)) {
            return err(outOfViewport(cssX, cssY, geo))
        }
        val pt = TapGeometry.map(cssX, cssY, geo.cssW, geo.cssH, geo.viewW, geo.viewH)
            ?: return err("坐标换算失败（页面几何无效）")
        val before = fingerprintOf(tab)
        val down = SystemClock.uptimeMillis()
        val downOk = touch(tab.webView, MotionEvent.ACTION_DOWN, pt.x, pt.y, down)
        val upOk = touch(tab.webView, MotionEvent.ACTION_UP, pt.x, pt.y, down)
        if (!downOk && !upOk) return err("页面没有接收这次触摸（dispatchTouchEvent 返回 false）")
        Thread.sleep(NAV_SETTLE_MS)
        val after = fingerprintOf(tab)
        return ok(
            "tapped" to "($cssX,$cssY)",
            "view" to "(${pt.x},${pt.y})px",
            "check" to PageFingerprint.diff(before, after, null),
        )
    }

    /**
     * 长按（CSS px 视口坐标 ＋ 按住毫秒数）。
     *
     * 「按住」是真的等：down 与 up 之间在**调用线程**睡 durationMs（不是主线程），
     * 页面的长按识别看到的是真实时间差。
     */
    internal fun longPress(cssX: Int, cssY: Int, durationMs: Int, tabId: String? = null): String {
        val t = target(tabId)
        val tab = t.tab ?: return err(t.error ?: "没有活跃标签")
        val geo = geometryOf(tab) ?: return err("取不到页面几何（页面可能还没绘制）")
        if (!TapGeometry.insideViewport(cssX, cssY, geo.cssW, geo.cssH)) {
            return err(outOfViewport(cssX, cssY, geo))
        }
        val pt = TapGeometry.map(cssX, cssY, geo.cssW, geo.cssH, geo.viewW, geo.viewH)
            ?: return err("坐标换算失败（页面几何无效）")
        val hold = durationMs.coerceIn(MIN_PRESS_MS, MAX_PRESS_MS)
        val before = fingerprintOf(tab)
        val down = SystemClock.uptimeMillis()
        if (!touch(tab.webView, MotionEvent.ACTION_DOWN, pt.x, pt.y, down)) {
            return err("页面没有接收这次触摸（dispatchTouchEvent 返回 false）")
        }
        Thread.sleep(hold.toLong())
        touch(tab.webView, MotionEvent.ACTION_UP, pt.x, pt.y, down)
        Thread.sleep(NAV_SETTLE_MS)
        val after = fingerprintOf(tab)
        return ok(
            "pressed" to "($cssX,$cssY) ${hold}ms",
            "view" to "(${pt.x},${pt.y})px",
            "check" to PageFingerprint.diff(before, after, null),
        )
    }

    /**
     * 滑动（起点→终点，durationMs 走完）。
     *
     * 中间帧数由时长决定（约每 40ms 一帧，最多 24 帧）；每一帧都是一次主线程投递，
     * 帧间在调用线程睡，不阻塞主线程。
     */
    internal fun swipe(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int,
        tabId: String? = null,
    ): String {
        val t = target(tabId)
        val tab = t.tab ?: return err(t.error ?: "没有活跃标签")
        val geo = geometryOf(tab) ?: return err("取不到页面几何（页面可能还没绘制）")
        if (!TapGeometry.insideViewport(x1, y1, geo.cssW, geo.cssH)) {
            return err("起点" + outOfViewport(x1, y1, geo))
        }
        if (!TapGeometry.insideViewport(x2, y2, geo.cssW, geo.cssH)) {
            return err("终点" + outOfViewport(x2, y2, geo))
        }
        val dur = durationMs.coerceIn(MIN_SWIPE_MS, MAX_PRESS_MS)
        val from = TapGeometry.map(x1, y1, geo.cssW, geo.cssH, geo.viewW, geo.viewH)
            ?: return err("坐标换算失败（页面几何无效）")
        val to = TapGeometry.map(x2, y2, geo.cssW, geo.cssH, geo.viewW, geo.viewH)
            ?: return err("坐标换算失败（页面几何无效）")
        val steps = ((dur / 40).coerceIn(2, 24))
        val path = TapGeometry.path(from.x, from.y, to.x, to.y, steps)
        val before = fingerprintOf(tab)
        val down = SystemClock.uptimeMillis()
        var accepted = touch(tab.webView, MotionEvent.ACTION_DOWN, path.first().first, path.first().second, down)
        val startAt = SystemClock.uptimeMillis()
        for (i in 1 until path.size) {
            // 按进度睡，事件时间戳才与手指速度一致（页面才认得出「快滑」还是「慢拖」）
            val targetAt = startAt + dur.toLong() * i / (path.size - 1)
            val wait = targetAt - SystemClock.uptimeMillis()
            if (wait > 0) Thread.sleep(wait)
            accepted = touch(
                tab.webView,
                if (i == path.size - 1) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE,
                path[i].first,
                path[i].second,
                down,
            ) || accepted
        }
        if (!accepted) return err("页面没有接收这次滑动（dispatchTouchEvent 返回 false）")
        Thread.sleep(NAV_SETTLE_MS)
        val after = fingerprintOf(tab)
        return ok(
            "swiped" to "($x1,$y1)→($x2,$y2) ${dur}ms",
            "check" to PageFingerprint.diff(before, after, null),
        )
    }

    /**
     * 按键（enter/tab/backspace/escape）。
     *
     * ⚠️ **用页面里的 KeyboardEvent 合成**：`enter` 能触发监听 keydown/keypress 的页面逻辑，
     * 但**不保证**触发浏览器原生默认行为（表单隐式提交）——这与「真按物理回车」不同。
     * 施工图口径：先挂账，真机发现不提交再单独补 `dispatchKeyEvent`（本批不写）。
     */
    internal fun keyPress(key: String, tabId: String? = null): String {
        val script = keyJsScript(key) ?: return err("不支持的 key：$key（只认 enter/tab/backspace/escape）")
        val t = target(tabId)
        val tab = t.tab ?: return err(t.error ?: "没有活跃标签")
        val before = fingerprintOf(tab)
        return when (val r = evalJs(tab.webView, script)) {
            "OK" -> {
                Thread.sleep(NAV_SETTLE_MS)
                val after = fingerprintOf(tab)
                ok(
                    "key" to key,
                    "check" to PageFingerprint.diff(before, after, null),
                    "note" to "回车是页面内合成事件：监听 keydown 的页面能收到，原生表单隐式提交不保证",
                )
            }
            "NO_TARGET" -> err("页面上没有可聚焦的目标（先 browser_click 输入框再按回车）")
            null -> err("执行超时或失败")
            else -> err("意外结果：$r")
        }
    }

    /**
     * 等待：给 `ms` 就纯等；给 `selector` 就轮询到它出现或预算用完。
     *
     * 只读（不动页面、不动盘）；预算上限 [MAX_WAIT_MS]，轮询间隔 [WAIT_POLL_MS]。
     */
    internal fun await(ms: Int, selector: String?, tabId: String? = null): String {
        val budget = ms.coerceIn(0, MAX_WAIT_MS)
        val t = target(tabId)
        val tab = t.tab ?: return err(t.error ?: "没有活跃标签")
        val sel = selector?.trim()?.takeIf { it.isNotEmpty() }
            ?: run {
                Thread.sleep(budget.toLong())
                return ok("waited" to "${budget}ms")
            }
        val v = BridgePolicy.checkSelector(sel)
        if (v is BridgePolicy.Verdict.Deny) return err(v.reason)
        val script = PageFingerprint.targetExistsScript(jsStringLiteral(sel))
        val deadline = System.currentTimeMillis() + budget
        while (true) {
            when (evalJs(tab.webView, script)) {
                "YES" -> return ok("appeared" to sel, "waited" to "${budget}ms 以内")
                "NO" -> Unit
                else -> return err("等待时取不到页面状态（页面可能还没绘制）")
            }
            if (System.currentTimeMillis() >= deadline) {
                return err("等了 ${budget}ms 还没等到元素：$sel（可再等、或先 snapshot 看看页面）")
            }
            Thread.sleep(WAIT_POLL_MS)
        }
    }

    /**
     * 带前后校验的点击（**抱朴侧用这个**；桥的 `click` 仍走原路、回执不变）。
     * 228 批 A-1：target 同样收 ref 句柄——`@e3` 解析成定位子再点；still 核对也对
     * 定位子做（句柄本身不会出现在页面里）。
     */
    internal fun clickChecked(selector: String, tabId: String? = null): String {
        // 判路规则同 [click]（228）：@ 句柄不走 CSS 校验；CSS 校验在取标签之前
        val isRef = selector.startsWith("@")
        if (!isRef) {
            val v = BridgePolicy.checkSelector(selector)
            if (v is BridgePolicy.Verdict.Deny) return err(v.reason)
        }
        val t = target(tabId)
        val tab = t.tab ?: return err(t.error ?: "没有活跃标签")
        return if (isRef) {
            when (val ro = resolveRef(tab, selector)) {
                is RefOut.Use -> clickCheckedRun(tab, selector, ro.locator)
                is RefOut.Deny -> err(ro.msg)
                is RefOut.Css -> err("句柄解析异常：$selector") // 不可达（@ 开头 resolveRef 不回 Css），穷尽性兜底
            }
        } else {
            clickCheckedRun(tab, selector, selector)
        }
    }

    private fun clickCheckedRun(tab: BrowserTabManager.Tab, echo: String, eff: String): String {
        val before = fingerprintOf(tab)
        val r = evalJs(tab.webView, clickJsScript(eff))
        return when (r) {
            "OK" -> {
                Thread.sleep(NAV_SETTLE_MS)
                val after = fingerprintOf(tab)
                val still = when (evalJs(tab.webView, PageFingerprint.targetExistsScript(jsStringLiteral(eff)))) {
                    "YES" -> true
                    "NO" -> false
                    else -> null
                }
                ok("clicked" to echo, "check" to PageFingerprint.diff(before, after, still))
            }
            "NOT_FOUND" -> err("找不到元素：$echo")
            null -> err("执行超时或失败")
            else -> err("意外结果：$r")
        }
    }

    /** 带前后校验的打字（抱朴侧用；桥的 `type` 不变）。228 批 A-1：target 同收 ref 句柄。 */
    internal fun typeChecked(selector: String, text: String, tabId: String? = null): String {
        // 判路规则同 [click]（228）：@ 句柄不走 CSS 校验；CSS 校验在取标签之前
        val isRef = selector.startsWith("@")
        if (!isRef) {
            val v = BridgePolicy.checkSelector(selector)
            if (v is BridgePolicy.Verdict.Deny) return err(v.reason)
        }
        val t = target(tabId)
        val tab = t.tab ?: return err(t.error ?: "没有活跃标签")
        return if (isRef) {
            when (val ro = resolveRef(tab, selector)) {
                is RefOut.Use -> typeCheckedRun(tab, selector, ro.locator, text)
                is RefOut.Deny -> err(ro.msg)
                is RefOut.Css -> err("句柄解析异常：$selector") // 不可达（@ 开头 resolveRef 不回 Css），穷尽性兜底
            }
        } else {
            typeCheckedRun(tab, selector, selector, text)
        }
    }

    private fun typeCheckedRun(tab: BrowserTabManager.Tab, echo: String, eff: String, text: String): String {
        val before = fingerprintOf(tab)
        val r = evalJs(tab.webView, typeJsScript(eff, text))
        return when (r) {
            "OK" -> {
                val after = fingerprintOf(tab)
                val still = when (evalJs(tab.webView, PageFingerprint.targetExistsScript(jsStringLiteral(eff)))) {
                    "YES" -> true
                    "NO" -> false
                    else -> null
                }
                ok("typed" to echo, "check" to PageFingerprint.diff(before, after, still))
            }
            "NOT_FOUND" -> err("找不到元素：$echo")
            null -> err("执行超时或失败")
            else -> err("意外结果：$r")
        }
    }

    // ── 228 批 A：ref 句柄（快照代次绑定）＋ stable 等待 ─────────────────────
    //
    // 为什么这套东西存在（派工单 §1 硬要求）：快照给每个可交互节点发 `ref:"eN"`，
    // click/type 收 `@eN`；**旧句柄绝不允许「点到别的元素却回成功」**——宁可报
    // 「旧句柄已失效，请重新 browser_snapshot」。四道闸：
    // ① 形状闸（[RefHandle]）：`@` 开头就必须是 `@e数字`，其余拒绝；
    // ② 本闸（[refBookOf]）：该标签得有一本当前句柄本（快照时发的）；
    // ③ 代次闸（nonce＋epoch）：文档换了（导航/刷新）或 DOM 变了 ⇒ 失效；
    // ④ 命中闸（[refProbeJsScript]）：定位子在**当前页面**上查得到且 tagName 一致。
    //
    // 代次的锚是页面「眼」（[EYE_GETTER]）：每文档一次性安装的 MutationObserver，
    // nonce 每文档随机（同 URL reload 内容相同时 epoch 会从头数——只对 epoch 会假匹配，
    // nonce 把「这一份文档」钉死）、epoch 对任何 DOM 变更 +1。快照取 DOM 原文与读代次
    // **在同一次 JS 执行**里完成（[eyeFetchJsScript]）——分两步取就有缝：中间页面
    // 变了而锚还是旧的。

    /** 一次眼读数：文档身份（nonce）、DOM 代次（epoch）、加载状态、最近一次变更距今毫秒。 */
    internal data class EyeProbe(
        val nonce: String,
        val epoch: Long,
        val readyState: String,
        val quietMs: Long,
    )

    /** `stable` 的静默窗：DOM 连续这么多毫秒无变化才算稳（派工单口径 200ms）。 */
    internal const val STABLE_QUIET_MS = 200L

    /**
     * 四段 `nonce|epoch|readyState|quietMs` 的解析。缺段/不是数 ⇒ null（调用方如实
     * 报「取不到页面状态」，不猜）。
     */
    internal fun parseEyeProbe(raw: String?): EyeProbe? {
        if (raw == null) return null
        val p = raw.split('|')
        if (p.size != 4) return null
        val epoch = p[1].trim().toLongOrNull() ?: return null
        val quiet = p[3].trim().toLongOrNull() ?: return null
        return EyeProbe(p[0].trim(), epoch, p[2].trim(), quiet)
    }

    /** 一次快照原子取数的解析结果：眼读数 ＋ 正文 ＋ 几何表（228 批 B：几何可能缺席）。 */
    internal data class EyeFetch(val probe: EyeProbe?, val html: String, val geo: GeometryTable?)

    /**
     * [eyeFetchJsScript] 回包的解析。三段以控制符分隔：`\u0001` 前是眼读数（或 `NO_EYE`），
     * `\u0001`…`\u0002` 之间是正文，`\u0002` 之后是几何表（[parseGeometry]；空 ⇒ null）。
     * 眼没装上（observer 建不出来）⇒ probe 为 null 但正文与几何照带——快照正文不该因为
     * 锚不可用而丢。
     */
    internal fun parseEyeFetch(raw: String?): EyeFetch? {
        if (raw == null) return null
        val sep = raw.indexOf('\u0001')
        if (sep < 0) return null
        val head = raw.substring(0, sep)
        val rest = raw.substring(sep + 1)
        val geoSep = rest.indexOf('\u0002')
        val html = if (geoSep < 0) rest else rest.substring(0, geoSep)
        val geoRaw = if (geoSep < 0) "" else rest.substring(geoSep + 1)
        val probe = if (head == "NO_EYE") null else parseEyeProbe(head) ?: return null
        return EyeFetch(probe, html, parseGeometry(geoRaw))
    }

    /** [stableNow] 的判据面：加载完 ＋ 静默窗满。 */
    internal fun stableNow(p: EyeProbe): Boolean =
        p.readyState == "complete" && p.quietMs >= STABLE_QUIET_MS

    /** 页面「眼」安装器（幂等）：nonce 每文档随机；observer 对任何 DOM 变更 +1 纪元并记录时刻。 */
    private const val EYE_GETTER =
        "(function(){" +
            "if(!window.__zdEye){" +
            "try{var o=new MutationObserver(function(){window.__zdEye.e++;window.__zdEye.t=Date.now()});" +
            "o.observe(document.documentElement,{childList:true,subtree:true,attributes:true,characterData:true});" +
            "window.__zdEye={n:Math.random().toString(36).slice(2),e:0,t:Date.now()}}" +
            "catch(x){return null}}" +
            "return window.__zdEye})()"

    /**
     * 快照的原子取数脚本：眼读数、正文（`innerExpr`）与几何探针（`rootExpr` 子树）
     * **同一次执行**取出，`\u0001`/`\u0002` 分隔。先求 `v` 再判 `e`——眼没装上也不能丢正文。
     */
    private fun eyeFetchJsScript(innerExpr: String, rootExpr: String): String =
        "(function(){var e=$EYE_GETTER;var v=($innerExpr);var g=${geometryJsScript(rootExpr)};" +
            "if(!e)return 'NO_EYE\u0001'+v+'\u0002'+g;" +
            "return [e.n,e.e,document.readyState,(Date.now()-e.t)].join('|')+'\u0001'+v+'\u0002'+g})()"

    // ── 228 批 B-1：页面内几何探针（真可见性 ＋ 视口坐标）────────────────────
    //
    // 派工单 §2 B1：Kotlin 侧的 isHidden 只看 hidden/aria-hidden/内联 style，
    // class 里的 display:none 全漏（§0 现状表）。真可见性只有页面自己知道
    // （getComputedStyle 吃得到 class/样式表），所以探针在页面里跑；但**脱敏不前移**
    // ——探针只产数值与布尔（坐标/尺寸/可见性），一个字符的页面文本都不碰，
    // 正文照旧回 Kotlin 过 scrubSecrets→redactDom 两道。

    /** 几何探针条数上限：万节点页面的回包不能把桥拖爆；超出的节点拿不到坐标，如实缺席。 */
    internal const val MAX_GEO_ENTRIES = 2_000

    /** 一个可交互元素的几何读数（CSS px，视口坐标；`visible` 由页面按计算样式判定）。 */
    internal class GeoEntry(val x: Int, val y: Int, val w: Int, val h: Int, val visible: Boolean)

    /** 一次快照的几何表：布局视口尺寸 ＋ 定位子 → 读数。 */
    internal class GeometryTable(val viewW: Int, val viewH: Int, val byLocator: Map<String, GeoEntry>)

    /**
     * 几何段解析：首行 `viewW|viewH`，其后每行 `locator|x|y|w|h|vis`（`\u0003` 分行）。
     * 定位子由 SAFE_TAG 白名单字符构成（不含 `|`），六段铁律；畸形行跳过（宁缺勿猜），
     * 头行坏了整体作废（没有视口尺寸 inViewport 就算不出来）。
     */
    internal fun parseGeometry(raw: String?): GeometryTable? {
        if (raw.isNullOrEmpty()) return null
        val lines = raw.split('\u0003')
        val head = lines[0].split('|')
        if (head.size != 2) return null
        val vw = head[0].trim().toIntOrNull() ?: return null
        val vh = head[1].trim().toIntOrNull() ?: return null
        val map = LinkedHashMap<String, GeoEntry>()
        for (i in 1 until lines.size) {
            val f = lines[i].split('|')
            if (f.size != 6) continue
            val x = f[1].trim().toIntOrNull() ?: continue
            val y = f[2].trim().toIntOrNull() ?: continue
            val w = f[3].trim().toIntOrNull() ?: continue
            val h = f[4].trim().toIntOrNull() ?: continue
            val vis = when (f[5].trim()) {
                "1" -> true
                "0" -> false
                else -> continue
            }
            map[f[0]] = GeoEntry(x, y, w, h, vis)
        }
        return GeometryTable(vw, vh, map)
    }

    /**
     * 节点矩形与布局视口是否相交（部分相交也算在视口里）。零尺寸矩形不算
     * （vis=0 的在 [applyGeometry] 已被剔除，这里是同一条口径的算术面）。
     * 派工单 B 负例口径：视口外的节点**必须**带 inViewport=false——判定在 Kotlin
     * 现算，不信页面自报。
     */
    internal fun rectInViewport(g: GeoEntry, viewW: Int, viewH: Int): Boolean =
        viewW > 0 && viewH > 0 && g.w > 0 && g.h > 0 &&
            g.x < viewW && g.y < viewH && (g.x + g.w) > 0 && (g.y + g.h) > 0

    /**
     * 把几何表套到解析出的节点名单上（纯函数，JVM 直测）：
     * - JS 判**不可见**（含 class 里的 display:none / opacity:0 / 零尺寸 / visibility:hidden）
     *   ⇒ 整行剔除——这就是「class 隐藏的按钮不再出现在快照里」的落点；
     * - 命中且可见 ⇒ 附视口坐标与 inViewport；
     * - 定位子失配（解析器与实况 DOM 在病态 HTML 上漂移、超 [MAX_GEO_ENTRIES]、子树根自己）
     *   ⇒ 原样保留、不带坐标——**如实缺席**，不假装看得见。
     *
     * nodesTotal 同步减去剔除数：不调它的话「100 个节点、10 个隐藏」会被误报成
     * 「被截断」（nodesTruncated = total > listed）。截断场景（>300）里 300 之外的
     * 隐藏节点数不可知，调整后的 total 只会偏大 ⇒ nodesTruncated 照旧为真，不回退。
     */
    internal fun applyGeometry(s: PageSnapshot.Summary, geo: GeometryTable): PageSnapshot.Summary {
        var removed = 0
        val kept = ArrayList<PageSnapshot.Node>(s.nodes.size)
        for (n in s.nodes) {
            val g = if (n.path.isNotEmpty()) geo.byLocator[n.path] else null
            when {
                g == null -> kept += n
                !g.visible -> removed++
                else -> kept += n.copy(x = g.x, y = g.y, inViewport = rectInViewport(g, geo.viewW, geo.viewH))
            }
        }
        return PageSnapshot.Summary(s.title, kept, s.nodesTotal - removed, s.text, s.textTruncated)
    }

    /**
     * 几何探针脚本：遍历 `root` 子树的可交互元素（判集合与 Kotlin 侧 [com.example.zhengdao.browser.PageSnapshot]
     * 的 isInteractive 同一口径），每个算计算样式可见性 ＋ getBoundingClientRect，
     * 定位子与 pathOf 同一 nth-of-type 方案（同型兄弟序号）。输出首行 `viewW|viewH`，
     * 其后每行 `locator|x|y|w|h|vis`，`\u0003` 分行。
     *
     * 已知边界（写进报告）：Kotlin 迷你解析器与浏览器 DOM 在病态 HTML 上可能漂移
     * （如浏览器给 `<table>` 自动插 `<tbody>`）——漂移的节点拿不到几何，按「如实缺席」保留，
     * **不会**被误剔（剔除只认探针明说不可见）。
     */
    private fun geometryJsScript(rootExpr: String): String =
        "(function(){var root=$rootExpr;if(!root)return '';" +
            "var de=document.documentElement;" +
            "var out=[(de.clientWidth||0)+'|'+(de.clientHeight||0)];" +
            "var all=root.getElementsByTagName('*');" +
            "for(var i=0;i<all.length&&out.length<=" + MAX_GEO_ENTRIES + ";i++){" +
            "var el=all[i];var tag=(el.tagName||'').toLowerCase();" +
            "if(!/^[a-z][a-z0-9-]*$/.test(tag))continue;" +
            "if(tag==='input'){if((el.getAttribute('type')||'').toLowerCase()==='hidden')continue}" +
            "else if(tag==='a'){if(!el.getAttribute('href'))continue}" +
            "else if(tag!=='select'&&tag!=='textarea'&&tag!=='button'&&tag!=='summary'){" +
            "if(!el.hasAttribute('role')&&!el.hasAttribute('contenteditable')&&!el.hasAttribute('onclick'))continue}" +
            "var loc=(function(e,stop){var segs=[];var cur=e;var d=0;" +
            "while(cur&&cur.nodeType===1&&d<32){var t=(cur.tagName||'').toLowerCase();" +
            "if(!/^[a-z][a-z0-9-]*$/.test(t))return '';" +
            "var p=cur.parentElement;" +
            "if(p&&p.nodeType===1&&cur!==stop){var seen=0;var kids=p.children;" +
            "for(var j=0;j<kids.length;j++){if(kids[j].tagName===cur.tagName){seen++;if(kids[j]===cur)break}}" +
            "segs.push(t+':nth-of-type('+seen+')')}" +
            "else{segs.push(t)}" +
            "if(cur===stop)break;cur=p;d++}" +
            "return segs.reverse().join(' > ')})(el,root);" +
            "if(!loc)continue;" +
            "var cs=getComputedStyle(el);var r=el.getBoundingClientRect();" +
            "var invis=(cs.display==='none')||(cs.visibility==='hidden')||(parseFloat(cs.opacity)===0)||" +
            "(r.width<=0&&r.height<=0);" +
            "out.push(loc+'|'+Math.round(r.left)+'|'+Math.round(r.top)+'|'+Math.round(r.width)+'|'+" +
            "Math.round(r.height)+'|'+(invis?'0':'1'))}" +
            "return out.join('\\u0003')})()"

    /** stable 轮询的眼探针：只取四段读数，不带正文。 */
    private fun eyeProbeJsScript(): String =
        "(function(){var e=$EYE_GETTER;" +
            "if(!e)return 'NO_EYE';" +
            "return [e.n,e.e,document.readyState,(Date.now()-e.t)].join('|')})()"

    /** 一条 ref 句柄：定位子（纯 nth-of-type CSS 链）＋ 子树前缀（子树快照时）＋ tagName 核对值。 */
    internal data class RefEntry(val locator: String, val subtreeSelector: String?, val tag: String)

    /** 一本快照的句柄簿：绑定本次快照的眼读数（nonce＝文档身份，epoch＝DOM 代次）。 */
    internal data class RefBook(
        val tabId: Int,
        val nonce: String,
        val domEpoch: Long,
        val entries: Map<String, RefEntry>,
    )

    /** 句柄簿全局上限（每标签只留当前一本；防疯狂开标签把簿撑爆）。 */
    private const val REF_BOOK_KEEP = 8

    private val refBooks = object : LinkedHashMap<Int, RefBook>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, RefBook>): Boolean =
            size > REF_BOOK_KEEP
    }

    /** 快照换代：同一标签旧本整体丢弃（book=null ⇒ 该标签本轮快照没有可锚定的眼）。 */
    internal fun installRefBook(tabId: Int, book: RefBook?) {
        synchronized(refBooks) {
            if (book == null) refBooks.remove(tabId) else refBooks[tabId] = book
        }
    }

    internal fun refBookOf(tabId: Int): RefBook? = synchronized(refBooks) { refBooks[tabId] }

    /** [RefHandle.decide] 的判定（纯函数矩阵，JVM 单测直接钉）。 */
    internal sealed interface RefDecision {
        /** 不是 ref 句柄（null 或非 `@` 开头）⇒ 调用方走既有 checkSelector 路。 */
        data object NotRef : RefDecision

        /** 失效/拒绝；除形状闸外文案统一含「旧句柄已失效，请重新 browser_snapshot」。 */
        data class Stale(val reason: String) : RefDecision

        /** 代次有效：给出该句的定位子与核对值。 */
        data class Ok(val entry: RefEntry) : RefDecision
    }

    /**
     * ref 句柄的纯判据（闸①②③；闸④要碰页面，在 [resolveRef]）。
     *
     * 为什么 nonce＋epoch 双锚：只对 epoch 的话，同 URL reload 会把「刷新过的页面」
     * 误判成「没变」（epoch 从头数、内容可能一样）；nonce 把「这一份文档」钉死。
     */
    internal object RefHandle {
        /** 协议形状：`@` ＋ `e` ＋ 数字（快照 renderNodes 发出的就是这形状）。 */
        val REF_SHAPE: Regex = Regex("^@e\\d+$")

        fun decide(
            raw: String?,
            book: RefBook?,
            tabId: Int,
            nowNonce: String?,
            nowEpoch: Long?,
        ): RefDecision {
            if (raw == null || !raw.startsWith("@")) return RefDecision.NotRef
            val t = raw.trim()
            if (!REF_SHAPE.matches(t)) {
                return RefDecision.Stale("@ 句柄只认快照里给的 @e数字（如 @e3）；先 browser_snapshot 再用")
            }
            if (book == null || book.tabId != tabId) {
                return RefDecision.Stale(
                    "旧句柄已失效，请重新 browser_snapshot（这个标签还没有可用的句柄本）",
                )
            }
            // 键不带 @（refEntriesOf 发的是 e3）；句柄带 @，剥掉再查
            val entry = book.entries[t.substring(1)]
                ?: return RefDecision.Stale(
                    "旧句柄已失效，请重新 browser_snapshot（句柄 $t 不在当前快照里）",
                )
            if (nowNonce == null || nowEpoch == null || nowNonce != book.nonce || nowEpoch != book.domEpoch) {
                return RefDecision.Stale("旧句柄已失效，请重新 browser_snapshot（页面已导航或内容已变化）")
            }
            return RefDecision.Ok(entry)
        }
    }

    /**
     * 从快照节点造 ref 表：`eN`（N＝nodes 数组序，1 起）→ [RefEntry]。
     *
     * 整页快照：定位子＝[PageSnapshot.Node.path] 原样。子树快照：refProbe 是
     * 「先查子树根、再在子树内查定位子」，所以剥掉 path 首段（子树根自己那段）；
     * 剥不出来（path 只有一段＝子树根自己）⇒ 不发 ref——子树根直接用 CSS 更省事。
     */
    internal fun refEntriesOf(
        nodes: List<PageSnapshot.Node>,
        subtreeSelector: String?,
    ): Map<String, RefEntry> {
        val out = LinkedHashMap<String, RefEntry>()
        nodes.forEachIndexed { i, n ->
            if (n.path.isEmpty()) return@forEachIndexed
            val locator = if (subtreeSelector == null) n.path else relativeLocator(n.path) ?: return@forEachIndexed
            out["e${i + 1}"] = RefEntry(locator, subtreeSelector, tagOfPath(n.path))
        }
        return out
    }

    /** 子树快照的相对定位子：剥首段；没有 ` > ` ⇒ null（子树根自己）。 */
    internal fun relativeLocator(path: String): String? =
        if (path.contains(" > ")) path.substringAfter(" > ") else null

    /** 路径末段标签名：`div > a:nth-of-type(2)` → `a`。 */
    internal fun tagOfPath(path: String): String =
        path.substringAfterLast(" > ").substringBefore(':').trim()

    /** [resolveRef] 的结局：Css＝走既有 checkSelector 路；Use＝句柄解析成定位子；Deny＝拒绝。 */
    internal sealed interface RefOut {
        data object Css : RefOut
        data class Use(val locator: String) : RefOut
        data class Deny(val msg: String) : RefOut
    }

    /**
     * click/type 的 target 判路（228 批 A-1，无含糊）：`@` 开头 ⇒ 句柄路（形状不对
     * 也是拒，绝不落回 CSS 去 querySelector 一个非法选择器）；其余一切（含 null）⇒
     * [RefOut.Css]。
     *
     * 句柄路的闸序：形状 → 本闸（[refBookOf]）→ 眼闸（页面探针）→ 代次
     * （[RefHandle.decide]）→ 命中（[refProbeJsScript]）。任何一关不过都回
     * 「旧句柄已失效，请重新 browser_snapshot」系文案——**宁失效，不点错**。
     */
    internal fun resolveRef(tab: BrowserTabManager.Tab, target: String?): RefOut {
        if (target == null || !target.startsWith("@")) return RefOut.Css
        val t = target.trim()
        if (!RefHandle.REF_SHAPE.matches(t)) {
            return RefOut.Deny("@ 句柄只认快照里给的 @e数字（如 @e3）；先 browser_snapshot 再用")
        }
        val book = refBookOf(tab.id)
            ?: return RefOut.Deny(
                "旧句柄已失效，请重新 browser_snapshot（这个标签还没有可用的句柄本：先 browser_snapshot）",
            )
        val probe = parseEyeProbe(evalJs(tab.webView, eyeProbeJsScript()))
            ?: return RefOut.Deny(
                "取不到页面状态（页面可能还没绘制）；句柄 $t 暂不可用，请重新 browser_snapshot",
            )
        return when (val d = RefHandle.decide(t, book, tab.id, probe.nonce, probe.epoch)) {
            is RefDecision.Stale -> RefOut.Deny(d.reason)
            is RefDecision.NotRef -> RefOut.Css
            is RefDecision.Ok -> when (evalJs(tab.webView, refProbeJsScript(d.entry))) {
                "OK" -> RefOut.Use(d.entry.locator)
                "STALE" -> RefOut.Deny("旧句柄已失效，请重新 browser_snapshot（元素已不在原地）")
                else -> RefOut.Deny(
                    "取不到页面状态（页面可能还没绘制）；句柄 $t 暂不可用，请重新 browser_snapshot",
                )
            }
        }
    }

    /**
     * 句柄闸④的页面探针：定位子（含子树前缀）查得到且 tagName 与快照时一致 ⇒ `OK`，
     * 否则（查无此元素 / 标签换了）`STALE`——失效按失效报，**绝不**把「点错」报成成功。
     */
    private fun refProbeJsScript(e: RefEntry): String {
        val find = if (e.subtreeSelector == null) {
            "document.querySelector(" + jsStringLiteral(e.locator) + ")"
        } else {
            "(function(){var r=document.querySelector(" + jsStringLiteral(e.subtreeSelector) + ");" +
                "return r?r.querySelector(" + jsStringLiteral(e.locator) + "):null})()"
        }
        return "(function(){var el=$find;if(!el)return 'STALE';" +
            "return (el.tagName?el.tagName.toLowerCase():'')===" + jsStringLiteral(e.tag) +
            "? 'OK':'STALE'})()"
    }

    /**
     * `browser_wait` 的 mode=stable（228 批 A-3）：readyState==complete ＋ DOM 连续
     * [STABLE_QUIET_MS] 无变化。预算仍夹 [MAX_WAIT_MS]；到点不稳**如实报「未稳定」**
     * （写清卡在哪），不冒充成功。
     */
    internal fun awaitStable(ms: Int, tabId: String? = null): String {
        val budget = ms.coerceIn(0, MAX_WAIT_MS)
        val t = target(tabId)
        val tab = t.tab ?: return err(t.error ?: "没有活跃标签")
        val deadline = System.currentTimeMillis() + budget
        var waited = 0L
        while (true) {
            val probe = parseEyeProbe(evalJs(tab.webView, eyeProbeJsScript()))
                ?: return err("等待时取不到页面状态（页面可能还没绘制）")
            if (stableNow(probe)) {
                return ok(
                    "stable" to "readyState=${probe.readyState}，DOM 已连续 ≥${STABLE_QUIET_MS}ms 无变化",
                    "waited" to "${waited}ms",
                )
            }
            if (System.currentTimeMillis() >= deadline) {
                val why = if (probe.readyState != "complete") {
                    "readyState=${probe.readyState}（页面还没加载完）"
                } else {
                    "DOM 最近一次变化在 ${probe.quietMs}ms 前（静默未满 ${STABLE_QUIET_MS}ms）"
                }
                return err("等了 ${budget}ms 仍未稳定：$why")
            }
            Thread.sleep(WAIT_POLL_MS)
            waited += WAIT_POLL_MS
        }
    }

    /** 按键的页面脚本（不支持的 key ⇒ null）。 */
    private fun keyJsScript(key: String): String? {
        val (name, code) = when (key.trim().lowercase()) {
            "enter" -> "Enter" to 13
            "tab" -> "Tab" to 9
            "backspace" -> "Backspace" to 8
            "escape" -> "Escape" to 27
            else -> return null
        }
        return "(function(){var el=document.activeElement||document.body;" +
            "if(!el)return 'NO_TARGET';var ok=0;" +
            "['keydown','keypress','keyup'].forEach(function(t){" +
            "try{el.dispatchEvent(new KeyboardEvent(t,{key:'$name',code:'$name'," +
            "keyCode:$code,which:$code,bubbles:true,cancelable:true}));ok=ok+1}" +
            "catch(err){ok=ok}}) ;return ok>0?'OK':'FAIL'})()"
    }

    // ── WebView 桥接小工具 ─────────────────────────────────────────────────

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
