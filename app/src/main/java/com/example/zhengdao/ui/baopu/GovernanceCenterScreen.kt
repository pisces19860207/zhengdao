// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百二十二批（2026-11-08）· **治理中心**：左侧栏「定时任务」下面那一屏，**只看**。
//
// 用户定调两次（2026-11-07/08）：「治理中心放左侧栏，排在定时任务的下面」＋
// 「治理中心就是看的小玩具，但**决策中心才是主要的**」⇒ 两屏分工写死：
//   · 本屏＝**看状态**（读数／执行体／模型小黑板），一条"要你拍板"的条目都不放；
//   · 办事在**决策中心**（顶栏盾牌那套五页签：御案/待授权/建议/提案/记录），两屏各管一段。
//
// 三条纪律（判据建在这上面）：
//   1. **读数只走 527**：本屏调 `GovernanceStateTool.run` 拿那一把 JSON，再由
//      `GovernanceCenterRules` 原样抄成行。本文件里没有第二个取数路，也没有任何算术
//      （判据②；源级门禁 `GovernanceCenterGateTest` 扫的就是这条）。
//   2. **只读**：除下面这三个由**人**按的动作，本屏没有任何写口
//      （不碰队列、不碰 `DecisionTab` / `royalRows`——判据⑤）：
//      · 清空小黑板（322）；· 清空今天干过什么（539，只抬本地水位，账本一个字节都不动）；
//      · 确认删一条待办（565 → 541 的 `TodoStore.confirm(actor = 用户)`，追加一行 confirm）。
//      三个都够不着模型：`todo_confirm` 根本不是工具（541 判据⑤）。
//   3. **不新增导航栈**：全屏 Dialog（与本仓既有浮层同形态），返回键/关闭键一起收，页面栈不变。
//
// 第五百三十九批（2026-11-09）追加第 4 块「今天干过什么」：三本账的已执行行，四列
// （时间·谁·做了什么·结果）。本屏在这一块里**只画不算**——取数在 `ActivityBoardSource`，
// 过滤/四列/倒序在 `ActivityBoardRules`，这里只把行照着画出来（判据⑥）。
//
// 第五百六十五批（2026-11-09）追加第 6 块「待办」：收起态一行 `待办 N · 在做 M`，点开列列表、
// 再点收回（**一次只展开一块**，状态机在 `TodoBoardRules.toggle`）。三条口径落在判据层：
//   · 行里只有摘要 + 状态签（`TodoRowView` 就三个字段，想多画也拿不到——判据③）；
//   · 「确认」只出现在「已完成」上（`canConfirm`），而「已完成」只有右相 PASS 一个入口（判据④）；
//   · 点「确认」＝调 541 的 `TodoStore.confirm(actor = 用户)`：**追加**一行 confirm，
//     看板由账本回放得出 ⇒ 该条消失、账本历史一行不少。
// 于是本屏的人按写口从两个变成**三个**：清空小黑板 / 清空今天干过什么 / 确认删一条待办。
// 三个都由**人**按，模型一个都够不着（541 判据⑤：`todo_confirm` 不是工具）。
//
// 第五百六十一批（2026-11-09）追加第 7 块「开发核对」：一行摘要 ＋「导出核对包」。
// 块本体在 `GovernanceCenterAudit.kt`（544/558/561 三单同屏在飞，块放独立文件把合并面压到最小）；
// 屏里只补快照一个字段＋LazyColumn 一行。三条口径落在判据层：
//   · **不加抽屉入口**（判据⑥：入口净增 0）——这块只活在屏内，抽屉一行不动；
//   · 「导出核对包」是本屏第 4 个**人按**写口，采集/组包/防覆盖全在 `BuildAuditPack`，
//     模型一个都够不着（它不在任何工具 spec 里）；
//   · 导出＝只读采集＋写 `<工作区>/产物/.zd-audit-*.json/.md`：点号开头不计产物数（512 口径），
//     同名不覆盖旧文件；readings 只抄 527 出口的 day/ratios/cards/gray 白名单四键（判据④）。
//
// 承载路子照本仓既有的全屏浮层（`Surface` ＋ 返回键一并收）：换个 Activity 要动 Manifest，
// 也会把"抽屉里点进去"变成另一套返回栈——本屏不另开一条。
// （2026-11-08 追加：536 已把 528 那屏整体回退掉，本屏的"同形态"参照物只剩 104 那套设置浮层。）
package com.example.zhengdao.ui.baopu

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.zhengdao.R
import com.example.zhengdao.BuildConfig
import com.example.zhengdao.baopu.BuildAuditPack
import com.example.zhengdao.baopu.agent.BoardRules
import com.example.zhengdao.baopu.agent.BoardStore
import com.example.zhengdao.baopu.agent.GovernanceStateTool
import com.example.zhengdao.baopu.consequence.CredentialStore
import com.example.zhengdao.baopu.consequence.IdentityStore
import com.example.zhengdao.baopu.todo.TodoActor
import com.example.zhengdao.baopu.todo.TodoCounts
import com.example.zhengdao.baopu.todo.TodoRules
import com.example.zhengdao.baopu.todo.TodoState
import com.example.zhengdao.baopu.todo.TodoStore
import com.example.zhengdao.keepalive.CoroutineCrashHandler
import com.example.zhengdao.ui.SectionCard
import com.example.zhengdao.ui.theme.ShapeCard
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 这一屏读到的全部东西（一次 IO 全拿，渲染不做第二次读盘）。
 *
 * @param root 527 出口那一把 JSON（null ＝ 出口没出来，界面逐格说读不到）
 * @param credentialCount 凭据绑定表条数（读侧 `CredentialStore.load`；不认识的族跳过）
 * @param revokedAtMs 最近一次撤销的时刻（0 ＝没撤销过）
 * @param board 小黑板全文（null ＝读不出；""＝空便签，是两件不同的事）
 */
