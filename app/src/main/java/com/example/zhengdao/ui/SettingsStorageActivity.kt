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
import com.example.zhengdao.keepalive.CoroutineCrashHandler
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
    val scope = rememberCoroutineScope { CoroutineCrashHandler }
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
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
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

    SettingsSubPage(title = stringResource(R.string.settings_storage), onBack = onBack, snackbar = snackbarHostState) {
        // ── 存储占用 ──
        SectionCard(stringResource(R.string.storage_usage_title)) {
            // 2026-10-10 UIUX 审计 P1-5：未统计完前显示"统计中…"，避免一进设置页
            // 看到三个 0 MB 误以为 App 占空或统计坏了。
            InfoRow(
                stringResource(R.string.storage_row_rootfs),
                if (rootfsMb < 0) stringResource(R.string.settings_status_measuring) else "$rootfsMb MB",
            )
            InfoRow(
                stringResource(R.string.storage_row_home),
                if (homeMb < 0) stringResource(R.string.settings_status_measuring) else "$homeMb MB",
            )
            InfoRow(
                stringResource(R.string.storage_row_cache),
                if (cacheMb < 0) stringResource(R.string.settings_status_measuring) else "$cacheMb MB",
            )

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
                            // 数据层分组名是匹配契约（"hermes 工具"等原样比较），展示层换白话——
                            // 用户反馈：「不知道清理的是什么，专业人才看得懂」。
                            text = when (item.group) {
                                "hermes 工具" -> "装 hermes 工具时下载的文件"
                                "hermes 自检" -> "hermes 自检记录"
                                "公共区安装脚本" -> "安装时下载的脚本"
                                else -> item.group
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    InfoRow(
                        item.label,
                        "${item.mb} MB · " + if (item.removable) {
                            stringResource(R.string.storage_tag_removable)
                        } else {
                            stringResource(R.string.storage_tag_keep)
                        },
                    )
                }
            }

            // B：可选工具那一组。只删 StorageAudit.OPTIONAL_TOOL_NAMES 命中的目录；
            // python/node/uv/npm 等核心运行时、ffmpeg（必需包）、以及 **hermes 自己装的扩展**
            // （浏览器 agent-browser + chromium，用户 2026-10-11 点名保留；桌面自动化 cua-driver
            // 同一天深夜一并划入）永不在删除范围内。
            // ⚠️ 2026-10-11 深夜起 OPTIONAL_TOOL_NAMES **是空表** ⇒ optMb 恒为 0 ⇒ 这个入口
            // 不会渲染（下方 `if (optMb > 0)`）。代码与弹窗文案留着：哪天要放开某个工具，
            // 把它挪进 StorageAudit 的 OPTIONAL 表，这里就自动出现，不用再改 UI。
            // ⚠️ "hermes 工具" 是 StorageAudit 侧的分组名（数据契约，不是界面文案）——
            // 这里按原样匹配，不做 i18n 抽取，改了会破坏分组匹配。
            val optMb = audit.filter {
                it.removable && it.group == "hermes 工具"
            }.sumOf { it.mb }
            var optConfirm by remember { mutableStateOf(false) }
            if (optMb > 0) {
                Spacer(Modifier.height(4.dp))
                TextButton(enabled = !cleaning, onClick = { optConfirm = true }) {
                    Text(
                        if (cleaning) stringResource(R.string.common_cleaning)
                        else stringResource(R.string.storage_opt_btn, optMb),
                    )
                }
                if (optConfirm) {
                    AlertDialog(
                        onDismissRequest = { optConfirm = false },
                        title = { Text(stringResource(R.string.storage_opt_title)) },
                        text = {
                            Column {
                                Text(stringResource(R.string.storage_opt_free, optMb))
                                Spacer(Modifier.height(6.dp))
                                Text(stringResource(R.string.storage_opt_desc))
                                Spacer(Modifier.height(6.dp))
                                Text(stringResource(R.string.storage_opt_browser))
                                Spacer(Modifier.height(6.dp))
                                Text(stringResource(R.string.storage_opt_ffmpeg))
                                Spacer(Modifier.height(6.dp))
                                Text(stringResource(R.string.storage_opt_keeps))
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
                                            ctx.getString(
                                                R.string.storage_opt_deleted,
                                                count,
                                                CacheCleaner.bytesToMb(freed),
                                            )
                                        else ctx.getString(R.string.storage_opt_none)
                                    },
                                )
                            }) { Text(if (cleaning) stringResource(R.string.common_cleaning) else stringResource(R.string.common_clean)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { optConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
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
                    Text(stringResource(R.string.storage_script_btn))
                }
                if (scriptConfirm) {
                    AlertDialog(
                        onDismissRequest = { scriptConfirm = false },
                        title = { Text(stringResource(R.string.storage_script_title)) },
                        text = {
                            Column {
                                Text(stringResource(R.string.storage_script_desc1))
                                Spacer(Modifier.height(6.dp))
                                Text(stringResource(R.string.storage_script_desc2))
                                Spacer(Modifier.height(6.dp))
                                Text(stringResource(R.string.storage_script_keeps))
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
                                            ctx.getString(
                                                R.string.storage_script_cleared,
                                                count,
                                                CacheCleaner.bytesToMb(freed),
                                            )
                                        else ctx.getString(R.string.storage_script_none)
                                    },
                                )
                            }) { Text(if (cleaning) stringResource(R.string.common_cleaning) else stringResource(R.string.common_clean)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { scriptConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
                        },
                    )
                }
            }
        }
        // ── 缓存清理（三档白名单：一档走 guest 官方命令，二档走宿主侧；详见 CacheCleaner）──
        SectionCard(stringResource(R.string.storage_cache_title)) {
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
                    text = stringResource(R.string.settings_status_measuring),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // 注意：这里的 name 是 CacheCleaner.measure() 返回的目录名（数据侧 key，
                // 同时也是展示文案）——按原样显示，不做 i18n 抽取。
                cacheSizes.forEach { (name, mb) -> InfoRow(name, "$mb MB") }
            }
            Spacer(Modifier.height(6.dp))
            // 用户 2026-10-08：「下载的东西都放到 download 证道文件夹里」——那么面板就该把
            // 这个位置写出来，用户才能自己进去看、自己删（此前只有私有 cache，看不也删不掉）。
            Text(
                text = stringResource(
                    R.string.storage_cache_location,
                    com.example.zhengdao.terminal.Store.root(ctx).path,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            // 三档说明是解释性长句 ⇒ 折叠（走查报告 §5.6）
            ExpandableHint(stringResource(R.string.storage_cache_tiers))
            Spacer(Modifier.height(2.dp))

            // 一档：官方 CLI（命令在 guest 里跑，输出可见、可中断）。
            // ⚠️ **不再拿 SessionManager.isAlive() 卡门**：旧实现会话没起时只弹一句
            //    Toast，用户看到的就是"点了不跳转"（2026-10-07 用户当面指出）。
            //    拉起会话这件事本就该由终端自己做——TerminalActivity 的
            //    ensureStartedAndAttach() 会起会话并注入 autocmd。这里只挡真正
            //    跑不了的情况：环境未安装（此时终端是回退 shell，命令无处可去）。
            TextButton(onClick = {
                if (!AppState.rootfsInstalled(ctx)) {
                    Toast.makeText(
                        ctx,
                        ctx.getString(R.string.storage_cache_not_installed),
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    onOpenTerminal(com.example.zhengdao.terminal.CacheCleaner.guestCommand(), null)
                }
            }) { Text(stringResource(R.string.storage_cache_guest_btn)) }

            // 二档：宿主侧直接删（rootfs 就是宿主上的普通目录），因此**不需要会话**
            val tempMb = cacheSizes["临时文件"] ?: 0L
            var tempConfirm by remember { mutableStateOf(false) }
            TextButton(enabled = tempMb > 0 && !cleaning, onClick = { tempConfirm = true }) {
                Text(
                    when {
                        cleaning -> stringResource(R.string.common_cleaning)
                        tempMb > 0 -> stringResource(R.string.storage_temp_btn, tempMb)
                        else -> stringResource(R.string.storage_temp_none)
                    }
                )
            }
            if (tempConfirm) {
                AlertDialog(
                    onDismissRequest = { tempConfirm = false },
                    title = { Text(stringResource(R.string.storage_temp_title)) },
                    text = {
                        Text(stringResource(R.string.storage_temp_desc, tempMb))
                    },
                    confirmButton = {
                        TextButton(enabled = !cleaning, onClick = {
                            tempConfirm = false
                            // 2026-10-11：挪到 IO（见 runClean）。清理前后 storageTick++ 由它统一做。
                            runClean(
                                work = { 0 to CacheCleaner.cleanTempFiles(ctx) },
                                toast = { _, freed ->
                                    if (freed > 0) ctx.getString(R.string.storage_freed, CacheCleaner.bytesToMb(freed))
                                    else ctx.getString(R.string.storage_temp_cleaned_none)
                                },
                            )
                        }) { Text(if (cleaning) stringResource(R.string.common_cleaning) else stringResource(R.string.common_clean)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { tempConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
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
                        cleaning -> stringResource(R.string.common_cleaning)
                        agentMb > 0 -> stringResource(R.string.storage_agent_btn, agentMb)
                        else -> stringResource(R.string.storage_agent_btn_bare)
                    }
                )
            }
            if (agentConfirm) {
                AlertDialog(
                    onDismissRequest = { agentConfirm = false },
                    title = { Text(stringResource(R.string.storage_agent_title)) },
                    text = {
                        Column {
                            Text(stringResource(R.string.storage_agent_desc))
                            if (agentSizes.isEmpty()) {
                                Text(stringResource(R.string.storage_agent_empty))
                            } else {
                                agentSizes.forEach { (name, mb) ->
                                    Text(stringResource(R.string.storage_agent_item, name, mb))
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            // 会话是常驻的 ⇒ 不做"拒绝执行"，只把风险讲清楚，让用户自己决定
                            if (CacheCleaner.busy(ctx)) {
                                Text(stringResource(R.string.storage_agent_busy))
                                Spacer(Modifier.height(6.dp))
                            }
                            Text(stringResource(R.string.storage_agent_keeps))
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
                                    if (freed > 0) ctx.getString(R.string.storage_freed, CacheCleaner.bytesToMb(freed))
                                    else ctx.getString(R.string.storage_agent_cleaned_none)
                                },
                            )
                        }) { Text(if (cleaning) stringResource(R.string.common_cleaning) else stringResource(R.string.common_clean)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { agentConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
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
                    Text(
                        if (cleaning) stringResource(R.string.common_cleaning)
                        else stringResource(R.string.storage_gen_btn, genMb),
                    )
                }
                if (genConfirm) {
                    AlertDialog(
                        onDismissRequest = { genConfirm = false },
                        title = { Text(stringResource(R.string.storage_gen_title)) },
                        text = {
                            Column {
                                Text(stringResource(R.string.storage_gen_free, genMb))
                                Spacer(Modifier.height(6.dp))
                                Text(stringResource(R.string.storage_gen_desc))
                                Spacer(Modifier.height(6.dp))
                                Text(stringResource(R.string.storage_gen_keeps))
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
                                            ctx.getString(
                                                R.string.storage_gen_deleted,
                                                count,
                                                CacheCleaner.bytesToMb(freed),
                                            )
                                        else ctx.getString(R.string.storage_gen_none)
                                    },
                                )
                            }) { Text(if (cleaning) stringResource(R.string.common_cleaning) else stringResource(R.string.common_clean)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { genConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
                        },
                    )
                }
            }
        }
        // ── 安装包缓存（第三批）──
        SectionCard(stringResource(R.string.storage_installer_title)) {
            // 2026-10-12 无感化（§5.5 第 4 处）：打开即显上次快照，量完就地覆盖。
            var cacheText by remember {
                mutableStateOf(
                    LastValues.loadText(ctx, "installer_cache_text")
                        ?: ctx.getString(R.string.settings_status_measuring)
                )
            }
            var rollbacks by remember { mutableStateOf<List<File>>(emptyList()) }
            var rollbackConfirm by remember { mutableStateOf<File?>(null) }
            // §B 顺序 2 · P2-5：删掉非当前版本的安装包缓存是**永久删除**，先弹确认框再执行。
            var installerCleanConfirm by remember { mutableStateOf(false) }
            fun reloadCache() {
                Thread {
                    val archives = com.example.zhengdao.rootfs.RootfsCache.listArchives(ctx)
                    val dir = com.example.zhengdao.rootfs.RootfsCache.dir(ctx)
                    val current = com.example.zhengdao.rootfs.RootfsCache.currentVersion(ctx)
                    val lines = buildString {
                        appendLine(ctx.getString(R.string.storage_installer_dir, dir.path))
                        appendLine(
                            ctx.getString(
                                R.string.storage_installer_count,
                                archives.size,
                                current ?: ctx.getString(R.string.settings_status_not_installed),
                            )
                        )
                        archives.forEach {
                            appendLine(
                                ctx.getString(
                                    R.string.storage_installer_item,
                                    it.name,
                                    it.length() / (1024 * 1024),
                                )
                            )
                        }
                    }
                    val rb = com.example.zhengdao.rootfs.RootfsCache.rollbackCandidates(ctx)
                    android.os.Handler(ctx.mainLooper).post {
                        cacheText = lines
                        rollbacks = rb
                        LastValues.saveText(ctx, "installer_cache_text", lines)
                    }
                }.apply { isDaemon = true; name = "zd-cache-reload" }.start()
            }
            LaunchedEffect(Unit) { reloadCache() }
            Text(cacheText, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            // §B 顺序 2 · P2-5：按钮只负责弹确认框，Thread{} 那段破坏性动作原封不动搬进 confirmButton。
            // B-3（审计第 2 期）：下载/安装进行中点它会删掉正在下载的 .part
            // （RootfsCache.cleanupNonCurrent 删 .part）⇒ 传输出错白跑。与 :691 / :712 同口径共用 busy。
            OutlinedButton(enabled = !InstallFlow.isRunning(), onClick = { installerCleanConfirm = true }) {
                Text(stringResource(R.string.storage_installer_clean_btn))
            }
            if (installerCleanConfirm) {
                AlertDialog(
                    onDismissRequest = { installerCleanConfirm = false },
                    title = {
                        Text(
                            stringResource(R.string.storage_installer_clean_title),
                            color = zhengdaoErrorText(),
                        )
                    },
                    text = {
                        Column {
                            Text(
                                stringResource(R.string.storage_installer_clean_desc),
                                color = zhengdaoErrorText(),
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(R.string.storage_installer_clean_warn),
                                color = zhengdaoErrorText(),
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(enabled = !InstallFlow.isRunning(), onClick = {
                            installerCleanConfirm = false
                            Thread {
                                val n = com.example.zhengdao.rootfs.RootfsCache.cleanupNonCurrent(ctx)
                                android.os.Handler(ctx.mainLooper).post {
                                    Toast.makeText(
                                        ctx,
                                        ctx.getString(R.string.storage_installer_cleaned, n),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    reloadCache()
                                }
                            }.apply { isDaemon = true; name = "zd-cache-clean" }.start()
                        }) { Text(stringResource(R.string.common_clean), color = zhengdaoErrorText()) }
                    },
                    dismissButton = {
                        TextButton(onClick = { installerCleanConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
                    },
                )
            }
            if (rollbacks.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.storage_rollback_list),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                rollbacks.forEach { f ->
                    TextButton(
                        onClick = { rollbackConfirm = f },
                        enabled = !InstallFlow.isRunning(),
                    ) {
                        Text(
                            stringResource(
                                R.string.storage_rollback_to,
                                com.example.zhengdao.rootfs.RootfsCache.versionOf(f.name) ?: f.name,
                            )
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                InstallFlow.StatusLine()
            }
            if (rollbackConfirm != null) {
                val target = rollbackConfirm!!
                AlertDialog(
                    onDismissRequest = { rollbackConfirm = null },
                    title = { Text(stringResource(R.string.storage_rollback_title)) },
                    text = { Text(stringResource(R.string.storage_rollback_desc, target.name)) },
                    confirmButton = {
                        TextButton(
                            enabled = !InstallFlow.isRunning(), // #11 / E-078：与其它长流程共享 busy
                            onClick = {
                            rollbackConfirm = null
                            Thread {
                                // 这条路自己写着"约几分钟"，此前却只有结束那一条 Toast（E-036 §7）
                                InstallFlow.start(
                                    ctx,
                                    ctx.getString(R.string.storage_rollback_start, target.name),
                                    fromLocal = true,
                                )
                                try {
                                    InstallFlow.update(ctx, ctx.getString(R.string.storage_unpacking))
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
                                    InstallFlow.finish(ctx, ctx.getString(R.string.storage_rollback_done, target.name))
                                    android.os.Handler(ctx.mainLooper).post { storageTick++ }
                                } catch (t: Throwable) {
                                    InstallFlow.fail(
                                        ctx,
                                        ctx.getString(R.string.storage_rollback_failed, HumanizeError.title(t, ctx.resources)),
                                    )
                                    // 2026-10-08：Toast → Snackbar（带"查看日志"action）。
                                    // t.message 原文仍落 InstallFlow.fail + RunLog（诊断不丢），
                                    // 用户看的用人话，进 Snackbar 后点 action 由 openLogFromSnackbar()
                                    // 现场取日志并弹窗（缺日志时给指向目录的 Toast，不静默）。
                                    // 必须在主线程弹——scope 是 Composable 的 CoroutineScope，
                                    // 但 rememberCoroutineScope { CoroutineCrashHandler } 默认走 Dispatchers.Main.immediate。
                                    scope.launch {
                                        val r = snackbarHostState.showSnackbar(
                                            message = ctx.getString(
                                                R.string.storage_rollback_failed,
                                                HumanizeError.title(t, ctx.resources),
                                            ),
                                            actionLabel = ctx.getString(R.string.common_view_log),
                                            duration = SnackbarDuration.Indefinite,
                                        )
                                        if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                                            // 现场取日志再弹窗（直接置弹窗状态会因文本仍是 null 而静默无操作）
                                            openLogFromSnackbar()
                                        }
                                    }
                                }
                            }.apply { isDaemon = true; name = "zd-rollback" }.start()
                        }) { Text(stringResource(R.string.storage_rollback_btn)) }
                    },
                    dismissButton = { TextButton(onClick = { rollbackConfirm = null }) { Text(stringResource(R.string.common_cancel)) } },
                )
            }
        }
    }

    SettingsLogDialog(log = logDialogText, onDismiss = { logDialogText = null })
}

