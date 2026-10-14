// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据的公开接口：Jetpack Compose 官方 API、OpenCode 官方 serve 模式。
package com.example.zhengdao.ui.taiji

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import com.example.zhengdao.R
import com.example.zhengdao.oc.OcManager
import com.example.zhengdao.oc.OcModel
import com.example.zhengdao.oc.ConnectionState
import com.example.zhengdao.oc.OcMessage
import com.example.zhengdao.oc.OcPart
import com.example.zhengdao.oc.OcRepository
import com.example.zhengdao.oc.OcRuntime
import com.example.zhengdao.oc.OcSessionSummary
import com.example.zhengdao.oc.TaijiPhase
import com.example.zhengdao.oc.TaijiState
import com.example.zhengdao.ui.PluginManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 太极 Tab —— OpenCode 的 Compose 原生客户端。
 *
 * ## 定位
 *
 * 直连 `opencode serve` 的 HTTP + SSE API，不经WebView、不经 TerminalView。
 * 完整设计见 docs/milestones/证道-太极Tab-Compose设计方案.md。
 *
 * ## 骨架状态（2026-10-06）
 *
 * ✅ 已就绪：网络层（[OcClient] / [SseClient]）、状态归约（[OcRepository]）、
 *   全部 UI 组件（[TaijiComponents.kt]）。
 * ⚠️ **端点与字段待实测**：跑的是社区 bionic 版（binary surgery 移植产物），
 *   API 覆盖面与事件字段须按实际打包版本核实（阶段 0-4）。若某端点缺失，
 *   对应功能降级但**不崩溃**（[OcRepository] 内均runCatching 包裹）。
 * ⚠️ **鉴权未验证**：阶段 0-1 若过不了，本界面会停在"启动失败"并显示明确原因。
 *
 * @param onExit 退出太极 Tab（回洞天/丹房）。
 */