private data class GovSnapshot(
    val root: JSONObject? = null,
    val credentialCount: Int = 0,
    val revokedAtMs: Long = 0L,
    val board: String? = null,
    /** 小黑板上次写盘的时刻（0 ＝盘上没有这件文件；542 起用于顶上那行「最近更新」）。 */
    val boardUpdatedAtMs: Long = 0L,
    /**
     * 「今天干过什么」的行（539）：**已经过判据层的四列行**（排序/过滤都在
     * [ActivityBoardRules] 里做完了）。本屏不再从账本取第二遍，也不在这里筛。
     */
    val activity: List<ActivityRow> = emptyList(),
    /** 上次「清空」的时刻（`HH:mm`；空串＝没清过）。只清投影，账本原文不动。 */
    val activityClearedClock: String = "",
    /**
     * 待办块的行（565）：**已经过判据层的三字段行**（只有 id/摘要/状态，看板口径在
     * [TodoBoardRules.rows]）。本屏不筛、不拼字、不算数。
     */
    val todoRows: List<TodoRowView> = emptyList(),
    /** 收起态那一行要的两个数（[TodoBoardRules.header] 是唯一渲染口）。 */
    val todoCounts: TodoCounts = TodoCounts(todo = 0, doing = 0, complete = 0),
    /** 首次读盘还没回来：与"读出来是空的"分开（同抽屉那批 264 的口径）。 */
    val ready: Boolean = false,
    /**
     * 「开发核对」块那一行摘要（561）：`构建 … · 开关 … · 可删候选 …`。
     * 拼装在 `BuildAuditPack.summaryLine`（数据层），本屏只照着画（565"只画不算"同款）。
     */
    val auditSummary: String? = null,
)

/**
 * 治理中心（只读一屏）。
 *
 * @param onDismiss 关闭（顶部 ←／系统返回）
 */
