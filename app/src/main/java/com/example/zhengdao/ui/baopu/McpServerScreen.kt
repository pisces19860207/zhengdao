// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第二百一十六批 · MCP OAuth 2.1：**服务器清单界面**（派工单 §2.5）。
//
// 从「一个输入框」变成「一台服务器一行」：
//   名称 / 主机 / 授权状态 / 工具数 / 最后连接
//   行内：连接·重新授权 / 断开（清 token）/ 删除（二次确认）/ 查看工具
//   顶栏：全部断开
//
// 三条界面纪律（都是这一批的判据）：
//   1. **授权状态必须真实**：过期就写"已过期"、被撤销就写"已撤销"、不支持 OAuth 就写
//      "不支持 OAuth"——绝不把旧式服务器显示成"已连接"；
//   2. **失败要可读**：不支持 OAuth 的服务器点连接只给说明，不静默失败；
//   3. **界面上永远看不到 token**：这里只有 [McpAuthStatus] 枚举，没有凭据字段可显示。
//
// 授权页一律交给**系统浏览器**（`ACTION_VIEW` + BROWSABLE）：本文件不出现 WebView，
// 回调走 `zhengdao://mcp/oauth` 深链（见 McpAuthInbox / IncomingIntentRouter）。
package com.example.zhengdao.ui.baopu

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.zhengdao.R
import com.example.zhengdao.baopu.BaopuRuntime
import com.example.zhengdao.baopu.mcp.McpAuthStatus
import com.example.zhengdao.baopu.mcp.McpClipboardToken
import com.example.zhengdao.baopu.mcp.McpConnectOutcome
import com.example.zhengdao.baopu.mcp.McpPasteRule
import com.example.zhengdao.baopu.mcp.McpProbeFailure
import com.example.zhengdao.baopu.mcp.McpProbeResult
import com.example.zhengdao.baopu.mcp.McpSelfService
import com.example.zhengdao.baopu.mcp.McpServerDraft
import com.example.zhengdao.baopu.mcp.McpServerStatus
import com.example.zhengdao.baopu.mcp.McpServerView
import com.example.zhengdao.keepalive.CoroutineCrashHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.zhengdao.core.TimeFormats
import com.example.zhengdao.util.clearPrimaryClip
import com.example.zhengdao.util.readPrimaryClip

/** 面板上的一条提示（成功＝青、失败＝朱；两色都取主题，不新增色板）。 */
private data class McpNotice(val ok: Boolean, val text: String)

/** 一次连接动作的收尾（新建与重连共用一份，避免两处文案漂移）。 */
private data class McpConnectResult(
    val outcome: McpConnectOutcome,
    val rows: List<McpServerView>,
    /** false ＝ 这个地址之前就登记过（addServer 拒收重复）。 */
    val isNew: Boolean,
    /** 第五百零二批：这一下动的是哪台（自动 B 的弹窗台账按它记账）。 */
    val serverId: String,
)

