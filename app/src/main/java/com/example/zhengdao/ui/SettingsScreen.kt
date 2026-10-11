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
import androidx.compose.ui.text.style.TextAlign
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
/** 状态行"就绪／已授权"用的绿：比主题 tertiary(#34C759) 更深，浅底上作正文色才有对比度。 */
private val ReadyGreen = IOSReadyGreen

/**
 * 设置（入口页，2026-10-11 重排）。
 *
 * 原来这里是 17 张卡、2400 行的单页 —— 用户（2026-10-11）看完说「设置页内容太多了，
 * 整体显得很乱」。重排只做两件事：**入口化**（顶层只留一句话状态 + 一次点击）
 * 和**搬运**（卡片内容一字未改地搬进 6 个二级页）。
 *
 * 二级页：存储与缓存 / 环境与更新 / 终端与资料 / 后台与保活 / 帮助 / 关于。
 */
@Composable
fun SettingsScreen(
    onOpenTerminal: (autocmd: String?, agentId: String?) -> Unit = { _, _ -> },
    onOpenPlugins: () -> Unit = {},
) {
    val ctx = LocalContext.current
    var storageTick by remember { mutableStateOf(0) }
    var rootfsMb by remember { mutableStateOf(-1L) }
    var homeMb by remember { mutableStateOf(-1L) }
    var cacheMb by remember { mutableStateOf(-1L) }

    // 局部函数没有提升：必须先声明再用（否则 remember { } 里的调用会推不出类型）。
    fun storageGrantedNow(): Boolean =
        com.example.zhengdao.terminal.ProotLauncher.storageGranted(ctx)

    fun manageGranted(): Boolean =
        runCatching { android.os.Environment.isExternalStorageManager() }.getOrDefault(false)

    var storageOk by remember { mutableStateOf(storageGrantedNow()) }
    var manageOk by remember { mutableStateOf(manageGranted()) }
    var kbEnabled by remember {
        mutableStateOf(com.example.zhengdao.terminal.KnowledgeBase.isEnabled(ctx))
    }
    var kbFiles by remember { mutableStateOf<Int?>(null) }

    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                manageOk = manageGranted()
                storageOk = storageGrantedNow()
                kbEnabled = com.example.zhengdao.terminal.KnowledgeBase.isEnabled(ctx)
                storageTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(storageTick) {
        val sizes = withContext(Dispatchers.IO) {
            Triple(
                dirSizeMb(File(ctx.filesDir, "rootfs")),
                dirSizeMb(File(ctx.filesDir, "home")),
                dirSizeMb(ctx.cacheDir),
            )
        }
        rootfsMb = sizes.first
        homeMb = sizes.second
        cacheMb = sizes.third
        kbFiles = withContext(Dispatchers.IO) {
            runCatching { com.example.zhengdao.terminal.KnowledgeBase.status(ctx).files }.getOrNull()
        }
    }

    val hasSu = remember { listOf("/system/bin/su", "/system/xbin/su", "/sbin/su").any { File(it).exists() } }

    /** 目录大小：`-1` = 还在量，显示省略号而不是「0 MB」。 */
    fun mbText(mb: Long): String = if (mb < 0) "…" else "$mb MB"

    /** 二级页统一入口。 */
    fun open(page: Class<*>) {
        ctx.startActivity(Intent(ctx, page))
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SectionCard("环境") {
                SettingRow(
                    label = "存储与缓存",
                    value = "rootfs ${mbText(rootfsMb)} · home ${mbText(homeMb)} · cache ${mbText(cacheMb)}",
                ) { open(SettingsStorageActivity::class.java) }
                SettingsDivider()
                SettingRow(
                    label = "环境与更新",
                    value = if (AppState.rootfsInstalled(ctx)) "已安装 · 检查更新 / 修复" else "未安装 · 去安装",
                ) { open(SettingsEnvActivity::class.java) }
            }

            SectionCard("终端与资料") {
                SettingRow(
                    label = "终端与工作区",
                    value = "外观 ${TerminalPrefs.spacing(ctx).label} · 工作区 ${Workspace.hostDir(ctx).name}" +
                        (if (Workspace.isPrivate(ctx)) "（私有）" else ""),
                ) { open(SettingsDataActivity::class.java) }
                SettingsDivider()
                SettingRow(
                    label = "资料库",
                    value = when {
                        !kbEnabled -> "未启用"
                        (kbFiles ?: 0) > 0 -> "已挂载 ${kbFiles} 个文件"
                        else -> "已启用 · 还没放资料"
                    },
                ) { open(SettingsDataActivity::class.java) }
                SettingsDivider()
                SettingRow(
                    label = "权限",
                    value = if (storageOk && manageOk) "已就绪" else "待处理",
                    valueColor = if (storageOk && manageOk) ReadyGreen else Color.Unspecified,
                ) { open(SettingsDataActivity::class.java) }
            }

            SectionCard("后台与保活") {
                InfoRow("Root 增强模式", if (hasSu) "未实现 · 设备已 Root" else "未实现 · 计划 v1.x")
                Text(
                    "证道现在用 PRoot 免 Root 跑 Debian，不需要 Root 权限。Root / chroot 后端还没做，" +
                        "所以这里不放开关（旧版本那个开关点了只提示「即将推出」，容易被当成故障）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SettingsDivider()
                SettingRow(
                    label = "后台保活",
                    value = "最近 ${KeepaliveArchive.history(ctx, 3).size} 条记录 · 指南",
                ) { open(SettingsKeepaliveActivity::class.java) }
            }

            SectionCard("帮助与关于") {
                SettingRow(label = "帮助", value = "新手指南 · 运行日志") {
                    open(SettingsHelpActivity::class.java)
                }
                SettingsDivider()
                SettingRow(label = "关于", value = BuildConfig.VERSION_NAME) {
                    open(SettingsAboutActivity::class.java)
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * 分组卡片（iOS 设置语言）：标题在卡片外做小号灰标签，卡片本身是白底 + 0.5dp 发丝描边 + 16dp 圆角。
 * 旧版把标题塞进卡片里、且是 16sp 半粗——十几张卡读下来每张都是"标题 + 一堆小字"，
 * 分组反而没有层级。标题移到卡外后，视线先落到组标签、再落到这一组的内容。
 */
@Composable
internal fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                content()
            }
        }
    }
}

/** 名称—数值行：数值右对齐成一列，扫一眼就能比大小（旧版固定 150dp 标签宽 + 左对齐，读着像表格）。 */
@Composable
internal fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 2026-10-11 修：**标签先按固有宽度排，数值吃剩余宽度并右对齐**。
        // 旧写法是 `label` 带 `weight(1f)`、`value` 不带权重 —— Row 里无权重子项先按固有宽度量，
        // 长数值（如「rootfs 768 MB · home 3530 MB · cache 6 MB」实测占 1060px）会把
        // 带权重标签分到的剩余宽度压到接近 0，中文标签于是**逐字换行成竖排**（用户 2026-10-11 报：
        // 「存储与缓存这几个字是竖着的」）。现在长数值自己换行，标签永不被挤。
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 设置页统一的可点行：主标签 + 右侧状态文字 + "›"，整行可点、行高一致。
 * 旧版三行权限各有各的写法（行尾有的挂按钮、有的挂 ✅ emoji、有的挂 ›），一页凑出三套交互视觉。
 */
@Composable
internal fun SettingRow(
    label: String,
    value: String,
    valueColor: Color = Color.Unspecified,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressClickable(onClick = onClick)
            // 2026-10-08 走查：12dp → 14dp。bodyMedium 行高 24dp + 24dp = 48dp
            // 刚好不达标（差 4dp），这是设置页几乎所有行的交互热区。
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 2026-10-11 修（同 `InfoRow`）：标签按固有宽度一行显示，数值吃剩余宽度右对齐。
        // 这里是最先暴露问题的一行 —— 「存储与缓存」的数值 `rootfs 768 MB · home 3530 MB ·
        // cache 6 MB` 是入口页最长的一条，旧写法把标签挤成竖排。
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            softWrap = false,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (valueColor == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant
            else valueColor,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "›",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 轻量选择芯片（避免引入额外依赖）：选中＝淡蓝底 + 主色描边。 */
@Composable
fun FilterChip2(label: String, selected: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        border = BorderStroke(
            width = if (selected) 1.dp else 0.5.dp,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant,
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface,
            contentColor = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        // 2026-10-08 走查：4dp → 10dp。M3 OutlinedButton 默认最小高 40dp，
        // 原来 24dp 行高 + 8dp = 32dp，被默认值兜到 40dp 仍不足 48dp；
        // 现在内容高 44dp，筛选 chip 这类"次要但要重复点"的控件按得准。
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
