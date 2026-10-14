// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui

import com.example.zhengdao.ui.util.pressClickable
import com.example.zhengdao.ui.theme.zhengdaoErrorText
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.core.IssueCenter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 卡片右侧操作按钮的**最小**宽度（2026-10-10 UIUX 审计 B2 修）。
 *
 * 原来写死 `Modifier.width(84.dp)`：M3 Button 横向内边距是 24dp×2，84dp 只剩 36dp 放字，
 * 而「重试安装 / 即将支持 / 先装环境」是 4 个汉字（labelLarge 14sp ≈ 56dp）⇒ 装不下就折行。
 * 真机截图 `build/home-cards.png` 里 Hermes 卡片就是「安装⏎中」两行。
 *
 * 改成 `widthIn(min = 108.dp)`：108 − 48 = 60dp 文字区，4 个字（56dp）刚好放得下还有 4dp 余量，
 * 六个按钮在默认字号下**宽度一致**（都取 108）；系统字号调大时按内容长，仍不折行。
 * 若将来按钮文案超过 4 个字，改这一个常量即可。
 */
private const val BUTTON_MIN_WIDTH_DP = 108

/**
 * 系统信息的进程级缓存（2026-10-12 无感化 §5.5）：跨页面重进保留上一次快照，
 * 让「加载中…」只出现在本进程第一次采集时。仅作展示兜底，不参与任何判定。
 */
private var sysInfoProcessCache: SystemInfoProvider.Info? = null

/**
 * 首页：顶部可折叠系统状态卡 + Agent 卡片列表；底部 Tab 的「终端」页由
 * 外层 MainActivity 处理（导航到 TerminalActivity）。
 */
