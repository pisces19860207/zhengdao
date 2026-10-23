// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-10-19-第三十八批 §2 B 段（产物在 App 内点开看）＋ §3「查看上限」
// ＋ §7（用户 2026-10-19 追加的硬要求：复制必须是全文，一个字符都不许少）
// ＋ §8（用户 2026-10-19 再追加：顶栏那个「最近一条回复」小框改成**产物条**）。
// 用户原话：「产物为什么不能在APP里直接点击查看啊？就是那些json、txt什么的」
// 「源码复制的时候不能只是半截或者少了吧？不然网络平台会保存不了。」
//
// 本文件只做**只读查看**与**复制**两件事：
// - 只读：整页没有任何写盘/删除/改名的调用（判据 B6 在源码级钉着）；文件内容
//   不落库、不进消息，只在用户点开的这一次组合里读出来。
// - 看可以截断（200 KB / 3000 行，保证渲染不卡），**复制不行**：`onCopyClick`
//   走 `artifactCopyOf` 从盘上重新完整读一遍，绝不拿下面显示用的截断串去复制
//   （§7 纪律①）。读回来的字节自证过「长度与盘上一致、UTF-8 往返一致」才写
//   剪贴板；自证不过 ⇒ 明确报「复制不完整，请重试」，不许静默（§7 纪律②）。
//   逐字节口径（不转义、不改换行、不加 BOM、不 trim、不补末尾换行）由
//   `artifactCopyOf` 保证——这里不给文本做任何加工，读回来是什么就交什么。
//
// 为什么单独开一个文件、不挂在 BaopuMessageList 里：30 批的门钉着「消息列表整
// 文件只应有 1 处 Dialog(」（图片全屏层专用）。查看器是另一层，别把它的 Dialog
// 塞进那本账。
package com.example.zhengdao.ui.baopu

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import androidx.core.content.getSystemService
import com.example.zhengdao.R
import com.example.zhengdao.baopu.ArtifactEntry
import com.example.zhengdao.baopu.ArtifactView
import com.example.zhengdao.baopu.artifactChipPlan
import com.example.zhengdao.baopu.artifactCopyOf
import com.example.zhengdao.baopu.artifactEntries
import com.example.zhengdao.baopu.artifactFileName
import com.example.zhengdao.baopu.artifactViewOf
import com.example.zhengdao.baopu.firstArtifactForCopy
import com.example.zhengdao.keepalive.CoroutineCrashHandler
import com.example.zhengdao.ui.theme.zhengdaoErrorText
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * §7 复制纪律的**唯一落点**（第三十八批；查看器与 §8 顶栏产物条都调这里）。
 *
 * 1. **查看截断 ≠ 复制截断**：源头永远是盘上的 `File`（`artifactCopyOf` 自己开文件读全量），
 *    绝不拿显示用的截断串——连参数都不接那份串，想拿也拿不到；
 * 2. **复制后自证**：`artifactCopyOf` 里比完字节才 `complete = true`；自证不过 ⇒ 不写剪贴板，
 *    回「复制不完整，请重试」，不许静默把半截当成功；
 * 3. **原文逐字节**：这里对文本一个字符都不加工（不转义、不改换行、不加 BOM、不 trim）。
 *
 * 返回给用户看的那一句（成功报「已复制 N 字符／M 行」，失败报明）。读盘在 IO 线程。
 */
private suspend fun copyArtifactToClipboard(context: Context, root: File, relPath: String): String {
    // 剪贴板标签是界面文案 ⇒ 必须在组合里取好再传进来（协程里不能调 stringResource，
    // 这是 38 批跑马灯同款坑）。
    val clipLabel = context.getString(R.string.baopu_artifact_row_label)
    val result = withContext(Dispatchers.IO) { artifactCopyOf(root, relPath) }
    val completeText = result?.takeIf { it.complete }?.text
    val ok = if (completeText == null) {
        false
    } else {
        runCatching {
            val cm = context.getSystemService<ClipboardManager>() ?: return@runCatching false
            cm.setPrimaryClip(ClipData.newPlainText(clipLabel, completeText))
            true
        }.getOrDefault(false)
    }
    return when {
        result == null -> context.getString(R.string.baopu_artifact_missing, relPath)
        !ok -> context.getString(R.string.baopu_artifact_copy_incomplete)
        else -> context.resources.getQuantityString(R.plurals.baopu_artifact_copied, result.chars, result.chars, result.lines)
    }
}

