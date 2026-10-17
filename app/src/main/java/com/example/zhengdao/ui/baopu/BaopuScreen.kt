// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第九批 §4.3（BaopuScreen：真页面根——会话区＋消息流＋输入区＋状态条
// ＋中断提示；提示词小框＋右上角复制＝复用 ui/baopu/CopyBox.kt）+ §4.4
// （MainActivity 的 TabIndex.BAOPU 支由占位页改指本页）。
// 刀 1 补丁：页顶加「换通道」入口（弹 BaopuChannelSheet）；保存后重建控制器
// （控制器构造期持引擎，而 provider 是引擎的构造期注入 ⇒ 换通道必须换引擎）。
// 刀 2：页顶补「章节抽取」「备份原件」两个入口 ＋ 防丢顶条（超 N 天没导出就提醒、
// 一键导出）＋ 收料提问卡 ＋ 提炼待过目卡与自省便签 ＋ 长按图标快捷方式落地。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.ui.theme.zhengdaoErrorText
import com.example.zhengdao.baopu.BaopuRuntime
import com.example.zhengdao.baopu.ProviderPrefs
import com.example.zhengdao.baopu.agent.RunState
import com.example.zhengdao.baopu.library.BackupRules
import kotlinx.coroutines.delay

/**
 * 抱朴页（刀 1 · B 段）：占位页 [BaopuRebuilding] 的真身。
 *
 * 结构（上→下）：入口行（换通道／章节抽取／备份原件）→ 防丢顶条 → 会话头（状态/中断/续跑）
 * → 自省便签 → 提炼待过目卡 → 记忆候选卡 → 收料提问卡 → 资料库结果一行 → 提示词小框（可复制）
 * → 消息流 → 输入区。
 * 「提示词小框」的落地：展示**最近一条抱朴回复**的可复制小框（CopyBox）——
 * 用户把回复复制到别处用，是这一行设计的实际用途场景（报告里写明解释）。
 */
