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
 */
internal fun bytesMbText(bytes: Long): String =
    if (bytes > 0) "约 ${(bytes + 524_288) / 1_048_576} MB" else "大小未知"
/** 设置偏好（工作区模式 / 已安装环境版本登记）。 */
object Settings {
    fun prefs(ctx: android.content.Context) =
        ctx.getSharedPreferences("zhengdao-settings", android.content.Context.MODE_PRIVATE)
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

/** 二级设置页统一顶栏：左「‹ 返回」+ 居中标题（与保活记录页同款）。 */
@Composable
internal fun SettingsTopBar(title: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(48.dp)) {
        TextButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BackChevron(tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(4.dp))
                Text("返回", color = MaterialTheme.colorScheme.primary)
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

/** 设置页统一分隔线（B-补-16）：全页只此一种粗细与颜色，行间不再各写各的。 */
@Composable
internal fun SettingsDivider() {
    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
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
            title = { Text("运行日志") },
            text = { Text(text.takeLast(6000), style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                TextButton(onClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("zhengdao-log", text))
                    Toast.makeText(ctx, "已复制全部日志", Toast.LENGTH_SHORT).show()
                }) { Text("复制全部") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("关闭") }
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
                "这次的日志还没写出来。日志目录：${RunLog.dirPath(ctx)}（用文件管理器打开）",
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
/**
 * 「资料库 · 查看说明」对话框。
 *
 * 直接读资料库里的 `说明.md`（那份文件是写给**用户**看的人话说明，通篇无技术词）。
 * 文件还没生成时给一句友好提示，而不是报错 —— 它会在用户第一次打开终端后出现。
 */
@Composable
internal fun KnowledgeBaseHelpDialog(ctx: android.content.Context, onDismiss: () -> Unit) {
    var body by remember { mutableStateOf("读取中…") }
    LaunchedEffect(Unit) {
        body = withContext(Dispatchers.IO) {
            runCatching {
                val f = File(
                    com.example.zhengdao.terminal.KnowledgeBase.root(ctx),
                    com.example.zhengdao.terminal.KnowledgeBase.F_README,
                )
                if (f.isFile) f.readText(Charsets.UTF_8)
                else "说明文件还没生成 —— 打开一次终端后，它就会出现在资料库文件夹里。\n\n" +
                    "位置：" + com.example.zhengdao.terminal.KnowledgeBase.root(ctx).absolutePath
            }.getOrDefault("读取失败，请到资料库文件夹里直接打开「说明.md」。")
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
        title = { Text("资料库说明") },
        text = {
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
    )
}
