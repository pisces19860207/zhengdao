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
//   2. **只读**：除"清空小黑板"这一个由**人**按的动作，本屏没有任何写口
//      （不碰账本、不碰队列、不碰 `DecisionTab` / `royalRows`——判据⑤）。
//   3. **不新增导航栈**：全屏 Dialog（与本仓既有浮层同形态），返回键/关闭键一起收，页面栈不变。
//
// 承载路子照本仓既有的全屏浮层（`Surface` ＋ 返回键一并收）：换个 Activity 要动 Manifest，
// 也会把"抽屉里点进去"变成另一套返回栈——本屏不另开一条。
// （2026-11-08 追加：536 已把 528 那屏整体回退掉，本屏的"同形态"参照物只剩 104 那套设置浮层。）
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.example.zhengdao.baopu.agent.BoardRules
import com.example.zhengdao.baopu.agent.BoardStore
import com.example.zhengdao.baopu.agent.GovernanceStateTool
import com.example.zhengdao.baopu.consequence.CredentialStore
import com.example.zhengdao.baopu.consequence.IdentityStore
import com.example.zhengdao.keepalive.CoroutineCrashHandler
import com.example.zhengdao.ui.SectionCard
import com.example.zhengdao.ui.theme.ShapeCard
import java.io.File
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
    /** 首次读盘还没回来：与"读出来是空的"分开（同抽屉那批 264 的口径）。 */
    val ready: Boolean = false,
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

    // 读盘全在 IO 上（264 的口径：抽屉/面板首帧不许占主线程），读回来再一次性换快照。
    LaunchedEffect(tick) {
        snapshot = withContext(Dispatchers.IO) {
            readSnapshot(context.filesDir, controller.workspaceRoot)
        }
    }

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
                    item { ExecutorSection(snapshot) }
                    item { Spacer(Modifier.height(14.dp)) }
                    item {
                        BoardSection(
                            snapshot = snapshot,
                            onClear = { confirmingClear = true },
                        )
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    if (confirmingClear) {
        BoardClearDialog(
            onDismiss = { confirmingClear = false },
            onConfirm = {
                confirmingClear = false
                scope.launch {
                    withContext(Dispatchers.IO) { BoardStore.clear(controller.workspaceRoot) }
                    tick++ // 重读：清完当场变空，不留旧字
                }
            },
        )
    }
}

/** 一次读全（IO 上跑；每一路都自己兜住，缺哪路由界面如实说读不到）。 */
private fun readSnapshot(filesDir: File, wsRoot: File): GovSnapshot {
    // ① 读数：**唯一取数路**——527 出口。不在这里算第二遍（判据②）。
    val root = runCatching {
        val outcome = GovernanceStateTool.run(wsRoot, System.currentTimeMillis())
        (outcome as? com.example.zhengdao.baopu.agent.ToolOutcome.Ok)?.let {
            GovernanceCenterRules.parse(it.text)
        }
    }.getOrNull()
    // ② 执行体另外两格：凭据条数、最近撤销（527 只给 id/state 两格）
    val creds = runCatching { CredentialStore.load(filesDir).size }.getOrDefault(0)
    val revokedAt = runCatching {
        GovernanceCenterRules.lastRevokeAtMs(IdentityStore.load(filesDir))
    }.getOrDefault(0L)
    // ③ 小黑板：只读镜像 ＋ 上次写盘时刻（542：顶上那行「最近更新 HH:mm」只认这两样）
    val board = BoardStore.read(wsRoot)
    val boardUpdatedAtMs = BoardStore.updatedAt(wsRoot)
    return GovSnapshot(
        root = root,
        credentialCount = creds,
        revokedAtMs = revokedAt,
        board = board,
        boardUpdatedAtMs = boardUpdatedAtMs,
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
