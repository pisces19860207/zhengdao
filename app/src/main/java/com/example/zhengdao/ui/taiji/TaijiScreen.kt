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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import com.example.zhengdao.oc.OcClient
import com.example.zhengdao.oc.OcManager
import com.example.zhengdao.oc.OcModel
import com.example.zhengdao.oc.ConnectionState
import com.example.zhengdao.oc.OcMessage
import com.example.zhengdao.oc.OcPart
import com.example.zhengdao.oc.OcRepository
import com.example.zhengdao.oc.OcSessionSummary
import com.example.zhengdao.oc.SseClient
import com.example.zhengdao.oc.TaijiPhase
import com.example.zhengdao.oc.TaijiState
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
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // ⚠️ 网络层用 remember 而非 rememberSaveable：连接不应在配置变更（旋转）时重建。
    //    Compose state 是唯一真相；进程被杀后由 open() 从 serve 恢复。
    val repo = remember {
        val client = OcClient(passwordProvider = { OcManager.servePassword })
        OcRepository(client, SseClient(client.sseClient))
    }
    // 用项目已有的 collectAsState，避免为collectAsStateWithLifecycle 引入 lifecycle-runtime-compose
    val state by repo.state.collectAsState()

    // 🔺 会话 id 必须跨配置变更保存。
    //    转屏 / 切 Tab 回来时 Activity 重建、Repository 也重建；若每次都传 null 就会
    //    **新建一个会话**，历史消息全丢（表现："转一下屏聊天记录没了"）。
    //    Repository 重建后会自动全量拉取，所以只要 id 保住，内容就能完整恢复。
    var savedSession by rememberSaveable { mutableStateOf<String?>(null) }

    // ── v1.1 第一阶段：会话完整化 ─────────────────────────────────────
    // 历史列表内容 + 左侧抽屉的开合。刻意留在 UI 层局部状态，不进 Repository
    // —— 「会话数据」才是 Repository 的职责，「抽屉开没开」是纯 UI 关注点。
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var sessions by remember { mutableStateOf<List<OcSessionSummary>>(emptyList()) }
    var sessionsLoading by remember { mutableStateOf(false) }

    // 拉取历史会话（打开抽屉 / 手动刷新 / 删除后 共用同一实现）
    suspend fun refreshSessions() {
        sessionsLoading = true
        sessions = repo.listSessions()
        sessionsLoading = false
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
    // 装成功后自增 → 让下面的 LaunchedEffect 重跑一次（起 serve + 开会话）。
    // 只在成功时自增，故不会重复开会话。
    var serveStartTrigger by remember { mutableStateOf(0) }

    // 首次进入：确保 serve 在跑，再打开会话（复用上次的会话 id）
    LaunchedEffect(serveStartTrigger) {
        if (!OcManager.installed(ctx)) return@LaunchedEffect      // 未装：显示引导
        // serveRunning()/startServe() 都是阻塞的（裸 HttpURLConnection + 起进程），必须切 IO
        withContext(Dispatchers.IO) {
            // ⚠️ 竞态修复：**总是**调 startServe(ctx)，不再用 `if (!serveRunning())` 前置拦截。
            //    startServe 本身幂等：serve 已在运行（典型：重装/重启后上一进程的孤儿仍在
            //    监听）时，它会解析密码并写入 OcManager.servePassword 后返回。
            //    原写法在"孤儿 serve 存活"时直接跳过整个分支 → servePassword 恒为 null
            //    → 请求无 Authorization → 全程 401（本次「进不了 UI」的触发路径）。
            OcManager.startServe(ctx)   // 幂等：运行中则解析密码，未运行则拉起
        }
        repo.open(scope, savedSession)
        savedSession = repo.state.value.sessionId ?: savedSession
        // 重启后重放模型覆盖：复用的会话重新 POST 一次（幂等，仅影响当前会话）
        modelOverride?.let { m ->
            repo.state.value.sessionId?.let { repo.setSessionModel(m) }
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            // 未安装：给就地安装入口（丹房过滤了 OpenCode，v1.1.1 起这里是唯一入口——
            // 原文案"去丹房安装"是死循环回归）。缓存命中时约 1 分钟（含校验+解压）
            !OcManager.installed(ctx) -> NotInstalledPane(
                onExit = onExit,
                installing = ocInstalling,
                progress = ocProgress,
                onInstall = {
                    ocInstalling = true
                    ocProgress = "准备中…"
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
                                ocProgress = "安装完成，正在启动 OpenCode…"
                                serveStartTrigger++
                            } else {
                                // 失败必须可见（项目原则）：留在引导页并给出原因
                                ocProgress = "安装失败：${r.message}"
                            }
                        }
                    }
                },
            )

            // 启动失败：明确原因 + 重试。**不静默失败**（项目原则）
            state.phase is TaijiPhase.Failed ->
                FailedPane((state.phase as TaijiPhase.Failed).message) {
                    scope.launch { repo.open(scope, sessionId = null) }
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
                        Column(modifier = Modifier.fillMaxSize()) {
                            HistoryDrawer(
                                sessions = sessions,
                                loading = sessionsLoading,
                                currentId = state.sessionId,
                                // 点击条目：关抽屉 → 保住 id（防转屏/切 Tab 丢会话）→ 切换会话
                                onPick = { id ->
                                    scope.launch { drawerState.close() }
                                    savedSession = id
                                    scope.launch { repo.switchSession(scope, id) }
                                },
                                onNew = {
                                    scope.launch { drawerState.close() }
                                    scope.launch { repo.startNewSession(scope)?.let { savedSession = it } }
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
                                            savedSession = null
                                            repo.startNewSession(scope)?.let { savedSession = it }
                                        }
                                        // ③ 再回源对齐（含刚建的新会话）。只做 ① 会留下缺口：
                                        //    "删当前会话 → 另起新会话"时新会话不在列表里、当前徽章消失
                                        //    （2026-10-07 真机验收发现），故必须回源一次。
                                        scope.launch { refreshSessions() }
                                    }
                                    ok
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
                                repo.startNewSession(scope)?.let { savedSession = it }
                            }
                        },
                        currentModelText = modelOverride?.let { it.providerID + "/" + it.id }
                            ?: state.currentModel ?: "默认",
                        onModelClick = {
                            scope.launch {
                                modelsLoading = true
                                repo.fetchModels(
                                    com.example.zhengdao.terminal.Workspace.hostDir(ctx).absolutePath
                                )
                                modelsLoading = false
                                showModelSheet = true
                            }
                        },
                        onStop = { scope.launch { repo.close(); onExit() } },
                    )
                    ConnectionBanner(state, onDismiss = repo::dismissError)

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
                            )
                        }
                    }

                    ComposerBar(
                        input = state.input,
                        isStreaming = state.isStreaming,
                        enabled = state.sessionId != null,
                        onInputChange = repo::setInput,
                        onSend = { scope.launch { repo.prompt(state.input) } },
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

        // ★ 权限批准：不可省。Agent 改文件时会发 permission.asked，不响应就卡死。
        state.pendingPermission?.let { perm ->
            PermissionSheet(perm) { allow, remember ->
                scope.launch { repo.respondPermission(perm.permissionId, allow, remember) }
            }
        }
    }
}