@Composable
internal fun GovernanceCenterScreen(
    controller: BaopuChatController,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // 协程作用域必须装崩溃处理器（本仓门禁：UI 侧不许裸 rememberCoroutineScope）
    val scope = rememberCoroutineScope { CoroutineCrashHandler }
    // 每次进屏重读一次（便签随时会被模型改；读数也只对"看的那一刻"负责）。
    var tick by remember { mutableIntStateOf(0) }
    var snapshot by remember { mutableStateOf(GovSnapshot()) }
    var confirmingClear by remember { mutableStateOf(false) }
    var confirmingActivityClear by remember { mutableStateOf(false) }
    // 565：哪一块是展开的（null＝全收起）。状态机在判据层（TodoBoardRules.toggle），这里只存返回值。
    var expandedBlock by remember { mutableStateOf<String?>(null) }

    // 读盘全在 IO 上（264 的口径：抽屉/面板首帧不许占主线程），读回来再一次性换快照。
    LaunchedEffect(tick) {
        snapshot = withContext(Dispatchers.IO) {
            readSnapshot(context, controller.workspaceRoot)
        }
    }

    GovFrame(
        snapshot = snapshot,
        wsRoot = controller.workspaceRoot,
        expandedBlock = expandedBlock,
        onDismiss = onDismiss,
        onClearBoard = { confirmingClear = true },
        onClearActivity = { confirmingActivityClear = true },
        onToggleTodo = {
            expandedBlock = TodoBoardRules.toggle(expandedBlock, TodoBoardRules.BLOCK_TODO)
        },
        onConfirmTodo = { id ->
            launchTodoConfirm(scope, controller.workspaceRoot, id) { tick++ }
        },
    )

    GovClearDialogs(
        board = confirmingClear,
        activity = confirmingActivityClear,
        onDismissBoard = { confirmingClear = false },
        onConfirmBoard = {
            confirmingClear = false
            scope.launch {
                withContext(Dispatchers.IO) { BoardStore.clear(controller.workspaceRoot) }
                tick++ // 重读：清完当场变空，不留旧字
            }
        },
        onDismissActivity = { confirmingActivityClear = false },
        onConfirmActivity = {
            confirmingActivityClear = false
            scope.launch {
                // 只抬水位（本地投影），账本原文一条都不动（判据②）
                withContext(Dispatchers.IO) { ActivityBoardStore.clear(context.filesDir) }
                tick++ // 重读：清完当场变空
            }
        },
    )
}

