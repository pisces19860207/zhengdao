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
import androidx.compose.foundation.layout.Column
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
import com.example.zhengdao.baopu.mcp.McpConnectOutcome
import com.example.zhengdao.baopu.mcp.McpServerDraft
import com.example.zhengdao.baopu.mcp.McpServerStatus
import com.example.zhengdao.baopu.mcp.McpServerView
import com.example.zhengdao.keepalive.CoroutineCrashHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.zhengdao.util.TimeFormats

/** 面板上的一条提示（成功＝青、失败＝朱；两色都取主题，不新增色板）。 */
private data class McpNotice(val ok: Boolean, val text: String)

/** 一次连接动作的收尾（新建与重连共用一份，避免两处文案漂移）。 */
private data class McpConnectResult(
    val outcome: McpConnectOutcome,
    val rows: List<McpServerView>,
    /** false ＝ 这个地址之前就登记过（addServer 拒收重复）。 */
    val isNew: Boolean,
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

    /** 授权回调（浏览器回来时投递到这里；null ＝ 没有待处理的）。 */
    val callback by McpAuthInbox.pendingState

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
                    // 失败一律**明说**（state 不匹配 / 换 token 失败），不静默。
                    McpNotice(
                        false,
                        res.getString(
                            R.string.baopu_mcp_oauth_auth_failed,
                            (e as? com.example.zhengdao.baopu.mcp.McpAuthException)?.userMessage
                                ?: e.javaClass.simpleName,
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
                if (clearInput) {
                    url = ""
                    clientId = ""
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
                if (clearInput) {
                    url = ""
                    clientId = ""
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

    /** 点「添加并授权」：造配置 → 登记（写盘）→ 连接（无 token 时会自动转授权）。 */
    fun connectNew() {
        if (busyId != null) return
        when (val draft = McpServerDraft.fromUrl(url, clientId)) {
            is McpServerDraft.Result.Invalid ->
                notice = McpNotice(false, res.getString(R.string.baopu_mcp_url_invalid, draft.message))

            is McpServerDraft.Result.Ok -> {
                val config = draft.config
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

    /** 查看工具（只读）。 */
    fun viewTools(target: McpServerView) {
        scope.launch {
            toolNames = withContext(Dispatchers.IO) { BaopuRuntime.mcp(context).toolNames(target.id) }
            viewingTools = target
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
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.baopu_mcp_url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
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

    // 查看工具：只读列表（工具名来自服务器 tools/list，不是我们编的）。
    viewingTools?.let { target ->
        AlertDialog(
            onDismissRequest = { viewingTools = null },
            title = { Text(stringResource(R.string.baopu_mcp_oauth_tools_title, target.name)) },
            text = {
                if (toolNames.isEmpty()) {
                    Text(stringResource(R.string.baopu_mcp_oauth_tools_empty))
                } else {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        toolNames.forEach { name ->
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
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

/** 一行：名字 / 主机 / 状态 / 授权状态 / 工具数 / 最后连接 / 四个动作。 */
@Composable
private fun McpServerRow(
    view: McpServerView,
    busy: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onDelete: () -> Unit,
    onViewTools: () -> Unit,
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
                pluralStringResource(R.plurals.baopu_mcp_tool_count, view.toolCount, view.toolCount)
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
            TextButton(onClick = onDelete, enabled = !busy) {
                Text(stringResource(R.string.baopu_mcp_delete))
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
