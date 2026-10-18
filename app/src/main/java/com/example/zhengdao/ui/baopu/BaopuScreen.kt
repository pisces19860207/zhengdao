// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第九批 §4.3（BaopuScreen：真页面根——会话区＋消息流＋输入区＋状态条
// ＋中断提示；提示词小框＋右上角复制＝复用 ui/baopu/CopyBox.kt）+ §4.4
// （MainActivity 的 TabIndex.BAOPU 支由占位页改指本页）。
// 刀 1 补丁：页顶加「换通道」入口（弹 BaopuChannelSheet）；保存后重建控制器
// （控制器构造期持引擎，而 provider 是引擎的构造期注入 ⇒ 换通道必须换引擎）。
// 刀 3（2026-10-17）：顶条瘦身——「章节抽取／备份原件／换通道」三个文字按钮收进左侧抽屉
// （BaopuDrawer），首屏只留 ☰／状态／＋ 四件。口径来自用户：「不常用的设置归到抱朴的设置抽屉里，
// 满屏幕都是按钮和按键，手机本来屏幕就小」。
package com.example.zhengdao.ui.baopu

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.IconButton
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.keepalive.CoroutineCrashHandler
import com.example.zhengdao.ui.SettingsDataActivity
import com.example.zhengdao.ui.theme.zhengdaoErrorText
import com.example.zhengdao.baopu.BaopuRuntime
import com.example.zhengdao.baopu.ProviderPrefs
import com.example.zhengdao.baopu.agent.RunState
import com.example.zhengdao.baopu.library.BackupRules
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 抽屉入口统一跳「终端与资料」页（工具开关、资料库都在那儿）。 */
private fun openSettings(context: Context) {
    context.startActivity(Intent(context, SettingsDataActivity::class.java))
}

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
    // 第十八批 A 段：抽屉「抱朴设置」直达抱朴自己的设置页（页内全屏浮层，
    // 与章节抽取／备份／换通道同一模式）——不再去「终端与资料」大杂烩页。
    var showSettings by remember { mutableStateOf(false) }

    // 刀 3：抽屉。宽度按屏宽算（窄屏 80%，宽屏封顶 300dp），点遮罩／返回键都能关。
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope { CoroutineCrashHandler }
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val drawerWidth = if (screenWidthDp * 0.8f > 300f) 300.dp else (screenWidthDp * 0.8f).dp
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
    // 设置浮层开着的返回键语义：先关浮层（注册在抽屉的后面 ⇒ 两者同时可用时它优先）。
    BackHandler(enabled = showSettings) { showSettings = false }

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

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(drawerWidth)) {
                BaopuDrawer(
                    busy = controller.busy,
                    onNewSession = {
                        scope.launch { drawerState.close() }
                        controller.newSession()
                    },
                    onLibrary = {
                        scope.launch { drawerState.close() }
                        openSettings(context)
                    },
                    onExtract = {
                        scope.launch { drawerState.close() }
                        showExtract = true
                    },
                    onBackup = {
                        scope.launch { drawerState.close() }
                        showBackup = true
                    },
                    onChannel = {
                        scope.launch { drawerState.close() }
                        showChannel = true
                    },
                    onSettings = {
                        scope.launch { drawerState.close() }
                        showSettings = true
                    },
                )
            }
        },
    ) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // 顶条（刀 3 瘦身）：☰ 开抽屉 · 状态 · ＋新会话。
            // 「章节抽取／备份原件／换通道」已进抽屉；中断提示与续跑仍由 BaopuSessionBar 承担。
            Surface(tonalElevation = 1.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val openLabel = stringResource(R.string.baopu_drawer_open)
                    val newSessionLabel = stringResource(R.string.baopu_new_session)
                    IconButton(
                        onClick = { scope.launch { drawerState.open() } },
                        modifier = Modifier
                            .size(48.dp)
                            .semantics { contentDescription = openLabel },
                    ) {
                        // 本项目没引 material-icons 依赖，用字形够了（☰），不为一个图标多打一个包。
                        Text(text = "☰", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        text = stringResource(stateLabel(state)) +
                            if (controller.engineStep.value > 0) " · ${controller.engineStep.value}" else "",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = { controller.newSession() },
                        enabled = !controller.busy,
                        modifier = Modifier
                            .size(48.dp)
                            .semantics { contentDescription = newSessionLabel },
                    ) {
                        Text(text = "＋", style = MaterialTheme.typography.titleMedium)
                    }
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
                interruptedStep = controller.interruptedStep,
                notice = controller.engineNotice.value,
                hasSession = controller.sessionId != null,
                onResume = { controller.resume() },
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
            // 已压缩细条（第十三批 §4.4：只加一条，不做美化；点开展摘要全文）
            controller.compressionNote?.let { note ->
                Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    BaopuCompressedBar(note = note)
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
                // LazyColumn 自带滚动（不能再套 verticalScroll——嵌套滚动会崩）。
                // 十六批第一件：传入 run_log 活动行/在跑态/钉住态——已完成的历史轮
                // 收成一行摘要，点开看每一步；口径全在 BaopuMessageList 的纯函数里。
                BaopuMessageList(
                    messages = controller.messages,
                    steps = controller.stepRows,
                    running = controller.busy,
                    pinLastRound = controller.actionCardsPresent,
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
            if (showSettings) {
                BaopuSettingsScreen(onDismiss = { showSettings = false })
            }
        }
    }
    }
}

/**
 * 「已压缩」细条（第十三批 §4.4）。
 *
 * 三条规定都在这一小块里：
 * 1. 文案照派工单：`已压缩 M 条早期消息 · 摘要 x 字`（走 `strings.xml`，不拼中文）；
 * 2. **点开才展开**摘要全文（`labelSmall`；[SelectionContainer] 让它可以长按选中复制——
 *    派工单要的「可复制」不靠加按钮，不加按钮就不加按钮）；
 * 3. **不给「取消压缩」「删除原始消息」**（本单不做回滚；原始消息仍在库里，
 *    用户点开这里能回看的只是摘要）。
 */
@Composable
internal fun BaopuCompressedBar(note: BaopuChatController.CompressionNote) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.baopu_compressed_bar, note.count, note.summary.length),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (expanded) {
                SelectionContainer {
                    Text(
                        text = note.summary,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}
