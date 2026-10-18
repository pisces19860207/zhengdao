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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.IconButton
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
 * 结构（上→下）：☰ 顶条 → 防丢顶条 → 会话头（状态/中断/续跑）→ 自省便签 → 待办提示行
 * → 资料库结果一行 → 提示词小框（可复制）→ 消息流（贴底跟随＋「⬇ 回到最新」浮标）→
 * **底部操作卡区（确认卡/问人卡/待过目记忆卡/记忆候选卡，第十八批-B 从列表上方挪来）**
 * → 输入区（composer）。纯展示类（压缩条/资料库提示/提示词小框/自省便签）留在原处；
 * 卡片区与输入框之间还夹着抽屉/各类 Sheet（ModalNavigationDrawer 与弹层）。
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
        // 键盘遮挡（2026-10-18 真机：输入框没被键盘顶上去）：edge-to-edge 下
        // （MainActivity setDecorFitsSystemWindows(false)）窗口不 resize，必须自己消费 ime insets。
        // 放在根 Column：卡片区与输入框一起上移，卡片仍压在输入框上方（J10）。
        Column(Modifier.fillMaxSize().imePadding()) {
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
            // 自省便签（2026-10-18）：搬进消息列表首项（随内容滚走，不占固定可视面积）；
            // 点一下即消失（表示用户知道了）。
            var noteDismissed by remember(controller.introspectionNote) { mutableStateOf(false) }
            controller.pendingNote?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = zhengdaoErrorText(), // P2-11：error 色 3.55:1 不达正文 AA 4.5:1
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            // 已压缩细条已搬进消息列表首项（2026-10-18：不占固定可视面积，随内容滚走）。
            libraryNote?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            // 提示词小框（右上角复制）已搬进消息列表首项（2026-10-18）：默认一行、点开展开，
            // 不再占据固定可视面积。
            // ── 贴底跟随＋「回到最新」浮标（第十八批-B §2.2）──────────────────
            // 滚动状态归屏幕层：判据是纯函数（BaopuChatScroll.shouldFollow /
            // shouldShowJump / jumpHasNewDot，JVM 直测），这里只接线。
            // 跟随态 autoScroll 只在**用户拖动松手**时按当时是否贴底刷新——
            // 程序化滚动不产生拖动交互 ⇒ 不会把跟随态误清（负例 2 的靶心）。
            val listState = rememberLazyListState()
            var autoScroll by remember { mutableStateOf(true) }
            var hasNewContent by remember { mutableStateOf(false) }
            LaunchedEffect(listState) {
                listState.interactionSource.interactions.collect { interaction ->
                    if (interaction is DragInteraction.Stop) {
                        val atBottom = listState.isAtBottom()
                        autoScroll = atBottom
                        if (atBottom) hasNewContent = false
                    }
                }
            }
            // 内容签名变化（新消息/新步骤行/忙态/操作卡增减）⇒ 贴着底部就滚到最新
            // （最新内容紧贴输入框上方）；上翻读历史时不抢，只记「有新内容」。
            val bottomCardsInput = BottomCardsInput(
                confirmCard = controller.confirmCard != null,
                askCard = controller.askCard != null,
                pendingMemory = controller.pendingMemories.isNotEmpty(),
                memoryCandidate = controller.memoryCandidates.isNotEmpty(),
            )
            var expandAllCards by remember { mutableStateOf(false) }
            val cardsView = bottomCards(bottomCardsInput, expandAllCards)
            // 没有可折的卡了 ⇒ 收起「全部展开」态（卡全被处理后自然清）。
            LaunchedEffect(cardsView.hiddenCount) {
                if (cardsView.hiddenCount == 0) expandAllCards = false
            }
            LaunchedEffect(
                controller.messages,
                controller.stepRows,
                controller.busy,
                cardsView.visible.size,
                cardsView.hiddenCount,
            ) {
                val total = listState.layoutInfo.totalItemsCount
                if (total == 0) return@LaunchedEffect
                if (shouldFollow(autoScroll)) {
                    listState.scrollToItem(total - 1)
                    hasNewContent = false
                } else {
                    hasNewContent = true
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
                    listState = listState,
                    lastAssistant = lastAssistant,
                    headerNote = if (noteDismissed) null else controller.introspectionNote,
                    onDismissHeaderNote = { noteDismissed = true },
                    compressedNote = controller.compressionNote,
                    modifier = Modifier.fillMaxSize(),
                )
                // 「⬇ 回到最新」浮标（主单 §2.4.1）：不在底部才出现，有新内容带
                // 小圆点；点一下回底并清标记。文案复用旧键 taiji_back_to_latest
                // （重建抱朴页时丢了的那个「贴心小设计」）。
                if (shouldShowJump(autoScroll, hasNewContent)) {
                    val jumpLabel = stringResource(R.string.taiji_back_to_latest)
                    val jumpDesc = stringResource(R.string.taiji_back_to_latest_desc)
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = CircleShape,
                        tonalElevation = 3.dp,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp)
                            .clickable {
                                val last = listState.layoutInfo.totalItemsCount - 1
                                if (last >= 0) {
                                    scope.launch { listState.animateScrollToItem(last) }
                                }
                                autoScroll = true
                                hasNewContent = false
                            }
                            .semantics { contentDescription = jumpDesc },
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (jumpHasNewDot(autoScroll, hasNewContent)) {
                                Box(
                                    Modifier
                                        .size(6.dp)
                                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                text = jumpLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }
            // ── 底部操作卡区（第十八批-B §2.1）────────────────────────────
            // 「要用户动手」的卡（确认/问人/待过目记忆/记忆候选）紧贴输入框上方：
            // 翻消息不跟着滚走（在 LazyColumn 外的 Column 里）；键盘弹出时随
            // composer 的 imePadding 一起顶上，卡片仍在输入框上方可见（J10）。
            // 多卡只展开最上面一张，其余折成「展开 ▼ ＋ N」一行，点开才全展开（J9）。
            if (cardsView.hasAny) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    cardsView.visible.forEach { kind ->
                        when (kind) {
                            BottomCardKind.CONFIRM -> controller.confirmCard?.let { card ->
                                BaopuConfirmCard(
                                    request = card,
                                    onApprove = { controller.approveConfirm() },
                                    onReject = { controller.rejectConfirm() },
                                )
                            }

                            BottomCardKind.ASK -> controller.askCard?.let { card ->
                                BaopuAskUserCard(
                                    card = card,
                                    onPick = { controller.answerAskCard(it) },
                                    onFreeText = { controller.answerAskCardFree(it) },
                                )
                            }

                            BottomCardKind.PENDING -> BaopuPendingMemoryCard(
                                pending = controller.pendingMemories,
                                onConfirm = { controller.confirmPendingMemory(it) },
                                onForget = { controller.forgetPendingMemory(it) },
                            )

                            BottomCardKind.CANDIDATE -> BaopuMemoryCard(
                                candidates = controller.memoryCandidates,
                                onConfirm = { controller.confirmMemory(it) },
                                onForget = { controller.forgetMemory(it) },
                            )
                        }
                    }
                    if (cardsView.hiddenCount > 0) {
                        // 「还有 N 张」一行：展开箭头复用旧键 common_expand_arrow，
                        // 数字角标是数据不是文案——本单 strings 零新增（§3）。
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { expandAllCards = true }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.common_expand_arrow),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(6.dp))
                            Box(
                                Modifier
                                    .size(16.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = cardsView.hiddenCount.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                        }
                    }
                }
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
 * 列表是否贴底（第十八批-B）：**最后一项可见即算贴底**——露个头也算贴着，
 * 手机上好点（差一像素不算「划上去」就不弹浮标，太神经刀）。
 *
 * 读的是 Compose 布局状态 ⇒ 不是纯函数；纯判据在
 * [com.example.zhengdao.ui.baopu.BaopuChatScroll.shouldFollow]。
 */
private fun LazyListState.isAtBottom(): Boolean {
    // 2026-10-18 真机修正：原口径「最后可见项 index == 总数-1」，而抱朴的「一轮」是
    // 一条很高的 item ⇒ 用户往上翻时最后那项**仍然可见**（只是顶部露一角）⇒ 判定恒为
    // "在底部"，autoScroll 永远 true、「⬇ 回到最新」永远不出现（用户实测：上滑也没有）。
    // 改用 Compose 官方口径：还能不能继续往下滚。
    return !canScrollForward
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
