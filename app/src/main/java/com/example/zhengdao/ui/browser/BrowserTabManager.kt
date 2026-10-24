// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

package com.example.zhengdao.ui.browser

import com.example.zhengdao.browser.BrowserSnapshotPolicy
import android.content.Context
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.core.net.toUri
import com.example.zhengdao.R
import com.example.zhengdao.browser.BrowserMemoryProbe
import com.example.zhengdao.browser.BrowserTabPolicy
import com.example.zhengdao.browser.EngineSecurityConfig
import com.example.zhengdao.browser.LocalPreviewPolicy
import com.example.zhengdao.browser.NavigationDelegate
import com.example.zhengdao.browser.PreviewMode
import com.example.zhengdao.browser.WebViewEngine
import com.example.zhengdao.rootfs.RunLog

/**
 * 玄览多标签管理器（第⑨刀 B · 阶段 2 §4.2）——按 tabId 管多个 Engine + 渲染优先级 + 生命周期。
 *
 * 设计依据：《证道-浏览器Tab技术选型与架构草案-2026-10-13》v1.1 §4.1 / §4.2。
 * 替换原 [BrowserHolder] 的单实例角色（其 KDoc 自陈"阶段 2 按 tabId 管多个 Engine，本类随之替换"）；
 * [BrowserHolder] 保留为对外facade（pendingUrl / 切 Tab 请求），tab 语义全部落到本类。
 *
 * **线程模型**：全部公开方法限定 UI 线程调用（Compose 事件 / MainActivity 生命周期 /
 * TerminalActivity 的 UI 线程入口）。WebView 创建与回调本就 UI 线程；标签状态用
 * Compose snapshot state 承载（UI 线程读写），切 Tab 时列表/活跃 id 的变化直接驱动重组。
 *
 * **数据目录隔离的平台现实（第⑨刀 B 查证，② AOSP 原文）**：
 * `WebView.setDataDirectorySuffix` 是**静态、每进程一次性**（须在首个 WebView 创建前调），
 * 设计意图是**多进程间**隔离（"Each directory can be used by only one process… different
 * processes in the same application cannot directly share WebView-related data"，
 * `core/java/android/webkit/WebView.java`）。⇒ **单进程内的多个标签不可能各有数据目录**，
 * cookie / localStorage / cache 在同进程的所有标签间共享（与桌面浏览器普通标签一致）。
 * 进程级 suffix 在 `ZhengdaoApp.onCreate` 统一设（见该处注释）。
 *
 * **每标签真正做到的隔离**：独立 WebView 实例（独立历史栈 / 页面状态 / JS 上下文 /
 * 渲染进程优先级）＋ 后台 onPause + RENDERER_PRIORITY_WAIVED（§4.2 两条）。
 *
 * 第五十九批 §5 在本类加**本地预览页签**那一支（[openPreviewTab] / [setPreviewMode] /
 * [setPreviewJsSandbox]）：同一个 `tabs` 列表、同一套生命周期与回收出口，差别只在
 * 「建的时候装的是断网+禁 JS 那一档配置、内容是注入进来的、地址是假的」。
 * 刻意**不另开一个 PreviewTabManager**：另开就要在 [BrowserHolder.isIdle]、`pauseAll`、
 * 上限 20、回收出口各处都补一句"那边也有一份"——那些语义每一条都是踩过坑钉下来的，
 * 复制一份就是给将来留两份真相。
 */
object BrowserTabManager {

    /** 一个标签页：WebView 实例 + 引擎 + 可观测的界面状态（[BrowserTabState]）。 */
    class Tab(
        val id: Int,
        val webView: WebView,
        val engine: WebViewEngine,
        /**
         * 本标签的界面状态。**参数化的原因只有一条**（第一百七十二批 S2）：渲染进程被回收后
         * 死掉的 WebView 实例必须销毁重建（官方口径：`onRenderProcessGone` 返回 true 之后
         * 不可再用），而"这是第几号标签、页面曾经是什么、滚到哪儿"属于**用户的东西**，
         * 不能跟着实例一起丢 ⇒ 重建时把旧 state 原样接过来，只换壳。
         */
        val state: BrowserTabState = BrowserTabState(),
    )

    /** 按创建顺序排列的标签（SnapshotStateList：增删直接驱动标签条重组）。 */
    private val tabs: SnapshotStateList<Tab> = mutableStateListOf()

    /** 当前活跃标签 id（null = 无标签，玄览显示空态）。 */
    private var activeId by mutableStateOf<Int?>(null)

    /**
     * App 是否在前台（第一百七十二批 S1）——**渲染优先级的唯一判据**。
     *
     * 只由 [pauseAll] / [resumeAll] 写（MainActivity 的 ON_PAUSE / ON_RESUME 是唯一来源），
     * 初值 true 是为了「玄览页刚建、还没走过生命周期」的那一段与前台语义一致。
     * 刻意不做成 Compose 状态：它只影响 `WebView` 的旁路开关，不驱动任何重组。
     */
    private var appForeground = true

    // ── 页签快照（第一百七十二批 L4） ──────────────────────────────────────
    //
    // 一条硬约束决定了这里的形状：**`Tab.webView`/`engine` 是非空的**（桥命令 BrowserCommands
    // 直接解引用它们）。所以"恢复全部标签但只给活跃那颗建 WebView"不能靠"webView 可空"实现，
    // 而是：活跃那颗材料化成真 Tab，其余先放在 [restoredRows] 里（标签条上的「上次」片），
    // 用户点哪颗就材料化哪颗。**代价与收益写清楚**：桥命令永远拿得到非空 WebView（不改 177 的地盘），
    // 内存只按一颗算（这是 L4 的核心收益）。

