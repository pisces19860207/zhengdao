// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

package com.example.zhengdao.ui.browser

import android.content.ComponentCallbacks2
import android.content.Context
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.zhengdao.browser.WebViewEngine
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 玄览对外门面（第⑨刀 B 起为薄门面）——跨界面入口 + 切 Tab 请求。
 *
 * 为什么需要：玄览 Tab 在 `when (tab)` 分支里，切到底栏其他 Tab 时 BrowserScreen 离开组合、
 * remember 的 WebView 会被回收——用户切走再回来页面白了。终端链接点击（阶段 1.5）发生在
 * TerminalActivity（独立 Activity），更要求实例跨 Activity 存活。
 *
 * 设计依据：《证道-浏览器Tab技术选型与架构草案-2026-10-13》v1.1 §4.1（分层）、§4.6（打开层入口）。
 *
 * 防泄漏：WebView 用 **applicationContext** 创建，不持 Activity 引用。
 *
 * 阶段 2（第⑨刀 B）拆分：多标签本体（按 tabId 管多个 Engine / 渲染优先级 / 生命周期 /
 * 每标签状态）搬到 [BrowserTabManager]；本类只留**跨界面入口**（TerminalActivity 与
 * MainActivity 都在用，不动它们）：
 * - [pendingUrl]：外部打开入口（终端链接点击写入）——**Compose 可观察状态**
 *   （P1-8，2026-10-19：已停在玄览时点链接也要立刻被消费，裸 @Volatile var 驱动不了重组）；
 * - [requestBrowserTab] / [consumeTabRequest]：请求主页切到玄览（一次性）；
 * - [obtain] / [view]：取活跃引擎/WebView 的转发口。
 *
 * 原「跨 Tab 保留的界面状态」（rememberPage/lastUrl/everLoaded）随多标签迁到
 * [BrowserTabManager.Tab] 的 [BrowserTabState]（每标签一份；语义由 BrowserTabStateTest 钉住）。
 *
 * **受控回收出口（第⑱刀 · 审计 D-4）**：WebView 用 applicationContext 建、跨 Tab 保活，
 * 此前**全仓没有任何 destroy() 出口** —— 内存压力下没人回收。现在由 [onTrimMemory]
 * （两 Activity 的 onTrimMemory 各调一行）在 [ComponentCallbacks2.TRIM_MEMORY_COMPLETE] 档
 * 触发 [releaseIfIdle]：只有"没有标签显示在界面上"（玄览离线）时才真销毁并清空，
 * 下次 [obtain] 重建。**刻意不是"每次 obtain 都新建"** —— 单例保活是本类的设计前提。
 */
object BrowserHolder {

    /**
     * 外部打开入口（§4.6）：终端链接点击等把待加载 URL 写在这里；
     * 玄览界面读到它时由 [BrowserTabManager.openExternal] 消费并清槽（防重复加载）。
     *
     * **P1-8（第五十五批 · §B 77 审计，2026-10-19）：必须是 Compose 可观察状态。**
     * 旧写法是裸 `@Volatile var` —— Volatile 只保证**跨线程可见性**，不保证**重组**：
     * 用户已经停在玄览 Tab 时从终端点一条链接，值确实写进去了，但 BrowserScreen 不会因为
     * 这次写入而重新组合/重跑 effect ⇒ 整条链接石沉大海（要等下次进玄览才被消费）。
     * 现在 [androidx.compose.runtime.State] 承载，写入即失效读侧、驱动重组，消费侧改以
     * 「值」为 key 的 `LaunchedEffect`（判据见 BrowserPendingUrlObservableGateTest S1–S6）。
     *
     * 对外签名不变：写入方（[com.example.zhengdao.TerminalActivity] /
     * [com.example.zhengdao.ui.baopu.BaopuPreview]）仍是 `BrowserHolder.pendingUrl = url`
     * 一行赋值，禁再造第二条「待打开 URL」通道。
     *
     * 线程：写入方都在 UI 线程（点链接、切 Tab 都是 UI 事件）；UI 线程读写 snapshot state
     * 正是它的适用场景，跨线程可见性由快照机制兜底。
     */
    var pendingUrl: String? by mutableStateOf<String?>(null)