/** 整屏骨架（浮层 ＋ 顶栏 ＋ 七块滚动区）：只是排版，不取数、不判断。 */
@Composable
private fun GovFrame(
    snapshot: GovSnapshot,
    wsRoot: File,
    expandedBlock: String?,
    onDismiss: () -> Unit,
    onClearBoard: () -> Unit,
    onClearActivity: () -> Unit,
    onToggleTodo: () -> Unit,
    onConfirmTodo: (String) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                GovTopBar(onDismiss)
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    item { ReadingSection(snapshot) }
                    item { Spacer(Modifier.height(14.dp)) }
                    item { GoalSection(snapshot) }
                    item { Spacer(Modifier.height(14.dp)) }
                    item { ExecutorSection(snapshot) }
                    item { Spacer(Modifier.height(14.dp)) }
                    item { BoardSection(snapshot = snapshot, onClear = onClearBoard) }
                    item { Spacer(Modifier.height(14.dp)) }
                    item { ActivitySection(snapshot = snapshot, onClear = onClearActivity) }
                    item { Spacer(Modifier.height(14.dp)) }
                    item {
                        TodoSection(
                            snapshot = snapshot,
                            expanded = expandedBlock == TodoBoardRules.BLOCK_TODO,
                            onToggle = onToggleTodo,
                            onConfirm = onConfirmTodo,
                        )
                    }
                    // 「开发核对」（561）：块本体在 GovernanceCenterAudit.kt；屏内块、无抽屉入口。
                    item { AuditSection(summary = snapshot.auditSummary, wsRoot = wsRoot) }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

/**
 * 人按「确认」那一下（565）：IO 上追加一行 confirm，回主线程重读 ⇒ 那一条当场从看板消失。
 *
 * 写口只有一个（541 的 `TodoStore.confirm`）；它自己会再拒一次非用户角色（判据⑤第二道闸），
 * 所以这里即使被谁误调也不会把条目删掉。
 */
private fun launchTodoConfirm(
    scope: CoroutineScope,
    wsRoot: File,
    id: String,
    onChanged: () -> Unit,
) {
    scope.launch {
        withContext(Dispatchers.IO) {
            TodoStore.confirm(wsRoot, id, TodoActor.USER, System.currentTimeMillis())
        }
        onChanged()
    }
}

/** 两个「清空」的确认框（小黑板一份、看板水位一份）；只在按下时出现，不占版面。 */
@Composable
private fun GovClearDialogs(
    board: Boolean,
    activity: Boolean,
    onDismissBoard: () -> Unit,
    onConfirmBoard: () -> Unit,
    onDismissActivity: () -> Unit,
    onConfirmActivity: () -> Unit,
) {
    if (board) BoardClearDialog(onDismiss = onDismissBoard, onConfirm = onConfirmBoard)
    if (activity) ActivityClearDialog(onDismiss = onDismissActivity, onConfirm = onConfirmActivity)
}

/** 一次读全（IO 上跑；每一路都自己兜住，缺哪路由界面如实说读不到）。 */
private fun readSnapshot(context: Context, wsRoot: File): GovSnapshot {
    val nowMs = System.currentTimeMillis()
    // ① 读数：**唯一取数路**——527 出口。不在这里算第二遍（判据②）。
    val root = runCatching {
        val outcome = GovernanceStateTool.run(wsRoot, nowMs)
        (outcome as? com.example.zhengdao.baopu.agent.ToolOutcome.Ok)?.let {
            GovernanceCenterRules.parse(it.text)
        }
    }.getOrNull()
    // ② 执行体另外两格：凭据条数、最近撤销（527 只给 id/state 两格）
    val creds = runCatching { CredentialStore.load(context.filesDir).size }.getOrDefault(0)
    val revokedAt = runCatching {
        GovernanceCenterRules.lastRevokeAtMs(IdentityStore.load(context.filesDir))
    }.getOrDefault(0L)
    // ③ 小黑板：只读镜像 ＋ 上次写盘时刻（542：顶上那行「最近更新 HH:mm」只认这两样）
    val board = BoardStore.read(wsRoot)
    val boardUpdatedAtMs = BoardStore.updatedAt(wsRoot)
    // ④ 今天干过什么（539）：账本 → 候选（取数层）→ 四列行（判据层）。本屏不筛、不算。
    val activityClearedAtMs = ActivityBoardStore.readClearedAt(context.filesDir)
    val activity = ActivityBoardRules.rows(
        candidates = ActivityBoardSource.candidates(context.applicationContext, nowMs),
        nowMs = nowMs,
        clearedAtMs = activityClearedAtMs,
    )
    // ⑤ 待办（565）：读不出 ⇒ 空列表（"没有待办"与"读不出"在界面上是同一句"现在没有待办"，
    // 不做第三种文案——待办不是读数，读不出时它不是"账不实"，是本来就可能还没立过）。
    val todoItems = runCatching { TodoStore.items(wsRoot) }.getOrDefault(emptyList())
    // ⑥ 开发核对摘要（561）：开关现值＋短号＋可删候选数，拼装在 BuildAuditPack（数据层）。
    //    注意它**不是第二套读数**——率/弹卡/灰度仍只走上面那条 527 出口；这里只有开关布尔。
    val auditSummary = runCatching {
        BuildAuditPack.summaryLine(
            BuildConfig.GIT_SHA,
            BuildAuditPack.collectSwitches(context),
            BuildAuditPack.deadCandidateCount(),
        )
    }.getOrNull()
    return GovSnapshot(
        root = root,
        credentialCount = creds,
        revokedAtMs = revokedAt,
        board = board,
        boardUpdatedAtMs = boardUpdatedAtMs,
        activity = activity,
        activityClearedClock = if (activityClearedAtMs > 0L) {
            GovernanceCenterRules.clockHm(activityClearedAtMs)
        } else {
            ""
        },
        // ⑤ 待办（565）：账本 → 三字段行（判据层）＋ 收起态两个数。本屏不筛、不拼。
        todoRows = TodoBoardRules.rows(todoItems),
        todoCounts = TodoRules.counts(todoItems),
        auditSummary = auditSummary,
        ready = true,
    )
}

@Composable
private fun GovTopBar(onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("←") }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(
                text = stringResource(R.string.gc_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.gc_subtitle),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** ① 读数区：四率 · 弹卡三数 · 灰度（全部原样抄 527 出口）。 */
@Composable
private fun ReadingSection(snapshot: GovSnapshot) {
    val root = snapshot.root
    val readings = remember(root, snapshot.ready) {
        if (!snapshot.ready) null else GovernanceCenterRules.ratios(root) +
            GovernanceCenterRules.cards(root) +
            GovernanceCenterRules.grays(root)
    }
    SectionCard(title = stringResource(R.string.gc_section_reading)) {
        if (readings == null) {
            LoadingLine()
            return@SectionCard
        }
        val day = GovernanceCenterRules.dayOf(root)
        Text(
            text = if (day.isEmpty()) {
                stringResource(R.string.gc_reading_hint)
            } else {
                stringResource(R.string.gc_reading_day, day)
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        readings.forEach { r -> ReadingLine(labelOf(r.key), r.value, r.detail) }
    }
}

/**
 * ①′ 目标（第四百八十二批）：一行 —— `目标：… / 进度 3/5 步 / 预算用 40%`。
 *
 * 整行是出口给的字，本屏不拼、不算（322 判据②）；没有目标就显示「读不到」＋原因。
 */
@Composable
private fun GoalSection(snapshot: GovSnapshot) {
    val reading = remember(snapshot) { GovernanceCenterRules.goal(snapshot.root) }
    SectionCard(title = stringResource(R.string.gc_section_goal)) {
        if (!snapshot.ready) {
            LoadingLine()
            return@SectionCard
        }
        Text(
            text = stringResource(R.string.gc_goal_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Text(
            text = reading.value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (reading.detail.isNotEmpty()) {
            Text(
                text = reading.detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** ② 执行体：id 前 8 · 凭据条数 · 最近撤销。 */
@Composable
private fun ExecutorSection(snapshot: GovSnapshot) {
    val exec = remember(snapshot) {
        GovernanceCenterRules.executor(snapshot.root, snapshot.credentialCount, snapshot.revokedAtMs)
    }
    SectionCard(title = stringResource(R.string.gc_section_executor)) {
        if (!snapshot.ready) {
            LoadingLine()
            return@SectionCard
        }
        ReadingLine(
            label = stringResource(R.string.gc_executor_id),
            value = exec.id8.ifEmpty { stringResource(R.string.gc_executor_none) },
            detail = stateLabel(exec.state),
        )
        ReadingLine(
            label = stringResource(R.string.gc_executor_creds),
            value = exec.credentialCount.toString(),
            detail = "",
        )
        ReadingLine(
            label = stringResource(R.string.gc_executor_revoke),
            value = exec.lastRevokeClock.ifEmpty { stringResource(R.string.gc_executor_none) },
            detail = "",
        )
    }
}

/**
 * ③ 模型小黑板（只读镜像 ＋ 人工清空）。
 *
 * 第五百四十二批：**A 案板面**（深绿底 `#1F3B2F` / 白字 `#F2F1E6`）。四条样式口径逐条落地：
 * 1. **顶上只有一行**「模型小黑板 · 最近更新 HH:mm」——板名与时刻同一行，不再另起一个小标题；
 *    没写过（盘上没这件文件）时**不显示**「最近更新」，不编一个时间；
 * 2. **空板占位**「（还没写过）」：与"读不出"分开（后者如实说读不出）；
 * 3. **右下「清空」**：文字按钮，板上有字才按得动（空板时按钮仍在，只是不可点——
 *    "三样齐"要的是**看得见**，不是"处处可点"）；
 * 4. **不做主题切换**：板面色住在 `BoardPalette`，不读 `isZhengdaoDark()`（一块板不随深浅模式变色）。
 *
 * 尺寸/形状一律用律令既有 token：卡片 `ShapeCard`（16dp 圆角）、内距 16dp（同 `SectionCard`）、
 * 正文 `bodyMedium`（15sp）、小字 `labelMedium`（13sp）/ `labelSmall`（10sp）、
 * 按钮 `heightIn(min = 48.dp)`（触摸目标下限，同本仓顶栏口径）。**没有新增字号/圆角/内距档位。**
 */
@Composable
private fun BoardSection(snapshot: GovSnapshot, onClear: () -> Unit) {
    val view = remember(snapshot) {
        GovernanceCenterRules.boardView(snapshot.board, snapshot.boardUpdatedAtMs)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = ShapeCard,
        colors = CardDefaults.cardColors(containerColor = BoardPalette.ground),
    ) {
        // 三段各自一个函数：一段一件事（板名行 / 正文 / 底部那一行），
        // 也免得整块超 detekt 的 60 行（542 首跑就是 70 行红的）。
        Column(Modifier.padding(16.dp)) {
            BoardTitleLine(view)
            Spacer(Modifier.height(8.dp))
            BoardBodyLine(snapshot.board, view)
            BoardFooterLine(view, onClear)
        }
    }
}

/** 板面顶上一行：「模型小黑板」＋（有这件文件时）「 · 最近更新 HH:mm」。 */
@Composable
private fun BoardTitleLine(view: GovBoard) {
    BoardText(
        text = if (view.updatedClock.isEmpty()) {
            stringResource(R.string.gc_section_board)
        } else {
            stringResource(
                R.string.gc_board_header,
                stringResource(R.string.gc_section_board),
                view.updatedClock,
            )
        },
        style = MaterialTheme.typography.labelMedium,
        color = BoardPalette.chalkDim,
    )
}

/** 板面正文：读不出／空板（占位）／有字，三态分开（空与读不出是两件事）。 */
@Composable
private fun BoardBodyLine(board: String?, view: GovBoard) {
    when {
        view.unreadable -> BoardText(
            text = stringResource(R.string.gc_board_unreadable),
            style = MaterialTheme.typography.bodyMedium,
            color = BoardPalette.chalkDim,
        )
        view.empty -> BoardText(
            text = stringResource(R.string.gc_board_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = BoardPalette.chalkDim,
        )
        else -> BoardText(
            text = board.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = BoardPalette.chalk,
        )
    }
}

/** 板面底下一行：左边上限读数（小字），右下「清空」（文字按钮，空板时不可点但仍在）。 */
@Composable
private fun BoardFooterLine(view: GovBoard, onClear: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BoardText(
            text = stringResource(
                R.string.gc_board_capacity,
                BoardRules.MAX_KB,
                BoardRules.MAX_LINES,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = BoardPalette.chalkDim,
        )
        Spacer(Modifier.weight(1f))
        TextButton(
            onClick = onClear,
            enabled = view.clearEnabled,
            modifier = Modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.textButtonColors(
                contentColor = BoardPalette.chalk,
                // 不可点时压暗到"看得出不可点"：板面底色上不靠系统主题色（那个青在绿上说不清）
                disabledContentColor = BoardPalette.chalkDim.copy(alpha = 0.45f),
            ),
        ) {
            Text(stringResource(R.string.gc_board_clear))
        }
    }
}

/**
 * 板面上的一行字（单一出口：三处文案共用同一套换行/顺序口径，防止有人单独改行高）。
 *
 * ⚠️ **不许给它加 `fillMaxWidth()`**（542 真机截图上踩过）：这一行也用在底部那个 `Row` 里，
 * 一填满宽度就把 `Spacer(weight)` 与「清空」按钮挤到可视区之外——`uiautomator dump` 里
 * 连节点都没有，看着像"按钮没做"。正文的换行不需要它：`Text` 本来就按父约束换行。
 */
@Composable
private fun BoardText(text: String, style: TextStyle, color: Color) {
    Text(text = text, style = style, color = color)
}

@Composable
private fun BoardClearDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.gc_board_clear_title)) },
        text = { Text(stringResource(R.string.gc_board_clear_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.gc_board_clear)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.gc_cancel)) }
        },
    )
}

/**
 * ④ 今天干过什么（第五百三十九批）：三本账里**已执行且有结果**的行，四列一张表。
 *
 * 三条口径写死在这里（判据建在上面）：
 * 1. **只画不算**：行是 [ActivityBoardRules.rows] 给的四列字（时间已格式化、顺序已倒序、
 *    该丢的已丢），本函数一个判断都不加——判据⑥要的就是"页面没有第二套口径"；
 * 2. **没有批/驳/搁置**：这一屏只显示发生过什么，需要拍板的条目一个都不进（那是决策中心）；
 * 3. **清空只清这一屏**：抬本地水位（[ActivityBoardStore]），账本原文一条都不动（判据②）。
 */
@Composable
private fun ActivitySection(snapshot: GovSnapshot, onClear: () -> Unit) {
    SectionCard(title = stringResource(R.string.gc_section_activity)) {
        if (!snapshot.ready) {
            LoadingLine()
            return@SectionCard
        }
        Text(
            text = stringResource(R.string.gc_activity_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        if (snapshot.activityClearedClock.isNotEmpty()) {
            Text(
                text = stringResource(R.string.gc_activity_cleared, snapshot.activityClearedClock),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        ActivityTitles()
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ActivityBody(snapshot)
        ActivityFooter(onClear)
    }
}

/** 四列的列名（与 [ActivityLine] 同一套宽度比例：表头和数据列必须对齐）。 */
@Composable
private fun ActivityTitles() {
    Row(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
        ActivityCell(stringResource(R.string.gc_activity_col_time), W_TIME, dimStyle())
        ActivityCell(stringResource(R.string.gc_activity_col_who), W_WHO, dimStyle())
        ActivityCell(stringResource(R.string.gc_activity_col_what), W_WHAT, dimStyle())
        ActivityCell(stringResource(R.string.gc_activity_col_result), W_RESULT, dimStyle())
    }
}

/** 表体：有行照抄四列，没行如实说"今天还没有"。 */
@Composable
private fun ActivityBody(snapshot: GovSnapshot) {
    if (snapshot.activity.isEmpty()) {
        Text(
            text = stringResource(R.string.gc_activity_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        return
    }
    snapshot.activity.forEach { row -> ActivityLine(row) }
}

/** 一行四列（时间 · 谁 · 做了什么 · 结果）。 */
@Composable
private fun ActivityLine(row: ActivityRow) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        ActivityCell(row.clock, W_TIME, dimStyle())
        ActivityCell(activityWhoLabel(row.who), W_WHO, dimStyle())
        ActivityCell(row.what, W_WHAT, MaterialTheme.typography.bodySmall)
        ActivityCell(activityResultLabel(row.result), W_RESULT, dimStyle(), activityResultColor(row.result))
    }
}

/** 一格里的一行字（四个权重常量只在这里用，表头与数据列共用 ⇒ 不会各走各的）。 */
@Composable
private fun RowScope.ActivityCell(
    text: String,
    weight: Float,
    style: TextStyle,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Text(
        text = text,
        style = style,
        color = color,
        modifier = Modifier.weight(weight).padding(end = 6.dp),
    )
}

/** 底下那行：左上限读数（小字），右「清空」。 */
@Composable
private fun ActivityFooter(onClear: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.gc_activity_capacity),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onClear, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.gc_activity_clear))
        }
    }
}

@Composable
private fun ActivityClearDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.gc_activity_clear_title)) },
        text = { Text(stringResource(R.string.gc_activity_clear_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.gc_activity_clear)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.gc_cancel)) }
        },
    )
}

