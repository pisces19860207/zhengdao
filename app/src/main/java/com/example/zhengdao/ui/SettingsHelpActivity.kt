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
import com.example.zhengdao.ui.theme.zhengdaoErrorText
import androidx.compose.ui.res.stringResource
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
import com.example.zhengdao.R
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
    // §B 顺序 2 · P2-5：剪掉旧日志归档是**永久删除**，先弹确认框再执行。
    var pruneConfirm by remember { mutableStateOf(false) }

    SettingsSubPage(title = stringResource(R.string.settings_help), onBack = onBack, scrollToEnd = scrollToEnd) {
        // ── 新手指南（第三批）──
        SectionCard(stringResource(R.string.help_guide_title)) {
            GuideLine("1", stringResource(R.string.help_guide_1))
            GuideLine("2", stringResource(R.string.help_guide_2))
            // 2026-10-08：「洞天」→「终端」，与底栏 Tab 文案一致（见 MainActivity.kt:470）
            GuideLine("3", stringResource(R.string.help_guide_3))
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.help_faq_title),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            FaqLine(stringResource(R.string.help_faq_q1), stringResource(R.string.help_faq_a1))
            FaqLine(stringResource(R.string.help_faq_q2), stringResource(R.string.help_faq_a2))
            FaqLine(stringResource(R.string.help_faq_q3), stringResource(R.string.help_faq_a3))
        }
        SectionCard(stringResource(R.string.help_log_title)) {
            // 用户 2026-10-08：日志要和下载物一样"看得见、找得到"——不再藏在私有 cache 里。
            Text(
                stringResource(R.string.help_log_location, com.example.zhengdao.rootfs.RunLog.dirPath(ctx)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            // 解释性长句 ⇒ 默认只显第一句（走查报告 §5.6）
            ExpandableHint(stringResource(R.string.help_log_desc))
            if (archiveCount > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.help_log_archive_count, archiveCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            prevLogText?.let { prev ->
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.help_log_prev_error),
                    style = MaterialTheme.typography.bodySmall,
                    // P2-11：错误正文 zhengdaoErrorText()（error 色 3.55:1 不达 AA）
                    color = zhengdaoErrorText(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { logDialogText = prev }) {
                    Text(stringResource(R.string.help_log_view_prev))
                }
            }
            if (archiveCount > 3) {
                // §B 顺序 2 · P2-5：按钮只负责弹确认框，破坏性动作搬进 confirmButton。
                TextButton(onClick = { pruneConfirm = true }) {
                    Text(
                        stringResource(R.string.help_log_prune_btn),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (pruneConfirm) {
                    AlertDialog(
                        onDismissRequest = { pruneConfirm = false },
                        title = {
                            Text(
                                stringResource(R.string.help_log_prune_title),
                                color = zhengdaoErrorText(),
                            )
                        },
                        text = {
                            Column {
                                Text(
                                    stringResource(R.string.help_log_prune_desc, archiveCount - 3),
                                    color = zhengdaoErrorText(),
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    stringResource(R.string.help_log_prune_warn),
                                    color = zhengdaoErrorText(),
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                pruneConfirm = false
                                val n = com.example.zhengdao.rootfs.RunLog.pruneArchivesKeep(ctx, keep = 3)
                                archiveCount = com.example.zhengdao.rootfs.RunLog.archives().size
                                Toast.makeText(ctx, ctx.getString(R.string.help_log_pruned, n), Toast.LENGTH_SHORT).show()
                            }) { Text(stringResource(R.string.common_clean), color = zhengdaoErrorText()) }
                        },
                        dismissButton = {
                            TextButton(onClick = { pruneConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
                        },
                    )
                }
            }
        }
    }

    SettingsLogDialog(log = logDialogText, onDismiss = { logDialogText = null })
}