@Composable
fun BaopuScreen(onExit: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // 换通道 = 换引擎：控制器构造期持引擎（BaopuChatController），改 epoch 让它重建。
    var channelEpoch by remember { mutableIntStateOf(0) }
    val controller = remember(channelEpoch) { BaopuChatController(context) }
    var showChannel by remember { mutableStateOf(false) }
    var showBackup by remember { mutableStateOf(false) }
    var showExtract by remember { mutableStateOf(false) }

    // 引擎事件驱动刷新已由 controller 内部做；这里补一个前台可见时的兜底刷新
    // （进程被杀后重进：库里有中断标记，controller 构造时 refresh 已读一次）。
    LaunchedEffect(channelEpoch) { controller.refresh() }

    // 长按图标快捷方式（D 段第 1 条）：MainActivity 把请求塞进 BaopuIntents，
    // 这一页组合时消费——冷启动（捷径直接拉起 App）与热启动（App 已在栈里）都通。
    val shortcutBackup by BaopuIntents.backupRequestedState
    LaunchedEffect(shortcutBackup) {
        if (shortcutBackup) {
            showBackup = true
            BaopuIntents.consumeBackup()
        }
    }

    // 确认等待的**前台计时**（刀 3 A 段）：页面不可见时上报引擎——超时 15 分钟
    // 只算前台时间（派工单 §3.2/§2 补录），用户放下手机去干别的，回来卡还在。
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> controller.onScreenPause()
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> controller.onScreenResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val state = controller.engineState.value
    val lastAssistant = controller.messages.lastOrNull { it.role != "user" }
        ?.parts?.lastOrNull { it.kind == "text" }?.text.orEmpty()

    // 资料库结果一句话：显示几秒就退（不占地方；要再看有导出记录在库里）
    val libraryNote = controller.libraryNote
    LaunchedEffect(libraryNote) {
        if (libraryNote != null) {
            delay(8_000)
            controller.clearLibraryNote()
        }
    }
    val pendingNote = controller.pendingNote
    LaunchedEffect(pendingNote) {
        if (pendingNote != null) {
            delay(8_000)
            controller.clearPendingNote()
        }
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { showExtract = true }) {
                    Text(stringResource(R.string.baopu_extract_title))
                }
                TextButton(onClick = { showBackup = true }) {
                    Text(stringResource(R.string.baopu_backup_title))
                }
                TextButton(onClick = { showChannel = true }) {
                    Text(stringResource(R.string.baopu_channel_button))
                }
            }
            // 防丢顶条（D 段第 3 条）：原件超 N 天没导出就提醒 ＋ 一键导出。
            // 判据是纯函数（BackupRules.isStale，N 可配）；从没导出过且库里有件 ⇒ 立刻算超期。
            val stats = controller.backupStats
            val nowMs = remember(stats) { System.currentTimeMillis() }
            if (BackupRules.isStale(stats.lastExportAt, nowMs, stats.total)) {
                val days = BackupRules.daysSinceExport(stats.lastExportAt, nowMs)
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (days == null) {
                                stringResource(R.string.baopu_backup_stale_never)
                            } else {
                                stringResource(R.string.baopu_backup_stale_days, days)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = { controller.exportOriginals(false) },
                            enabled = !controller.exportBusy,
                        ) {
                            Text(
                                stringResource(
                                    if (controller.exportBusy) R.string.baopu_export_running else R.string.baopu_backup_go,
                                ),
                            )
                        }
                    }
                }
            }
            BaopuSessionBar(
                state = state,
                step = controller.engineStep.value,
                interruptedStep = controller.interruptedStep,
                notice = controller.engineNotice.value,
                hasSession = controller.sessionId != null,
                onResume = { controller.resume() },
                onNewSession = { controller.newSession() },
            )
            // 自省便签（C 段：恰好一条；新会话清空）
            controller.introspectionNote?.let { note ->
                BaopuIntrospectionNote(text = note)
            }
            // 提炼待过目卡（C 段：PENDING 未落盘，用户过目后才写盘）
            BaopuPendingMemoryCard(
                pending = controller.pendingMemories,
                onConfirm = { controller.confirmPendingMemory(it) },
                onForget = { controller.forgetPendingMemory(it) },
            )
            controller.pendingNote?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = zhengdaoErrorText(), // P2-11：error 色 3.55:1 不达正文 AA 4.5:1
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            BaopuMemoryCard(
                candidates = controller.memoryCandidates,
                onConfirm = { controller.confirmMemory(it) },
                onForget = { controller.forgetMemory(it) },
            )
            // 收料提问卡（A 段：扫投递 → 一次 ask_user；形状判据全在 AskUserCardRules）
            controller.askCard?.let { card ->
                Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    BaopuAskUserCard(
                        card = card,
                        onPick = { controller.answerAskCard(it) },
                        onFreeText = { controller.answerAskCardFree(it) },
                    )
                }
            }
            // 文件改动确认卡（刀 3 A 段：要动文件 → 出卡 → 批准/拒绝）
            controller.confirmCard?.let { card ->
                Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    BaopuConfirmCard(
                        request = card,
                        onApprove = { controller.approveConfirm() },
                        onReject = { controller.rejectConfirm() },
                    )
                }
            }
            libraryNote?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            // 提示词小框（右上角复制）：显示最近一条抱朴回复
            if (lastAssistant.isNotBlank()) {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    CopyBox(
                        text = lastAssistant,
                        label = stringResource(R.string.baopu_role_assistant),
                        maxLines = 4,
                    )
                }
            }
            Box(Modifier.weight(1f)) {
                // LazyColumn 自带滚动（不能再套 verticalScroll——嵌套滚动会崩）
                BaopuMessageList(
                    messages = controller.messages,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            BaopuComposer(
                busy = controller.busy,
                onSend = { controller.send(it) },
                onStop = { controller.stop() },
            )
            if (showChannel) {
                val (customBase, customModel) = ProviderPrefs.storedCustom(context)
                BaopuChannelSheet(
                    currentChannelId = ProviderPrefs.currentChannelId(context),
                    storedKeyOf = { id -> ProviderPrefs.storedKey(context, id).orEmpty() },
                    customBaseUrl = customBase,
                    customModel = customModel,
                    onDismiss = { showChannel = false },
                    onSave = { id, key, base, model ->
                        BaopuRuntime.switchChannel(context, id, key, base, model)
                        showChannel = false
                        channelEpoch++ // 换引擎（provider 是构造期注入；旧引擎线程闲着无害）
                    },
                )
            }
            if (showBackup) {
                BaopuBackupDialog(
                    lastExportAt = controller.backupStats.lastExportAt,
                    unexportedCount = controller.backupStats.unexported,
                    busy = controller.exportBusy,
                    onExportAll = { controller.exportOriginals(false) },
                    onExportText = { controller.exportOriginals(true) },
                    onDismiss = { showBackup = false },
                )
            }
            if (showExtract) {
                BaopuExtractSheet(onDismiss = { showExtract = false })
            }
        }
    }
}