/**
 * 产物只读查看器（第三十八批 B 段）。
 *
 * @param root 工作区根目录（[com.example.zhengdao.baopu.BaopuWorkspace.hostDir]）——
 *        只允许读它下面的相对路径，形参加的是目录不是绝对文件路径。
 * @param relPath 产物相对路径（与 `verifyTouched` 里落库的那一份一致）。
 * @param onDismiss 关闭（调用方清 `controller.viewingArtifact`）。
 *
 * 读取一律 off-main：`produceState` + `withContext(Dispatchers.IO)`，房型照 34 批
 * 技能详情（`BaopuSkillCard`）——UI 线程读大文件是「点一下就卡住」的老坑。
 */
@Composable
internal fun BaopuArtifactViewer(
    root: File,
    relPath: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val view by produceState<ArtifactView?>(initialValue = null, key1 = root, key2 = relPath) {
        value = withContext(Dispatchers.IO) { artifactViewOf(root, relPath) }
    }
    // 复制的自证结果写在原地（不用 Toast：34 批技能详情「已复制」就是这个房型，
    // 而且失败那句要让用户看清楚，不该三秒就消失）。
    var copyNote by remember { mutableStateOf<String?>(null) }
    var copying by remember { mutableStateOf(false) }
    // P4-4 纪律：UI 作用域必须装 CoroutineCrashHandler（门禁 CoroutineCrashHandlerTest
    // 扫「没装 handler 的裸作用域」），协程崩了才留得下协程名与 Job 线索。
    val scope = rememberCoroutineScope { CoroutineCrashHandler }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.88f),
        ) {
            Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp)) {
                Text(
                    text = relPath,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                val v = view
                val bytesLine = if (v == null) {
                    stringResource(R.string.baopu_artifact_loading)
                } else {
                    pluralStringResource(R.plurals.baopu_artifact_bytes, if (v.fileBytes == 1L) 1 else 2, v.fileBytes)
                }
                Text(
                    text = bytesLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // §3 截断必须写明：只显示前 N 行；共 M 行在超 4 MB 没数完时不编数
                // （artifactViewOf 用 totalLines = -1 表示「没数完」）。
                if (v != null && v.truncated) {
                    Text(
                        text = if (v.totalLines >= 0) {
                            pluralStringResource(R.plurals.baopu_artifact_truncated, v.shownLines, v.shownLines, v.totalLines)
                        } else {
                            pluralStringResource(R.plurals.baopu_artifact_truncated_only, v.shownLines, v.shownLines)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        // P2-11：error 色 3.55:1 不达正文 AA 4.5:1，文字走语义色（门禁钉着）
                        color = zhengdaoErrorText(),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clipToBounds()
                        // 竖滚在外、横滚在内：Text 拿到的是无限宽无限高约束 ⇒ softWrap=false
                        // 才真按「不折行」量出最长那一行的宽度，横向滚得动。
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState()),
                ) {
                    when {
                        v == null -> Text(
                            text = stringResource(R.string.baopu_artifact_loading),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        else -> Text(
                            text = v.text,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            softWrap = false,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                copyNote?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        enabled = !copying,
                        onClick = {
                            copying = true
                            copyNote = null
                            scope.launch {
                                // §7 三条纪律都落在 copyArtifactToClipboard 里（与 §8 顶栏产物条同一份，
                                // 「复制全文」在本批只有一种含义）。
                                copyNote = copyArtifactToClipboard(context, root, relPath)
                                copying = false
                            }
                        },
                    ) {
                        Text(
                            text = if (copying) {
                                stringResource(R.string.baopu_artifact_loading)
                            } else {
                                stringResource(R.string.baopu_artifact_copy)
                            },
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.common_close))
                    }
                }
            }
        }
    }
}


/**
 * 顶栏产物条（§8，用户 2026-10-19 追加）。
 *
 * 用户原话：「顶部的『在的，抱朴在线』这个位置，这里现在是简略的回复信息，可以弄成那些代码啊
 * 什么的产物的吗？把那个复制功能做小点，这样点击展开就能显示更多的那些产物的内容了，也不用
 * 一定要去左侧栏翻产物区里的东西了。」
 *
 * 形态（§8.1）：折叠态**仍是一行** = 「产物 · N」＋第一个文件的短名（超宽省略号）＋右侧一个
 * **小号**复制按钮（labelSmall、24dp 高，不再占半行）；点这一行展开成每行「文件名＋大小＋复制」，
 * 高度上限 180dp、内部可竖滚；点文件名 = 用本文件的只读查看器打开（打开后按 §7 整段复制）；
 * 再点一下收起。没有产物时**不渲染这里**，调用方退回原来的 `CopyBox`（§8.2／判据 B9）。
 *
 * 与查看器同一本账：只读，不写盘（判据 B6 扫整文件）；复制只走
 * [copyArtifactToClipboard] 那一条 §7 路径。
 */
@Composable
internal fun BaopuArtifactTopBar(
    root: File,
    artifacts: List<String>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope { CoroutineCrashHandler }
    var note by remember { mutableStateOf<String?>(null) }
    var busyRel by remember { mutableStateOf<String?>(null) }
    // 「第一个」由纯函数说一不二（B11：折叠态那个小号按钮只复制第一个产物全文）
    val first = firstArtifactForCopy(artifacts)
    val firstName = first?.let { artifactFileName(it) } ?: ""
    // 摆几片＋还有几个：与消息列表那行同一个 artifactChipPlan（8 个上限两处一致）
    val plan = artifactChipPlan(artifacts)
    // 每行的大小只在**展开**时才 stat（折叠态一次盘都不碰）；仍然走 IO 线程
    val entries by produceState(
        initialValue = emptyList<ArtifactEntry>(),
        key1 = root,
        key2 = artifacts,
        key3 = expanded,
    ) {
        value = if (expanded) withContext(Dispatchers.IO) { artifactEntries(root, artifacts) } else emptyList()
    }

    // 折叠态按钮与展开态每行按钮走同一段：rel 进、§7 自证过的结果进 note
    fun copyRow(rel: String) {
        busyRel = rel
        note = null
        scope.launch {
            note = copyArtifactToClipboard(context, root, rel)
            busyRel = null
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        tonalElevation = 1.dp,
    ) {
        Column {
            // 折叠行：一行高，不新增行（§8.3）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() }
                    .padding(start = 12.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.baopu_artifact_top_label, artifacts.size),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = firstName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // 「小号」复制按钮：labelSmall ＋ 24dp 高（§8.1 点名不许再用占半行的那种）
                TextButton(
                    onClick = { if (first != null) copyRow(first) },
                    enabled = first != null && busyRel == null,
                    modifier = Modifier.heightIn(min = 24.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text(
                        text = stringResource(
                            if (busyRel != null && busyRel == first) R.string.baopu_artifact_loading
                            else R.string.copy_text,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            if (expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 2.dp),
                ) {
                    plan.shown.forEach { rel ->
                        val e = entries.firstOrNull { it.relPath == rel }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, end = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = e?.name ?: artifactFileName(rel),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                // 点文件名 = 用 B 段那个只读查看器打开（§8.1 第 3 条）
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onOpen(rel) },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (e == null) ""
                                else pluralStringResource(R.plurals.baopu_artifact_bytes, if (e.fileBytes == 1L) 1 else 2, e.fileBytes),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                            TextButton(
                                onClick = { copyRow(rel) },
                                enabled = busyRel == null,
                                modifier = Modifier.heightIn(min = 24.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            ) {
                                Text(
                                    text = stringResource(
                                        if (busyRel == rel) R.string.baopu_artifact_loading
                                        else R.string.copy_text,
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                    // 超过 8 个：与消息列表那行同一句「还有 N 个」（B5 口径两处一致）
                    if (plan.more > 0) {
                        Text(
                            text = pluralStringResource(R.plurals.baopu_artifact_more, plan.more, plan.more),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp, bottom = 2.dp),
                        )
                    }
                }
                // 复制结果留在原地（不弹 Toast：失败那句要看得见，同查看器的口径）
                note?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp, bottom = 2.dp),
                    )
                }
            }
        }
    }
}