    /** 切 Tab 请求（一次性）：终端在独立 Activity 点链接 → 回主页 → MainActivity ON_RESUME 消费。 */
    private val tabRequested = AtomicBoolean(false)

    // ── 262 批：切 Tab 请求的目标 ＋ App 页签上报 ＋ 程序化切屏状态机 ────────────
    //
    // 老语义（requestBrowserTab ⇒ 消费侧硬编码 BROWSER）原样保留：consumeTabRequest()
    // 的 Boolean 形状被 BaopuPreviewTest 钉着，既有调用方（BaopuPreview/终端 B 路径）
    // 一行不改。目标值走旁路 [requestedTabTarget]——消费侧读它，读到的**总是最近一次**
    // 请求的目标（两次请求挤进同一次消费窗口 ⇒ 合并，去哪由最新请求定）。

    /** 最近一次切 Tab 请求的目标（[com.example.zhengdao.TabIndex] 序）。 */
    @Volatile
    private var requestedTab: Int = com.example.zhengdao.TabIndex.BROWSER

    /** 程序化切屏前记录的原页签（[openForShot] 写、[restoreAfterShot] 消费）。 */
    @Volatile
    private var shotOriginTab: Int? = null

    /**
     * 用户/程序此刻所在的 App 页签（[MainActivity] 每次页签变化上报，TabIndex 序）。
     * 「用户手动切走就不抢」的判定面：恢复那一刻读它，不在玄览 ⇒ 用户自己选的，不动。
     */
    @Volatile
    var appTab: Int? = null

    /** 请求主页切到指定 App 页签（262 批程序化切屏用；[requestBrowserTab] 的泛化）。 */
    fun requestTabTo(target: Int) {
        requestedTab = target
        tabRequested.set(true)
        tabRequestVersion++
    }

    /** 最近一次切 Tab 请求的目标（消费侧在 consumeTabRequest()==true 后读）。 */
    fun requestedTabTarget(): Int = requestedTab

    /**
     * 程序化切到玄览（截图征询卡同意后调）：记录原页签、发切屏请求。
     *
     * @return 切之前的原 App 页签；拿不到（未上报/已在玄览）＝ null（截完就不用恢复）
     */
    fun openForShot(): Int? {
        val origin = appTab
        shotOriginTab = origin
        requestTabTo(com.example.zhengdao.TabIndex.BROWSER)
        return origin
    }

    /**
     * 截完切回 [origin]。用户此刻已手动切走（不在玄览）⇒ **不动**并返回 false——
     * 他切去哪儿是他此刻的选择，程序化恢复反而是抢屏（工单口径「用户手动切走就不抢」）。
     */
    fun restoreAfterShot(origin: Int?): Boolean {
        shotOriginTab = null
        val cur = appTab ?: return false
        if (cur != com.example.zhengdao.TabIndex.BROWSER) return false
        if (origin == null || origin == cur) return false
        requestTabTo(origin)
        return true
    }

    // ── 262 批 §6：三级截图链路的宿主半（前台载入台） ──────────────────────────
    //
    // 判定在纯函数 [com.example.zhengdao.baopu.ShotTierRule]（零 Android，JVM 直测）；
    // 引擎只认 ScreenSwitch 接口；这里只出**事实与动作**的真身：App 前台真相的读口、
    // 「页面渲染过没有」的读口、前台载入、摘离屏挂载。
    //
    // ⚠️ 全程**不抢焦点**：1dp 离屏容器是挂在现有 Activity 的 decorView 上的普通
    // View（不是新窗口、不是全屏 Activity、不要焦点）——它对用户不可见，输入分发
    // 照旧。工单原话「1dp 挂窗本来不抢，别改成全屏 Activity」钉的就是这条。

    /** App 前台真相的读口（[BrowserTabManager] 的 onPageFinished 与 wiring 共用；不另设真相源）。 */
    internal fun appInForegroundNow(): Boolean = appForeground