@Composable
internal fun McpServerScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    // Compose 里取资源走 LocalResources（lint: LocalContextGetResourceValueCall，configuration-aware）
    val res = LocalResources.current
    val scope = rememberCoroutineScope { CoroutineCrashHandler }
    var url by remember { mutableStateOf("") }
    var clientId by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<McpServerView>>(emptyList()) }
    /** 正在忙的 server id（新建时是待登记那条的 id）；null ＝ 空闲。 */
    var busyId by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<McpNotice?>(null) }
    var pendingDelete by remember { mutableStateOf<McpServerView?>(null) }
    var pendingDisconnect by remember { mutableStateOf<McpServerView?>(null) }
    var viewingTools by remember { mutableStateOf<McpServerView?>(null) }
    var toolNames by remember { mutableStateOf<List<String>>(emptyList()) }
    /** 630：查看工具清单里被钉住的原名集（面板开关的镜像；真账在 McpBudgetStore）。 */
    var pinnedNames by remember { mutableStateOf<Set<String>>(emptySet()) }
    /** 502 自动 C：URL 框里一旦是合法地址就提示「名称/传输」（纯文本，不是新入口）。 */
    var parseHint by remember { mutableStateOf<String?>(null) }

    /** 授权回调（浏览器回来时投递到这里；null ＝ 没有待处理的）。 */
    val callback by McpAuthInbox.pendingState

    // 502：面板在组合里 ⇒ 回调归面板（见 McpAuthInbox.panelActive 的路由注释）。
    DisposableEffect(Unit) {
        McpAuthInbox.panelActive = true
        onDispose { McpAuthInbox.panelActive = false }
    }

    // 打开面板：把盘里存下的配置登记回来（**只登记、不联网**）；幂等，重开不会重复。
    LaunchedEffect(Unit) {
        rows = withContext(Dispatchers.IO) {
            val source = BaopuRuntime.mcp(context)
            source.restoreFromStore()
            source.snapshot()
        }
    }

    // 授权回调：换 token（IO）→ 自动重连那一台 → 刷新列表。
    // 只消费一次（按引用比对），组合重放不会重复换 token。
    //
    // ⚠️ 真机踩到的坑（2026-10-26）：**消费回调会把 callback 置 null ⇒ 本 effect 的 key
    // 变化 ⇒ 自己被取消**，于是 `busyId` 永远停在"连接中…"（日志里其实已经连上了）。
    // 所以真正干活的协程挂在 rememberCoroutineScope 上（它不随 key 变而取消），
    // 本 effect 只负责"看到回调 → 领活 → 交出去"。
    LaunchedEffect(callback) {
        val cb = callback ?: return@LaunchedEffect
        if (!McpAuthInbox.consume(cb)) return@LaunchedEffect
        // 干活挂在 rememberCoroutineScope 上（不随 key 变而取消），见上面那段坑的说明。
        scope.launch {
            busyId = ""
            notice = null
            val outcome = withContext(Dispatchers.IO) {
                val flow = BaopuRuntime.mcpAuth(context)
                val source = BaopuRuntime.mcp(context)
                runCatching {
                    val done = flow.complete(cb)
                    // 502：授权闭环 ⇒ 自动 B 的本 episode 结束（下次过期重授还能再弹）。
                    McpSelfService.popupLedger.forget(done.serverId)
                    // 授权完成 ≠ 连上：还要 initialize + tools/list 才算可用。
                    done.serverId to source.connect(done.serverId)
                }
            }
            busyId = null
            notice = outcome.fold(
                onSuccess = { (_, result) ->
                    when (result) {
                        is McpConnectOutcome.Ok ->
                            McpNotice(true, res.getString(R.string.baopu_mcp_oauth_auth_ok))
                        is McpConnectOutcome.NeedsAuth -> McpNotice(
                            false,
                            res.getString(R.string.baopu_mcp_oauth_auth_failed, result.message),
                        )
                        is McpConnectOutcome.Failed -> McpNotice(
                            false,
                            res.getString(R.string.baopu_mcp_oauth_auth_failed, result.message),
                        )
                    }
                },
                onFailure = { e ->
                    // 502：浏览器里明确拒绝 ⇒ 记台账（自动 B 从此不再为这台弹页）。
                    val authEx = e as? com.example.zhengdao.baopu.mcp.McpAuthException
                    if (authEx?.failure == com.example.zhengdao.baopu.mcp.McpAuthFailure.AUTHORIZATION_DENIED) {
                        authEx.serverId?.let { McpSelfService.popupLedger.markDenied(it) }
                    }
                    // 失败一律**明说**（state 不匹配 / 换 token 失败），不静默。
                    McpNotice(
                        false,
                        res.getString(
                            R.string.baopu_mcp_oauth_auth_failed,
                            authEx?.userMessage ?: e.javaClass.simpleName,
                        ),
                    )
                },
            )
            rows = withContext(Dispatchers.IO) { BaopuRuntime.mcp(context).snapshot() }
        }
    }

    /** 把授权页交给系统浏览器（**不嵌 WebView**）。 */
    fun openAuthorization(authUrl: String) {
        val intent = Intent(Intent.ACTION_VIEW, authUrl.toUri())
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            notice = McpNotice(true, res.getString(R.string.baopu_mcp_oauth_browser_opened))
        } catch (e: ActivityNotFoundException) {
            notice = McpNotice(false, res.getString(R.string.baopu_mcp_oauth_no_browser))
        }
    }

    /** 结果 → 状态 + 人话（成功清输入框；失败把原因留在提示里，配置还留着可重试）。 */
    fun finish(result: McpConnectResult, clearInput: Boolean) {
        rows = result.rows
        busyId = null
        notice = when (val outcome = result.outcome) {
            is McpConnectOutcome.Ok -> {
                // 502：连接成功 ⇒ 自动 B 的本 episode 闭环（过期重授还能再弹）。
                McpSelfService.popupLedger.forget(result.serverId)
                if (clearInput) {
                    url = ""
                    clientId = ""
                    parseHint = null
                }
                val head = if (outcome.toolCount > 0) {
                    res.getQuantityString(
                        R.plurals.baopu_mcp_connect_ok,
                        outcome.toolCount,
                        outcome.toolCount,
                    )
                } else {
                    res.getString(R.string.baopu_mcp_connect_ok_none)
                }
                val extra = ArrayList<String>(outcome.warnings.size + 1)
                if (!result.isNew) extra += res.getString(R.string.baopu_mcp_duplicate)
                outcome.warnings.forEach { extra += res.getString(R.string.baopu_mcp_hint_warn, it) }
                McpNotice(true, if (extra.isEmpty()) head else (listOf(head) + extra).joinToString("\n"))
            }
            is McpConnectOutcome.NeedsAuth -> {
                // 不是失败：这是 OAuth 的正常一步。配置已经登记好了（addServer 在前）。
                // 502：这次弹页记进台账（自动 B「只弹一次」的 episode 开始）。
                McpSelfService.popupLedger.markShown(result.serverId)
                if (clearInput) {
                    url = ""
                    clientId = ""
                    parseHint = null
                }
                openAuthorization(outcome.authorizationUrl)
                // step-up（insufficient_scope）时把"缺哪个权限"单独说一句——用户才知道要批什么。
                val head = outcome.missingScope?.let {
                    res.getString(R.string.baopu_mcp_oauth_scope_missing, it)
                } ?: outcome.message
                McpNotice(true, head)
            }
            is McpConnectOutcome.Failed -> McpNotice(
                false,
                res.getString(R.string.baopu_mcp_connect_failed, outcome.message),
            )
        }
    }

    /** 探针结论 → 一句具体的话（不出现任何头、体、token——它们压根没被读）。 */
    fun probeNotice(name: String, result: McpProbeResult?): McpNotice {
        val failure = result?.failure
        if (failure != null) {
            val text = when (failure) {
                McpProbeFailure.BAD_URL -> res.getString(R.string.baopu_mcp_probe_bad_url, name)
                McpProbeFailure.DNS -> res.getString(R.string.baopu_mcp_probe_dns, name)
                McpProbeFailure.TLS -> res.getString(R.string.baopu_mcp_probe_tls, name)
                McpProbeFailure.TIMEOUT -> res.getString(R.string.baopu_mcp_probe_timeout, name)
                McpProbeFailure.REFUSED -> res.getString(R.string.baopu_mcp_probe_refused, name)
                McpProbeFailure.NETWORK -> res.getString(R.string.baopu_mcp_probe_network, name)
            }
            return McpNotice(false, text)
        }
        val code = result?.statusCode
            ?: return McpNotice(false, res.getString(R.string.baopu_mcp_probe_unknown, name))
        return when {
            code == 401 && result.discoveryFound ->
                McpNotice(true, res.getString(R.string.baopu_mcp_probe_auth_discovery, name))
            // 606③：discovery 探测根本没问到（DNS/TLS/超时）——不许说「没找到授权服务器」。
            code == 401 && result.discoveryProbeFailed ->
                McpNotice(true, res.getString(R.string.baopu_mcp_probe_auth_probe_error, name))
            code == 401 -> McpNotice(true, res.getString(R.string.baopu_mcp_probe_auth, name))
            code in 200..299 -> McpNotice(true, res.getString(R.string.baopu_mcp_probe_ok, name, code))
            // 606①（602 落点：测试连接 4xx 不再一律「HTTP %d」）：404→端点；其余 4xx→协议。
            code == 404 -> McpNotice(false, res.getString(R.string.baopu_mcp_probe_endpoint, name))
            code in 400..499 -> McpNotice(false, res.getString(R.string.baopu_mcp_probe_protocol, name, code))
            else -> McpNotice(false, res.getString(R.string.baopu_mcp_probe_http, name, code))
        }
    }

    /**
     * 245 B3「测试连接」：只探 discovery 与一次未授权请求（看是否 401）。
     *
     * 与连接**不是**同一条路：不登记、不建会话、不碰 token；失败给具体原因。
     */
    fun probeServer(target: McpServerView) {
        if (busyId != null) return
        busyId = target.id
        notice = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { BaopuRuntime.mcp(context).probe(target.id) }.getOrNull()
            }
            busyId = null
            notice = probeNotice(target.name, result)
        }
    }

    /**
     * 513「从剪贴板读取」：读一次剪贴板 → 形状闸 → 直接进保险柜 → 尽力清空剪贴板。
     * 令牌不进对话/日志/回执（提示语只有结果，没有内容）；拒收时剪贴板原样不动。
     * 读与清都在主线程当下做（Android 10+ 只许前台读；面板开着就是前台），落库在 IO。
     */
    fun importFromClipboard(target: McpServerView) {
        if (busyId != null) return
        val clip = readPrimaryClip(context)
        busyId = target.id
        notice = null
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                val cfg = BaopuRuntime.mcp(context).config(target.id)
                if (cfg == null) null else McpClipboardToken.importText(BaopuRuntime.mcpAuth(context), cfg, clip)
            }
            busyId = null
            when (outcome) {
                null -> notice = McpNotice(false, res.getString(R.string.baopu_mcp_clipboard_no_server, target.name))
                is McpClipboardToken.Outcome.Rejected -> notice = McpNotice(
                    false,
                    res.getString(
                        when (outcome.reason) {
                            McpClipboardToken.Reason.EMPTY -> R.string.baopu_mcp_clipboard_empty
                            McpClipboardToken.Reason.SHAPE -> R.string.baopu_mcp_clipboard_shape
                            McpClipboardToken.Reason.IMPORT -> R.string.baopu_mcp_clipboard_import_failed
                        },
                        target.name,
                    ),
                )
                is McpClipboardToken.Outcome.Saved -> {
                    // 判据④：清完再读一次复核；清不掉就明说（不假成功）。
                    val cleared = clearPrimaryClip(context) && readPrimaryClip(context).isNullOrEmpty()
                    notice = McpNotice(
                        true,
                        res.getString(
                            if (cleared) R.string.baopu_mcp_clipboard_saved
                            else R.string.baopu_mcp_clipboard_saved_not_cleared,
                            target.name,
                        ),
                    )
                    rows = withContext(Dispatchers.IO) { BaopuRuntime.mcp(context).snapshot() }
                }
            }
        }
    }

    /** 点「添加并授权」：造配置 → 登记（写盘）→ 连接（无 token 时会自动转授权）。 */
    fun connectNew() {
        if (busyId != null) return
        when (val draft = McpServerDraft.fromUrl(url, clientId)) {
            is McpServerDraft.Result.Invalid ->
                notice = McpNotice(false, res.getString(R.string.baopu_mcp_url_invalid, draft.message))

            is McpServerDraft.Result.Ok -> {
                // 502：用户点「添加并授权」＝显式要连 ⇒ 进启动自动连名单（下次开机自动回）。
                val config = draft.config.copy(autoConnect = true)
                busyId = config.id
                notice = null
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        val source = BaopuRuntime.mcp(context)
                        val added = source.addServer(config)
                        McpConnectResult(
                            outcome = source.connect(config.id),
                            rows = source.snapshot(),
                            isNew = added,
                            serverId = config.id,
                        )
                    }
                    finish(result, clearInput = true)
                }
            }
        }
    }

    /** 行上的「连接／重新授权」：配置已经在管理器里（id 就是 key），不重新拼地址。 */
    fun reconnect(target: McpServerView) {
        if (busyId != null) return
        busyId = target.id
        notice = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val source = BaopuRuntime.mcp(context)
                McpConnectResult(
                    outcome = source.connect(target.id),
                    rows = source.snapshot(),
                    isNew = true,
                    serverId = target.id,
                )
            }
            finish(result, clearInput = false)
        }
    }

    /** 断开：注销工具、清授权、**清 token**，配置留着可重连。 */
    fun disconnect(target: McpServerView) {
        if (busyId != null) return
        busyId = target.id
        scope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                val source = BaopuRuntime.mcp(context)
                source.disconnect(target.id)
                source.snapshot()
            }
            rows = snapshot
            busyId = null
            notice = McpNotice(true, res.getString(R.string.baopu_mcp_disconnected, target.name))
        }
    }

    /** 全部断开：清所有 token，不删配置。 */
    fun disconnectAll() {
        if (busyId != null) return
        busyId = ""
        scope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                val source = BaopuRuntime.mcp(context)
                source.disconnectAll()
                source.snapshot()
            }
            rows = snapshot
            busyId = null
            notice = McpNotice(true, res.getString(R.string.baopu_mcp_oauth_revoked_all, rows.size))
        }
    }

    /** 删除：断连 + 删盘上的记录 + 清 token/会话（确认卡在下一层 AlertDialog）。 */
    fun remove(target: McpServerView) {
        if (busyId != null) return
        busyId = target.id
        scope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                val source = BaopuRuntime.mcp(context)
                source.removeServer(target.id)
                source.snapshot()
            }
            rows = snapshot
            busyId = null
            notice = McpNotice(true, res.getString(R.string.baopu_mcp_removed, target.name))
        }
    }

    /** 查看工具（只读清单 ＋ 630 的 pin 开关）。 */
    fun viewTools(target: McpServerView) {
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val source = BaopuRuntime.mcp(context)
                val names = source.toolNames(target.id)
                val pins = source.pinnedRemoteNames(target.id)
                names to pins
            }
            toolNames = loaded.first
            pinnedNames = loaded.second
            viewingTools = target
        }
    }

    /** 钉住/取消一个工具（630：装配必带的那批；真账落 prefs，本地镜像即时刷新）。 */
    fun togglePin(serverId: String, remoteName: String) {
        scope.launch {
            val nowPinned = withContext(Dispatchers.IO) {
                val source = BaopuRuntime.mcp(context)
                if (remoteName in pinnedNames) source.unpinTool(serverId, remoteName)
                else source.pinTool(serverId, remoteName)
                source.pinnedRemoteNames(serverId)
            }
            pinnedNames = nowPinned
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 560.dp)
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.baopu_mcp_sheet_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.baopu_mcp_sheet_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = stringResource(R.string.baopu_mcp_oauth_token_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        // 502 自动 C「粘贴即解析」：地址一成形就报名称与候选传输（零网络、
                        // 纯形状判断；真相连接时实测）。复用现有提示位，不加新控件。
                        parseHint = McpPasteRule.singleUrl(it)
                            ?.takeIf { p -> p.invalid == null }
                            ?.let { p -> res.getString(R.string.baopu_mcp_paste_hint, p.name, p.transportHint) }
                    },
                    label = { Text(stringResource(R.string.baopu_mcp_url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                parseHint?.let { hint ->
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                OutlinedTextField(
                    value = clientId,
                    onValueChange = { clientId = it },
                    label = { Text(stringResource(R.string.baopu_mcp_oauth_client_id)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                Text(
                    text = stringResource(R.string.baopu_mcp_direct_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { disconnectAll() },
                        enabled = busyId == null && rows.any { it.status == McpServerStatus.CONNECTED },
                    ) {
                        Text(stringResource(R.string.baopu_mcp_oauth_revoke_all))
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(android.R.string.cancel))
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { connectNew() },
                        enabled = busyId == null && url.trim().startsWith("http"),
                    ) {
                        Text(
                            stringResource(
                                if (busyId != null) R.string.baopu_mcp_connecting else R.string.baopu_mcp_oauth_add,
                            ),
                        )
                    }
                }

                notice?.let { n ->
                    Text(
                        text = n.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (n.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(top = 14.dp))
                Text(
                    text = stringResource(R.string.baopu_mcp_registered),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 12.dp),
                )
                if (rows.isEmpty()) {
                    Text(
                        text = stringResource(R.string.baopu_mcp_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                } else {
                    rows.forEach { view ->
                        McpServerRow(
                            view = view,
                            busy = busyId != null,
                            onConnect = { reconnect(view) },
                            onDisconnect = { pendingDisconnect = view },
                            onDelete = { pendingDelete = view },
                            onViewTools = { viewTools(view) },
                            onProbe = { probeServer(view) },
                            onClipboard = { importFromClipboard(view) },
                        )
                    }
                }
            }
        }
    }

    // 断开＝清本机 token：也值得确认一次（用户可能只是想把工具收起来）。
    pendingDisconnect?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDisconnect = null },
            title = { Text(stringResource(R.string.baopu_mcp_disconnect)) },
            text = { Text(stringResource(R.string.baopu_mcp_oauth_disconnect_hint)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDisconnect = null
                        disconnect(target)
                    },
                ) {
                    Text(stringResource(R.string.baopu_mcp_disconnect))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDisconnect = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    // 删除是破坏性动作：先弹一张确认卡（不批量、不静默）。
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.baopu_mcp_delete_title)) },
            text = { Text(stringResource(R.string.baopu_mcp_delete_body, target.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        remove(target)
                    },
                ) {
                    Text(stringResource(R.string.baopu_mcp_delete_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    // 查看工具：清单 + 630 的 pin 开关（钉住的工具在预算装不下时仍会带上）。
    viewingTools?.let { target ->
        AlertDialog(
            onDismissRequest = { viewingTools = null },
            title = { Text(stringResource(R.string.baopu_mcp_oauth_tools_title, target.name)) },
            text = {
                if (toolNames.isEmpty()) {
                    Text(stringResource(R.string.baopu_mcp_oauth_tools_empty))
                } else {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            text = stringResource(R.string.baopu_mcp_pin_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        toolNames.forEach { name ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(vertical = 2.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                TextButton(onClick = { togglePin(target.id, name) }) {
                                    Text(
                                        text = if (name in pinnedNames) {
                                            stringResource(R.string.baopu_mcp_pinned)
                                        } else {
                                            stringResource(R.string.baopu_mcp_pin)
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewingTools = null }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }
}

/** 一行：名字 / 主机 / 状态 / 授权状态 / 工具数 / 最后连接 / 动作簇（245 加「测试连接」，513 加「从剪贴板读取」）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun McpServerRow(
    view: McpServerView,
    busy: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onDelete: () -> Unit,
    onViewTools: () -> Unit,
    onProbe: () -> Unit,
    onClipboard: () -> Unit,
) {
    val statusText = when (view.status) {
        McpServerStatus.CONNECTED -> stringResource(R.string.baopu_mcp_status_online)
        McpServerStatus.OFFLINE -> stringResource(R.string.baopu_mcp_status_offline)
        McpServerStatus.ERROR -> stringResource(R.string.baopu_mcp_status_error)
    }
    val statusColor: Color = when (view.status) {
        McpServerStatus.CONNECTED -> MaterialTheme.colorScheme.primary
        McpServerStatus.ERROR -> MaterialTheme.colorScheme.error
        McpServerStatus.OFFLINE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val authText = when (view.auth) {
        McpAuthStatus.VALID -> stringResource(R.string.baopu_mcp_oauth_auth_valid)
        McpAuthStatus.EXPIRED -> stringResource(R.string.baopu_mcp_oauth_auth_expired)
        McpAuthStatus.REVOKED -> stringResource(R.string.baopu_mcp_oauth_auth_revoked)
        McpAuthStatus.UNSUPPORTED -> stringResource(R.string.baopu_mcp_oauth_auth_unsupported)
        McpAuthStatus.UNAUTHORIZED -> stringResource(R.string.baopu_mcp_oauth_auth_none)
    }
    val authColor: Color = when (view.auth) {
        McpAuthStatus.VALID -> MaterialTheme.colorScheme.primary
        McpAuthStatus.EXPIRED, McpAuthStatus.REVOKED, McpAuthStatus.UNSUPPORTED ->
            MaterialTheme.colorScheme.error
        McpAuthStatus.UNAUTHORIZED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val connected = view.status == McpServerStatus.CONNECTED

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = view.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 第二行给「主机+路径」（去掉 scheme）：名称本来就是从主机名派生的，
                // 两行都写 127.0.0.1 等于白占一行（真机截图里看得很明显）。
                Text(
                    text = view.displayUrl.substringAfter("://").ifBlank { view.host },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelMedium,
                    color = statusColor,
                )
                Text(
                    text = authText,
                    style = MaterialTheme.typography.labelSmall,
                    color = authColor,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 组合期取资源必须在可组合调用里完成（不能塞进 buildString 的 lambda）。
            val toolText = if (connected) {
                // 630：{toolCount, schemaBytes, loadedCount} 三件读数都在面板这一行（④）。
                val stats = stringResource(
                    R.string.baopu_mcp_loaded_stats,
                    view.loadedCount,
                    view.toolCount,
                    formatSchemaBytes(view.schemaBytes),
                )
                val pinned = if (view.pinnedCount > 0) {
                    pluralStringResource(R.plurals.baopu_mcp_pinned_count, view.pinnedCount, view.pinnedCount)
                } else {
                    ""
                }
                val count = pluralStringResource(R.plurals.baopu_mcp_tool_count, view.toolCount, view.toolCount)
                listOf(count, stats, pinned).filter { it.isNotEmpty() }.joinToString(" · ")
            } else {
                ""
            }
            val lastText = if (view.auth == McpAuthStatus.UNSUPPORTED) {
                // 失败要可读：旧式服务器就直说，别让它看起来像"再点一次就能成"。
                stringResource(R.string.baopu_mcp_oauth_unsupported_hint)
            } else {
                view.lastConnectedAtMs?.let {
                    stringResource(R.string.baopu_mcp_oauth_last_connected, formatClock(it))
                } ?: stringResource(R.string.baopu_mcp_oauth_never_connected)
            }
            Text(
                text = if (toolText.isEmpty()) lastText else "$toolText · $lastText",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 2,
            )
            // 245 B3：动作从 3 颗变 4 颗（多一颗「测试连接」）⇒ 按钮簇改用 FlowRow：
            // 装得下时与原来同形（右对齐一行），窄屏／系统字号放大时折行，
            // 不许把最后一颗挤出右沿（与 B-补-22 同一条理由）。
            FlowRow(
                modifier = Modifier.weight(2f),
                horizontalArrangement = Arrangement.End,
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                if (connected) {
                    TextButton(onClick = onViewTools, enabled = !busy) {
                        Text(stringResource(R.string.baopu_mcp_oauth_view_tools))
                    }
                    TextButton(onClick = onDisconnect, enabled = !busy) {
                        Text(stringResource(R.string.baopu_mcp_disconnect))
                    }
                } else {
                    TextButton(onClick = onConnect, enabled = !busy) {
                        // 按钮文案按"现在缺什么"给：未授权=去授权，过期/撤销=重新授权，
                        // 不支持 OAuth=去连接（点了只给说明，不静默失败），有效=连接。
                        val label = when (view.auth) {
                            McpAuthStatus.UNAUTHORIZED -> R.string.baopu_mcp_oauth_authorize
                            McpAuthStatus.EXPIRED, McpAuthStatus.REVOKED -> R.string.baopu_mcp_oauth_reauth
                            McpAuthStatus.UNSUPPORTED -> R.string.baopu_mcp_connect
                            McpAuthStatus.VALID -> R.string.baopu_mcp_reconnect
                        }
                        Text(stringResource(label))
                    }
                }
                // 513：令牌不经对话——复制后点这颗，直接进保险柜（连没连上都能用）。
                TextButton(onClick = onClipboard, enabled = !busy) {
                    Text(stringResource(R.string.baopu_mcp_clipboard))
                }
                TextButton(onClick = onProbe, enabled = !busy) {
                    Text(stringResource(R.string.baopu_mcp_probe))
                }
                TextButton(onClick = onDelete, enabled = !busy) {
                    Text(stringResource(R.string.baopu_mcp_delete))
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * 时间只到分钟（列表里够认人；不显示秒，避免每次重组都变）。
 *
 * 走仓里的共享 formatter（门禁 B-补-24：主源码不许裸 `SimpleDateFormat`）。
 */
private fun formatClock(epochMs: Long): String =
    "${TimeFormats.monthDay(epochMs)} ${TimeFormats.hourMinute(epochMs)}"

/** schema 字节量的显示形态（630 面板读数；KB 一位小数，1 KB 以下按字节）。 */
private fun formatSchemaBytes(bytes: Long): String =
    if (bytes >= 1024) {
        String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0)
    } else {
        "$bytes B"
    }