/** 四列的宽度比例（和＝1.0；时间与结果各占一格窄的，正文吃剩下的）。 */
private const val W_TIME = 0.18f
private const val W_WHO = 0.16f
private const val W_WHAT = 0.48f
private const val W_RESULT = 0.18f

@Composable
private fun dimStyle(): TextStyle = MaterialTheme.typography.labelSmall

@Composable
private fun activityWhoLabel(who: ActivityWho): String = when (who) {
    ActivityWho.MODEL -> stringResource(R.string.gc_activity_who_model)
    ActivityWho.MEDIATOR -> stringResource(R.string.gc_activity_who_mediator)
    ActivityWho.REPAIRER -> stringResource(R.string.gc_activity_who_repairer)
    ActivityWho.EVOLVER -> stringResource(R.string.gc_activity_who_evolver)
}

@Composable
private fun activityResultLabel(result: ActivityResult): String = when (result) {
    ActivityResult.DONE -> stringResource(R.string.gc_activity_result_done)
    ActivityResult.FAILED -> stringResource(R.string.gc_activity_result_failed)
    ActivityResult.SKIPPED -> stringResource(R.string.gc_activity_result_skipped)
    ActivityResult.ROLLED_BACK -> stringResource(R.string.gc_activity_result_rolled_back)
}