// ── 占位面板 ──────────────────────────────────────────────────────────

@Composable
private fun LoadingPane() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text("  正在连接 OpenCode…", style = MaterialTheme.typography.bodyMedium)
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
                "开始一段新对话",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                "在下方输入你的任务，例如「帮我看看下载文件夹里有什么」",
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
    onInstall: () -> Unit = {},
) {
    val failed = progress.startsWith("安装失败")
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("尚未安装 OpenCode", style = MaterialTheme.typography.titleMedium)
            Text(
                if (installing || failed) progress
                else "点下方按钮下载安装（约 65MB，已有缓存则约 1 分钟）",
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
                Text(if (installing) "安装中…" else if (failed) "重试安装" else "下载并安装")
            }
            OutlinedButton(onClick = onExit, modifier = Modifier.padding(top = 8.dp)) {
                Text("返回")
            }
        }
    }
}

@Composable
private fun FailedPane(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("OpenCode 启动失败", style = MaterialTheme.typography.titleMedium)
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                "若提示鉴权失败，说明 serve 密码未就绪——可重启 App 再试",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            OutlinedButton(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
                Text("重试")
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
            "OpenCode ${installed ?: "未安装"}（出厂 ${OcManager.VERSION}）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            enabled = !checking,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            onClick = {
                checking = true
                scope.launch {
                    val chk = withContext(Dispatchers.IO) { OcManager.checkUpdateDetailed(ctx) }
                    checking = false
                    when (chk) {
                        is OcManager.UpdateCheck.Available -> pending = chk.info
                        OcManager.UpdateCheck.UpToDate -> Toast.makeText(
                            ctx,
                            "OpenCode 已是最新（${installed ?: OcManager.VERSION}）",
                            Toast.LENGTH_SHORT,
                        ).show()
                        is OcManager.UpdateCheck.Failed -> Toast.makeText(
                            ctx,
                            "检查更新失败：${chk.reason}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            },
        ) { Text(if (checking) "检查中…" else "检查 OpenCode 更新") }
    }

    // 有新版：弹确认（digest 缺失时明确说明为何不给直装 —— 不静默降级）
    pending?.let { info ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("发现 OpenCode ${info.version}") },
            text = {
                Text(
                    if (info.sha256 == null) {
                        "当前 $installed → ${info.version}。该 release 未提供 SHA256 digest，" +
                            "为安全起见不提供 App 内直装 —— 可到 GitHub 手动下载。"
                    } else {
                        "当前 $installed → ${info.version}。将从 GitHub 下载约 65MB 并校验 digest，" +
                            "通过后覆盖安装。安装完成后重启 App 生效。"
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
                ) { Text("下载并安装") }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text("稍后") }
            },
        )
    }
}