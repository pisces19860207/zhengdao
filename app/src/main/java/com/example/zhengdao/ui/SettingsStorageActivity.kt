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

/** 二级页：存储与缓存（逐字搬自原设置页的「存储占用 / 缓存清理 / 安装包缓存」三张卡）。 */
class SettingsStorageActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            ZhengdaoTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsStoragePage(onBack = { finish() })
                }
            }
        }
    }
}

@Composable
private fun SettingsStoragePage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var storageTick by remember { mutableStateOf(0) }
    // 2026-10-12 无感化（走查报告 §5.5）：打开即显上次实测值，量完后就地覆盖。
    val lastStorage = remember { LastValues.loadStorageMb(ctx) }
    var rootfsMb by remember { mutableStateOf(lastStorage?.first ?: -1L) }
    var homeMb by remember { mutableStateOf(lastStorage?.second ?: -1L) }
    var cacheMb by remember { mutableStateOf(lastStorage?.third ?: -1L) }
    var logDialogText by remember { mutableStateOf<String?>(null) }

    /** 卡片里原本直调设置页的 `onOpenTerminal`，这里接到同一条意图上。 */
    val onOpenTerminal: (String?, String?) -> Unit = { autocmd, agentId ->
        openTerminal(ctx, autocmd, agentId)
    }

    fun openLogFromSnackbar() {
        openLogDialog(scope, ctx) { logDialogText = it }
    }

    // 回来时重量一次（清理、装包、修复环境都会改数字）。
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) storageTick++
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
        LastValues.saveStorageMb(ctx, sizes.first, sizes.second, sizes.third)
    }

    /**
     * 「清理」类按钮的统一执行器（2026-10-11 新增）。
     *
     * 用户当时的原话是「清理 agent 缓存包的时候有点卡，反应有点慢，能快一点那种吗？」。
     * 根因：这些清理是**真删目录**——npm 的 `_cacache`／uv 缓存动辄几万个小文件，
     * 旧实现还是"先量一遍再删一遍"（两遍遍历），总共十几万次 stat+unlink 全压在
     * 主线程里（按钮的 onClick 直调）。放主线程轻则掉帧，重则 ANR。
     *
     * 现在：整体挪到 [Dispatchers.IO]，期间 [cleaning] 为真 → 相关按钮禁用并显示「清理中…」，
     * 完成后统一 Toast + 让 [storageTick] 走一格（量出来的数字跟着变）。
     */
    var cleaning by remember { mutableStateOf(false) }
    fun runClean(
        work: () -> Pair<Int, Long>,
        toast: (files: Int, freed: Long) -> String,
    ) {
        if (cleaning) return
        cleaning = true
        scope.launch {
            val (files, freed) = withContext(Dispatchers.IO) {
                runCatching { work() }.getOrElse { 0 to 0L }
            }
            cleaning = false
            Toast.makeText(ctx, toast(files, freed), Toast.LENGTH_SHORT).show()
            storageTick++
        }
    }

    SettingsSubPage(title = "存储与缓存", onBack = onBack, snackbar = snackbarHostState) {
        // ── 存储占用 ──
        SectionCard("存储占用") {
            // 2026-10-10 UIUX 审计 P1-5：未统计完前显示"统计中…"，避免一进设置页
            // 看到三个 0 MB 误以为 App 占空或统计坏了。
            InfoRow("rootfs（系统层）", if (rootfsMb < 0) "统计中…" else "$rootfsMb MB")
            InfoRow("home（登录态与配置）", if (homeMb < 0) "统计中…" else "$homeMb MB")
            InfoRow("cache（下载缓存）", if (cacheMb < 0) "统计中…" else "$cacheMb MB")

            // Issue #8-D：把 A（旧依赖代）/ B（hermes 可选工具）/ C（公共区安装包）的体积摊开，
            // 数字来源是 StorageAudit（与自动清理、体检面板同一套口径，避免 E-073 那种两套账）。
            // 不可清的行也列出来并标「保留」：用户最想知道的是"哪些点了真能腾地方"。
            // 2026-10-12 无感化（§5.5）：首帧即显上次明细，量完就地覆盖，不再整列表凭空出现。
            var audit by remember(storageTick) {
                mutableStateOf(LastValues.loadAudit(ctx).orEmpty())
            }
            LaunchedEffect(storageTick) {
                audit = withContext(Dispatchers.IO) {
                    com.example.zhengdao.terminal.StorageAudit.items(ctx)
                }
                LastValues.saveAudit(ctx, audit)
            }
            if (audit.isNotEmpty()) {
                var lastGroup = ""
                audit.forEach { item ->
                    if (item.group != lastGroup) {
                        lastGroup = item.group
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = item.group,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    InfoRow(item.label, "${item.mb} MB · ${if (item.removable) "可清" else "保留"}")
                }
            }

            // B：可选工具那一组。只删 StorageAudit.OPTIONAL_TOOL_NAMES 命中的目录；
            // python/node/uv/npm 等核心运行时、ffmpeg（必需包）、以及 **hermes 自己装的扩展**
            // （浏览器 agent-browser + chromium，用户 2026-10-11 点名保留；桌面自动化 cua-driver
            // 同一天深夜一并划入）永不在删除范围内。
            // ⚠️ 2026-10-11 深夜起 OPTIONAL_TOOL_NAMES **是空表** ⇒ optMb 恒为 0 ⇒ 这个入口
            // 不会渲染（下方 `if (optMb > 0)`）。代码与弹窗文案留着：哪天要放开某个工具，
            // 把它挪进 StorageAudit 的 OPTIONAL 表，这里就自动出现，不用再改 UI。
            val optMb = audit.filter {
                it.removable && it.group == "hermes 工具"
            }.sumOf { it.mb }
            var optConfirm by remember { mutableStateOf(false) }
            if (optMb > 0) {
                Spacer(Modifier.height(4.dp))
                TextButton(enabled = !cleaning, onClick = { optConfirm = true }) {
                    Text(if (cleaning) "清理中…" else "清理可选工具（$optMb MB）")
                }
                if (optConfirm) {
                    AlertDialog(
                        onDismissRequest = { optConfirm = false },
                        title = { Text("清理可选工具？") },
                        text = {
                            Column {
                                Text("可释放约 $optMb MB。")
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "删的是 hermes 自带的桌面自动化工具（cua-driver）。" +
                                        "它只在这类会话里用得上；删了不会自己回来——" +
                                        "要用时得在终端里跑一次 hermes pm install（要联网、要等一会儿）。"
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "浏览器工具（agent-browser / chromium）不在删除范围内——" +
                                        "有它 hermes 才能开网页，保留。"
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "ffmpeg 也不在这里面：它是 hermes 的必需包，清掉之后它每次启动都会报 " +
                                        "install out of sync（系统层自带的那份它不认）。"
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "不会删除：python / node / uv / npm / ripgrep 这些核心运行时、" +
                                        "依赖环境、记忆、hermes 本体、系统文件、任何用户数据。"
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(enabled = !cleaning, onClick = {
                                optConfirm = false
                                // 2026-10-11：≈1.0G 的删除，绝不能在主线程做（见 runClean）
                                runClean(
                                    work = {
                                        com.example.zhengdao.terminal.StorageAudit
                                            .cleanOptionalTools(ctx)
                                    },
                                    toast = { count, freed ->
                                        if (count > 0)
                                            "已删除 $count 项，释放 ${CacheCleaner.bytesToMb(freed)} MB"
                                        else "没有可清理的可选工具"
                                    },
                                )
                            }) { Text(if (cleaning) "清理中…" else "清理") }
                        },
                        dismissButton = {
                            TextButton(onClick = { optConfirm = false }) { Text("取消") }
                        },
                    )
                }
            }

            // 公共区那份**安装脚本缓存**：清掉它，下次安装就会重新下载。
            // 为什么要单独给一个入口（用户 2026-10-11）：这份缓存会被"重试免下载"复用
            // （用户当初指定），可一旦脚本本身有问题——上游改了格式、代理塞了拦截页、
            // 写到一半被杀——用户看到的是**永远同一个失败**且毫无自救手段（公共区连
            // 卸载 App 都清不掉）。清掉它，下次安装重新下一份干净的。
            var scriptConfirm by remember { mutableStateOf(false) }
            if (audit.any { it.group == "公共区安装脚本" }) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { scriptConfirm = true }) {
                    Text("清理安装脚本缓存（下次安装重新下载）")
                }
                if (scriptConfirm) {
                    AlertDialog(
                        onDismissRequest = { scriptConfirm = false },
                        title = { Text("清理安装脚本缓存？") },
                        text = {
                            Column {
                                Text(
                                    "会删掉公共区 agents/scripts 里缓存的安装脚本、App 自己记的校验值、" +
                                        "以及终端里的执行副本。"
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "脚本只有几十 KB——这一步不是为了腾地方，而是让下次安装重新下载" +
                                        "一份干净的（重装被同一个失败卡住时，先点这里）。"
                                )
                                Spacer(Modifier.height(6.dp))
                                Text("不会删：Agent 账本 installed.json、装好的 Agent 本体与工具链、日志、资料库。")
                            }
                        },
                        confirmButton = {
                            TextButton(enabled = !cleaning, onClick = {
                                scriptConfirm = false
                                // 2026-10-11：挪到 IO + 期间禁用/显示「清理中…」（见 runClean）
                                runClean(
                                    work = {
                                        com.example.zhengdao.terminal.StorageAudit
                                            .cleanAgentScriptCache(ctx)
                                    },
                                    toast = { count, freed ->
                                        if (count > 0)
                                            "已清 $count 个脚本缓存文件（释放 ${CacheCleaner.bytesToMb(freed)} MB），下次安装会重新下载"
                                        else "没有脚本缓存可清"
                                    },
                                )
                            }) { Text(if (cleaning) "清理中…" else "清理") }
                        },
                        dismissButton = {
                            TextButton(onClick = { scriptConfirm = false }) { Text("取消") }
                        },
                    )
                }
            }
        }
        // ── 缓存清理（三档白名单：一档走 guest 官方命令，二档走宿主侧；详见 CacheCleaner）──
        SectionCard("缓存清理") {
            // 2026-10-12 无感化（§5.5）：首帧即显上次各目录体积，量完就地覆盖。
            var cacheSizes by remember(storageTick) {
                mutableStateOf(LastValues.loadCacheSizes(ctx))
            }
            // 量目录要遍历整棵文件树（npm 缓存动辄几万个小文件），不能放主线程——旧实现
            // 直接在组合期 remember { measure() } 里走，滚动设置页会卡。同时跟着 storageTick
            // 走：去 guest 里装完东西再回来，数字要跟着变，而不是停在进页那一刻。
            LaunchedEffect(storageTick) {
                cacheSizes = withContext(Dispatchers.IO) { CacheCleaner.measure(ctx) }
                LastValues.saveCacheSizes(ctx, cacheSizes)
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
                text = "包缓存（风险极低）：npm / uv / apt 下载的缓存，在终端里清，你能看到过程。" +
                    "临时文件（风险低）：临时目录里超过一天没动过的残留；不用开终端，App 启动时超过 500MB 也会自动清一次。" +
                    "工具链、系统文件、你的数据，永远不动。",
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
            TextButton(enabled = tempMb > 0 && !cleaning, onClick = { tempConfirm = true }) {
                Text(
                    when {
                        cleaning -> "清理中…"
                        tempMb > 0 -> "清理临时文件（$tempMb MB）"
                        else -> "无临时文件可清"
                    }
                )
            }
            if (tempConfirm) {
                AlertDialog(
                    onDismissRequest = { tempConfirm = false },
                    title = { Text("清理临时文件？") },
                    text = {
                        Text(
                            "将删除临时目录里约 $tempMb MB 的残留文件（超过一天没动过的）。\n\n" +
                                "不会删终端会话、编译缓存、锁文件，也不碰你的数据。"
                        )
                    },
                    confirmButton = {
                        TextButton(enabled = !cleaning, onClick = {
                            tempConfirm = false
                            // 2026-10-11：挪到 IO（见 runClean）。清理前后 storageTick++ 由它统一做。
                            runClean(
                                work = { 0 to CacheCleaner.cleanTempFiles(ctx) },
                                toast = { _, freed ->
                                    if (freed > 0) "已释放 ${CacheCleaner.bytesToMb(freed)} MB"
                                    else "没有可清理的临时文件"
                                },
                            )
                        }) { Text(if (cleaning) "清理中…" else "清理") }
                    },
                    dismissButton = {
                        TextButton(onClick = { tempConfirm = false }) { Text("取消") }
                    },
                )
            }

            // Agent 包缓存（2026-10-09 用户要求：「多一个清理 agent 的缓存包的按钮」）：
            // 宿主侧直接删 Agent 的包缓存 —— 不用开一次终端；同时**绝不碰**装好的工具链
            // （`.hermes/tools`）、依赖环境与记忆（`.hermes/installs`、MEMORY.md 等）。
            var agentSizes by remember(storageTick) {
                mutableStateOf<List<Pair<String, Long>>>(emptyList())
            }
            LaunchedEffect(storageTick) {
                agentSizes = withContext(Dispatchers.IO) { CacheCleaner.agentCacheMeasure(ctx) }
            }
            val agentMb = agentSizes.sumOf { it.second }
            var agentConfirm by remember { mutableStateOf(false) }
            TextButton(enabled = !cleaning, onClick = { agentConfirm = true }) {
                Text(
                    when {
                        cleaning -> "清理中…"
                        agentMb > 0 -> "清理 Agent 缓存包（$agentMb MB）"
                        else -> "清理 Agent 缓存包"
                    }
                )
            }
            if (agentConfirm) {
                AlertDialog(
                    onDismissRequest = { agentConfirm = false },
                    title = { Text("清理 Agent 缓存包？") },
                    text = {
                        Column {
                            Text("将删除下面这些能重新下载 / 重新生成的缓存：")
                            if (agentSizes.isEmpty()) {
                                Text("（这次没量到可清理的缓存）")
                            } else {
                                agentSizes.forEach { (name, mb) -> Text("· $name —— $mb MB") }
                            }
                            Spacer(Modifier.height(6.dp))
                            // 会话是常驻的 ⇒ 不做"拒绝执行"，只把风险讲清楚，让用户自己决定
                            if (CacheCleaner.busy(ctx)) {
                                Text(
                                    "⚠️ 终端会话正在运行：如果此刻正在装依赖，清理可能打断那一次安装" +
                                        "（已经装好的东西不受影响）。"
                                )
                                Spacer(Modifier.height(6.dp))
                            }
                            Text(
                                "不会删除：装好的 Agent 本体与工具链（hermes/tools、Python/Node）、" +
                                    "依赖环境与记忆、配置文件、系统文件，以及你自己的文件。" +
                                    "下次需要时这些缓存会自己重新下载。"
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(enabled = !cleaning, onClick = {
                            agentConfirm = false
                            // 2026-10-11：这就是用户报的那一处「清理 agent 缓存包的时候有点卡」。
                            // 现在整体在 IO 线程跑，且缓存清理函数已改成"边删边统计"（单遍遍历）。
                            runClean(
                                work = { 0 to CacheCleaner.cleanAgentCaches(ctx) },
                                toast = { _, freed ->
                                    if (freed > 0) "已释放 ${CacheCleaner.bytesToMb(freed)} MB"
                                    else "没有可清理的 Agent 缓存"
                                },
                            )
                        }) { Text(if (cleaning) "清理中…" else "清理") }
                    },
                    dismissButton = {
                        TextButton(onClick = { agentConfirm = false }) { Text("取消") }
                    },
                )
            }

            // hermes 旧依赖代（Issue #8 A，2026-10-09）：真机实测 `installs/<hash>/environments/`
            // 下堆了 4 代、`facts.json` 只指向其中一代 ⇒ 约 734M 是死的（每次重建依赖环境都会留一代）。
            // 这里是**用户手动**那一刀（自动那一刀在 CacheCleaner.autoCleanFirstTier）；判据全在
            // HermesEnv.prunableGenerations 里，删完当前依赖环境与记忆都不动。
            var genMb by remember(storageTick) { mutableStateOf(0L) }
            LaunchedEffect(storageTick) {
                genMb = withContext(Dispatchers.IO) {
                    CacheCleaner.bytesToMb(HermesEnv.deadGenerationsMb(HermesEnv.hermesHome(ctx)))
                }
            }
            if (genMb > 0) {
                var genConfirm by remember { mutableStateOf(false) }
                TextButton(enabled = !cleaning, onClick = { genConfirm = true }) {
                    Text(if (cleaning) "清理中…" else "清理旧版依赖文件（$genMb MB）")
                }
                if (genConfirm) {
                    AlertDialog(
                        onDismissRequest = { genConfirm = false },
                        title = { Text("清理旧版依赖文件？") },
                        text = {
                            Column {
                                Text("可释放约 $genMb MB。")
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "每次重装依赖环境都会留下一份旧的（几百 MB），" +
                                        "而记录只认得最新那份——这里删的就是没人再用的那些。" +
                                        "正在用的和最新的都会留着。"
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "不会删：正在用的依赖环境、记忆、装好的工具链、系统文件。" +
                                        "需要回到旧版时 hermes 会重装（要联网）。"
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(enabled = !cleaning, onClick = {
                                genConfirm = false
                                // 2026-10-11：同样挪到 IO（删一"代"是整棵 venv，几万个小文件）
                                runClean(
                                    work = { HermesEnv.pruneGenerations(HermesEnv.hermesHome(ctx)) },
                                    toast = { count, freed ->
                                        if (count > 0)
                                            "已删除 $count 份，释放 ${CacheCleaner.bytesToMb(freed)} MB"
                                        else "没有可清理的旧依赖文件"
                                    },
                                )
                            }) { Text(if (cleaning) "清理中…" else "清理") }
                        },
                        dismissButton = {
                            TextButton(onClick = { genConfirm = false }) { Text("取消") }
                        },
                    )
                }
            }
        }
        // ── 安装包缓存（第三批）──
        SectionCard("安装包缓存") {
            // 2026-10-12 无感化（§5.5 第 4 处）：打开即显上次快照，量完就地覆盖。
            var cacheText by remember { mutableStateOf(LastValues.loadText(ctx, "installer_cache_text") ?: "统计中…") }
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
                    android.os.Handler(ctx.mainLooper).post {
                        cacheText = lines
                        rollbacks = rb
                        LastValues.saveText(ctx, "installer_cache_text", lines)
                    }
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
                    "能回到的旧版本（只留最近 2 个安装包）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                rollbacks.forEach { f ->
                    TextButton(
                        onClick = { rollbackConfirm = f },
                        enabled = !InstallFlow.isRunning(),
                    ) {
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
                    text = { Text("会用 ${target.name} 重装系统文件（约几分钟）。登录状态和工作区都会保留。") },
                    confirmButton = {
                        TextButton(
                            enabled = !InstallFlow.isRunning(), // #11 / E-078：与其它长流程共享 busy
                            onClick = {
                            rollbackConfirm = null
                            Thread {
                                // 这条路自己写着"约几分钟"，此前却只有结束那一条 Toast（E-036 §7）
                                InstallFlow.start(
                                    ctx,
                                    "回退环境：用本地安装包 ${target.name} 重装（不联网）",
                                    fromLocal = true,
                                )
                                try {
                                    InstallFlow.update(ctx, "正在解压（没有详细进度，请留在本页或看通知栏）…")
                                    RootfsInstaller.ensureFreeSpace(ctx, target.length())
                                    // 回退到"与当前环境同源的那个包"（例如刚回退过又点了一次同一个版本）时
                                    // 保留环境指纹；换成别的版本必然对不上 ⇒ null，照装不写 env。
                                    // 判定与理由同修复路径：RootfsInstaller.envForReinstall 的 KDoc。
                                    val rollSha = com.example.zhengdao.rootfs.RootfsDownloader.sha256Of(target)
                                    val rollEnv = com.example.zhengdao.rootfs.RootfsInstaller.envForReinstall(
                                        File(ctx.filesDir, "rootfs"),
                                        rollSha,
                                    )
                                    com.example.zhengdao.rootfs.RootfsInstaller.install(ctx, target, rollEnv, rollSha) { }
                                    com.example.zhengdao.rootfs.RootfsCache.pruneKeep(ctx)
                                    InstallFlow.finish(ctx, "回退完成：已换回 ${target.name}，重进终端生效（没联网）")
                                    android.os.Handler(ctx.mainLooper).post { storageTick++ }
                                } catch (t: Throwable) {
                                    InstallFlow.fail(ctx, "回退失败：${HumanizeError.title(t)}")
                                    // 2026-10-08：Toast → Snackbar（带"查看日志"action）。
                                    // t.message 原文仍落 InstallFlow.fail + RunLog（诊断不丢），
                                    // 用户看的用人话，进 Snackbar 后点 action 由 openLogFromSnackbar()
                                    // 现场取日志并弹窗（缺日志时给指向目录的 Toast，不静默）。
                                    // 必须在主线程弹——scope 是 Composable 的 CoroutineScope，
                                    // 但 rememberCoroutineScope() 默认走 Dispatchers.Main.immediate。
                                    scope.launch {
                                        val r = snackbarHostState.showSnackbar(
                                            message = "回退失败：${HumanizeError.title(t)}",
                                            actionLabel = "查看日志",
                                            duration = SnackbarDuration.Indefinite,
                                        )
                                        if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                                            // 现场取日志再弹窗（直接置弹窗状态会因文本仍是 null 而静默无操作）
                                            openLogFromSnackbar()
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
    }

    SettingsLogDialog(log = logDialogText, onDismiss = { logDialogText = null })
}