    /** 最后 resumed 的 Activity 的弱引用（1dp 容器的挂点；框架本就强持有它，弱引用只为不拖住已销毁的实例）。 */
    @Volatile
    private var topActivityRef: java.lang.ref.WeakReference<android.app.Activity>? = null

    private fun topActivity(): android.app.Activity? = topActivityRef?.get()

    /**
     * 当前 top activity（离屏取图要往它的 decorView 挂 1dp 容器；见
     * [com.example.zhengdao.browser.offscreen.OffscreenShotter]）。
     *
     * 拿不到（App 退到后台/没有 activity）⇒ null，调用方必须如实报错——
     * 263 实测：后台新载入的页画不出，那种情形本来就该走征询切屏。
     */
    internal fun topActivityOrNull(): android.app.Activity? = topActivity()

    /**
     * 截图目标页（玄览当前页）此刻**能不能画出一张像样的图**。
     *
     * 两条都要：① [BrowserTabState.isRenderable]（载入过 ＋ 载入完成时 App 在前台 ＋ 没被回收）；
     * ② **WebView 真的挂在一个窗口上且有尺寸**。
     *
     * ⚠️ 只有 ① 会骗人（262 真机实测 2026-10-28）：`renderedWhileForeground` 在 `onPageFinished`
     * 时只看「App 在不在前台」，不看这一页挂没挂窗口。玄览页签被切走（没组合）时 WebView 会被
     * 摘下来、尺寸归 0×0，可 ① 仍然说"渲染过" ⇒ 引擎判 PAINT_OFFSCREEN 直接离屏画 ⇒
     * 画出来什么都没有（连白图都不是，`draw` 拿不到尺寸）。加上 ② 之后这种情形会落到
     * LOAD_THEN_PAINT，由 [loadForShot] 挂回窗口再画。
     */
    fun pageRenderableNow(): Boolean {
        val tab = BrowserTabManager.activeTab() ?: return false
        if (!tab.state.isRenderable()) return false
        return onMainValue(false) { tab.webView.isAttachedToWindow && tab.webView.width > 0 }
    }

    /** 主线程 Handler 工厂（Handler 不静态持有——它没跨调用状态，现用现建）。 */
    private fun shotMainHandler(): android.os.Handler =
        android.os.Handler(android.os.Looper.getMainLooper())

    /** 1dp 离屏容器的弱引用（挂载期间 decorView 强持有 ⇒ 弱引用不丢；摘下后即死）。 */
    private var shotContainerRef: java.lang.ref.WeakReference<android.widget.FrameLayout>? = null

    /** 载入等待上限（毫秒）——§6 口径「切前台 2 秒载入」的同族量级。 */
    private const val LOAD_TIMEOUT_MS = 2_500L

    /** 载入轮询间隔（毫秒）。 */
    private const val LOAD_POLL_MS = 150L

    /** readyState 到 complete 之后的沉降（毫秒）——让最后一批渲染落定（263 实测的 settle）。 */
    private const val LOAD_SETTLE_MS = 400L

