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
import androidx.compose.runtime.mutableIntStateOf
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
import com.example.zhengdao.ui.theme.zhengdaoSwitchColors
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
import com.example.zhengdao.R
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
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import com.example.zhengdao.BackChevron
import com.example.zhengdao.keepalive.KeepaliveArchive
import com.example.zhengdao.rootfs.RunLog
import com.example.zhengdao.terminal.Workspace
import com.example.zhengdao.ui.theme.ZhengdaoTheme
import kotlinx.coroutines.CoroutineScope

/** 二级页：关于（逐字搬自「关于」卡 —— 版本、仓库、反馈、许可、开源许可、联系作者）。 */
class SettingsAboutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            ZhengdaoTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsAboutPage(onBack = { finish() })
                }
            }
        }
    }
}

@Composable
private fun SettingsAboutPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    // 隐藏入口（2026-10-12 走查报告 §1.2-D）：输入回归页是开发自测页，从用户设置里撤下，
    // 收成「版本」行连点 3 次。普通人误点三次也只会当它是彩蛋。
    var versionTaps by remember { mutableIntStateOf(0) }
    SettingsSubPage(title = stringResource(R.string.settings_about), onBack = onBack) {
        // ── 关于 ──
        SectionCard(stringResource(R.string.settings_about)) {
            InfoRow(
                stringResource(R.string.about_version),
                "${BuildConfig.VERSION_NAME} (versionCode ${BuildConfig.VERSION_CODE})",
                onClick = {
                    versionTaps++
                    if (versionTaps >= 3) {
                        versionTaps = 0
                        ctx.startActivity(
                            android.content.Intent(ctx, ImeRegressionActivity::class.java),
                        )
                    }
                },
            )
            Spacer(Modifier.height(4.dp))
            // 定位文案（第十八批 C 段）：抱朴 = 自研 harness。中英走 strings.xml（baopu_positioning_*），
            // 与首页顶栏那句同一对字符串。
            InfoRow(
                stringResource(R.string.baopu_positioning_label),
                stringResource(R.string.baopu_positioning_value),
            )
            Spacer(Modifier.height(4.dp))
            InfoRow(stringResource(R.string.about_author), "小信多多")
            Spacer(Modifier.height(4.dp))
            // Root 增强模式（2026-10-12 从设置 Hub 移来）：未实现项不再占设置页的行，
            // 但它是个真实的状态说明，放在关于页纯文本展示。
            InfoRow(
                stringResource(R.string.about_root_mode),
                stringResource(R.string.about_root_mode_value),
            )
            Spacer(Modifier.height(4.dp))
            // 安装包来源（E-065）：GPL 允许别人重打包，但用户有权知道手里这个包是谁签的。
            // 只报告，不拦功能 —— 自己改代码自己签名也是 GPL 明确允许的用法。
            InfoRow(
                stringResource(R.string.about_build_source),
                when (remember { runCatching { com.example.zhengdao.util.SigningCheck.check(ctx) }.getOrNull() }) {
                    com.example.zhengdao.util.SigningCheck.Result.OFFICIAL ->
                        stringResource(R.string.about_build_official)
                    com.example.zhengdao.util.SigningCheck.Result.UNOFFICIAL ->
                        stringResource(R.string.about_build_unofficial)
                    else -> stringResource(R.string.about_build_unknown)
                },
            )
            Spacer(Modifier.height(4.dp))
            // 外链也走同一套行式组件（右侧统一是 ›），不再是一排蓝色文字按钮
            SettingRow(
                label = stringResource(R.string.about_repo),
                value = "",
                onClick = {
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW, "https://github.com/pisces19860207/zhengdao".toUri())
                    )
                },
            )
            SettingsDivider()
            SettingRow(
                label = stringResource(R.string.about_issues),
                value = "",
                onClick = {
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW, "https://github.com/pisces19860207/zhengdao/issues".toUri())
                    )
                },
            )
            SettingsDivider()
            // 许可证与署名（2026-10-11 起）：第一方代码改为「保留所有权利」（商业使用需书面许可），
            // 历史版本（≤ v2.0.15）仍按当时发布的 GPL-3.0 —— 详见仓库根 LICENSE 与下一页「开源许可」。
            SettingRow(
                label = stringResource(R.string.about_license),
                value = stringResource(R.string.about_license_value),
                onClick = {
                    ctx.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            "https://github.com/pisces19860207/zhengdao/blob/main/LICENSE".toUri(),
                        )
                    )
                },
            )
            SettingsDivider()
            SettingRow(
                label = stringResource(R.string.about_licenses),
                value = stringResource(R.string.about_licenses_value),
                onClick = {
                    // 2026-10-11：许可证正文已随 APK 分发（assets/licenses/），这一页离线可读。
                    // 上面那行「许可证」不动（仍是 GitHub 链接）—— 联网的人照样能去仓库看。
                    ctx.startActivity(Intent(ctx, OpenSourceLicensesActivity::class.java))
                },
            )
            SettingsDivider()
            SettingRow(
                label = stringResource(R.string.about_contact),
                value = "",
                onClick = {
                    ctx.startActivity(
                        Intent(Intent.ACTION_SENDTO, "mailto:putaopanpan@gmail.com".toUri())
                    )
                },
            )
        }
    }
}
