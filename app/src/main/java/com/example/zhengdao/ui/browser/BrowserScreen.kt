// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

package com.example.zhengdao.ui.browser

import android.content.Context
import android.net.Uri
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import com.example.zhengdao.R
import com.example.zhengdao.browser.WebViewEngine
import com.example.zhengdao.rootfs.RunLog
import com.example.zhengdao.ui.theme.zhengdaoErrorText
import kotlinx.coroutines.launch

/**
 * 玄览主界面 —— 用户模式（第⑨刀 B 多标签 + C 底栏改版）。
 *
 * 设计依据：《证道-浏览器Tab技术选型与架构草案-2026-10-13》v1.1 §4.1/§4.2/§4.4 ＋
 * `证道-玄览UI设计稿-2026-10-14-v1.1.html`（v1.1 主稿 + 探索稿 B 收窄定稿）。
 *
 * 已落地（② 文档定稿项）：
 * - 多标签（§4.2）：标签条 + 上限 20 + 每标签独立 WebView/历史栈 + 后台 onPause+WAIVED；
 *   标签状态每标签一份，切走再切回各归各（§B 顺序 32）；
 * - **底栏改版（2026-10-18 定稿）：两条带子合成一条**。原第⑨刀 C 的地址栏带 50dp ＋ 浮动
 *   胶囊栏 56dp（合计 **106dp**，2026-10-14 定稿）**作废**，改成一条 **56dp** 底栏：
 *   `‹〔›〕[地址胶囊] ↻/⨯ ⋯` ⇒ 壳子 158dp（52＋50＋56）→ **108dp**（52＋56），直板
 *   374.9×813.7dp 的页面可视 554dp/68% → **604dp/74%**，地址焦点从屏幕 15% 高度挪到 90%。
 *   件数：删除「前往」（与键盘 Go 同义）；前进改按需出现（`engine.canGoForward()`）；
 *   主页收进 ⋯ 菜单第一项（落点 `HOME_URL` 不变，动作本体仍写在本文件）；⋯ 菜单只放
 *   代码里已有的三个动作（主页 / 新标签 / 关闭当前标签）。刷新键加载中同位变红色 ⨯「停止」；
 *   键位与几何定稿见 `证道设计报告/玄览-底栏改版-定稿与施工单-2026-10-18.md`；
 * - 提示条落点随之搬家：底栏之上、页面区下沿（离出错那一页更近，不再把页面顶下一截）；
 * - 地址栏：URL 校验 + http→https 自动升级 + 非 URL 输入走搜索引擎；胶囊两态（常态只显示
 *   host ＋ 次级色 path，点开编辑态铺完整 URL），判据在 [BrowserUrlPolicy]；
 * - 安全：启动即 applySecurityConfig(EngineSecurityConfig())（默认加固值，§4.4）；
 *   危险 scheme 由引擎 + 委托双重拦截并给用户提示；
 * - JS 弹窗收口（§4.4）：引擎回调 → 本界面 Compose 对话框；
 * - WebView 实例经 [BrowserTabManager] 跨 Tab 保活（applicationContext 创建防泄漏）；
 * - **第五十五批 · §B 77 审计 P1-7/P1-8（2026-10-19）**：① WebView 不再在组合期构造 ——
 *   `remember { BrowserHolder.obtain(...) }` 改成首帧后的 `LaunchedEffect`，构造期间页面区
 *   给 [BrowserWarmupPlaceholder]（主题底＋进度件，不白屏），引擎改「可空＋随活跃标签派生」，
 *   所有引擎调用空安全（治的是首帧卡死 / ANR 风险，见 §B 61 的构造耗时观测）；
 *   ② 外部打开入口 `BrowserHolder.pendingUrl` 改可观察状态后，本界面以「值」为 key 消费 ——
 *   已停在玄览 Tab 时点终端链接不再整条丢失（旧 `LaunchedEffect(Unit)` 只消费一次）。
 *   两条各有一套源码门禁：[BrowserFirstFrameEngineGateTest] / [BrowserPendingUrlObservableGateTest]。
 *
 * 图标说明：本项目图标一律 Canvas 自绘（Tab 图标 / BackChevron 同款笔法），不引
 * material-icons 库——视觉统一 + 零额外依赖。底栏键位 glyph 见 [BrowserBottomBar]。
 */
