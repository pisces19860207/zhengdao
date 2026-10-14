// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据的公开接口：Jetpack Compose（Material 3）官方 API。
//
// P4-1（2026-10-14）：自 TaijiComponents.kt（拆分前 1929 行）按内聚性拆出——
// **只搬不改**，代码逐行原样（含已提交的 P2-10 48dp 触摸目标修复）。
package com.example.zhengdao.ui.taiji

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.oc.ConnectionState
import com.example.zhengdao.oc.TaijiState
import com.example.zhengdao.ui.theme.zhengdaoErrorText
import com.example.zhengdao.ui.util.pressClickable

// ── 顶部栏 ────────────────────────────────────────────────────────────

/**
 * 会话状态栏（**固定不滚**：由 TaijiScreen 放在滚动区之外，见那里的 Column/weight 结构）。
 *
 * [connection]非[ConnectionState.Connected] 时显示状态——**失败必须可见**，
 * 不静默（与项目"M2 内存治理不假装成功"同一原则）。
 *
 * 顶栏三件事：历史入口（☰）/ 会话标题 + 连接状态 · 模型池入口 / 新会话（＋）。
 *
 * ## 2026-10-08 顶栏瘦身（用户反馈）
 * ① 「那个＋号太小」——原实现是 `IconButton` 里放一个字符「＋」，字形约 16dp 且无底色，
 *    在相邻的 ☰ 旁边显得更小、手指也难瞄。现改为 **40dp 实心 tonal 圆钮 + 自绘加号**。
 * ② 原来的第四个按钮「◼（退出）」已**移入左侧抽屉**（见 [HistoryDrawer]）：它做的事是
 *    `repo.close() + onExit()`，即**结束本次会话（关掉 OpenCode 实例）并退出太极**，
 *    与「停止生成」（[ComposerBar] 里随流式状态出现的停止钮）**不是同一件事**。
 *    两者都以「停止」的形态出现，是误解的源头；现在「停止」只剩输入框旁那一处。
 *
 * ## 2026-10-07 布局修复（真机实测的缺陷）
 * v1.0 合并后模型入口曾与标题并排直排主行，模型名实测长达
 * `opencode/longcat-2.5-preview-free`（31 字符）→ 把 `weight(1f)` 的标题列挤到 **0 宽**：
 * 标题整段不可见、「已连接」被折成竖排（`已/连/接`），顶栏实际只剩一个模型名。
 * 现在：**标题独占主行剩余空间**，模型退到副行做成**限宽胶囊**，
 * 长名字最多牺牲自己尾部，标题永远完整。
 */
@Composable
fun SessionBar(
    title: String,
    connection: ConnectionState,
    attempt: Int,
    onHistory: () -> Unit = {},
    onNew: () -> Unit = {},
    currentModelText: String? = null,
    onModelClick: () -> Unit = {},
) {
    // 无障碍播报文本（图标是 Unicode 字形，读屏需 contentDescription 覆盖字形）
    val sessionHistoryDesc = stringResource(R.string.taiji_session_history)
    val newSessionDesc = stringResource(R.string.taiji_new_session)
    // 标题为空时的兜底文案：先取出来，不在 ifEmpty 的内联 lambda 里再调 Composable
    val newSessionLabel = stringResource(R.string.taiji_new_session)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()          // edge-to-edge：顶栏不得被手机状态栏（时间/电量）压住
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onHistory) {
            // 2026-10-08：无障碍。图标是用 Unicode 字符「☰」冒充的，读屏会念成
            // "三条横线"甚至乱码，而不是「会话历史」。contentDescription 覆盖字形播报。
            Text(
                "☰",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { contentDescription = sessionHistoryDesc },
            )
        }
        Column(
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                title.ifEmpty { newSessionLabel },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 2026-10-10 UIUX 审计 P0-1：给 ConnectionLabel 一个 weight(1f, fill = false)，
                // 让 ModelChip 先量、ConnectionLabel 吃剩余 ⇒ 副行整体宽度稳定，
                // 重连/连接中文案超长时只会被省略号裁掉，不会把胶囊挤变形成椭圆。
                ConnectionLabel(
                    connection,
                    attempt,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (!currentModelText.isNullOrBlank()) {
                    // 副行里连接状态与模型之间加分隔点；Idle 时 ConnectionLabel 不渲染，避免孤立圆点
                    if (connection != ConnectionState.Idle) {
                        Text(
                            "·",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                    ModelChip(currentModelText, onModelClick)
                }
            }
        }
        // ＋ 新会话：48dp 实心圆钮 + 自绘加号（2026-10-08，原先只有一个小字符，见 KDoc）。
        // 按钮取 M3 的行内最小触摸目标 48dp（原先 40dp，偏小）；加号字形仍是自绘的固定 20dp
        // （见 PlusGlyph），所以按钮放大**不会**把字形一起撑大。
        // 无障碍：PlusGlyph 是裸 Canvas，默认不带任何语义，读屏只会念"按钮" —— 用
        // contentDescription 明示它是「新会话」（语法与 [SendGlyph]/[StopGlyph] 的用法一致）。
        FilledTonalIconButton(
            onClick = onNew,
            modifier = Modifier.size(48.dp).semantics { contentDescription = newSessionDesc },
        ) { PlusGlyph() }
    }
}

