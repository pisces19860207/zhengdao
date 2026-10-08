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
import com.example.zhengdao.util.HumanizeError
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

/** 状态行"就绪／已授权"用的绿：比主题 tertiary(#34C759) 更深，浅底上作正文色才有对比度。 */
private val ReadyGreen = IOSReadyGreen

/**
 * 索引里的字节数 → 人读大小。`<= 0` = 索引没给这个字段（老格式/字段缺失），
 * 显示"大小未知"而不是"约 0 MB"（后者会让用户以为包是空的）。
 */
private fun bytesMbText(bytes: Long): String =
    if (bytes > 0) "约 ${(bytes + 524_288) / 1_048_576} MB" else "大小未知"

/** 设置偏好（工作区模式 / 已安装环境版本登记）。 */
object Settings {
    fun prefs(ctx: android.content.Context) =
        ctx.getSharedPreferences("zhengdao-settings", android.content.Context.MODE_PRIVATE)
}

/** 设置页（第二批）：存储占用 / 修复环境 / 工作区 / 检查更新 / Root / 关于。 */
@Composable
fun SettingsScreen(
    onOpenTerminal: (autocmd: String?, agentId: String?) -> Unit = { _, _ -> },
    onOpenPlugins: () -> Unit = {},
) {
    val ctx = LocalContext.current
    // 这里原先有一份 SystemInfoProvider.collect() 的结果缓存，但全页从未读过它——
    // 设置页只展示存储占用。留着会每次进页白跑一次采集（v1.1 起采集还包含 node
    // 二进制的版本扫描），故删掉。
    // 2026-10-08：把"修复失败 / 回退失败"两个 Toast 升级为 Snackbar——MD3 不推荐用
    // Toast 喂需要"看完详情"的错误。SnackbarHost 装在顶层 Box 底部，配合
    // 复用 showPrevLog 让用户能从 action 直达日志原文（t.message 仍落 RunLog 不丢）。
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var repairConfirm by remember { mutableStateOf(false) }
    var updateMsg by remember { mutableStateOf<String?>(null) }
    var pendingUpdateUrl by remember { mutableStateOf<String?>(null) }
    var pendingUpdateSha by remember { mutableStateOf<String?>(null) }

    /**
     * 检查更新时拿到的**整条索引**（而不是只留 env）：全量装完后要按"信任锚"规则决定写不写
     * `env=` 行 —— 只有索引 sha256 与实际校验通过的 sha256 一致才写
     * （见 [RootfsInstaller.envForMarker]，理由：索引可能因 CI 半途失败而陈旧）。
     * null = 走的不是索引路径（老 manifest 降级）或索引取不到 ⇒ 装完不写 env 行。
     */
    var pendingIndex by remember { mutableStateOf<RootfsIndex?>(null) }

    /** 索引给的增量补丁（基线与本机相符时才会被赋上）；非 null = 弹窗确认后先试增量。 */
    var pendingPatch by remember { mutableStateOf<PatchRef?>(null) }
    var prevLogText by remember { mutableStateOf<String?>(null) }
    var archiveCount by remember { mutableStateOf(0) }
    var showPrevLog by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var rootfsMb by remember { mutableStateOf(0L) }
    var homeMb by remember { mutableStateOf(0L) }
    var cacheMb by remember { mutableStateOf(0L) }
    var pluginCount by remember { mutableStateOf(0) }
    var wsPickerOpen by remember { mutableStateOf(false) }

    // ── 权限（存储 + 网络自检）──
    fun storageGrantedNow(): Boolean =
        com.example.zhengdao.terminal.ProotLauncher.storageGranted(ctx)   // v1.3 E2：单一判定源

    /** 后台线程 → 主线程 Toast 的统一出口（子线程不能直接弹 Toast）。 */
    fun toastOnMain(text: String, long: Boolean = false) {
        android.os.Handler(ctx.mainLooper).post {
            Toast.makeText(ctx, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
    }

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
                // 2026-10-08：异常原文 → 人话（[HumanizeError]）。原文已落日志
                Toast.makeText(ctx, "打开失败：${HumanizeError.title(e2)}", Toast.LENGTH_SHORT).show()
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
        // 已启用插件数（入口行上显示，让用户不用点进去也知道有没有装）
        // 只数太极实例——插件归 OpenCode 管，终端那份自装的不在本 App 的管理范围。
        pluginCount = withContext(Dispatchers.IO) {
            PluginManager.readSpecs(PluginManager.taijiConfig(ctx)).size
        }
    }

    Box(Modifier.fillMaxSize()) {
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
            // 用户 2026-10-08：「下载的东西都放到 download 证道文件夹里」——那么面板就该把
            // 这个位置写出来，用户才能自己进去看、自己删（此前只有私有 cache，看不也删不掉）。
            Text(
                text = "存放位置：${com.example.zhengdao.terminal.Store.root(ctx).path}" +
                    "（cache 包缓存 / logs 日志 / agents 脚本与账本；rootfs、opencode 是安装包）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "一档（极低风险）：npm / uv / apt 包缓存，清理命令在终端里执行、输出可见。" +
                    "二档（低风险）：临时目录里带固定命名指纹、且 24 小时内没动过的残留文件，" +
                    "不需要终端会话，且 App 启动时若超过 500MB 会自动清一次。" +
                    "OpenCode/Hermes 工具链、rootfs 系统层、用户数据永不清。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))

            // 一档：官方 CLI（命令在 guest 里跑，输出可见、可中断）。
            // ⚠️ **不再拿 SessionManager.isAlive() 卡门**：旧实现会话没起时只弹一句
            //    Toast，用户看到的就是"点了不跳转"（2026-10-07 用户当面指出）。
            //    拉起会话这件事本就该由终端自己做——TerminalActivity 的
            //    ensureStartedAndAttach() 会起会话并注入 autocmd。这里只挡真正
            //    跑不了的情况：环境未安装（此时终端是回退 shell，命令无处可去）。
            TextButton(onClick = {
                if (!AppState.rootfsInstalled(ctx)) {
                    Toast.makeText(ctx, "运行环境尚未安装，无法在终端中清理", Toast.LENGTH_SHORT).show()
                } else {
                    onOpenTerminal(com.example.zhengdao.terminal.CacheCleaner.guestCommand(), null)
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

        // ── 插件（2026-10-07 新增）：把 OpenCode 插件从配置文件里显性化 ──
        // 背景：此前插件被硬编码写进 opencode.json，用户既看不见也关不掉；
        // 装了个"记忆插件"占 2.6GB 却从未产出记忆，直到全量排查才发现。
        // 现在给一个正规入口：可见、可开关、可清缓存。
        SectionCard("插件") {
            SettingRow(
                label = "插件管理",
                value = if (pluginCount > 0) "$pluginCount 个已启用" else "未启用",
                onClick = onOpenPlugins,
            )
            Text(
                text = "扩展太极里 OpenCode 的能力（如跨会话记忆）。可查看已启用的插件、一键开关、清理下载缓存。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                }.onFailure {
                    // 2026-10-08：异常原文 → 人话（[HumanizeError]）
                    Toast.makeText(ctx, "打开失败：${HumanizeError.title(it)}", Toast.LENGTH_SHORT).show()
                }
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
                    // 两个探针：国内基线 + 更新源（GitHub 家族，含 gh-proxy 镜像回退）。
                    // 只探前者会在「国内通、GitHub 家族全灭」的设备上给出骗人的绿灯——
                    // 用户拿着"✅ 网络可用"却下不动环境包。见 NetSelfCheck 的类注释与 ERRATA E-034。
                    Thread {
                        val cn = NetSelfCheck.probeCn()
                        val update = NetSelfCheck.probeIndex()
                        netMsg = NetSelfCheck.summary(cn, update)
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
            Spacer(Modifier.height(4.dp))
            InstallFlow.StatusLine()
        }

        // ── Hermes 依赖环境（E-025）──
        // 事故（2026-10-08 真机）：恢复搬家包后敲 hermes 只剩三行依赖环境错误，重开 App 也一样。
        // 判定在宿主侧读文件（HermesEnv.inspect）；能纯文件修的当场修，要重建 Python 环境 /
        // 要 git checkout 补源码锁的走终端脚本（输出可见，不静默改 Hermes 的东西）。
        var hermesEnv by remember(storageTick) {
            mutableStateOf<com.example.zhengdao.terminal.HermesEnv.State?>(null)
        }
        LaunchedEffect(storageTick) {
            hermesEnv = withContext(Dispatchers.IO) {
                com.example.zhengdao.terminal.HermesEnv.inspect(ctx)
            }
        }
        SectionCard("Hermes 依赖环境") {
            val st = hermesEnv
            Text(
                text = st?.detail ?: "检测中…",
                style = MaterialTheme.typography.bodySmall,
                color = if (st != null && !st.ok) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (st != null && !st.ok && st.canRepairOnHost) {
                    OutlinedButton(onClick = {
                        Thread {
                            val home = com.example.zhengdao.terminal.HermesEnv.hermesHome(ctx)
                            com.example.zhengdao.terminal.HermesEnv.repairOnHost(home)
                            android.os.Handler(android.os.Looper.getMainLooper())
                                .post { storageTick++ }
                        }.start()
                    }) { Text("一键修正记录") }
                }
                TextButton(onClick = {
                    if (com.example.zhengdao.terminal.HermesEnv.writeRepairScript(ctx)) {
                        onOpenTerminal(com.example.zhengdao.terminal.HermesEnv.REPAIR_CMD, null)
                    } else {
                        Toast.makeText(ctx, "修复脚本写入失败", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("在终端里修复") }
            }
            Text(
                text = "搬家包恢复、或更新被中断，都会让 Hermes 的依赖环境记录指向不存在的目录，" +
                    "于是敲 hermes 直接报错退出（连 hermes update 也救不了）。终端修复过程可见：" +
                    "补回源码锁 → 清失效记录 → hermes pm repair。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            // 用户 2026-10-08：装环境时他不知道"已经在装了"，跑到这里连点「检查环境更新」——
            // 于是这里既禁用按钮，也把正在进行的安装状态直接摊在按钮下面（状态来自 InstallProgress，
            // 与终端页横幅 / 系统通知是同一份数据，不会各说各话）。
            val installState = InstallProgress.state.value
            OutlinedButton(
                enabled = !checking && !InstallProgress.isRunning(),
                onClick = {
                    checking = true
                    Thread {
                        // 待下载目标（发现新环境时由检查逻辑填入，弹窗确认后用）
                        var pendingUrl: String? = null
                        var pendingSha: String? = null
                        var pendingIdx: RootfsIndex? = null
                        var pendingPatchRef: PatchRef? = null
                        val result = try {
                            // ① 增量协议：`rootfs-index.json` 是唯一事实来源，版本按 **env 内容指纹**比对。
                            //    老逻辑拿恒定的发行版号（13.7）比，于是永远判"已是最新"——本次修掉的正是它。
                            val idx = RootfsIndexFetcher.fetch()
                            if (idx != null && !idx.env.isNullOrBlank()) {
                                val localEnv = RootfsMarker.installedEnv(ctx)
                                val ver = idx.version.ifBlank { idx.distro.ifBlank { "未知版本" } }
                                when {
                                    // 旧安装（标记里没有 env 行）：第一次更新只能全量，装完就记上 env
                                    localEnv == null -> {
                                        pendingUrl = idx.url; pendingSha = idx.sha256
                                        pendingIdx = idx
                                        "发现新版本 $ver（本地环境未记录版本，本次需全量下载，${bytesMbText(idx.size)}）"
                                    }
                                    localEnv.equals(idx.env, ignoreCase = true) ->
                                        "已是最新版本（$ver，环境 $localEnv）"
                                    else -> {
                                        pendingUrl = idx.url; pendingSha = idx.sha256
                                        pendingIdx = idx
                                        val p = idx.patch
                                        if (p != null && p.from.equals(localEnv, ignoreCase = true)) {
                                            pendingPatchRef = p
                                            "发现新版本 $ver，可增量更新（${bytesMbText(p.size)}，无需重下全量包）"
                                        } else {
                                            "发现新版本 $ver（当前环境 $localEnv，索引未给对应增量包），需全量下载，${bytesMbText(idx.size)}"
                                        }
                                    }
                                }
                            } else {
                                // ② 索引取不到：保留老 manifest 提示路径（该文件已无人维护，见 ERRATA E-033）
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
                                        // 刻意不再判定"已是最新"：旧 manifest 的 version 恒为发行版号
                                        // （13.7），拿它跟本地比得出的"最新"是假的（见 ERRATA E-033）。
                                        // 拿不到索引时只给"可下载"的事实，不替用户下结论。
                                        else -> {
                                            pendingUrl = url; pendingSha = sha
                                            "拿不到环境索引（已降级读旧 manifest）：本地 $installed、清单 $ver。" +
                                                "清单版本号恒为发行版号，不能用来判断新旧；如需更新请直接下载安装。"
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
                            }
                        } catch (t: Throwable) {
                            "检查失败（网络不可达）：${t.message}"
                        }
                        android.os.Handler(ctx.mainLooper).post {
                            updateMsg = result; checking = false
                            pendingUpdateUrl = pendingUrl
                            pendingUpdateSha = pendingSha
                            pendingIndex = pendingIdx
                            pendingPatch = pendingPatchRef
                            if (!result.startsWith("发现新版本")) {
                                Toast.makeText(ctx, result, Toast.LENGTH_LONG).show()
                            }
                        }
                    }.start()
                },
            ) { Text(if (checking) "检查中…" else "检查环境更新") }
            installState?.let { st ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "⏳ 正在安装环境（${if (st.fromLocal) "使用本地缓存包，不联网下载" else "联网下载"}）：" +
                        "${st.text}\n安装完成前不需要、也不能重复检查更新；进度同时显示在终端页顶部横幅与系统通知里。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
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

            // ── OpenCode 内置版更新入口已从这里移走（用户 2026-10-08：主打不是 OpenCode）──
            //   现在只有「太极」抽屉底部那一处（TaijiScreen 的 OcVersionFooter）。
            //   OpenCode 随 APK 内置、开箱即用；真要重装/升级去太极抽屉。
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
                Spacer(Modifier.height(4.dp))
                InstallFlow.StatusLine()
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
                                // 这条路自己写着"约几分钟"，此前却只有结束那一条 Toast（E-036 §7）
                                InstallFlow.start(
                                    ctx,
                                    "回退环境：用本地安装包 ${target.name} 重装系统层（不联网下载）",
                                    fromLocal = true,
                                )
                                try {
                                    InstallFlow.update(ctx, "正在解压系统层（没有细粒度进度，请留在本页或看通知栏）…")
                                    RootfsInstaller.ensureFreeSpace(ctx, target.length())
                                    RootfsInstaller.install(ctx, target) { }
                                    com.example.zhengdao.rootfs.RootfsCache.pruneKeep(ctx)
                                    InstallFlow.finish(ctx, "回退完成：已换回 ${target.name}，重进终端生效（未联网下载）")
                                    android.os.Handler(ctx.mainLooper).post { storageTick++ }
                                } catch (t: Throwable) {
                                    InstallFlow.fail(ctx, "回退失败：${HumanizeError.title(t)}")
                                    // 2026-10-08：Toast → Snackbar（带"查看日志"action）。
                                    // t.message 原文仍落 InstallFlow.fail + RunLog（诊断不丢），
                                    // 用户看的用人话，进 Snackbar 后可点 action 跳到 showPrevLog AlertDialog。
                                    // 必须在主线程弹——scope 是 Composable 的 CoroutineScope，
                                    // 但 rememberCoroutineScope() 默认走 Dispatchers.Main.immediate。
                                    scope.launch {
                                        val r = snackbarHostState.showSnackbar(
                                            message = "回退失败：${HumanizeError.title(t)}",
                                            actionLabel = "查看日志",
                                            duration = SnackbarDuration.Indefinite,
                                        )
                                        if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                                            // 触发复用 showPrevLog AlertDialog（SettingsScreen:1112 的）
                                            showPrevLog = true
                                        }
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

        // ── 运行日志（2026-10-08：搬到 Download/证道/logs/，卸载 App 也不丢；
        //    用户 m08482：「那些日志都是方便给你们这些 agent 看查哪里有问题的，所以要留着」
        //    ⇒ RunLog 不再"没出错就删掉上一轮"，改成每轮归档一份，只受份数/体积上限约束）──
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                archiveCount = com.example.zhengdao.rootfs.RunLog.archives().size
                prevLogText = if (com.example.zhengdao.rootfs.RunLog.lastRunHadErrors(ctx))
                    (com.example.zhengdao.rootfs.RunLog.latestArchive()
                        ?: com.example.zhengdao.rootfs.RunLog.prevFile())?.readText()
                else null
            }
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
                "zhengdao-log.txt = 本轮运行；zhengdao-log.<时间>.txt = 历次运行的存档" +
                    "（留最近 20 份 / 最多 20 MB，从最旧的开始轮转；不会因为「这轮没出错」就删）；" +
                    "errors.log = 历次错误汇总（只记错误行）。用文件管理器可直接打开，也可以直接丢给我们查问题。",
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
            if (prevLogText != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "上次运行检测到错误，日志已保留（可直接复制反馈）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { showPrevLog = true }) { Text("查看上次日志") }
            }
            if (archiveCount > 3) {
                TextButton(onClick = {
                    val n = com.example.zhengdao.rootfs.RunLog.pruneArchivesKeep(ctx, keep = 3)
                    archiveCount = com.example.zhengdao.rootfs.RunLog.archives().size
                    Toast.makeText(ctx, "已删掉 $n 份旧日志，保留最近 3 份", Toast.LENGTH_SHORT).show()
                }) { Text("只留最近 3 份", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }

        // ── Root 增强模式 ──
        // （旧「太极 Tab 界面」回退开关已删——v1.1.1 阶段 3：原生 UI 过真机验收后，
        //   WebView + LocalProxy 旧路径整体移除，开关失去意义。）

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

    // 2026-10-08：Snackbar 出口（替换 828/1080 的"回退失败/修复失败" Toast）。
    // BottomCenter 让它浮在 verticalScroll 内容之上不抢内容。
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.align(Alignment.BottomCenter),
    )
    }

    // ── 修复环境二次确认（Compose 版）──
    if (repairConfirm) {
        AlertDialog(
            onDismissRequest = { repairConfirm = false },
            title = { Text("修复环境") },
            text = { Text("将重新解压 Debian 系统层（约 30 秒 + Agent 重装时间）。登录态与工作区保留。需要本地已有安装包（Download/证道/rootfs 缓存，或 Download/证道 根目录里的安装包）。确定？") },
            confirmButton = {
                TextButton(onClick = {
                    repairConfirm = false
                    val candidates = (
                        listOf(
                            File("/storage/emulated/0/Download/证道/debian-13.7-base-arm64.tar.zst"),
                        ) + com.example.zhengdao.rootfs.RootfsCache.listArchives(ctx)
                        ).firstOrNull { it.isFile && it.length() > 100_000_000L }
                    if (candidates == null) {
                        Toast.makeText(ctx, "未找到本地安装包：请先在终端重新下载一次", Toast.LENGTH_LONG).show()
                    } else {
                        Thread {
                            // 进度走 InstallFlow（横幅状态 + 通知栏常驻 + RunLog）——此前这条路
                            // 从头到尾只有两条 Toast，用户看到的就是"点了没反应"（E-036 §7）。
                            InstallFlow.start(
                                ctx,
                                "修复环境：用本地安装包（${candidates.length() / (1024 * 1024)} MB）重新解压系统层，不联网下载",
                                fromLocal = true,
                            )
                            try {
                                InstallFlow.update(ctx, "正在准备安装包：${candidates.name}")
                                val archive = File(ctx.cacheDir, candidates.name)
                                if (archive.absolutePath != candidates.absolutePath) candidates.copyTo(archive, true)
                                InstallFlow.update(ctx, "正在解压系统层（没有细粒度进度，约 30 秒～几分钟）…")
                                com.example.zhengdao.rootfs.RootfsInstaller.install(ctx, archive) { }
                                InstallFlow.finish(
                                    ctx,
                                    "修复完成：环境已重置，登录态与工作区保留（本次未联网下载）",
                                )
                                android.os.Handler(ctx.mainLooper).post { storageTick++ }
                            } catch (t: Throwable) {
                                InstallFlow.fail(ctx, "修复失败：${HumanizeError.title(t)}")
                                // 2026-10-08：Toast → Snackbar（带"查看日志"action），见 :842 注释
                                scope.launch {
                                    val r = snackbarHostState.showSnackbar(
                                        message = "修复失败：${HumanizeError.title(t)}",
                                        actionLabel = "查看日志",
                                        duration = SnackbarDuration.Indefinite,
                                    )
                                    if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                                        showPrevLog = true
                                    }
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
                    val patchRef = pendingPatch
                    val index = pendingIndex
                    Thread {
                        // 增量失败会写这里的原因，随最终结论一起告诉用户（"回退全量"必须可见）
                        var fallbackNote = ""
                        try {
                            // 进度走 InstallFlow：通知栏常驻进度条 + 设置页状态行 + RunLog。
                            // 此前是每 20% 闪一条 Toast（"下载中 20%"），正是用户说的
                            // "提示时间有点短……我以为要重新下载呢"（E-036 §7）。
                            InstallFlow.start(ctx, "检查环境更新：开始下载新版本环境包", fromLocal = false)
                            val archive = RootfsCache.archiveFor(ctx, url)

                            // ── ① 先试增量（协议 §6）：只有索引给了补丁、且本机基线正是补丁基线时才走 ──
                            var deltaDone = false
                            if (patchRef != null && RootfsDelta.canApply(ctx, patchRef)) {
                                val deltaFile = RootfsCache.deltaFor(ctx, patchRef.url)
                                try {
                                    InstallFlow.update(ctx, "正在下载增量补丁（${bytesMbText(patchRef.size)}）…")
                                    RootfsDownloader.download(
                                        urls = RootfsDownloader.withMirrorFallback(patchRef.url),
                                        dest = deltaFile,
                                        shaUrls = RootfsDownloader.withMirrorFallback("${patchRef.url}.sha256"),
                                    ) { done, total ->
                                        if (total > 0 && done * 100 / total % 10 == 0L) {
                                            val pct = (done * 100 / total).toInt()
                                            InstallFlow.update(
                                                ctx,
                                                "增量补丁下载中 $pct%（${done / (1024 * 1024)}/${total / (1024 * 1024)} MB）",
                                                pct,
                                            )
                                        }
                                    }
                                    // 索引给的补丁 sha256 是硬校验（协议 §4）
                                    RootfsDownloader.verifySha256(deltaFile, patchRef.sha256)
                                    RootfsInstaller.ensureFreeSpace(ctx, deltaFile.length())
                                    val info = RootfsDelta.readPatchInfo(deltaFile)
                                        ?: throw RootfsInstaller.InstallFailed("补丁元数据缺失或不可读")
                                    RootfsDelta.apply(ctx, deltaFile, info)
                                    // 增量成功后照旧做一次缓存整理（与全量路径一致）
                                    RootfsCache.pruneKeep(ctx)
                                    deltaDone = true
                                    // 让用户明确知道"这次没有重下 300MB"——他上次的误会正来自这里
                                    InstallFlow.update(
                                        ctx,
                                        "增量补丁已应用（本次只下了 ${bytesMbText(patchRef.size)}，没有重下完整包）",
                                    )
                                } catch (t: Throwable) {
                                    // 增量路径的任何失败（含基线不符 BaseMismatch）都回退全量：
                                    // applyTo 是原子的——此时 rootfs 要么没动，要么已完整换成新环境
                                    fallbackNote = "增量更新失败（${t.message}），已回退全量下载；"
                                    com.example.zhengdao.rootfs.RunLog.log("增量更新失败，回退全量：${t.message}")
                                } finally {
                                    RootfsCache.cleanupDelta(ctx, deltaFile)
                                }
                            }

                            if (!deltaDone) {
                                // ── ② 全量下载安装（原流程，也是增量失败的唯一兜底）──
                                val verifiedSha = RootfsDownloader.download(
                                    // 镜像兜底（v1.2 B3）：环境更新走的是同一条 github 直链
                                    urls = RootfsDownloader.withMirrorFallback(url),
                                    dest = archive,
                                    shaUrls = RootfsDownloader.withMirrorFallback("$url.sha256"),
                                ) { done, total ->
                                    if (total > 0 && done * 100 / total % 10 == 0L) {
                                        val pct = (done * 100 / total).toInt()
                                        InstallFlow.update(
                                            ctx,
                                            "环境包下载中 $pct%（${done / (1024 * 1024)}/${total / (1024 * 1024)} MB，可离开本页）",
                                            pct,
                                        )
                                    }
                                }
                                if (!expectedSha.isNullOrBlank()) {
                                    RootfsDownloader.verifySha256(archive, expectedSha)
                                }
                                // 信任锚（用户 2026-10-08 规则）：只有索引 sha256 == 实际校验通过的 sha256
                                // 才敢把索引的 env 写进标记；否则照装不写 env ⇒ 下次自然走全量。
                                val actualSha = verifiedSha ?: expectedSha
                                val envToWrite = RootfsInstaller.envForMarker(index?.env, index?.sha256, actualSha)
                                if (index != null && envToWrite == null) {
                                    com.example.zhengdao.rootfs.RunLog.log(
                                        "索引 sha256 与实际校验值不一致（或索引字段缺失），本次安装不写 env 标记"
                                    )
                                }
                                InstallFlow.update(ctx, "正在解压系统层（没有细粒度进度，请勿离开本页）…")
                                RootfsInstaller.ensureFreeSpace(ctx, archive.length())
                                RootfsInstaller.install(ctx, archive, envToWrite) { }
                                RootfsCache.pruneKeep(ctx)
                            }
                            InstallFlow.finish(ctx, fallbackNote + "环境更新完成，重进终端生效")
                            android.os.Handler(ctx.mainLooper).post { storageTick++ }
                        } catch (t: Throwable) {
                            InstallFlow.fail(ctx, "更新失败：${t.message}")
                            toastOnMain("更新失败：${t.message}", long = true)
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
internal fun SettingRow(
    label: String,
    value: String,
    valueColor: Color = Color.Unspecified,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            // 2026-10-08 走查：12dp → 14dp。bodyMedium 行高 24dp + 24dp = 48dp
            // 刚好不达标（差 4dp），这是设置页几乎所有行的交互热区。
            .padding(vertical = 14.dp),
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
        // 2026-10-08 走查：4dp → 10dp。M3 OutlinedButton 默认最小高 40dp，
        // 原来 24dp 行高 + 8dp = 32dp，被默认值兜到 40dp 仍不足 48dp；
        // 现在内容高 44dp，筛选 chip 这类"次要但要重复点"的控件按得准。
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
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
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { current = File(current, name).absolutePath }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FolderGlyph(tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(6.dp))
                            Text(name, style = MaterialTheme.typography.bodyMedium)
                        }
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
