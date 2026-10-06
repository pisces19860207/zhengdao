// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
import com.example.zhengdao.oc.OcManager
import com.example.zhengdao.terminal.CacheCleaner
import com.example.zhengdao.oc.TaijiPrefs
import com.example.zhengdao.terminal.TerminalPrefs
import com.example.zhengdao.rootfs.RootfsDownloader
import com.example.zhengdao.rootfs.RootfsInstaller
import com.example.zhengdao.ui.SystemInfoProvider.dirSizeMb
import com.example.zhengdao.ui.AppState.rootfsInstalled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 状态行"就绪／已授权"用的绿：比主题 tertiary(#34C759) 更深，浅底上作正文色才有对比度。 */
private val ReadyGreen = Color(0xFF2E7D32)

/** 设置偏好（工作区模式 / 已安装环境版本登记）。 */
object Settings {
    fun prefs(ctx: android.content.Context) =
        ctx.getSharedPreferences("zhengdao-settings", android.content.Context.MODE_PRIVATE)
}

/** 设置页（第二批）：存储占用 / 修复环境 / 工作区 / 检查更新 / Root / 关于。 */
@Composable
fun SettingsScreen(onOpenTerminal: (autocmd: String?, agentId: String?) -> Unit = { _, _ -> }) {
    val ctx = LocalContext.current
    // 这里原先有一份 SystemInfoProvider.collect() 的结果缓存，但全页从未读过它——
    // 设置页只展示存储占用。留着会每次进页白跑一次采集（v1.1 起采集还包含 node
    // 二进制的版本扫描），故删掉。
    var repairConfirm by remember { mutableStateOf(false) }
    var updateMsg by remember { mutableStateOf<String?>(null) }
    var pendingUpdateUrl by remember { mutableStateOf<String?>(null) }
    var pendingUpdateSha by remember { mutableStateOf<String?>(null) }
    var prevLogText by remember { mutableStateOf<String?>(null) }
    var showPrevLog by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var rootfsMb by remember { mutableStateOf(0L) }
    var homeMb by remember { mutableStateOf(0L) }
    var cacheMb by remember { mutableStateOf(0L) }
    var wsPickerOpen by remember { mutableStateOf(false) }

    // ── 权限（存储 + 网络自检）──
    fun storageGrantedNow(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED &&
        androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    var storageOk by remember { mutableStateOf(storageGrantedNow()) }
    var permHint by remember { mutableStateOf<String?>(null) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        storageOk = storageGrantedNow()
        permHint = if (storageOk) "已授权" else "系统未放行——点「去系统设置」手动开启（部分系统会把弹窗静默掉）"
    }
    var netChecking by remember { mutableStateOf(false) }
    var netMsg by remember { mutableStateOf<String?>(null) }

    // ── MANAGE_EXTERNAL_STORAGE（可选增强，用户定稿简化版）──
    // 检查 isExternalStorageManager；引导两级 Intent 兜底；无机型分叉；
    // 未授权不阻塞（工作区降级私有目录），仅设置页保留入口。
    fun manageGranted(): Boolean =
        runCatching { android.os.Environment.isExternalStorageManager() }.getOrDefault(false)
    var manageOk by remember { mutableStateOf(manageGranted()) }
    fun launchManage() {
        try {
            ctx.startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    .setData(android.net.Uri.parse("package:${ctx.packageName}"))
            )
        } catch (e: Exception) {
            try {
                ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (e2: Exception) {
                Toast.makeText(ctx, "打开失败: ${e2.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 从系统设置页返回后刷新权限状态（授权/撤销都发生在别的页面）
    // 存储占用刷新 tick：进页算一次；从终端清理返回（ON_RESUME）时重算，
    // 否则「在终端中清理缓存」回来后显示的还是旧值
    var storageTick by remember { mutableStateOf(0) }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                manageOk = manageGranted()
                storageOk = storageGrantedNow()
                storageTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(storageTick) {
        val sizes = withContext(Dispatchers.IO) {
            Triple(
                dirSizeMb(File(ctx.filesDir, "rootfs")),
                dirSizeMb(File(ctx.filesDir, "home")),
                dirSizeMb(ctx.cacheDir),
            )
        }
        rootfsMb = sizes.first; homeMb = sizes.second; cacheMb = sizes.third
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        // 组间距（20dp）刻意大于组内行距：SectionCard 现在把标题移到卡片外，
        // 分组才立得住；组间距不够的话卡片外的小标题会读成上一张卡的注脚。
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // 页面标题不在这里重复——外层顶栏已有「‹ 返回｜设置」（见 MainActivity）。
        // 旧版这里又写了一遍大字"设置"，和顶栏标题上下叠着，是全页最扎眼的重复。

        // ── 存储占用 ──
        SectionCard("存储占用") {
            InfoRow("rootfs（系统层）", "$rootfsMb MB")
            InfoRow("home（登录态与配置）", "$homeMb MB")
            InfoRow("cache（下载缓存）", "$cacheMb MB")
        }

        // ── 缓存清理（三档白名单：一档走 guest 官方命令，二档走宿主侧；详见 CacheCleaner）──
        SectionCard("缓存清理") {
            var cacheSizes by remember(storageTick) { mutableStateOf<Map<String, Long>>(emptyMap()) }
            // 量目录要遍历整棵文件树（npm 缓存动辄几万个小文件），不能放主线程——旧实现
            // 直接在组合期 remember { measure() } 里走，滚动设置页会卡。同时跟着 storageTick
            // 走：去 guest 里装完东西再回来，数字要跟着变，而不是停在进页那一刻。
            LaunchedEffect(storageTick) {
                cacheSizes = withContext(Dispatchers.IO) { CacheCleaner.measure(ctx) }
            }
            if (cacheSizes.isEmpty()) {
                Text(
                    text = "统计中…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                cacheSizes.forEach { (name, mb) -> InfoRow(name, "$mb MB") }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "一档（极低风险）：npm / uv / apt 包缓存，清理命令在终端里执行、输出可见。" +
                    "二档（低风险）：临时目录里带固定命名指纹、且 24 小时内没动过的残留文件，" +
                    "不需要终端会话。OpenCode/Hermes 工具链、rootfs 系统层、用户数据永不清。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))

            // 一档：官方 CLI，需要会话活着（命令要在 guest 里跑）
            TextButton(onClick = {
                if (com.example.zhengdao.terminal.SessionManager.isAlive()) {
                    onOpenTerminal(com.example.zhengdao.terminal.CacheCleaner.guestCommand(), null)
                } else {
                    Toast.makeText(ctx, "请先启动终端（会话未运行）", Toast.LENGTH_SHORT).show()
                }
            }) { Text("在终端中清理包缓存") }

            // 二档：宿主侧直接删（rootfs 就是宿主上的普通目录），因此**不需要会话**
            val tempMb = cacheSizes["临时文件"] ?: 0L
            var tempConfirm by remember { mutableStateOf(false) }
            TextButton(enabled = tempMb > 0, onClick = { tempConfirm = true }) {
                Text(if (tempMb > 0) "清理临时文件（$tempMb MB）" else "无临时文件可清")
            }
            if (tempConfirm) {
                AlertDialog(
                    onDismissRequest = { tempConfirm = false },
                    title = { Text("清理临时文件？") },
                    text = {
                        Text(
                            "将删除临时目录里约 $tempMb MB 的残留文件（固定命名指纹、且 24 " +
                                "小时内没动过）。\n\n不会删除 tmux 会话、编译缓存、锁文件，" +
                                "也不碰任何用户数据。"
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            tempConfirm = false
                            val freed = CacheCleaner.cleanTempFiles(ctx)
                            Toast.makeText(
                                ctx,
                                if (freed > 0) "已释放 ${CacheCleaner.bytesToMb(freed)} MB"
                                else "没有可清理的临时文件",
                                Toast.LENGTH_SHORT,
                            ).show()
                            storageTick++ // 触发重新统计，数字立刻回落到真实值
                        }) { Text("清理") }
                    },
                    dismissButton = {
                        TextButton(onClick = { tempConfirm = false }) { Text("取消") }
                    },
                )
            }
        }

        // ── 权限（存储读写 + 网络自检）──
        // 存储：/sdcard 共享存储读写全靠 READ/WRITE 运行时授权（E-005 修正后的事实）
        // 网络：INTERNET 为安装时权限系统自动授，无可引导项；用户真正会卡的是
        //      代理 App 分应用没勾选证道——给一键自检代替空喊"网络权限"
        SectionCard("权限") {
            // 两行常驻可点（用户提议）：已授权时点击 = 跳系统 App 详情页（查看/撤销/
            // MagicOS 联网管控都在那里）；未授权时行内另给授权按钮
            val openAppDetails = {
                runCatching {
                    ctx.startActivity(
                        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(android.net.Uri.parse("package:${ctx.packageName}"))
                    )
                }.onFailure { Toast.makeText(ctx, "打开失败: ${it.message}", Toast.LENGTH_SHORT).show() }
                Unit
            }
            // 未授权时整行可点＝直接拉起系统授权弹窗；已授权时点击＝去系统设置查看/撤销。
            SettingRow(
                label = "存储读写（共享存储 /sdcard）",
                value = if (storageOk) "已授权" else "未授权",
                valueColor = if (storageOk) ReadyGreen else MaterialTheme.colorScheme.error,
                onClick = {
                    if (storageOk) {
                        openAppDetails()
                    } else {
                        permLauncher.launch(
                            arrayOf(
                                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            )
                        )
                    }
                },
            )
            Text(
                text = "Agent 读写手机文件（/sdcard）依赖此权限；缺失时终端里读写手机文件会全部失败。点击本行可到系统设置查看或调整。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!storageOk) {
                Spacer(Modifier.height(6.dp))
                permHint?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(6.dp))
                }
            }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(10.dp))

            SettingRow(
                label = "网络访问",
                value = "自动授予",
                valueColor = ReadyGreen,
                onClick = openAppDetails,
            )
            Text(
                text = "INTERNET 为安装时权限，无需操作。若走代理：请在代理 App 的「分应用代理」里勾选证道；点击本行可到系统设置查看联网管控。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            // 自检也收进同一套行式组件（状态在右、结论在行下方），不再是一个孤立的文字按钮
            SettingRow(
                label = "网络自检",
                value = if (netChecking) "检测中…" else "点击检测",
                valueColor = if (netChecking) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.primary,
                onClick = {
                    if (!netChecking) {
                    netChecking = true
                    netMsg = "检测中…"
                    Thread {
                        val msg = try {
                            val t0 = System.currentTimeMillis()
                            val conn = URL("https://registry.npmmirror.com/-/ping").openConnection() as HttpURLConnection
                            conn.connectTimeout = 5000; conn.readTimeout = 5000
                            val ok = conn.responseCode in 200..299
                            runCatching { conn.inputStream.close() }
                            val ms = System.currentTimeMillis() - t0
                            if (ok) "✅ 网络可用（${ms}ms）" else "❌ 不通（HTTP ${conn.responseCode}）"
                        } catch (t: Throwable) {
                            "❌ 不通：${t.message}——检查网络，或在代理 App 分应用代理里勾选证道"
                        }
                        netMsg = msg
                        netChecking = false
                    }.start()
                    }
                },
            )
            netMsg?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(10.dp))

            // ── 所有文件访问（MANAGE_EXTERNAL_STORAGE，可选增强；用户定稿简化版）──
            SettingRow(
                label = "所有文件访问（推荐开启·主路径）",
                value = if (manageOk) "已开启" else "未开启",
                valueColor = if (manageOk) ReadyGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = { launchManage() },
            )
            Text(
                text = "推荐开启：Agent 可访问更广的存储范围（主路径）。基础 /sdcard 读写不开启此项亦可用；不开启或系统无此开关都不影响基本使用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ── 终端外观（字号 + 配色；下次进入终端时应用）──
        SectionCard("终端外观") {
            var sizeDp by remember { mutableStateOf(TerminalPrefs.sizeDp(ctx)) }
            var schemeId by remember { mutableStateOf(TerminalPrefs.scheme(ctx).id) }

            Text(
                text = "下次进入终端时生效。字号越小，同屏能显示的内容越多。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))

            Text("字号", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TerminalPrefs.SIZE_OPTIONS.forEach { dp ->
                    // 与「运行内存上限」共用同一个芯片组件：同一页里两个选项组，
                    // 选中态必须是同一套视觉（淡蓝底 + 主色描边）。
                    FilterChip2("$dp", dp == sizeDp) {
                        sizeDp = dp
                        TerminalPrefs.saveSize(ctx, dp)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("配色", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TerminalPrefs.Scheme.values().forEach { sc ->
                    val selected = sc.id == schemeId
                    OutlinedButton(
                        onClick = { schemeId = sc.id; TerminalPrefs.saveScheme(ctx, sc) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        // 选中＝淡蓝底 + 主色描边（与芯片同一套选中语言），不再只靠行尾一个 ✓
                        border = BorderStroke(
                            width = if (selected) 1.dp else 0.5.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface,
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 色块预览：用方案自身的底色 + 前景色显示 "Aa"
                            Box(
                                modifier = Modifier
                                    .size(36.dp, 22.dp)
                                    .background(Color(sc.bg), RoundedCornerShape(4.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("Aa", style = MaterialTheme.typography.labelSmall, color = Color(sc.fg))
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = sc.label + if (selected) "  ✓" else "",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }

        // ── 修复环境 ──
        SectionCard("修复环境") {
            Text(
                text = "重新解压系统层，保留登录态与工作区。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = { repairConfirm = true }) { Text("修复环境（30 秒）") }
        }

        // ── 工作区（0.6 显性化：产出边界让用户看得见）──
        SectionCard("工作区") {
            val wsHost = com.example.zhengdao.terminal.Workspace.hostDir(ctx)
            val wsShared = com.example.zhengdao.terminal.Workspace.isShared(ctx)
            Text(
                "终端与 Agent 的产出位置（guest 内路径固定为 /workspace）",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = wsHost.absolutePath,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (wsShared) {
                    "手机文件管理器直接可见、可自由删除；卸载证道后此文件夹仍会保留——产出不丢。"
                } else {
                    "应用专属目录：卸载证道时随之删除（在意痕迹时用）。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row {
                TextButton(onClick = { wsPickerOpen = true }) { Text("选择文件夹") }
                TextButton(onClick = {
                    com.example.zhengdao.terminal.Workspace.setDefault(ctx)
                    Toast.makeText(ctx, "已恢复默认（Download/证道），下次启动会话生效", Toast.LENGTH_SHORT).show()
                }) { Text("恢复默认") }
                TextButton(onClick = {
                    com.example.zhengdao.terminal.Workspace.setPrivate(ctx)
                    Toast.makeText(ctx, "已切换仅私有，下次启动会话生效", Toast.LENGTH_SHORT).show()
                }) { Text("仅私有") }
            }
            Text(
                text = "边界约定：Agent 可读写整个 /sdcard 用于查找资料，但产出约定只进工作区——此约定已写入 Agent 人设，终端与太极共用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (wsPickerOpen) {
            WorkspaceFolderPicker(
                onDismiss = { wsPickerOpen = false },
                onPick = { path ->
                    wsPickerOpen = false
                    if (com.example.zhengdao.terminal.Workspace.setCustom(ctx, path)) {
                        Toast.makeText(ctx, "工作区已设为 $path，下次启动会话生效", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(ctx, "该位置不可用，请选择内部存储中的文件夹", Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }

        // ── 检查环境更新（第三批：manifest 对比 + 应用内下载安装，不自动检查）──
        SectionCard("环境更新") {
            Text(
                text = "系统环境通过此按钮更新。不要在终端内执行 apt upgrade，可能导致环境损坏；语言级依赖优先用 pip / npm 管理。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                enabled = !checking,
                onClick = {
                    checking = true
                    Thread {
                        // 待下载目标（发现新版本时由检查逻辑填入，弹窗确认后用）
                        var pendingUrl: String? = null
                        var pendingSha: String? = null
                        val result = try {
                            // 优先拉 manifest（版本号 + 直链 + SHA256 一条龙）
                            val manifestText = com.example.zhengdao.rootfs.RootfsDownloader
                                .fetchText(com.example.zhengdao.rootfs.RootfsCache.MANIFEST_URL)
                            if (manifestText != null) {
                                val ver = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(manifestText)?.groupValues?.get(1)
                                val url = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(manifestText)?.groupValues?.get(1)
                                val sha = Regex("\"sha256\"\\s*:\\s*\"([^\"]+)\"").find(manifestText)?.groupValues?.get(1)
                                val installed = com.example.zhengdao.rootfs.RootfsCache.currentVersion(ctx)
                                when {
                                    ver == null || url == null ->
                                        "manifest 格式异常，无法解析版本"
                                    installed != null && ver == installed ->
                                        "已是最新版本（$installed）"
                                    else -> {
                                        pendingUrl = url; pendingSha = sha
                                        "发现新版本 $ver（当前 $installed），是否下载安装？安装包将缓存到 Download/zhengdao/cache，旧包自动保留。"
                                    }
                                }
                            } else {
                                // manifest 不可达：降级走 Releases API（仅提示 + 跳转）
                                val c = URL("https://api.github.com/repos/pisces19860207/zhengdao/releases/latest")
                                    .openConnection() as HttpURLConnection
                                c.connectTimeout = 15000; c.readTimeout = 15000
                                c.setRequestProperty("Accept", "application/vnd.github+json")
                                val body = c.inputStream.bufferedReader().readText()
                                val tag = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                                if (tag != null) "仓库最新发布：$tag（应用内直装通道未就绪，可点「去下载」手动获取安装包）"
                                else "仓库结构变化，无法解析版本"
                            }
                        } catch (t: Throwable) {
                            "检查失败（网络不可达）：${t.message}"
                        }
                        android.os.Handler(ctx.mainLooper).post {
                            updateMsg = result; checking = false
                            pendingUpdateUrl = pendingUrl
                            pendingUpdateSha = pendingSha
                            if (!result.startsWith("发现新版本")) {
                                Toast.makeText(ctx, result, Toast.LENGTH_LONG).show()
                            }
                        }
                    }.start()
                },
            ) { Text(if (checking) "检查中…" else "检查环境更新") }
            Spacer(Modifier.height(4.dp))
            Text(
                "Agent 清单：${AgentManifest.cachedVersionText(ctx)}（安装命令可免发版更新，进主页时自动刷新）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = {
                Toast.makeText(ctx, "正在刷新 Agent 清单…", Toast.LENGTH_SHORT).show()
                AgentManifest.refresh(ctx, force = true) { applied ->
                    android.os.Handler(ctx.mainLooper).post {
                        Toast.makeText(
                            ctx,
                            if (applied) "Agent 清单已更新" else "刷新失败（沿用现有清单）",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }) { Text("立即刷新 Agent 清单") }
            Spacer(Modifier.height(8.dp))

            // ── OpenCode 内置版更新（P2 第 5 条：用户主动点，不打扰启动）──
            val ocVer = OcManager.installedVersion(ctx)
            Text(
                text = "OpenCode 内置版：" + when {
                    ocVer != null -> ocVer
                    OcManager.installed(ctx) -> "已安装（版本未知）"
                    else -> "未安装（进太极 Tab 首次下载）"
                } + "（出厂 ${OcManager.VERSION}）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            var checkingOc by remember { mutableStateOf(false) }
            OutlinedButton(
                enabled = !checkingOc,
                onClick = {
                    checkingOc = true
                    Toast.makeText(ctx, "正在检查 OpenCode 更新…", Toast.LENGTH_SHORT).show()
                    Thread {
                        val upd = OcManager.checkUpdate(ctx)
                        val r = upd?.let { OcManager.downloadAndInstall(ctx, it) { } }
                        android.os.Handler(ctx.mainLooper).post {
                            checkingOc = false
                            Toast.makeText(
                                ctx,
                                when {
                                    upd == null -> "OpenCode 已是最新（${OcManager.installedVersion(ctx) ?: OcManager.VERSION}）或检查失败"
                                    r?.ok == true -> "OpenCode 已更新到 ${upd.version}，下次启动生效"
                                    else -> "更新失败：${r?.message ?: "未知错误"}"
                                },
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }.start()
                },
            ) { Text(if (checkingOc) "检查中…" else "检查 OpenCode 更新") }
        }

        // ── 安装包缓存（第三批）──
        SectionCard("安装包缓存") {
            var cacheText by remember { mutableStateOf("统计中…") }
            var rollbacks by remember { mutableStateOf<List<File>>(emptyList()) }
            var rollbackConfirm by remember { mutableStateOf<File?>(null) }
            fun reloadCache() {
                Thread {
                    val archives = com.example.zhengdao.rootfs.RootfsCache.listArchives(ctx)
                    val dir = com.example.zhengdao.rootfs.RootfsCache.dir(ctx)
                    val current = com.example.zhengdao.rootfs.RootfsCache.currentVersion(ctx)
                    val lines = buildString {
                        appendLine("目录：${dir.path}")
                        appendLine("已缓存 ${archives.size} 个安装包（当前环境：${current ?: "未安装"}）")
                        archives.forEach {
                            appendLine("· ${it.name}（${it.length() / (1024 * 1024)} MB）")
                        }
                    }
                    val rb = com.example.zhengdao.rootfs.RootfsCache.rollbackCandidates(ctx)
                    android.os.Handler(ctx.mainLooper).post { cacheText = lines; rollbacks = rb }
                }.start()
            }
            LaunchedEffect(Unit) { reloadCache() }
            Text(cacheText, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = {
                Thread {
                    val n = com.example.zhengdao.rootfs.RootfsCache.cleanupNonCurrent(ctx)
                    android.os.Handler(ctx.mainLooper).post {
                        Toast.makeText(ctx, "已清理 $n 个旧版本文件", Toast.LENGTH_SHORT).show()
                        reloadCache()
                    }
                }.start()
            }) { Text("清理旧版本缓存（保留当前版本）") }
            if (rollbacks.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "可回退的历史版本（安装包保留最近 2 个）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                rollbacks.forEach { f ->
                    TextButton(onClick = { rollbackConfirm = f }) {
                        Text("回退到 ${com.example.zhengdao.rootfs.RootfsCache.versionOf(f.name) ?: f.name}")
                    }
                }
            }
            if (rollbackConfirm != null) {
                val target = rollbackConfirm!!
                AlertDialog(
                    onDismissRequest = { rollbackConfirm = null },
                    title = { Text("回退环境版本") },
                    text = { Text("将用 ${target.name} 重装系统层（约几分钟）。登录态与工作区都会保留。") },
                    confirmButton = {
                        TextButton(onClick = {
                            rollbackConfirm = null
                            Thread {
                                try {
                                    RootfsInstaller.ensureFreeSpace(ctx, target.length())
                                    RootfsInstaller.install(ctx, target) { }
                                    com.example.zhengdao.rootfs.RootfsCache.pruneKeep(ctx)
                                    android.os.Handler(ctx.mainLooper).post {
                                        Toast.makeText(ctx, "回退完成，重进终端生效", Toast.LENGTH_LONG).show()
                                    }
                                } catch (t: Throwable) {
                                    android.os.Handler(ctx.mainLooper).post {
                                        Toast.makeText(ctx, "回退失败：${t.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }.start()
                        }) { Text("回退") }
                    },
                    dismissButton = { TextButton(onClick = { rollbackConfirm = null }) { Text("取消") } },
                )
            }
        }

        // ── 运行内存上限（用户第四批）：ulimit -v 防单个任务膨胀拖垮整机 ──
        SectionCard("运行内存上限") {
            Text(
                text = "限制 guest 内每个进程的虚拟内存。⚠️ 默认关闭：虚拟地址空间≠物理内存，真实负载下可能误伤 Agent（内存治理由 M2 软监控负责）。仅在某任务失控膨胀、拖垮整机时才建议临时开启。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            var memLimit by remember {
                mutableStateOf(Settings.prefs(ctx).getString("guest_mem_limit_mb", "0") ?: "0")
            }
            Row {
                FilterChip2("关闭", memLimit == "0") {
                    memLimit = "0"
                    Settings.prefs(ctx).edit().putString("guest_mem_limit_mb", "0").apply()
                    Toast.makeText(ctx, "已关闭，下次启动会话生效", Toast.LENGTH_SHORT).show()
                }
                Spacer(Modifier.width(6.dp))
                FilterChip2("3GB", memLimit == "3072") {
                    memLimit = "3072"
                    Settings.prefs(ctx).edit().putString("guest_mem_limit_mb", "3072").apply()
                    Toast.makeText(ctx, "已设为 3GB，下次启动会话生效", Toast.LENGTH_SHORT).show()
                }
                Spacer(Modifier.width(6.dp))
                FilterChip2("4GB", memLimit == "4096") {
                    memLimit = "4096"
                    Settings.prefs(ctx).edit().putString("guest_mem_limit_mb", "4096").apply()
                    Toast.makeText(ctx, "已设为 4GB，下次启动会话生效", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // ── 运行日志（第三批调整：存 cache，无错自动删，有错保留一代）──
        LaunchedEffect(Unit) {
            prevLogText = withContext(Dispatchers.IO) {
                if (com.example.zhengdao.rootfs.RunLog.lastRunHadErrors(ctx))
                    com.example.zhengdao.rootfs.RunLog.prevFile()?.readText()
                else null
            }
        }
        if (prevLogText != null) {
            SectionCard("上次运行日志（有错误，已保留）") {
                Text(
                    "上次运行检测到错误，日志已保留在 cache/runlog/，可直接复制反馈。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { showPrevLog = true }) { Text("查看上次日志") }
                TextButton(onClick = {
                    com.example.zhengdao.rootfs.RunLog.prevFile()?.delete()
                    prevLogText = null
                    Toast.makeText(ctx, "已删除", Toast.LENGTH_SHORT).show()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            }
        }

        // ── Root 增强模式 ──
        // ── 太极 Tab 走哪套 UI ──
        // 默认 Compose 原生界面（直连 opencode serve 的 HTTP + SSE，不走 WebView/LocalProxy）。
        // ⚠️ 保留回退开关的原因：阶段 0 的真机鉴权（Basic auth 打 /global/health）尚未验证，
        //    万一新界面连不上，用户能自己退回旧版而不必等发版——失败必须可恢复。
        SectionCard("太极 Tab 界面") {
            var nativeUi by remember { mutableStateOf(TaijiPrefs.useNativeUi(ctx)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (nativeUi) "Compose 原生界面（直连 serve）" else "WebView 旧版（经本地代理）",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "原生界面响应更快、中文输入更可靠；若打不开或一直空白，可退回旧版",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = nativeUi, onCheckedChange = {
                    nativeUi = it
                    TaijiPrefs.setNativeUi(ctx, it)
                })
            }
        }

        SectionCard("Root 增强模式") {
            val hasSu = remember {
                listOf("/system/bin/su", "/system/xbin/su", "/sbin/su").any { File(it).exists() }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (hasSu) "检测到 Root 设备" else "检测到 Root 后可用",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(checked = false, enabled = hasSu, onCheckedChange = {
                    Toast.makeText(ctx, "即将在 v1.x 版本推出（chroot 后端）", Toast.LENGTH_SHORT).show()
                })
            }
        }

        // ── 荣耀 / MagicOS 保活指南（Magic 5 Pro 实测）──
        SectionCard("荣耀 / MagicOS 保活指南") {
            Text(
                text = "① 应用启动管理：设置 → 应用和服务 → 应用启动管理 → 证道 → 关闭「自动管理」，" +
                    "手动开启「允许自启动 / 关联启动 / 后台活动」\n" +
                    "② 电池优化：设置 → 电池 → 更多电池设置 → 证道 → 设为「不允许优化」\n" +
                    "③ 多任务锁定：多任务界面找到证道卡片，下滑出现小锁图标，点击锁定\n" +
                    "④ 进程意外退出时：开发者选项 → 确认「不要保留活动」未勾选",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "荣耀对前台服务较为尊重：保持「会话运行中」通知可见 + 多任务上锁，" +
                    "即可长期后台存活；即便被清理，tmux 会话恢复机制会在重进时自动回到现场。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = {
                // 电池优化白名单（用户第四批）：直接拉起系统"忽略电池优化"请求对话框；
                // 厂商定制系统不支持该入口时退回通用设置列表，仍不支持则静默。
                try {
                    ctx.startActivity(
                        Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .apply { data = android.net.Uri.parse("package:${ctx.packageName}") }
                    )
                } catch (_: Throwable) {
                    try {
                        ctx.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (_: Throwable) {
                        Toast.makeText(ctx, "请到系统设置的电池优化中手动设置", Toast.LENGTH_LONG).show()
                    }
                }
            }) { Text("一键跳转：把证道设为「不优化」") }
            TextButton(onClick = {
                // 应用详情页（启动管理/自启动设置的入口）：荣耀等国产 ROM 的保活开关都在这层
                runCatching {
                    ctx.startActivity(
                        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .apply { data = android.net.Uri.parse("package:${ctx.packageName}") }
                    )
                }.onFailure {
                    Toast.makeText(ctx, "请到系统设置 → 应用管理中找到证道", Toast.LENGTH_LONG).show()
                }
            }) { Text("打开应用详情（启动管理入口）") }
        }

        // ── 新手指南（第三批）──
        SectionCard("新手指南") {
            GuideLine("1", "主页点「安装运行环境」装好 Debian 环境；再给想用的 Agent 点「安装」。")
            GuideLine("2", "进各 Agent 内完成各自的登录 / 授权（凭据由 Agent 自己保管），会话内直接可用。")
            GuideLine("3", "进底部「终端」，直接输入 agent 命令使用（claude / hermes / opencode / agy）。")
            Spacer(Modifier.height(8.dp))
            Text(
                "常见问题",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            FaqLine("环境打不开 / 下载失败？", "用上方「修复环境」重新解压系统层，登录态会保留。")
            FaqLine("Agent 想更新？", "在终端里重跑一遍安装命令即可；系统层更新用「检查环境更新」。")
            FaqLine("我的文件在哪？", "见下方「工作区」：Agent 产出都在工作区文件夹（默认手机 Download/证道），guest 内是 /workspace。")
        }

        // ── 关于 ──
        SectionCard("关于") {
            InfoRow("版本", "${BuildConfig.VERSION_NAME} (versionCode ${BuildConfig.VERSION_CODE})")
            Spacer(Modifier.height(4.dp))
            // 外链也走同一套行式组件（右侧统一是 ›），不再是一排蓝色文字按钮
            SettingRow(
                label = "GitHub 仓库",
                value = "",
                onClick = {
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/pisces19860207/zhengdao"))
                    )
                },
            )
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            SettingRow(
                label = "问题反馈（Issues）",
                value = "",
                onClick = {
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/pisces19860207/zhengdao/issues"))
                    )
                },
            )
        }

        // 页脚留白：最后一张卡不与系统导航栏贴着
        Spacer(Modifier.height(8.dp))
    }

    // ── 修复环境二次确认（Compose 版）──
    if (repairConfirm) {
        AlertDialog(
            onDismissRequest = { repairConfirm = false },
            title = { Text("修复环境") },
            text = { Text("将重新解压 Debian 系统层（约 30 秒 + Agent 重装时间）。登录态与工作区保留。需要本地已有安装包（cache 或 Download/证道）。确定？") },
            confirmButton = {
                TextButton(onClick = {
                    repairConfirm = false
                    val candidates = listOf(
                        File(ctx.cacheDir, "debian-13.7-base-arm64.tar.zst"),
                        File("/storage/emulated/0/Download/证道/debian-13.7-base-arm64.tar.zst"),
                    ).firstOrNull { it.isFile }
                    if (candidates == null) {
                        Toast.makeText(ctx, "未找到本地安装包：请先在终端重新下载一次", Toast.LENGTH_LONG).show()
                    } else {
                        Thread {
                            try {
                                val archive = File(ctx.cacheDir, candidates.name)
                                if (archive.absolutePath != candidates.absolutePath) candidates.copyTo(archive, true)
                                com.example.zhengdao.rootfs.RootfsInstaller.install(ctx, archive) { }
                                android.os.Handler(ctx.mainLooper).post {
                                    Toast.makeText(ctx, "修复完成：环境已重置，登录态保留", Toast.LENGTH_LONG).show()
                                }
                            } catch (t: Throwable) {
                                android.os.Handler(ctx.mainLooper).post {
                                    Toast.makeText(ctx, "修复失败: ${t.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }.start()
                    }
                }) { Text("修复") }
            },
            dismissButton = {
                TextButton(onClick = { repairConfirm = false }) { Text("取消") }
            },
        )
    }

    // ── 上次运行日志查看弹窗 ──
    if (showPrevLog && prevLogText != null) {
        AlertDialog(
            onDismissRequest = { showPrevLog = false },
            title = { Text("上次运行日志") },
            text = {
                Text(
                    prevLogText!!.takeLast(6000),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("zhengdao-prevlog", prevLogText))
                    Toast.makeText(ctx, "已复制全部日志", Toast.LENGTH_SHORT).show()
                }) { Text("复制全部") }
            },
            dismissButton = { TextButton(onClick = { showPrevLog = false }) { Text("关闭") } },
        )
    }

    // ── 发现新版本弹窗（updateMsg 驱动）：确认后在应用内下载到公共缓存并安装 ──
    updateMsg?.takeIf { it.startsWith("发现新版本") }?.let { msg ->
        AlertDialog(
            onDismissRequest = { updateMsg = null },
            title = { Text("发现环境更新") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = {
                    val url = pendingUpdateUrl
                    updateMsg = null
                    if (url == null) {
                        ctx.startActivity(
                            Intent(Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://github.com/pisces19860207/zhengdao/releases"))
                        )
                        return@TextButton
                    }
                    val expectedSha = pendingUpdateSha
                    Thread {
                        try {
                            android.os.Handler(ctx.mainLooper).post {
                                Toast.makeText(ctx, "开始下载新版本环境…", Toast.LENGTH_SHORT).show()
                            }
                            val archive = com.example.zhengdao.rootfs.RootfsCache.archiveFor(ctx, url)
                            RootfsDownloader.download(
                                urls = listOf(url),
                                dest = archive,
                                shaUrl = "$url.sha256",
                            ) { done, total ->
                                if (total > 0 && done * 100 / total % 20 == 0L) {
                                    android.os.Handler(ctx.mainLooper).post {
                                        Toast.makeText(
                                            ctx,
                                            "下载中 ${done * 100 / total}%",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                            if (!expectedSha.isNullOrBlank()) {
                                RootfsDownloader.verifySha256(archive, expectedSha)
                            }
                            RootfsInstaller.ensureFreeSpace(ctx, archive.length())
                            RootfsInstaller.install(ctx, archive) { }
                            com.example.zhengdao.rootfs.RootfsCache.pruneKeep(ctx)
                            android.os.Handler(ctx.mainLooper).post {
                                Toast.makeText(ctx, "环境更新完成，重进终端生效", Toast.LENGTH_LONG).show()
                            }
                        } catch (t: Throwable) {
                            android.os.Handler(ctx.mainLooper).post {
                                Toast.makeText(ctx, "更新失败：${t.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }.start()
                }) { Text("下载并安装") }
            },
            dismissButton = { TextButton(onClick = { updateMsg = null }) { Text("取消") } },
        )
    }
}

/**
 * 分组卡片（iOS 设置语言）：标题在卡片外做小号灰标签，卡片本身是白底 + 0.5dp 发丝描边 + 16dp 圆角。
 * 旧版把标题塞进卡片里、且是 16sp 半粗——十几张卡读下来每张都是"标题 + 一堆小字"，
 * 分组反而没有层级。标题移到卡外后，视线先落到组标签、再落到这一组的内容。
 */
@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
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
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * 设置页统一的可点行：主标签 + 右侧状态文字 + "›"，整行可点、行高一致。
 * 旧版三行权限各有各的写法（行尾有的挂按钮、有的挂 ✅ emoji、有的挂 ›），一页凑出三套交互视觉。
 */
@Composable
private fun SettingRow(
    label: String,
    value: String,
    valueColor: Color = Color.Unspecified,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (valueColor == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant
            else valueColor,
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
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 新手指南：三步上手的单行（蓝色序号 + 说明）。 */
@Composable
private fun GuideLine(number: String, text: String) {
    Row(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 5.dp)) {
        Text(
            text = "$number.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(20.dp),
        )
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 常见问题单条：加粗问题 + 答案（问题用与正文同级字号，答案降一档）。 */
@Composable
private fun FaqLine(question: String, answer: String) {
    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 6.dp)) {
        Text(
            text = question,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = answer,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 工作区文件夹浏览器（0.6）：直读共享存储（/storage/emulated/0），逐级进出，
 * 「选定此文件夹」即用。不用 SAF（MagicOS 禁选根目录），无任何手输框（用户定）。
 */
@Composable
fun WorkspaceFolderPicker(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val sharedRoot = "/storage/emulated/0"
    var current by remember { mutableStateOf(sharedRoot) }
    val entries = remember(current) {
        runCatching {
            File(current).listFiles { f -> f.isDirectory }
                ?.sortedBy { it.name.lowercase() }
                ?.map { it.name }
                ?: emptyList()
        }.getOrDefault(emptyList())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择工作区文件夹", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                Text(
                    text = current.removePrefix(sharedRoot).ifBlank { "/（内部存储根目录）" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                if (current != sharedRoot) {
                    TextButton(onClick = { current = File(current).parent ?: sharedRoot }) { Text("← 上一级") }
                }
                Column(modifier = Modifier.height(280.dp).verticalScroll(rememberScrollState())) {
                    if (entries.isEmpty()) {
                        Text("（无子文件夹）", style = MaterialTheme.typography.bodySmall)
                    }
                    entries.forEach { name ->
                        Text(
                            text = "📁 $name",
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { current = File(current, name).absolutePath }
                                .padding(vertical = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Agent 产出会写入所选文件夹（手机文件管理器可见）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onPick(current) }) { Text("选定此文件夹") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
