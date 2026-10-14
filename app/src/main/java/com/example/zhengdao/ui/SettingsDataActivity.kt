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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
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
import com.example.zhengdao.ui.theme.zhengdaoReadyGreen
import com.example.zhengdao.ui.theme.zhengdaoErrorText
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

/** 二级页：终端与资料（逐字搬自「资料库 / 权限 / 终端外观 / 工作区 / 输入回归页」五张卡）。 */
class SettingsDataActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            ZhengdaoTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsDataPage(onBack = { finish() })
                }
            }
        }
    }
}

/**
 * 状态行"就绪／已授权"用的绿：2026-10-12 起改走主题感知取色
 * （浅色 #2E7D32 / 深色 #30D158，见 theme/Theme.kt 的 zhengdaoReadyGreen()），
 * 深色主题下不再是一枚看不清的深绿。
 */

@Composable
private fun SettingsDataPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope { CoroutineCrashHandler }

    var kbStatus by remember { mutableStateOf<com.example.zhengdao.terminal.KnowledgeBase.Status?>(null) }
    var kbEnabled by remember { mutableStateOf(com.example.zhengdao.terminal.KnowledgeBase.isEnabled(ctx)) }
    var kbHelpOpen by remember { mutableStateOf(false) }
    // FIX-F（2026-10-10，E-084）：提问时自动附上资料线索 —— **默认关**，要用户自己打开
    var kbHints by remember { mutableStateOf(com.example.zhengdao.terminal.KnowledgeBaseHints.isEnabled(ctx)) }

    var wsPickerOpen by remember { mutableStateOf(false) }

    fun storageGrantedNow(): Boolean =
        com.example.zhengdao.terminal.ProotLauncher.storageGranted(ctx)   // v1.3 E2：单一判定源

    var storageOk by remember { mutableStateOf(storageGrantedNow()) }
    var permHint by remember { mutableStateOf<String?>(null) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        storageOk = storageGrantedNow()
        permHint = if (storageOk) ctx.getString(R.string.data_perm_granted)
        else ctx.getString(R.string.data_perm_denied)
    }

    var netChecking by remember { mutableStateOf(false) }
    var netMsg by remember { mutableStateOf<String?>(null) }

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
                Toast.makeText(
                    ctx,
                    ctx.getString(R.string.data_open_failed, HumanizeError.title(e2, ctx.resources)),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    var storageTick by remember { mutableStateOf(0) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                manageOk = manageGranted()
                storageOk = storageGrantedNow()
                storageTick++
                // 2026-10-11：回到设置页就顺手刷一次资料库清单。用户常常是"先去文件管理器
                // 把资料丢进 原始/，再切回 App"——那条路上没有任何机会触发会话启动，
                // 于是旧版显示的一直是"还没放资料"（他看到的＝"放了东西没反应"）。
                // autoRefresh 只在后台比指纹+重建清单，不建骨架、不写分发指路。
                com.example.zhengdao.terminal.KnowledgeBase.autoRefresh(ctx)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // 资料库状态：只重读本页用得到的三项（原 kb_effect 还带三处目录大小，那是存储页的活）。
    LaunchedEffect(storageTick) {
        kbEnabled = com.example.zhengdao.terminal.KnowledgeBase.isEnabled(ctx)
        kbHints = com.example.zhengdao.terminal.KnowledgeBaseHints.isEnabled(ctx)
        kbStatus = withContext(Dispatchers.IO) {
            com.example.zhengdao.terminal.KnowledgeBase.status(ctx)
        }
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

    /**
     * 换工作区前的闸（2026-10-11 新增）。
     *
     * 为什么要有：`资料库` 挂在**工作区**下面（[KnowledgeBase.root] = `hostDir/资料库`），
     * 换工作区就等于换了一份资料库 —— 旧那份原样留在原地、不再被读。真机上正是这件事
     * 把用户绕住了：他先把 19 个文件放进 `Download/证道/资料库/原始/`，随后在设置页把
     * 工作区换成了 `Download/男性`，回来只看到「未挂载 · 还没放资料」，以为 App 没反应。
     * ⇒ 当前资料库里有东西时先问一句，并给"顺手复制过去"这条路（原件不动）。
     */
    var wsAsk by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    fun changeWorkspace(toastText: String, doSwitch: () -> Unit) {
        if ((kbStatus?.files ?: 0) > 0) {
            wsAsk = toastText to doSwitch
        } else {
            doSwitch()
            Toast.makeText(ctx, toastText, Toast.LENGTH_SHORT).show()
            storageTick++
        }
    }

    fun toastOnMain(text: String, long: Boolean = false) {
        android.os.Handler(ctx.mainLooper).post {
            Toast.makeText(ctx, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
    }

    SettingsSubPage(title = stringResource(R.string.data_page_title), onBack = onBack) {
        // ── 资料库（2026-10-09 新增）──
        // 定位：用户把手里的资料丢进工作区后，**终端里的各类 AI 自动能读到**。
        // 机械层（预置骨架／扫描／写清单／分发指路）由 App 完成；内容理解交给 AI。
        // ⚠️ 与「太极」无关：OpenCode 插件入口在太极页抽屉里（TaijiScreen 的 HistoryDrawer）；
        //    本项服务的是**终端**里的 agent（Claude Code／Hermes／AGY／用户自装的其他 agent）。
        SectionCard(stringResource(R.string.data_kb_card)) {
            val st = kbStatus
            // E-089（让号自 E-082）：文件数达到扫描上限时**必须提示** —— 否则用户会以为"资料全在这儿了"
            val capNote = { n: Int ->
                if (n >= com.example.zhengdao.terminal.KnowledgeBase.MAX_SCAN)
                    ctx.getString(R.string.data_kb_cap_note)
                else ""
            }
            val stateText = when (st?.state) {
                com.example.zhengdao.terminal.KnowledgeBase.State.DISABLED ->
                    stringResource(R.string.data_kb_disabled)
                com.example.zhengdao.terminal.KnowledgeBase.State.NOT_MOUNTED ->
                    stringResource(R.string.data_kb_not_mounted)
                com.example.zhengdao.terminal.KnowledgeBase.State.BUSY ->
                    stringResource(R.string.data_kb_busy)
                com.example.zhengdao.terminal.KnowledgeBase.State.ORGANIZED ->
                    stringResource(R.string.data_kb_organized, st.files) + capNote(st.files)
                com.example.zhengdao.terminal.KnowledgeBase.State.MOUNTED ->
                    stringResource(R.string.data_kb_mounted, st.files) + capNote(st.files)
                null -> stringResource(R.string.data_kb_reading)
            }
            InfoRow(stringResource(R.string.data_state_label), stateText)
            if (st != null && st.state != com.example.zhengdao.terminal.KnowledgeBase.State.DISABLED) {
                Text(
                    text = st.path,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.data_kb_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.data_kb_privacy),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // ── 2026-10-11（真机现场）──
            // 用户原话：「资料库我已经放了东西到原始文件了，但是APP这边没反应」。
            // 真实原因是**换过工作区**：资料库挂在工作区下面（KnowledgeBase.root），换工作区
            // 就等于换了一份资料库；旧那份原样留在原地、不再被读。以前界面对此只字不提，
            // 他看到的只有"未挂载 · 还没放资料"，自然以为 App 坏了。
            // 现在主动报出"别处还有一份带资料的资料库"，并给一键复制过来（只增不减）。
            var otherKb by remember(storageTick) {
                mutableStateOf<List<Pair<String, Int>>>(emptyList())
            }
            LaunchedEffect(storageTick) {
                otherKb = withContext(Dispatchers.IO) {
                    com.example.zhengdao.terminal.KnowledgeBase.otherPopulated(ctx)
                }
            }
            if (st?.state == com.example.zhengdao.terminal.KnowledgeBase.State.NOT_MOUNTED &&
                otherKb.isNotEmpty()
            ) {
                val otherPath = otherKb.first().first
                val otherCount = otherKb.first().second
                var copyAsk by remember { mutableStateOf(false) }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.data_kb_other_found, otherPath, otherCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                TextButton(enabled = !cleaning, onClick = { copyAsk = true }) {
                    Text(stringResource(R.string.data_kb_copy_btn, otherCount))
                }
                if (copyAsk) {
                    AlertDialog(
                        onDismissRequest = { copyAsk = false },
                        title = { Text(stringResource(R.string.data_kb_copy_title)) },
                        text = {
                            Text(
                                stringResource(
                                    R.string.data_kb_copy_desc,
                                    otherPath,
                                    com.example.zhengdao.terminal.KnowledgeBase.SUB_RAW,
                                )
                            )
                        },
                        confirmButton = {
                            TextButton(enabled = !cleaning, onClick = {
                                copyAsk = false
                                runClean(
                                    work = {
                                        val n = com.example.zhengdao.terminal.KnowledgeBase
                                            .copyRawInto(
                                                ctx,
                                                File(
                                                    otherPath,
                                                    com.example.zhengdao.terminal.KnowledgeBase.SUB_RAW,
                                                ),
                                            )
                                        com.example.zhengdao.terminal.KnowledgeBase
                                            .rebuildAfterCopy(ctx)
                                        n to 0L
                                    },
                                    toast = { n, _ ->
                                        if (n > 0) ctx.getString(R.string.data_kb_copied, n)
                                        else ctx.getString(R.string.data_kb_copy_none)
                                    },
                                )
                            }) { Text(stringResource(R.string.data_copy_action)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { copyAsk = false }) { Text(stringResource(R.string.common_cancel)) }
                        },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row {
                TextButton(onClick = {
                    val p = kbStatus?.path
                        ?: com.example.zhengdao.terminal.KnowledgeBase.root(ctx).absolutePath
                    runCatching {
                        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(
                            android.content.ClipData.newPlainText(
                                ctx.getString(R.string.data_kb_path_label),
                                p,
                            )
                        )
                    }
                    toastOnMain(ctx.getString(R.string.data_path_copied, p))
                }) { Text(stringResource(R.string.data_copy_path)) }
                TextButton(onClick = { kbHelpOpen = true }) { Text(stringResource(R.string.data_view_help)) }
                TextButton(onClick = {
                    com.example.zhengdao.terminal.KnowledgeBase.requestRebuild(ctx)
                    // ── FIX-A（2026-10-10，E-080）：此处原先顺带调
                    //    KnowledgeBaseSummarizerRunner.requestSummaries() —— 由太极的云端免费模型
                    //    把每个文件开头（≤1200 字，.docx 先抽正文）读走、写进清单的「文件摘要」节。
                    //    ⚠️ 那一步会把文件内容发到设备之外，与上方「证道本身不会把它发出去」直接矛盾；
                    //    且用户 2026-10-10 已拍板：不再让太极承担该职责（它一更新就变、非自有组件）。
                    //    ⇒ 摘除该调用。「重新整理」从此只做纯本地的「重建清单」这一件事。
                    //    Runner 源码保留不删（将来若做"本地摘要"可在此重新接线）；
                    //    摘要改由终端里带工具的 agent 自行生成（见 docs/知识库-收工条件.md）。
                    toastOnMain(ctx.getString(R.string.data_organizing))
                    storageTick++
                }) { Text(stringResource(R.string.data_rebuild)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.data_kb_toggle),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = kbEnabled,
                    onCheckedChange = { on ->
                        kbEnabled = on
                        com.example.zhengdao.terminal.KnowledgeBase.setEnabled(ctx, on)
                        // 重新打开开关同样是显式要求：若资料库被删过，顺手把它建回来（E-061）。
                        if (on) com.example.zhengdao.terminal.KnowledgeBase.requestRebuild(ctx)
                        storageTick++
                    },
                    colors = iosSwitchColors(),
                )
            }
            // ── FIX-F（2026-10-10，E-084）：提问时自动附上资料线索 ──
            // 默认关：这是往用户屏幕上贴字的功能，先让他自己决定开不开。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.data_hints_toggle),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = kbHints,
                    onCheckedChange = { on ->
                        kbHints = on
                        com.example.zhengdao.terminal.KnowledgeBaseHints.setEnabled(ctx, on)
                    },
                    // 2026-10-11 补（验收页 §4-1）：同一张卡里的两个开关必须同色，
                    // 上一处（「让终端 AI 自动读取」）已用 iOS 绿，这处漏了。
                    colors = iosSwitchColors(),
                )
            }
            ExpandableHint(stringResource(R.string.data_hints_desc))
        }

        if (kbHelpOpen) {
            KnowledgeBaseHelpDialog(ctx) { kbHelpOpen = false }
        }
        // ── 终端外观（行距可选；字号 / 画布留白 / 配色是定稿默认，只展示）──
        // 用户 2026-10-11 定：这一卡只留「行距」能选（舒适 / 紧凑），
        // 字号 / 画布留白 / 配色按 2026-10-10 定稿的那一套（Catppuccin Mocha · 12 ·
        // 上下 8dp）当默认展示，不再给选项——选项太多反而挑不出哪个是"对的"。
        // 字号仍可在终端里双指缩放临时改（同一份 TerminalPrefs，见 TerminalActivity）。
        SectionCard(stringResource(R.string.data_appearance_card)) {
            val sizeDp = TerminalPrefs.sizeDp(ctx)
            val insetDp = TerminalPrefs.insetDp(ctx)
            val scheme = TerminalPrefs.scheme(ctx)
            var spacingId by remember { mutableStateOf(TerminalPrefs.spacing(ctx).id) }

            Text(
                text = stringResource(R.string.data_appearance_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            InfoRow(stringResource(R.string.data_font_size), stringResource(R.string.data_font_size_value, sizeDp))
            InfoRow(
                stringResource(R.string.data_inset),
                if (insetDp == 0) stringResource(R.string.data_inset_none)
                else stringResource(R.string.data_inset_value, insetDp),
            )
            InfoRow(stringResource(R.string.data_color_scheme), scheme.label)

            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.data_spacing),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TerminalPrefs.Spacing.values().forEach { sp ->
                    FilterChip2(sp.label, sp.id == spacingId) {
                        spacingId = sp.id
                        TerminalPrefs.saveSpacing(ctx, sp)
                    }
                }
            }
            Text(
                text = stringResource(R.string.data_spacing_desc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        // ── 权限（存储读写 + 网络自检）──
        // 存储：/sdcard 共享存储读写全靠 READ/WRITE 运行时授权（E-005 修正后的事实）
        // 网络：INTERNET 为安装时权限系统自动授，无可引导项；用户真正会卡的是
        //      代理 App 分应用没勾选证道——给一键自检代替空喊"网络权限"
        SectionCard(stringResource(R.string.data_perm_ws_card)) {
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
                    Toast.makeText(
                        ctx,
                        ctx.getString(R.string.data_open_failed, HumanizeError.title(it, ctx.resources)),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                Unit
            }
            // 未授权时整行可点＝直接拉起系统授权弹窗；已授权时点击＝去系统设置查看/撤销。
            SettingRow(
                label = stringResource(R.string.data_perm_storage),
                value = if (storageOk) stringResource(R.string.data_perm_granted)
                else stringResource(R.string.data_perm_not_granted),
                // P2-11：未授予是状态文字，走 zhengdaoErrorText()（error 色 3.55:1 不达 AA）
                valueColor = if (storageOk) zhengdaoReadyGreen() else zhengdaoErrorText(),
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
                text = stringResource(R.string.data_perm_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!storageOk) {
                Spacer(Modifier.height(6.dp))
                permHint?.let {
                    // P2-11：提示正文 zhengdaoErrorText()
                    Text(it, style = MaterialTheme.typography.bodySmall, color = zhengdaoErrorText())
                    Spacer(Modifier.height(6.dp))
                }
            }

            Spacer(Modifier.height(10.dp))
            SettingsDivider()
            Spacer(Modifier.height(10.dp))

            SettingRow(
                label = stringResource(R.string.data_net_row),
                value = stringResource(R.string.data_net_auto),
                valueColor = zhengdaoReadyGreen(),
                onClick = openAppDetails,
            )
            Text(
                text = stringResource(R.string.data_net_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            // 自检也收进同一套行式组件（状态在右、结论在行下方），不再是一个孤立的文字按钮
            SettingRow(
                label = stringResource(R.string.data_net_check),
                value = if (netChecking) stringResource(R.string.data_net_checking)
                else stringResource(R.string.data_net_check_btn),
                valueColor = if (netChecking) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.primary,
                onClick = {
                    if (!netChecking) {
                    netChecking = true
                    netMsg = ctx.getString(R.string.data_net_checking)
                    // 两个探针：国内基线 + 更新源（GitHub 家族，含 gh-proxy 镜像回退）。
                    // 只探前者会在「国内通、GitHub 家族全灭」的设备上给出骗人的绿灯——
                    // 用户拿着"✅ 网络可用"却下不动环境包。见 NetSelfCheck 的类注释与 ERRATA E-034。
                    Thread {
                        val cn = NetSelfCheck.probeCn()
                        val update = NetSelfCheck.probeIndex(res = ctx.resources)
                        netMsg = NetSelfCheck.summary(cn, update, ctx.resources)
                        netChecking = false
                    }.apply { isDaemon = true; name = "zd-net-check" }.start()
                    }
                },
            )
            netMsg?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(10.dp))
            SettingsDivider()
            Spacer(Modifier.height(10.dp))

            // ── 所有文件访问（MANAGE_EXTERNAL_STORAGE，可选增强；用户定稿简化版）──
            SettingRow(
                label = stringResource(R.string.data_manage_row),
                value = if (manageOk) stringResource(R.string.data_manage_on)
                else stringResource(R.string.data_manage_off),
                valueColor = if (manageOk) zhengdaoReadyGreen() else MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = { launchManage() },
            )
            Text(
                text = stringResource(R.string.data_manage_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(10.dp))
            SettingsDivider()
            Spacer(Modifier.height(10.dp))

            // ── 工作区（0.6 显性化：产出边界让用户看得见）──
            // 2026-10-12 并入「权限」卡（走查报告 §1.3：5 卡 → 3 卡）：权限与工作区
            // 本就是同一件事的两面——权限决定「能不能读写」，工作区决定「读写落在哪」。
            val wsHost = com.example.zhengdao.terminal.Workspace.hostDir(ctx)
            val wsShared = com.example.zhengdao.terminal.Workspace.isShared(ctx)
            Text(
                stringResource(R.string.data_ws_output),
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
                    stringResource(R.string.data_ws_shared_desc)
                } else {
                    stringResource(R.string.data_ws_private_desc)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row {
                TextButton(enabled = !cleaning, onClick = { wsPickerOpen = true }) {
                    Text(stringResource(R.string.data_ws_pick))
                }
                TextButton(onClick = {
                    // 2026-10-11：先过 changeWorkspace 的闸（当前资料库有东西时会先问一句）
                    changeWorkspace(ctx.getString(R.string.data_ws_restored)) {
                        com.example.zhengdao.terminal.Workspace.setDefault(ctx)
                    }
                }) { Text(stringResource(R.string.data_ws_restore)) }
                TextButton(onClick = {
                    changeWorkspace(ctx.getString(R.string.data_ws_private_done)) {
                        com.example.zhengdao.terminal.Workspace.setPrivate(ctx)
                    }
                }) { Text(stringResource(R.string.data_ws_private)) }
            }
            Text(
                text = stringResource(R.string.data_ws_rule),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (wsPickerOpen) {
            WorkspaceFolderPicker(
                onDismiss = { wsPickerOpen = false },
                onPick = { path ->
                    wsPickerOpen = false
                    if (!path.startsWith("/storage/emulated/0")) {
                        Toast.makeText(
                            ctx,
                            ctx.getString(R.string.data_ws_invalid),
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        // 同样过 changeWorkspace 的闸：换工作区 = 换一份资料库
                        changeWorkspace(ctx.getString(R.string.data_ws_set, path)) {
                            com.example.zhengdao.terminal.Workspace.setCustom(ctx, path)
                        }
                    }
                },
            )
        }

        // 换工作区的确认框（只有"当前资料库里确实有文件"时才会被 changeWorkspace 置上）
        wsAsk?.let { ask ->
            val askToast = ask.first
            val askSwitch = ask.second
            val curFiles = kbStatus?.files ?: 0
            val curKb = com.example.zhengdao.terminal.KnowledgeBase.root(ctx).absolutePath
            AlertDialog(
                onDismissRequest = { wsAsk = null },
                title = { Text(stringResource(R.string.data_ws_change_title)) },
                text = {
                    Column {
                        Text(stringResource(R.string.data_ws_change_current, curKb, curFiles))
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.data_ws_change_desc))
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.data_ws_change_ask))
                    }
                },
                confirmButton = {
                    TextButton(enabled = !cleaning, onClick = {
                        wsAsk = null
                        askSwitch()
                        Toast.makeText(ctx, askToast, Toast.LENGTH_SHORT).show()
                        runClean(
                            work = {
                                val c = com.example.zhengdao.terminal.KnowledgeBase.copyRawInto(
                                    ctx,
                                    File(
                                        curKb,
                                        com.example.zhengdao.terminal.KnowledgeBase.SUB_RAW,
                                    ),
                                )
                                com.example.zhengdao.terminal.KnowledgeBase.rebuildAfterCopy(ctx)
                                c to 0L
                            },
                            toast = { c, _ ->
                                if (c > 0) ctx.getString(R.string.data_ws_change_copied, c)
                                else ctx.getString(R.string.data_ws_change_none)
                            },
                        )
                    }) { Text(stringResource(R.string.data_ws_change_copy_btn)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        wsAsk = null
                        askSwitch()
                        Toast.makeText(ctx, askToast, Toast.LENGTH_SHORT).show()
                        storageTick++
                    }) { Text(stringResource(R.string.data_ws_change_move_btn)) }
                },
            )
        }
        // ── 输入回归页入口已下沉（2026-10-12 走查报告 §1.2-D）──
        // 这是开发自测页（Issue #2 / 设计方案 v3 §3），普通用户不需要；
        // 现在收进「关于」页的隐藏入口：版本行连点 3 次进入。
    }
}

// (KnowledgeBaseHelpDialog / WorkspaceFolderPicker 已移入 SettingsCommon.kt，见 tools/_settings-apply.ps1)