/** 结果列的颜色：失败用 `error`（要一眼看见），其余用主色（不做第四套配色）。 */
@Composable
private fun activityResultColor(result: ActivityResult): Color = when (result) {
    ActivityResult.FAILED -> MaterialTheme.colorScheme.error
    ActivityResult.DONE, ActivityResult.SKIPPED, ActivityResult.ROLLED_BACK ->
        MaterialTheme.colorScheme.onSurface
}

/**
 * ⑤ 待办（第五百六十五批）：收起态一行 `待办 N · 在做 M`，点开列列表、再点收回。
 *
 * 三条口径（判据建在上面）：
 * 1. **只画不算**：那一行来自 [TodoBoardRules.header]，行来自 [TodoBoardRules.rows]
 *    （三字段：摘要 / 状态 / 能不能点确认）。本屏一个判断都不加——判据③要的就是"没有第二套口径"；
 * 2. **确认按钮只由人按**：点了走 [launchTodoConfirm]（IO 上追加一行 confirm），
 *    账本历史一行不少；模型够不着这个口（541 判据⑤）；
 * 3. **一次只展开一块**：展开状态是屏幕层的一个 `String?`，切换规则在 [TodoBoardRules.toggle]。
 */
@Composable
private fun TodoSection(
    snapshot: GovSnapshot,
    expanded: Boolean,
    onToggle: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = ShapeCard,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            TodoHeaderLine(counts = snapshot.todoCounts, expanded = expanded, onToggle = onToggle)
            if (expanded) TodoBody(snapshot, onConfirm)
        }
    }
}