/** 加号图标：自绘（本工程不使用任何第三方图标素材，画法与 [SendGlyph] / [StopGlyph] 同源）。 */
@Composable
private fun PlusGlyph() {
    val tint = LocalContentColor.current
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val sw = 2.2f
        drawLine(tint, Offset(w * 0.50f, h * 0.20f), Offset(w * 0.50f, h * 0.80f), sw, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.20f, h * 0.50f), Offset(w * 0.80f, h * 0.50f), sw, StrokeCap.Round)
    }
}

/**
 * 模型池入口（v1.0 任务一）：**紧凑胶囊**。
 *
 * 宽度硬上限 176dp —— 这是顶栏不被撑爆的关键（见 [SessionBar] 注释）。模型名过长时
 * 省略号收尾，只牺牲自己；点它打开模型池抽屉（[ModelSheet]）。
 */
@Composable
private fun ModelChip(text: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .widthIn(max = 176.dp)
            // ⚠️ 必须先 clip 再 clickable：胶囊是 50% 圆角，涟漪默认按矩形绘制，
            //    不裁会在两个圆头外糊出方角（与首页「环境就绪」卡同一个坑，v1.1.1 阶段 1.3 补齐）。
            .clip(RoundedCornerShape(50))
            .pressClickable(onClick = onClick),
    ) {
        Text(
            shortModelName(text),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // P2-10（issue #17）：补齐 M3 最小触摸目标 48dp。
            // 2026-10-08 走查：上下内边距 2dp → 12dp（原来整枚只有约 20dp 高）。本轮再抬一档：
            // labelSmall 行高约 16dp，竖内边距 12 → 16dp 后整枚正好 48dp；外层
            // defaultMinSize 兜底——字体行高若实测大于 16dp，总高只会更高不会更低
            // （padding 会把约束里的 minHeight 一并扣减、再用外层约束回夹，故
            //  defaultMinSize 必须放在 padding 外层；顺序反了 minHeight 会先被 padding
            //  吃掉，兜底失效、极端情况整块还会涨到 48+32=80dp）。
            // 旧注释「未一步到 48dp：顶栏高度受限，胶囊再厚会把会话标题挤掉」的前提已作废：
            // 同一顶栏里的 ＋ 钮（见 [SessionBar]）本就是 48dp，顶栏总高由它决定；
            // 标题在主行、胶囊在副行，本来就不互相挤压。
            modifier = Modifier
                .defaultMinSize(minHeight = 48.dp)
                .padding(horizontal = 12.dp, vertical = 16.dp),
        )
    }
}

/**
 * 「provider/model」→ 只留 model 段，供顶栏胶囊显示。
 *
 * 手机顶栏宽度有限，`provider` 前缀信息量低于模型名本身（且多为 `opencode`），故剥离。
 * 无「/」或 model 段为空则原样返回。
 * `internal` 供 JVM 单测直接验证（与 [groupSessionsByDay] 同惯例）。
 */
internal fun shortModelName(raw: String): String =
    raw.substringAfterLast('/').takeIf { it.isNotBlank() } ?: raw

