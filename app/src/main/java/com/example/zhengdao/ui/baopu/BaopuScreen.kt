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
import android.widget.Toast
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalResources
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
import com.example.zhengdao.baopu.BaopuWorkspace
import com.example.zhengdao.baopu.TopBarSlot
import com.example.zhengdao.baopu.UsageCopy
import com.example.zhengdao.baopu.shouldShowUsageNotice
import com.example.zhengdao.baopu.toggleTopBarExpanded
import com.example.zhengdao.baopu.topBarSlot
import com.example.zhengdao.baopu.usageNoticeBody
import com.example.zhengdao.baopu.kernel.KernelAudit
import com.example.zhengdao.rootfs.RunLog
import com.example.zhengdao.ui.WorkspaceFolderPicker
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
    val res = LocalResources.current
    // 换通道 = 换引擎：控制器构造期持引擎（BaopuChatController），改 epoch 让它重建。
    var channelEpoch by remember { mutableIntStateOf(0) }
    val controller = remember(channelEpoch) { BaopuChatController(context) }
    var showChannel by remember { mutableStateOf(false) }
    var showBackup by remember { mutableStateOf(false) }
    var showExtract by remember { mutableStateOf(false) }
    // 第四十批：长按用户消息 ⇒ 小菜单（编辑这条 / 复制）⇒ 编辑框。
    var menuMsg by remember { mutableStateOf<BaopuUiMessage?>(null) }
    var editMsg by remember { mutableStateOf<Pair<Long, String>?>(null) }
    // 第十八批 A 段：抽屉「抱朴设置」直达抱朴自己的设置页（页内全屏浮层，
    // 与章节抽取／备份／换通道同一模式）——不再去「终端与资料」大杂烩页。
    var showSettings by remember { mutableStateOf(false) }
    // 朗读（2026-10-18）：系统 TTS 跟着页面生命走 —— 离开抱朴页就 shutdown，不许后台接着念。
    // 引擎是异步就绪的，首次点击由 BaopuSpeaker 自己排队（见该类 KDoc 第 1 条）。
    val speaker = remember { BaopuSpeaker(context) }
    DisposableEffect(Unit) { onDispose { speaker.release() } }
    // 第二十批 §2.1.1：历史会话浮层（同一模式；抽屉「历史会话」进）。
    var showSessionLibrary by remember { mutableStateOf(false) }
    // 「在这个工作区开新会话」：当前工作区直接开；别的文件夹要先问人（切工作区是全局动作）。
    var runHereNode by remember { mutableStateOf<DrawerWorkspaceNode?>(null) }
    // 「工作区」标题右侧的 ＋：选一个文件夹加进来（落点与设置页那张卡同一套选择器）。
    var addWorkspaceOpen by remember { mutableStateOf(false) }
    // 长按会话行 ⇒ 删除确认（设置页那套平铺列表已删，删除只在这里）
    var deleteRow by remember { mutableStateOf<DrawerSessionRow?>(null) }
    // 2026-10-19：确认框要写清工作区名（行上不带分组键，W4）
    var deleteRowWs by remember { mutableStateOf("") }
    // 刀 3：抽屉。宽度按屏宽算（窄屏 80%，宽屏封顶 300dp），点遮罩／返回键都能关。
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope { CoroutineCrashHandler }
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val drawerWidth = if (screenWidthDp * 0.8f > 300f) 300.dp else (screenWidthDp * 0.8f).dp
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
    // 设置浮层开着的返回键语义：先关浮层（注册在抽屉的后面 ⇒ 两者同时可用时它优先）。
    BackHandler(enabled = showSettings) { showSettings = false }
    // 会话库浮层同口径（第二十批）。
    BackHandler(enabled = showSessionLibrary) { showSessionLibrary = false }

    // ── 第二十九批：抽屉顶部＝工作区树 ────────────────────────────────────
    // 判据（分组、末级名、相对时间、高亮）全在 BaopuSessionTree 的纯函数里，这里只做四件事：
    // ① 开抽屉那一刻读一次会话库（与会话库浮层同一个 loadSessionLibrary，不另开口径）；
    // ② 把当前工作区（BaopuWorkspace.hostDir）与它的状态（正常／私有／未授权）交进纯函数；
    // ③ 点文件夹行的两个落点接上：临时提示（Toast，飘完就没＝A5 的"不许常驻"）＋设置页「工作区」卡；
    // ④ 点会话行走 controller.requestOpenSession——撞上单活跃门由下面那张确认卡问人（不许自动停）。
    var drawerTick by remember { mutableStateOf(0) }
    LaunchedEffect(drawerState.isOpen) { if (drawerState.isOpen) drawerTick++ }
    val wsPrivateLabel = stringResource(R.string.baopu_drawer_ws_private)
    val wsUnauthorizedLabel = stringResource(R.string.baopu_drawer_ws_unauthorized)
    val wsUnknownLabel = stringResource(R.string.baopu_drawer_ws_unknown)
    val unnamedSessionLabel = stringResource(R.string.baopu_session_unnamed)
    val timeTemplates = RelativeTemplates(
        justNow = stringResource(R.string.baopu_drawer_time_just_now),
        minutes = stringResource(R.string.baopu_drawer_time_minutes),
        hours = stringResource(R.string.baopu_drawer_time_hours),
        days = stringResource(R.string.baopu_drawer_time_days),
        months = stringResource(R.string.baopu_drawer_time_months),
    )
    // drawerTick==0 ⇒ 一颗都没读：没开过抽屉就不该为它跑一条 SELECT。
    val drawerNodes = remember(
        drawerTick,
        controller.sessionId,
        timeTemplates,
        wsPrivateLabel,
        wsUnauthorizedLabel,
        wsUnknownLabel,
        unnamedSessionLabel,
    ) {
        if (drawerTick == 0) {
            emptyList()
        } else {
            val host = BaopuWorkspace.hostDir(context)
            val wsState = workspaceStateOf(
                BaopuWorkspace.storageGranted(context),
                BaopuWorkspace.isPrivate(context),
            )
            buildWorkspaceTree(
                entries = controller.loadSessionLibrary(),
                currentHostPath = host.absolutePath,
                currentLabel = workspaceFolderLabel(
                    host.absolutePath,
                    wsState,
                    wsPrivateLabel,
                    wsUnauthorizedLabel,
                ),
                currentState = wsState,
                unknownLabel = wsUnknownLabel,
                unknownPath = BaopuWorkspace.defaultDir().absolutePath,
                currentSessionId = controller.sessionId,
                now = System.currentTimeMillis(),
                templates = timeTemplates,
                fallbackTitle = unnamedSessionLabel,
            )
        }
    }
    val showWorkspacePath: (String) -> Unit = { path ->
        Toast.makeText(context, path, Toast.LENGTH_SHORT).show()
    }

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
    // ── 第三十八批 §8（用户 2026-10-19 追加）：顶栏那一条显示什么 ──────────────
    // 用户原话：「把那个复制功能做小点，这样点击展开就能显示更多的那些产物的内容了，
    // 也不用一定要去左侧栏翻产物区里的东西了。」
    // **这里只接线**：「有产物出产物条、没产物出回复」这条判断在纯函数 `topBarSlot`
    // 里（判据 B9 钉着，负例「无产物也硬出产物条」打的就是它）。
    // 产物源与消息列表那行**同一份**：controller.roundArtifacts（引擎筛过的文本白名单）。
    val topArtifacts = controller.roundArtifacts
    // 展开态键在产物列表上：换一轮（或同轮新增一个产物）就收回折叠，
    // 免得用户翻过的东西在下一轮还压着下面的内容（判据 B10）。
    var topBarExpanded by remember(topArtifacts) { mutableStateOf(false) }

    // 资料库结果一句话：显示几秒就退（不占地方；要再看有导出记录在库里）
    val libraryNote = controller.libraryNote
    LaunchedEffect(libraryNote) {
        if (libraryNote != null) {
            delay(8_000)
            controller.clearLibraryNote()
        }
    }
    // 第四十九批 · 任务 B：pendingNote 的 8 秒自清**删掉了**。
    // 它装的是错误原因（BaopuChatController.kt:984 = r.reason），不是进度 —— 错误凭什么叫到 8 秒就自己走。
    // libraryNote ／ skillNote 那两个 LaunchedEffect 一行不动（派工单 §3：它们既有「记下来中…／收料处理中…／导出中…」
    // 这类进度，也有失败，没分类之前把自清去掉会把进度钉死在屏上）。
    val skillNote = controller.skillNote
    LaunchedEffect(skillNote) {
        if (skillNote != null) {
            delay(8_000)
            controller.clearSkillNote()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(drawerWidth)) {
                BaopuDrawer(
                    busy = controller.busy,
                    // E-163：抽屉内容常驻组合，回收站个数得靠「打开」这个信号重数一次。
                    drawerOpen = drawerState.isOpen,
                    nodes = drawerNodes,
                    onNewSession = {
                        scope.launch { drawerState.close() }
                        controller.newSession()
                    },
                    // 抽屉里**不再有**「历史会话」行（会话已归到各工作区下面）；
                    // 平铺列表（含删除）改成低频入口：抱朴设置 → 会话记录。
                    // 点会话行：先关抽屉再交给控制器（控制器决定"直接切"还是"出确认卡问人"）。
                    onOpenSession = { sid ->
                        scope.launch { drawerState.close() }
                        controller.requestOpenSession(sid)
                    },
                    // 长按＝删除：先关抽屉再问人，确认才真删（走修好 compression 的 deleteSession）
                    // 2026-10-19：回收站里恢复/彻底删除会话后，让会话库重读一次（否则列表像没变）。
                    onSessionLibraryChanged = { drawerTick++ },
                    onSessionLongPress = { row, ws ->
                        scope.launch { drawerState.close() }
                        deleteRow = row
                        deleteRowWs = ws
                    },
                    // 文件夹行右侧的「圆里加号」＝在这个工作区里干活（开新会话）。
                    onWorkspaceRunHere = { node ->
                        scope.launch { drawerState.close() }
                        if (node.isCurrent) {
                            controller.newSession()
                        } else {
                            runHereNode = node   // 别的文件夹：先问人，别默默把人挪到别处
                        }
                    },
                    // 标题右侧的 ＋＝选文件夹加进工作区。
                    onAddWorkspace = {
                        scope.launch { drawerState.close() }
                        addWorkspaceOpen = true
                    },
                    // 点文件夹行＝临时提示完整路径 ＋ 落点抱朴设置页（那张「工作区」卡在这一页里）。
                    onWorkspaceTap = { path ->
                        scope.launch { drawerState.close() }
                        showWorkspacePath(path)
                        showSettings = true
                    },
                    // 长按文件夹行＝只看路径，不跳页（派工单 §7 的「长按提示」）。
                    onWorkspaceHold = showWorkspacePath,
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
                    // 预览这条回复（刀 4-B 的零件函数在这里第一次有调用方）。
                    // 没有可预览的回复 ⇒ 明说「还没有」，不装作放好了。
                    onPreviewLastReply = {
                        scope.launch { drawerState.close() }
                        val reply = lastAssistantReply(controller.messages)
                        val text = if (reply == null) {
                            PreviewNotice.NO_REPLY_YET
                        } else {
                            previewToastText(previewLastReply(reply))
                        }
                        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
                    },
                    // 清空：张数由 PreviewNotice.cleared 说话（0 张也要说，R7）。
                    onPreviewClear = {
                        scope.launch { drawerState.close() }
                        val n = clearPreviewCache()
                        Toast.makeText(context, PreviewNotice.cleared(n), Toast.LENGTH_SHORT).show()
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
                    // 「这次学到 / 已压缩」竖向跑马灯（2026-10-18 用户：位置在「待命中」和「＋」
                    // 那一栏里）——顶栏中间剩余宽度全给它，只占一行高。
                    val noticeItems = buildList {
                        controller.introspectionNote?.takeIf { it.isNotBlank() }?.let {
                            add(NoticeItem(stringResource(R.string.baopu_introspection_label), it))
                        }
                        controller.compressionNote?.let {
                            add(
                                NoticeItem(
                                    stringResource(R.string.baopu_notice_compressed_label),
                                    stringResource(R.string.baopu_compressed_bar, it.count, it.summary.length) +
                                        "\n" + it.summary,
                                ),
                            )
                        }
                        // 2026-10-19：玄览正在干什么（用户：别只留工具卡在消息流里）
                        controller.browserActivity?.let {
                            add(NoticeItem(stringResource(R.string.baopu_browser_notice_label), it))
                        }
                        // 第三十八批 A 段：本轮用量。跑马灯滚的是标题（「本轮用量」），
                        // 点开 DropdownMenu 才是明细：第一行累计、往后**每步一行**
                        // （`第 3 步 · 入 12.3k · 出 2.1k · 4.2s`——排布照 run_log 那套：
                        // 步骤／入／出／耗时，用户 2026-10-19 原话「按那个展开显示就好了」）。
                        //
                        // **一条读数都没收到就不加这一条**（判据 A3：不许显示 0 假装知道）。
                        // 源头在引擎 `persistUsage`：usage 为 null 或五列全空 ⇒ 根本不回调。
                        //
                        // 步号那一项用 `context.getString`：`UsageCopy.stepLabel` 是普通
                        // lambda（纯函数文件里不能出现 @Composable），在 lambda 里调
                        // `stringResource` 编译就过不去。
                        val usage = controller.roundUsage
                        if (shouldShowUsageNotice(usage)) {
                            val usageCopy = UsageCopy(
                                stepLabel = { step -> res.getString(R.string.baopu_usage_step, step) },
                                inLabel = stringResource(R.string.baopu_usage_in),
                                outLabel = stringResource(R.string.baopu_usage_out),
                                cacheHitLabel = stringResource(R.string.baopu_usage_cache_hit),
                            )
                            add(
                                NoticeItem(
                                    stringResource(R.string.baopu_usage_label),
                                    usageNoticeBody(usage, usageCopy),
                                ),
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    BaopuNoticeTicker(items = noticeItems, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(4.dp))
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
            // 自省便签（C 段：恰好一条）。任务 B：改成仓库里早就写好、却一直零调用的 BaopuIntrospectionNote
            // （Surface ＋ 标题 ＋ ×）。**不自动消失，点整条（含 ×）才清** —— 内边距由组件自己给，这里不再传 modifier。
            controller.pendingNote?.let { note ->
                BaopuIntrospectionNote(
                    text = note,
                    onDismiss = { controller.clearPendingNote() },
                    // 2026-10-19：错误便签有自己的标题与错误色；压紧凑，别比它替换掉的单行更高
                    label = stringResource(R.string.memory_write_failed_label),
                    isError = true,
                    compact = true,
                )
            }
            // 技能动作一句话（第十九批：上限拒绝／写盘失败；显示几秒就清）
            controller.skillNote?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            libraryNote?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            // 顶栏那一条（2026-10-18 定稿「固定到现在这个位置，不用跟着正文框跑」＋
            // 38 批 §8 追加「这一栏改成产物」）：位置不变、折叠态仍是一行高（§8.3）。
            // 二选一的判断全在 topBarSlot 里，这一层不写 isEmpty()。
            when (val slot = topBarSlot(topArtifacts, lastAssistant, topBarExpanded)) {
                is TopBarSlot.Artifacts -> Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    BaopuArtifactTopBar(
                        // 查看器用的根目录与下面 `BaopuArtifactViewer` 同一取法（不另开一路）
                        root = BaopuWorkspace.hostDir(context),
                        artifacts = topArtifacts,
                        expanded = slot.expanded,
                        onToggle = { topBarExpanded = toggleTopBarExpanded(topBarExpanded) },
                        // 点文件名 = 打开 38 批 B 段那个只读查看器（两处共用同一个）
                        onOpen = { rel -> controller.openArtifact(rel) },
                    )
                }
                // 没有产物 ⇒ 退化为原 CopyBox（判据 B9 的另一半；回复为空时整条不出）
                is TopBarSlot.ReplyPreview -> if (slot.text.isNotBlank()) {
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        CopyBox(
                            text = slot.text,
                            label = stringResource(R.string.baopu_role_assistant),
                            collapsible = true,
                        )
                    }
                }
            }
            // （跑马灯已移进顶栏那一行，见上）
            // ── 贴底跟随＋「回到最新」浮标（第十八批-B §2.2）──────────────────
            // 滚动状态归屏幕层：判据是纯函数（BaopuChatScroll.shouldFollow /
            // shouldShowJump / jumpHasNewDot，JVM 直测），这里只接线。
            // 跟随态 autoScroll 只在**用户拖动松手**时按当时是否贴底刷新——
            // 程序化滚动不产生拖动交互 ⇒ 不会把跟随态误清（负例 2 的靶心）。
            val listState = rememberLazyListState()
            var autoScroll by remember { mutableStateOf(true) }
            var hasNewContent by remember { mutableStateOf(false) }
            // 2026-10-18 真机：输入框被键盘顶上去了，但**正文没有跟着**——根布局让出键盘高度后
            // 列表视口变矮、滚动位置不变 ⇒ 最下面那条被挡在键盘后面。
            // 这里盯视口高度：一变矮（键盘弹出/旋转）且用户本来就贴着底部，就把列表重新贴回底部。
            // 不依赖 WindowInsets.ime（该版本 Compose 里取不到这个扩展属性）。
            val viewportH = listState.layoutInfo.viewportSize.height
            LaunchedEffect(viewportH) {
                if (autoScroll) {
                    val last = listState.layoutInfo.totalItemsCount - 1
                    if (last >= 0) listState.scrollToItem(last)
                }
            }
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
                skillProposal = controller.pendingSkillProposals.isNotEmpty(),
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
                    onSpeak = { speaker.speak(it) },
                    // 第四十批：长按用户那条消息 ⇒ 菜单（编辑这条 / 复制）
                    onUserLongPress = { msg -> if (!controller.busy) menuMsg = msg },
                    // 第三十八批 B 段：本轮写出来的文本产物（json/txt/md…）。
                    // 数据源是引擎那份只读视图（写成功才进来），点片开只读查看器。
                    artifacts = controller.roundArtifacts,
                    onOpenArtifact = { rel -> controller.openArtifact(rel) },
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

                            // 技能索引提案卡（第十九批 §2.2：采纳才写人设，丢弃只移除）
                            BottomCardKind.SKILL -> BaopuSkillProposalCard(
                                proposals = controller.pendingSkillProposals,
                                onConfirm = { controller.confirmSkillProposal(it) },
                                onDiscard = { controller.discardSkillProposal(it) },
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
            // ask_user 的选项卡：就在输入栏上方（用户 2026-10-19 点名位置）
            controller.pendingChoice?.let { req ->
                BaopuChoiceCard(request = req, onPick = { option -> controller.answerChoice(option.id) })
                Spacer(Modifier.height(8.dp))
            }
            // 「这次学到」提案卡：同一区域（输入栏上方），收尾才出、点采纳才落盘
            controller.learnProposals?.let { list ->
                BaopuLearnCard(
                    proposals = list,
                    existingNames = controller.learnExistingNames,
                    onAdopt = { id -> controller.adoptLearn(id) },
                    onDismiss = { controller.dismissLearn() },
                )
                Spacer(Modifier.height(8.dp))
            }
            // 第四十批：继续说完 / 重试（只在被截断、跑挂时出现；平时不占屏幕）。
            val tailSet = tailActions(
                busy = controller.busy,
                truncated = controller.tailTruncated,
                aborted = controller.engineState.value == RunState.ABORTED,
            )
            if (tailSet.isNotEmpty()) {
                BaopuTailActions(
                    actions = tailSet,
                    onContinue = { controller.continueTruncated() },
                    onRetry = { controller.retryLastTurn() },
                )
            }
            // 第四十批：长按用户那条消息 ⇒ 小菜单（编辑这条 / 复制）⇒ 编辑框
            menuMsg?.let { msg ->
                BaopuUserMessageMenu(
                    onEdit = {
                        controller.userMessageText(msg.id)?.let { editMsg = msg.id to it }
                        menuMsg = null
                    },
                    onCopy = {
                        controller.userMessageText(msg.id)?.let {
                            com.example.zhengdao.util.copyPlainText(context, "baopu-user-message", it)
                        }
                        menuMsg = null
                    },
                    onDismiss = { menuMsg = null },
                )
            }
            editMsg?.let { (id, seed) ->
                BaopuEditMessageDialog(
                    initial = seed,
                    onDismiss = { editMsg = null },
                    onSave = { text ->
                        controller.editUser(id, text)
                        editMsg = null
                    },
                )
            }
            BaopuComposer(
                busy = controller.busy,
                // 第二十四批 B 段：附件随文本一起交出去（图片要落 image part）
                onSend = { text, attachments -> controller.send(text, attachments) },
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
                BaopuSettingsScreen(
                    onDismiss = { showSettings = false },
                    onOpenSessionLibrary = { showSettings = false; showSessionLibrary = true },
                )
            }
            if (showSessionLibrary) {
                BaopuSessionLibraryScreen(
                    controller = controller,
                    onDismiss = { showSessionLibrary = false },
                )
            }
            // 第三十八批 B 段：产物只读查看器（点产物片才开，**不自动开**）。
            // Dialog 本体在新文件 BaopuArtifactViewer.kt：`BaopuMessageList.kt` 有门禁
            // 钉着「整文件只应有 1 处 Dialog(」（BaopuImageGridTest G5），别处的浮层
            // 也都各自守着自己的文件——查看器同样一处。
            controller.viewingArtifact?.let { rel ->
                BaopuArtifactViewer(
                    root = BaopuWorkspace.hostDir(context),
                    relPath = rel,
                    onDismiss = { controller.closeArtifact() },
                )
            }
            // ── 「在这个工作区开新会话」（文件夹行右侧的圆里加号）──────────────
            // 切工作区是**全局**动作（全局只有一个 Workspace），所以别的文件夹必须先问人：
            // 默默把用户挪到另一个目录、还在那里发一条消息，是最不该有的惊喜。
            deleteRow?.let { row ->
                AlertDialog(
                    onDismissRequest = { deleteRow = null },
                    title = { Text(stringResource(R.string.baopu_delete_session_title)) },
                    text = { Text(stringResource(R.string.baopu_delete_session_body_ws, row.title, deleteRowWs, row.timeText)) },
                    confirmButton = {
                        TextButton(onClick = {
                            val id = row.id
                            deleteRow = null
                            if (!controller.deleteSession(id)) {
                                Toast.makeText(context, context.getString(R.string.baopu_delete_session_failed), Toast.LENGTH_SHORT).show()
                            }
                        }) { Text(stringResource(R.string.baopu_delete_session_confirm)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { deleteRow = null }) { Text(stringResource(R.string.baopu_delete_session_cancel)) }
                    },
                )
            }
            runHereNode?.let { node ->
                AlertDialog(
                    onDismissRequest = { runHereNode = null },
                    title = { Text(stringResource(R.string.baopu_switch_ws_title)) },
                    text = { Text(stringResource(R.string.baopu_switch_ws_body, node.label)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                runHereNode = null
                                val before = BaopuWorkspace.hostDir(context).absolutePath
                                // 默认工作区那一行不是「自定义路径」，走 setDefault（mode=default）；
                                // 其余文件夹走 setCustom。两条都要留痕。
                                val switched = if (node.unknown) {
                                    BaopuWorkspace.setDefault(context)
                                    true
                                } else {
                                    BaopuWorkspace.setCustom(context, node.fullPath)
                                }
                                if (switched) {
                                    val after = BaopuWorkspace.hostDir(context).absolutePath
                                    // 内核项（工作区）变更要留痕——与设置页同一条规则（26-B）
                                    if (after != before) RunLog.log(KernelAudit.workspace(after, before))
                                    controller.newSession()
                                } else {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.baopu_switch_ws_failed),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                        ) { Text(stringResource(R.string.baopu_switch_ws_confirm)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { runHereNode = null }) {
                            Text(stringResource(R.string.baopu_switch_ws_cancel))
                        }
                    },
                )
            }
            // ── 「工作区」标题右侧的 ＋：选一个文件夹加进工作区 ──────────────
            if (addWorkspaceOpen) {
                WorkspaceFolderPicker(
                    onDismiss = { addWorkspaceOpen = false },
                    onPick = { path ->
                        addWorkspaceOpen = false
                        val before = BaopuWorkspace.hostDir(context).absolutePath
                        if (BaopuWorkspace.setCustom(context, path)) {
                            val after = BaopuWorkspace.hostDir(context).absolutePath
                            if (after != before) RunLog.log(KernelAudit.workspace(after, before))
                            controller.newSession()
                        }
                    },
                )
            }
            // ── 单活跃门的确认卡（第二十九批 B 段）────────────────────────
            // 另一个会话在跑时点它的会话行 ⇒ 只出这张卡，**绝不停任何东西**：
            // engine.stop() 只在 confirmButton（用户点「停掉并切过来」）里调；
            // 点「取消」或直接关掉（onDismissRequest）都只收卡。
            // 为什么用 AlertDialog 而不是底部卡：底部卡片区（BaopuConfirmCard）跟着引擎的
            // run 走，这张是壳层自己的一次性问答，不占操作卡队列、也不进 actionCardsPresent。
            controller.sessionSwitchAsk?.let { ask ->
                AlertDialog(
                    onDismissRequest = { controller.dismissSessionSwitch() },
                    title = { Text(stringResource(R.string.baopu_switch_title)) },
                    text = {
                        Text(stringResource(R.string.baopu_switch_desc, ask.runningTitle, ask.targetTitle))
                    },
                    confirmButton = {
                        TextButton(onClick = { controller.acceptSessionSwitch() }) {
                            // 「停掉」是破坏性动作 ⇒ 语义红走仓库唯一口径（ErrorTextContrastGuardTest：
                            // 文字不许直接吃 colorScheme.error，3.55:1 不达正文 AA 4.5:1）。
                            Text(
                                stringResource(R.string.baopu_switch_confirm),
                                color = zhengdaoErrorText(),
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { controller.dismissSessionSwitch() }) {
                            Text(stringResource(R.string.common_cancel))
                        }
                    },
                )
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