/**
 * 收起态那一行（整行可点：点开 / 再点收回）。
 *
 * ⚠️ 高度下限 48dp（本仓触摸目标口径）——这一行就是这块的"入口"，不能做成一句小字。
 */
@Composable
private fun TodoHeaderLine(counts: TodoCounts, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = TodoBoardRules.header(counts),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(
                if (expanded) R.string.gc_todo_collapse else R.string.gc_todo_expand,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 展开态：一条说明（为什么有的点不了确认）＋ 列表；没有条目就如实说"现在没有待办"。 */
@Composable
private fun TodoBody(snapshot: GovSnapshot, onConfirm: (String) -> Unit) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Text(
        text = stringResource(R.string.gc_todo_hint),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
    )
    if (snapshot.todoRows.isEmpty()) {
        Text(
            text = stringResource(R.string.gc_todo_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        return
    }
    snapshot.todoRows.forEach { row -> TodoLine(row, onConfirm) }
}

/** 一行：摘要（吃剩下的宽度）＋状态签＋（只在「已完成」上）「确认」。 */
@Composable
private fun TodoLine(row: TodoRowView, onConfirm: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).padding(end = 6.dp),
        )
        Text(
            text = todoStateLabel(row.state),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (row.canConfirm) {
            TextButton(
                onClick = { onConfirm(row.id) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.gc_todo_confirm))
            }
        }
    }
}

