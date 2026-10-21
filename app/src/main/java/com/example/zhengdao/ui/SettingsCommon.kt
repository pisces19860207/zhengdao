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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.launch
import com.example.zhengdao.R
import com.example.zhengdao.core.AppPrefs
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
/**
 * 索引里的字节数 → 人读大小。`<= 0` = 索引没给这个字段（老格式/字段缺失），
 * 显示"大小未知"而不是"约 0 MB"（后者会让用户以为包是空的）。
 *
 * [res] 传 null 时回退中文（无 Context 场景），传 `ctx.resources` 即随界面语言。
 */
internal fun bytesMbText(bytes: Long, res: android.content.res.Resources? = null): String =
    if (bytes > 0) {
        val mb = (bytes + 524_288) / 1_048_576
        res?.getString(R.string.common_approx_mb, mb) ?: "约 $mb MB"
    } else {
        res?.getString(R.string.common_size_unknown) ?: "大小未知"
    }
/** 设置偏好（工作区模式 / 已安装环境版本登记）。 */
object Settings {
    /**
     * 第㉛刀（Qoder 🟡-3）：存储真源下沉到 [AppPrefs]——terminal（底层）原来
     * 反向 import 本类拿 prefs，依赖方向反了。这里保留同名 API 委托过去，
     * ui 侧调用点零改动；文件名与键一字不变 ⇒ 行为零变化。
     */
    fun prefs(ctx: android.content.Context) = AppPrefs.prefs(ctx)
}

/**
 * 打开终端并带上一次性命令 / agent id。
 *
 * 与 `MainActivity.terminalIntent` 是**同一条意图**（CLEAR_TOP | SINGLE_TOP，让既有终端
 * 提到前台并走 onNewIntent）。二级页各自是独立 Activity，lambda 传不过去，所以在这里
 * 复刻一份 —— 卡片里的调用点一个字没改。
 */
internal fun openTerminal(context: android.content.Context, autocmd: String?, agentId: String?) {
    context.startActivity(
        android.content.Intent(context, com.example.zhengdao.TerminalActivity::class.java).apply {
            putExtra("autocmd", autocmd)
            putExtra("agent_id", agentId)
            addFlags(
                android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
    )
}

/**
 * 二级设置页统一顶栏：左「‹ 返回」+ 居中标题（与保活记录页同款）。
 *
 * B-补-8（2026-10-16）：原来是写死的 `height(48.dp)` —— 48dp 是**触摸目标下限**，不是高度上限；
 * 系统字号放大（≥1.3×）时标题会被这个硬高度裁掉半行。改用 `defaultMinSize(minHeight = 48.dp)`：
 * 常规字号下视觉高度仍是 48dp，大字号下随内容自然长高。四个顶栏同改（本文件 ＋ `MainActivity`
 * 设置路由 ＋ `KeepaliveLogActivity` ＋ `OpenSourceLicensesActivity`）。
 */
@Composable
internal fun SettingsTopBar(title: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)) {
        TextButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BackChevron(tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.common_back), color = MaterialTheme.colorScheme.primary)
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

/**
 * 二级设置页外壳（2026-10-11 设置页重排时新增）。
 *
 * 顶栏 + 与设置页完全一致的滚动内容区（同样的 16dp 横向边距、20dp 组间距、8dp 页脚）。
 * 卡片内容是从原设置页**逐字搬过来**的，没有改过一个字 —— 这里只负责外壳。
 *
 * [scrollToEnd]（2026-10-11，台账 §B 顺序 11）：给「首页通知按钮直接跳到本页某一节」用 ——
 * 目前只有「帮助」页的「运行日志」是**最后一张卡**（`ui/SettingsHelpActivity.kt`），
 * 从首页点「看日志」时直接落在那张卡上，不用自己往下滑。
 */
@Composable
internal fun SettingsSubPage(
    title: String,
    onBack: () -> Unit,
    snackbar: SnackbarHostState? = null,
    scrollToEnd: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollState = rememberScrollState()
    if (scrollToEnd) {
        // maxValue 要等测量完成才有值，故在它变化时再滚一次（只在首帧后生效）
        LaunchedEffect(scrollState.maxValue) {
            if (scrollState.maxValue > 0) scrollState.scrollTo(scrollState.maxValue)
        }
    }
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        SettingsTopBar(title = title, onBack = onBack)
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                content()
                Spacer(Modifier.height(8.dp))
            }
            if (snackbar != null) {
                SnackbarHost(
                    hostState = snackbar,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .wrapContentHeight(Alignment.Bottom),
                )
            }
        }
    }
}