@Composable
fun BrowserScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val res = LocalResources.current
    // ── P1-7（第五十五批 · §B 77 审计，2026-10-19）：WebView 不再在组合期构造 ──────────
    // 旧写法 `val engine = remember { BrowserHolder.obtain(context) }`：remember 的 lambda 在
    // **组合期**执行，obtain 里 `new WebView(app)`；而 WebView 构造是本仓唯一已知会**间歇卡死
    // （≥100s）**的点，卡点在 Chromium 内部（App 侧修不了，见 BrowserTabManager.newTab 里
    // §B 61 的前后耗时观测）。它落在首帧之前 ⇒ 首帧卡死 / ANR。现在首帧只画占位
    // [BrowserWarmupPlaceholder]，构造挪进下面的 LaunchedEffect —— 仍跑主线程，但**已过首帧**。
    //
    // 延迟构造后「第一个标签要自己冒出来」靠什么：BrowserTabManager 的状态本就是 snapshot
    // state（tabs = mutableStateListOf、activeId = mutableStateOf），本界面在**组合体**里读
    // tabs() / activeTab() ⇒ newTab 的写入直接驱动重组，不需要额外刷新通道，也不需要改
    // BrowserTabManager（门禁 BrowserFirstFrameEngineGateTest 判据 F7 同时钉这两件事：
    // 状态源可观察 ＋ 读发生在组合期）。
    //
    // 初值取「已有标签」：切回玄览时标签还在（第⑨刀的跨 Tab 保活是本类设计前提），此刻
    // WebView 早已构造完 ⇒ 首帧就该是页面，不该先转一圈；冷启动（0 标签）才走占位。
    var engineReady by remember { mutableStateOf(BrowserTabManager.count() > 0) }
    val tabs = BrowserTabManager.tabs()
    val active = BrowserTabManager.activeTab()
    // 引擎跟着**活跃标签**走：构造完成前是 null ⇒ 下面所有引擎动作一律空安全（不 `!!` 硬取）。
    // 顺带堵住旧写法的另一半病：remember 下来的 engine 在用户关掉最后一个标签之后指向
    // **已销毁**实例（㊽-D 只把提交路径改活了，底栏那几个键当时仍在吃死引擎）。
    val engine: WebViewEngine? = active?.engine

    LaunchedEffect(Unit) {
        // 第一百七十二批 L4：冷启先进一次快照（只在本进程还没有任何标签时）。
        // 读库在 IO 线程（不许主线程 IO）；恢复只材料化「上次活跃」那一颗，其余进标签条待点开。
        if (BrowserTabManager.count() == 0) {
            val rows = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                BrowserTabSnapshotStore.load(context)
            }
            BrowserTabManager.restoreFromSnapshot(context, rows)
        }
        // 首帧后构造：无标签时建第一个（建 WebView + 引擎 + 委托），已有标签时复用活跃引擎。
        BrowserHolder.obtain(context)
        engineReady = true
    }

    // 内存回收守卫（第⑱刀 · D-4）：把"玄览正显示在屏幕上"告诉回收出口 —— 只有本界面
    // 离开组合（切到别的底栏 Tab）之后，WebView 才允许被 destroy（挂载中的实例不可销毁，
    // 见 BrowserHolder.releaseIfIdle）。非视觉改动，不碰底栏/胶囊几何。
    DisposableEffect(Unit) {
        BrowserHolder.onBrowserShown()
        onDispose { BrowserHolder.onBrowserHidden() }
    }

    // 地址栏输入：跟随活跃标签（切 Tab 时换成那个标签的地址）
    var urlInput by remember { mutableStateOf(active?.state?.url ?: "") }
    // 底栏改版（2026-10-18）：地址胶囊两态由本界面持有 —— 展示态读**页面真实 URL**
    // （`active?.state?.url`，由引擎的 onPageStarted/Finished 写进每标签状态），编辑态才吃
    // urlInput。放在这里而不是底栏内部：切标签要能把编辑态关掉（见下面的 LaunchedEffect）。
    var addressEditing by remember { mutableStateOf(false) }
    // UI 层一次性提示（如"标签数已达上限"），与本标签安提示条共用一行样式
    var uiMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(active?.id) {
        urlInput = active?.state?.url ?: ""
        addressEditing = false
    }

    // ㊽-D（§B 79，2026-10-16）：0 标签空态下提交不再「点了没反应」。
    // 原先三条提交路径（地址栏「前往」/ 地址栏 IME Go / 起始页搜索框）都把 URL 交给 remember
    // 下来的 engine —— 用户关掉最后一个标签后 active == null，那个 engine 属于已销毁的标签，
    // loadUrl 落进没人承载的 WebView；提示又写进 active?.state，而 active 也是 null ⇒ 连
    // 「被拦」的提示都发不出来（真机实证 N5）。现在统一走 [tryNavigate]，由它保证有承载。
    val reportBlocked: () -> Unit = {
        val msg = res.getString(R.string.browser_blocked_scheme)
        val tab = BrowserTabManager.activeTab()
        if (tab != null) tab.state.onBlocked(msg) else uiMsg = msg
    }
    val navigate: () -> Unit = {
        if (!tryNavigate(context, urlInput)) reportBlocked()
    }

    // ── JS 弹窗收口（§4.4）：引擎（applicationContext 的 WebView，弹不了框）→ 本界面对话框 ──
    // 每条回答都必须最终调用一次结果回调，否则页面 JS 永久阻塞等答案。
    var jsDialog by remember { mutableStateOf<JsDialog?>(null) }
    // P1-7：engine 构造前为 null ⇒ 回调只在有引擎时挂（`if (engine != null)` 守卫块是门禁
    // F5 认可的**唯一**直取位置）；key 换成每标签引擎后，切标签会把回调搬到当前那一页，
    // 不再只绑在第一个标签上（旧写法 remember 一个引擎，第二个标签的 JS 弹窗没人接）。
    if (engine != null) {
        DisposableEffect(engine) {
            engine.jsConfirmHandler = { message, onResult ->
                jsDialog = JsDialog.Confirm(message, onResult)
            }
            engine.jsPromptHandler = { message, defaultValue, onResult ->
                jsDialog = JsDialog.Prompt(message, defaultValue, onResult)
            }
            onDispose {
                engine.jsConfirmHandler = null
                engine.jsPromptHandler = null
            }
        }
    }

    // 外部打开入口（§4.6，第 ② 刀接线）：终端链接点击把 URL 写进 BrowserHolder。
    // ── P1-8（第五十五批 · §B 77 审计，2026-10-19）：pendingUrl 改成可观察状态后，消费
    // 不再挂在 `LaunchedEffect(Unit)` 上。旧写法只在**首次组合**跑一次：用户已经停在玄览
    // Tab 时从终端点一条链接，组合不重跑 ⇒ URL 整条丢失（@Volatile 只给可见性，不给重组）。
    // 现在先在组合体读一次（读发生在组合期 ⇒ 写入即重组），再以「值」为 key 起 effect 消费。
    // 消费后先清槽再开标签：key 变成 null 会让 effect 再跑一次，那一趟走 `pending != null`
    // 的分支外（空操作），不会重复 openExternal（门禁 S5/S6 钉顺序与次数）。
    val pending = BrowserHolder.pendingUrl
    LaunchedEffect(pending) {
        if (pending != null) {
            BrowserHolder.pendingUrl = null
            BrowserTabManager.openExternal(context, pending)
        }
    }

    // ── 本地预览请求（第五十九批 §5）：上面那条链的**同房型、另一个槽** ──────────────
    // 为什么另开一个槽而不复用 pendingUrl：pendingUrl 装的是"去打开这个网址"，这里装的是
    // "这份**已经读出来、已经守过越界**的文本，挂到只显示页签上"。塞进同一条 `openExternal`
    // 就要在链中间判类型分叉——那是第二条过滤链的兄弟形状（§4 打回第二条）。
    // 房型照抄三条：① 读发生在组合期（写入即重组，P1-8 那条病不重犯）；
    // ② effect 以「值」为 key，而 Request 带单调 seq ⇒ 同一文件原样再预览一次也是新事件
    //    （否则"页签被关掉后再调一次没反应"）；③ 先清槽再开页签（key 变 null 那一趟是空操作）。
    // 满了（20）时 openPreviewTab 回 null ⇒ 如实落一条 UI 层提示，不静默丢（与「＋」键同一条文案）。
    // 文案在**组合期**取好再给 effect 用：`LaunchedEffect` 里不能调 stringResource（协程体不在
    // 组合上下文），而 `context.getString` 写在 effect 里会被 lint 判成本地上下文取资源
    // （LocalContextGetResourceValueCall，Error 级——预算闸只容存量、不容新增）。
    val tabsFullNote = stringResource(R.string.browser_tabs_full)
    val previewPending = LocalPreviewHolder.pending
    LaunchedEffect(previewPending) {
        if (previewPending != null) {
            LocalPreviewHolder.pending = null
            val opened = BrowserTabManager.openPreviewTab(
                context,
                previewPending.relPath,
                previewPending.text,
            )
            if (opened == null) uiMsg = tabsFullNote
        }
    }

    // 地址胶囊编辑态的「退出」通道（2026-10-18 收尾，§B 顺序 75 落地）：
    // 定稿 §6 只写了「点胶囊进编辑态 + 键盘 Go 提交」，没写怎么取消。旧设计地址栏永远是输入框，
    // 所以这不算回归；但两态设计里「点开却退不出去」是硬伤 —— 退出只能靠提交或切标签。
    // 现在补两条出口，**都不导航、不退出 App**：① 系统返回键；② 点页面区（旁听，不消费事件）。
    val focusManager = LocalFocusManager.current
    val exitAddressEditing: () -> Unit = {
        addressEditing = false
        focusManager.clearFocus(force = true) // 收键盘（BACK 键收键盘是禁手，见交付报告纪律）
    }

    // 系统返回键优先级：① 编辑态 ⇒ 先退出编辑 ② 有历史 ⇒ 走 WebView 历史栈（§4.2 导航语义）
    // ③ 都没有 ⇒ 交给系统。两条 BackHandler 的 enabled **互斥**，谁在栈顶都不抢对方。
    // P1-7：第二条要等引擎构造完（engine 非空）才登记 —— 构造前没有历史栈可退，
    // 不登记就等于「交给系统」，语义与旧版 canGoBack()==false 一致。
    BackHandler(enabled = addressEditing) { exitAddressEditing() }
    if (engine != null) {
        BackHandler(enabled = !addressEditing && engine.canGoBack()) { engine.goBack() }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // ── 标签条（第⑨刀 B） ──
        BrowserTabStrip(
            tabs = tabs,
            activeId = active?.id,
            onSelect = { BrowserTabManager.selectTab(it) },
            onClose = { BrowserTabManager.closeTab(it) },
            onAdd = {
                if (BrowserTabManager.newTab(context) == null) {
                    uiMsg = res.getString(R.string.browser_tabs_full)
                }
            },
            // L4：上次没点开的标签，点哪颗才建哪颗的 WebView（不自动联网）
            restored = BrowserTabManager.restored(),
            onOpenRestored = { id ->
                if (BrowserTabManager.openRestored(context, id) == null) {
                    uiMsg = res.getString(R.string.browser_tabs_full)
                }
            },
        )

        // ── 页面区（底栏改版 2026-10-18：紧跟标签条，壳子只剩 52＋56） ──
        // key(活跃标签) ⇒ 切 Tab 时旧 WebView 摘挂载、新的挂上
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // 编辑态下点页面区只负责「退出编辑」，**旁听不消费**（Initial pass 观察一次就退出
                // 监听）—— 页面的链接、滚动照旧收到这一下，别为了退出编辑把整个 WebView 变成死屏。
                .pointerInput(addressEditing) {
                    if (!addressEditing) return@pointerInput
                    awaitPointerEventScope {
                        awaitPointerEvent(PointerEventPass.Initial)
                        exitAddressEditing()
                    }
                },
        ) {
            val activeId = active?.id
            // 第一百七十二批 S2：本标签的渲染进程被系统回收过 ⇒ 页面是死的，
            // 不挂 WebView（挂上去只会是一块白），改画「点一下重新加载」卡片。
            val recycled = active?.state?.recycled == true
            // L4：从快照恢复、还没点开 ⇒ 同样不挂 WebView（现在是空的），画「上次的页面」卡片
            val restored = active?.state?.restored == true
            // 第㊱刀：空态不挂 WebView —— 共享 WebView 默认白底，深色模式下会把
            // 起始页的雾白图腾/标题压成 ~1:1（实测截图不可见）；空态时页面区就是
            // 起始页（背景走主题色），everLoaded 翻转后照常挂回，状态机零改动。
            if (activeId != null && !recycled && !restored && !(active == null || !active.state.everLoaded)) {
                key(activeId) {
                    AndroidView(
                        factory = { BrowserTabManager.view() ?: WebView(it) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            // 回收卡片压在同一块页面区上：点了才 loadUrl（不偷偷联网，见 reloadRecycled）
            if (active != null && recycled) {
                BrowserRecycledCard(
                    onReload = { BrowserTabManager.reloadRecycled(active.id) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // L4 恢复卡片：点了「打开」才第一次联网（loadRestoredTab）
            if (active != null && restored) {
                BrowserRestoredCard(
                    onOpen = { BrowserTabManager.loadRestoredTab(active.id) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // P1-7：WebView 还在构造（首帧后那一段）⇒ 页面区显示占位，**不给白屏**。
            // 占位自带主题底色（不是 WebView 的默认白底，第㊱刀那条深色对比度教训同一件事），
            // 且不挂 AndroidView —— 构造没完成时此刻也没有可挂的实例。
            if (!engineReady) {
                BrowserWarmupPlaceholder(modifier = Modifier.fillMaxSize())
            } else if (active == null || !active.state.everLoaded) {
                // 第㊱刀：空态 = 浏览器式起始页（居中搜索框 + 快捷入口），替代原裸 Text。
                // 提交仍走 tryNavigate（既有归一化路径，禁第二套解析）；scheme 预检失败
                // 沿用既有 browser_blocked_scheme 提示；空态判据一字未动
                // （everLoaded 只涨不落，不新增状态、不改 BrowserTabState）。
                // ㊽-D（§B 79，2026-10-16）：空态正是「0 标签」这一支 —— tryNavigate 现在会
                // 先保证有承载引擎（建标签后加载），提示也不再写向 null 的 active。
                BrowserStartPage(
                    onSearch = { input ->
                        if (!tryNavigate(context, input)) reportBlocked()
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // ── 安全提示条（可关闭）：本标签安提示优先，其次 UI 层一次性提示 ──
        // 底栏改版（2026-10-18）落点搬家：**底栏之上、页面区下沿** —— 离出错那一页更近，
        // 且不再把页面顶下一截（原来它夹在胶囊与页面之间）。样式一字未改。
        val msg = active?.state?.blockedMsg ?: uiMsg
        msg?.let { text ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WarningGlyph(tint = MaterialTheme.colorScheme.error, side = 16.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    // P2-11：提示正文走 zhengdaoErrorText()（左边的警告图标是图形，
                    // 3:1 即过，保留 error 色）
                    color = zhengdaoErrorText(),
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        active?.state?.blockedMsg = null
                        uiMsg = null
                    },
                ) {
                    CloseGlyph(
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        contentDescription = stringResource(R.string.browser_dismiss),
                    )
                }
            }
        }

        // ── 底栏那一格（56dp）：二选一 ──
        //  · 上网页签 → [BrowserBottomBar]（`‹〔›〕[地址胶囊] ↻/⨯ ⋯`，2026-10-18 定稿，一个字没改）
        //  · 本地预览页签 → [BrowserPreviewBar]（文件名＋状态 / 看源码·看排版 / 脚本 / 复制，§5.3-§5.4）
        // 判据只有 [BrowserTabState.isPreview] 这一条（= previewPath 非空），不新增状态源。
        // 为什么是"换一栏"而不是"底栏加几个参数"或"底栏上再叠一栏"：见 BrowserPreviewBar 的 KDoc；
        // 叠一栏会把壳子从 108dp 撑到 164dp（页面可视少 56dp），那是 §5 从没要求的外观预算。
        val previewTab = active?.takeIf { it.state.isPreview() }
        if (previewTab != null) {
            BrowserPreviewBar(
                fileName = previewTab.state.previewFileName(),
                mode = previewTab.state.previewMode,
                jsSandbox = previewTab.state.previewJsSandbox,
                text = previewTab.state.previewText.orEmpty(),
                onSetMode = { BrowserTabManager.setPreviewMode(previewTab.id, it) },
                onToggleJsSandbox = { BrowserTabManager.setPreviewJsSandbox(previewTab.id, it) },
            )
        } else {
            // 动作本体仍写在本文件（门禁要求）：主页 = HOME_URL、刷新/停止 = reload/stopLoading、
            // 提交 = 唯一入口 tryNavigate；切 Tab 时展示态跟着换（读活跃标签 state.url）。
            // P1-7：引擎相关件在构造完成前一律"没有"——前进/后退判据取不到历史就当没有历史，
            // 动作键空安全（engine 为 null 时静默，不 `!!` 硬取）；提交键不受影响，它走 tryNavigate
            // （内部 `activeEngine() ?: obtain` 自己保证有承载，㊽-D 那条路径本来就不吃这里的 engine）。
            BrowserBottomBar(
                displayUrl = active?.state?.url ?: "",
                editValue = urlInput,
                isEditing = addressEditing,
                canBack = engine?.canGoBack() == true,
                canForward = engine?.canGoForward() == true,
                loading = active?.state?.loading == true,
                canCloseTab = active != null,
                onEditStart = {
                    // 进编辑态先把当前页的真实 URL 灌进输入框（否则改的是上次提交的残留）
                    urlInput = active?.state?.url ?: ""
                    addressEditing = true
                },
                onEditEnd = { addressEditing = false },
                onValueChange = { urlInput = it },
                onSubmit = navigate,
                onBack = { engine?.goBack() },
                onForward = { engine?.goForward() },
                onRefreshOrStop = {
                    if (active?.state?.loading == true) engine?.stopLoading() else engine?.reload()
                },
                onHome = { engine?.loadUrl(HOME_URL) },
                onNewTab = {
                    if (BrowserTabManager.newTab(context) == null) {
                        uiMsg = res.getString(R.string.browser_tabs_full)
                    }
                },
                onCloseTab = { active?.let { BrowserTabManager.closeTab(it.id) } },
            )
        }
    }

    // ── JS 弹窗对话框 ──
    jsDialog?.let { dialog ->
        JsDialogUi(dialog = dialog, onDismiss = { jsDialog = null })
    }
}

/**
 * 主页落点（C）：设计稿起始页「当前引擎：必应国内版 · 免翻墙」——
 * 主页 = 回引擎首页。底栏改版（2026-10-18）后**主页键不再是常驻件**，收进 ⋯ 菜单第一项，
 * 但动作本体仍留在本文件（[BrowserBottomBar] 只发一个"回主页"回调），落点值一字未改。
 * 新标签的空态引导（第㊱刀起始页：居中搜索框 + 快捷入口）仍由 everLoaded=false 呈现，
 * 见 [BrowserStartPage]。
 */
private const val HOME_URL = "https://cn.bing.com"

/** JS 弹窗的两种形态（alert/confirm 同一形态；prompt 多一个输入框）。 */
private sealed class JsDialog {
    /** 页面消息（对话框正文）。 */
    abstract val message: String

    data class Confirm(
        override val message: String,
        val onResult: (Boolean) -> Unit,
    ) : JsDialog()

    data class Prompt(
        override val message: String,
        val defaultValue: String,
        val onResult: (String?) -> Unit,
    ) : JsDialog()
}

/**
 * JS 弹窗对话框（§4.4「JS 弹窗收口」的 UI 半）。
 * 三条出口（确认/取消/点外面）都保证回答一次——页面 JS 在等，不答就永久卡住。
 */
@Composable
private fun JsDialogUi(dialog: JsDialog, onDismiss: () -> Unit) {
    var promptText by remember(dialog) {
        mutableStateOf((dialog as? JsDialog.Prompt)?.defaultValue ?: "")
    }
    val answer: (Boolean, String?) -> Unit = { ok, text ->
        when (dialog) {
            is JsDialog.Confirm -> dialog.onResult(ok)
            is JsDialog.Prompt -> dialog.onResult(if (ok) text else null)
        }
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = { answer(false, null) },
        title = { Text(stringResource(R.string.browser_js_dialog_title)) },
        text = {
            Column {
                Text(dialog.message)
                if (dialog is JsDialog.Prompt) {
                    Spacer(Modifier.padding(top = 8.dp))
                    BasicTextField(
                        value = promptText,
                        onValueChange = { promptText = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { answer(true, promptText) }) {
                Text(stringResource(R.string.common_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = { answer(false, null) }) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

/**
 * 地址栏 / 起始页提交的**唯一入口**（㊽-D ＝ §B 79，2026-10-16 起）：scheme 预检 →
 * 归一化 URL → 确保有承载的引擎 → 加载。（第51刀把前两步的顺序改正，见下方"顺序"。）
 *
 * 为什么"确保承载"也放在这里：提交路径共有三条（地址栏「前往」、地址栏 IME Go、起始页搜索框），
 * 三条都调本函数 —— 判据只放一处，才不会出现"某一条记得建标签、另一条忘了"。
 * 0 标签时建标签走 [BrowserTabManager.obtain]（= 建标签 + 取活跃引擎），与标签条「＋」同一入口。
 *
 * 背景（真机实证 N5）：2026-10-16 前三条路径都把 URL 交给 remember 下来的 engine。用户关掉
 * 最后一个标签后 active == null，那个 engine 属于**已销毁**的标签 ⇒ loadUrl 落进没人承载的
 * WebView，界面仍停在起始页，表现为"点了没反应"；且提示写进 `active?.state`，active 为 null
 * 时连"被拦"的提示都发不出来。
 *
 * scheme 白名单与引擎内拦截一致（http/https）——这里是用户输入预检，引擎是兜底。
 *
 * **顺序（第51刀 ＝ §B 82 的 N7 修复，2026-10-16）**：预检必须早于归一化。旧顺序先归一化，
 * 而归一化对"不像 URL"的输入一律当搜索词包成 bing 查询 ⇒ `intent://x` 被洗成 `https://…`，
 * 预检永远看到 https，`return false` 这条分支在真实输入上不可达（真机：提示一次没出现，
 * 输入被当搜索词提交）。判据本身搬进 [BrowserUrlPolicy]（纯函数，可被单测直接打到）。
 *
 * @return false = scheme 不在白名单（调用方给提示）；true = 已加载，或空输入（无动作）。
 */
private fun tryNavigate(context: Context, input: String): Boolean {
    if (BrowserUrlPolicy.shouldReject(input)) return false
    val url = normalizeInput(input)
    if (url.isEmpty()) return true
    val scheme = runCatching { url.toUri().scheme?.lowercase() }.getOrNull()
    if (scheme !in setOf("http", "https")) return false
    // ㊽-D：0 标签空态（用户关掉最后一个标签）时先建一个，否则这次提交没有承载者。
    val engine = BrowserTabManager.activeEngine() ?: BrowserTabManager.obtain(context)
    engine.loadUrl(url)
    return true
}

/**
 * 输入归一化（§4.2 用户模式要点；实现已搬进 [BrowserUrlPolicy.normalize]，此处只做
 * `Uri.encode` 注入 —— 判据留在纯函数里，才能被普通 JVM 单测打到真行为）：
 * - 空串 → 空（调用方无动作）；
 * - 不含点号或含空格 → 当搜索词走 bing（与 openWebSearch 兜底一致，② `TerminalActivity.kt:1061` 先例）；
 * - http:// → https://（外部明文被网络策略拦，升级避免加载失败）；
 * - 其他 → 补 https://。
 */
private fun normalizeInput(input: String): String =
    BrowserUrlPolicy.normalize(input) { Uri.encode(it) }

// ─────────────────────────────────────────────────────────────────────────────
// 提示条自绘小图标（与项目 Tab 图标 / BackChevron 同一套笔：圆头 1.9f 描边）。
// 底栏键位 glyph 在 BrowserBottomBar.kt（描边宽度可配，禁用态 1.4）。
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 首帧占位（P1-7 · 第五十五批）：WebView 构造完成前页面区显示的东西，要求是「轻量、**不白屏**」。
 *
 * 为什么不能是一块白：页面区此刻没有挂 AndroidView（引擎还在构造），露出来的是窗口底 ——
 * 深浅色下不一致，第㊱刀已经为"WebView 默认白底压掉深色起始页"付过一次学费。所以这里
 * 自己铺一层主题 `background`，再居中一个进度件（与标签条加载转圈同族，28dp）。
 *
 * 只活一小会儿：正常情况下一帧到几百毫秒；真卡在 Chromium 构造里（§B 61 那条）时，
 * 它至少让用户看到"在准备"而不是白屏或闪退。
 */
@Composable
private fun BrowserWarmupPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 2.5.dp,
        )
    }
}

/**
 * 渲染进程被回收后的卡片（第一百七十二批 S2）—— **不白屏、不假装活着、不偷偷联网**。
 *
 * 为什么要有它：`onRenderProcessGone` 被接住（返回 true）之后，App 活下来了，但这颗标签的
 * 页面已经死了（WebView 实例也被换掉）。若什么都不画，用户看到的就是一块白 —— 与
 * 「切回不重载」的承诺撞在一起，会被当成"玄览坏了"。所以这里说清三件事：
 * 发生了什么（系统回收了页面）、没发生什么（你的标签还在）、下一步（点一下重新加载）。
 *
 * 零自动加载：重新加载必须由用户点（2026-10-24 口径：不偷偷联网）。
 */
@Composable
private fun BrowserRecycledCard(onReload: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.browser_recycled_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.browser_recycled_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            // 热区 ≥48dp（项目既有纪律：不许做小热区按钮）
            Button(onClick = onReload, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.browser_recycled_reload))
            }
        }
    }
}

/**
 * 「上次的页面」卡片（第一百七十二批 L4）—— 与回收卡片同版式、**不同语义**：
 * 回收＝页面曾经活着被系统收走；恢复＝这次启动从没加载过，只有地址和标题。
 * 共同点是最要紧的那条：**不点不开、不偷偷联网**。
 */
@Composable
private fun BrowserRestoredCard(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.browser_restored_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.browser_restored_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.browser_restored_open))
            }
        }
    }
}

/** 警告：三角 + 感叹号。 */
@Composable
private fun WarningGlyph(tint: Color, side: androidx.compose.ui.unit.Dp = 20.dp) {
    Canvas(modifier = Modifier.size(side)) {
        val w = size.width
        val h = size.height
        val tri = Path().apply {
            moveTo(w * 0.50f, h * 0.10f)
            lineTo(w * 0.95f, h * 0.88f)
            lineTo(w * 0.05f, h * 0.88f)
            close()
        }
        drawPath(
            tri,
            tint,
            style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawLine(tint, Offset(w * 0.50f, h * 0.38f), Offset(w * 0.50f, h * 0.62f), 1.6.dp.toPx(), StrokeCap.Round)
        drawCircle(tint, radius = w * 0.045f, center = Offset(w * 0.50f, h * 0.75f))
    }
}

/** 关闭：X。 */
@Composable
private fun CloseGlyph(
    tint: Color,
    contentDescription: String? = null,
    side: androidx.compose.ui.unit.Dp = 18.dp,
) {
    Canvas(modifier = Modifier.size(side)) {
        val w = size.width
        val h = size.height
        val m = w * 0.22f
        drawLine(tint, Offset(m, m), Offset(w - m, h - m), 1.9.dp.toPx(), StrokeCap.Round)
        drawLine(tint, Offset(w - m, m), Offset(m, h - m), 1.9.dp.toPx(), StrokeCap.Round)
    }
}