    /** 上次留下、还没点开的标签（可观察：标签条直接渲染成片）。 */
    private val restoredRows: SnapshotStateList<BrowserSnapshotPolicy.Row> = mutableStateListOf()

    /** 待恢复滚动位置（tabId → (x,y) CSS px）：只有点了「打开」并加载完才用得上。 */
    private val pendingScroll = HashMap<Int, Pair<Float, Float>>()

    /** 每颗标签首次 onPageFinished 的时刻（用于 §5 的 +800ms 补 clamp）。 */
    private val finishedAt = HashMap<Int, Long>()

    /** 已经补过一次 clamp 的标签（最多补一次）。 */
    private val secondClampDone = HashSet<Int>()

    /** 上次渲染出来的恢复行（标签条用）。 */
    fun restored(): List<BrowserSnapshotPolicy.Row> = restoredRows.toList()

    /** 还有没有没点开的恢复行（界面据此决定是否提示）。 */
    fun hasRestored(): Boolean = restoredRows.isNotEmpty()

    /**
     * 用库里的行恢复标签（冷启首次进玄览时调，已由调用方在 IO 线程读完）。
     *
     * 做三件事，顺序不能换：
     * ① 顺序按 `id ASC`（[BrowserSnapshotPolicy.restoreOrder]）；
     * ② **修 id 游标** `nextId = max(恢复 id, nextId-1) + 1`（否则新建标签撞 id，
     *    表现为"点新标签切到旧页"或委托把事件写进别人的 state）；
     * ③ **只材料化 `last_used_at` 最大的那颗**（用户上次停在哪，就给他看哪），
     *    其余进 [restoredRows] 等用户点。
     *
     * 恢复出来的标签 `state.restored = true` ⇒ 画「上次的页面 · 点一下继续」卡片，**不 loadUrl**。
     */
    fun restoreFromSnapshot(context: Context, rows: List<BrowserSnapshotPolicy.Row>) {
        if (rows.isEmpty() || tabs.isNotEmpty()) return
        val ordered = BrowserSnapshotPolicy.restoreOrder(rows)
        nextId = BrowserSnapshotPolicy.nextIdAfterRestore(ordered.map { it.id }, nextId)
        val activeRow = BrowserSnapshotPolicy.activeRestoredId(ordered)
        ordered.forEach { row ->
            if (row.id == activeRow) {
                val id = materializeRestored(context, row)
                // 必须真的选中它：不 select 的话 activeId 仍是空，界面会画起始页而不是恢复卡片
                selectTab(id)
            } else {
                restoredRows.add(row)
            }
        }
        RunLog.log(
            "玄览 从快照恢复 ${ordered.size} 个标签（活跃 #$activeRow 已建壳，其余 ${restoredRows.size} 个待点开）",
        )
    }

    /**
     * 用户点了标签条上的「上次」片：把它材料化成真标签并选中。
     *
     * @return 新标签 id；null = 已到上限（与 [newTab] 同一条判据）
     */
    fun openRestored(context: Context, rowId: Int): Int? {
        val row = restoredRows.firstOrNull { it.id == rowId } ?: return null
        if (!BrowserTabPolicy.canOpenMore(tabs.size)) return null
        restoredRows.remove(row)
        val id = materializeRestored(context, row)
        selectTab(id)
        return id
    }

    /**
     * 用户点了恢复卡片上的「打开」：**这才是第一次联网**。
     *
     * 清掉 `restored`、开加载；滚动位置在 `onPageFinished` 之后由委托补回（见 [applyRestoredScroll]）。
     */
    fun loadRestoredTab(tabId: Int): Boolean {
        val tab = tabs.firstOrNull { it.id == tabId } ?: return false
        if (!tab.state.restored) return false
        tab.state.restored = false
        if (tab.state.url.isNotEmpty()) {
            tab.engine.loadUrl(tab.state.url)
            RunLog.log("玄览 标签#$tabId：用户点了打开（恢复快照，开始加载 ${tab.state.url}）")
            return true
        }
        return false
    }