@Composable
private fun ConnectionLabel(
    connection: ConnectionState,
    attempt: Int,
    modifier: Modifier = Modifier,
) {
    val (text, color) = when (connection) {
        ConnectionState.Connected ->
            stringResource(R.string.taiji_conn_connected) to MaterialTheme.colorScheme.primary
        ConnectionState.Connecting ->
            stringResource(R.string.taiji_conn_connecting) to MaterialTheme.colorScheme.onSurfaceVariant
        ConnectionState.Reconnecting ->
            // P2-11（issue #17）：错误正文走 zhengdaoErrorText()——colorScheme.error
            // （#FF3B30）白底只有 3.55:1，够不上 WCAG AA 正文 4.5:1。
            stringResource(R.string.taiji_conn_reconnecting, attempt) to zhengdaoErrorText()
        ConnectionState.Idle -> "" to MaterialTheme.colorScheme.onSurface
    }
    if (text.isNotEmpty()) {
        // 2026-10-10 UIUX 审计 P0-1 修：顶栏副行最长的字符串是"○ 已断开，正在重连（第 3 次）"
        // 约 165dp；同伴 ModelChip 有 widthIn(max = 176.dp)。不写 maxLines + 文字不限定权重，
        // 在 360dp 屏上两者挤在一起：胶囊被压窄成椭圆形或整行撑高成两行，把顶栏的视觉重心
        // 推变形。给文字加 weight(1f, false) 让胶囊先量、文字吃剩余；自己再 maxLines=1 + 省略号，
        // 极端情况下顶栏副行不会破形。（TextOverflow 本文件已 import。）
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
    }
}

// ── 连接横幅 ──────────────────────────────────────────────────────────

/**
 * 仅在非 [ConnectionState.Connected] 时出现的横幅（信息重复但不打扰阅读流）。
 *
 * 2026-10-08 走查：原条件 `!= Connected` 把「首次 [ConnectionState.Connecting]」也算成
 * "已断开"，横幅飘红 + 文案「连接已断开，正在重连…」——用户根本没断过，这是骗。
 *
 * 现在区分两种状态：
 * - **[ConnectionState.Connecting]**（首次连接中）：中性色 `tertiaryContainer` + 文案
 *   「正在连接…」，**不给「知道了」**——用户不能忽略还没连上这个事实，否则会以为 UI 卡死。
 * - **[ConnectionState.Reconnecting]** / **[ConnectionState.Idle]**：红色 `errorContainer`
 *   + 文案「连接已断开，正在重连…」（或 [TaijiState.lastError]），**给「知道了」**允许用户
 *   主动消除错误提示。
 *
 * [ConnectionState.Connected] 直接返回 → 不渲染。
 */
@Composable
fun ConnectionBanner(state: TaijiState, onDismiss: () -> Unit) {
    val isVisible = state.connection != ConnectionState.Connected
    AnimatedVisibility(visible = isVisible) {
        val isConnecting = state.connection == ConnectionState.Connecting
        val bg = if (isConnecting)
            MaterialTheme.colorScheme.tertiaryContainer
        else
            MaterialTheme.colorScheme.errorContainer
        val fg = if (isConnecting)
            MaterialTheme.colorScheme.onTertiaryContainer
        else
            MaterialTheme.colorScheme.onErrorContainer
        val msg = when {
            isConnecting -> stringResource(R.string.taiji_banner_connecting)
            state.lastError != null -> state.lastError
            state.connection == ConnectionState.Idle -> stringResource(R.string.taiji_banner_idle)
            else -> stringResource(R.string.taiji_banner_reconnecting)
        }
        // 「首次连接中」不给「知道了」——否则用户点了之后以为连接已建立，发消息必失败
        val showDismiss = !isConnecting

        Surface(color = bg, modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = fg,
                    modifier = Modifier.weight(1f),
                )
                if (showDismiss) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_got_it)) }
                }
            }
        }
    }
}

/**
 * 「Agent 运行失败」横幅（E-054）。
 *
 * 与 [ConnectionBanner] **必须分开**（真机 2026-10-09 实测的坑）：后者只在
 * `connection != Connected` 时渲染（见其 `isVisible`），而 Agent 运行失败发生在连接
 * **完全正常**的时刻 —— 把原因塞进 `lastError` 会**一个字都不显示**，这正是本次
 * 「发消息后界面永远空着」最难归因的地方。
 *
 * 内容：人话原因 + 「重试」（原样重发上次提示词，仅当确有可重发内容）+ 「知道了」。
 */
@Composable
fun RunFailureBanner(
    state: TaijiState,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val reason = state.runFailure ?: return
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.taiji_agent_run_failed, reason),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            // 有可重试内容、且当前没在跑，才给「重试」——否则是个骗人的按钮
            if (state.lastPrompt != null && !state.isStreaming) {
                TextButton(onClick = onRetry) { Text(stringResource(R.string.common_retry)) }
            }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_got_it)) }
        }
    }
}