@Composable
fun TaijiScreen(
    onExit: () -> Unit = {},
    // K2（切回 Tab 保留滚动位置）：外部 HomeTabs 注入的 listState。
    // 不传时退化到内部新建。**注意**：切会话的逻辑（LaunchedEffect(sessionId) 滚到底）
    // 在 MessageList 内部仍生效——K2 解决的是"切 Tab 不丢位置"，与会话切换无关。
    listState: androidx.compose.foundation.lazy.LazyListState =
        androidx.compose.foundation.lazy.rememberLazyListState(),
    // K2 配套（2026-10-08 审查）：只提 listState 不够 ——「是否跟随最新」和「已定位过哪个会话」
    // 同样要跨 Tab 存活，否则切回来时 follow 重新初始化成 true、positionedSession 变 null，
    // 两个 LaunchedEffect 立刻把保留的滚动位置踢回底部，K2 在太极页等于没做。默认值只服务
    // "调用方没注入"的旧用法，HomeTabs 里两个都注入（见 MainActivity 的 K2 配套注释）。
    followState: androidx.compose.runtime.MutableState<Boolean> =
        androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) },
    positionedSession: androidx.compose.runtime.MutableState<String?> =
        androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(null) },
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // ⚠️ 网络层用 remember 而非 rememberSaveable：连接不应在配置变更（旋转）时重建。
    //    Compose state 是唯一真相；进程被杀后由 open() 从 serve 恢复。
    //
    // 2026-10-11（用户报「每次重进太极都会重新连接」）：实例改从 OcRuntime 取。
    //   原先 `remember { OcRepository(…) }` 只活在**本页面的 Composition** 里，而 HomeTabs
    //   切 Tab 用的是 `when (tab)`（切走的页面直接退出 Composition）⇒ "切去丹房再切回来"
    //   会把 repository 与它的轮询/SSE 任务一起拆掉重建。现在连接挂在进程上，切 Tab、
    //   转屏、退到后台都不再重连；只有「关闭太极并退出」或进程被杀才结束（见 OcRuntime）。
    val repo = remember { OcRuntime.repository(ctx.resources) }
    // 用项目已有的 collectAsState，避免为collectAsStateWithLifecycle 引入 lifecycle-runtime-compose
    val state by repo.state.collectAsState()

    // C2（重新发送）：发送失败时弹 Snackbar + "重试" action。
    // 搜证结论：MD3 / iOS HIG / WhatsApp 实务都要求"永远不要删除用户输入"——
    // 失败时把 input 留给输入框（prompt 失败时不走 `input=""` 路径，已天然满足），
    // 再给一个 1-tap 的重发入口。
    //
    // Snackbar 优于 Toast 的原因：MD3 明确说"需要操作的错误用 Snackbar"——
    // "重试"是个有后续动作的入口，不是通知。
    val snackbarHostState = remember { SnackbarHostState() }

    // 🔺 会话 id 必须跨「配置变更 + Tab 切换 + 终端往返」都保住。
    //    P2-3（§B 顺序 2，2026-10-14）：原先是 rememberSaveable —— 那只跨**转屏**；
    //    切到底栏别的 Tab 时 TaijiScreen 离开组合、id 一丢，回来就 repo.open(scope, null)
    //    ⇒ **新建一个会话**，历史消息全丢。现在存进进程级的 [TaijiStateHolder]
    //    （与 ui/browser/BrowserHolder 同口径），本地 savedSession 只是它的可变镜像。
    //    Repository 重建后会自动全量拉取，所以只要 id 保住，内容就能完整恢复。
    var savedSession by remember { mutableStateOf(TaijiStateHolder.sessionId) }

    /** 统一的会话 id 写入点：同时更新本地镜像与 holder（漏写 holder 它就白建）。 */
    fun syncSession(id: String?) {
        savedSession = id
        TaijiStateHolder.setSession(id)
    }

    // ── v1.1 第一阶段：会话完整化 ─────────────────────────────────────
    // 历史列表内容 + 左侧抽屉的开合。刻意留在 UI 层局部状态，不进 Repository
    // —— 「会话数据」才是 Repository 的职责，「抽屉开没开」是纯 UI 关注点。
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    // ── 插件入口（2026-10-08）：从「设置」页搬进太极 ──
    // 用户反馈「太极页找不到插件入口」——插件是太极的能力（PluginManager 只读写太极实例的
    // opencode.json），入口却在设置页，用的时候要跨页找。现放进抽屉底部（见 HistoryDrawer），
    // 面板内容复用设置页那一份 PluginsScreen，避免两份 UI 各自变。
    var showPlugins by remember { mutableStateOf(false) }
    var pluginCount by remember { mutableStateOf(0) }
    // 长按某条消息 → 操作面板（2026-10-09 用户需求「对话那里长按能复制」）。
    // 这里只存"哪条消息被长按了"；文本怎么拼（正文优先 / 工具卡兜底）在 MessageCopy 里，
    // 面板只负责展示与回调 —— UI 状态与文本规则分开，后者可 JVM 单测。
    var actionMessage by remember { mutableStateOf<OcMessage?>(null) }
    // 开抽屉 / 面板开合都重算一次：插件是在面板里改的，回到抽屉必须看到新数字。
    LaunchedEffect(drawerState.isOpen, showPlugins) {
        pluginCount = withContext(Dispatchers.IO) {
            PluginManager.readSpecs(PluginManager.taijiConfig(ctx)).size
        }
    }
    var sessions by remember { mutableStateOf<List<OcSessionSummary>>(emptyList()) }
    var sessionsLoading by remember { mutableStateOf(false) }

    // 拉取历史会话（打开抽屉 / 手动刷新 / 删除后 共用同一实现）。
    //
    // 2026-10-09（审计 A2）修两处真问题：
    //  ① 原写法 `sessions = repo.listSessions()`——repo 会把"拉取失败"吞成**空列表**，
    //     于是刷新失败时界面上的已有会话被清空，用户看到"会话都没了"，还毫无提示；
    //  ② 没有 try/finally——一旦抛异常 `sessionsLoading` 永久停在 true（转圈不消失）。
    // 现在：用 listSessionsResult 区分"确实为空"与"失败"；**失败保留旧列表** +
    // Snackbar 可见反馈 + 一键重试；finally 保证进度一定收起。
    suspend fun refreshSessions() {
        sessionsLoading = true
        val res = try {
            repo.listSessionsResult()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // 协程取消要原样抛出，不能当成"刷新失败"
        } catch (e: Throwable) {
            Result.failure(e) // 兜底：任何未预期异常也走失败路径，绝不让 loading 卡住
        } finally {
            sessionsLoading = false
        }
        res.fold(
            onSuccess = { sessions = it },
            onFailure = { e ->
                // 人话化：与发送失败同款（HumanizeError）。旧列表**不动**——宁可显示陈旧数据，
                // 也不能让用户以为会话被删光了。
                val title = com.example.zhengdao.util.HumanizeError.title(e, ctx.resources)
                val r = snackbarHostState.showSnackbar(
                    message = ctx.getString(R.string.taiji_refresh_failed, title),
                    actionLabel = ctx.getString(R.string.common_retry),
                    withDismissAction = true,
                    duration = SnackbarDuration.Short,
                )
                if (r == SnackbarResult.ActionPerformed) refreshSessions()
            },
        )
    }

    // 🔺 返回键兜底：抽屉打开时按 BACK 必须**先关抽屉**，不能直接退出 App。
    //   material3 的 ModalNavigationDrawer 内置了 predictive back，但在本工程
    //   （targetSdk 28、未声明 android:enableOnBackInvokedCallback）的真机上**不生效**——
    //   2026-10-07 复测：抽屉开着按一次 BACK 直接回到桌面。这里显式注册，语义与「点遮罩关闭」一致。
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    // 顶部标题：优先历史列表里服务端给的 title，否则回退「首条用户消息前 20 字」
    // （计划 P1-4：**不调模型生成标题**）。两者都取不到则为空 → SessionBar 显示「新会话」。
    val currentTitle = remember(state.messages, state.sessionId, sessions) {
        sessions.firstOrNull { it.id == state.sessionId }?.title?.takeIf { it.isNotBlank() }
            ?: state.messages.firstOrNull { it.role == OcMessage.Role.USER }
                ?.parts?.filterIsInstance<OcPart.Text>()?.firstOrNull()?.text
                ?.lineSequence()?.firstOrNull()?.trim()?.take(20)?.takeIf { it.isNotBlank() }
            ?: ""
    }

    // ── 模型池（v1.0 任务一）──
    var showModelSheet by remember { mutableStateOf(false) }
    var modelsLoading by remember { mutableStateOf(false) }
    // 覆盖持久化：重启 App 后重放（复用最近会话时再次 POST，仅影响当前会话）。
    // 用项目统一的 SharedPreferences——DataStore 未在项目引入，为单个键值新增依赖不值。
    val modelPrefs = remember { ctx.getSharedPreferences("zhengdao-taiji", android.content.Context.MODE_PRIVATE) }
    var modelOverride by remember {
        mutableStateOf(
            modelPrefs.getString("model_provider_id", null)?.let { pid ->
                modelPrefs.getString("model_id", null)?.let { mid ->
                    OcModel(id = mid, providerID = pid, name = "", free = false)
                }
            }
        )
    }

    fun persistOverride(m: OcModel?) {
        modelPrefs.edit()
            .putString("model_provider_id", m?.providerID)
            .putString("model_id", m?.id)
            .apply()
        modelOverride = m
    }

    // ── 太极就地安装的进度（v1.2 新用户排查所得）──
    // 原实现只有一条 Toast「后台安装中…完成后重进本页」：新用户不知道装到哪、
    // 也不知道装完没有，且装完必须手动退出再进（serve 没人拉起）。
    var ocInstalling by remember { mutableStateOf(false) }
    var ocProgress by remember { mutableStateOf("") }
    // 2026-10-12 i18n：安装失败由这个显式标志表达，**不再**用
    // `progress.startsWith("安装失败")` 猜——切到 English 后文案前缀变了，那个判断恒为
    // false，失败态会退化成"普通引导页 + 无重试按钮"（同类隐患见 SettingsEnvActivity 的
    // 更新弹窗标题）。文案与状态解耦是这段的要害。
    var ocInstallFailed by remember { mutableStateOf(false) }
    // 装成功后自增 → 让下面的 LaunchedEffect 重跑一次（起 serve + 开会话）。
    // 只在成功时自增，故不会重复开会话。
    var serveStartTrigger by remember { mutableStateOf(0) }

    // 首次进入：确保 serve 在跑，再打开会话（复用上次的会话 id）
    LaunchedEffect(serveStartTrigger) {
        if (!OcManager.installed(ctx)) return@LaunchedEffect      // 未装：显示引导
        // serveRunning()/startServe() 都是阻塞的（裸 HttpURLConnection + 起进程），必须切 IO
        withContext(Dispatchers.IO) {
            // 太极自己的预置（人设 AGENTS.md + opencode.json 性能/插件字段 + 旧缓存搬家）：
            // 2026-10-09 从 ProotLauncher 搬来（ERRATA E-069）——它**必须由太极这条路径触发**，
            // 否则"只用太极、从不进终端"的用户拿不到人设；同时在起 serve 前把工作区建好。
            com.example.zhengdao.oc.TaijiPreset.ensure(ctx)
            // ⚠️ 竞态修复：**总是**调 startServe(ctx)，不再用 `if (!serveRunning())` 前置拦截。
            //    startServe 本身幂等：serve 已在运行（典型：重装/重启后上一进程的孤儿仍在
            //    监听）时，它会解析密码并写入 OcManager.servePassword 后返回。
            //    原写法在"孤儿 serve 存活"时直接跳过整个分支 → servePassword 恒为 null
            //    → 请求无 Authorization → 全程 401（本次「进不了 UI」的触发路径）。
            OcManager.startServe(ctx)   // 幂等：运行中则解析密码，未运行则拉起
        }
        // ⚠️ 用 OcRuntime.scope（进程级）而不是页面自己的 scope：轮询 / SSE 必须比
        //    Composition 活得久，否则切一次 Tab 就把连接拆了（见上面 repo 的注释）。
        repo.open(OcRuntime.scope, savedSession)
        syncSession(repo.state.value.sessionId ?: savedSession)
        // P2-2：切回来把该会话的草稿灌回输入框。本 effect 的 key 含 savedSession，
        // 而它切回时从 holder 恢复 ⇒ 这段会重跑，天然就是灌回点。
        // ⚠️ 只能回文本：repo.setInput(text: String) 不收光标（见 [TaijiStateHolder] KDoc）。
        TaijiStateHolder.draft(savedSession)?.let { repo.setInput(it) }
        // 重启后重放模型覆盖：复用的会话重新 POST 一次（幂等，仅影响当前会话）
        modelOverride?.let { m ->
            repo.state.value.sessionId?.let { repo.setSessionModel(m) }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when {
            // 未安装：给就地安装入口（丹房过滤了 OpenCode，v1.1.1 起这里是唯一入口——
            // 原文案"去丹房安装"是死循环回归）。缓存命中时约 1 分钟（含校验+解压）
            !OcManager.installed(ctx) -> NotInstalledPane(
                onExit = onExit,
                installing = ocInstalling,
                progress = ocProgress,
                failed = ocInstallFailed,
                onInstall = {
                    ocInstalling = true
                    ocInstallFailed = false
                    ocProgress = ctx.getString(R.string.taiji_oc_preparing)
                    scope.launch(Dispatchers.IO) {
                        val r = OcManager.downloadAndInstall(ctx) { msg ->
                            // 进度回调在后台线程 → 切主线程再改状态
                            scope.launch(Dispatchers.Main) { ocProgress = msg }
                        }
                        withContext(Dispatchers.Main) {
                            ocInstalling = false
                            if (r.ok) {
                                // installed() 此时已为 true ⇒ 本分支自动切到主界面；
                                // 自增 trigger 让上面的 LaunchedEffect 重跑，自动起 serve + 开会话。
                                ocProgress = ctx.getString(R.string.taiji_oc_install_done)
                                serveStartTrigger++
                            } else {
                                // 失败必须可见（项目原则）：留在引导页并给出原因
                                ocInstallFailed = true
                                ocProgress = ctx.getString(R.string.taiji_oc_install_failed, r.message)
                            }
                        }
                    }
                },
            )

            // 启动失败：明确原因 + 重试。**不静默失败**（项目原则）
            state.phase is TaijiPhase.Failed ->
                FailedPane((state.phase as TaijiPhase.Failed).message) {
                    scope.launch { repo.open(OcRuntime.scope, sessionId = null) }
                }

            // ★ 主界面：**左侧抽屉 + 固定顶栏** 结构。
            //   ModalNavigationDrawer 自带「左缘滑入 / 点遮罩关闭」；抽屉内容 = 历史会话列表。
            //   顶栏放在 Column 顶部、消息区用 weight(1f) 独立滚动 —— 顶栏因此天然不跟着滚。
            else -> ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet(
                        modifier = Modifier.width(300.dp),
                        drawerContainerColor = MaterialTheme.colorScheme.surface,
                    ) {
                        // 抽屉 = 上（可滚动的）会话列表 + 下（固定）OpenCode 版本条。
                        // 2026-10-08 用户反馈「opencode bionic 版没有更新入口」：入口原本只在
                        // 设置 → 环境更新 里，而太极是天天开的页面，所以抽屉底部也放一个。
                        // HistoryDrawer 内部是 Column(fillMaxSize)，故用 weight(1f) 让它只占
                        // 上半部，版本条固定在底部。
                        //
                        // 2026-10-08 UI 走查：外层 Column 加 navigationBarsPadding——手势导航
                        // 下底部「检查 OpenCode 更新」按钮会被导航条压住一截，加这个就让按钮浮
                        // 到导航条之上。M3 ModalDrawerSheet 默认不接管 navigationBars insets。
                        Column(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
                            HistoryDrawer(
                                sessions = sessions,
                                loading = sessionsLoading,
                                currentId = state.sessionId,
                                // 点击条目：关抽屉 → 保住 id（防转屏/切 Tab 丢会话）→ 切换会话
                                onPick = { id ->
                                    scope.launch { drawerState.close() }
                                    syncSession(id)
                                    scope.launch { repo.switchSession(OcRuntime.scope, id) }
                                },
                                onNew = {
                                    scope.launch { drawerState.close() }
                                    scope.launch { repo.startNewSession(OcRuntime.scope)?.let { syncSession(it) } }
                                },
                                onRefresh = { scope.launch { refreshSessions() } },
                                // 长按删除：二次确认在 HistoryDrawer 内部完成，这里只执行 + 同步列表
                                onDelete = { target ->
                                    val ok = repo.deleteSession(target.id)
                                    if (ok) {
                                        // ① 乐观移除：立刻从列表拿掉，不会出现"删完还挂在那儿"的观感
                                        sessions = sessions.filterNot { it.id == target.id }
                                        // ② 删掉的正是当前会话 → 立刻另起一个新会话，
                                        //    否则 UI 会停在"一个已不在服务端的会话"上（再发消息必错）
                                        if (target.id == state.sessionId) {
                                            syncSession(null)
                                            repo.startNewSession(OcRuntime.scope)?.let { syncSession(it) }
                                        }
                                        // ③ 再回源对齐（含刚建的新会话）。只做 ① 会留下缺口：
                                        //    "删当前会话 → 另起新会话"时新会话不在列表里、当前徽章消失
                                        //    （2026-10-07 真机验收发现），故必须回源一次。
                                        scope.launch { refreshSessions() }
                                    }
                                    ok
                                },
                                pluginCount = pluginCount,
                                onPlugins = {
                                    scope.launch { drawerState.close() }
                                    showPlugins = true
                                },
                                // 顶栏原「◼」的动作：结束本次会话（关 OpenCode 实例）+ 退出太极。
                                // 它和「停止生成」不是一回事，见 SessionBar KDoc。
                                onCloseSession = {
                                    // 收抽屉 → 关实例 → 退出，三件事在**同一个协程里顺序执行**：
                                    // 拆成两个 launch 时它们是并发跑的，抽屉还没收完就可能已经开始退出，
                                    // 视觉上像"确认弹窗还开着，界面突然没了"（2026-10-08 修复）。
                                    scope.launch {
                                        drawerState.close()
                                        repo.close()
                                        // 连单例一起丢掉：否则下次进太极会拿到"关过但还活着"的
                                        // 实例，open() 的判据会以为连接仍在（见 OcRuntime.shutdown）。
                                        OcRuntime.shutdown()
                                        onExit()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                            OcVersionFooter()
                        }
                    }
                },
            ) {
                Column(Modifier.fillMaxSize()) {
                    SessionBar(
                        title = currentTitle,
                        connection = state.connection,
                        attempt = state.reconnectAttempt,
                        // ☰ 历史：打开左侧抽屉并重拉列表（每次打开都重拉，保证看到最新会话）
                        onHistory = {
                            scope.launch { drawerState.open() }
                            scope.launch { refreshSessions() }
                        },
                        // ＋ 新会话：创建后切入，并同步 savedSession（防转屏/切 Tab 丢会话）
                        onNew = {
                            scope.launch {
                                repo.startNewSession(OcRuntime.scope)?.let { syncSession(it) }
                            }
                        },
                        currentModelText = modelOverride?.let { it.providerID + "/" + it.id }
                            ?: state.currentModel ?: stringResource(R.string.common_default),
                        onModelClick = {
                            // 2026-10-08：**先弹抽屉、再拉目录**（原来相反）。
                            // 原顺序是"拉完才弹"——用户在那一两秒里看到的是"点了没反应"，
                            // 于是普遍感觉"模型选择要手动刷新"（用户 2026-10-08 原话）。
                            // 现在抽屉立刻出现、内部显示「正在拉取模型目录…」，拉到就填上；
                            // 用户在等待期间至少能看到界面已经响应。
                            showModelSheet = true
                            modelsLoading = true
                            scope.launch {
                                repo.fetchModels(
                                    com.example.zhengdao.terminal.Workspace.hostDir(ctx).absolutePath
                                )
                                modelsLoading = false
                            }
                        },
                    )
                    ConnectionBanner(state, onDismiss = repo::dismissError)
                    // E-054：Agent 运行失败**必须**在连接正常时也看得见（连接横幅做不到这件事）
                    RunFailureBanner(
                        state = state,
                        onRetry = { scope.launch { repo.retryLastPrompt() } },
                        onDismiss = repo::dismissRunFailure,
                    )

                    Box(Modifier.weight(1f)) {
                        when {
                            // 尚未完成首载 → 转圈
                            state.messages.isEmpty() && !state.loadedOnce -> LoadingPane()
                            // 已就绪但空会话 → 引导文案（P1-1「首次进入显示引导」）
                            state.messages.isEmpty() -> EmptyConversationHint()
                            else -> MessageList(
                                messages = state.messages,
                                todos = state.todos,
                                isStreaming = state.isStreaming,
                                // 会话 id 进 key：切会话后重新定位到该会话底部（不沿用上一个会话的滚动位置）
                                sessionId = state.sessionId,
                                modifier = Modifier.fillMaxSize(),
                                listState = listState, // K2：外部注入的 state
                                // K2 配套：跟随标记与"已定位会话"也由外部注入（见本函数签名注释）
                                followState = followState,
                                positionedSession = positionedSession,
                                // 长按消息 → 弹操作面板（复制到剪贴板）
                                onLongPressMessage = { actionMessage = it },
                            )
                        }
                    }

                    ComposerBar(
                        input = state.input,
                        isStreaming = state.isStreaming,
                        enabled = state.sessionId != null,
                        // P2-2（§B 顺序 2）：草稿按会话存进 holder —— 切走再切回时灌回
                        //（见下面 LaunchedEffect(savedSession)）。repo 是进程级单例，草稿本该
                        // 一直在；但会话被换掉时它会被清空，所以这里自己留一份。
                        onInputChange = { t ->
                            repo.setInput(t)
                            TaijiStateHolder.saveDraft(savedSession, t)
                        },
                        onSend = {
                            // 失败必须看得见（C2 2026-10-08 升级）：失败时给"重试"入口，
                            // 让用户 1-tap 重新发同样内容。input 文字由 prompt 失败路径自然
                            // 保留（成功才清空），用户也可自己改完再发。
                            val text = state.input
                            scope.launch {
                                repo.prompt(text).onFailure { e ->
                                    // 人话化：把 Java 异常翻成短句（详见 HumanizeError.title）。
                                    val title = com.example.zhengdao.util.HumanizeError.title(e, ctx.resources)
                                    val r = snackbarHostState.showSnackbar(
                                        message = ctx.getString(R.string.taiji_send_failed, title),
                                        actionLabel = ctx.getString(R.string.common_retry),
                                        withDismissAction = true,
                                        duration = SnackbarDuration.Short,
                                    )
                                    if (r == SnackbarResult.ActionPerformed) {
                                        // 点"重试"——text 此刻可能已被用户改了（Snackbar 在屏时输入框可用），
                                        // 改了就用最新，没改就直接用原 text。**不**用最原始的 text 强行发。
                                        repo.prompt(state.input)
                                    }
                                }
                            }
                        },
                        onAbort = { scope.launch { repo.abort() } },
                    )
                }
            }
        }

        // 模型池选择器（v1.0 任务一）
        if (showModelSheet) {
            ModelSheet(
                models = state.models,
                override = modelOverride,
                currentModel = state.currentModel,
                isLoading = modelsLoading,
                // 失败原因（来自 OcRepository.fetchModels → TaijiState.lastModelFetchError）：
                // 必传，不给默认值——漏传就等于把「拉取失败」又变回静默，编译期拦下比运行时才发现强。
                error = state.lastModelFetchError,
                onSelectDefault = {
                    persistOverride(null)
                    showModelSheet = false
                },
                onSelect = { m ->
                    persistOverride(m)
                    showModelSheet = false
                    scope.launch { repo.setSessionModel(m) }
                },
                onReload = {
                    scope.launch {
                        modelsLoading = true
                        repo.fetchModels(
                            com.example.zhengdao.terminal.Workspace.hostDir(ctx).absolutePath
                        )
                        modelsLoading = false
                    }
                },
                onDismiss = { showModelSheet = false },
            )
        }

        // 插件面板（2026-10-08）：从抽屉进来，内容复用设置页那份 PluginsScreen。
        if (showPlugins) PluginsSheet(onDismiss = { showPlugins = false })

        // 长按消息的操作面板（2026-10-09 用户需求）。面板把文本拼好回传，这里只管
        // "写剪贴板 + 告诉用户结果"：复制失败也必须有提示，不能弹「已复制」骗人
        // （copyPlainText 返回 false 时文案明确说失败）。
        actionMessage?.let { msg ->
            MessageActionsSheet(
                message = msg,
                conversation = state.messages,
                onCopy = { label, text ->
                    actionMessage = null // 先关面板，Snackbar 才看得见
                    val ok = com.example.zhengdao.util.copyPlainText(ctx, label, text)
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            message = if (ok) ctx.getString(R.string.taiji_copied)
                            else ctx.getString(R.string.taiji_copy_failed),
                            withDismissAction = true,
                            duration = SnackbarDuration.Short,
                        )
                    }
                },
                onDismiss = { actionMessage = null },
            )
        }

        // ★ 权限批准：不可省。Agent 改文件时会发 permission.asked，不响应就卡死。
        state.pendingPermission?.let { perm ->
            PermissionSheet(perm) { allow, remember ->
                scope.launch { repo.respondPermission(perm.permissionId, allow, remember) }
            }
        }

        // C2：发送失败的 Snackbar 锚点。align BottomCenter 让它浮在输入框 + 底栏之上；
        // navigationBarsPadding 避免手势导航的横条压住 Snackbar 关闭按钮。
        // 不加 imePadding —— Snackbar 出现时键盘通常不在屏（用户已经松手了）。
        //
        // ★ E-058（2026-10-09）：**wrapContentHeight 不能省**。根 Box 是 fillMaxSize()，
        // 而 Modifier.fillMaxSize()（FillNode）会往下传「min = max = 全屏」的固定约束；
        // Box 的 measure policy 把 constraints.copyMaxDimensions() 交给子节点时**保留了 min**，
        // 于是 SnackbarHost 内部的 Box 被撑成整屏（真机实测 1312x2425），
        // Snackbar 就在那个 Box 里按默认 TopStart 摆放 —— 结果弹到屏幕顶部（压住顶栏）。
        // wrapContentHeight(Alignment.Bottom) 把 min 高度放回 0（WrapContentNode），
        // 宿主恢复成"裹住内容"，再由外层贴底。改这里时请一起看 ui/SettingsScreen.kt 的同款写法。
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .wrapContentHeight(Alignment.Bottom)
                .navigationBarsPadding(),
        )
    }
}
    }