/** 状态签 → 文案（**只查资源，不做判断**：状态在 541 的账本回放里定，本屏只翻译）。 */
@Composable
private fun todoStateLabel(state: TodoState): String = when (state) {
    TodoState.TODO -> stringResource(R.string.gc_todo_state_todo)
    TodoState.MODEL_DONE -> stringResource(R.string.gc_todo_state_done)
    TodoState.COMPLETE -> stringResource(R.string.gc_todo_state_complete)
}

@Composable
private fun LoadingLine() {
    Text(
        text = stringResource(R.string.common_loading),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 「标签 — 值」一行（标签 13sp 副色、值 15sp 主色；小字副注跟在值后面）。 */
@Composable
private fun ReadingLine(label: String, value: String, detail: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (detail.isNotEmpty()) {
            Text(
                text = "  $detail",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 机器键 → 文案（**只查资源，不做判断**：判断在 527 与 [GovernanceCenterRules]）。 */
@Composable
private fun labelOf(key: String): String = when (key) {
    "violation" -> stringResource(R.string.gc_ratio_violation)
    "misjudge" -> stringResource(R.string.gc_ratio_misjudge)
    "intervention" -> stringResource(R.string.gc_ratio_intervention)
    "skip" -> stringResource(R.string.gc_ratio_skip)
    "pass" -> stringResource(R.string.gc_card_pass)
    "card" -> stringResource(R.string.gc_card_card)
    "compress" -> stringResource(R.string.gc_card_compress)
    // 灰度那几格用出口自带的 `cn`（能力名）当标签，键是线的能力名 ⇒ 查不到也照原样显示
    else -> key
}

@Composable
private fun stateLabel(state: String): String = when (state) {
    "active" -> stringResource(R.string.gc_state_active)
    "revoked" -> stringResource(R.string.gc_state_revoked)
    "rotated_out" -> stringResource(R.string.gc_state_rotated)
    else -> state
}
