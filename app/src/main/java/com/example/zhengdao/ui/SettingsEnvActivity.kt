// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import com.example.zhengdao.ui.theme.zhengdaoSwitchColors
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
import com.example.zhengdao.ui.settings.EnvRules
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
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import com.example.zhengdao.BackChevron
import com.example.zhengdao.keepalive.KeepaliveArchive
import com.example.zhengdao.rootfs.RunLog
import com.example.zhengdao.terminal.Workspace
import com.example.zhengdao.ui.theme.ZhengdaoTheme
import com.example.zhengdao.keepalive.CoroutineCrashHandler
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

/**
 * `FlowRow`（运行内存上限那张卡的三个芯片要能折行）在 `foundation` 里仍是 `@ExperimentalLayoutApi`，
 * 必须显式 OptIn —— 同 `ui/baopu/BaopuSheets`（2026-10-16 已删）对 `ModalBottomSheet` 的处理。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsEnvPage(onBack: () -> Unit) {
    val ctx = LocalContext.current; val res = androidx.compose.ui.platform.LocalResources.current
    val scope = rememberCoroutineScope { CoroutineCrashHandler }
    // ENV-D1：Snackbar 正文在组合期按资源取（stringResource 跟随配置/语言变化），
    // 协程里只捕获结果 —— 不拿 LocalContext 读资源（Lint LocalContextGetResourceValueCall）。
    val blockMessage = stringResource(LIVE_SESSION_BLOCK_MESSAGE_RES)
    val gotIt = stringResource(R.string.common_got_it)
    val viewLog = stringResource(R.string.common_view_log)
    val repairNoLocalMsg = stringResource(R.string.env_repair_no_local)
    val updateFailedTemplate = stringResource(R.string.env_update_failed)
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
     * 检查更新时拿到的**整条索引**（而不是只留 env）：全量装完后要按"信任锚"规则决定写不写
     * `env=` 行 —— 只有索引 sha256 与实际校验通过的 sha256 一致才写
     * （见 [RootfsInstaller.envForMarker]，理由：索引可能因 CI 半途失败而陈旧）。
     * null = 走的不是索引路径（老 manifest 降级）或索引取不到 ⇒ 装完不写 env 行。
     */
    var pendingIndex by remember { mutableStateOf<RootfsIndex?>(null) }

    /** 索引给的增量补丁（基线与本机相符时才会被赋上）；非 null = 弹窗确认后先试增量。 */
    var pendingPatch by remember { mutableStateOf<PatchRef?>(null) }

    // 第⑩刀审计 R-20（2026-10-14）：原先这里还声明了 prevLogText / archiveCount —— 那是
    // P4-1 拆页前的遗留，两张日志卡已搬到 ui/SettingsHelpActivity.kt，本文件只赋值不读取
    // （连加载那段 LaunchedEffect 一起是孤儿）。两个死 state 已删，加载逻辑搬回帮助页。

    fun openLogFromSnackbar() {
        openLogDialog(scope, ctx) { logDialogText = it }
    }

    var checking by remember { mutableStateOf(false) }

    SettingsSubPage(title = stringResource(R.string.settings_env_update), onBack = onBack, snackbar = snackbarHostState) {
        // ── 修复环境 ──
        SectionCard(stringResource(R.string.env_repair_card)) {
            Text(
                text = stringResource(R.string.env_repair_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = { repairConfirm = true },
                enabled = !InstallFlow.isRunning(), // #11 / E-078：有安装/更新在跑时不许再排队
            ) { Text(stringResource(R.string.env_repair_btn)) }
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
                com.example.zhengdao.terminal.HermesEnv.inspect(ctx, res)
            }
        }
        // 第十八批 B 段（§2.2-2/4）：检测通过且没有要清理的东西 ⇒ 整卡不出现
        // （不显示「一切正常」，不留空壳）；检测中 / 未通过 / 有中断标记要清理才显示。
        val hermesSt = hermesEnv
        if (hermesSt == null || !hermesSt.ok || hermesSt.canRepairOnHost) {
        SectionCard(stringResource(R.string.env_hermes_card)) {
            val st = hermesSt
            Text(
                text = st?.detail ?: stringResource(R.string.env_hermes_checking),
                style = MaterialTheme.typography.bodySmall,
                // P2-11：体检不通过的详情是正文，走 zhengdaoErrorText()（error 色 3.55:1 不达 AA）
                color = if (st != null && !st.ok) zhengdaoErrorText()
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 2026-10-11（用户 m01758）：环境本身好、只剩更新中断的标记文件时不再打红叉，
                // 但清理入口要留着 ⇒ 按 canRepairOnHost 显示，按钮文案随情形变。
                if (st != null && st.canRepairOnHost) {
                    OutlinedButton(onClick = {
                        Thread {
                            val home = com.example.zhengdao.terminal.HermesEnv.hermesHome(ctx)
                            com.example.zhengdao.terminal.HermesEnv.repairOnHost(home)
                            android.os.Handler(android.os.Looper.getMainLooper())
                                .post { storageTick++ }
                        }.apply { isDaemon = true; name = "zd-hermes-repair" }.start()
                    }) {
                        Text(
                            if (!st.ok) stringResource(R.string.env_hermes_fix_record)
                            else stringResource(R.string.env_hermes_clean_marker)
                        )
                    }
                }
                TextButton(onClick = {
                    if (com.example.zhengdao.terminal.HermesEnv.writeRepairScript(ctx)) {
                        onOpenTerminal(com.example.zhengdao.terminal.HermesEnv.REPAIR_CMD, null)
                    } else {
                        Toast.makeText(
                            ctx,
                            res.getString(R.string.env_hermes_script_failed),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }) { Text(stringResource(R.string.env_hermes_repair_terminal)) }
            }
            // 说明只在出问题时给（2026-10-12 走查 §5.6 文案瘦身）：正常时不解释，
            // 出问题时一句人话 + 一条去路，不摊原理。
            if (st != null && !st.ok) {
                Text(
                    text = stringResource(R.string.env_hermes_broken),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            }
        }
        // ── 检查环境更新（第三批：manifest 对比 + 应用内下载安装，不自动检查）──
        SectionCard(stringResource(R.string.env_update_card)) {
            Text(
                text = stringResource(R.string.env_update_desc),
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
                        val result = try {
                            // ① 增量协议：`rootfs-index.json` 是唯一事实来源，版本按 **env 内容指纹**比对。
                            //    老逻辑拿恒定的发行版号（13.7）比，于是永远判"已是最新"——本次修掉的正是它。
                            val idx = RootfsIndexFetcher.fetch()
                            if (idx != null && !idx.env.isNullOrBlank()) {
                                // 第八十批（2026-10-20）：这里原先是一坨 75 行的 when 树 —— 8 种口径
                                // 各自决定"给哪句话 / 有没有下载目标 / 要不要先试增量"，从没有单测钉住
                                // （2026-10-08「同版本被叫成发现新版本」的投诉就是这段判据失守）。
                                // 判据整体搬到 EnvRules.planUpdate（纯值进出），本处只做三件事：
                                // 取值、按结论落 pending*、按结论选一句文案。分支与旧实现逐条对应。
                                val localEnv = RootfsMarker.installedEnv(ctx)
                                val ver = EnvRules.displayVersion(
                                    idx.version,
                                    idx.distro,
                                    res.getString(R.string.env_version_unknown),
                                )
                                // 有可下载目标 ⇒ 交给下面那个确认弹窗；没有 ⇒ 只 Toast 一句。
                                // 判据只有一条：本地 env 指纹与索引不一致（[EnvRules.isUpToDate] 取反），
                                // 不是文案前缀 —— 同版本补指纹的文案不以「发现新版本」开头，按前缀判
                                // 会让它既不弹窗也不提示（2026-10-08 修）。挂 pending 放在判定之前，
                                // 与迁移前同序（判定里读盘抛异常时，迁移前也是先挂了 pending）。
                                if (!EnvRules.isUpToDate(localEnv, idx.env)) {
                                    pendingUrl = idx.url; pendingSha = idx.sha256
                                    pendingIdx = idx
                                }
                                val plan = EnvRules.planUpdate(
                                    localEnv = localEnv,
                                    indexEnv = idx.env,
                                    indexVersion = ver,
                                    indexSize = idx.size,
                                    // 惰性取值：迁移前这两项只在需要它的分支里读盘，传闭包保持读盘次数不变
                                    localVersion = {
                                        com.example.zhengdao.rootfs.RootfsCache.currentVersion(ctx).orEmpty()
                                    },
                                    cachedBytes = {
                                        com.example.zhengdao.rootfs.RootfsCache
                                            .localCandidateFor(ctx, idx.url, idx.size)?.length()
                                    },
                                    patchFrom = idx.patch?.from,
                                    patchSize = idx.patch?.size ?: 0L,
                                )
                                if (plan.wantPatch) pendingPatchRef = idx.patch
                                when (plan.notice) {
                                    // 旧安装（标记里没有 env 行）：第一次更新只能全量，装完就记上 env。
                                    // 2026-10-08：本地装的往往**就是同一个版本**（13.7），只是标记缺 env 行，
                                    // 却被叫成"发现新版本 13.7"——用户会以为真出了新版（真机投诉）。
                                    // 所以按本地已装版本分两种口径：同版本＝补指纹，不同/未知＝确实有新版。
                                    // 缓存里若已躺着索引那个包（下过一次没装成、或上次重装留下的），
                                    // 就多一条"本地已有"的提示可选；只按大小定位，不据此改走本地安装
                                    // （2026-10-14 用户拍板：环境更新**不再用本地包**，一律重新下载）。
                                    EnvRules.UpdateNotice.LocalEnvMissingCached -> res.getString(
                                        R.string.env_local_env_missing_cached,
                                        plan.version,
                                        bytesMbText(plan.sizeBytes, res),
                                    )
                                    EnvRules.UpdateNotice.LocalCached -> res.getString(
                                        R.string.env_local_cached,
                                        bytesMbText(plan.sizeBytes, res),
                                    )
                                    EnvRules.UpdateNotice.LocalEnvMissingFull -> res.getString(
                                        R.string.env_local_env_missing_full,
                                        plan.version,
                                        bytesMbText(plan.sizeBytes, res),
                                    )
                                    EnvRules.UpdateNotice.NewVersionNoLocalRecord -> res.getString(
                                        R.string.env_new_version_no_local_record,
                                        plan.version,
                                        bytesMbText(plan.sizeBytes, res),
                                    )
                                    EnvRules.UpdateNotice.UpToDate -> res.getString(
                                        R.string.env_up_to_date,
                                        plan.version,
                                        localEnv ?: "",
                                    )
                                    // 有新版本：本地已有索引那个整包只影响给的提示文案，不改更新路径。
                                    EnvRules.UpdateNotice.NewVersionCached -> res.getString(
                                        R.string.env_new_version_cached,
                                        plan.version,
                                        bytesMbText(plan.sizeBytes, res),
                                    )
                                    EnvRules.UpdateNotice.NewVersionPatch -> res.getString(
                                        R.string.env_new_version_patch,
                                        plan.version,
                                        bytesMbText(plan.sizeBytes, res),
                                    )
                                    EnvRules.UpdateNotice.NewVersionFull -> res.getString(
                                        R.string.env_new_version_full,
                                        plan.version,
                                        localEnv ?: "",
                                        bytesMbText(plan.sizeBytes, res),
                                    )
                                }
                            } else {
                                // ② 索引取不到：保留老 manifest 提示路径（该文件已无人维护，见 ERRATA E-033）
                                val manifestText = com.example.zhengdao.rootfs.RootfsDownloader
                                    .fetchText(com.example.zhengdao.rootfs.RootfsCache.MANIFEST_URL)
                                if (manifestText != null) {
                                    val feed = EnvRules.parseLegacyManifest(manifestText)
                                    val installed = com.example.zhengdao.rootfs.RootfsCache.currentVersion(ctx)
                                    when {
                                        feed.version == null || feed.url == null ->
                                            res.getString(R.string.env_feed_unreadable)
                                        // 刻意不再判定"已是最新"：旧 manifest 的 version 恒为发行版号
                                        // （13.7），拿它跟本地比得出的"最新"是假的（见 ERRATA E-033）。
                                        // 拿不到索引时只给"可下载"的事实，不替用户下结论。
                                        else -> {
                                            pendingUrl = feed.url; pendingSha = feed.sha256
                                            res.getString(
                                                R.string.env_only_old_manifest,
                                                installed,
                                                feed.version,
                                            )
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
                                    if (tag != null) res.getString(R.string.env_latest_release, tag)
                                    else res.getString(R.string.env_repo_unparsable)
                                }
                            }
                        } catch (t: Throwable) {
                            // R-29（第⑯刀）：内部异常原文不进 UI —— 用户看 HumanizeError 人话，原文只落 RunLog
                            com.example.zhengdao.rootfs.RunLog.log(
                                "环境检查失败（原文）：${t.javaClass.simpleName}: ${t.message}"
                            )
                            res.getString(R.string.env_check_failed, HumanizeError.title(t, res))
                        }
                        android.os.Handler(ctx.mainLooper).post {
                            updateMsg = result; checking = false
                            pendingUpdateUrl = pendingUrl
                            pendingUpdateSha = pendingSha
                            pendingIndex = pendingIdx
                            pendingPatch = pendingPatchRef
                            // 有可下载目标 → 交给下面的弹窗（用户点「下载并安装」）；
                            // 没有目标（已是最新 / 只能手动去下载） → 用可读 Snackbar 说清楚。
                            // 判据是 pendingUrl 而不是文案前缀：同版本补指纹的文案不以"发现新版本"开头，
                            // 按前缀判断会让它既不弹窗也不提示（2026-10-08 修）。
                            // ENV-D1（2026-10-21）：这些结论最长 60 字（英文 180+ 字符），系统 Toast
                            // 只有两行 ⇒ 改走 Snackbar，第二句/补充说明不再被截。
                            if (pendingUrl == null) {
                                scope.launch {
                                    snackbarHostState.showReadable(
                                        message = result,
                                        actionLabel = gotIt,
                                    )
                                }
                            }
                        }
                    }.apply { isDaemon = true; name = "zd-env-check" }.start()
                },
            ) {
                Text(
                    if (checking) stringResource(R.string.env_checking)
                    else stringResource(R.string.env_check_btn)
                )
            }
            installState?.let { st ->
                Spacer(Modifier.height(6.dp))
                // 2026-10-08 修：此前无论 phase 是什么都硬写"⏳ 正在安装环境…"，且 Done 之后
                // 就一直挂着（真机现象："⏳ 正在安装环境……安装完成！"，还带着"不能重复检查更新"
                // 这句对已结束安装毫无意义的话，被用户读成"还在装"）。现在按 phase 给图标与颜色，
                // "正在安装"只属于 Running；Done/Failed 收尾成一次性状态，可点「知道了」清掉。
                // 第八十批：图标/错误色/收尾行/重试入口的对应关系搬到 EnvRules.phaseView（可单测）
                val view = EnvRules.phaseView(st.phase)
                // 第八十批：颜色口径与迁移前**完全一致**（失败仍用 colorScheme.error，视觉零变化）。
                // 该处「错误色当正文」是迁移前就有的 AA 问题（3.55:1），本批不改视觉，已记入报告遗留项。
                val statusColor = if (view.isError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary
                Text(
                    text = when (st.phase) {
                        InstallProgress.Phase.Running ->
                            "${view.icon} " + stringResource(
                                R.string.env_installing,
                                if (st.fromLocal) stringResource(R.string.env_install_local)
                                else stringResource(R.string.env_install_online),
                                st.text,
                            )
                        InstallProgress.Phase.Done ->
                            "${view.icon} " + stringResource(R.string.env_install_last, st.text)
                        InstallProgress.Phase.Failed ->
                            "${view.icon} " + stringResource(R.string.env_install_last_failed, st.text)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor,
                )
                if (view.showCloseRow) {
                    // 结束后给一个明确的收尾动作：否则这条状态会一直占着按钮下面的位置
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // #4（2026-10-09）：失败时此前只有「知道了」——把它清掉就再没有任何重试入口，
                        // 用户只能自己想起"修复环境"那张卡。这里直接给重试，走的就是同一条修复流程
                        // （本地安装包重解压，不联网；见下方 repairConfirm 的二次确认）。
                        if (view.showRetry) {
                            TextButton(onClick = { repairConfirm = true }) {
                                Text(stringResource(R.string.env_retry_install))
                            }
                        }
                        TextButton(onClick = { InstallProgress.clear() }) {
                            Text(stringResource(R.string.common_got_it))
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.env_manifest_line, AgentManifest.cachedVersionText(ctx)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = {
                Toast.makeText(ctx, res.getString(R.string.env_manifest_refreshing), Toast.LENGTH_SHORT).show()
                AgentManifest.refresh(ctx, force = true) { applied ->
                    android.os.Handler(ctx.mainLooper).post {
                        Toast.makeText(
                            ctx,
                            res.getString(
                                if (applied) R.string.env_manifest_updated
                                else R.string.env_manifest_failed
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }) { Text(stringResource(R.string.env_manifest_refresh_btn)) }
            Spacer(Modifier.height(8.dp))

            // ── OpenCode 内置版更新入口已从这里移走（用户 2026-10-08：主打不是 OpenCode）──
            //   清场前只有「抱朴」抽屉底部那一处（BaopuScreen 的 OcVersionFooter，
            //   该抽屉 2026-10-16 随旧抱朴线整批删除）。
            //   OpenCode 随 APK 内置、开箱即用；真要重装/升级去抱朴抽屉。
        }
        // ── 运行内存上限（用户第四批）：ulimit -v 防单个任务膨胀拖垮整机 ──
        SectionCard(stringResource(R.string.env_memlimit_card)) {
            Text(
                text = stringResource(R.string.env_memlimit_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            var memLimit by remember {
                mutableStateOf(Settings.prefs(ctx).getString("guest_mem_limit_mb", "0") ?: "0")
            }
            // B-补-22（2026-10-16）：这三个芯片原来挤在一个裸 `Row` 里 —— 正常字号刚好一行三个，
            // 但系统字号放到 1.3× 以上时第三个（4GB）会被挤出卡片右沿**裁掉**（不是换行，用户看不到它）。
            // `FlowRow`：装得下时与原来完全同形（一行三个、间距 6dp），装不下就自动折到第二行。
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip2(stringResource(R.string.env_memlimit_off), memLimit == "0") {
                    memLimit = "0"
                    Settings.prefs(ctx).edit { putString("guest_mem_limit_mb", "0") }
                    Toast.makeText(
                        ctx,
                        res.getString(R.string.env_memlimit_toast_off),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                FilterChip2("3GB", memLimit == "3072") {
                    memLimit = "3072"
                    Settings.prefs(ctx).edit { putString("guest_mem_limit_mb", "3072") }
                    Toast.makeText(
                        ctx,
                        res.getString(R.string.env_memlimit_toast, "3GB"),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                FilterChip2("4GB", memLimit == "4096") {
                    memLimit = "4096"
                    Settings.prefs(ctx).edit { putString("guest_mem_limit_mb", "4096") }
                    Toast.makeText(
                        ctx,
                        res.getString(R.string.env_memlimit_toast, "4GB"),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }

        // ── 运行日志 ──
        // 第⑩刀审计 R-20（2026-10-14）：这段加载副作用是 P4-1 拆页留下的孤儿 —— 它写的
        // archiveCount / prevLogText 在**本文件里再没有读取点**（两张日志卡已搬到
        // ui/SettingsHelpActivity.kt），于是帮助页的归档份数与「上一轮出错」提示永远是空的。
        // 逻辑已原样搬回帮助页，这里连同两个死 state 一起删掉。
    }

    if (repairConfirm) {
        AlertDialog(
            onDismissRequest = { repairConfirm = false },
            title = { Text(stringResource(R.string.env_repair_card)) },
            text = { Text(stringResource(R.string.env_repair_confirm)) },
            confirmButton = {
                TextButton(
                    enabled = !InstallFlow.isRunning(), // #11 / E-078：与其它长流程共享 busy
                    onClick = {
                    // 第五十二批 · P0-5：活会话时不换 rootfs —— swapLocked 是「旧树改名 → 新树
                    // 就位 → 旧树递归删除」，运行中的 proot/agent 后续 open/exec 会 ENOENT。
                    // 判定放在任何下载/解压/替换之前；被拦时一个字节都不动，不报「安装失败」
                    // （这是前置条件不满足，不是安装失败）。
                    if (envInstallBlocked(com.example.zhengdao.terminal.SessionManager.isAlive(), "修复环境", com.example.zhengdao.rootfs.RunLog::log)) {
                        // ENV-D1：先关确认框再弹 Snackbar —— 对话框是独立窗口，不关会把
                        // 页面底部的 Snackbar 整个盖住；同时 Toast 两行上限会截掉第二句。
                        repairConfirm = false
                        scope.launch {
                            snackbarHostState.showReadable(
                                message = blockMessage,
                                actionLabel = gotIt,
                            )
                        }
                        return@TextButton
                    }
                    repairConfirm = false
                    val candidates = (
                        listOf(
                            File("/storage/emulated/0/Download/证道/debian-13.7-base-arm64.tar.zst"),
                        ) + com.example.zhengdao.rootfs.RootfsCache.listArchives(ctx)
                        // 第八十批：≥100MB 的门槛搬进 EnvRules（严格大于，与迁移前一致）
                        ).firstOrNull { EnvRules.isRepairCandidate(it.isFile, it.length()) }
                    if (candidates == null) {
                        // ENV-D1：英文 61 字符，超出两行 Toast 的可读上限 ⇒ 走 Snackbar。
                        scope.launch {
                            snackbarHostState.showReadable(
                                message = repairNoLocalMsg,
                                actionLabel = gotIt,
                            )
                        }
                    } else {
                        Thread {
                            // 进度走 InstallFlow（横幅状态 + 通知栏常驻 + RunLog）——此前这条路
                            // 从头到尾只有两条 Toast，用户看到的就是"点了没反应"（E-036 §7）。
                            InstallFlow.start(
                                ctx,
                                res.getString(
                                    R.string.env_repair_start,
                                    candidates.length() / (1024 * 1024),
                                ),
                                fromLocal = true,
                            )
                            try {
                                InstallFlow.update(ctx, res.getString(R.string.env_preparing, candidates.name))
                                val trace = com.example.zhengdao.rootfs.InstallTrace()
                                val archive = File(ctx.cacheDir, candidates.name)
                                val copyAt = System.nanoTime()
                                if (archive.absolutePath != candidates.absolutePath) candidates.copyTo(archive, true)
                                trace.record(
                                    com.example.zhengdao.rootfs.InstallTrace.Stage.DOWNLOAD,
                                    (System.nanoTime() - copyAt) / 1_000_000L,
                                    "本地包拷贝 ${archive.length() / (1024 * 1024)}MB",
                                )
                                InstallFlow.update(ctx, res.getString(R.string.env_unpacking_long))
                                // 校验与解包同遍（Rust 对账）：派工单 §1-② 要求"若与下载同遍则记 0 并注明"
                                trace.record(
                                    com.example.zhengdao.rootfs.InstallTrace.Stage.VERIFY,
                                    0L,
                                    "与解包同遍（Rust 对账）",
                                )
                                // 修复＝拿本地这个包再解压一次。若它与「当初装出当前 env 的那个包」
                                // 逐字节相同（标记里的 archive-sha256 对得上），内容指纹照样成立 ⇒ 原样写回，
                                // 否则一次修复就把增量基线抹掉、下次更新被迫全量（2026-10-08 真机 bug，
                                // 详见 RootfsInstaller.envForReinstall 的 KDoc）。
                                //
                                // 第六十五批（去重复校验）：这里**不再** sha256Of 整包读一遍。Rust 解包
                                // 流水线本来就会对恰好被解压的字节算 sha256 并回传（extract.rs 步骤 1），
                                // 安装内核拿它调 resolveEnv 即可——同一份包在本次安装里只哈希一遍。
                                // 改前：sha256Of（校验 184ms）+ Rust 对账 = 2 遍；改后：安装内 1 遍。
                                // Java 回退拿不到 sha ⇒ 不写 env（与"无法确认同源"同一处理，下次老实全量）。
                                val repairRootfs = File(ctx.filesDir, "rootfs")
                                com.example.zhengdao.rootfs.RootfsInstaller.install(
                                    ctx,
                                    archive,
                                    trace = trace,
                                    resolveEnv = { actualSha ->
                                        val env = com.example.zhengdao.rootfs.RootfsInstaller.envForReinstall(
                                            repairRootfs,
                                            actualSha,
                                        )
                                        if (env != null) {
                                            com.example.zhengdao.rootfs.RunLog.log(
                                                "修复环境：本地包与当前环境同源（sha=${actualSha.take(12)}），保留环境记录 $env",
                                            )
                                        } else {
                                            com.example.zhengdao.rootfs.RunLog.log(
                                                "修复环境：本地包与当前环境无法确认同源（sha=${actualSha.take(12)}），不写环境记录（下次更新要重下完整包）",
                                            )
                                        }
                                        env
                                    },
                                ) { }
                                InstallFlow.finish(
                                    ctx,
                                    res.getString(R.string.env_repair_done),
                                )
                                android.os.Handler(ctx.mainLooper).post { storageTick++ }
                            } catch (t: Throwable) {
                                InstallFlow.fail(
                                    ctx,
                                    res.getString(R.string.env_repair_failed, HumanizeError.title(t, res)),
                                )
                                // 2026-10-08：Toast → Snackbar（带"查看日志"action），同"回退失败"分支
                                scope.launch {
                                    val r = snackbarHostState.showSnackbar(
                                        message = res.getString(
                                            R.string.env_repair_failed,
                                            HumanizeError.title(t, res),
                                        ),
                                        actionLabel = res.getString(R.string.common_view_log),
                                        duration = SnackbarDuration.Indefinite,
                                    )
                                    if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                                        // 同上：现场取日志再弹，缺日志时会有指向目录的 Toast
                                        openLogFromSnackbar()
                                    }
                                }
                            }
                        }.apply { isDaemon = true; name = "zd-env-repair" }.start()
                    }
                }) { Text(stringResource(R.string.env_repair_action)) }
            },
            dismissButton = {
                TextButton(onClick = { repairConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    // ── 环境更新弹窗（pendingUpdateUrl 驱动）：确认后在应用内下载到公共缓存并安装 ──
    // 2026-10-12：标题原本按文案前缀（startsWith("发现新版本")）二选一——接入 i18n 后
    // 文案会变成英文，前缀判断必然失效 ⇒ 改为统一标题，区分交给正文 msg 自己说。
    updateMsg?.takeIf { pendingUpdateUrl != null }?.let { msg ->
        AlertDialog(
            onDismissRequest = { updateMsg = null },
            title = { Text(stringResource(R.string.env_update_dialog_title)) },
            text = { Text(msg) },
            confirmButton = {
                TextButton(
                    enabled = !InstallFlow.isRunning(), // #11 / E-078：与其它长流程共享 busy
                    onClick = {
                    // 第五十二批 · P0-5：活会话时不换 rootfs（增量 apply 与全量 install 走同一个
                    // 整树替换）。判定在下单/下载之前，被拦时零文件改动。
                    if (envInstallBlocked(com.example.zhengdao.terminal.SessionManager.isAlive(), "环境更新", com.example.zhengdao.rootfs.RunLog::log)) {
                        // ENV-D1：同修复路径 —— 先关更新确认框，Snackbar 才不会被盖住。
                        updateMsg = null
                        scope.launch {
                            snackbarHostState.showReadable(
                                message = blockMessage,
                                actionLabel = gotIt,
                            )
                        }
                        return@TextButton
                    }
                    val url = pendingUpdateUrl
                    updateMsg = null
                    if (url == null) {
                        ctx.startActivity(
                            Intent(Intent.ACTION_VIEW,
                                "https://github.com/pisces19860207/zhengdao/releases".toUri())
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
                            // 2026-10-14 用户拍板：**本地已有整包也不再直接重解压**，一律重新下载
                            // （原先这条 ⓪ 分支见 git 历史；保留它的收益是省一次下载，代价是"本地
                            // 缓存复用"这条便利本身要取消）。
                            // 进度走 InstallFlow：通知栏常驻进度条 + 设置页状态行 + RunLog。
                            // 此前是每 20% 闪一条 Toast（"下载中 20%"），正是用户说的
                            // "提示时间有点短……我以为要重新下载呢"（E-036 §7）。
                            InstallFlow.start(ctx, res.getString(R.string.env_update_start_download), fromLocal = false)
                            val archive = RootfsCache.archiveFor(ctx, url)

                            // ── ① 先试增量（协议 §6）：只有索引给了补丁、且本机基线正是补丁基线时才走 ──
                            var deltaDone = false
                            if (patchRef != null && RootfsDelta.canApply(ctx, patchRef)) {
                                val deltaFile = RootfsCache.deltaFor(ctx, patchRef.url)
                                try {
                                    InstallFlow.update(
                                        ctx,
                                        res.getString(R.string.env_patch_downloading, bytesMbText(patchRef.size, res)),
                                    )
                                    RootfsDownloader.download(
                                        urls = RootfsDownloader.withMirrorFallback(patchRef.url),
                                        dest = deltaFile,
                                        shaUrls = RootfsDownloader.withMirrorFallback("${patchRef.url}.sha256"),
                                    ) { done, total ->
                                        // 第八十批：10% 一档的节流判据搬进 EnvRules（整数除法口径不变）
                                        val pct = EnvRules.progressPercent(done, total)
                                        if (pct != null) {
                                            InstallFlow.update(
                                                ctx,
                                                res.getString(
                                                    R.string.env_patch_progress,
                                                    pct,
                                                    done / (1024 * 1024),
                                                    total / (1024 * 1024),
                                                ),
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
                                        ?: throw RootfsInstaller.InstallFailed(
                                            res.getString(R.string.env_patch_meta_missing)
                                        )
                                    // 索引给的补丁 sha256 双重把关：下载校验一次（协议 §4），
                                    // 再把同一期望值交给解压流水线（Rust 对"恰好被解压的字节"算一次）
                                    RootfsDelta.apply(ctx, deltaFile, info, expectedSha256 = patchRef.sha256)
                                    // 增量成功后照旧做一次缓存整理（与全量路径一致）
                                    RootfsCache.pruneKeep(ctx)
                                    deltaDone = true
                                    // 让用户明确知道"这次没有重下 300MB"——他上次的误会正来自这里
                                    InstallFlow.update(
                                        ctx,
                                        res.getString(R.string.env_patch_done, bytesMbText(patchRef.size, res)),
                                    )
                                } catch (t: Throwable) {
                                    // 增量路径的任何失败（含基线不符 BaseMismatch）都回退全量：
                                    // applyTo 是原子的——此时 rootfs 要么没动，要么已完整换成新环境
                                    // R-29（第⑯刀）：fallbackNote 会进 InstallFlow.finish 的用户可见文案，
                                    // 原文只落 RunLog（下一行），UI 拿人话
                                    val why = HumanizeError.title(t, res)
                                    com.example.zhengdao.rootfs.RunLog.log("小更新失败，改为完整下载：${t.message}")
                                    fallbackNote = res.getString(R.string.env_patch_fallback, why)
                                } finally {
                                    RootfsCache.cleanupDelta(ctx, deltaFile)
                                }
                            }

                            if (!deltaDone) {
                                // ── ② 全量下载安装（原流程，也是增量失败的唯一兜底）──
                                val trace = com.example.zhengdao.rootfs.InstallTrace()
                                val downloadAt = System.nanoTime()
                                val verifiedSha = RootfsDownloader.download(
                                    // 镜像兜底（v1.2 B3）：环境更新走的是同一条 github 直链
                                    urls = RootfsDownloader.withMirrorFallback(url),
                                    dest = archive,
                                    shaUrls = RootfsDownloader.withMirrorFallback("$url.sha256"),
                                ) { done, total ->
                                    // 第八十批：同上，全量下载走同一条节流判据
                                    val pct = EnvRules.progressPercent(done, total)
                                    if (pct != null) {
                                        InstallFlow.update(
                                            ctx,
                                            res.getString(
                                                R.string.env_full_downloading,
                                                pct,
                                                done / (1024 * 1024),
                                                total / (1024 * 1024),
                                            ),
                                            pct,
                                        )
                                    }
                                }
                                trace.record(
                                    com.example.zhengdao.rootfs.InstallTrace.Stage.DOWNLOAD,
                                    (System.nanoTime() - downloadAt) / 1_000_000L,
                                    "联网下载+下载时校验 ${archive.length() / (1024 * 1024)}MB",
                                )
                                // download() 返回前已经整包校验过一次（它就是返回值）。
                                trace.preHashPasses += 1
                                // 第六十五批（去重复校验）：只有**两个可信来源不一致**时才按索引值
                                // 再验一遍（此时多读一遍是必要的证据，而不是日常开销）；一致就跳过——
                                // 改前这里的 verifySha256 是无条件执行的，等于下载后立刻整读第二遍。
                                if (!expectedSha.isNullOrBlank() &&
                                    !verifiedSha.isNullOrBlank() &&
                                    !verifiedSha.equals(expectedSha, ignoreCase = true)
                                ) {
                                    val rehashAt = System.nanoTime()
                                    RootfsDownloader.verifySha256(archive, expectedSha)
                                    trace.record(
                                        com.example.zhengdao.rootfs.InstallTrace.Stage.VERIFY,
                                        (System.nanoTime() - rehashAt) / 1_000_000L,
                                        "索引值与下载值不一致，按索引复验",
                                    )
                                    trace.preHashPasses += 1
                                }
                                // 信任锚（用户 2026-10-08 规则）：只有索引 sha256 == 实际校验通过的 sha256
                                // 才敢把索引的 env 写进标记；否则照装不写 env ⇒ 下次自然走全量。
                                val actualSha = verifiedSha ?: expectedSha
                                val envToWrite = RootfsInstaller.envForMarker(index?.env, index?.sha256, actualSha)
                                if (index != null && envToWrite == null) {
                                    com.example.zhengdao.rootfs.RunLog.log(
                                        "安装包校验值与更新信息对不上，这次不写环境记录"
                                    )
                                }
                                InstallFlow.update(ctx, res.getString(R.string.env_unpacking_here))
                                RootfsInstaller.ensureFreeSpace(ctx, archive.length())
                                // archiveSha256 一并写进标记：它是下次「修复环境/回退」判断
                                // "本地这个包是否就是装出当前 env 的那个包"的唯一依据（见
                                // RootfsInstaller.envForReinstall）。全量更新路径有确定值，一定传。
                                RootfsInstaller.install(ctx, archive, envToWrite, actualSha, trace) { }
                                RootfsCache.pruneKeep(ctx)
                            }
                            InstallFlow.finish(ctx, fallbackNote + res.getString(R.string.env_update_done))
                            android.os.Handler(ctx.mainLooper).post { storageTick++ }
                        } catch (t: Throwable) {
                            // R-29（第⑯刀）：why 进 InstallFlow.fail + 可读 Snackbar（用户可见），原文只落 RunLog
                            com.example.zhengdao.rootfs.RunLog.log(
                                "环境更新失败（原文）：${t.javaClass.simpleName}: ${t.message}"
                            )
                            val why = HumanizeError.title(t, res)
                            InstallFlow.fail(ctx, res.getString(R.string.env_update_failed, why))
                            // ENV-D1：失败原因可长可短，Toast 两行装不下 ⇒ 与「修复失败」同口径：
                            // Snackbar 全程可读，点「查看日志」现场取日志。
                            scope.launch {
                                val r = snackbarHostState.showReadable(
                                    message = String.format(
                                        java.util.Locale.getDefault(),
                                        updateFailedTemplate,
                                        why,
                                    ),
                                    actionLabel = viewLog,
                                )
                                if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                                    openLogFromSnackbar()
                                }
                            }
                        }
                    }.apply { isDaemon = true; name = "zd-env-update" }.start()
                }) {
                    Text(stringResource(R.string.env_install_download_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = { updateMsg = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    SettingsLogDialog(log = logDialogText, onDismiss = { logDialogText = null })
}

