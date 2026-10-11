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

/** 二级页：环境与更新（逐字搬自「修复环境 / Hermes 依赖环境 / 环境更新 / 运行内存上限」四张卡）。 */
class SettingsEnvActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            ZhengdaoTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsEnvPage(onBack = { finish() })
                }
            }
        }
    }
}

@Composable
private fun SettingsEnvPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var storageTick by remember { mutableStateOf(0) }
    var logDialogText by remember { mutableStateOf<String?>(null) }

    /** 卡片里原本直调设置页的 `onOpenTerminal`，这里接到同一条意图上（Hermes 卡的「在终端里修复」）。 */
    val onOpenTerminal: (String?, String?) -> Unit = { autocmd, agentId ->
        openTerminal(ctx, autocmd, agentId)
    }

    var repairConfirm by remember { mutableStateOf(false) }
    var updateMsg by remember { mutableStateOf<String?>(null) }
    var pendingUpdateUrl by remember { mutableStateOf<String?>(null) }
    var pendingUpdateSha by remember { mutableStateOf<String?>(null) }

    /**
     * 缓存里"字节数正好等于索引那个包"的本地文件。非 null ⇒ 弹窗确认后**先**逐字节验它：
     * sha 与索引一致就直接本地重解压，不再下 192 MB（2026-10-08 真机：一次「补指纹」白下了 192 MB）。
     * 注意这里只按**大小**定位候选，sha 由安装线程在动手前验（[RootfsCache.localCandidateFor]）。
     */
    var pendingLocalArchive by remember { mutableStateOf<File?>(null) }

    /**
     * 检查更新时拿到的**整条索引**（而不是只留 env）：全量装完后要按"信任锚"规则决定写不写
     * `env=` 行 —— 只有索引 sha256 与实际校验通过的 sha256 一致才写
     * （见 [RootfsInstaller.envForMarker]，理由：索引可能因 CI 半途失败而陈旧）。
     * null = 走的不是索引路径（老 manifest 降级）或索引取不到 ⇒ 装完不写 env 行。
     */
    var pendingIndex by remember { mutableStateOf<RootfsIndex?>(null) }

    /** 索引给的增量补丁（基线与本机相符时才会被赋上）；非 null = 弹窗确认后先试增量。 */
    var pendingPatch by remember { mutableStateOf<PatchRef?>(null) }

    /** 上一轮（有错误时）的日志原文；只喂「运行日志」卡片的提示与按钮。 */
    var prevLogText by remember { mutableStateOf<String?>(null) }
    var archiveCount by remember { mutableStateOf(0) }

    fun openLogFromSnackbar() {
        openLogDialog(scope, ctx) { logDialogText = it }
    }

    fun toastOnMain(text: String, long: Boolean = false) {
        android.os.Handler(ctx.mainLooper).post {
            Toast.makeText(ctx, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
    }

    var checking by remember { mutableStateOf(false) }

    SettingsSubPage(title = "环境与更新", onBack = onBack, snackbar = snackbarHostState) {
        // ── 修复环境 ──
        SectionCard("修复环境") {
            Text(
                text = "重新解压系统层，保留登录态与工作区。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = { repairConfirm = true },
                enabled = !InstallFlow.isRunning(), // #11 / E-078：有安装/更新在跑时不许再排队
            ) { Text("修复环境（30 秒）") }
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
                // #11 / E-078：busy 的不只是本页这条路——终端页正在装、增量更新正在跑，这里也不该能点。
                // InstallFlow.isRunning() 把 InstallProgress（本页四条流程）与 RootfsInstaller 的
                // 进程级真锁合起来看，两者任一在跑都算忙。
                enabled = !checking && !InstallFlow.isRunning(),
                onClick = {
                    checking = true
                    Thread {
                        // 待下载目标（发现新环境时由检查逻辑填入，弹窗确认后用）
                        var pendingUrl: String? = null
                        var pendingSha: String? = null
                        var pendingIdx: RootfsIndex? = null
                        var pendingPatchRef: PatchRef? = null
                        // 缓存里可能就是索引那个包（只按大小定位，sha 在安装前才验）。
                        // 2026-10-08 用户拍板：本地已有就别再下 192 MB。
                        var pendingLocal: File? = null
                        val result = try {
                            // ① 增量协议：`rootfs-index.json` 是唯一事实来源，版本按 **env 内容指纹**比对。
                            //    老逻辑拿恒定的发行版号（13.7）比，于是永远判"已是最新"——本次修掉的正是它。
                            val idx = RootfsIndexFetcher.fetch()
                            if (idx != null && !idx.env.isNullOrBlank()) {
                                val localEnv = RootfsMarker.installedEnv(ctx)
                                val ver = idx.version.ifBlank { idx.distro.ifBlank { "未知版本" } }
                                when {
                                    // 旧安装（标记里没有 env 行）：第一次更新只能全量，装完就记上 env。
                                    // 2026-10-08：本地装的往往**就是同一个版本**（13.7），只是标记缺 env 行，
                                    // 却被叫成"发现新版本 13.7"——用户会以为真出了新版（真机投诉）。
                                    // 这里按本地已装版本分两种口径：同版本＝补指纹，不同/未知＝确实有新版。
                                    localEnv == null -> {
                                        pendingUrl = idx.url; pendingSha = idx.sha256
                                        pendingIdx = idx
                                        val localVer = com.example.zhengdao.rootfs.RootfsCache.currentVersion(ctx).orEmpty()
                                        // 2026-10-08：缓存里若已躺着索引那个包（下过一次没装成、或上次重装留下的），
                                        // 就别再让用户下 192 MB——真机上这一次白下了整包。只按大小定位，
                                        // sha 在确认后、动手前由安装线程验（对不上照旧下载）。
                                        val cached = com.example.zhengdao.rootfs.RootfsCache
                                            .localCandidateFor(ctx, idx.url, idx.size)
                                        pendingLocal = cached
                                        val sameVer = localVer.isNotBlank() && localVer.equals(ver, ignoreCase = true)
                                        when {
                                            cached != null && sameVer ->
                                                "本地已安装 $ver，但缺少环境指纹记录（env）：" +
                                                    "本地已有该版本的安装包（${bytesMbText(cached.length())}），" +
                                                    "将直接用它重装补指纹，无需下载。"
                                            cached != null ->
                                                "本地已有该版本的安装包（${bytesMbText(cached.length())}），" +
                                                    "将直接用它安装，无需下载。"
                                            sameVer ->
                                                "本地已安装 $ver，但缺少环境指纹记录（env）：" +
                                                    "本次将用全量包（${bytesMbText(idx.size)}）重装一次以补上指纹，" +
                                                    "重装后「检查环境更新」才能正确判断新旧。"
                                            else ->
                                                "发现新版本 $ver（本地环境未记录版本，本次需全量下载，${bytesMbText(idx.size)}）"
                                        }
                                    }
                                    localEnv.equals(idx.env, ignoreCase = true) ->
                                        "已是最新版本（$ver，环境 $localEnv）"
                                    else -> {
                                        pendingUrl = idx.url; pendingSha = idx.sha256
                                        pendingIdx = idx
                                        val p = idx.patch
                                        // 本地已有索引那个整包时**优先本地**：0 下载优于几十 MB 的补丁。
                                        val cached = com.example.zhengdao.rootfs.RootfsCache
                                            .localCandidateFor(ctx, idx.url, idx.size)
                                        pendingLocal = cached
                                        when {
                                            cached != null ->
                                                "发现新版本 $ver（本地已有该版本的安装包（${bytesMbText(cached.length())}），" +
                                                    "将直接用它安装，无需下载）"
                                            p != null && p.from.equals(localEnv, ignoreCase = true) -> {
                                                pendingPatchRef = p
                                                "发现新版本 $ver，可增量更新（${bytesMbText(p.size)}，无需重下全量包）"
                                            }
                                            else ->
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
                            pendingLocalArchive = pendingLocal
                            // 有可下载目标 → 交给下面的弹窗（用户点「下载并安装」）；
                            // 没有目标（已是最新 / 只能手动去下载） → 用 Toast 说清楚。
                            // 判据是 pendingUrl 而不是文案前缀：同版本补指纹的文案不以"发现新版本"开头，
                            // 按前缀判断会让它既不弹窗也不提示（2026-10-08 修）。
                            if (pendingUrl == null) {
                                Toast.makeText(ctx, result, Toast.LENGTH_LONG).show()
                            }
                        }
                    }.start()
                },
            ) { Text(if (checking) "检查中…" else "检查环境更新") }
            installState?.let { st ->
                Spacer(Modifier.height(6.dp))
                // 2026-10-08 修：此前无论 phase 是什么都硬写"⏳ 正在安装环境…"，且 Done 之后
                // 就一直挂着（真机现象："⏳ 正在安装环境……安装完成！"，还带着"不能重复检查更新"
                // 这句对已结束安装毫无意义的话，被用户读成"还在装"）。现在按 phase 给图标与颜色，
                // "正在安装"只属于 Running；Done/Failed 收尾成一次性状态，可点「知道了」清掉。
                val (icon, color) = when (st.phase) {
                    InstallProgress.Phase.Running -> "⏳" to MaterialTheme.colorScheme.primary
                    InstallProgress.Phase.Done -> "✅" to MaterialTheme.colorScheme.primary
                    InstallProgress.Phase.Failed -> "⚠️" to MaterialTheme.colorScheme.error
                }
                Text(
                    text = when (st.phase) {
                        InstallProgress.Phase.Running ->
                            "$icon 正在安装环境（${if (st.fromLocal) "使用本地缓存包，不联网下载" else "联网下载"}）：" +
                                "${st.text}\n安装完成前不需要、也不能重复检查更新；进度同时显示在终端页顶部横幅与系统通知里。"
                        InstallProgress.Phase.Done -> "$icon 上一次安装：${st.text}"
                        InstallProgress.Phase.Failed -> "$icon 上一次安装失败：${st.text}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = color,
                )
                if (st.phase != InstallProgress.Phase.Running) {
                    // 结束后给一个明确的收尾动作：否则这条状态会一直占着按钮下面的位置
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // #4（2026-10-09）：失败时此前只有「知道了」——把它清掉就再没有任何重试入口，
                        // 用户只能自己想起"修复环境"那张卡。这里直接给重试，走的就是同一条修复流程
                        // （本地安装包重解压，不联网；见下方 repairConfirm 的二次确认）。
                        if (st.phase == InstallProgress.Phase.Failed) {
                            TextButton(onClick = { repairConfirm = true }) { Text("重试安装") }
                        }
                        TextButton(onClick = { InstallProgress.clear() }) { Text("知道了") }
                    }
                }
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
    }

    if (repairConfirm) {
        AlertDialog(
            onDismissRequest = { repairConfirm = false },
            title = { Text("修复环境") },
            text = { Text("将重新解压 Debian 系统层（约 30 秒 + Agent 重装时间）。登录态与工作区保留。需要本地已有安装包（Download/证道/rootfs 缓存，或 Download/证道 根目录里的安装包）。确定？") },
            confirmButton = {
                TextButton(
                    enabled = !InstallFlow.isRunning(), // #11 / E-078：与其它长流程共享 busy
                    onClick = {
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
                                // 修复＝拿本地这个包再解压一次。若它与「当初装出当前 env 的那个包」
                                // 逐字节相同（标记里的 archive-sha256 对得上），内容指纹照样成立 ⇒ 原样写回，
                                // 否则一次修复就把增量基线抹掉、下次更新被迫全量（2026-10-08 真机 bug，
                                // 详见 RootfsInstaller.envForReinstall 的 KDoc）。对不上就传 null。
                                val repairSha = com.example.zhengdao.rootfs.RootfsDownloader.sha256Of(archive)
                                val repairEnv = com.example.zhengdao.rootfs.RootfsInstaller.envForReinstall(
                                    File(ctx.filesDir, "rootfs"),
                                    repairSha,
                                )
                                if (repairEnv != null) {
                                    com.example.zhengdao.rootfs.RunLog.log(
                                        "修复环境：本地包与当前环境同源（sha=${repairSha.take(12)}），保留环境指纹 $repairEnv",
                                    )
                                } else {
                                    com.example.zhengdao.rootfs.RunLog.log(
                                        "修复环境：本地包与当前环境无法确认同源（sha=${repairSha.take(12)}），不写环境指纹（下次更新走全量）",
                                    )
                                }
                                com.example.zhengdao.rootfs.RootfsInstaller.install(ctx, archive, repairEnv, repairSha) { }
                                InstallFlow.finish(
                                    ctx,
                                    "修复完成：环境已重置，登录态与工作区保留（本次未联网下载）",
                                )
                                android.os.Handler(ctx.mainLooper).post { storageTick++ }
                            } catch (t: Throwable) {
                                InstallFlow.fail(ctx, "修复失败：${HumanizeError.title(t)}")
                                // 2026-10-08：Toast → Snackbar（带"查看日志"action），同"回退失败"分支
                                scope.launch {
                                    val r = snackbarHostState.showSnackbar(
                                        message = "修复失败：${HumanizeError.title(t)}",
                                        actionLabel = "查看日志",
                                        duration = SnackbarDuration.Indefinite,
                                    )
                                    if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                                        // 同上：现场取日志再弹，缺日志时会有指向目录的 Toast
                                        openLogFromSnackbar()
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

    // ── 环境更新弹窗（pendingUpdateUrl 驱动）：确认后在应用内下载到公共缓存并安装 ──
    // 触发条件用"是否真有可下载目标"，不再靠文案前缀猜：同版本补指纹的场景文案不以
    // "发现新版本"开头，按前缀判断会让它既不弹窗也不提示（2026-10-08 修）。
    updateMsg?.takeIf { pendingUpdateUrl != null }?.let { msg ->
        AlertDialog(
            onDismissRequest = { updateMsg = null },
            title = { Text(if (msg.startsWith("发现新版本")) "发现环境更新" else "可以重装环境") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(
                    enabled = !InstallFlow.isRunning(), // #11 / E-078：与其它长流程共享 busy
                    onClick = {
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
                    val localCandidate = pendingLocalArchive
                    pendingLocalArchive = null
                    Thread {
                        // 增量失败会写这里的原因，随最终结论一起告诉用户（"回退全量"必须可见）
                        var fallbackNote = ""
                        try {
                            // ── ⓪ 本地已有与索引逐字节相同的整包 → 直接本地重解压，不下载 ──
                            // 2026-10-08 用户拍板：一次「补指纹」不该再下 192 MB（真机上白下过一次）。
                            // 只有 sha 与索引一致才敢把索引的 env 写进标记——信任锚规则与全量路径同一条
                            // （见 RootfsInstaller.envForMarker）；对不上就照旧走下载。
                            if (localCandidate != null && localCandidate.isFile) {
                                val localSha = RootfsDownloader.sha256Of(localCandidate)
                                if (expectedSha.isNullOrBlank() || localSha.equals(expectedSha, ignoreCase = true)) {
                                    InstallFlow.start(
                                        ctx,
                                        "检查环境更新：本地已有同版本安装包" +
                                            "（${localCandidate.length() / (1024 * 1024)} MB），直接重解压，不下载",
                                        fromLocal = true,
                                    )
                                    InstallFlow.update(ctx, "正在准备安装包：${localCandidate.name}")
                                    val archive = File(ctx.cacheDir, localCandidate.name)
                                    if (archive.absolutePath != localCandidate.absolutePath) {
                                        localCandidate.copyTo(archive, true)
                                    }
                                    val envToWrite = RootfsInstaller.envForMarker(index?.env, index?.sha256, localSha)
                                    if (index != null && envToWrite == null) {
                                        com.example.zhengdao.rootfs.RunLog.log(
                                            "本地包 sha256 与索引不一致（或索引字段缺失），本次安装不写 env 标记"
                                        )
                                    }
                                    com.example.zhengdao.rootfs.RunLog.log(
                                        "环境更新：用本地安装包（sha=${localSha.take(12)}）重解压，本次未联网下载"
                                    )
                                    InstallFlow.update(ctx, "正在解压系统层（没有细粒度进度，请勿离开本页）…")
                                    RootfsInstaller.ensureFreeSpace(ctx, archive.length())
                                    RootfsInstaller.install(ctx, archive, envToWrite, localSha) { }
                                    RootfsCache.pruneKeep(ctx)
                                    InstallFlow.finish(ctx, "环境更新完成（用本地安装包，本次未联网下载），重进终端生效")
                                    android.os.Handler(ctx.mainLooper).post { storageTick++ }
                                    return@Thread
                                }
                                com.example.zhengdao.rootfs.RunLog.log(
                                    "本地缓存包 sha（${localSha.take(12)}）与索引不一致（${expectedSha?.take(12)}），改为下载"
                                )
                            }
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
                                    // 加固-4（E-085）：增量的最坏情况是"硬链接失败 ⇒ 整树复制"，
                                    // 那一刻旧树与新树同时在盘上 —— 把实测树大小交给预检，
                                    // 而不是只按补丁大小估（树没超过常量时口径与从前一致）。
                                    RootfsInstaller.ensureFreeSpaceForDelta(
                                        ctx,
                                        deltaFile.length(),
                                        dirSizeMb(File(ctx.filesDir, "rootfs")) * 1_048_576,
                                    )
                                    val info = RootfsDelta.readPatchInfo(deltaFile)
                                        ?: throw RootfsInstaller.InstallFailed("补丁元数据缺失或不可读")
                                    // 索引给的补丁 sha256 双重把关：下载校验一次（协议 §4），
                                    // 再把同一期望值交给解压流水线（Rust 对"恰好被解压的字节"算一次）
                                    RootfsDelta.apply(ctx, deltaFile, info, expectedSha256 = patchRef.sha256)
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
                                // archiveSha256 一并写进标记：它是下次「修复环境/回退」判断
                                // "本地这个包是否就是装出当前 env 的那个包"的唯一依据（见
                                // RootfsInstaller.envForReinstall）。全量更新路径有确定值，一定传。
                                RootfsInstaller.install(ctx, archive, envToWrite, actualSha) { }
                                RootfsCache.pruneKeep(ctx)
                            }
                            InstallFlow.finish(ctx, fallbackNote + "环境更新完成，重进终端生效")
                            android.os.Handler(ctx.mainLooper).post { storageTick++ }
                        } catch (t: Throwable) {
                            InstallFlow.fail(ctx, "更新失败：${t.message}")
                            toastOnMain("更新失败：${t.message}", long = true)
                        }
                    }.start()
                }) {
                    // 本地已有索引那个包时不再说"下载"：按钮文案与实际动作一致（2026-10-08）
                    Text(if (pendingLocalArchive != null) "用本地包安装" else "下载并安装")
                }
            },
            dismissButton = { TextButton(onClick = { updateMsg = null }) { Text("取消") } },
        )
    }

    SettingsLogDialog(log = logDialogText, onDismiss = { logDialogText = null })
}