/**
 * 需要用户**读完**的 Snackbar（第一百一十批 · ENV-D1，2026-10-21）。
 *
 * 为什么不用 Toast：Android 12+ 的文本 Toast 只显示两行（真机 Android 16 实测），
 * P0-5 拦截文案的第二句「本次没有改动任何文件…」就是被系统这样截掉的。Snackbar 正文
 * 按可用宽度自然折行、不限行数；`Indefinite` 让文案一直留到用户点动作按钮
 * （「知道了」/「查看日志」），1.3× 字号下也不会读一半就消失。
 *
 * 只统一**展示口径**：宿主仍是各页现有的 [SnackbarHost] 与 Material3 默认样式，
 * 不新造 token、不改布局语言。
 */
internal suspend fun SnackbarHostState.showReadable(
    message: String,
    actionLabel: String,
): SnackbarResult =
    showSnackbar(
        message = message,
        actionLabel = actionLabel,
        duration = SnackbarDuration.Indefinite,
    )

/** 设置页统一分隔线（B-补-16）：全页只此一种粗细与颜色，行间不再各写各的。 */
@Composable
internal fun SettingsDivider() {
    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * 「了解详情」折叠说明（2026-10-12 走查报告 §5.6）。
 *
 * 要解决的现象：每张卡都是「标题 + 一大段解释 + 按钮」，十几张卡读下来眼睛全被解释性
 * 文字占满。药方是**按需展开**：默认只显示第一句，长说明折起来，点「了解详情」再看。
 * 信息不丢——只是从「默认全摊」改成「要的时候才展开」。
 *
 * 取材策略（[shortThreshold] = 40 字）：短于此的说明直接全显、不给折叠入口（折叠本身
 * 也是干扰）；更长的取**第一句**（按 `。` / `！` / `？` / 换行切），切不出合适句子时
 * 退化为按字数截断 + 省略号。
 */
@Composable
internal fun ExpandableHint(
    text: String,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    shortThreshold: Int = 40,
) {
    if (text.length <= shortThreshold) {
        Text(text = text, style = style, color = color)
        return
    }
    var expanded by remember { mutableStateOf(false) }
    Text(
        text = if (expanded) text else firstSentenceOf(text, shortThreshold),
        style = style,
        color = color,
    )
    Text(
        text = if (expanded) stringResource(R.string.common_collapse)
        else stringResource(R.string.common_learn_more),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(top = 2.dp)
            .pressClickable { expanded = !expanded },
    )
}

/** 取第一句；切点太靠前（<4 字）或太靠后（等于全文）时按字数截断。 */
private fun firstSentenceOf(text: String, threshold: Int): String {
    val end = text.indexOfFirst { it == '。' || it == '！' || it == '？' || it == '\n' }
    return if (end in 4 until text.length - 1) {
        text.substring(0, end + 1)
    } else {
        text.take(threshold).trimEnd() + "…"
    }
}

/**
 * 运行日志弹窗：**文本即开关**（null = 不弹）。
 *
 * 逐字搬自设置页原实现（含「复制全部」与 takeLast(6000)）——
 * 现在三个二级页（存储与缓存 / 环境 / 帮助）共用它。
 */
@Composable
internal fun SettingsLogDialog(log: String?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    log?.takeIf { it.isNotBlank() }?.let { text ->
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.help_log_title)) },
            text = {
                // B-补-11（2026-10-16 修）：原先这里的 Text 没有滚动，长日志（最多 6000 字）
                // 被 AlertDialog 的可用高度裁掉且**滑不动** ⇒ 补 verticalScroll，
                // 与设置页正文（见本文件 SettingsScaffold 的 Column）同一套写法。
                Text(
                    text = text.takeLast(6000),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("zhengdao-log", text))
                    Toast.makeText(ctx, ctx.getString(R.string.help_log_copied), Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.common_copy_all)) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
            },
        )
    }
}