    /** 材料化一颗恢复行（建 WebView ＋ 填 state，**不加载**）。 */
    private fun materializeRestored(context: Context, row: BrowserSnapshotPolicy.Row): Int {
        val app = context.applicationContext
        val id = row.id
        val wv = WebView(app)
        // MATCH_PARENT：与 newTab 同一味药（100vh=0 那个自锁，2026-10-18 判决）。
        wv.layoutParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
        )
        val eng = WebViewEngine(wv)
        val tab = Tab(id, wv, eng)
        tab.state.url = row.url
        tab.state.title = row.title
        tab.state.everLoaded = false
        tab.state.restored = true
        eng.applySecurityConfig(EngineSecurityConfig())
        eng.setNavigationDelegate(delegateFor(tab, app))
        tabs.add(tab)
        pendingScroll[id] = row.scrollX to row.scrollY
        return id
    }

    /**
     * 把库里的滚动定位回去（第一百七十二批 L4 §5）。
     *
     * 两道：`onPageFinished` 立刻滚一次；**+800ms 再滚一次**（SPA/懒加载会把内容高度改掉）。
     * 三条护栏都在 JS 里：clamp、**用户已手动滚过就不补**（不抢人的手）、不可横滚或 x=0 不横滚。
     */
    private fun applyRestoredScroll(tab: Tab) {
        val target = pendingScroll[tab.id] ?: return
        val engine = tab.engine
        fun script(withSecondFlag: Boolean): String = """
            (function(){
              var y=${target.second}, x=${target.first};
              var de=document.documentElement;
              var maxY=Math.max(0,de.scrollHeight-window.innerHeight);
              var maxX=Math.max(0,de.scrollWidth-window.innerWidth);
              var tx=(x>0&&maxX>0)?Math.min(x,maxX):0;
              var wantY=Math.max(0,Math.min(y,maxY));
              // 「恢复自己的滚动不算用户滚」：scrollTo 触发的 scroll 事件是异步的，
              // 同步标志位清零后事件才到 ⇒ 把自己误记成用户滚（19:19 真机实证）。
              // 改用 300ms 时间窗：窗口内的事件一律视为恢复动作。
              window.__zdRestoreUntil=Date.now()+300;
              window.__zdUserScrolled=0;
              if(!window.__zdRestoreGuard){
                window.__zdRestoreGuard=1;
                window.addEventListener('scroll',function(){
                  if(Date.now()>(window.__zdRestoreUntil||0)){ window.__zdUserScrolled=1; }
                },{passive:true});
              }
              window.scrollTo(tx,wantY);
              return ${if (withSecondFlag) 1 else 0};
            })()
        """.trimIndent()

        runCatching { engine.evaluateJavascript(script(false)) {} }
        val tabId = tab.id
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val stillOpen = tabs.any { it.id == tabId }
            if (!stillOpen) return@postDelayed
            // 用户已经自己滚过了 ⇒ 不补（JS 侧判，避免两次跨进程往返）
            runCatching {
                engine.evaluateJavascript(
                    "(function(){return (window.__zdUserScrolled===1)?0:1;})()",
                ) { allow ->
                    if (allow?.trim()?.trim('"') == "1") {
                        runCatching { engine.evaluateJavascript(script(true)) {} }
                        secondClampDone.add(tabId)
                        pendingScroll.remove(tabId)
                        RunLog.log("玄览 标签#$tabId：SPA 补 clamp 已执行（+800ms）")
                    } else {
                        pendingScroll.remove(tabId)
                        RunLog.log("玄览 标签#$tabId：用户已手动滚动 ⇒ 跳过补 clamp")
                    }
                }
            }
        }, 800L)
    }

    /** 退后台前把全部标签落一次库（L4 最要紧的一次快照；由 [BrowserHolder.setAppForeground] 调）。 */
    fun snapshotAll(context: Context) {
        val now = System.currentTimeMillis()
        tabs.forEach { tab ->
            if (tab.state.url.isEmpty()) return@forEach
            BrowserTabSnapshotStore.upsert(
                context = context,
                tabId = tab.id,
                url = tab.state.url,
                title = tab.state.title,
                scrollX = 0f,
                scrollY = pendingScroll[tab.id]?.second ?: lastScrollY[tab.id] ?: 0f,
                now = now,
                touch = tab.id == activeId,
            )
        }
    }

    /** 首次拿到 context 时记住（快照的 DB 写入要用；selectTab/closeTab 签名里没有 Context）。 */
    private var appCtx: android.app.Application? = null

    /** 最近一次从页面读到的滚动值（CSS px；滚动节流回调里写）。 */
    private val lastScrollY = HashMap<Int, Float>()

    /**
     * 读一次当前页面的滚动位置并落库（滚动节流调用点；IO 在仓库侧）。
     * 读不到（页面没绑定/已销毁）就什么都不做——快照是尽力而为。
     */
    fun captureScroll(context: Context, tabId: Int) {
        val tab = tabs.firstOrNull { it.id == tabId } ?: return
        runCatching {
            tab.engine.evaluateJavascript(
                "(function(){return JSON.stringify([window.scrollX,window.scrollY]);})()",
            ) { raw ->
                val nums = Regex("-?\\d+(\\.\\d+)?").findAll(raw.orEmpty()).map { it.value.toFloat() }.toList()
                if (nums.size >= 2 && nums[1].isFinite()) {
                    lastScrollY[tabId] = nums[1]
                    BrowserTabSnapshotStore.updateScroll(
                        context = context,
                        tabId = tabId,
                        scrollX = nums[0],
                        scrollY = nums[1],
                        now = System.currentTimeMillis(),
                    )
                }
            }
        }
    }

    private var nextId = 1

    /** 全部标签（创建序）。返回快照，调用方不应改。 */
    fun tabs(): List<Tab> = tabs.toList()

    /** 当前活跃标签（null = 空态）。 */
    fun activeTab(): Tab? = activeId?.let { id -> tabs.firstOrNull { it.id == id } }

    /**
     * 按 id 取标签；不存在返回 null（㊽-C ＝ §B 78，2026-10-16）。
     *
     * 供桥命令**按 tabId 精确路由**用：2026-10-16 前 `status/snapshot/execJS/click/type/scroll/navigate`
     * 一律操作活跃标签，body 里的 `tabId` 被静默忽略（真机实证 N4：`tabId=1` 的 click 点在了 tab2）。
     * 与 [activeTab] 的区别只有一处：本函数不碰活跃态，纯查询。
     */
    fun tabById(id: Int): Tab? = tabs.firstOrNull { it.id == id }

    /** 当前活跃引擎（null = 空态）。 */
    fun activeEngine(): WebViewEngine? = activeTab()?.engine

    /** 当前活跃 WebView（AndroidView factory 用；null = 空态）。 */
    fun view(): WebView? = activeTab()?.webView

    /** 标签数。 */
    fun count(): Int = tabs.size

    /**
     * 取活跃引擎；无任何标签时先建一个（首入玄览 / 外部打开各路径都走这里）。
     * 返回的引擎保证非空（首建失败除外——WebView 构造不会失败，省略该分支）。
     */
    fun obtain(context: Context): WebViewEngine {
        appCtx = context.applicationContext as? android.app.Application
        activeEngine()?.let { return it }
        newTab(context)
        return activeEngine()!!
    }

    /**
     * 开新标签并激活。到上限（[BrowserTabPolicy.MAX_TABS]）返回 null（调用方给提示）。
     * @param url 非空则建后即加载（外部打开入口 / 终端链接）。
     */
    fun newTab(context: Context, url: String? = null): Int? {
        if (!BrowserTabPolicy.canOpenMore(tabs.size)) return null
        val app = context.applicationContext
        val id = nextId++
        // 空 WebView 内存基线（阶段 0 三条之一）：每个标签首建前后各采一次，差值进 RunLog
        BrowserMemoryProbe.log("before-webview#tab$id")
        // §B 61（2026-10-15）：WebView 构造是本类唯一已知会间歇卡死（≥100s）的点，
        // 且卡点在 Chromium 内部（App 侧修不了）⇒ 只能加细观测：构造前后各落一行
        // 带线程名与耗时的日志，把「构造真卡」与「探针/日志自身卡」区分开。
        RunLog.log("玄览 newTab#tab$id：主线程开始构造 WebView（thread=${Thread.currentThread().name}）")
        val ctorBeginNanos = System.nanoTime()
        val wv = WebView(app)
        // 玄览「100vh = 0」修法（2026-10-18 · 真机同包对照判决）：给 WebView 自带一份
        // MATCH_PARENT 的 LayoutParams。
        //
        // 机理：本类建好的 WebView 由 `BrowserScreen` 的 `AndroidView(factory = { … })` 挂上窗口，
        // 宿主是 Compose 的 `ViewFactoryHolder`（`FrameLayout` 子类）。它 `addView(child)` 时不传
        // params ⇒ 走 `generateDefaultLayoutParams()` = **WRAP_CONTENT** ⇒ WebView.onMeasure 收到
        // 的 heightMeasureSpec 模式是 AT_MOST/UNSPECIFIED 而**不是 EXACTLY** ⇒ Chromium 改走
        // 「按网页内容高度给自己定尺寸」的分支，而内容高度正是由这个已经塌成 0 的视口算出来的
        // ⇒ 自锁：`100vh/100dvh/100svh`、`min-height` 媒体查询恒为 0（宽度通道不受影响）。
        // 用 `height:100vh + overflow:hidden` 把首屏裁死的站点（必应首页）正文整层消失、搜索框
        // 跑到屏外；正文本身高于一屏的站点（长文页）会被自己的内容高度拽出来 ⇒ 表现为「看站点的时好时坏」。
        //
        // 为什么这一行就够：`FrameLayout.checkLayoutParams` 认子 View **自己带的** params ⇒
        // WebView 拿到 EXACTLY 的测量模式，视口通道恢复；`useWideViewPort`／`loadWithOverviewMode`
        // 两个开关单独开、一起开都**不改变** vh=0（同包各测一版已排除），它们只管桌面版式（980px 窄屏
        // 缩放），与本病无因果。
        //
        // 同实例读数（lp 字段与 vh100 取自同一个 WebView 对象）：`lp=-2x-2`（WRAP_CONTENT）⇒ vh100=0；
        // `lp=-1x-1`（MATCH_PARENT）⇒ vh100 = innerHeight。
        wv.layoutParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
        )
        val ctorMs = (System.nanoTime() - ctorBeginNanos) / 1_000_000
        RunLog.log(
            "玄览 newTab#tab$id：WebView 构造返回（${ctorMs}ms${if (ctorMs > 2000) "，异常缓慢" else ""}）",
        )
        BrowserMemoryProbe.log("after-webview#tab$id")
        val eng = WebViewEngine(wv)
        val tab = Tab(id, wv, eng)
        // 安全配置 + 每标签委托在建时即装好（后台标签的加载态照常演进，不依赖 UI 组合）
        eng.applySecurityConfig(EngineSecurityConfig())
        eng.setNavigationDelegate(delegateFor(tab, app))
        tabs.add(tab)
        selectTab(id)
        if (!url.isNullOrEmpty()) eng.loadUrl(url)
        return id
    }

    /** 关闭标签。关活跃标签时按 [BrowserTabPolicy.nextActiveAfterClose] 换活跃；最后一个关掉即空态。 */
    fun closeTab(id: Int) {
        val tab = tabs.firstOrNull { it.id == id } ?: return
        val wasActive = activeId == id
        appCtx?.let { BrowserTabSnapshotStore.remove(it, id) }
        pendingScroll.remove(id)
        lastScrollY.remove(id)
        finishedAt.remove(id)
        secondClampDone.remove(id)
        val nextId2 = BrowserTabPolicy.nextActiveAfterClose(tabs.map { it.id }, id, activeId)
        tabs.remove(tab)
        if (wasActive) {
            activeId = nextId2
            // 先摘挂载再销毁（WebView 挂在窗口上时 destroy 是未定义行为）；
            // 活跃标签此刻还挂在 AndroidView 上 ⇒ removeView 后 Compose 那次 dispose 的
            // removeView 自然成 no-op。
            destroySafely(tab.webView)
            // 新活跃标签恢复优先级/生命周期
            activeTab()?.let { resumeAndPromote(it) }
        } else {
            // 后台标签从没挂进窗口，直接销毁
            destroySafely(tab.webView)
        }
    }

    // ── 渲染进程被系统回收（第一百七十二批 S2） ─────────────────────────────

    /**
     * 某一颗标签的渲染进程被系统收走了：**只废这一颗**，其余标签原样，`activeId` 不动。
     *
     * 为什么必须连实例一起换掉（而不是"标个状态等用户点重载"）：
     * `onRenderProcessGone` 返回 true 之后，官方口径是这个 WebView **不可再用**
     * （"it is not safe to continue using it"）⇒ 留着它，下一次点击/滚动才炸，成了定时炸弹。
     * 所以这里做三件事，顺序不能换：
     * ① 旧 state 打上 [BrowserTabState.recycled]（用户会看到「点一下重新加载」卡片）；
     * ② 用**同一个 id、同一份 state** 换一颗新 WebView 进标签表（标签条位置、标题、地址都不动）；
     * ③ 销毁旧实例（摘挂载 → about:blank → destroy，与 [closeTab] 同一条纪律）。
     *
     * 刻意**不自动加载**：新实例是空白的，用户点一下才联网（用户 2026-10-24 口径：
     * 不偷偷联网、恢复快照不假装活着）。
     */
    fun markRendererGone(context: Context, tabId: Int) {
        val index = tabs.indexOfFirst { it.id == tabId }
        if (index < 0) return
        val dead = tabs[index]
        dead.state.onRendererGone()
        val app = context.applicationContext
        tabs[index] = reviveTab(app, dead)
        destroySafely(dead.webView)
    }

    /**
     * 用户点了「重新加载」：清掉回收标记并把页面重新拉起来。
     *
     * 三条分支都是"按这标签本来的身份恢复"：
     * - 预览页签 ⇒ 重新注入那份文本（它的"地址"是假的，不能 loadUrl）；
     * - 上网页签且有地址 ⇒ loadUrl 回去；
     * - 有标签但从没加载成功过（地址为空）⇒ 什么都不做，留在空页等用户输入。
     *
     * @return 真的发起了恢复为 true；没有这个标签 / 没处于回收态为 false（调用方不必提示）。
     */
    fun reloadRecycled(tabId: Int): Boolean {
        val tab = tabs.firstOrNull { it.id == tabId } ?: return false
        if (!tab.state.recycled) return false
        tab.state.recycled = false
        if (tab.state.isPreview()) {
            tab.engine.loadLocalDocument(tab.state.previewDocument())
        } else if (tab.state.url.isNotEmpty()) {
            tab.engine.loadUrl(tab.state.url)
        }
        RunLog.log("玄览 标签#$tabId：用户点了重新加载（回收后恢复）")
        return true
    }

    /**
     * 造一颗与 [dead] 同身份的新标签：同 id、同一份 [BrowserTabState]，只换 WebView/引擎。
     *
     * 与 [newTab] / [openPreviewTab] 一样把 `WebView(app)` 与 `LayoutParams` 写死在这里，
     * 而不是抽成一个公共构造函数 —— 理由与 [openPreviewTab] 里那段注释同源：
     * `BrowserRecycleGateTest` 钉「WebView 实例在具名构建点里创建、不在 obtain() 里」，
     * 抽成 helper 会让那条门禁的字面失效，等于用"优雅"换掉一条护栏。
     */
    private fun reviveTab(app: Context, dead: Tab): Tab {
        BrowserMemoryProbe.log("before-webview#revive${dead.id}")
        val wv = WebView(app)
        // MATCH_PARENT：与 newTab 同一味药（100vh=0 那个自锁，2026-10-18 判决）。
        wv.layoutParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
        )
        BrowserMemoryProbe.log("after-webview#revive${dead.id}")
        val eng = WebViewEngine(wv)
        val tab = Tab(dead.id, wv, eng, dead.state)
        // 预览页签按预览档重建（断网 + 默认禁 JS），上网页签按默认档 —— 身份不能因为被回收就降级。
        val config = if (dead.state.isPreview()) {
            LocalPreviewPolicy.configFor(dead.state.previewJsSandbox)
        } else {
            EngineSecurityConfig()
        }
        eng.applySecurityConfig(config)
        eng.setNavigationDelegate(delegateFor(tab, app))
        // 新壳跟前台现状对齐：后台被回收的标签回前台前不该占 IMPORTANT。
        applyRendererPriority(tab)
        return tab
    }

    /**
     * 激活指定标签。
     *
     * **第一百七十二批 S1：切走的那一颗不再降级。** 改前这里是
     * `activeTab()?.let { pauseAndWaive(it) }` —— 把被切走的标签挂到 `WAIVED`
     * （"系统想收就收"那一档）。那与 L1「切回不重载」直接互斥，本批删掉；
     * 新激活的一颗按 [BrowserTabPolicy.rendererPriority] 取档（前台 = IMPORTANT）。
     */
    fun selectTab(id: Int) {
        if (activeId == id) return
        val tab = tabs.firstOrNull { it.id == id } ?: return
        activeId = id
        resumeAndPromote(tab)
        // L4：切到谁就刷新谁的 LRU 时间戳（恢复时「上次用哪个」靠它）
        appCtx?.let { BrowserTabSnapshotStore.touch(it, tab.id, System.currentTimeMillis()) }
    }

    /**
     * 整个 App 退后台（MainActivity ON_PAUSE / 盖上了终端活动）：全部标签 onPause + 全部降 WAIVED。
     * 这是 S1 之后**唯一**允许降级的入口（前台不再降）。
     */
    fun pauseAll() {
        appForeground = false
        tabs.forEach { tab ->
            runCatching { tab.webView.onPause() }
            applyRendererPriority(tab)
        }
    }

    /**
     * 回前台（MainActivity ON_RESUME）：**全部**标签 onResume + 按前台档升回来（S1）。
     *
     * 改前叫 `resumeActive()`，只救回活跃那一颗 ⇒ 其余标签被 [pauseAll] 降成 WAIVED 之后
     * **永不回升**（2026-10-24 核对时实证：`pauseAll` 降全部、`resumeActive` 升一颗）。
     * 玄览的 L1/L2 都要求"每一颗都活着"，所以这里必须全量。
     */
    fun resumeAll() {
        appForeground = true
        tabs.forEach { resumeAndPromote(it) }
    }

    /**
     * 外部打开入口（终端链接点击等，§4.6）：**开新标签**加载（[BrowserTabPolicy.externalOpenShouldCreateTab]；
     * 满 20 时回退当前活跃标签）。由 BrowserScreen 组合时消费 BrowserHolder.pendingUrl 后调。
     *
     * 第一百四十九批 组3：入口先拒**预览假地址**（`local-preview://…`，判据走
     * [LocalPreviewPolicy.isPreviewUrl] 这一条，不在这里写 scheme 字面量）。为什么必须拦在这里：
     * 预览页签的三条硬语义（断网、默认禁脚本、内容现读注入）住在 [openPreviewTab] 那一支，
     * 而本入口建的是**上网页签**——一条假地址只要从这里进去，就会以"能导航的网址"的身份
     * 拿到默认档配置、显示在地址胶囊里（用户会以为那是个真页面），而引擎层只是"投不进去"，
     * 不报错也不给提示 = 静默。地址栏那条链早就有 `BrowserUrlPolicy.shouldReject` 拦同样的输入
     * （第51刀），本入口此前是这条判据的缺口。
     */
    fun openExternal(context: Context, url: String) {
        if (url.isEmpty()) return
        if (LocalPreviewPolicy.isPreviewUrl(url)) {
            RunLog.log("玄览 外部打开：拒收预览假地址 $url（预览只走 openPreviewTab 那条注入通道）")
            return
        }
        if (BrowserTabPolicy.externalOpenShouldCreateTab(tabs.size)) {
            newTab(context, url)
        } else {
            activeTab()?.engine?.loadUrl(url)
        }
    }

    // ── 本地预览页签（第五十九批 §5） ──────────────────────────────────────
    //
    // 三条硬语义，逐条对着派工单 §5.2 的验收判据：
    // - **与上网页签互不干涉**：预览页签是**独立的 WebView 实例**、独立的一套
    //   [EngineSecurityConfig]（[LocalPreviewPolicy.configFor]），`newTab` 那条零参构造
    //   `EngineSecurityConfig()` 一个字没改 ⇒ 正常标签的 JS/网络配置零改动（§5 验收第一条）；
    // - **只显示**：内容经 [WebViewEngine.loadLocalDocument] 注入（不走 file://，红线见该类 KDoc），
    //   断网与禁 JS 由配置 + [LocalPreviewPolicy] 在引擎层硬拦，不靠"用户别点链接"；
    // - **不留旧缓存**：[openPreviewTab] 每次都按**调用方刚读出来的**文本重注入
    //   （文本由 [com.example.zhengdao.baopu.agent.PreviewTool] 每次现读盘，见其 preview()）；
    //   同一文件再预览**复用那个页签**（不叠一层页签堆），换个文件就开新页签。

    /**
     * 开或复用一个本地预览页签。
     *
     * @param relPath 相对工作区根的文件路径（`/` 分隔）——**权限判定不在这里**：
     *   越界由工具层 [com.example.zhengdao.baopu.agent.PreviewTool] 用现成的
     *   [com.example.zhengdao.baopu.BaopuGuardCore] 拒掉，能走到这里的内容已经在工作区内。
     *   本管理器不再判一次路径，是为了避免"同一件事两处各有一套判据"（§4 打回第二条的形状）。
     * @return 页签 id；null = 标签数已达上限（调用方如实给提示，不静默丢）。
     */
    fun openPreviewTab(context: Context, relPath: String, text: String): Int? {
        val same = tabs.firstOrNull { it.state.previewPath == relPath }
        if (same != null) {
            same.state.openPreview(relPath, text)
            same.engine.loadLocalDocument(same.state.previewDocument())
            selectTab(same.id)
            return same.id
        }
        if (!BrowserTabPolicy.canOpenMore(tabs.size)) return null
        val app = context.applicationContext
        val id = nextId++
        BrowserMemoryProbe.log("before-webview#preview$id")
        // 构造耗时观测（§B 61）与 newTab 同规：WebView 构造是本仓唯一已知会间歇卡死的点。
        RunLog.log("玄览 预览#$id：主线程开始构造 WebView（thread=${Thread.currentThread().name}）")
        val ctorBeginNanos = System.nanoTime()
        val wv = WebView(app)
        // MATCH_PARENT 的 LayoutParams：与 newTab 同一味药（100vh=0 那个自锁，2026-10-18 判决）。
        // 两处各自写死而不抽公共函数，是因为 BrowserRecycleGateTest 钉"WebView 实例必须在
        // newTab 里创建"——把构造搬进 helper 会让那条门禁字面失效。改这里时记得同时改那里。
        wv.layoutParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
        )
        val ctorMs = (System.nanoTime() - ctorBeginNanos) / 1_000_000
        RunLog.log("玄览 预览#$id：WebView 构造返回（${ctorMs}ms${if (ctorMs > 2000) "，异常缓慢" else ""}）")
        BrowserMemoryProbe.log("after-webview#preview$id")
        val eng = WebViewEngine(wv)
        val tab = Tab(id, wv, eng)
        // 顺序是硬要求：**先装档再注入**。JS 开关在 loadDataWithBaseURL 之后才设，
        // 页面里的 `<script>` 就已经跑过了（§5.2「默认禁 JS」会变成一句空话）。
        eng.applySecurityConfig(LocalPreviewPolicy.configFor(jsSandbox = false))
        eng.setNavigationDelegate(delegateFor(tab, app))
        tabs.add(tab)
        selectTab(id)
        tab.state.openPreview(relPath, text)
        eng.loadLocalDocument(tab.state.previewDocument())
        return id
    }

    /**
     * 换显示模式（§5.3「源码查看／渲染预览切换」）。
     * 非预览页签调到这里 = 什么都不做（判据在 [BrowserTabState.isPreview]，不新增状态源）。
     */
    fun setPreviewMode(tabId: Int, mode: PreviewMode) {
        val tab = tabs.firstOrNull { it.id == tabId } ?: return
        if (!tab.state.isPreview() || tab.state.previewMode == mode) return
        tab.state.previewMode = mode
        tab.engine.loadLocalDocument(tab.state.previewDocument())
    }

    /**
     * 开/关本页签的脚本沙箱（§5.3 的可选开关，默认关）。
     *
     * 改的是**本页签**的配置：[LocalPreviewPolicy.configFor] 里 `blockAllNetwork` 恒为 true
     * ⇒「脚本会跑、出不了网」是这一档的全部含义，别读成"开了沙箱就能联网"。
     * 重注入而不是"就地生效"：已经跑起来的脚本关不掉（WebView 没有这个能力），
     * 所以关掉时重新注入一份干净的——这比留着脚本再骗用户"已经关了"诚实。
     */
    fun setPreviewJsSandbox(tabId: Int, enabled: Boolean) {
        val tab = tabs.firstOrNull { it.id == tabId } ?: return
        if (!tab.state.isPreview() || tab.state.previewJsSandbox == enabled) return
        tab.state.previewJsSandbox = enabled
        tab.engine.applySecurityConfig(LocalPreviewPolicy.configFor(enabled))
        tab.engine.loadLocalDocument(tab.state.previewDocument())
    }

    /** 预览页签此刻是否允许脚本（UI 那一侧的开关初值；非预览页签回 false）。 */
    fun previewJsSandboxOf(tabId: Int): Boolean =
        tabs.firstOrNull { it.id == tabId }?.state?.previewJsSandbox == true

    /**
     * 回收用（第⑱刀 · D-4）：把**全部**标签从管理器摘下并返回它们的 WebView，交给
     * [BrowserHolder.releaseIfIdle] 统一销毁。
     *
     * **只摘不销毁**：销毁必须由持有回收出口的 [BrowserHolder] 单独做（单一 destroy 出口，
     * 门禁可钉）；本方法只把管理器的"活引用"清干净，避免留下已销毁实例的引用。
     * 调用方须先确认 [BrowserHolder.isIdle]（没有标签显示在界面上）。
     */
    internal fun detachAllForRelease(): List<WebView> {
        val detached = tabs.map { it.webView }
        tabs.clear()
        activeId = null
        return detached
    }

    /** 仅单测用：清空全部标签态（object 单例会跨用例串味）。WebView 销毁不在此路径（单测无 WebView）。 */
    internal fun resetForTest() {
        tabs.clear()
        activeId = null
        nextId = 1
    }

    // ── 内部 ──────────────────────────────────────────────────────────────

    private fun resumeAndPromote(tab: Tab) {
        runCatching { tab.webView.onResume() }
        applyRendererPriority(tab)
    }

    /**
     * 按「App 可见性 ＋ 前台预算」给这一颗标签上档（S1 的唯一落点 ＋ 172 CP2 S3 的接线点）。
     * 判据全在 [BrowserTabPolicy] 的两个纯函数里（JVM 单测钉 6 格表 ＋ 预算表），
     * 这里只负责把结果写进 WebView —— 一份判据、一条装配线。
     *
     * S3（第一百七十二批 CP2）：前台最多 [BrowserTabPolicy.IMPORTANT_BUDGET] 颗标签挂在
     * IMPORTANT 档。**活跃的那颗永远在预算内**（[BrowserTabPolicy.importantTabIds] 的第一条规则），
     * 所以「当前页被降档 ⇒ 当场白页」这种事故不可能发生；预算外的标签允许被系统收走，
     * 由 CP1 的回收卡片兜住（不崩、不白屏）。
     */
    private fun applyRendererPriority(tab: Tab) {
        val budget = BrowserTabPolicy.importantTabIds(tabs.map { it.id }, activeId)
        val priority = BrowserTabPolicy.rendererPriority(
            appForeground = appForeground,
            isActiveTab = tab.id == activeId,
            withinBudget = tab.id in budget,
        )
        runCatching { tab.webView.setRendererPriorityPolicy(priority, false) }
    }

    private fun destroySafely(webView: WebView) {
        runCatching { (webView.parent as? ViewGroup)?.removeView(webView) }
        runCatching { webView.loadUrl("about:blank") }
        runCatching { webView.destroy() }
    }

    /**
     * 每标签导航委托：把页面事件写进**该标签**的状态（后台标签照常演进）。
     * scheme 拦截与消息文案与原单标签版逐字一致（[BrowserHolder] 时代的 BrowserScreen 逻辑）。
     *
     * 第五十九批 §5：预览页签走同一份委托、多三个分支（下面 `isPreview()` 那三处），
     * 上网页签那几行**一字未动**。为什么不让预览页签用另一份委托：委托管的是
     * 「页面事件 → 本标签状态」的落点，两档页签在这一点上是同一件事；
     * 另开一份就会有两处记 url/title 的地方，将来改一处漏一处。
     */
    private fun delegateFor(tab: Tab, app: Context): NavigationDelegate = object : NavigationDelegate {
        override fun shouldOverrideUrlLoading(url: String): Boolean {
            if (tab.state.isPreview()) {
                // 预览页签不跳转（§5.2「只显示」）。引擎层的 allowsNavigation 已经把 http/https
                // 拒在投递之前，这里到得了的多半是同一文档内的事件或非网络 scheme——
                // 一律拦下并**给一句人话**（点了链接没反应是最容易被当成 bug 的静默）。
                tab.state.onBlocked(app.getString(R.string.browser_preview_no_nav))
                return true
            }
            val scheme = runCatching { url.toUri().scheme?.lowercase() }.getOrNull()
            if (scheme !in setOf("http", "https")) {
                tab.state.onBlocked(app.getString(R.string.browser_blocked_scheme))
                return true
            }
            return false
        }

        override fun onPageStarted(url: String) {
            // 委托**只转发**：预览页签要把显示地址换回那条假地址（`local-preview://…`），
            // 这件事归 [BrowserTabState] 自己判（它才知道自己是预览页签）。
            // 不在这里写 if —— `BrowserTabStateTest` 那条源码门禁钉的就是"逐字写进本标签 state"，
            // 委托里加条件等于把门禁的针脚从语义上拆开了（第五十九批合流时踩到，改回原样）。
            tab.state.onPageStarted(url)
            // 第一百七十二批：留一行「真的开始加载了」的判读证据。真机验收 L1「切回不重载」
            // 靠数这行 —— 切走再回来若多出一行，就是页面被重新请求了（不是我们想看到的结果）。
            RunLog.log("玄览 标签#${tab.id} 开始加载 $url")
            // L4 快照：开始加载就记一条（url 由 store 侧过脱敏；滚动沿用最近一次读到的值）
            appCtx?.let { c ->
                BrowserTabSnapshotStore.upsert(
                    context = c, tabId = tab.id, url = url, title = tab.state.title,
                    scrollX = 0f, scrollY = lastScrollY[tab.id] ?: 0f,
                    now = System.currentTimeMillis(), touch = true,
                )
            }
        }

        override fun onPageFinished(url: String) {
            tab.state.onPageFinished(url)
            finishedAt[tab.id] = System.currentTimeMillis()
            appCtx?.let { c ->
                BrowserTabSnapshotStore.upsert(
                    context = c, tabId = tab.id, url = url, title = tab.state.title,
                    scrollX = 0f, scrollY = lastScrollY[tab.id] ?: 0f,
                    now = System.currentTimeMillis(), touch = false,
                )
            }
            // L4：恢复出来又被点开的这一颗 ⇒ 把滚动送回原位（含 +800ms 补 clamp）
            if (pendingScroll.containsKey(tab.id)) applyRestoredScroll(tab)
            // L4：页面刚加载完，先采一次滚动值（供退后台快照用）
            appCtx?.let { captureScroll(it, tab.id) }
        }

        override fun onReceivedTitle(title: String) {
            // 预览页签的标题 = 文件名（openPreview 里写的），不让页面自带的 <title> 顶掉：
            // 用户在一排页签里要认的是"这是哪个文件"，不是"这页 HTML 自己说它叫什么"。
            if (!tab.state.isPreview()) tab.state.onReceivedTitle(title)
        }

        override fun onSslError(url: String) {
            tab.state.onBlocked(app.getString(R.string.browser_ssl_error))
        }

        override fun onPageError(url: String, message: String) {
            tab.state.onBlocked(app.getString(R.string.browser_page_error))
        }

        override fun onSafeBrowsingHit(url: String, threatType: Int) {
            tab.state.onBlocked(app.getString(R.string.browser_safe_browsing_hit))
        }

        /**
         * 渲染进程被系统回收（第一百七十二批 S2）—— 委托只做一次转发：
         * 「哪一颗标签被回收了」这件事本对象最清楚（它按标签构造），善后归管理器。
         */
        override fun onRenderProcessGone() {
            markRendererGone(app, tab.id)
        }
    }
}