    /**
     * 前台载入（§6 ②/③ 的补救动作；引擎线程调）。
     *
     * 三步：① 页面没挂在任何窗口 ⇒ 挂 1dp 离屏容器（玄览在屏的页签本来就挂在真实
     * 窗口上，就地载入——用户看得见，那本来就是要给他看的页）；② 发起载入，**复用
     * 既有出口**（恢复标签走 loadRestoredTab、回收标签走 reloadRecycled、普通页
     * loadUrl——各自的标记清理别处已经写好，这里不另立第二套真相）；③ 轮询
     * readyState 到 complete **且** 引擎提交了目标地址（about:blank 的 complete 不
     * 算——那是「还没载入」，不是「载入完了」），就绪后沉降一小段再返回。
     *
     * @return 是否真的载入就绪；超时如实回 false（调用方照样执行截图——拍不出是
     *   工具自己的诚实错误，不假装成功）。
     */
    fun loadForShot(): Boolean {
        val tab = BrowserTabManager.activeTab() ?: return false
        val webView = tab.webView
        // 已渲染过**并且真的挂在窗口上**，才没有「载入」可做（引擎的 tier 判定已过滤，这里兜一次防白干）。
        //
        // ⚠️ 只判 isRenderable 不够（262 真机实测 2026-10-28）：isRenderable 里的
        // renderedWhileForeground 只表示「载入完成那一刻 App 在前台」，**不代表这一页被画到
        // 过屏幕上**。玄览页签没组合时 WebView 从没 layout ⇒ 尺寸 0×0 ⇒ draw 出来什么都没有
        // （连白图都不是）。这种情形本方法必须走挂载＋载入，否则秒回 true，调用方以为能画，
        // 实际永远拿不到图。
        val onWindow = onMainValue(false) { webView.isAttachedToWindow && webView.width > 0 }
        if (tab.state.isRenderable() && onWindow) return true
        // ① 挂载（只在没父容器时挂；有父容器＝组合或我们已挂好）。
        onMainWait {
            runCatching { if (webView.parent == null) attachOffscreenForShot(webView) }
        }
        // ② 发起载入——**只在这页确实需要载入时才发**。
        //
        // （262 真机实测 2026-10-28）页面已经载入过、只是没挂窗口（玄览页签被切走），
        // 挂上窗口就能画，重载一遍纯属白等：百度首页重载 2.5s 都不够，本方法会超时返回
        // false，而调用方照样按"没就绪"去截图 ⇒ 白忙。所以已载入的普通页跳过载入。
        val needLoad = !tab.state.everLoaded || tab.state.restored || tab.state.recycled
        if (needLoad) {
            onMainWait {
                runCatching {
                    when {
                        tab.state.restored -> BrowserTabManager.loadRestoredTab(tab.id)
                        tab.state.recycled -> BrowserTabManager.reloadRecycled(tab.id)
                        else -> webView.loadUrl(tab.state.url)
                    }
                }
            }
        }
        // ③ 轮询就绪（getUrl 文档明确可跨线程调；readyState 要回主线程问）。
        val deadline = System.currentTimeMillis() + LOAD_TIMEOUT_MS
        var ready = false
        while (System.currentTimeMillis() < deadline) {
            val rs = readyStateOf(webView)
            val committed = runCatching { webView.url }.getOrNull()
            if (com.example.zhengdao.baopu.ShotTierRule.isReadyState(rs) &&
                !committed.isNullOrBlank() &&
                committed != "about:blank"
            ) {
                ready = true
                break
            }
            Thread.sleep(LOAD_POLL_MS)
        }
        if (ready) {
            // 263 四步序的「等 visual」：readyState 只是 DOM 完事，合成帧未必出——
            // postVisualStateCallback（263 实测的信号源）到了才算真的能画。
            visualStateReady(webView)
            Thread.sleep(LOAD_SETTLE_MS)
        }
        return ready
    }

    /**
     * 摘下离屏挂载（与 [loadForShot] 配对；引擎的 finally 调——执行抛异常也必须摘）。
     *
     * 只摘**我们自己容器**还持有的 WebView：用户若在载入期间切到玄览，组合已把它
     * 挂进界面（parent ≠ 容器），那一刻再摘等于当面拆页面。
     */
    fun detachForShot() {
        val container = shotContainerRef?.get() ?: return
        shotContainerRef = null
        onMainWait {
            runCatching {
                val child = container.getChildAt(0)
                if (child != null && child.parent === container) container.removeView(child)
                (container.parent as? ViewGroup)?.removeView(container)
            }
        }
    }