/**
 * 取一次运行日志原文交给 [onLog]；一份都没有时给指向日志目录的 Toast（绝不静默）。
 * 取件顺序：本轮日志 → 最新归档 → 旧版 .prev 文件（与设置页原实现一致）。
 */
internal fun openLogDialog(scope: CoroutineScope, ctx: Context, onLog: (String) -> Unit) {
    scope.launch {
        val text = withContext(Dispatchers.IO) {
            runCatching {
                (RunLog.file() ?: RunLog.latestArchive() ?: RunLog.prevFile())?.readText()
            }.getOrNull()
        }
        if (text.isNullOrBlank()) {
            Toast.makeText(
                ctx,
                ctx.getString(R.string.help_log_missing, RunLog.dirPath(ctx)),
                Toast.LENGTH_LONG,
            ).show()
        } else {
            onLog(text)
        }
    }
}

/** 新手指南：三步上手的单行（蓝色序号 + 说明）。 */
@Composable
internal fun GuideLine(number: String, text: String) {
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
internal fun FaqLine(question: String, answer: String) {
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
    // D-2（第⑪批 B-1）：列子目录是磁盘 IO（listFiles），原先在 remember(current) 里
    // **组合期同步**跑 —— key 是 current，每进一级目录都在主线程读一次共享存储
    // （FUSE 慢路径上能感知卡顿）。照 SettingsStorageActivity 的 StorageAudit 同款改法：
    // LaunchedEffect(current) + withContext(Dispatchers.IO)，行为不变（进目录列子目录），
    // 只是列表变成「先短暂空白 → IO 完成就地出现」。
    var entries by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(current) {
        entries = withContext(Dispatchers.IO) {
            runCatching {
                File(current).listFiles { f -> f.isDirectory }
                    ?.sortedBy { it.name.lowercase() }
                    ?.map { it.name }
                    ?: emptyList()
            }.getOrDefault(emptyList())
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ws_picker_title), style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                Text(
                    text = current.removePrefix(sharedRoot)
                        .ifBlank { stringResource(R.string.ws_picker_root) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                if (current != sharedRoot) {
                    TextButton(onClick = { current = File(current).parent ?: sharedRoot }) {
                        Text(stringResource(R.string.ws_picker_up))
                    }
                }
                // B-补-8（2026-10-16）：原来写死 height(280.dp)，文件夹少时也撑出一大块空白；
                // 改成 heightIn(max = 280.dp) —— 上限不变，内容少时对话框自然变矮、内容多时照旧滚动。
                Column(modifier = Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    if (entries.isEmpty()) {
                        Text(stringResource(R.string.ws_picker_empty), style = MaterialTheme.typography.bodySmall)
                    }
                    entries.forEach { name ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pressClickable { current = File(current, name).absolutePath }
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
                    text = stringResource(R.string.ws_picker_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onPick(current) }) { Text(stringResource(R.string.ws_picker_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
/**
 * 「资料库 · 查看说明」对话框。
 *
 * 直接读资料库里的 `说明.md`（那份文件是写给**用户**看的人话说明，通篇无技术词）。
 * 文件还没生成时给一句友好提示，而不是报错 —— 它会在用户第一次打开终端后出现。
 */
@Composable
internal fun KnowledgeBaseHelpDialog(ctx: android.content.Context, onDismiss: () -> Unit) {
    var body by remember { mutableStateOf(ctx.getString(R.string.common_loading)) }
    LaunchedEffect(Unit) {
        body = withContext(Dispatchers.IO) {
            runCatching {
                val f = File(
                    com.example.zhengdao.terminal.KnowledgeBase.root(ctx),
                    com.example.zhengdao.terminal.KnowledgeBase.F_README,
                )
                if (f.isFile) f.readText(Charsets.UTF_8)
                else ctx.getString(
                    R.string.kb_help_missing,
                    com.example.zhengdao.terminal.KnowledgeBase.root(ctx).absolutePath,
                )
            }.getOrDefault(ctx.getString(R.string.kb_help_failed))
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_got_it)) } },
        title = { Text(stringResource(R.string.kb_help_title)) },
        text = {
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
    )
}
