// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.launch
import com.example.zhengdao.ui.util.pressClickable
import com.example.zhengdao.util.HumanizeError
import com.example.zhengdao.ui.theme.iosSwitchColors
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import com.example.zhengdao.BuildConfig
import com.example.zhengdao.terminal.CacheCleaner
import com.example.zhengdao.terminal.HermesEnv
import com.example.zhengdao.terminal.TerminalPrefs
import com.example.zhengdao.rootfs.PatchRef
import com.example.zhengdao.rootfs.RootfsCache
import com.example.zhengdao.rootfs.RootfsDelta
import com.example.zhengdao.rootfs.RootfsDownloader
import com.example.zhengdao.rootfs.RootfsIndex
import com.example.zhengdao.rootfs.RootfsIndexFetcher
import com.example.zhengdao.rootfs.RootfsInstaller
import com.example.zhengdao.rootfs.RootfsMarker
import com.example.zhengdao.ui.SystemInfoProvider.dirSizeMb
import com.example.zhengdao.ui.AppState.rootfsInstalled
import com.example.zhengdao.ui.theme.IOSReadyGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Surface
import androidx.core.view.WindowCompat
import com.example.zhengdao.BackChevron
import com.example.zhengdao.keepalive.KeepaliveArchive
import com.example.zhengdao.rootfs.RunLog
import com.example.zhengdao.terminal.Workspace
import com.example.zhengdao.ui.theme.ZhengdaoTheme
import kotlinx.coroutines.CoroutineScope

/** 二级页：帮助（逐字搬自「新手指南 / 运行日志」两张卡 —— 指南放在首屏，B-补-23）。 */
class SettingsHelpActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // 首页「最近问题」卡上的「看日志」：本页的「运行日志」是最后一张卡，
        // 带这个 extra 进来就直接停在那一节（台账 §B 顺序 11 / 原 §B-补-42）。
        val focusLogs = intent?.getBooleanExtra(EXTRA_FOCUS_LOGS, false) == true
        setContent {
            ZhengdaoTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsHelpPage(onBack = { finish() }, scrollToEnd = focusLogs)
                }
            }
        }
    }

    companion object {
        /** 打开本页时直接停在「运行日志」一节。 */
        const val EXTRA_FOCUS_LOGS = "focus_logs"
    }
}

@Composable
private fun SettingsHelpPage(onBack: () -> Unit, scrollToEnd: Boolean = false) {
    val ctx = LocalContext.current
    var storageTick by remember { mutableStateOf(0) }
    var logDialogText by remember { mutableStateOf<String?>(null) }

    /** 上一轮（有错误时）的日志原文；只喂「运行日志」卡片的提示与按钮。 */
    var prevLogText by remember { mutableStateOf<String?>(null) }
    var archiveCount by remember { mutableStateOf(0) }

    SettingsSubPage(title = "帮助", onBack = onBack, scrollToEnd = scrollToEnd) {
        // ── 新手指南（第三批）──
        SectionCard("新手指南") {
            GuideLine("1", "主页点「安装运行环境」装好 Debian 环境；再给想用的 Agent 点「安装」。")
            GuideLine("2", "在每个 Agent 里完成它自己的登录（密码由它自己保管），之后直接就能用。")
            // 2026-10-08：「洞天」→「终端」，与底栏 Tab 文案一致（见 MainActivity.kt:470）
            GuideLine("3", "进底部「终端」，直接输入 agent 命令使用（claude / hermes / agy）。OpenCode 已内置在「太极」，开箱即用；你在终端里另外装的 opencode 是另一份，两者互不干扰。")
            Spacer(Modifier.height(8.dp))
            Text(
                "常见问题",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            FaqLine("环境打不开 / 下载失败？", "用上面的「修复环境」重新解压，登录状态会保留。")
            FaqLine("Agent 想更新？", "在终端里重跑一遍安装命令；更新系统用「检查环境更新」。")
            FaqLine("我的文件在哪？", "产物都在工作区文件夹里（默认是手机的 Download/证道）。")
        }
        SectionCard("运行日志") {
            // 用户 2026-10-08：日志要和下载物一样"看得见、找得到"——不再藏在私有 cache 里。
            Text(
                "位置：${com.example.zhengdao.rootfs.RunLog.dirPath(ctx)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "zhengdao-log.txt 是本次运行；带时间的存档最多留 20 份 / 20 MB，从最旧的开始删" +
                    "（不会因为「这轮没出错」就删）；errors.log 只记错误行。" +
                    "可以用文件管理器直接打开，也可以丢给我们查问题。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (archiveCount > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "已有 $archiveCount 份历史日志。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            prevLogText?.let { prev ->
                Spacer(Modifier.height(6.dp))
                Text(
                    "上次运行检测到错误，日志已保留（可直接复制反馈）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { logDialogText = prev }) { Text("查看上次日志") }
            }
            if (archiveCount > 3) {
                TextButton(onClick = {
                    val n = com.example.zhengdao.rootfs.RunLog.pruneArchivesKeep(ctx, keep = 3)
                    archiveCount = com.example.zhengdao.rootfs.RunLog.archives().size
                    Toast.makeText(ctx, "已删掉 $n 份旧日志，保留最近 3 份", Toast.LENGTH_SHORT).show()
                }) { Text("只留最近 3 份", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }

    SettingsLogDialog(log = logDialogText, onDismiss = { logDialogText = null })
}