    /**
     * 1dp 挂窗：容器 1dp×1dp 挂 top-activity 的 decorView，WebView 给**真实屏幕
     * 尺寸**的布局参数（capturePicture 成像走渲染器内容，视口尺寸必须是真的——
     * WebView 若按 1dp 布局，页面按 1px 宽排版，画出来必是废图）。容器 clipChildren
     * 保持默认（真）：子 view 画超界被裁到 1dp，对用户不可见。
     */
    private fun attachOffscreenForShot(webView: WebView): Boolean {
        val activity = topActivity() ?: return false
        val decor = activity.window?.decorView as? ViewGroup ?: return false
        if (shotContainerRef?.get() != null) return true
        val dm = activity.resources.displayMetrics
        val one = maxOf(1, dm.density.toInt())
        val container = android.widget.FrameLayout(activity)
        container.addView(
            webView,
            android.widget.FrameLayout.LayoutParams(
                maxOf(1, dm.widthPixels),
                maxOf(1, dm.heightPixels),
            ),
        )
        decor.addView(container, android.widget.FrameLayout.LayoutParams(one, one))
        shotContainerRef = java.lang.ref.WeakReference(container)
        return true
    }

    /** 引擎线程 → 主线程执行一段视操作并等它落地（有界 2s；已在主线程则就地执行）。 */
    private fun onMainWait(block: () -> Unit) {        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            block()
            return
        }
        val latch = java.util.concurrent.CountDownLatch(1)
        shotMainHandler().post {
            try {
                block()
            } finally {
                latch.countDown()
            }
        }
        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
    }

    /**
     * 在主线程取一个值（[onMainWait] 的有返回版）：拿不到（超时/异常）返回 [fallback]。
     *
     * 为什么不直接跨线程读 View 的字段：`isAttachedToWindow`／`width` 都是 View 状态，
     * 约定只在 UI 线程读；这里已经要等主线程，顺手带上返回值而已。
     */
    private fun <T> onMainValue(fallback: T, block: () -> T): T {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            return runCatching(block).getOrDefault(fallback)
        }
        var out = fallback
        onMainWait { out = runCatching(block).getOrDefault(fallback) }
        return out
    }

    /** 问一页的 readyState（evaluateJavascript 的回调回主线程 ⇒ post 后由回调数闩）。 */
    private fun readyStateOf(webView: WebView): String? {
        var value: String? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        shotMainHandler().post {
            runCatching {
                webView.evaluateJavascript("document.readyState") { r ->
                    value = r?.trim()?.trim('"')
                    latch.countDown()
                }
            }.onFailure { latch.countDown() }
        }
        latch.await(1, java.util.concurrent.TimeUnit.SECONDS)
        return value
    }

    /** 等视觉帧就绪（263 实测四步序的「等 visual」：postVisualStateCallback；有界 1s）。 */
    private fun visualStateReady(webView: WebView): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1)
        shotMainHandler().post {
            runCatching {
                webView.postVisualStateCallback(
                    0L,
                    object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) {
                            latch.countDown()
                        }
                    },
                )
            }.onFailure { latch.countDown() }
        }
        return latch.await(1, java.util.concurrent.TimeUnit.SECONDS)
    }

    /**
     * 玄览界面此刻是否显示在屏幕上（由 [BrowserScreen] 组合进出维护）。
     * 只读区在 UI 线程，但 TRIM 回调也可能来自别的线程 ⇒ @Volatile。
     */
    @Volatile
    private var browserOnScreen = false

    // ── App 前台真相（第一百七十二批 S4） ──────────────────────────────────
    //
    // 为什么不能只看 MainActivity 的 ON_PAUSE：**跳到终端（独立 Activity）时主页也会 ON_PAUSE**，
    // 那时 App 明明在前台、用户就在眼前。改前 pauseAll 就挂在那里 ⇒ 一进终端，玄览所有标签
    // 当场降成 WAIVED（＝系统想收就收），L2「在终端/抱朴时玄览要活着」直接破了。
    // 所以前台判据取**整个 App**：只要还有一个 Activity 处于 started，就算前台。
    // 计数用 started（不是 resumed）：从主页切到终端时 先 start 后者、再 stop 前者，
    // 计数 1→2→1，中途不会掉到 0，也就不会出现"瞬降WAIVED再升回来"的抖动。

    /** App 是否在前台（有任一 Activity started）。TRIM 回调可能来自别的线程 ⇒ @Volatile。 */
    @Volatile
    private var appForeground = true

    /** started 状态的 Activity 计数。 */
    private val startedActivities = java.util.concurrent.atomic.AtomicInteger(0)

    /** 生命周期是否已挂上（幂等：MainActivity 重建不会重复注册）。 */
    @Volatile
    private var foregroundTracked = false

    /** 应用上下文（L4 退后台快照要写库；与 [attachAppForegroundTracking] 一起拿到）。 */
    private var appContext: android.content.Context? = null

    /**
     * 给整个 App 挂上前台跟踪（[com.example.zhengdao.MainActivity] 的 onCreate 调一次）。
     *
     * 为什么落在 BrowserHolder 而不是 Application 类：本批的边界是"只碰
     * BrowserTabPolicy／BrowserHolder／MainActivity"；Application 只负责注册，
     * 判据与动作都归玄览自己的门面。
     */
    fun attachAppForegroundTracking(app: android.app.Application) {
        this.appContext = app.applicationContext
        if (foregroundTracked) return
        foregroundTracked = true
        app.registerActivityLifecycleCallbacks(
            object : android.app.Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: android.app.Activity) {
                    if (startedActivities.incrementAndGet() == 1) setAppForeground(true)
                }

                override fun onActivityStopped(activity: android.app.Activity) {
                    if (startedActivities.decrementAndGet() == 0) setAppForeground(false)
                }

                // 262 批 §6：记下最后 resumed 的 Activity——1dp 离屏载入容器的挂点
                // （挂它的 decorView，不新建窗口，天然不抢焦点）。弱引用：已销毁的
                // Activity 不许被静态字段拖住（StaticFieldLeak 纪律）。
                override fun onActivityResumed(activity: android.app.Activity) {
                    topActivityRef = java.lang.ref.WeakReference(activity)
                }

                override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
                override fun onActivityPaused(activity: android.app.Activity) {}
                override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
                override fun onActivityDestroyed(activity: android.app.Activity) {}
            },
        )
    }

    /**
     * 前台状态的**唯一入口**：写标记 + 驱动玄览标签的暂停/恢复。
     *
     * 为什么把"暂停/恢复标签"放在这里而不是各 Activity 自己调：只有本类同时掌握
     * 「App 在前台吗」与「玄览在线吗」两件事；散在 Activity 里就会出现改前那种
     * "跳终端也被当成退后台"的错判。**幂等**：状态没变就什么都不做。
     */
    fun setAppForeground(foreground: Boolean) {
        if (appForeground == foreground) return
        appForeground = foreground
        if (foreground) {
            BrowserTabManager.resumeAll()
        } else {
            // 第一百七十二批 L4：退后台是进程被杀前**最后一次**写快照的机会（边用边记的另一半）。
            // 只读不写库的东西在这里落一次盘；失败只记日志（快照是增强，不许拖垮退后台）。
            appContext?.let { BrowserTabManager.snapshotAll(it) }
            BrowserTabManager.pauseAll()
        }
    }

    /** 取（或首建）全局活跃引擎实例（无标签时建第一个）。 */
    fun obtain(context: Context): WebViewEngine = BrowserTabManager.obtain(context)

    /** 当前活跃 WebView 实例（AndroidView factory 用；未初始化时为 null）。 */
    fun view(): WebView? = BrowserTabManager.view()

    /** 请求主页切到玄览 Tab（TerminalActivity 点链接时调；MainActivity ON_RESUME 消费）。 */
    /** 151/160：切 Tab 请求的可观察版本号——主页在同一页里也能立刻消费（原来只在 ON_RESUME）。 */
    var tabRequestVersion by mutableIntStateOf(0)
        private set

    fun requestBrowserTab() {
        // 262：老调用方的目标恒是玄览。requestedTab 可能被上一次程序化切屏（restoreAfterShot）
        // 写成别的页签——不重置的话，消费侧读旁路就把终端点链接的人送错页。
        requestedTab = com.example.zhengdao.TabIndex.BROWSER
        tabRequested.set(true)
        tabRequestVersion++
    }

    /** 消费切 Tab 请求（一次性）；有待处理请求时返回 true。 */
    fun consumeTabRequest(): Boolean = tabRequested.compareAndSet(true, false)

    // ── 受控回收出口（第⑱刀 · D-4） ───────────────────────────────────────

    /**
     * 玄览上屏时的旁路钩子（262 批预约式：巡按的补拍队列在玄览上屏那一刻 drain）。
     * 可空＝没人订阅就是零开销；订阅方（BaopuBrowserWiring）自己负责投后台线程——
     * 钩子在**组合期**（主线程）回调，钩子里直调桥命令会撞 onMain 自等死锁。
     */
    @Volatile
    var browserShownListener: (() -> Unit)? = null

    /** 玄览进入组合（[BrowserScreen] 显示）。 */
    internal fun onBrowserShown() {
        browserOnScreen = true
        runCatching { browserShownListener?.invoke() }
    }

    /** 玄览此刻是否显示在屏幕上（262 批征询式要跨包读它；TRIM 回调可在别的线程 ⇒ 只读不写）。 */
    fun browserOnScreenNow(): Boolean = browserOnScreen

    /** 玄览离开组合（切到别的底栏 Tab）。 */
    internal fun onBrowserHidden() {
        browserOnScreen = false
    }

    /**
     * 是否"没有活动标签需要保活"：**此刻没有标签显示在界面上、却仍离线持有 WebView**。
     *
     * 判据（第一百七十二批 S4 起）全部交给纯函数
     * [com.example.zhengdao.browser.BrowserTabPolicy.canReleaseForMemory]：
     * 玄览不在屏 **且 App 不在前台** 且 还有标签 —— 三条同时成立才可回收。
     * 新增"前台"那一条的意义：L2 承诺"App 在前台时页面必须活着"，
     * 而它此前只靠"前台一般到不了 TRIM_COMPLETE 档"这条经验兜着，不是判据。
     */
    internal fun isIdle(): Boolean = com.example.zhengdao.browser.BrowserTabPolicy.canReleaseForMemory(
        browserOnScreen = browserOnScreen,
        appForeground = appForeground,
        tabCount = BrowserTabManager.count(),
    )

    /**
     * 受控回收（D-4）：把**离线持有**的全部 WebView 销毁并清空标签，下次 [obtain] 重建
     * （cookie / localStorage 落在 WebView 数据目录里，不随实例销毁而丢）。
     *
     * 为什么必须先 [isIdle]：`WebView.destroy()` 对一个**仍挂在窗口上、仍被界面持有**的实例
     * 是未定义行为（与 [BrowserTabManager.closeTab] 里"先 removeView 再 destroy"同一条纪律）。
     * TRIM 回调到达时玄览可能正显示着 —— 那种情况**宁可不回收**，不冒崩的风险。
     *
     * @return 真的回收了（销毁了 ≥1 个 WebView）为 true。
     */
    fun releaseIfIdle(): Boolean {
        if (!isIdle()) return false
        val released = BrowserTabManager.detachAllForRelease()
        released.forEach { destroySafely(it) }
        return released.isNotEmpty()
    }

    /**
     * 内存压力回调（[MainActivity] / [TerminalActivity] 的 onTrimMemory 各调一行）。
     *
     * 只在 [ComponentCallbacks2.TRIM_MEMORY_COMPLETE]（"进程已接近后台 LRU 尾、即将被系统回收"）
     * 才真回收；更轻的档位频繁出现且此时"切走底栏再回来"的页面必须还在（那是本类存在的理由）。
     *
     * @param level 见 [ComponentCallbacks2]（`TRIM_MEMORY_COMPLETE` = 80）。
     */
    fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) releaseIfIdle()
    }

    /**
     * 三步安全销毁：先摘挂载 → 清页面 → destroy。
     * 先 removeView 是因为"挂在窗口上的 WebView 调 destroy 未定义"；三步各自 runCatching，
     * 任何一步失败都不拦住后面的清理（本方法只服务于回收，不吞噬调用方的异常语义）。
     */
    private fun destroySafely(webView: WebView) {
        runCatching { (webView.parent as? ViewGroup)?.removeView(webView) }
        runCatching { webView.loadUrl("about:blank") }
        runCatching { webView.destroy() }
    }
}
