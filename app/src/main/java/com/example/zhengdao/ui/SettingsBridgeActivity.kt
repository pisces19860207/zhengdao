package com.example.zhengdao.ui

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.example.zhengdao.R
import com.example.zhengdao.bridge.BrowserAgentBridge
import com.example.zhengdao.bridge.BridgePolicy
import com.example.zhengdao.bridge.BridgeToken
import com.example.zhengdao.ui.theme.ZhengdaoTheme
import androidx.core.content.getSystemService

/**
 * 二级页：浏览器桥（给终端里的 agent 用）。
 *
 * ## 这一页是什么（设计文档 §4.5「token 下发」的落地）
 *
 * 终端里装的外部 agent（Claude Code / Codex / Hermes / AGY）自己没有浏览器，
 * 而 App 的 WebView 走手机全网络。浏览器桥 = 在 `127.0.0.1:14080` 开一个环回端点，
 * agent 用 Basic 鉴权调 11 条命令（navigate / click / type / snapshot / execJS / …）
 * 操作玄览的页面。**太极（内置 OpenCode）不接入本桥** —— 它的浏览器能力由自身插件提供。
 *
 * ## 三条 UX 纪律
 *
 * 1. **默认关**：开关拨开才 bind 端口。一个能操作浏览器的通道不该默认开着。
 * 2. **口令打码显示**：token 是 64 位随机串，界面只露前后各 4 位（防旁观/截屏瞟到整条）。
 *    要复制就点「复制口令」——复制是**显式动作**，不是默认展示。
 * 3. **重置立即生效**：重置后旧 token 当场失效（下一次请求就过不了鉴权）。
 *
 * 权限边界：agent 在"高权限区"自由操作网页，但"硬红线区"（WebView 安全配置、导航拦截规则、
 * 本桥 allowlist/端口/token、JS 接口重绑、App 私有数据、浏览器 UI/引擎、隐私脱敏开关）
 * 是锁死的 —— 见 [com.example.zhengdao.bridge.BridgePolicy] 的 KDoc。
 */
class SettingsBridgeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            ZhengdaoTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsBridgePage(onBack = { finish() })
                }
            }
        }
    }
}

@Composable
private fun SettingsBridgePage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    // 进程内单例：桥开着就是开着（开关只反映现状，不持有第二份状态）
    var enabled by remember { mutableStateOf(BrowserAgentBridge.isRunning()) }
    // 重置口令后自增，逼 remember 重新取一次打码串
    var tokenTick by remember { mutableStateOf(0) }
    var toast by remember { mutableStateOf<String?>(null) }
    val tokenMasked = remember(tokenTick) { BridgeToken.maskedForDisplay(ctx) }

    SettingsSubPage(title = stringResource(R.string.bridge_title), onBack = onBack) {
        // ── 开关 + 监听地址 ──
        SectionCard(stringResource(R.string.bridge_switch_group)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.bridge_switch_label),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = if (enabled) {
                            stringResource(R.string.bridge_status_on)
                        } else {
                            stringResource(R.string.bridge_status_off)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { on ->
                        enabled = if (on) {
                            BrowserAgentBridge.start(ctx)
                        } else {
                            BrowserAgentBridge.stop()
                            false
                        }
                        toast = if (enabled) {
                            ctx.getString(R.string.bridge_started)
                        } else {
                            ctx.getString(R.string.bridge_stopped)
                        }
                    },
                )
            }
            SettingsDivider()
            // 监听地址：把"只绑本机"这件事明写在界面上（用户该知道它够不着外网）
            SettingRow(
                label = stringResource(R.string.bridge_port_label),
                value = "${BridgePolicy.BIND_HOST}:${BridgePolicy.PORT}",
            ) { }
        }

        // ── 口令 ──
        SectionCard(stringResource(R.string.bridge_token_group)) {
            Text(
                text = stringResource(R.string.bridge_token_label),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = tokenMasked,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
            Text(
                text = stringResource(R.string.bridge_token_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    val cm = ctx.getSystemService<ClipboardManager>()
                    cm?.setPrimaryClip(
                        android.content.ClipData.newPlainText("zhengdao-bridge-token", BridgeToken.copyable(ctx)),
                    )
                    toast = ctx.getString(R.string.bridge_copied)
                }) { Text(stringResource(R.string.bridge_copy)) }

                TextButton(onClick = {
                    BridgeToken.regenerate(ctx)
                    tokenTick++
                    toast = ctx.getString(R.string.bridge_reset_done)
                }) { Text(stringResource(R.string.bridge_reset)) }
            }
        }

        // ── 怎么用 ──
        SectionCard(stringResource(R.string.bridge_howto_group)) {
            Text(
                text = stringResource(R.string.bridge_howto),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(8.dp))
    }

    // 轻提示：不新开布局，用 Snackbar 的轻量替身——一个自动消失的浮字
    toast?.let { msg ->
        LaunchedEffect(msg) {
            kotlinx.coroutines.delay(1800)
            toast = null
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.padding(bottom = 24.dp),
            ) {
                Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}