// ── 占位面板 ──────────────────────────────────────────────────────────

@Composable
private fun LoadingPane() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            // 前导两空格是与转圈图标的视觉缩进，不随语言变（故与文案拼接而非写进资源）
            Text("  " + stringResource(R.string.taiji_connecting), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * 空会话引导（v1.1 第一阶段 P1-1「首次进入显示引导文案」）。
 *
 * 用户第一次进太极、或点「＋ 新会话」后的界面 —— 告诉一个完全不懂终端的人
 * "从哪开始"。这正是 v1.1「从能用升级为好用」的关键：不能只丢一个空白框。
 */
@Composable
private fun EmptyConversationHint() {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "☯",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(R.string.taiji_start_new),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                stringResource(R.string.taiji_start_new_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun NotInstalledPane(
    onExit: () -> Unit,
    installing: Boolean = false,
    progress: String = "",
    // 失败态由调用方的显式标志给出，不从 progress 文案前缀推断（i18n：见 ocInstallFailed 注释）
    failed: Boolean = false,
    onInstall: () -> Unit = {},
) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.taiji_oc_not_installed_title), style = MaterialTheme.typography.titleMedium)
            Text(
                if (installing || failed) progress
                else stringResource(R.string.taiji_oc_install_hint),
                style = MaterialTheme.typography.bodySmall,
                color = if (failed) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (installing) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(top = 16.dp).size(28.dp),
                    strokeWidth = 3.dp,
                )
            }
            OutlinedButton(
                onClick = onInstall,
                enabled = !installing,
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Text(
                    when {
                        installing -> stringResource(R.string.taiji_oc_installing)
                        failed -> stringResource(R.string.taiji_oc_retry_install)
                        else -> stringResource(R.string.taiji_oc_download_install)
                    }
                )
            }
            OutlinedButton(onClick = onExit, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.common_back))
            }
        }
    }
}

