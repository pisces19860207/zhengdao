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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.launch
import com.example.zhengdao.ui.util.pressClickable
import com.example.zhengdao.util.HumanizeError
import com.example.zhengdao.ui.theme.iosSwitchColors
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
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import com.example.zhengdao.BackChevron
import com.example.zhengdao.keepalive.KeepaliveArchive
import com.example.zhengdao.rootfs.RunLog
import com.example.zhengdao.terminal.Workspace
import com.example.zhengdao.ui.theme.ZhengdaoTheme
import kotlinx.coroutines.CoroutineScope

/** 二级页：后台与保活（逐字搬自「荣耀 / MagicOS 保活指南」卡，含保活记录入口）。 */
class SettingsKeepaliveActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            ZhengdaoTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsKeepalivePage(onBack = { finish() })
                }
            }
        }
    }
}

@Composable
private fun SettingsKeepalivePage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val res = LocalResources.current
    var storageTick by remember { mutableIntStateOf(0) }

    // 「保活记录」卡里那一行用 storageTick 当键：在新页面点完「清空记录」返回时，它要自己刷空。
    // 原来的自增点是设置页的 ON_RESUME 观察者，这里照搬一个只做自增的。
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) storageTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingsSubPage(title = stringResource(R.string.keepalive_title), onBack = onBack) {
        // ── 荣耀 / MagicOS 保活指南（Magic 5 Pro 实测）──
        SectionCard(stringResource(R.string.keepalive_guide_title)) {
            Text(
                text = stringResource(R.string.keepalive_guide_steps),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
            // 这条是解释性长句 ⇒ 默认只显第一句，要细看再展开（走查报告 §5.6）
            ExpandableHint(stringResource(R.string.keepalive_note))
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = {
                // 电池优化白名单（用户第四批）：直接拉起系统"忽略电池优化"请求对话框；
                // 厂商定制系统不支持该入口时退回通用设置列表，仍不支持则静默。
                try {
                    ctx.startActivity(
                        Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .apply { data = "package:${ctx.packageName}".toUri() }
                    )
                } catch (_: Throwable) {
                    try {
                        ctx.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (_: Throwable) {
                        Toast.makeText(ctx, res.getString(R.string.keepalive_battery_toast), Toast.LENGTH_LONG).show()
                    }
                }
            }) { Text(stringResource(R.string.keepalive_battery_btn)) }
            TextButton(onClick = {
                // 应用详情页（启动管理/自启动设置的入口）：荣耀等国产 ROM 的保活开关都在这层
                runCatching {
                    ctx.startActivity(
                        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .apply { data = "package:${ctx.packageName}".toUri() }
                    )
                }.onFailure {
                    Toast.makeText(ctx, res.getString(R.string.keepalive_details_toast), Toast.LENGTH_LONG).show()
                }
            }) { Text(stringResource(R.string.keepalive_details_btn)) }

            // ── 保活记录：只留一行入口（2026-10-11 搬家）──
            // 用户原话：「保活记录这个卡弄成和问题反馈那种，太多太长了，单独给他弄一个。」
            // 判据：那张卡是**报障时才看**的内容（位置 / 长解释 / 最近退出 / 最近心跳 /
            // logcat 片段数 / 导出 / 清空），却天天占着设置页一整屏 ⇒ 整段搬进二级页
            // ui/KeepaliveLogActivity.kt，文案与 KeepaliveArchive 调用逐字未改；
            // 设置页只留一行，写法与「关于」卡里的「问题反馈（Issues）」同款
            //（SettingRow：整行可点 + 右侧 ›）。
            //
            // 为什么并进这张卡，而不是自己再起一张 SectionCard("保活记录")：
            // 用户嫌的就是"占地方"，而一张只有一行的卡仍要吃掉一个卡头 + 20dp 组间距
            //（组间距约定见本文件内容区 Column 的 verticalArrangement：组间距 20dp）；紧邻这张讲的正是同一件事
            //（怎么活下来 / 活下来的证据：死亡证明、心跳、logcat），合并省掉一整块版面。
            // 被否决：并入「关于」卡 —— 那里是签名 / 更新 / 开源协议，用户报障时得在
            // 一堆"关于"信息里翻找，反而更难找。
            //
            // 摘要「最近 N 条退出记录」：读的是 exits.log，必须 IO 线程。
            // 口径刻意用页面同一个 history(ctx, 3)、不自己数文件 —— 同一件事两套账
            //（本仓 E-073 的教训）迟早对不上。读不到时 history 自己返回空表 ⇒ 显示空串。
            // 键用 storageTick（上面 ON_RESUME 观察者自增）而不是 Unit：在新页面点完
            // 「清空记录」返回，这一行会自己刷成空串，不会留着已清掉的旧数字。
            var kaExitCount by remember { mutableIntStateOf(0) }
            LaunchedEffect(storageTick) {
                kaExitCount = withContext(Dispatchers.IO) {
                    com.example.zhengdao.keepalive.KeepaliveArchive.history(ctx, 3).size
                }
            }
            SettingsDivider()
            SettingRow(
                label = stringResource(R.string.keepalive_log_row),
                value = if (kaExitCount > 0) {
                    stringResource(R.string.keepalive_log_recent, kaExitCount)
                } else "",
                onClick = { ctx.startActivity(Intent(ctx, KeepaliveLogActivity::class.java)) },
            )
        }
    }
}

