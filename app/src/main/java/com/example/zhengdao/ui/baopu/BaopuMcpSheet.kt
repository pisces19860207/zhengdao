// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第一百七十批 B · MCP 真入口：面板直接连（addServer → connect），不再「给模型发一句话」。
//
// 为什么改：旧实现把地址当成一句聊天内容发给模型（走对话通道），能不能连、
// 连上没有全靠模型自己调 connect 工具——库里那套 McpToolSource 在生产路径上一次都没被
// 调用过（第一百七十批 B 的靶心）。现在这里开真入口，三层口径不变：
//   · 连接是用户点击的显式动作（不自动连、不在装配期连）；
//   · 工具注册即 CONFIRM ⇒ 第一次调用仍走既有确认卡（本文件不碰确认链路）；
//   · 配置落 prefs（只存地址/名字/超时，不存请求头）——不碰 BaopuDb.VERSION。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.example.zhengdao.baopu.mcp.McpConnectOutcome
import com.example.zhengdao.baopu.mcp.McpServerDraft
import com.example.zhengdao.baopu.mcp.McpServerStatus
import com.example.zhengdao.baopu.mcp.McpServerView
import com.example.zhengdao.keepalive.CoroutineCrashHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
internal fun BaopuMcpSheet(
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // Compose 里取资源走 LocalResources（lint: LocalContextGetResourceValueCall，configuration-aware）
    val res = LocalResources.current
    val scope = rememberCoroutineScope { CoroutineCrashHandler }
    var url by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<McpServerView>>(emptyList()) }
    /** 正在忙的 server id（新建时是待登记那条的 id）；null ＝ 空闲。 */
    var busyId by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<McpNotice?>(null) }
    var pendingDelete by remember { mutableStateOf<McpServerView?>(null) }

    // 打开面板：把盘里存下的配置登记回来（**只登记、不联网**）；幂等，重开不会重复。
    LaunchedEffect(Unit) {
        rows = withContext(Dispatchers.IO) {
            val source = BaopuRuntime.mcp(context)
            source.restoreFromStore()
            source.snapshot()
        }
    }

    /** 结果 → 状态 + 人话（成功清输入框；失败把原因留在提示里，配置还留着可重试）。 */
    fun finish(result: McpConnectResult, clearInput: Boolean) {
        rows = result.rows
        busyId = null
        notice = when (val outcome = result.outcome) {
            is McpConnectOutcome.Ok -> {
                if (clearInput) url = ""
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
                outcome.warnings.forEach {
                    extra += res.getString(R.string.baopu_mcp_hint_warn, it)
                }
                McpNotice(true, if (extra.isEmpty()) head else (listOf(head) + extra).joinToString("\n"))
            }
            is McpConnectOutcome.Failed -> McpNotice(
                false,
                res.getString(R.string.baopu_mcp_connect_failed, outcome.message),
            )
        }
    }

    /** 点「连接」：造配置 → 登记（写盘）→ 真连。 */
    fun connectNew() {
        if (busyId != null) return
        when (val draft = McpServerDraft.fromUrl(url)) {
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

    /** 行上的「连接／重连」：配置已经在管理器里（id 就是 key），不重新拼地址。 */
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

    /** 断开：注销工具、清授权，配置留着可重连。 */
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

    /** 删除：断连 + 删盘上的记录（确认卡在下一层 AlertDialog）。 */
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

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
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
                    text = stringResource(R.string.baopu_mcp_direct_note),
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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
                                if (busyId != null) R.string.baopu_mcp_connecting else R.string.baopu_mcp_connect,
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
                            onDisconnect = { disconnect(view) },
                            onDelete = { pendingDelete = view },
                        )
                    }
                }
            }
        }
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
}

/** 一行：名字 / 脱敏地址 / 状态 / 工具数 / 断开或连接 / 删除。 */
@Composable
private fun McpServerRow(
    view: McpServerView,
    busy: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onDelete: () -> Unit,
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
                Text(
                    text = view.displayUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelMedium,
                color = statusColor,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (connected) {
                    pluralStringResource(
                        R.plurals.baopu_mcp_tool_count,
                        view.toolCount,
                        view.toolCount,
                    )
                } else {
                    ""
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (connected) {
                TextButton(onClick = onDisconnect, enabled = !busy) {
                    Text(stringResource(R.string.baopu_mcp_disconnect))
                }
            } else {
                TextButton(onClick = onConnect, enabled = !busy) {
                    Text(stringResource(R.string.baopu_mcp_reconnect))
                }
            }
            TextButton(onClick = onDelete, enabled = !busy) {
                Text(stringResource(R.string.baopu_mcp_delete))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