@Composable
private fun FailedPane(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.taiji_oc_start_failed), style = MaterialTheme.typography.titleMedium)
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                stringResource(R.string.taiji_oc_auth_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            OutlinedButton(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
                Text(stringResource(R.string.common_retry))
            }
        }
    }
}

/**
 * 抽屉底部：OpenCode 内置版版本 + 检查更新入口（2026-10-08）。
 *
 * 为什么放在这里：用户反馈「opencode bionic 版没有更新入口」——入口原本只在
 * 设置 → 环境更新 里，而太极是天天开的页面。
 *
 * 为什么不用 [OcManager.checkUpdate]：旧实现把「网络不通 / 接口报错」和「已是最新」
 * 都返回 null，UI 只能一律显示"已是最新"（**假好消息**，用户点一下什么都不发生）。
 * 这里用 [OcManager.checkUpdateDetailed] 把三种结果分开，失败原因如实显示。
 */
@Composable
private fun OcVersionFooter() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<OcManager.UpdateInfo?>(null) }
    var installed by remember { mutableStateOf(OcManager.installedVersion(ctx)) }

    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Text(
            stringResource(
                R.string.taiji_oc_version_line,
                installed ?: stringResource(R.string.common_not_installed),
                OcManager.VERSION,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val isInstalled = installed != null
        OutlinedButton(
            enabled = !checking,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            onClick = {
                checking = true
                scope.launch {
                    // 未安装属异常态（OpenCode 随 APK 内置、开箱即用）：直接装，不谈"更新"。
                    // 设置页那处入口 2026-10-08 已按用户要求撤掉（主打不是 OpenCode），
                    // 这里是唯一的更新 / 重装入口。
                    if (!isInstalled) {
                        val r = withContext(Dispatchers.IO) { OcManager.downloadAndInstall(ctx) { } }
                        checking = false
                        installed = OcManager.installedVersion(ctx)
                        Toast.makeText(ctx, r.message, Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    val chk = withContext(Dispatchers.IO) { OcManager.checkUpdateDetailed(ctx) }
                    checking = false
                    when (chk) {
                        is OcManager.UpdateCheck.Available -> pending = chk.info
                        OcManager.UpdateCheck.UpToDate -> Toast.makeText(
                            ctx,
                            ctx.getString(
                                R.string.taiji_oc_up_to_date,
                                installed ?: OcManager.VERSION,
                            ),
                            Toast.LENGTH_SHORT,
                        ).show()
                        is OcManager.UpdateCheck.Failed -> Toast.makeText(
                            ctx,
                            ctx.getString(R.string.taiji_oc_check_failed, chk.reason),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            },
        ) {
            Text(
                when {
                    checking -> stringResource(R.string.taiji_oc_processing)
                    isInstalled -> stringResource(R.string.taiji_oc_check_update)
                    else -> stringResource(R.string.taiji_oc_install)
                }
            )
        }
    }

    // 有新版：弹确认（digest 缺失时明确说明为何不给直装 —— 不静默降级）
    pending?.let { info ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.taiji_oc_found_new, info.version)) },
            text = {
                Text(
                    if (info.sha256 == null) {
                        stringResource(
                            R.string.taiji_oc_no_digest,
                            installed ?: stringResource(R.string.common_not_installed),
                            info.version,
                        )
                    } else {
                        stringResource(
                            R.string.taiji_oc_digest_ok,
                            installed ?: stringResource(R.string.common_not_installed),
                            info.version,
                        )
                    }
                )
            },
            confirmButton = {
                TextButton(
                    enabled = info.sha256 != null,
                    onClick = {
                        pending = null
                        checking = true
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                OcManager.downloadAndInstall(ctx, info) { }
                            }
                            checking = false
                            installed = OcManager.installedVersion(ctx)
                            Toast.makeText(ctx, r.message, Toast.LENGTH_LONG).show()
                        }
                    },
                ) { Text(stringResource(R.string.taiji_oc_download_install)) }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text(stringResource(R.string.common_later)) }
            },
        )
    }
}