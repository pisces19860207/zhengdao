// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据的公开接口：Jetpack Compose 官方 API、OpenCode 官方 serve 模式。
package com.example.zhengdao.ui.taiji

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
    // 历史抽屉的开关与内容。刻意留在 UI 层局部状态，不进 Repository
    // —— 「会话数据」才是 Repository 的职责，「抽屉开没开」是纯 UI 关注点。
    var showHistory by remember { mutableStateOf(false) }
    var sessions by remember { mutableStateOf<List<OcSessionSummary>>(emptyList()) }
    var sessionsLoading by remember { mutableStateOf(false) }

    // 顶部标题：优先历史列表里服务端给的 title，否则回退「首条用户消息前 20 字」
    // （计划 P1-4：**不调模型生成标题**）。两者都取不到则为空 → SessionBar 显示「新会话」。
    val currentTitle = remember(state.messages, state.sessionId, sessions) {
        sessions.firstOrNull { it.id == state.sessionId }?.title?.takeIf { it.isNotBlank() }
            ?: state.messages.firstOrNull { it.role == OcMessage.Role.USER }
                ?.parts?.filterIsInstance<OcPart.Text>()?.firstOrNull()?.text
                ?.lineSequence()?.firstOrNull()?.trim()?.take(20)?.takeIf { it.isNotBlank() }
            ?: ""
    }

    // 首次进入：确保 serve 在跑，再打开会话（复用上次的会话 id）
    LaunchedEffect(Unit) {
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
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            // 未安装：引导下载（复用既有文案，不重复实现下载流程）
            !OcManager.installed(ctx) -> NotInstalledPane(onExit)

            // 启动失败：明确原因 + 重试。**不静默失败**（项目原则）
            state.phase is TaijiPhase.Failed ->
                FailedPane((state.phase as TaijiPhase.Failed).message) {
                    scope.launch { repo.open(scope, sessionId = null) }
                }

            else -> Column(Modifier.fillMaxSize()) {
                SessionBar(
                    title = currentTitle,
                    connection = state.connection,
                    attempt = state.reconnectAttempt,
                    // ☰ 历史：打开抽屉并拉取列表（每次打开都重拉，保证看到最新会话）
                    onHistory = {
                        showHistory = true
                        scope.launch {
                            sessionsLoading = true
                            sessions = repo.listSessions()
                            sessionsLoading = false
                        }
                    },
                    // ＋ 新会话：创建后切入，并同步 savedSession（防转屏/切 Tab 丢会话）
                    onNew = {
                        scope.launch {
                            repo.startNewSession(scope)?.let { savedSession = it }
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

        // ★ 权限批准：不可省。Agent 改文件时会发 permission.asked，不响应就卡死。
        state.pendingPermission?.let { perm ->
            PermissionSheet(perm) { allow, remember ->
                scope.launch { repo.respondPermission(perm.permissionId, allow, remember) }
            }
        }

        // ☰ 历史会话抽屉（v1.1 第一阶段）：点击条目即恢复该会话
        if (showHistory) {
            HistorySheet(
                sessions = sessions,
                loading = sessionsLoading,
                currentId = state.sessionId,
                onPick = { id ->
                    showHistory = false
                    savedSession = id               // 保住 id，防转屏/切 Tab 丢会话
                    scope.launch { repo.switchSession(scope, id) }
                },
                onDismiss = { showHistory = false },
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
private fun NotInstalledPane(onExit: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("尚未安装 OpenCode", style = MaterialTheme.typography.titleMedium)
            Text(
                "请在「丹房」下载安装后回到本页",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            OutlinedButton(onClick = onExit, modifier = Modifier.padding(top = 16.dp)) {
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