@Composable
fun HomeScreen(
    onOpenTerminal: (autocmd: String?, agentId: String?) -> Unit,
    onOpenSettings: () -> Unit = {},
    // K2（切回 Tab 保留滚动位置）：外部 HomeTabs 注入的 listState。
    // 走外部 state 让"切到太极再切回"时仍记得位置；
    // 不传时退化到内部新建（保持向后兼容，例如设置页内嵌的 HomeScreen 预览）。
    listState: androidx.compose.foundation.lazy.LazyListState =
        androidx.compose.foundation.lazy.rememberLazyListState(),
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // 2026-10-12（走查报告 §五）：错误提示从 Toast 迁 Snackbar——Toast 不能带动作、
    // 与页面内容脱节；Snackbar 挂在本页底部，可读、可跟随、不打断。
    // 定义在使用点之前（Kotlin 局部函数作用域从声明处开始）。
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    fun snack(msg: String) {
        snackbarScope.launch { snackbarHostState.showSnackbar(msg) }
    }
    // 去路执行（#4 走查，2026-10-09）：把 EnvHealth / IssueCenter 的「标识」翻译成「行为」的
    // 唯一一处（体检行与「最近问题」卡共用），免得同一句"去处理"在各处各写一遍、各指一个地方。
    val openStorageGrant: () -> Unit = {
        runCatching {
            val i = android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
            )
            i.data = android.net.Uri.parse("package:" + context.packageName)
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        }.onFailure {
            // 个别 ROM 没有这个页面（或被策略拦下）——不能静默失败，给一句人话 + 手动路径
            snack(context.getString(R.string.home_grant_page_failed))
        }
    }
    // 台账 §B 顺序 11（原 §B-补-42）：两条「去看记录 / 看日志」的通知按钮各自的落点。
    // ①保活留档 →「保活记录」页（再往里一层就是留档原文）；②运行日志 → 帮助页的「运行日志」一节。
    val openKeepaliveLogs: () -> Unit = {
        runCatching {
            context.startActivity(
                android.content.Intent(context, com.example.zhengdao.ui.KeepaliveLogActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            // 极少数 ROM/策略下起不来：退回设置首页，至少不会一点反应都没有
            onOpenSettings()
        }
    }
    val openRunLogs: () -> Unit = {
        runCatching {
            context.startActivity(
                android.content.Intent(context, com.example.zhengdao.ui.SettingsHelpActivity::class.java)
                    .putExtra(com.example.zhengdao.ui.SettingsHelpActivity.EXTRA_FOCUS_LOGS, true)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            onOpenSettings()
        }
    }
    // 反馈通道：用户看不懂、也修不了的失败，至少有个地方说（带日志的话更好定位）
    val openFeedback: () -> Unit = {
        runCatching {
            val i = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://github.com/pisces19860207/zhengdao/issues/new"),
            )
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        }.onFailure {
            snack(context.getString(R.string.home_no_browser))
        }
    }
    // 太极 serve 的重试 / 重启：动作在后台线程跑，结果回主线程汇报（成功撤下问题，失败留在卡上）
    val retryServe: (restart: Boolean, issueId: String) -> Unit = { restart, issueId ->
        Thread {
            val err = runCatching {
                if (restart) com.example.zhengdao.oc.OcManager.stopServe()
                com.example.zhengdao.oc.OcManager.startServe(context)
            }.getOrElse {
                context.getString(
                    R.string.home_start_error,
                    it.message ?: it.javaClass.simpleName,
                )
            }
            android.os.Handler(context.mainLooper).post {
                if (err == null) {
                    IssueCenter.resolve(issueId)
                } else {
                    IssueCenter.report(
                        id = issueId,
                        title = context.getString(R.string.home_taiji_not_up),
                        detail = err,
                        actionLabel = context.getString(R.string.common_retry),
                        actionId = IssueCenter.ACTION_RETRY_SERVE,
                    )
                    snack(err)
                }
            }
        }.start()
    }

    var agents by remember { mutableStateOf(AppState.agents(context)) }
    var statusExpanded by remember { mutableStateOf(false) }
    // 环境体检（P7）：状态卡展开时展示逐项勾叉，红项可定向修复。
    // 2026-10-12 无感化（走查报告 §5.4）：**打开即显上次缓存值**（旧值），
    // 流式刷新逐项就地覆盖——不再「先空白 → 11 行同时蹦出来」（用户原话：有点吓人）。
    var healthChecks by remember { mutableStateOf(LastValues.loadChecks(context)) }
    var healthEpoch by remember { mutableStateOf(0) }
    // 卸载二次确认（P3）：非 null 时弹出确认弹窗
    var uninstallTarget by remember { mutableStateOf<AppState.AgentInfo?>(null) }
    // 2026-10-09（E-059）：已装的 Agent 也要有一条**不卸载**就能重来的路——用户不会为了修一个
    // 坏掉的 Agent 去卸载 App，而「卸载」只删程序、保留 ~/.hermes 这类用户数据目录，坏在数据里
    // 的问题它清不掉。「重新安装」= 重跑官方安装器（增量），不删任何东西。
    var reinstallTarget by remember { mutableStateOf<AppState.AgentInfo?>(null) }
    // 卸载（P3/v1.0）：menuOpenFor = 当前展开「更多菜单」的卡片 id；
    // uninstallTarget = 二次确认弹窗的目标 Agent
    var menuOpenFor by remember { mutableStateOf<String?>(null) }


    // 展开状态卡（或修复完成）时跑一遍体检；IO 采集，与 sysInfo 同模式。
    // #10：inspect 内部已逐项兜底（不抛异常），这里再兜一层——展开状态卡这个动作
    // 宁可少显示几项，也不能变成闪退。
    // 2026-10-12 无感化（§5.4）：改串行整体替换为**流式逐项**——11 项并行跑，先算完先上屏，
    // 每到达一项只替换那一行；全部到齐后落盘，下次打开（含冷启动）先显旧值。
    LaunchedEffect(statusExpanded, healthEpoch) {
        if (statusExpanded) {
            runCatching {
                EnvHealth.inspectStream(context, context.resources).collect { c ->
                    val cur = healthChecks
                    healthChecks = if (cur.isNullOrEmpty()) listOf(c)
                    else cur.filter { it.id != c.id } + c
                }
            }.onFailure { healthChecks = emptyList() }
            LastValues.saveChecks(context, healthChecks.orEmpty())
        }
    }
    var sysInfo by remember { mutableStateOf(sysInfoProcessCache) }
    // 系统信息采集轮次：进页面采一次，之后每次 ON_RESUME 再采一次。
    // 原先只在 LaunchedEffect(Unit) 里采一次——用户去终端装完环境/Agent 再回来，
    // 卡片里的"内存可用 / 已装 Agent / 发行版"全是切走之前的旧快照。
    // 2026-10-12 无感化（§5.5 第 5 处）：进程级缓存兜底——重进页面先显上一次的系统信息，
    // 新快照到达后就地覆盖，不再「加载中…」整块闪现。
    var sysTick by remember { mutableStateOf(0) }
    LaunchedEffect(sysTick) {
        sysInfo = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            SystemInfoProvider.collect(context, context.resources)
        }
        sysInfoProcessCache = sysInfo
    }
    // 每次回到本页（从终端返回）刷新安装状态
    LaunchedEffect(Unit) {
        agents = AppState.agents(context)
        // M3：Agent 清单免发版更新（6h TTL，静默失败；拉到新清单后刷新卡片）
        AgentManifest.refresh(context, force = false) {
            agents = AppState.agents(context)
        }
    }

    // ── 安装状态镜像（对 Compose 可见的那一份）─────────────────────────────
    // 为什么需要这一层（2026-10-07 真机实测得出，修的是"卡片不刷新"）：
    //   AgentRepository 的状态存在 SharedPreferences + /proc 里 —— **Compose 看不见**，
    //   而卡片的按钮形态（安装 / 安装中 / 启动）和失败提示全靠它。
    //   旧写法是轮询时重新赋值 `agents`，指望它带出重组：**没用**，因为 AgentInfo 是
    //   data class，前后两次内容相等 ⇒ mutableStateOf 判定"没变" ⇒ 不重组 ⇒
    //   卡片永远停在旧状态。真机症状：在丹房点「安装」→ 进终端 → 点红点回来，
    //   卡片仍写着「安装」；**切一下 tab 才变成「安装中」**。那种"要手抖一下才刷新"
    //   的状态比没有状态更糟——用户没法判断自己到底点到没有。
    //   故：把 stateOf 的结果灌进一个 snapshot 状态容器，让重组由它驱动。
    //   ⚠️ 必须 `remember`：这一行原先漏了它（WorkBuddy 的 `da3d8eb` 引入，2026-10-07 代码审查
    //   发现）。漏掉的后果很隐蔽——每次重组都会新建一个空 map，而下面那个常驻轮询 effect 捕获的是
    //   **创建它时的那一个实例**（effect 不重启就不换），于是轮询默默往旧实例里灌、组合读的是新空
    //   map，镜像层等于没接上，又退回到 :421 每次重组现算一次的老路。显示结果不会错，只是"卡片
    //   要手抖一下才刷新"那个老毛病会悄悄回来。加上 `remember` 才真正接上。
    val installStates = remember { androidx.compose.runtime.mutableStateMapOf<String, AgentRepository.State>() }
    // 刷新节拍：改 stateTick 能让下面那个 effect 立刻重灌一遍镜像（不等 2 秒）。
    var stateTick by remember { mutableIntStateOf(0) }
    // ⚠️ 这里必须是**常驻循环**，不能写成"还有 Agent 在装才继续查下一轮"（2026-10-07 二次真机实测）：
    //    条件式轮询有个致命缺口——**它需要有东西先把它叫醒**。而"点了「安装」"这件事在
    //    Compose 眼里什么都没发生：没有 state 变化 ⇒ 不重组 ⇒ effect 的 key 不变 ⇒
    //    effect 不重启 ⇒ 连第一轮都跑不起来，卡片就一直停在「安装」。旧版之所以看起来
    //    有时能刷新，是因为切 tab 会把整个 HomeScreen 拆掉重建、歪打正着跑了第一轮——
    //    这正是用户那句"要手抖一下才刷新"的病因。改成常驻循环后，进页面先读一次、
    //    此后每 2 秒重读一次，直到离开该页（effect 随组合销毁自动取消）。
    //    开销：一次读 = SharedPreferences（内存缓存）+ 几个 File.exists()，只有"安装中"
    //    才多扫一次 /proc。半赫兹，且只在丹房 tab 组合，可忽略。
    androidx.compose.runtime.LaunchedEffect(agents, stateTick) {
        while (true) {
            // §B 顺序 6 / P1-4（2026-10-14）：这几行是**真 I/O** —— SharedPreferences +
            // 几个 File.exists()（"安装中"时还扫 /proc）。原先直接跑在 LaunchedEffect 的
            // 默认调度器上，那是**主线程**，且每 2 秒一次。现在挪到 IO 线程算，只把结果
            // 回主线程写进 Compose 状态（状态必须在主线程改）。
            // 2026-10-14 冲突处置：原先这里还顺手同步「恢复全部」账本（AgentLedger）——
            // 那条功能已按用户拍板整条删除，故只保留 IO 化这件事。
            val states = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                agents.map { a -> a.id to AgentRepository.stateOf(context, a) }
            }
            states.forEach { (id, st) -> installStates[id] = st }
            kotlinx.coroutines.delay(2000)
        }
    }

    // 设置页/终端返回后刷新（工作区 / 修复环境可能已变更）
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                agents = AppState.agents(context)
                // 立刻重灌一次安装状态镜像：常驻轮询最快也要等 2 秒，而从终端返回时
                // 用户第一眼就看卡片——"点过安装没有"必须有即时答案（别让他等）。
                stateTick++
                // 系统信息同步重采：内存/存储占用、已装 Agent 列表都是会变的
                sysTick++
                // 展开态下体检也要重跑：去系统设置授权、去终端装 Agent 回来，
                // 存储权限/网络等项的状态已经变了，留着旧结论同样不真实。
                if (statusExpanded) healthEpoch++
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        // ── 固定顶栏（不随内容滚动）──
        // 与太极页一致：标题常驻、内容在下方自滚。原先把标题塞进 LazyColumn 的第一项，
        // 往下滚标题就滚没了——两个根页同一层级，行为却不一样。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            // 设置入口：自绘滑杆图标替代 "⚙" 字符（emoji 在浅色主题里是一块彩色塑料，
            // 与底部三个自绘道家图标不是一套笔）
            TextButton(onClick = onOpenSettings) {
                com.example.zhengdao.SettingsIcon(tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.common_settings), color = MaterialTheme.colorScheme.primary)
            }
        }

        LazyColumn(
            state = listState, // K2：外部注入的 state
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
        // ── 系统状态卡（默认收起）──
        item {
            // 可观察状态：环境在终端里装完后回主页要立刻跟上（v1.2 修复，详见 RootfsState 注释）。
            // 这里不能再写 AppState.rootfsInstalled(context) —— 那是一次性快照，装完不刷新。
            val envReady by RootfsState.installed
            val installedCount = agents.count { it.installed }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    // ⚠️ 必须先 clip 再 clickable：Card 是 16dp 圆角，但涟漪默认按矩形绘制，
                    //    按下去会在圆角外糊出一块方形的印子（2026-10-07 用户当面指出）。
                    //    clip 在外层 → 内层 clickable 画的涟漪被裁成圆角。
                    .clip(RoundedCornerShape(16.dp))
                    .pressClickable { statusExpanded = !statusExpanded },
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    // 第一行＝状态名 + Agent 计数 + 展开箭头（把"这张卡可以点开"这件事说出来）。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    // 状态点颜色过渡：环境装好/卸载的瞬间不闪变（300ms 淡入新色）
                                    animateColorAsState(
                                        targetValue = if (envReady) MaterialTheme.colorScheme.tertiary
                                        else MaterialTheme.colorScheme.error,
                                        animationSpec = tween(300),
                                        label = "statusDot",
                                    ).value
                                , CircleShape,),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (envReady) stringResource(R.string.home_env_ready)
                            else stringResource(R.string.home_env_missing),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = stringResource(R.string.home_agent_count, installedCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        // 展开指示：自绘 chevron（与 BackChevron/ChevronRight 同一套笔）+ 180° 弹性旋转。
                        // 取代原先的 "▴/▾" 字符切换——字符突变是"跳"，旋转是"动"（走查报告 §五同源问题）。
                        val chevronRotation by animateFloatAsState(
                            targetValue = if (statusExpanded) 180f else 0f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMedium,
                            ),
                            label = "expandChevron",
                        )
                        ExpandChevron(
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.rotate(chevronRotation),
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    // 副行＝系统与环境的原始摘要。原先把含计数的整句挤进两行，
                    // 真机上被折成"… 2 / 个 Agent"（数字和量词分家）；现在计数上提到第一行，
                    // 这里只剩系统信息，一行放得下。
                    Text(
                        text = buildString {
                            append("Android ").append(android.os.Build.VERSION.RELEASE)
                            append(" · ").append(
                                android.os.Build.SUPPORTED_ABIS.firstOrNull()
                                    ?: context.getString(R.string.home_abi_unknown)
                            )
                            append(" · ").append(
                                if (envReady) context.getString(R.string.home_debian_installed)
                                else context.getString(R.string.home_debian_missing)
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 展开/收起换弹簧曲线（2026-10-12）：默认的短淡入观感廉价——用户原话
                    // 「说是动态卡一点也不动态」。展开带一点回弹（LowBouncy），
                    // 收起不回弹（收起弹跳观感很怪）。体检项自身的入场见 StaggeredAppear。
                    AnimatedVisibility(
                        visible = statusExpanded,
                        enter = expandVertically(
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioLowBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                            expandFrom = Alignment.Top,
                        ) + fadeIn(tween(180)),
                        exit = shrinkVertically(
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                            shrinkTowards = Alignment.Top,
                        ) + fadeOut(tween(140)),
                    ) {
                        Column {
                            // 摘要与体检之间加一条发丝线：展开后内容骤增，需要一条明确的
                            // "以下属于详情"的分界（与卡片、底栏同一套 0.5dp outlineVariant）。
                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                            Spacer(Modifier.height(12.dp))
                            // 环境体检（P7）：逐项勾叉 + 定向修复；先于系统信息展示
                            val checks = healthChecks
                            Text(
                                text = if (checks == null) stringResource(R.string.home_health_checking)
                                else {
                                    // 告警（warn）不计入"通过"：否则会出现"8/8 通过"却带 ⚠ 的自相矛盾
                                    val pass = checks.count { it.ok && !it.warn }
                                    stringResource(R.string.home_health_pass, pass, checks.size)
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            if (checks == null) {
                                Text(
                                    text = stringResource(R.string.home_health_scanning),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
checks.forEachIndexed { index, c ->
                                    // 每项延迟 index*50ms 淡入+上移：体检结果"飘进来"而不是"砸进来"
                                    // （2026-10-12 用户：「说是动态卡一点也不动态」）。remember 不带 key：
                                    // 流式新项出现在新组合位置 ⇒ 各自播入场；复检更新同位置 ⇒ 不重播。
                                    StaggeredAppear(index = index) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 6.dp),
                                    ) {
                                        // 三态：✗ 故障（可修/引导）· ⚠ 超阈值（无一键修复）· ✓ 正常
                                        Text(
                                            text = when {
                                                !c.ok -> "✗"
                                                c.warn -> "⚠"
                                                else -> "✓"
                                            },
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = when {
                                                !c.ok -> zhengdaoErrorText() // P2-11：error 色 3.55:1 不达正文 AA
                                                // 黄：Material3 无 amber 语义色，用 tertiary 兜底
                                                c.warn -> MaterialTheme.colorScheme.tertiary
                                                else -> MaterialTheme.colorScheme.primary
                                            },
                                            modifier = Modifier.width(22.dp),
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            // 项目名升到 bodyMedium：12sp 的标题 + 12sp 的说明
                                            // 在真机上主次不分，一屏小字看着累。
                                            Text(
                                                text = c.label,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                            )
                                            Text(
                                                text = c.detail,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        // 可修复项：行尾定向修复（宿主侧）；需进 guest 的修复：
                                        // 落盘脚本后开终端跑（过程可见）；RootFS/proot 损坏：引导到设置页重解压
                                        if (!c.ok) {
                                            val tcmd = c.terminalCmd
                                            when {
                                                c.fixId != null -> TextButton(onClick = {
                                                    val fid = c.fixId
                                                    Thread {
                                                        // #10：线程体绝不外抛（未捕获异常 = 进程级闪退，
                                                        // 审计报告 P0-1）。异常在这里就地转成一条「最近问题」。
                                                        try {
                                                            val res = EnvHealth.fixDetailed(context, fid)
                                                            // 修复动作"跑过了"不等于"修好了"（例如
                                                            // link-mode 写成功但 uv 仍裸奔），所以
                                                            // 复检一次再决定要不要在「最近问题」里留一条。
                                                            val again = EnvHealth.inspect(context, context.resources)
                                                                .firstOrNull { it.id == c.id }
                                                            android.os.Handler(context.mainLooper).post {
                                                                healthEpoch++
                                                                if (again != null && !again.ok) {
                                                                    IssueCenter.report(
                                                                        id = "health-fix-${c.id}",
                                                                        title = context.getString(
                                                                            R.string.home_fix_ineffective, c.label,
                                                                        ),
                                                                        detail = (res.error?.let {
                                                                            context.getString(
                                                                                R.string.home_fix_error_prefix, it,
                                                                            )
                                                                        } ?: "") +
                                                                            context.getString(
                                                                                R.string.home_fix_still, again.detail,
                                                                            ),
                                                                        actionLabel = context.getString(R.string.common_feedback),
                                                                        actionId = IssueCenter.ACTION_FEEDBACK,
                                                                    )
                                                                } else {
                                                                    IssueCenter.resolve("health-fix-${c.id}")
                                                                }
                                                            }
                                                        } catch (t: Throwable) {
                                                            IssueCenter.report(
                                                                id = "health-fix-${c.id}",
                                                                title = context.getString(
                                                                    R.string.home_fix_crashed, c.label,
                                                                ),
                                                                detail = context.getString(
                                                                    R.string.home_fix_thread_threw,
                                                                    t.javaClass.simpleName,
                                                                    t.message?.let { m ->
                                                                        context.getString(R.string.home_fix_thread_msg, m)
                                                                    } ?: "",
                                                                ),
                                                                actionLabel = context.getString(R.string.common_feedback),
                                                                actionId = IssueCenter.ACTION_FEEDBACK,
                                                            )
                                                            android.os.Handler(context.mainLooper)
                                                                .post { healthEpoch++ }
                                                        }
                                                    }.start()
                                                }) { Text(stringResource(R.string.common_repair)) }

                                                tcmd != null -> TextButton(onClick = {
                                                    // 宿主侧修不了（要重建 Python 环境、或要 git checkout 补
                                                    // 源码锁）⇒ 脚本落进工作区，进终端跑，输出用户看得见
                                                    if (com.example.zhengdao.terminal.HermesEnv
                                                            .writeRepairScript(context)
                                                    ) {
                                                        onOpenTerminal(tcmd, null)
                                                    } else {
                                                        // 静默失败改正：以前只有一句 Toast，现在同时
                                                        // 留一条「最近问题」（去路 = 去设置页重试）
                                                        val msg = context.getString(R.string.home_repair_script_failed)
                                                        snack(msg)
                                                        IssueCenter.report(
                                                            id = "hermes-repair-script",
                                                            title = context.getString(R.string.home_hermes_script_issue),
                                                            detail = context.getString(R.string.home_hermes_script_issue_detail),
                                                            actionLabel = context.getString(R.string.home_go_settings),
                                                            actionId = IssueCenter.ACTION_OPEN_SETTINGS,
                                                        )
                                                    }
                                                }) { Text(stringResource(R.string.common_repair)) }

                                                // 引导项：文案按去路给，不再统统叫「去处理」
                                                else -> {
                                                    val route = c.route
                                                    TextButton(onClick = {
                                                        when (route) {
                                                            EnvHealth.ROUTE_STORAGE_GRANT -> openStorageGrant()
                                                            // 修复环境 / 网络自检都在设置页，且设置页
                                                            // 是独立全屏 —— 直接过去，用户一眼能看到那一节
                                                            else -> onOpenSettings()
                                                        }
                                                    }) {
                                                        Text(
                                                            when (route) {
                                                                EnvHealth.ROUTE_REPAIR_ENV ->
                                                                    stringResource(R.string.home_btn_repair_env)
                                                                EnvHealth.ROUTE_STORAGE_GRANT ->
                                                                    stringResource(R.string.home_btn_grant)
                                                                EnvHealth.ROUTE_NET_CHECK ->
                                                                    stringResource(R.string.home_btn_net_check)
                                                                // #8-D：存储明细与一键清理都在设置页「存储占用」那张卡上
                                                                EnvHealth.ROUTE_STORAGE_DETAIL ->
                                                                    stringResource(R.string.home_btn_storage_detail)
                                                                else -> stringResource(R.string.home_btn_handle)
                                                            }
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    } // StaggeredAppear 收尾
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            sysInfo?.let { info ->
                                // 系统信息是一段多行长文本，裸排会和体检项混成一片；
                                // 给它一个浅底信息块（与卡片同圆角语系），明确"这是可复制的原文"。
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            MaterialTheme.colorScheme.surfaceContainerLow,
                                            RoundedCornerShape(10.dp),
                                        )
                                        .padding(10.dp),
                                ) {
                                    Text(
                                        text = info.asText(context.resources),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                TextButton(onClick = {
                                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                        as android.content.ClipboardManager
                                    cm.setPrimaryClip(
                                        android.content.ClipData.newPlainText("zhengdao-sysinfo", info.asText(context.resources))
                                    )
                                    snack(context.getString(R.string.home_sysinfo_copied))
                                }) { Text(stringResource(R.string.home_copy_sysinfo)) }
                            } ?: Text(
                                stringResource(R.string.common_loading),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
        // ── 环境未装引导横幅（第三批）：环境没装时 Agent 装不了，先给一条一键安装路径 ──
        // 同样走可观察状态：装完环境回来这条横幅（连同「安装运行环境」按钮）必须消失。
        if (!RootfsState.installed.value) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.home_env_not_installed),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.home_env_install_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = { onOpenTerminal(null, null) },
                            shape = RoundedCornerShape(50),
                        ) {
                            Text(stringResource(R.string.home_install_env))
                        }
                    }
                }
            }
        }
        // ── 最近问题（#4 走查，2026-10-09）：后台静默失败的统一出口 ──
        // 以前这些失败只写 RunLog：用户看到的是"点了没反应"，而日志在公共区躺着没人会看。
        // 非空才渲染；每条尽量带一个能点的去路（重试 / 修复 / 去授权 / 反馈）。
        val issues by IssueCenter.issues
        if (issues.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.home_recent_issues, issues.size),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { IssueCenter.clear() }) {
                                Text(stringResource(R.string.common_clear), color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                        Text(
                            text = stringResource(R.string.home_issues_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        issues.forEach { issue ->
                            Column(modifier = Modifier.padding(top = 10.dp)) {
                                Text(
                                    text = issue.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Text(
                                    text = issue.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                val label = issue.actionLabel
                                val aid = issue.actionId
                                if (label != null && aid != null) {
                                    TextButton(onClick = {
                                        when (aid) {
                                            IssueCenter.ACTION_RETRY_SERVE -> retryServe(false, issue.id)
                                            IssueCenter.ACTION_RESTART_SERVE -> retryServe(true, issue.id)
                                            IssueCenter.ACTION_GRANT_STORAGE -> openStorageGrant()
                                            IssueCenter.ACTION_FEEDBACK -> openFeedback()
                                            // 台账 §B 顺序 11（原 §B-补-42）：这两条以前和「修复环境」一起
                                            // 落进 else = 只打开设置首页，用户还得自己找；现在各去各的页。
                                            IssueCenter.ACTION_VIEW_KEEPALIVE_LOGS -> openKeepaliveLogs()
                                            IssueCenter.ACTION_VIEW_LOGS -> openRunLogs()
                                            // 修复环境 / 通用去设置：设置页里都有对应那一节
                                            else -> onOpenSettings()
                                        }
                                    }) { Text(label) }
                                }
                            }
                        }
                    }
                }
            }
        }
        // ── Agent 卡片（OpenCode 已内置为太极，不在丹房展示——用户定稿）──
        items(agents.filter { it.id != "opencode" }, key = { it.id }) { agent ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                // 页面底色是浅灰（systemGroupedBackground）、卡片是纯白，原本只靠色差划界，
                // 边界偏软。补一条 0.5dp 发丝描边——与底部导航栏的分隔线同一套语言。
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // 读镜像（第一次组合时镜像还没灌，退回实时查一次）——
                    // 这一读同时把本卡片挂到 installStates 上，状态一变就重组。
                    val installState = installStates[agent.id]
                        // §B 顺序 6 / P1-5（2026-10-14）：兜底那次查盘的**结果写回缓存** ——
                        // 否则每次重组（滚动、主题变化、任何 state 变化）都会在主线程重查一遍
                        // SharedPreferences + /proc。写回后每张卡最多查一次，之后由上面那个
                        // 2 秒轮询刷新。**行为不变**：首帧仍显示真实状态。
                        ?: AgentRepository.stateOf(context, agent).also { installStates[agent.id] = it }
                    val installing = installState == AgentRepository.State.Installing
                    val failedInstall = installState == AgentRepository.State.Failed
                    // 一键安装（本地化脚本 + 清锁 + 装完自动启动）：失败重试、首次安装、
                    // 「更多」菜单里的「重新安装」共用同一条路。
                    val envReady = RootfsState.installed.value
                    val startInstall: () -> Unit = {
                        AgentInstaller.prepareInstall(context, agent) { cmd ->
                            onOpenTerminal(cmd, agent.id)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = agent.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        // 卸载入口（P3/v1.0）：已装且有卸载命令的才给「更多」。原先它是描述
                        // 下方另起一行的左对齐 "⋮"，真机上孤零零挂在卡片左下角，像列表装饰；
                        // 挪到主操作旁——它和「启动」同属"对这张卡片的操作"。
                        // 2026-10-09（E-059）：菜单里不止「卸载」——已装的 Agent 也要能「重新安装」，
                        // 所以有安装命令的也把「更多」显示出来。
                        if (agent.installed && (agent.uninstallCmd != null || agent.installCmd != null)) {
                            Box {
                                Box(
                                    modifier = Modifier
                                        // 2026-10-08 走查：34dp → 48dp（Material 3 的最小
                                        // 触摸目标）。「⋮」是卸载入口，34dp 是全 App 最容易
                                        // 点空的控件之一；「⋮」字形本身不变，只是热区变大。
                                        .size(48.dp)
                                        // 同上：圆形热区配方形涟漪很难看，裁成圆
                                        .clip(CircleShape)
                                        .pressClickable { menuOpenFor = agent.id },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "⋮",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                DropdownMenu(
                                    expanded = menuOpenFor == agent.id,
                                    onDismissRequest = { menuOpenFor = null },
                                ) {
                                    if (agent.installCmd != null && envReady) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.home_agent_reinstall)) },
                                            onClick = {
                                                menuOpenFor = null
                                                reinstallTarget = agent
                                            },
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.home_agent_uninstall)) },
                                        onClick = {
                                            menuOpenFor = null
                                            uninstallTarget = agent
                                        },
                                    )
                                }
                            }
                        }
                        when {
                            // ⚠️ 失败要排在 installed **前面**（2026-10-07 真机实测）：
                            //    安装挂在最后一步时，可执行文件往往已经落位了（hermes 实测：
                            //    转发脚本 22:29 就位，Python 依赖装不上、脚本退出码 1）。
                            //    那时若按 installed 给「启动」，卡片就自相矛盾——
                            //    状态行写着"点「安装」可重试"，而按钮把用户往一个坏入口上引。
                            //    失败时该给的是「重试安装」。但要带上 envReady：
                            //    环境没了（重装 App / 卸载过）时重试安装也无从谈起，
                            //    该落到下面的「先装环境」，别给一个点了只会报错的按钮。
                            failedInstall && envReady -> OutlinedButton(
                                onClick = startInstall,
                                modifier = Modifier.widthIn(min = BUTTON_MIN_WIDTH_DP.dp),
                                shape = RoundedCornerShape(50),
                            ) { Text(stringResource(R.string.home_agent_retry_install)) }
                            agent.installed -> Button(
                                onClick = { onOpenTerminal(agent.launchCmd, agent.id) },
                                modifier = Modifier.widthIn(min = BUTTON_MIN_WIDTH_DP.dp),
                                shape = RoundedCornerShape(50),
                            ) { Text(stringResource(R.string.home_agent_start)) }
                            // ⚠️ 这里**不能用 enabled=false**（2026-10-07 真机血案）：
                            // AgentRepository 的「安装中」标记是 SharedPreferences 里的一个
                            // 时间戳，只有"文件真的出现"或"撑过 15 分钟 TTL"两条清除出口。
                            // 安装失败/半途 Ctrl-C/网络断了，标记不会自己消失 ⇒ 卡片连续
                            // 15 分钟显示禁用的「安装中」。用户点了完全没反应——他没法区分
                            // "没点到"、"在忙"还是"坏了"，而且连安装进度都看不到。
                            // 改为可点并跳终端：终端里就是正在跑的安装输出，能看到进度、
                            // 也能 Ctrl-C 中断再点按钮重来。这比一个死掉的禁用按钮诚实。
                            // 注：这条走 onOpenTerminal(null, agent.id) —— **不带命令**，
                            // 会话路由会判成"只是打开终端"，绝不会杀掉正在跑的安装。
                            installing -> OutlinedButton(
                                onClick = { onOpenTerminal(null, agent.id) },
                                modifier = Modifier.widthIn(min = BUTTON_MIN_WIDTH_DP.dp),
                                shape = RoundedCornerShape(50),
                            ) { Text(stringResource(R.string.home_agent_installing)) }
                            agent.installCmd != null && envReady -> OutlinedButton(
                                onClick = startInstall,
                                modifier = Modifier.widthIn(min = BUTTON_MIN_WIDTH_DP.dp),
                                shape = RoundedCornerShape(50),
                            ) { Text(stringResource(R.string.home_agent_install)) }
                            agent.installCmd == null -> OutlinedButton(
                                onClick = { },
                                enabled = false,
                                modifier = Modifier.widthIn(min = BUTTON_MIN_WIDTH_DP.dp),
                                shape = RoundedCornerShape(50),
                            ) { Text(stringResource(R.string.home_agent_coming_soon)) }
                            // 环境未装：Agent 无法安装，按钮禁用（上方横幅已给一键安装路径）
                            else -> OutlinedButton(
                                onClick = { },
                                enabled = false,
                                modifier = Modifier.widthIn(min = BUTTON_MIN_WIDTH_DP.dp),
                                shape = RoundedCornerShape(50),
                            ) { Text(stringResource(R.string.home_agent_install_env_first)) }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = agent.desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 状态行：失败 / 安装中用浅底提示条（原先的裸彩字在真机上会被读成
                    // 上一行的溢出，像渲染坏了）；已装版本属常态信息，保持素色小字。
                    if (failedInstall) {
                        // 文案由 AgentRepository 给：分清「失败（带退出码）」和「被打断」，
                        // 而不是一句笼统的「未完成」——用户 2026-10-07 要求失败可见且可行动。
                        StatusNote(
                            text = AgentRepository.failureReason(context, agent.id),
                            tone = zhengdaoErrorText(), // P2-11：tone 即文字色（见 StatusNote）
                        )
                    } else if (installing) {
                        // E-060：安装的最后一步是 Agent **自己的交互式配置向导**（hermes 实测：
                        // "How would you like to set up Hermes?"）。没人回答它就永远停在那儿——
                        // 脚本不返回、rc 文件不写，卡片一直「安装中」；而在那里按 Ctrl+C
                        // （不是 ESC）会把脚本打断，退出码非 0，又变成"装好了却报失败"。
                        // 我们没法替用户回答向导，但至少能提前把这件事说出来。
                        StatusNote(
                            text = stringResource(R.string.home_agent_install_hint),
                            tone = MaterialTheme.colorScheme.primary,
                        )
                    } else if (agent.installed && agent.installedVersion != null) {
                        // 只显示已装版本（本地 package.json 探测）；要不要更新由用户自己决定
                        Text(
                            text = stringResource(R.string.home_agent_version, agent.installedVersion),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                }
            }
        }
        // 空态：manifest 拉不到时列表会空掉，给一句话而不是一片白
        if (agents.none { it.id != "opencode" }) {
            item {
                Text(
                    text = stringResource(R.string.home_manifest_unreachable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                )
            }
        }
        item {
            Text(
                text = stringResource(R.string.home_agent_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
        } // LazyColumn 结束
    } // 外层 Column（固定顶栏 + 列表）结束

    // Snackbar 宿主（2026-10-12）：挂在页面最底层，错误提示从 Toast 迁到此处
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(bottom = 8.dp),
    )
    } // Box（内容 + Snackbar 浮层）结束

    // 卸载二次确认（P3/v1.0）：「卸载」删程序、留用户数据；「彻底清除」连用户数据一起删
    // （仅限已知名单内的 Agent——未知 manifest 条目的数据布局不猜测，不提供该选项）。
    // 尺寸用真实 du（从 uninstall 命令解析 rm 目标路径映射宿主后统计），不写死数字。
    uninstallTarget?.let { target ->
        val cmd = target.uninstallCmd ?: return@let
        val wipe = wipeTargets[target.id]
        var sizesText by remember(target.id) {
            mutableStateOf(context.getString(R.string.home_size_measuring))
        }
        LaunchedEffect(target.id) {
            sizesText = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val prog = programSizeMb(context, cmd)
                val cache = runCatching {
                    // "安装包缓存" 是 CacheCleaner.measure() 的目录名（数据侧 key），按原样匹配
                    com.example.zhengdao.terminal.CacheCleaner.measure(context)["安装包缓存"] ?: 0L
                }.getOrDefault(0L)
                val progText = if (prog >= 0) context.getString(R.string.home_about_mb, prog)
                else context.getString(R.string.home_actual_usage)
                context.getString(R.string.home_uninstall_detail, progText, cache)
            }
        }
        AlertDialog(
            onDismissRequest = { uninstallTarget = null },
            shape = RoundedCornerShape(14.dp),
            title = { Text(stringResource(R.string.home_uninstall_title, target.name)) },
            text = {
                Text(
                    buildString {
                        append(context.getString(R.string.home_uninstall_delete)).append(sizesText).append("\n\n")
                        append(context.getString(R.string.home_uninstall_keep))
                        wipe?.let {
                            append(context.getString(R.string.home_uninstall_purge, it.joinToString("、")))
                        }
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    uninstallTarget = null
                    // agentId 传 null：卸载不走「一键安装」登记，传 id 会被误标「安装中」
                    onOpenTerminal(cmd, null)
                }) { Text(stringResource(R.string.home_uninstall_action)) }
            },
            dismissButton = {
                Row {
                    if (wipe != null) {
                        TextButton(onClick = {
                            uninstallTarget = null
                            onOpenTerminal(cmd + " && rm -rf " + wipe.joinToString(" "), null)
                        }) {
                            Text(
                                stringResource(R.string.home_purge_action),
                                // P2-11：破坏性按钮的文字也要 4.5:1（error 色只有 3.55:1）
                                color = zhengdaoErrorText(),
                            )
                        }
                    }
                    TextButton(onClick = { uninstallTarget = null }) { Text(stringResource(R.string.common_cancel)) }
                }
            },
        )
    }

    // 重新安装二次确认（E-059）：这里要一句一句说清"不删数据"，否则用户会以为它等于卸载。
    reinstallTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { reinstallTarget = null },
            shape = RoundedCornerShape(14.dp),
            title = { Text(stringResource(R.string.home_reinstall_title, target.name)) },
            text = {
                Text(stringResource(R.string.home_reinstall_desc))
            },
            confirmButton = {
                TextButton(onClick = {
                    reinstallTarget = null
                    AgentInstaller.prepareInstall(context, target) { cmd ->
                        onOpenTerminal(cmd, target.id)
                    }
                }) { Text(stringResource(R.string.home_reinstall_action)) }
            },
            dismissButton = {
                TextButton(onClick = { reinstallTarget = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

}

/**
 * Agent 卡片内的状态提示条：语义色 10% 浅底 + 8dp 圆角。
 * 取代原先的裸彩字——裸红字紧跟在描述下方，真机上会被读成上一行的溢出／渲染错误。
 */
@Composable
private fun StatusNote(text: String, tone: Color) {
    Spacer(Modifier.height(8.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(tone.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = tone,
        )
    }
}

/** 「彻底清除」时随程序一起删除的用户数据目录（guest 内路径；shell 展开 ~）。
 *  仅限已知名单——未知 manifest 条目的数据布局不猜测。 */
private val wipeTargets: Map<String, List<String>> = mapOf(
    "claude-code" to listOf("~/.claude"),
    "hermes" to listOf("~/.hermes"),
    "codex" to listOf("~/.codex"),
)

/** 依据 manifest 的 uninstall 命令估算 Agent 程序体积：解析其中 rm -rf 的目标路径，
 *  映射到宿主真实目录后求 du 和（真实 du，不写死数字）。仅识别 /root/→home 与
 *  /usr/→rootfs/usr 两类映射；含通配符的路径跳过；解析不出已知路径时返回 -1
 *  （弹窗改用"以实际占用为准"措辞，不编造）。 */
private fun programSizeMb(ctx: android.content.Context, uninstallCmd: String): Long {
    val paths = Regex("rm\\s+-rf\\s+([^&\\n]+)").findAll(uninstallCmd)
        .flatMap { it.groupValues[1].trim().split(Regex("\\s+")) }
        .filter { it.startsWith("/") && !it.contains('*') }
        .distinct()
        .mapNotNull { p ->
            when {
                p == "/root" || p.startsWith("/root/") ->
                    java.io.File(ctx.filesDir, "home/" + p.removePrefix("/root/"))
                p == "/usr" || p.startsWith("/usr/") ->
                    java.io.File(ctx.filesDir, "rootfs" + p)
                else -> null
            }
        }
        .toList()
    if (paths.isEmpty()) return -1
    return paths.sumOf { SystemInfoProvider.dirSizeMb(it) }
}

/**
 * 交错入场（2026-10-12，用户「说是动态卡一点也不动态」）：体检项延迟 [index]×50ms
 * 淡入并轻微上移——检查结果"飘进来"而不是"砸进来"。突兀的闪现会被大脑处理成
 * 异常事件（用户原话「吓人」），有过渡才是"正常变化"。
 *
 * remember **不带 key** 是刻意的：流式上屏的新项出现在新的组合位置 ⇒ 各自播入场；
 * 复检更新的项位置不变 ⇒ 不重播（体检结果刷新时整列重弹一遍会很吵）。
 */
@Composable
private fun StaggeredAppear(index: Int, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(index * 50L)
        shown = true
    }
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(tween(220)) +
            expandVertically(
                animationSpec = tween(220),
                expandFrom = Alignment.Top,
            ),
        exit = shrinkVertically(tween(180)) + fadeOut(tween(180)),
    ) { content() }
}

/**
 * 展开指示 chevron（朝下）：与 [com.example.zhengdao.MainActivity] 的 BackChevron /
 * ChevronRight 同一套笔（圆头圆角 + 1.8f 描边，与 MainActivity 那对同源）。旋转动画由调用方
 * `Modifier.rotate(...)` 承担（180° 弹性旋转），本组件只负责画形。
 */
@Composable
private fun ExpandChevron(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(15.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.30f, h * 0.34f)
            lineTo(w * 0.50f, h * 0.66f)
            lineTo(w * 0.70f, h * 0.34f)
        }
        drawPath(
            path,
            tint,
            style = Stroke(width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
